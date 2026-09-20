import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import sair.v4.ext.BuildContext;
import sair.v4.ext.ContextProvider;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.FavorView;
import sair.v4.skill.Host;
import sair.v4.store.Store;

/**
 * 用户画像：把「对这个人的印象」注入上下文（五维 + 好感档 + 态度）。
 *
 * <h3>挂在哪</h3>
 * <ul>
 *   <li>{@link ContextProvider}，{@link #stable()} = {@code true}（<b>稳定段</b>）——
 *       内容只由「这个人是谁 + 库里已有的印象 + 好感度」决定，<b>同输入逐字节不变</b>：
 *       不放时间戳、不放随机数、不放本轮正文（段位口径见 {@code ContextProvider} 类注释：
 *       稳定段排在同一条系统提示词之后、事实块之前，一起构成可复用的前缀）；</li>
 *   <li>{@link #order()} = {@code 20}；{@link #maxChars()} = {@code prompt.md} 的「预算」（默认 600）。</li>
 * </ul>
 *
 * <h3>数据从哪来（只读）</h3>
 * <p>① 印象：{@code memory} 库里 {@code scope=user, scope_id=<这个人的 QQ>} 且
 * {@code source=印象蒸馏}（或 {@code tags} 含印象标签）的行 —— 一行一条「维度: 值」；
 * ② 好感档：{@code h.favor()}（只读视图，本插件<b>改不了</b>好感度）；③ 态度：印象行里标签命中「态度键」的那条。
 * <b>不建库、不写库、不自己开库、不写 SQL</b>；{@code BuildContext.subagent()} 为真时同样没有写路径
 * （本插件整体没有副作用）。</p>
 *
 * <h3>数字出处（V3 原样，见 notes/feel-plugins.md 第二节）</h3>
 * <ul>
 *   <li>五维：{@code tasks/impression-distill.md} 的 emotionStability / interests / speakingStyle /
 *       honesty / imageHabit（+ impressionLevel = 印象好坏）；</li>
 *   <li>行模板 {@code - {label}: {value}}：{@code context/impression-person-line.md} 原样；</li>
 *   <li>标题：{@code labels/qq.md} 的 {@code title.user-impression}=对该用户的印象
 *       （{@code PromptManager.heading} 会补上 {@code ## }）；</li>
 *   <li>V3 的 {@code field.*} 短标签（情绪状态/兴趣爱好/说话风格/虚实态度/图片习惯）当作<b>别名</b>收下。</li>
 * </ul>
 *
 * <h3>文案与阈值</h3>
 * <p>全在本插件目录的 {@code prompt.md}（{@code 键: 值}）；下面的常量只是<b>文件读不到时的兜底</b>。
 * {@code host()} 可能为 null（装配期绑不上）——那就用兜底值，绝不抛异常。</p>
 */
public class UserProfileProvider implements ContextProvider {

    // ==================== 兜底默认值（真正生效的值在 prompt.md） ====================

    /** 记忆库（业务表名，由「记忆」插件声明；不是文案）。 */
    private static final String LIB = "memory";
    private static final String S_USER = "user";

    private static final String DEF_TITLE = "## 对该用户的印象";
    private static final String DEF_SUBJECT = "对象：{name}（QQ {qq}）";
    private static final String DEF_LINE = "- {label}: {value}";
    private static final String DEF_RAW = "- {text}";
    private static final String DEF_DIMS = "情绪稳定,兴趣,说话风格,诚实度,发图习惯";
    private static final String DEF_ALIAS =
            "emotionStability=情绪稳定,情绪状态=情绪稳定,interests=兴趣,兴趣爱好=兴趣,"
            + "speakingStyle=说话风格,style=说话风格,honesty=诚实度,虚实态度=诚实度,"
            + "imageHabit=发图习惯,图片习惯=发图习惯,image-habit=发图习惯";
    private static final String DEF_ATT_KEYS = "态度,关系,印象";
    private static final String DEF_ATT_LINE = "- 态度: {value}";
    private static final String DEF_SRC = "印象蒸馏";
    private static final String DEF_TAG = "印象";
    private static final String DEF_FAVOR = "- 好感: {level}（{value}）";
    private static final String DEF_UNNAMED = "该用户";
    private static final int DEF_N = 12;
    private static final int DEF_SAMPLE = 60;
    private static final int DEF_BUDGET = 600;

