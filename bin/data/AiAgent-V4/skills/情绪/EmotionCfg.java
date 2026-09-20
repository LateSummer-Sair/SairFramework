import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.v4.kit.Str;
import sair.v4.skill.Host;

/**
 * 「情绪」插件的配置面：<b>本插件唯一的文案/阈值来源是同目录的 {@code prompt.md}</b>。
 *
 * <p>这一层只做三件事，一个字的业务判断都不做：</p>
 * <ol>
 *   <li><b>读</b>：{@code 键: 值} 一行一条（{@code #} 注释、全角冒号也认），
 *       惰性缓存 + 按「文件 + mtime + 字节数」失效 —— 改完保存，下一条消息/下一轮就生效；</li>
 *   <li><b>解析</b>成两类声明，供 {@link EmotionState} 与 {@link EmotionProvider} 直接取用：
 *     <ul>
 *       <li>{@link Rule}：{@code 情绪-<名>: 词表 => 效果} / {@code 触发-<名>: 词表 => 效果} /
 *           {@code 正则-<名>: 正则 => 效果}（怎么算、效果怎么用见 prompt.md 的说明）；</li>
 *       <li>{@link Level}：{@code <族>-<组>-<档名>: 条件 | 文案}（心情/表情/态度/关系/语气五族，
 *           阈值与文案全在文件里，Java 只留兜底默认值）。</li>
 *     </ul>
 *   </li>
 *   <li><b>填</b>：{@link #fill} 做 {@code {键}} 字面替换，并支持
 *       {@code [ ... ]} <b>可选组</b>：组里任一占位符取到空值，整个组连着方括号一起消失
 *       （一行段里"关系/语气/对方情绪"没内容时不会留下「关系：；」这种残渣）。</li>
 * </ol>
 *
 * <p><b>{@code Host} 可能为 null</b>（装配期绑不上）：那时用<b>最近一次</b>读到的配置；
 * 一次都没读到过就用空配置 —— 所有取值回落到 Java 里的兜底默认值。<b>任何情况都不抛异常。</b></p>
 */
public final class EmotionCfg {

    /** 技能名（= 文件夹名 = md 名），也是 prompt.md 的定位依据。 */
    public static final String NAME = "情绪";

    /** 兜底默认值（真正生效的值在 prompt.md；这里只是"文件读不到时"的下限）。
     *  <p>预算与 prompt.md 的出厂值<b>同源</b>（v2 = 1000；情绪块现在最多<b>六行</b>
     *  〔事实 / 语气 / 范例 / 边界 / 自报 / 覆盖〕，见 prompt.md 的「预算」那一段）。
     *  <b>改这一处必须同时改 prompt.md 的那一行</b> —— 否则"装配前"与"装配后"会读到两个上限。</p> */
    public static final int DEF_BUDGET = 1000;
    public static final double DEF_DECAY_MIN = 1.0d;
    public static final double DEF_ANGER_DECAY = 2.0d;
    public static final double DEF_SAD_DECAY = 1.5d;
    public static final double DEF_ANGER_MAX = 100.0d;
    public static final double DEF_SAD_MAX = 100.0d;
    public static final int DEF_AFF_MAX = 1000;
    public static final int DEF_AFF_MIN = -100;
    public static final int DEF_DAILY_AFF = 2;
    public static final int DEF_BATCH = 500;
    public static final String DEF_CALM = "平静";
    public static final String DEF_WHO = "这个人";

    /** 占位符 {@code {xxx}}（可选组里用它判断"这一段有没有内容"）。 */
    private static final Pattern PH = Pattern.compile("\\{([^{}]*)\\}");

    // ================================================================ 声明

    /** 一条「词/正则 → 效果」规则。 */
    public static final class Rule {
        /** 规则名（{@code 情绪-安慰} 的「安慰」；也用作情绪标签的默认值）。 */
        public final String name;
        /** 哪一族：{@code 情绪}（先到者胜）/ {@code 触发}（命中即生效）/ {@code 正则}。 */
        public final String kind;
        /** 词表（{@code 触发-}）或正则源码（{@code 正则-}）。 */
        public final List<String> words;
        /** 是否正则（{@code 正则-} 前缀）。 */
        public final boolean regex;
        /** 效果 token（原样保留，便于诊断）。 */
        public final List<String> effects;
        /** 编译好的正则（regex=false 时为 null；编译失败也为 null，这条规则作废）。 */
        public final Pattern pattern;

