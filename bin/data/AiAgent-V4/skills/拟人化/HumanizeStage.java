import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.ext.Outbound;
import sair.v4.ext.OutboundStage;
import sair.v4.kit.Str;
import sair.v4.skill.Host;

/**
 * 拟人化：把模型那股「AI 味」在发送前洗掉 —— <b>只去头尾那几种套话 + 压连续空行 + 去首尾空白</b>，
 * 不做任何"改写语气"的事，也<b>不硬截断</b>（超长交给分段）。
 *
 * <h3>挂在哪</h3>
 * <p>{@link OutboundStage}，{@link #order()} = {@code 10}。位置在<b>分段之后、发送之前</b>，
 * 所以是<b>逐条</b>看这一条长什么样（{@link #transform(Outbound)}），不是"整条回复"。</p>
 *
 * <h3>数字与正则出处（V3 原样，见 notes/feel-plugins.md 第二节 1）</h3>
 * <p>{@code QQAgentBridge.postProcessReplyText}（{@code QQAgentBridge.java:239-247}）：</p>
 * <pre>
 *   String t = text.trim();
 *   t = t.replaceAll("(?i)^(作为\\s*AI|作为人工智能|根据我的理解|希望以上信息对你有帮助)[，。:：\\s]*", "");
 *   t = t.replaceAll("(?i)(希望以上信息对你有帮助|如果还有问题可以继续问我|有其他问题随时问我)[。！!\\s]*$", "");
 *   t = t.replaceAll("\\n{3,}", "\n\n").trim();
 *   // 不再硬截断超长回复：分段发送负责完整拆条，确保「有多少输出多少」
 * </pre>
 * <p>三条一字不改地落成 {@code prompt.md} 的三个键（「去头套话正则」/「去尾套话正则」/「最大连续空行」），
 * Java 里的常量只是文件读不到时的兜底。</p>
 *
 * <h3>口径</h3>
 * <ul>
 *   <li>返回 {@code null} = <b>不改</b>（"没意见"的默认）：洗完之后跟进来时一模一样就返回 null，
 *       免得把"没变化"记成一次改动；</li>
 *   <li>洗成空串 = 这一条会被基板丢掉（与"清洗之后什么都不剩"同一个意思），这里不拦；</li>
 *   <li>正则写坏 / 文件读不到 / {@code m.host()} 为 null → 用兜底正则；兜底也坏 → 原文返回（绝不抛异常）；</li>
 *   <li>V3 的 {@code postProcessReplyText} 第二个参数（是否群聊）在 V3 里**根本没用到**，
 *       所以这里也不按群/私聊分叉。</li>
 * </ul>
 */
public class HumanizeStage implements OutboundStage {

    // ==================== 兜底默认值（真正生效的值在 prompt.md） ====================

    /** V3 原样：去头套话。 */
    private static final String DEF_HEAD =
            "(?i)^(作为\\s*AI|作为人工智能|根据我的理解|希望以上信息对你有帮助)[，。:：\\s]*";
    /** V3 原样：去尾套话。 */
    private static final String DEF_TAIL =
            "(?i)(希望以上信息对你有帮助|如果还有问题可以继续问我|有其他问题随时问我)[。！!\\s]*$";
    /** V3 原样：压连续空行（{@code \n{3,}} → {@code \n\n}，即最多 2 个连续换行）。 */
    private static final int DEF_MAX_NL = 2;

    // ==================== OutboundStage ====================

    @Override
    public int order() {
        return 10;
    }

    @Override
    public String transform(Outbound m) {
        try {
            if (m == null) return null;
            String raw = m.text();
            if (raw == null || raw.isEmpty()) return null;          // 空串没什么可洗的

            Map<String, List<String>> c = Cfg.of(m.host());
            if (!flag(c, "开关", true)) return null;

            String t = raw.trim();
            t = sub(t, one(c, "去头套话正则", DEF_HEAD));
            t = sub(t, one(c, "去尾套话正则", DEF_TAIL));
            // 外加的套话正则：一行一条，逐条做全文替换（默认没有 —— 出厂口径就是 V3 那三条）
            for (String extra : all(c, "套话正则")) {
                if (!Str.blank(extra)) t = sub(t, extra);
            }
            int maxNl = num(c, "最大连续空行", DEF_MAX_NL);
            if (maxNl < 1) maxNl = 1;
            t = squeeze(t, maxNl);

            if (t.equals(raw)) return null;                         // 没变化 = 不改
            return t;
        } catch (Throwable t) {
            return null;                                            // 插件绝不拖垮一条发送
        }
    }

    // ==================== 清洗 ====================

    /** 一条正则的替换；正则写坏就<b>跳过这一条</b>（不影响其它几条，也不抛）。 */
    private static String sub(String t, String regex) {
        if (Str.blank(regex) || t == null) return t;
        try {
            return t.replaceAll(regex, "");
        } catch (Throwable bad) {
            return t;
        }
    }

    /** 连续换行压到最多 {@code maxNl} 个（V3：{@code \n{3,}} → {@code \n\n}，maxNl=2）。 */
    private static String squeeze(String t, int maxNl) {
        if (t == null) return "";
        StringBuilder nl = new StringBuilder();
        for (int i = 0; i < maxNl; i++) nl.append('\n');
        try {
            return t.replaceAll("\\n{" + (maxNl + 1) + ",}", nl.toString()).trim();
        } catch (Throwable bad) {
            return t.trim();
        }
    }

    // ==================== prompt.md（惰性缓存 + 按 mtime 失效） ====================

    /**
     * 本插件目录下 {@code prompt.md} 的键值表。
     * <p>读不到文件 / {@code m.host()} 为 null（探针直接注册的合成 stage 就是 null）→ 返回空表
     * （调用方逐项落到兜底默认值）。缓存按「文件 + mtime」失效：每条消息不读盘，改了文件下一条生效。</p>
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
                    int a = s.indexOf(':');
                    int b = s.indexOf('：');
                    int k = (a < 0) ? b : (b < 0 ? a : Math.min(a, b));
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

    private static List<String> all(Map<String, List<String>> c, String key) {
        if (c == null) return Collections.emptyList();
        List<String> l = c.get(key);
        return l == null ? Collections.<String>emptyList() : l;
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