    /**
     * {@link #maxChars()} 的签名里没有 Host（基板调它时不给上下文），
     * 所以只能把「最近一次从 prompt.md 读到的预算」记下来 —— 第一次调用之前是兜底 600。
     */
    private volatile int budget = DEF_BUDGET;

    // ==================== ContextProvider ====================

    @Override
    public boolean stable() {
        return true;               // 稳定段：同输入逐字节不变（前缀缓存友好）
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public int maxChars() {
        int b = budget;
        return b > 0 ? b : DEF_BUDGET;
    }

    /** 没有具体的人（定时任务落点、没有调用者）就没有"画像"可言。 */
    @Override
    public boolean appliesTo(BuildContext b) {
        try {
            return b != null && b.qq() > 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String build(BuildContext b) {
        try {
            if (b == null) return "";
            Host h = b.host();
            Map<String, List<String>> c = Cfg.of(h);
            budget = num(c, "预算", DEF_BUDGET);
            if (!flag(c, "开关", true)) return "";
            long qq = b.qq();
            if (qq <= 0L) return "";

            String[] dims = split(one(c, "维度", DEF_DIMS));
            Map<String, String> alias = pairs(one(c, "维度别名", DEF_ALIAS));
            LinkedHashSet<String> attKeys = new LinkedHashSet<String>();
            for (String k : split(one(c, "态度键", DEF_ATT_KEYS))) attKeys.add(k);

            LinkedHashMap<String, String> found = new LinkedHashMap<String, String>();   // 维度 → 值
            LinkedHashMap<String, String> extra = new LinkedHashMap<String, String>();   // 补充（标签: 值）
            List<String> raws = new ArrayList<String>();                                 // 补充（无冒号原文）
            String[] att = new String[1];                                                // 态度（先到者胜）

            Store s = store(h);
            // 权限口径：本 provider 是纯扩展点（md 里没有 tool:、不提供工具）⇒ 没有任何 op 要判 ——
            // 技能管控表管的是工具（模型能调的那些动作），而扩展点跑在 SYSTEM 主体上（她自己的自主行为，恒放行）。
            List<JsonObject> rows = s == null ? null
                    : list(s, qq, num(c, "印象取样条数", DEF_SAMPLE));
            if (rows != null) {
                String[] srcs = split(one(c, "印象来源", DEF_SRC));
                String tag = one(c, "印象标签", DEF_TAG);
                int max = num(c, "印象条数", DEF_N);
                if (max <= 0) max = DEF_N;
                int used = 0;
                for (JsonObject r : rows) {
                    if (used >= max) break;
                    if (!qualified(r, srcs, tag)) continue;
                    if (parse(J.s(r, "content", ""), dims, alias, attKeys,
                            found, extra, raws, att)) used++;
                }
            }
            String attitude = att[0];
            // 好感档（主人默认不显示；零好感默认不显示 —— 不然每个人头上都挂一行 0）
            String favorLine = "";
            if (!(b.master() && !flag(c, "主人显示好感", false))) {
                double v = favor(h, qq);
                if (v > 0.0d || flag(c, "显示零好感", false)) {
                    favorLine = fill(one(c, "好感档模板", DEF_FAVOR),
                            "level", favorLevel(h, qq, v),
                            "value", num2(v),
                            "qq", String.valueOf(qq),
                            "name", name(b, c));
                }
            }

            List<String> lines = new ArrayList<String>();
            for (String d : dims) {
                if (d.isEmpty()) continue;
                String v = found.get(d);
                if (Str.blank(v)) continue;
                lines.add(fill(one(c, "行模板", DEF_LINE), "label", d, "value", v));
            }
            if (!favorLine.trim().isEmpty()) lines.add(favorLine);
            if (!Str.blank(attitude)) {
                lines.add(fill(one(c, "态度行模板", DEF_ATT_LINE), "value", attitude,
                        "qq", String.valueOf(qq), "name", name(b, c)));
            }
            for (Map.Entry<String, String> e : extra.entrySet()) {
                lines.add(fill(one(c, "行模板", DEF_LINE), "label", e.getKey(), "value", e.getValue()));
            }
            String rawTpl = one(c, "原文模板", DEF_RAW);
            for (String t : raws) lines.add(fill(rawTpl, "text", t, "value", t));

            if (lines.isEmpty()) return "";              // 什么都没读到 → 这一段不出现

            StringBuilder sb = new StringBuilder();
            String title = one(c, "标题", DEF_TITLE);
            if (!Str.blank(title)) sb.append(title.trim()).append("\n");
            String subject = fill(one(c, "对象行", DEF_SUBJECT),
                    "name", name(b, c), "qq", String.valueOf(qq));
            if (!Str.blank(subject)) sb.append(subject.trim()).append("\n");
            for (String l : lines) sb.append(l).append("\n");
            return sb.toString();
        } catch (Throwable t) {
            return "";                                   // 插件绝不拖垮一轮对话
        }
    }

    // ==================== 解析 ====================

    /**
     * 把一条印象记忆的正文解析进三个桶。
     *
     * <p>约定（写在 prompt.md 的备注里）：一行一条，{@code 标签: 值}；
     * 标签命中「维度」（或它的别名）→ 进维度；命中「态度键」→ 进态度；
     * 其它带冒号的 → 进补充；<b>没有冒号的整行</b> → 进原文补充。
     * 行按「新 → 老」遍历，同一个键<b>先到者胜</b>（新的覆盖旧的）。</p>
     *
     * @return 这一行有没有产出内容（用来数「印象条数」）
     */
    private static boolean parse(String content, String[] dims, Map<String, String> alias,
                                 LinkedHashSet<String> attKeys,
                                 LinkedHashMap<String, String> found,
                                 LinkedHashMap<String, String> extra,
                                 List<String> raws, String[] att) {
        if (Str.blank(content)) return false;
        boolean any = false;
        String[] lines = content.split("\n");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("-") || line.startsWith("*") || line.startsWith("\u2022")) {
                line = line.substring(1).trim();
            }
            if (line.isEmpty()) continue;
            int k = colon(line);
            if (k <= 0 || k > 16) {                       // 没有冒号（或不像标签）→ 原文
                if (!raws.contains(line)) raws.add(line);
                any = true;
                continue;
            }
            String label = line.substring(0, k).trim();
            String value = line.substring(k + 1).trim();
            if (label.isEmpty()) {
                if (!raws.contains(line)) raws.add(line);
                any = true;
                continue;
            }
            String dim = null;
            for (String d : dims) {
                if (d != null && d.equals(label)) {
                    dim = d;
                    break;
                }
            }
            if (dim == null) dim = alias.get(label);
            if (dim != null) {
                if (!found.containsKey(dim)) found.put(dim, value);
                any = true;
                continue;
            }
            if (attKeys.contains(label)) {
                if (att[0] == null) att[0] = value;
                any = true;
                continue;
            }
            if (!extra.containsKey(label)) extra.put(label, value);
            any = true;
        }
        return any;
    }

    // ==================== 只读能力面 ====================

    private static List<JsonObject> list(Store s, long qq, int limit) {
        try {
            JsonObject f = new JsonObject();
            f.addProperty("scope", S_USER);
            f.addProperty("scope_id", String.valueOf(qq));    // scope_id 列是 TEXT
            return s.list(LIB, f, limit, "ts DESC");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这条记忆算不算「印象」：source 命中印象来源，或 tags 含印象标签。 */
    private static boolean qualified(JsonObject r, String[] srcs, String tag) {
        if (r == null) return false;
        String src = J.s(r, "source", "");
        for (String x : srcs) {
            if (Str.blank(x)) continue;
            if (src.equals(x) || src.contains(x)) return true;
        }
        if (!Str.blank(tag)) {
            String tags = J.s(r, "tags", "");
            if (tags.contains(tag)) return true;
        }
        return false;
    }

    private static Store store(Host h) {
        if (h == null) return null;
        try {
            return h.store();
        } catch (Throwable t) {
            return null;
        }
    }

    private static double favor(Host h, long qq) {
        if (h == null) return 0.0d;
        try {
            FavorView f = h.favor();
            return f == null ? 0.0d : f.of(qq);
        } catch (Throwable t) {
            return 0.0d;
        }
    }

    private static String favorLevel(Host h, long qq, double v) {
        if (h == null) return "";
        try {
            FavorView f = h.favor();
            if (f == null) return "";
            String s = f.levelName(v);
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String name(BuildContext b, Map<String, List<String>> c) {
        try {
            String n = b.caller() == null ? "" : b.caller().name();
            return Str.blank(n) ? one(c, "无名对象", DEF_UNNAMED) : n;
        } catch (Throwable t) {
            return one(c, "无名对象", DEF_UNNAMED);
        }
    }

    // ==================== 小工具 ====================

    /** 好感数值：整数就不带小数点，否则一位小数（避免把 123.456789 印进上下文）。 */
    private static String num2(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 10.0d) / 10.0d);
    }

    private static int colon(String s) {
        int a = s.indexOf(':');
        int b = s.indexOf('\uff1a');
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private static String[] split(String s) {
        if (Str.blank(s)) return new String[0];
        String[] parts = s.split("[,，]");
        List<String> out = new ArrayList<String>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[out.size()]);
    }

    /** {@code 别名=正式名} 的逗号表 → 映射。 */
    private static Map<String, String> pairs(String s) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String one : split(s)) {
            int k = one.indexOf('=');
            if (k <= 0) continue;
            String a = one.substring(0, k).trim();
            String b = one.substring(k + 1).trim();
            if (!a.isEmpty() && !b.isEmpty()) m.put(a, b);
        }
        return m;
    }

    /** {@code {键}} 字面替换（不用正则，模板里带 {@code $} 也不怕）。 */
    private static String fill(String tpl, String... kv) {
        String s = tpl == null ? "" : tpl;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = kv[i];
            String v = kv[i + 1];
            s = s.replace("{" + k + "}", v == null ? "" : v);
        }
        return s;
    }