        // ---- 效果（解析后的数值；NaN / null = 这条规则不改这一项） ----
        public final double angryAdd;
        public final double angrySet;
        public final double sadAdd;
        public final double sadSet;
        public final int affAdd;
        public final int affSet;
        /** 显式情绪标签（{@code 情绪=X}），null = 用规则名。 */
        public final String mood;
        /** {@code 每日}：这条命中时算一次"当天交流"（亲疏按「每日亲疏」加一次）。 */
        public final boolean daily;
        /** {@code 恋爱=lover|ex|betrayer|none}（目标状态），null = 不动恋爱状态。 */
        public final String romance;
        /** 附加条件（{@code 条件=亲疏>=200}），null = 无条件<b>或</b>条件看不懂（两者靠 {@link #hasGate} 分开）。 */
        public final Cond gate;
        /**
         * 这一行的效果词里<b>有没有写过</b> {@code 条件=}。
         *
         * <p>存在的唯一理由：{@link #parseCond} 对"没写条件"与"条件看不懂"<b>都返回 {@code null}</b>，
         * 只靠 {@code gate == null} 分不出这两种情况 —— 而它们的失败方向必须相反
         * （没写 = 无条件成立；写坏了 = 不成立）。见 {@link #gate}。</p>
         */
        public final boolean hasGate;

        Rule(String kind, String name, List<String> words, boolean regex, String effRaw) {
            this.kind = kind;
            this.name = name;
            this.words = words;
            this.regex = regex;
            List<String> eff = tokens(effRaw);       // 效果词是**空格/逗号**分隔的，不是一个词
            this.effects = eff;
            Pattern p = null;
            if (regex && !words.isEmpty()) {
                try {
                    p = Pattern.compile(words.get(0), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                } catch (Throwable bad) {
                    p = null;                                  // 正则写坏了：这条规则不生效，不炸
                }
            }
            this.pattern = p;

            double aa = 0, as = Double.NaN, sa = 0, ss = Double.NaN;
            int fAdd = 0, fSet = Integer.MIN_VALUE;
            String md = null, rm = null;
            Cond g = null;
            boolean dy = false;
            boolean hasGate = false;       // 效果词里出现过「条件」⇒ 见 gate()（fail-closed）
            for (String e : eff) {
                if ("每日".equals(e)) { dy = true; continue; }
                int k = opIndex(e);
                if (k <= 0) {
                    // 「条件」两个字孤零零地出现（连运算符都没有）也是"写了条件"⇒ 按看不懂处理（fail-closed）。
                    // 其余读不出运算符的 token 在这里一律作废，照旧跳过。
                    if (e.startsWith("条件")) hasGate = true;
                    continue;
                }
                String dim = e.substring(0, k).trim();
                String op = String.valueOf(e.charAt(k));
                String v = e.substring(k + 1).trim();
                double num = numOf(v, Double.NaN);
                if ("怒".equals(dim)) {
                    if (Double.isNaN(num)) continue;             // 数值读不出来：这个词作废
                    if ("=".equals(op)) as = num; else if ("+".equals(op)) aa += num; else if ("-".equals(op)) aa -= num;
                } else if ("悲".equals(dim)) {
                    if (Double.isNaN(num)) continue;
                    if ("=".equals(op)) ss = num; else if ("+".equals(op)) sa += num; else if ("-".equals(op)) sa -= num;
                } else if ("亲疏".equals(dim)) {
                    if (Double.isNaN(num)) continue;
                    int n = (int) Math.round(num);
                    if ("=".equals(op)) fSet = n; else if ("+".equals(op)) fAdd += n; else if ("-".equals(op)) fAdd -= n;
                } else if ("情绪".equals(dim)) {
                    md = v;
                } else if ("恋爱".equals(dim)) {
                    rm = v;
                } else if ("条件".equals(dim)) {
                    g = parseCond(v);
                    hasGate = true;
                } else if (e.startsWith("条件")) {
                    // 「条件=」写坏了（dim 里多带了字符，例如漏了等号写成「条件亲疏>=200」）
                    // ⇒ 按"写了但看不懂"处理（见 gate()：fail-closed），不许静默退化成"无条件生效"。
                    g = null;
                    hasGate = true;
                }
            }
            this.angryAdd = aa;
            this.angrySet = as;
            this.sadAdd = sa;
            this.sadSet = ss;
            this.affAdd = fAdd;
            this.affSet = fSet;
            this.mood = md;
            this.daily = dy;
            this.romance = rm;
            this.gate = g;
            this.hasGate = hasGate;
        }

        /**
         * 附加条件成立吗 —— <b>条件层一律 fail-closed</b>，"没有条件"与"条件看不懂"必须分开：
         *
         * <ul>
         *   <li>这一行的效果词里<b>根本没写</b> {@code 条件=} ⇒ 无条件成立（{@code true}）；</li>
         *   <li>写了 {@code 条件=} 但 {@link #parseCond} 看不懂（返回 {@code null}）⇒ <b>不成立</b>
         *       —— 宁可这条规则不动，也<b>不许</b>"条件写坏了反而对每一条消息都生效"。</li>
         * </ul>
         *
         * <p>这与同文件 {@link Level#match}（条件看不懂 = 不成立，真 fail-closed）<b>是同一口径</b>：
         * 条件层只有这一条失败方向。</p>
         *
         * <p><b>改这一段之前发生过什么</b>：老写法是
         * {@code return gate == null || gate.match(...)}，而 {@code parseCond} 对"没写"与"看不懂"
         * <b>都</b>返回 {@code null} ⇒ 一个写坏的 {@code 条件=} 会让这条规则<b>无条件生效</b>
         * （fail-open）—— 与这句 javadoc、与 {@code Level} 层都相反，且<b>完全静默</b>。
         * 现在靠 {@link #hasGate} 把两种 {@code null} 分开。</p>
         */
        public boolean gate(double angry, double sad, int aff, String romance) {
            if (!hasGate) return true;                  // 没写「条件=」⇒ 无条件成立
            if (gate == null) return false;             // 写了但看不懂 ⇒ 不成立（fail-closed，与 Level 层同口径）
            return gate.match(angry, sad, aff, romance);
        }

        /** 这条规则命中吗（{@code 触发-} 子串、{@code 正则-} find，都忽略大小写）。 */
        public boolean matches(String text) {
            if (Str.blank(text)) return false;
            if (regex) {
                if (pattern == null) return false;
                try {
                    return pattern.matcher(text).find();
                } catch (Throwable t) {
                    return false;
                }
            }
            String low = Str.lower(text);
            for (String w : words) {
                if (Str.blank(w)) continue;
                if (low.indexOf(Str.lower(w)) >= 0) return true;
            }
            return false;
        }

        @Override
        public String toString() { return kind + "-" + name + (regex ? "(正则)" : ""); }
    }

    /** 一条「条件 → 文案」档（心情/表情/态度/关系/语气）。 */
    public static final class Level {
        /** 哪一族：{@code 心情} / {@code 表情} / {@code 态度} / {@code 关系} / {@code 语气}。 */
        public final String prefix;
        /** 组名（{@code 语气-怒-暴怒} 的「怒」；没有第二段时 = 档名）——同组内先到者胜。 */
        public final String group;
        /** 档名（{@code 语气-怒-暴怒} 的「暴怒」）。 */
        public final String name;
        /** 文案（{@code |} 右边，已去掉首尾空白）。 */
        public final String text;
        /** 条件（{@code |} 左边）。 */
        public final String cond;
        /** 解析好的条件；null = 这一行看不懂（跳过）。 */
        private final Cond parsed;

        Level(String prefix, String group, String name, String cond, String text) {
            this.prefix = prefix;
            this.group = group;
            this.name = name;
            this.cond = cond;
            this.text = text;
            this.parsed = parseCond(cond);
        }

        /** 条件成立吗（{@code 其他} = 兜底，永远成立）。看不懂的条件 = 不成立。 */
        public boolean match(double angry, double sad, int aff, String romance) {
            if (parsed == null) return false;
            return parsed.match(angry, sad, aff, romance);
        }