    // ==================== prompt.md（惰性缓存 + 按 mtime 失效） ====================

    /**
     * 本插件目录下 {@code prompt.md} 的键值表。
     * <p>读不到文件 / {@code host()} 为 null → 返回空表（调用方逐项落到兜底默认值）。
     * 缓存按「文件 + mtime」失效：每轮不读盘，改了文件下一轮生效。</p>
     */
    private static final class Cfg {

        static final Map<String, List<String>> EMPTY = Collections.emptyMap();

        private static File file;
        private static long stamp = -1L;
        private static Map<String, List<String>> kv = new LinkedHashMap<String, List<String>>();

        static synchronized Map<String, List<String>> of(Host h) {
            if (h == null) return EMPTY;
            try {
                File dir = h.skillDir();
                if (dir == null) return EMPTY;
                File f = new File(dir, "prompt.md");
                boolean exists = f.isFile();
                long m = exists ? f.lastModified() : 0L;
                if (file != null && file.equals(f) && m == stamp) return kv;
                Map<String, List<String>> next = exists ? parse(f) : new LinkedHashMap<String, List<String>>();
                file = f;
                stamp = m;
                kv = next;
                return next;
            } catch (Throwable t) {
                return EMPTY;
            }
        }

        /** {@code 键: 值} 一行一条；{@code #} 开头是注释；行内第一个（半角/全角）冒号是分隔符。 */
        private static Map<String, List<String>> parse(File f) {
            Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty() && line.charAt(0) == '\ufeff') line = line.substring(1);
                    String s = line.trim();
                    if (s.isEmpty() || s.charAt(0) == '#') continue;
                    int k = colon(s);
                    if (k <= 0) continue;
                    String key = s.substring(0, k).trim();
                    String val = s.substring(k + 1).trim();
                    if (key.isEmpty()) continue;
                    List<String> one = m.get(key);
                    if (one == null) {
                        one = new ArrayList<String>();
                        m.put(key, one);
                    }
                    one.add(val);
                }
            } catch (Throwable ignored) {
                // 读失败 = 空表 = 全用兜底值
            } finally {
                if (r != null) {
                    try {
                        r.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return m;
        }

        private Cfg() {}
    }

    private static String one(Map<String, List<String>> c, String key, String def) {
        if (c == null) return def;
        List<String> l = c.get(key);
        if (l == null || l.isEmpty()) return def;
        String v = l.get(0);
        return v == null ? def : v;
    }

    private static int num(Map<String, List<String>> c, String key, int def) {
        String v = one(c, key, null);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean flag(Map<String, List<String>> c, String key, boolean def) {
        String v = one(c, key, null);
        if (v == null) return def;
        String s = v.trim().toLowerCase();
        if (s.isEmpty()) return def;
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")
                || s.equals("开") || s.equals("是")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")
                || s.equals("关") || s.equals("否")) return false;
        return def;
    }
}