        /** 是不是"其他/兜底"那一行。 */
        public boolean fallback() { return parsed != null && parsed.fallback; }

        /** 能用吗（条件看得懂 + 文案非空）。 */
        public boolean ok() { return parsed != null && Str.has(text); }

        @Override
        public String toString() { return prefix + "-" + group + "-" + name + "[" + cond + "]"; }
    }

    // ================================================================ 条件（mini DSL）

    /** 一个原子条件：{@code 怒>=50} / {@code 亲疏<-30} / {@code 恋爱=lover}。 */
    private static final class Atom {
        final String dim;
        final String op;
        final double num;
        final String str;

        Atom(String dim, String op, double num, String str) {
            this.dim = dim;
            this.op = op;
            this.num = num;
            this.str = str;
        }

        boolean match(double angry, double sad, int aff, String romance) {
            if ("恋爱".equals(dim)) {
                String v = romance == null ? "" : romance;
                if ("!=".equals(op)) return !v.equals(str);
                return v.equals(str);
            }
            double cur;
            if ("怒".equals(dim)) cur = angry;
            else if ("悲".equals(dim)) cur = sad;
            else if ("亲疏".equals(dim)) cur = aff;
            else return false;
            if (">=".equals(op)) return cur >= num;
            if ("<=".equals(op)) return cur <= num;
            if (">".equals(op)) return cur > num;
            if ("<".equals(op)) return cur < num;
            if ("!=".equals(op)) return cur != num;
            if ("=".equals(op)) return cur == num;
            return false;
        }
    }

    /** 一条完整条件：若干原子用 {@code &&} 连起来；{@code 其他} = 兜底。 */
    static final class Cond {
        final List<Atom> atoms;
        final boolean fallback;

        Cond(List<Atom> atoms, boolean fallback) {
            this.atoms = atoms;
            this.fallback = fallback;
        }

        boolean match(double angry, double sad, int aff, String romance) {
            if (fallback) return true;
            for (Atom a : atoms) if (!a.match(angry, sad, aff, romance)) return false;
            return !atoms.isEmpty();
        }
    }

    /** 解析条件串；看不懂返回 null（那一行作废，绝不抛异常）。 */
    private static Cond parseCond(String raw) {
        String s = Str.trim(raw);
        if (s.isEmpty()) return null;
        if ("其他".equals(s) || "默认".equals(s) || "*".equals(s)) {
            return new Cond(new ArrayList<Atom>(), true);
        }
        List<Atom> atoms = new ArrayList<Atom>();
        for (String part : s.split("&&")) {
            String p = Str.trim(part);
            if (p.isEmpty()) continue;
            int k = condOpIndex(p);
            if (k < 0) return null;
            String dim = p.substring(0, k).trim();
            String op = opAt(p, k);
            String v = p.substring(k + op.length()).trim();
            if (dim.isEmpty() || v.isEmpty()) return null;
            if ("恋爱".equals(dim)) {
                if (!"=".equals(op) && !"!=".equals(op)) return null;
                atoms.add(new Atom(dim, op, 0, v));
            } else if ("怒".equals(dim) || "悲".equals(dim) || "亲疏".equals(dim)) {
                double n = numOf(v, Double.NaN);
                if (Double.isNaN(n)) return null;
                atoms.add(new Atom(dim, op, n, ""));
            } else {
                return null;
            }
        }
        if (atoms.isEmpty()) return null;
        return new Cond(atoms, false);
    }

    // ================================================================ 实例

    /** 键 → 首个值（{@code 键: 值} 的第一条）。 */
    private final Map<String, String> kv = new LinkedHashMap<String, String>();
    /** 键 → 全部值（按文件顺序）——同一族写多行时用它。 */
    private final Map<String, List<String>> many = new LinkedHashMap<String, List<String>>();
    /** 全部 {@code 效果规则}（按文件顺序）。 */
    private final List<Rule> rules = new ArrayList<Rule>();
    /** 全部 {@code 条件档}（按文件顺序）。 */
    private final List<Level> levels = new ArrayList<Level>();
    /** 有没有真的读到文件（诊断用）。 */
    private final boolean loaded;

    private EmotionCfg(boolean loaded) { this.loaded = loaded; }

    public boolean loaded() { return loaded; }

    // ---------------- 标量配置 ----------------

    public String s(String key, String def) {
        String v = kv.get(key);
        return Str.has(v) ? v.trim() : def;
    }

    public double d(String key, double def) {
        return numOf(kv.get(key), def);
    }

    public int i(String key, int def) {
        double v = numOf(kv.get(key), Double.NaN);
        return Double.isNaN(v) ? def : (int) Math.round(v);
    }

    /** 开关：{@code true/1/yes/on/开/是} 为真，{@code false/0/no/off/关/否} 为假，其余用默认。 */
    public boolean flag(String key, boolean def) {
        String v = kv.get(key);
        if (!Str.has(v)) return def;
        String s = v.trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)
                || "开".equals(s) || "是".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s)
                || "关".equals(s) || "否".equals(s)) return false;
        return def;
    }

    /** 某一族的效果规则（{@code 情绪} / {@code 触发} / {@code 正则}），按文件顺序。 */
    public List<Rule> rules(String kind) {
        List<Rule> out = new ArrayList<Rule>();
        for (Rule r : rules) if (r.kind.equals(kind)) out.add(r);
        return out;
    }

    /** 某一族的条件档（{@code 心情} / {@code 表情} / {@code 态度} / {@code 关系} / {@code 语气}）。 */
    public List<Level> levels(String prefix) {
        List<Level> out = new ArrayList<Level>();
        for (Level l : levels) if (l.prefix.equals(prefix) && l.ok()) out.add(l);
        return out;
    }

    /**
     * 某一族的多值表（键可以写多行，全部取回，按文件顺序）。
     * <p>给档位层用：{@code 词-&lt;标签&gt;}（关键词表）与 {@code 禁词-&lt;类目&gt;}（出站兜底清单）
     * 都是"一个键多行"的表。没有多行时退回那一行（值已按逗号切成清单）。</p>
     */
    public List<String> tags(String key) {
        List<String> out = new ArrayList<String>();
        if (!Str.has(key)) return out;
        List<String> vals = many.get(key);
        if (vals == null) {
            String one = kv.get(key);
            if (Str.has(one)) vals = new ArrayList<String>(java.util.Arrays.asList(one));
        }
        if (vals == null) return out;
        for (String v : vals) {
            if (Str.blank(v)) continue;
            if (v.indexOf(',') >= 0 || v.indexOf('，') >= 0) out.addAll(split(v));
            else out.add(Str.trim(v));
        }
        return out;
    }

    // ================================================================ 取值：档

    /** 档位层的一条消息命中了哪些标签（{@code 词-<标签>} 表驱动；一个中文词都不硬编码）。 */
    public static final class Sig {
        /** 骂（E1 即刻通道）。 */
        public boolean insult;
        /** 求饶/道歉/说好话（E2 即刻通道）。 */
        public boolean sorry;
        /** 夸（E8）。 */
        public boolean praise;
        /** 感兴趣的话题（E9）。 */
        public boolean interest;
        /** 贬低/否定人格/被拿来和别人比/被嫌弃（E10 → sad）。 */
        public boolean belittle;
        /** 冷落（E13 → letdown）。 */
        public boolean cold;
        /** 违规请求（E7b）。 */
        public boolean violate;
        /** 明确的"做不到"表述（E4 的第二证据之一）。 */
        public boolean cant;
        /** 明确的"为难"表述（E5 的第二证据之一）。 */
        public boolean hard;

        /** 命中了任何一类吗。 */
        public boolean any() {
            return insult || sorry || praise || interest || belittle || cold || violate || cant || hard;
        }
    }

    /**
     * 一条正文命中了哪些标签。
     * <p>词表 = {@code prompt.md} 的 {@code 词-<标签>} 行（可热改；改完下一条消息生效）。
     * <b>同一个标签的多个词只算一次命中</b>（一条消息里 3 个骂词 = 1 分，见规格 G8）。</p>
     */
    public Sig sig(String text) {
        Sig s = new Sig();
        if (Str.blank(text)) return s;
        s.insult = hasWord(text, "骂");
        s.sorry = hasWord(text, "求饶");
        s.praise = hasWord(text, "夸");
        s.interest = hasWord(text, "兴趣");
        s.belittle = hasWord(text, "贬低");
        s.cold = hasWord(text, "冷落");
        s.violate = hasWord(text, "违规") || regexHit(text, "违规正则");
        s.cant = hasWord(text, "做不到");
        s.hard = hasWord(text, "为难");
        return s;
    }

    /** 某个标签的词表里有没有命中（子串、忽略大小写）。 */
    public boolean hasWord(String text, String tag) {
        if (Str.blank(text)) return false;
        String low = Str.lower(text);
        for (String w : tags("词-" + tag)) {
            if (Str.blank(w)) continue;
            if (low.indexOf(Str.lower(w)) >= 0) return true;
        }
        return false;
    }

    /** 命中某一族正则吗（{@code 正则-<名>} 之外的独立一族：{@code 违规正则-<名>}）。 */
    public boolean regexHit(String text, String prefix) {
        if (Str.blank(text)) return false;
        for (Map.Entry<String, List<String>> e : many.entrySet()) {
            if (!e.getKey().startsWith(prefix + "-")) continue;
            for (String src : e.getValue()) {
                if (Str.blank(src)) continue;
                try {
                    if (Pattern.compile(src.trim(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                            .matcher(text).find()) return true;
                } catch (Throwable ignored) {
                    // 正则写坏了：这条作废（绝不炸）
                }
            }
        }
        return false;
    }

    /** 单值档：按文件顺序取<b>第一个命中</b>的（V3 的 if/else 阈值链口径）。
     * 兜底行（{@code 其他}）写在最后，自然也是"前面都不中才轮到它"。
     */
    public static String pick(List<Level> ls, double angry, double sad, int aff, String romance) {
        for (Level l : ls) if (l.match(angry, sad, aff, romance)) return l.text;
        return "";
    }

    /**
     * 语气档：<b>同组先到者胜、不同组可以同时命中</b>（V3 的「怒/悲两族各取一条」口径）。
     * 兜底行在"已经命中过别的组"时让位 —— 于是「平静」只会在真的平静时出现。
     *
     * @param sep 多条之间的分隔符（prompt.md 的「语气分隔符」）
     */
    public static String pickTones(List<Level> ls, double angry, double sad, int aff, String romance, String sep) {
        Set<String> hitGroups = new HashSet<String>();
        List<String> out = new ArrayList<String>();
        List<Level> fallbacks = new ArrayList<Level>();
        for (Level l : ls) {
            if (l.fallback()) {
                fallbacks.add(l);
                continue;
            }
            if (hitGroups.contains(l.group)) continue;          // 同组已经取过一条
            if (!l.match(angry, sad, aff, romance)) continue;
            hitGroups.add(l.group);
            out.add(l.text);
        }
        if (out.isEmpty()) {
            for (Level l : fallbacks) {
                if (l.match(angry, sad, aff, romance)) {
                    out.add(l.text);
                    break;
                }
            }
        }
        return Str.join(out, sep == null ? "；" : sep);
    }

    // ================================================================ 填充

    /**
     * {@code {键}} 字面替换 + 可选组。
     * <p>{@code [ ... ]} 里的任何占位符取到空值 → 连方括号一起丢掉；否则去掉方括号留下内容。
     * 方括号不配对时原样保留（宁可多留一段文字，也不吃字符）。</p>
     *
     * @param kv 交替的 键, 值（值 null = 空）
     */
    public static String fill(String tpl, String... kv) {
        if (tpl == null) return "";
        Map<String, String> v = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] == null) continue;
            v.put(kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
        }
        Set<String> empty = new HashSet<String>();
        for (Map.Entry<String, String> e : v.entrySet()) {
            if (!Str.has(e.getValue())) empty.add(e.getKey());
        }
        String s = stripOptional(tpl, empty);
        for (Map.Entry<String, String> e : v.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    /** 去掉"含空值占位符"的可选组（递归处理嵌套）。 */
    private static String stripOptional(String s, Set<String> empty) {
        if (s == null || s.isEmpty()) return "";
        int open = s.indexOf('[');
        if (open < 0) return s;
        int depth = 0;
        int close = -1;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '[') depth++;
            else if (ch == ']') {
                depth--;
                if (depth == 0) { close = i; break; }
            }
        }
        if (close < 0) return s;                                  // 方括号不配对：原样
        String inner = stripOptional(s.substring(open + 1, close), empty);
        boolean drop = !Str.has(inner);
        if (!drop) {
            Matcher m = PH.matcher(inner);
            while (m.find()) {
                if (empty.contains(m.group(1))) { drop = true; break; }
            }
        }
        String head = s.substring(0, open);
        String tail = s.length() > close + 1 ? stripOptional(s.substring(close + 1), empty) : "";
        return head + (drop ? "" : inner) + tail;
    }

    // ================================================================ 读取（按文件惰性缓存 + mtime 失效）

    /**
     * 配置缓存：<b>按 prompt.md 文件</b>各留一份。
     *
     * <p><b>为什么必须是多份</b>（我实测踩到、而且很难查）：缓存原来是"进程级一份"，
     * 而扩展点会拿着<b>别的插件</b>的宿主来问配置（{@code ExtRegistry} 给每个 stage/provider 绑
     * "它自己插件的宿主"）⇒ 单份缓存在多插件共存的进程里会被互相顶掉，
     * 表现是"某个插件突然读到一份<b>空</b>配置"（于是 {@code 情绪开关} 之类取默认值、整段静默不干活）。
     * 键就是那个 {@code prompt.md} 文件本身，值带 mtime + 字节数。</p>
     */
    private static final class Cache {
        final EmotionCfg cfg;
        final long stamp;
        final long len;

        Cache(EmotionCfg cfg, long stamp, long len) {
            this.cfg = cfg;
            this.stamp = stamp;
            this.len = len;
        }
    }

    private static final Map<String, Cache> CACHE = new java.util.concurrent.ConcurrentHashMap<String, Cache>();

    /** 最多缓存几份（技能数远小于它；超了直接清空，避免长期运行的收敛问题）。 */
    private static final int CACHE_MAX = 64;

    /**
     * 取配置。
     * <p>{@code Host} 为 null（或拿不到目录）时：随便给一份缓存（"少做点事"，不至于退回全默认值），
     * 一份都没有就给空配置（所有取值走 Java 兜底）。</p>
     */
    public static EmotionCfg of(Host h) {
        try {
            File f = promptFile(h);
            if (f == null) {
                for (Cache c : CACHE.values()) return c.cfg;
                return new EmotionCfg(false);
            }
            long stamp = f.lastModified();
            long len = f.length();
            String key = f.getAbsolutePath();
            Cache c = CACHE.get(key);
            if (c != null && c.stamp == stamp && c.len == len) return c.cfg;
            EmotionCfg fresh = parse(f);
            if (CACHE.size() > CACHE_MAX) CACHE.clear();
            CACHE.put(key, new Cache(fresh, stamp, len));
            return fresh;
        } catch (Throwable t) {
            for (Cache c : CACHE.values()) return c.cfg;
            return new EmotionCfg(false);
        }
    }

    /** 清掉缓存（探针/诊断用；正常路径靠 mtime 失效）。 */
    public static synchronized void forget() {
        CACHE.clear();
    }

    /** 本插件目录下的 prompt.md（拿不到目录返回 null）。 */
    public static File promptFile(Host h) {
        try {
            if (h == null) return null;
            File dir = h.skillDir();
            if (dir == null && h.conf() != null) dir = new File(h.conf().skillsDir(), NAME);
            return dir == null ? null : new File(dir, "prompt.md");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 这个 {@code Host} 绑的是本插件的目录吗？
     * <p>缓存现在是<b>按文件各一份</b>（见 {@link #of}），所以这个判断只剩诊断用途；
     * 真正生效的是"用哪个宿主的目录就去读哪个 prompt.md"。</p>
     */
    public static boolean matches(Host h) {
        return promptFile(h) != null;
    }

    /** 解析一份 prompt.md。 */
    private static EmotionCfg parse(File f) {
        EmotionCfg c = new EmotionCfg(true);
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
                String val = unesc(s.substring(k + 1).trim());
                if (key.isEmpty()) continue;
                List<String> one = c.many.get(key);
                if (one == null) {
                    one = new ArrayList<String>();
                    c.many.put(key, one);
                }
                one.add(val);
                if (!c.kv.containsKey(key)) c.kv.put(key, val);

                int dash = key.indexOf('-');
                if (dash <= 0) continue;
                String prefix = key.substring(0, dash).trim();
                String rest = key.substring(dash + 1).trim();
                if (rest.isEmpty()) continue;

                int arrow = val.indexOf("=>");
                if (arrow >= 0) {
                    // 效果规则：触发-/正则-/情绪-
                    String left = val.substring(0, arrow).trim();
                    String right = val.substring(arrow + 2).trim();
                    boolean re = "正则".equals(prefix);
                    List<String> words = re ? single(left) : split(left);
                    if (words.isEmpty()) continue;
                    c.rules.add(new Rule(prefix, rest, words, re, right));
                    continue;
                }
                int bar = val.indexOf('|');
                if (bar > 0) {
                    // 条件档：心情-/表情-/态度-/关系-/语气-
                    String cond = val.substring(0, bar).trim();
                    String text = val.substring(bar + 1).trim();
                    int last = rest.lastIndexOf('-');
                    String group = last > 0 ? rest.substring(0, last).trim() : rest;
                    String name = last > 0 ? rest.substring(last + 1).trim() : rest;
                    c.levels.add(new Level(prefix, group, name, cond, text));
                }
            }
        } catch (Throwable ignored) {
            // 读失败 = 已读到的部分照用（空文件 = 全兜底）
        } finally {
            close(r);
        }
        return c;
    }

    // ================================================================ 小工具

    private static void close(BufferedReader r) {
        if (r == null) return;
        try {
            r.close();
        } catch (Throwable ignored) {
        }
    }

    /** 行内第一个（半角/全角）冒号的位置。 */
    private static int colon(String s) {
        int a = s.indexOf(':');
        int b = s.indexOf('\uff1a');
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /** 效果词的运算符位置（支持 {@code 怒+15} / {@code 亲疏-20} / {@code 情绪=生气}）。 */
    private static int opIndex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+' || c == '-' || c == '=') return i;
        }
        return -1;
    }

    /**
     * 条件里的运算符位置（{@code 怒>=80} / {@code 悲<10} / {@code 恋爱!=lover} / {@code 亲疏>=-30}）。
     * <p>与 {@link #opIndex} 分开是必须的：条件里有 {@code > < !}，效果词里没有 ——
     * 合成一个函数会让「怒&gt;=80」被读成「dim=怒&gt;」。</p>
     */
    private static int condOpIndex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '>' || c == '<' || c == '=') return i;
            if (c == '!' && i + 1 < s.length() && s.charAt(i + 1) == '=') return i;
        }
        return -1;
    }

    /** 条件里的运算符（先长后短：{@code >=} 不能读成 {@code >}）。 */
    private static String opAt(String s, int i) {
        if (i + 1 < s.length()) {
            String two = s.substring(i, i + 2);
            if (">=".equals(two) || "<=".equals(two) || "!=".equals(two)) return two;
        }
        return String.valueOf(s.charAt(i));
    }

    private static List<String> single(String v) {
        List<String> out = new ArrayList<String>();
        if (Str.has(v)) out.add(v.trim());
        return out;
    }

    /** 逗号（半角/全角）分隔的清单（词表用这一支：词里可以有空格）。 */
    public static List<String> split(String v) {
        List<String> out = new ArrayList<String>();
        if (Str.blank(v)) return out;
        for (String p : v.split("[,，]")) {
            String t = Str.trim(p);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 空白或逗号分隔的 token 清单（效果词用这一支：{@code 怒+15 亲疏-20}）。 */
    public static List<String> tokens(String v) {
        List<String> out = new ArrayList<String>();
        if (Str.blank(v)) return out;
        for (String p : v.split("[\\s,，]+")) {
            String t = Str.trim(p);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static double numOf(String v, double def) {
        if (!Str.has(v)) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 值里的 {@code \n} / {@code \t} 转义（让 prompt.md 能写多行模板）。 */
    private static String unesc(String s) {
        if (s == null) return "";
        return s.replace("\\n", "\n").replace("\\t", "\t");
    }

    /** 只读的全部键（诊断）。 */
    public Map<String, String> all() { return Collections.unmodifiableMap(kv); }
}
