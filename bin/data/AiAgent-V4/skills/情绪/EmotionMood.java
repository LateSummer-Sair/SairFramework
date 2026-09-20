import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Ev;
import sair.v4.skill.Host;
import sair.v4.store.Store;

/**
 * 「情绪」的<b>档位层</b>：离散 6 态状态机（{@code calm / angry / happy / sad / letdown / strike}）。
 * <p>{@code strike}（罢工）是情绪 v2 新增的第 6 档：只能从 {@code angry} 升上来（见
 * {@link #toStrike}），<b>出口四条</b> —— ① 求饶道歉（既有 sorry 逻辑）② 网格翻页（既有 20 分钟格）
 * ③ {@code 罢工上限分钟} 到点（{@link #strikeOver}，{@code since} 起算，默认 10 分钟）
 * ④ {@code 罢工平息条数}（默认连续 3 条不带情绪信号的消息）。③④ 见 {@link #decide} 里那一段。
 * <b>罢工期间任何带情绪信号的消息只维持、不降级</b>（第二轮 GM 裁定 A3）。它<b>不改</b>其余五态的任何语义。</p>
 *
 * <h3>它和 {@link EmotionState} 是什么关系</h3>
 * <p>本类<b>不动</b>既有那台连续 0~100 的怒气/伤心/亲疏机器：那台机器继续管"强度与衰减"，
 * 本类管"<b>此刻是哪一档</b>"。两者<b>共用同一个注入点</b>（{@link EmotionProvider}）——
 * 全工程只有那一个 provider，模型不会看到两套互相冲突的语气指令。</p>
 *
 * <h3>状态放哪</h3>
 * <p>放 <b>{@code kv} 表</b>，键 {@code mood:<scope_id>}（{@code mood:group:<群号>} /
 * {@code mood:private:<QQ>}，<b>按场合隔离，不做全局情绪</b>）。
 * 刻意<b>不</b>声明自己的表：既有插件已占 {@code emotion} / {@code 情绪} / {@code 情绪状态} /
 * {@code emotion_state} 四个库名/别名，再撞一个会让整个插件装载失败。
 * 刻意<b>不</b>用 static 内存态（热重载会丢，编译失败会让钩子+扩展点+注入一起消失）、
 * 刻意<b>不</b>自起线程（只用基板唯一 tick）。</p>
 *
 * <h3>阈值口径（甲方硬数字，一个都不改）</h3>
 * <p>prompt.md 的「触发阈」表里写 {@code N}，含义是「<b>超过 N 次</b>」= <b>第 N+1 次</b>：
 * 戳 {@code 3} ⇒ 第 4 次；做不到 {@code 2} ⇒ 第 3 次；为难 {@code 3} ⇒ 第 4 次；重复问 {@code 3} ⇒ 第 4 次。</p>
 *
 * <h3>时间常数只有一个</h3>
 * <p>网格 {@code slot = floor(now / 20 分钟)}（墙上时钟<b>固定网格</b>，不是滑动窗口）：
 * {@code slot} 一变 ⇒ 状态回 {@code calm} + 计数清零，<b>状态变化不刷新网格</b>。</p>
 */
public final class EmotionMood {

    /** 技能名（与 {@link EmotionCfg#NAME} 同一个）。 */
    public static final String NAME = EmotionCfg.NAME;

    /** 自己的 kv 键空间（{@code mood:<scope_id>}）。 */
    public static final String KV = "mood:";
    /** 转移日志的 kv 键前缀。 */
    public static final String KV_LOG = "moodlog:";

    // ---- 6 个状态码 ----
    public static final String S_CALM = "calm";
    public static final String S_ANGRY = "angry";
    public static final String S_HAPPY = "happy";
    public static final String S_SAD = "sad";
    public static final String S_LETDOWN = "letdown";
    /** 第 6 档「罢工」（情绪 v2）：只骂人、不干活；从 {@code angry} 升上来。 */
    public static final String S_STRIKE = "strike";

    private static final String[] STATES = {S_CALM, S_ANGRY, S_HAPPY, S_SAD, S_LETDOWN, S_STRIKE};

    /** {@code 触发阈-strike} 的兜底默认值（写 N = 「超过 N 次」，与其余触发阈同一个口径）。 */
    private static final int DEF_STRIKE_TH = 3;

    /** {@code 罢工上限分钟} 的兜底默认值（罢工最长几分钟；与 prompt.md 那行**同源**）。 */
    private static final int DEF_STRIKE_MAX_MIN = 10;

    /** {@code 罢工平息条数} 的兜底默认值（连续几条"没有情绪信号"的消息就退到 angry）。 */
    private static final int DEF_STRIKE_QUIET = 3;

    // ---- 计分信号（下标 = 评估优先级；码写进日志的 trigger=） ----
    static final int G_INSULT = 0;
    static final int G_POKE = 1;
    static final int G_CANT = 2;
    static final int G_HARD = 3;
    static final int G_REPEAT = 4;
    static final int G_FLOOD = 5;
    static final int G_VIOLATE = 6;
    static final int G_BELITTLE = 7;
    static final int G_COLD = 8;
    static final int G_QUIET = 9;
    static final int G_PRAISE = 10;
    static final int G_INTEREST = 11;
    static final int G_SORRY = 12;
    static final int G_N = 13;

    /** 日志里的触发码（prompt.md 的「触发表」给友好名；这里给机器码）。 */
    private static final String[] CODES = {
            "insult", "poke", "cant", "hard", "repeat", "flood", "violate", "belittle",
            "cold", "quiet", "praise", "interest", "sorry"};

    /** 默认阈值（prompt.md 的「触发阈」读不到时用）；含义 = 「超过 N 次」。 */
    private static final int[] DEF_N = {0, 3, 2, 3, 3, 0, 0, 0, 0, 0, 0, 1, 0};

    /** 每个场合一把锁：同一个 scope 的「读-改-写（含落盘）」串行（钩子池是多线程）。 */
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<String, Object>();

    /** 上一次 {@link #onTimer} 里"网格翻页回平淡"的那一行（调用方决定打不打/怎么落）。 */
    private static volatile String GRID_LAST = "";

    private EmotionMood() {}

    // ================================================================ 状态形状

    /** 一个场合的情绪档位（{@code mood:<scope_id>} 那一行 JSON）。 */
    public static final class Mood {
        public String state = S_CALM;
        public long since;
        public long slot = Long.MIN_VALUE;
        public String trigger = "";
        public long by;
        public int peace;
        /** 主账本：码 → 计数（重复问用指纹当键）。 */
        public Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        /** 按人：计数码 → QQ → 次数（只对"某个人干的"信号记）。 */
        public Map<String, Map<String, Integer>> byUser = new LinkedHashMap<String, Map<String, Integer>>();
        /** 次账本（场合合计）：戳 / 骂。 */
        public int pokeGrp;
        public int insultGrp;
        /** 网格翻页时被清掉的旧计数摘要（诊断）。 */
        public Map<String, Integer> dropped = new LinkedHashMap<String, Integer>();
        /** 同一网格里已生效过的求饶次数（E2b 防操纵）。 */
        public int sorrySlot;
        /** 窗口：self_sends=她连续说了几条、last_in=上一条入站消息、quiet_hit=本网格是否已结算过"被晾"。 */
        public int selfSends;
        public long lastSelfSend;
        public long lastIn;
        public boolean quietHit;
        /** "被晾"的基线时刻。 */
        public long quietBase;
        /** 已见消息 id（环形，防重放）与请求指纹（重复问的判据）。 */
        public List<Long> seen = new ArrayList<Long>();
        public Map<String, Long> fps = new LinkedHashMap<String, Long>();
        /** 刷屏窗口：第一条消息的时刻、条数、总字符数。 */
        public long floodFrom;
        public int floodN;
        public int floodChars;
        /** 停留下限的基准：上一条消息（不是"上次状态变化" —— 那个会让生气被永久钉死，见 V3 教训）。 */
        public long lastSep;
        /** 上一条消息时有没有结构失败证据（E12 的判据）。 */
        public boolean lastFail;
        /** 结构信号（由 LifecycleHook / 出站 stage 记下，下一条消息消费）。 */
        public boolean failSeen;
        public String pendTag = "";
        /** 自报标记的记分口径（{@link #noteTag} 写、{@link #onMessage} 消费）。 */
        public String selfTag = "";
        public long selfTagTs;
        public boolean selfTagScored;
        /** 最近一次"感兴趣"信号的时刻（E9 的"持续"判据）。 */
        public long lastInterest;
        /**
         * 罢工期间**连续几条"不带情绪信号"的消息**（情绪 v2 第二轮 §A2-{@code 罢工平息条数}）。
         * <p>只用在 {@code strike} 态：到阈值就退到 {@code angry}；来一条带情绪信号的消息就清零。
         * 它落在 {@code win} 里（与 {@code self_sends} 那批窗口计数同一处），
         * <b>事实行与 state 的形状一个字段都没加</b>（§1.4）。</p>
         */
        public int strikeCalm;

        public int count(String code) {
            Integer v = counts.get(code);
            return v == null ? 0 : v.intValue();
        }

        public int countOf(String code, long qq) {
            Map<String, Integer> m = byUser.get(code);
            if (m == null) return 0;
            Integer v = m.get(String.valueOf(qq));
            return v == null ? 0 : v.intValue();
        }
    }

    /** 一次结算的结果（{@code changed=false} 时其余字段只是当时的现场）。 */
    public static final class Result {
        public boolean changed;
        /** 续期（状态没变，但"这一刻"被重新确认）。 */
        public boolean renewed;
        public String from = S_CALM;
        public String to = S_CALM;
        public String trigger = "";
        public int n;
        public int th;
        public long by;
        public long dwell;
        public String reason = "";
        /** 真的落库了吗（{@code false} = 写被拒/写失败，探针要能看见）。 */
        public boolean wrote;
        public Mood st;

        /** §7.1 的逐字日志格式（单行、不含换行）。 */
        public String line(String scopeId) {
            return "mood state=" + from + ">" + to
                    + " trigger=" + (Str.blank(trigger) ? "-" : trigger)
                    + " n=" + n + "/" + th
                    + " who=" + by
                    + " scene=" + scopeId
                    + " slot=" + (st == null ? 0L : st.slot)
                    + " dwell=" + dwell + "ms"
                    + " reason=" + reason;
        }
    }

    // ================================================================ 键 / 锁

    /** 场合键：群聊 {@code group:<群号>}、私聊 {@code private:<QQ>}（与规格 §4.2 逐字一致）。 */
    public static String scopeOf(long groupId, long qq) {
        return groupId > 0L ? ("group:" + groupId) : ("private:" + qq);
    }

    private static Object lockOf(String scopeId) {
        String k = scopeId == null ? "" : scopeId;
        Object o = LOCKS.get(k);
        if (o != null) return o;
        Object fresh = new Object();
        Object prev = LOCKS.putIfAbsent(k, fresh);
        if (LOCKS.size() > 4096) LOCKS.clear();
        return prev == null ? fresh : prev;
    }

    // ================================================================ 读 / 写

    private static JsonElement parse(String s) {
        if (Str.blank(s)) return null;
        try {
            return new JsonParser().parse(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读一个场合的状态（没有 = 全新的一份）。任何异常都吞掉当"没有这一行"。 */
    public static Mood load(Store s, String scopeId) {
        Mood m = new Mood();
        if (s == null || Str.blank(scopeId)) return m;
        String v = null;
        try {
            v = s.kv(KV + scopeId, null);
        } catch (Throwable t) {
            return m;
        }
        JsonElement el = parse(v);
        if (el == null || !el.isJsonObject()) return m;
        try {
            of(el.getAsJsonObject(), m);
        } catch (Throwable t) {
            // 值坏了 = 当全新的一份（一行坏数据不该把插件打死）
        }
        return m;
    }

    /**
     * 一行 {@code mood:} JSON → {@link Mood}。
     *
     * <p><b>每一个子对象都可能缺</b>（{@code by} / {@code win} / {@code seen} / {@code fps} /
     * {@code counts} / {@code dropped}）—— 缺了按"空"读，<b>绝不抛</b>。这条口径是夹具逼出来的：
     * 原来 {@code J.sub()} 缺键返回 {@code null}，于是 {@code by}/{@code win} 一缺就 NPE，
     * {@code load()} 靠外面那把 try 把它吞成"全新的一份"（状态读不出但至少不崩），
     * 而 {@code onTimer()} 是**直接调本方法**的 ⇒ 一行半截数据会把整个 tick 的结算打死。
     * 现在两种调用方都安全：完整行（{@code save()} 写出来的）行为逐字节不变。</p>
     */
    private static void of(JsonObject o, Mood m) {
        m.state = norm(J.s(o, "state", S_CALM));
        m.since = J.l(o, "since", 0L);
        m.slot = J.l(o, "slot", Long.MIN_VALUE);
        m.trigger = J.s(o, "trigger", "");
        m.by = J.l(o, "trigger_by", 0L);
        m.peace = J.i(o, "peace", 0);
        m.counts = ints(J.sub(o, "counts"));
        m.byUser = new LinkedHashMap<String, Map<String, Integer>>();
        JsonObject by = J.sub(o, "by");
        if (by != null) for (Map.Entry<String, JsonElement> e : by.entrySet()) {
            if (e.getValue() == null || !e.getValue().isJsonObject()) continue;
            m.byUser.put(e.getKey(), ints(e.getValue().getAsJsonObject()));
        }
        m.pokeGrp = J.i(o, "poked_grp", 0);
        m.insultGrp = J.i(o, "insult_grp", 0);
        m.sorrySlot = J.i(o, "sorry_slot", 0);
        m.dropped = ints(J.sub(o, "dropped"));
        JsonObject w = J.sub(o, "win");
        if (w == null) w = new JsonObject();
        m.selfSends = J.i(w, "self_sends", 0);
        m.lastSelfSend = J.l(w, "last_self_send_ts", 0L);
        m.lastIn = J.l(w, "last_msg_ts", 0L);
        m.quietHit = J.b(w, "quiet_hit", false);
        m.quietBase = J.l(w, "quiet_base", 0L);
        m.failSeen = J.b(w, "fail_seen", false);
        m.pendTag = J.s(w, "pend_tag", "");
        m.selfTag = J.s(w, "self_tag", "");
        m.selfTagTs = J.l(w, "self_tag_ts", 0L);
        m.selfTagScored = J.b(w, "self_tag_scored", false);
        m.lastInterest = J.l(w, "last_interest", 0L);
        m.strikeCalm = J.i(w, "strike_calm", 0);
        JsonArray seen = J.list(o, "seen");
        if (seen != null) for (JsonElement e : seen) {
            try {
                if (e != null) m.seen.add(Long.valueOf(e.getAsLong()));
            } catch (Throwable ignored) {
            }
        }
        JsonObject fps = J.sub(o, "fps");
        if (fps != null) for (Map.Entry<String, JsonElement> e : fps.entrySet()) {
            try {
                m.fps.put(e.getKey(), Long.valueOf(e.getValue().getAsLong()));
            } catch (Throwable ignored) {
            }
        }
    }

    private static Map<String, Integer> ints(JsonObject o) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        if (o == null) return m;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            try {
                m.put(e.getKey(), Integer.valueOf(e.getValue().getAsInt()));
            } catch (Throwable ignored) {
            }
        }
        return m;
    }

    private static JsonObject obj(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        if (m != null) for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getKey() == null) continue;
            o.addProperty(e.getKey(), e.getValue() == null ? 0 : e.getValue().intValue());
        }
        return o;
    }

    /** 落盘（一行 {@code kv}；<b>调用方必须在锁内</b>，锁内只做纯内存 + 一次 kvSet）。 */
    private static boolean save(Store s, String scopeId, Mood m) {
        if (s == null || Str.blank(scopeId)) return false;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("state", m.state);
            o.addProperty("since", m.since);
            o.addProperty("slot", m.slot);
            o.addProperty("trigger", m.trigger);
            o.addProperty("trigger_by", m.by);
            o.addProperty("level", levelOf(m.state));
            o.addProperty("peace", m.peace);
            o.add("counts", obj(m.counts));
            JsonObject by = new JsonObject();
            for (Map.Entry<String, Map<String, Integer>> e : m.byUser.entrySet()) {
                by.add(e.getKey(), obj(e.getValue()));
            }
            o.add("by", by);
            o.addProperty("poked_grp", m.pokeGrp);
            o.addProperty("insult_grp", m.insultGrp);
            o.addProperty("sorry_slot", m.sorrySlot);
            if (!m.dropped.isEmpty()) o.add("dropped", obj(m.dropped));
            JsonObject w = new JsonObject();
            w.addProperty("self_sends", m.selfSends);
            w.addProperty("last_self_send_ts", m.lastSelfSend);
            w.addProperty("last_msg_ts", m.lastIn);
            w.addProperty("quiet_base", m.quietBase);
            w.addProperty("quiet_hit", m.quietHit);
            w.addProperty("fail_seen", m.failSeen);
            w.addProperty("pend_tag", m.pendTag);
            w.addProperty("self_tag", m.selfTag);
            w.addProperty("self_tag_ts", m.selfTagTs);
            w.addProperty("self_tag_scored", m.selfTagScored);
            w.addProperty("last_interest", m.lastInterest);
            w.addProperty("strike_calm", m.strikeCalm);
            o.add("win", w);
            JsonArray seen = new JsonArray();
            for (Long id : m.seen) seen.add(id);
            o.add("seen", seen);
            JsonObject fp = new JsonObject();
            for (Map.Entry<String, Long> e : m.fps.entrySet()) {
                fp.addProperty(e.getKey(), e.getValue() == null ? 0L : e.getValue().longValue());
            }
            o.add("fps", fp);
            s.kvSet(KV + scopeId, o.toString());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只读视图（{@link EmotionProvider} 用）：<b>不落盘</b>，只读，返回的一定是稳定的一份。 */
    public static Mood peek(Store s, String scopeId, long now) {
        return peek(s, scopeId, now, null);
    }

    /**
     * 只读视图（{@link EmotionProvider} 用）：<b>不落盘、只读、返回的一定是稳定的一份</b>。
     *
     * <p><b>{@code now <= 0} 的语义 = "别替我算时间"</b>：那一路是 provider 的只读路径，
     * 它必须做到"同一状态两次 build 逐字节一致"（前缀缓存 + 探针都靠它），所以它不读墙上时钟。
     * 于是这里也<b>不做</b>网格翻页的降级 —— 翻页的落盘只发生在 {@code on_message} / {@code on_timer} 里
     * （那些路径本来就有真实时钟，而且 tick 每 20 秒就会把该翻的翻掉）。
     * <b>我踩过的坑</b>：一开始在 {@code now<=0} 时也算了一次槽，结果"0 != 已存的槽"恒成立，
     * 于是<b>每一次 build 都把状态看成 calm</b> —— 表现得像"注入完全没生效"，其实状态一直是对的。</p>
     */
    public static Mood peek(Store s, String scopeId, long now, EmotionCfg c) {
        Mood m = load(s, scopeId);
        if (now <= 0L) return m;
        long slot = slotOf(now, c);
        if (m.slot != Long.MIN_VALUE && m.slot != slot) {
            m.dropped = new LinkedHashMap<String, Integer>(m.counts);
            m.state = S_CALM;
            m.counts = new LinkedHashMap<String, Integer>();
            m.byUser = new LinkedHashMap<String, Map<String, Integer>>();
            m.pokeGrp = 0;
            m.insultGrp = 0;
            m.sorrySlot = 0;
            m.peace = 0;
            m.slot = slot;
            m.trigger = "grid";
            m.since = now;
        }
        return m;
    }

    // ================================================================ 时间 / 小工具

    /** 网格槽：{@code floor(now / 周期)}（周期 = prompt.md 的「重置分钟」，出厂 20 分钟）。 */
    public static long slotOf(long now) {
        return now <= 0L ? 0L : (now / 1200000L);
    }

    /** 按配置的周期算网格（{@code 重置分钟} 改了要跟着变）。 */
    public static long slotOf(long now, EmotionCfg c) {
        long min = c == null ? 20 : c.i("重置分钟", 20);
        if (min <= 0L) min = 20L;
        return now <= 0L ? 0L : (now / (min * 60000L));
    }

    static String iso(long ms) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date(ms));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 档位的<b>严重度</b>（写进 kv 与事实行的 {@code level}；也用于 {@code dwellOk} 的"向上/向下"判定）。
     * <p>v2 把 {@code strike} 放在梯子顶端（4）：它比 {@code angry}（3）更高，
     * 所以"罢工降回生气"在 {@code dwellOk} 眼里是一次向下的动作（不会被它当成升级放行）。
     * <b>angry = 3 一个字没动</b>（探针按字面钉它）。</p>
     */
    static int levelOf(String state) {
        if (S_STRIKE.equals(state)) return 4;
        if (S_ANGRY.equals(state)) return 3;
        if (S_SAD.equals(state)) return 2;
        if (S_LETDOWN.equals(state)) return 1;
        return 0;
    }

    static String norm(String s) {
        String v = Str.lower(Str.trim(s));
        for (String x : STATES) if (x.equals(v)) return x;
        return S_CALM;
    }

    // ================================================================ 就绪检查

    /**
     * 状态机现在能不能动（开关 + prompt.md + 库）。
     *
     * @return {@code null} = 能动；否则是"为什么不能"（调用方按需打日志）
     */
    public static String ready(Store s, EmotionCfg c, Host h, boolean needWrite) {
        if (c == null || !c.loaded()) return "prompt.md 没读出来（档位层不生效）";
        if (!c.flag("情绪开关", true)) return "档位层已关（prompt.md 的「情绪开关」= false）";
        if (s == null) return "拿不到 store";
        // 本插件不提供工具（没有 op 可判）：档位层只在 SYSTEM 主体的钩子上结算，读写自己的表恒放行。
        return null;
    }

    // ================================================================ ① on_message 结算

    /**
     * 一条入站消息 → 结算一次（<b>一次派发只改一次状态</b>）。
     *
     * @param ev      已解析的事件
     * @param text    正文
     * @param now     事件时刻（毫秒；调用方已按「时钟容差秒」校过）
     * @param pokeRow 这一条消息到达时从通知留痕里补算到的戳事件（通路 B；没有就是 null/空表）
     */
    public static Result onMessage(Store s, EmotionCfg c, Host h, Ev ev, String text, long now,
                                   List<JsonObject> pokeRow) {
        Result r = new Result();
        if (s == null || c == null || ev == null || ev.isEmpty()) return r;
        long qq = ev.userId();
        if (qq <= 0L) return r;
        long evNow = now > 0L ? now : System.currentTimeMillis();     // 事件时间（只给"间隔/被晾"用）
        long gid = ev.groupId() > 0L ? ev.groupId() : 0L;
        String scopeId = scopeOf(gid, qq);
        // ★ 网格只跟**墙上时钟**走，不跟"这条消息的事件时间"走。
        //   理由（我踩到的一个真坑）：带外钩子（on_notice）与 tick 用的都是墙上时钟，
        //   而 on_message 用的是事件时间（可能是上游机器给的、可能比本地早很多）。
        //   两者混用会让网格**往回跳**，于是网格要么被反复重置、要么永远重置不了。
        //   事件时间仍然有用——它只用来算"被晾了多久/间隔"，不参与网格。
        long wall = System.currentTimeMillis();
        long slot = slotOf(wall, c);
        synchronized (lockOf(scopeId)) {
            Mood m = load(s, scopeId);
            String from0 = m.state;
            rollover(m, slot, wall, c, r);
            if (r.changed) log(c, h, r.line(scopeId));
            // ---- 去重（同 message_id 不重复计；重放投递不会把计数打爆）
            long mid = ev.messageId();
            if (mid > 0L) {
                if (m.seen.contains(Long.valueOf(mid))) return r;
                m.seen.add(Long.valueOf(mid));
                while (m.seen.size() > 32) m.seen.remove(0);
            }
            // ---- 窗口（被晾 / 刷屏 / 自己说了几句）：gap 用事件时间算（它才是"这条消息什么时候发生的"）
            window(m, text, evNow, c);
            // ---- 通路 B：读通知留痕补算戳（只在没有 on_notice 钩子时才需要）
            if (!c.flag("通知钩子", true) && pokeRow != null) {
                for (JsonObject row : pokeRow) {
                    if (row == null) continue;
                    if (J.l(row, "user_id", 0L) != qq) continue;   // 只算当前这个人戳的
                    bump(m, "poke", qq);
                    m.pokeGrp = m.pokeGrp + 1;
                }
            }
            // ---- 关键词信号
            EmotionCfg.Sig sig = c.sig(text);
            boolean[] valid = new boolean[G_N];
            valid[G_INSULT] = sig.insult;
            valid[G_SORRY] = sig.sorry;
            // ---- 请求指纹（先算：它同时是"重复问"的判据与自报标记的第二证据）
            String fp = fpOf(text);
            boolean fpSeen = false;
            if (fp != null) {
                fpSeen = m.fps.containsKey(fp);
                // ★ "重复问"的**第一次也要计 1 分**：口径是"第 N+1 次炸"（写 3 ⇒ 第 4 次），
                //   所以计数必须代表"这件事出现过第几次"。只在"重复"时才加分会让累计值永远比
                //   出现次数少 1（实测：问第 4 次只到 3 分、阈值永远差一位）。
                // ★★ 但"这件事"= **同一个请求**，不是"任何一条带指纹的消息"：
                //   ⇒ 计数键**按指纹分开**（{@link #repKey}，与 {@link Mood#counts} 的字段注释同口径）。
                //   老写法写的是共用的 {@code "repeat"} 键，于是同一个网格里**第 4 条互不相关的消息**
                //   也会凑够阈值，被判成"重复问同一件事"（实测：四句毫不相干的话 ⇒ 第四条就生气，
                //   日志写 trigger=repeat）。
                bump(m, "repeat", qq, repKey(fp));
                if (!fpSeen) m.fps.put(fp, Long.valueOf(evNow));
                while (m.fps.size() > 32) {
                    String first = m.fps.keySet().iterator().next();
                    m.fps.remove(first);
                }
            }
            // ---- 主模型自报标记（<b>双证据</b>：只在"有硬证据"时才计分）
            // 这里的硬证据 = <b>这个要求此前出现过</b>（请求指纹命中）。
            //
            // ★ 关于"**一条要求只计 1 分**"的取舍（我实测踩过，写清楚免得后人再踩）：
            //   要求在第 N 轮，她的自报标记在第 N 轮**末尾**才产生（onTurnEnd），所以"这一轮的标记"
            //   永远只能服务**下一轮**的同一个要求。为了让"第 N 轮的要求"与"同一轮的标记分"
            //   不重复计，**词表命中那一侧在自报标记生效时不再加分**（见下面 cant/hard 那一段）。
            //   于是三轮"同一要求 + 每轮她都自报做不到" = 3 分 ⇒ 第 3 轮正好触发（甲方口径
            //   "超两次 = 第 3 次"）。这条设计也让自报标记**是必需的**：
            //   她不写标记，三轮就只有 2 分，第 3 轮不会生气 —— 这才是"双证据"的意思。
            boolean failEv = m.failSeen;
            m.failSeen = false;
            String tag = Str.trim(m.selfTag);
            // ★ 有效期用**墙上时钟**算，不用事件时间：标记是"她上一轮末期写下的"，那一头记的是墙上时钟，
            //   而这一头如果拿事件时间比，一个上游时钟偏早的部署会让**所有**标记当场过期
            //   （实测的表现是"标记永远不生效"，而且看不出原因）。
            boolean tagFresh = m.selfTagTs > 0L && (wall - m.selfTagTs) <= c.i("自报有效期分钟", 10) * 60000L;
            // 硬证据 = ① 她这轮的产出带结构失败标记（[工具] ok=0 / 权限阻断）；或
            //            ② 这个要求此前出现过（请求指纹命中）。两者都没有 ⇒ 标记作废。
            boolean tagHard = fpSeen || failEv;
            boolean tagWants = Str.has(tag) && tagFresh && !m.selfTagScored && tagHard;
            boolean tagged = false;
            // 「做不到 / 为难 / 违规」是**三条互斥的语义类**：一条消息只落其中一条，
            // 且**一条消息对同一类最多 +1 分** —— 两条证据（词表命中 / 她自报标记）**取其一**：
            //   ① 词表命中且这个要求此前出现过 ⇒ 词表这一侧计分（她自报的那一分就不再加）；
            //   ② 否则如果她这一轮自报了对应标记、且硬证据在 ⇒ 由标记计分。
            //   ★ 两边都计会让"一条要求 +2 分"，把甲方"超两次 = 第 3 次"的口径悄悄减半
            //     （我实测踩过：第 3 次就该炸的要求，第 2 次就炸了）。也让自报标记变成"可选加速"，
            //     而不是"必需的第二证据"。
            if (sig.violate) {
                if (!tagWants) bump(m, "violate", qq);
                tagged = true;
            } else if (sig.cant) {
                if (fpSeen || tagWants) bump(m, "cant", qq);
                tagged = true;
            } else if (sig.hard) {
                if (fpSeen || tagWants) bump(m, "hard", qq);
                tagged = true;
            } else if (tagWants) {
                if ("做不到".equals(tag)) bump(m, "cant", qq);
                else if ("为难".equals(tag)) bump(m, "hard", qq);
                else if ("违规".equals(tag)) bump(m, "violate", qq);
            }
            // ★ 标记**一次性消费**：无论有没有被采纳，读完就清掉。
            //   写成"过期才清"会让同一个标记在下一轮再被拿来试一次（等于靠重复刷分），
            //   而"她下一轮又写了一次"这种情况由 LifecycleHook 重新写入新标记。
            if (m.selfTagTs > 0L) {
                m.selfTag = "";
                m.selfTagScored = false;
            }
            // ---- 纯计数信号
            if (sig.insult) {
                bump(m, "insult", qq);
                m.insultGrp = m.insultGrp + 1;
            }
            if (sig.sorry) bump(m, "sorry", qq);
            boolean praiseOk = false;
            if (sig.praise && m.count("praise") < c.i("刷夸上限", 6)) {
                bump(m, "praise", qq);
                praiseOk = true;
            }
            if (sig.interest) {
                bump(m, "interest", qq);                      // 命中就算一条（E9 的"≥2 条"）
                valid[G_INTEREST] = gapOk(m.lastInterest, evNow, c);
                m.lastInterest = evNow;
            }
            if (sig.belittle) bump(m, "belittle", qq);
            if (sig.cold) bump(m, "cold", qq);
            // （「做不到 / 为难 / 违规」三条在上面跟自报标记一起处理了，那里保证"一条消息最多 +1 分"。）
            // 刷屏：同一人 60 秒内 ≥ 阈值条且平均正文 ≤ 字数上限
            if (m.floodN >= c.i("刷屏条数", 6) && m.floodChars <= m.floodN * c.i("刷屏字数", 8)) {
                bump(m, "flood", qq);
                m.floodFrom = wall;
                m.floodN = 0;
                m.floodChars = 0;
            }
            // ---- 第二次重复请求 + 上一轮有结构失败（E12：只到 letdown，且要在 E6 阈值之前）
            // 判据用 **lastFail（上一条消息时有没有失败证据）**，不用本次的 failEv ——
            // failEv 是"这一轮她自报了标记"那条路的证据，拿它去触发 letdown 会把
            // "第 2 次要求做不到的事"直接降成 letdown（实测过：A2/A3 全被它带偏）。
            // ★ 且**只在这条消息不是"做不到/为难"那两类要求时才成立**：那两类有自己的计数阈值
            //   （甲方的硬数字），E12 是"她说做不到但用户又提了一次"的另一条路，不能抢它们的触发。
            valid[G_COLD] = fpSeen && m.lastFail && !sig.cant && !sig.hard
                    && (m.count(repKey(fp)) < nOf(c, G_REPEAT) + 1);
            if (valid[G_COLD]) bump(m, "repeat_fail", qq);
            // ---- 把"这次这条消息里有哪些信号成立"补齐（正向与几类负向：只认本次命中的那一个）
            // ★ 这一处漏过：valid[] 只填了 insult/sorry，于是「被夸 ⇒ 高兴」那条路永远进不去
            //   （decide 的第一道门就是 `valid[G_PRAISE] || valid[G_INTEREST]`）。
            //   漏填的表现极具误导性：计数在涨、状态不动。
            valid[G_PRAISE] = sig.praise && praiseOk;
            valid[G_INTEREST] = valid[G_INTEREST] && sig.interest;
            valid[G_BELITTLE] = sig.belittle;
            valid[G_QUIET] = m.count("quiet") > 0;
            // ---- 评估（唯一结论）
            decide(m, c, r, wall, qq, valid, praiseOk, fp, fpSeen, sig);
            // ---- ★ 第九轮（GM 令 A1）：**转述场景不锁人** ----
            // 本条入站消息里出现**第三方指称**（`[提及 qq=X]`，X 既不是她自己、也不是说话人；
            // 或者带一个 `[引用 msg_id=…]` 段）⇒ 这一句很可能是"**转述别人骂我**"
            // ⇒ **不要把说话人锁成 `trigger_by`**（`by` 保持 0）。怒气/计数**照记**（她可以生气），
            //    后果是：`MoodStage.finish()` 不会 @ 他（没锁人）· `transform()` 的 S2 会**软化**说话人
            //    —— 她可以生气，但不能对着**传话的人**开骂。
            // ★ 刻意**不**在 `decide` 内部逐处改 `m.by`：只在这里收一次口（同一处、可读、可测），
            //   而且它发生在 `save()` 之前 ⇒ 落库的就是 `by=0`。
            // ★★ 第十一轮（GM 令，复核证伪"可达误伤"）：归零**必须"本条确实在骂/贬"才生效** ——
            //   否则一条毫无敌意的日常句（例：`说明一下他那个方案` = 说+他）就能把**已经锁好的**触怒者
            //   解开（`by` 从 1284688456 变 0）⇒ 她下一轮对真正的冒犯者不再 @、不再分句，而且
            //   `hot && by<=0` 还会让该场合攻击词**整体软化**（正好把第九轮要修的东西镜像回来）。
            //   用的就是 `decide(...)` 里那份现成信号（`sig.insult || sig.belittle`），**不新造第二套判据**。
            if ((sig.insult || sig.belittle) && thirdParty(ev, text, c)) m.by = 0L;
            r.st = m;
            if (r.changed) log(c, h, r.line(scopeId));
            r.wrote = save(s, scopeId, m);
            if (!r.wrote && Str.has(from0)) warn(h, "mood write denied: " + scopeId);
        }
        return r;
    }

    /** 网格翻页：状态回 {@code calm} + 计数清零（{@code since} = 翻页判定时刻）。 */
    static void rollover(Mood m, long slot, long now, EmotionCfg c, Result r) {
        if (m.slot == slot) return;
        boolean had = m.slot != Long.MIN_VALUE;
        String from = m.state;
        m.dropped = new LinkedHashMap<String, Integer>();
        if (had) for (Map.Entry<String, Integer> e : m.counts.entrySet()) {
            if (e.getValue() != null && e.getValue().intValue() > 0) m.dropped.put(e.getKey(), e.getValue());
        }
        m.slot = slot;
        m.counts = new LinkedHashMap<String, Integer>();
        m.byUser = new LinkedHashMap<String, Map<String, Integer>>();
        m.pokeGrp = 0;
        m.insultGrp = 0;
        m.sorrySlot = 0;
        m.peace = 0;
        m.quietHit = false;
        m.quietBase = now;
        m.lastIn = now;
        m.lastSep = 0L;
        m.lastFail = false;
        m.floodFrom = 0L;
        m.floodN = 0;
        m.floodChars = 0;
        m.fps = new LinkedHashMap<String, Long>();
        m.lastInterest = 0L;
        m.strikeCalm = 0;
        if (had && !S_CALM.equals(from)) {
            m.state = S_CALM;
            m.trigger = "grid";
            m.by = 0L;
            m.since = now;
            r.changed = true;
            r.from = from;
            r.to = S_CALM;
            r.trigger = "grid";
            r.n = 0;
            r.th = 0;
            r.by = 0L;
            r.dwell = 0L;
            r.reason = "20分钟重置";
        } else if (!had) {
            m.since = now;
        }
    }

    /** 窗口：被晾（她连说 ≥2 条后 ≥10 分钟没人说话）/ 刷屏统计 / 自己说了几条。 */
    private static void window(Mood m, String text, long now, EmotionCfg c) {
        long gap = m.lastIn > 0L ? (now - m.lastIn) : 0L;
        long quietMs = c.i("被晾分钟", 10) * 60000L;
        // 被晾：她连说 ≥ 阈值条之后，冷场 ≥ 阈值分钟 ⇒ 结算 1 次（同一个网格只结算一次）
        if (!m.quietHit && m.selfSends >= c.i("被晾条数", 2)
                && m.quietBase > 0L && gap >= quietMs) {
            bump(m, "quiet", 0L);
            m.quietHit = true;
        }
        // 停留下限的基准：上一条消息（不是"上次状态变化"）——这是常规通道的反向触发门
        m.lastSep = gap;
        m.lastFail = m.failSeen;
        m.lastIn = now;
        m.quietBase = now;
        m.selfSends = 0;
        int len = text == null ? 0 : text.length();
        if (m.floodFrom <= 0L || now - m.floodFrom > 60000L) {
            m.floodFrom = now;
            m.floodN = 1;
            m.floodChars = len;
        } else {
            m.floodN++;
            m.floodChars += len;
        }
    }

    private static boolean gapOk(long last, long now, EmotionCfg c) {
        if (last <= 0L) return false;
        long lim = c.i("兴趣间隔分钟", 3) * 60000L;
        return lim <= 0L || (now - last) <= lim;
    }

    /** 加 1 分（主账本 + 按人 + 可选指纹键）。 */
    private static void bump(Mood m, String code, long qq) {
        bump(m, code, qq, null);
    }

    private static void bump(Mood m, String code, long qq, String fpKey) {
        String key = fpKey != null ? fpKey : code;
        Integer v = m.counts.get(key);
        m.counts.put(key, Integer.valueOf(v == null ? 1 : v.intValue() + 1));
        if (qq > 0L) {
            Map<String, Integer> u = m.byUser.get(code);
            if (u == null) {
                u = new LinkedHashMap<String, Integer>();
                m.byUser.put(code, u);
            }
            String k = String.valueOf(qq);
            Integer uv = u.get(k);
            u.put(k, Integer.valueOf(uv == null ? 1 : uv.intValue() + 1));
        }
    }

    /**
     * 某个信号的触发阈值（<b>第几次才炸</b>），直接取自 prompt.md 的「触发阈-&lt;码&gt;」。
     * <p>prompt.md 里写的是甲方口径「<b>超过 N 次</b>」= <b>第 N+1 次</b>，所以这里<b>原样返回</b>读到的 N
     * （写 {@code 3} ⇒ 返回 {@code 3}），判据写成 {@code count > 阈值}（等价于 {@code count >= N+1}）。
     * 这两句必须成对看：{@code >} 与"原样返回"是一对，改成 {@code >=}（或让这里 +1）就会整体差一位
     * —— 实测"问第 4 次还不炸、要问第 5 次才炸"，正是这么来的。</p>
     * <p>{@code 0} 表示"不靠计数"（即刻通道 / 纯结构信号）。</p>
     */
    private static int nOf(EmotionCfg c, int gi) {
        int v = c == null ? 0 : c.i("触发阈-" + CODES[gi], DEF_N[gi]);
        return v < 0 ? 0 : v;
    }

    // ================================================================ ② 评估与抢占

    /**
     * 把现场评估成<b>一个</b>结论（P1 即刻通道 &gt; 常规通道；P2 骂 &gt; 求饶；P3 负向优先；
     * P4 不降级；P6 只能经 {@code calm} 向上）。
     *
     * <p><b>情绪 v2（§1）</b>：{@code strike} 的维持/退出与 {@code angry → strike} 的升级
     * 在<b>以上所有通道之前</b>先判（见方法体第一段）—— 那条即刻通道会把"另一个人骂过来"
     * 写成 {@code move(S_ANGRY)}，既降级又会换掉 {@code trigger_by}，与"罢工锁人"直接冲突。</p>
     *
     * <p><b>P3「负向优先」是怎么落地的</b>：常规负向的<b>扫描写在正向分支之前</b>
     * （老写法反过来：正向分支先判、先 {@code return}）⇒ 一条消息里既有夸/兴趣、又有贬低/冷落/戳…
     * 时，取的是<b>负向</b>。甲方口径：被骂 ⇒ 即刻生气，不许被同一条消息里的一个夸词抵掉。</p>
     *
     * @param fp 这一次入站消息的<b>请求指纹</b>（{@link #fpOf}；null = 拿不到）。
     *           「重复问」的计数与判据都按它分开（见 {@link #repKey}），不再共用一个键。
     */
    private static void decide(Mood m, EmotionCfg c, Result r, long now, long qq,
                               boolean[] valid, boolean praiseOk, String fp, boolean fpSeen, EmotionCfg.Sig sig) {
        String from = m.state;
        r.from = from;
        r.to = from;
        r.by = qq;
        int cur = levelOf(from);
        boolean neg = S_ANGRY.equals(from) || S_SAD.equals(from) || S_LETDOWN.equals(from);
        int peaceNeed = c.i("缓和分", 3);
        // ---- ★★ 情绪 v2 §1：罢工态（strike）的维持与退出，以及 angry → strike 的升级 ★★
        // 位置**刻意在"即刻通道"之前**：那条通道会把"另一个人骂过来"写成 move(S_ANGRY, …)，
        // 既把罢工降回生气、又会把 trigger_by 换成别人 —— 而 §1.2 要求罢工**锁人**。
        // 顺序也不能反：先判"已在罢工"（维持/退出），再判"该不该升上来"。
        if (S_STRIKE.equals(from)) {
            boolean sorryIn = valid[G_SORRY] && m.count("sorry") > 0;
            if (!sorryIn) {
                // §1.3-③（第二轮 GM 裁定）：**上限分钟**到点 ⇒ 退到 angry（since 起算，与网格无关）
                int capMin = c.i("罢工上限分钟", DEF_STRIKE_MAX_MIN);
                if (strikeOver(m, now, capMin)) {
                    m.strikeCalm = 0;
                    clearStrikeLevel(m);
                    move(m, r, now, m.by, S_ANGRY, "peace", 0, 0,
                            "罢工到点（超过 罢工上限分钟 " + capMin + "）");
                    return;
                }
                // §A3（第二轮 GM 裁定）：**任何带情绪信号的消息只维持 strike，绝不降级**
                // （被骂/被贬低/戳/重复问/冷落/刷屏/哄夸…都算"有事发生"）—— 甲方要的是"专心骂人"。
                if (anySignal(sig, valid, fpSeen)) {
                    m.strikeCalm = 0;
                    keepStrike(m, r, "罢工中（这一条带情绪信号 ⇒ 只维持，不降级）");
                    return;
                }
                // §A2（第二轮 GM 裁定）：**连续 N 条不带情绪信号的消息** ⇒ 退到 angry
                int need = c.i("罢工平息条数", DEF_STRIKE_QUIET);
                int calm = m.strikeCalm + 1;
                if (need > 0 && calm >= need) {
                    m.strikeCalm = 0;
                    clearStrikeLevel(m);
                    move(m, r, now, m.by, S_ANGRY, "peace", calm, need,
                            "罢工平息（连续 " + calm + " 条没有情绪信号）");
                    return;
                }
                m.strikeCalm = calm;
                keepStrike(m, r, "罢工中（连续 " + calm + "/" + Math.max(0, need) + " 条没有情绪信号）");
                return;
            }
            // §1.3-① 求饶：落到下面既有的 sorry 分支（它 move 到 calm）；顺带把升级计数清掉，
            // 免得"道歉之后一句普通的话"又被旧计数顶回罢工。
            clearStrikeLevel(m);
        }
        if (S_ANGRY.equals(from) && toStrike(m, c)) {
            enterStrike(m, c, r, now, qq);
            return;
        }
        // ---- P1/P2：即刻通道（不受停留下限约束）—— 骂 > 求饶 > 常规
        if (valid[G_INSULT] && m.count("insult") > 0) {
            int n = m.count("insult");
            m.counts.remove("insult");
            move(m, r, now, qq, S_ANGRY, "insult", n, 1, "被骂");
            return;
        }
        if (valid[G_SORRY] && m.count("sorry") > 0) {
            m.counts.remove("sorry");
            int lim = c.i("求饶即刻上限", 2);
            if (m.sorrySlot < lim) {
                m.sorrySlot++;                                     // E2：即刻回平淡（豁免停留下限）
                move(m, r, now, qq, S_CALM, "sorry", 1, 1, "求饶");
                return;
            }
            // E2b：同一网格第 3 次及以后的求饶不再即刻生效，只记缓和分
            m.peace++;
            if (neg && m.peace >= peaceNeed) {
                move(m, r, now, qq, S_CALM, "peace", m.peace, peaceNeed, "缓和分攒够");
                return;
            }
            r.n = m.peace;
            r.th = peaceNeed;
            r.reason = "求饶（本网格已生效 " + m.sorrySlot + " 次，改为缓和分）";
            return;
        }
        // ---- 正向：calm → happy；负向态下只加缓和分（P6：不能从 angry 直达 happy）
        //   ★ P3 负向优先的落点：**同一条消息**里既有正向信号（夸/兴趣）又有负向信号
        //     （贬低/冷落/违规/做不到/为难）时，取负向 —— 于是这里先问一句"这一句自己带不带负向语义"，
        //     带就整个跳过正向分支，落到下面的常规负向扫描（那一定命中）。
        //   ★ 判据**只看正文里的那几类**，不看账本型信号（戳的计数、重复问的指纹计数、刷屏窗口、
        //     被晾窗口、E12 的冷落）：那些是"这个人此前干过什么"，不是"这一句说了什么"。
        //     拿它们否决正向分支会让"生气态里被夸 ⇒ 记缓和分"这条既有路径失效
        //     （实测：A10 会红，peace 从此不长）。
        boolean negInMsg = negInMessage(sig);
        if (!negInMsg && (valid[G_PRAISE] || valid[G_INTEREST])) {
            int gp = (valid[G_PRAISE] && praiseOk) ? G_PRAISE : G_INTEREST;
            int n = m.count(CODES[gp]);
            int th = nOf(c, gp) + 1;
            if (praiseOk || gp == G_INTEREST) {
                if (S_CALM.equals(from) || S_HAPPY.equals(from)) {
                    move(m, r, now, qq, S_HAPPY, CODES[gp], n, th,
                            gp == G_PRAISE ? "被夸" : "持续感兴趣话题");
                    return;
                }
                if (neg) {
                    m.peace++;
                    if (m.peace >= peaceNeed) {
                        move(m, r, now, qq, S_CALM, "peace", m.peace, peaceNeed, "缓和分攒够");
                        return;
                    }
                    r.n = m.peace;
                    r.th = peaceNeed;
                    r.reason = "缓和分 +1（当前 " + from + "）";
                    return;
                }
            }
        }
        // ---- 常规负向：负向优先、先到者胜
        int gi = -1;
        for (int i = G_POKE; i <= G_QUIET; i++) {
            if (hit(m, c, i, fp, fpSeen, qq)) {
                gi = i;
                break;
            }
        }
        if (gi < 0) {
            if (!r.changed) {
                r.n = 0;
                r.th = 0;
                r.reason = "";
            }
            return;
        }
        String to = S_ANGRY;
        if (gi == G_BELITTLE) to = S_ANGRY.equals(from) ? null : S_SAD;
        else if (gi == G_COLD || gi == G_QUIET) to = (S_ANGRY.equals(from) || S_SAD.equals(from)) ? null : S_LETDOWN;
        String reason = reasonOf(gi);
        int th = thOf(m, c, gi);
        int n = nOf(c, gi) + 1;
        if (to == null) {
            // P4 不降级：已在更高强度时低强度触发只计数不改写
            clearHit(m, gi, fp);
            r.n = n;
            r.th = th;
            r.reason = reason + "（当前 " + from + " 不降级，只计数）";
            return;
        }
        if (to.equals(from)) {
            // 同态续期：已经在 angry 里继续被戳/被骂 —— 不刷新网格（G3），也不重复打转移行。
            // ★ 这里**不许**改 {@code trigger}（真机实测踩到的可观测性缺陷，决定账本 D73）：
            //   标签的语义是"**你是被什么惹进这个状态的**"，不是"这一条消息命中了什么"。
            //   老写法在这里写 {@code m.trigger = CODES[gi]}，于是真机上出现过
            //   `state=angry trigger=poke` 而该网格的 counts 里根本没有 poke（那次真正命中的是
            //   "重复问"）—— 归因指错，`mood:` 事实行与日志都会把人带偏。
            //   ⇒ trigger 现在**只在真的发生状态转移时更新**（{@link #move} 里的 !same 那一支）。
            //   续期仍然要更新 trigger_by（S2 的判据是"现在是谁在惹她"）与 Result 的诊断字段。
            m.by = qq;
            clearHit(m, gi, fp);
            r.renewed = true;
            r.n = n;
            r.th = th;
            r.reason = reason;
            return;
        }
        if (!dwellOk(from, levelOf(to), now, c, m)) {
            // 停留下限（只约束常规通道的反向触发）：只计数、不改写
            clearHit(m, gi, fp);
            r.n = n;
            r.th = th;
            r.reason = reason + "（停留下限内，只计数）";
            return;
        }
        if (gi == G_POKE) m.pokeGrp = 0;                 // 合计账本触发一次后归零，不重复炸
        move(m, r, now, qq, to, CODES[gi], n, th, reason);
    }

    /**
     * <b>这一条消息自己</b>带负向语义吗（P3「负向优先」的判据）。
     *
     * <p>只看<b>来自正文</b>的那几类信号：贬低 / 冷落 / 违规 / 做不到 / 为难。
     * <b>刻意不看账本型信号</b>——戳的计数、重复问的指纹计数、刷屏窗口、被晾窗口、E12 的冷落：
     * 那些说的是"这个人此前干过什么"，不是"这一句说了什么"。拿它们去否决正向分支会让
     * "生气态里被夸 ⇒ 只记缓和分（不直达 happy）"这条既有路径失效
     * （实测：A10 会红，peace 从此不长）——那样修出来的不是 P3，是"账本一有余额就再也哄不好"。</p>
     */
    private static boolean negInMessage(EmotionCfg.Sig sig) {
        if (sig == null) return false;
        return sig.belittle || sig.cold || sig.violate || sig.cant || sig.hard;
    }

    /** 这个信号现在够阈值了吗。 */
    private static boolean hit(Mood m, EmotionCfg c, int gi, String fp, boolean fpSeen, long qq) {
        switch (gi) {
            case G_POKE:
                // 主账本（G5 按人）：**只看这个人自己戳了几次** —— 甲 2 次 + 乙 2 次不该让谁挨骂。
                // 次账本（G7 场合合计）：场合累计 ≥ 2×阈值 + 1（"阈值 ×2"的 G9 口径）。
                return m.countOf("poke", qq) > nOf(c, G_POKE)
                        || (c.flag("场合合计", true) && m.pokeGrp > nOf(c, G_POKE) * 2);
            case G_CANT: return m.count("cant") > nOf(c, G_CANT);
            case G_HARD: return m.count("hard") > nOf(c, G_HARD);
            case G_REPEAT:
                // ★ 判据 = **同一个请求**出现过几次（键按指纹分开），不是"带指纹的消息有几条"。
                //   老写法读共用的 "repeat" 键 ⇒ 同一网格内第 4 条**互不相关**的消息也会命中这一条
                //   （实测：四句毫不相干的话就把她判成"重复问同一件事"）。
                return fp != null && m.count(repKey(fp)) > nOf(c, G_REPEAT);
            case G_FLOOD: return m.count("flood") > nOf(c, G_FLOOD);
            case G_VIOLATE: return m.count("violate") > nOf(c, G_VIOLATE);
            case G_BELITTLE: return m.count("belittle") > nOf(c, G_BELITTLE);
            case G_COLD: return m.count("cold") > nOf(c, G_COLD) || m.count("repeat_fail") > 0;
            case G_QUIET: return m.count("quiet") > nOf(c, G_QUIET);
            default: return false;
        }
    }

    /** 阈值分母（日志里的 {@code n/th}；一律 = 阈值 + 1，与"超 N 次"口径同源）。 */
    private static int thOf(Mood m, EmotionCfg c, int gi) {
        return nOf(c, gi) + 1;
    }

    /**
     * 「重复问」的计数键：{@code repeat@<指纹>} —— <b>按请求分开记</b>。
     *
     * <p><b>为什么必须分开</b>：{@link Mood#counts} 的字段注释（"重复问用指纹当键"）、
     * 本文件的阈值口径（"第 N+1 次"）与 {@code prompt.md} 的「触发阈」注释（"第 4 次<b>同一要求</b>"）
     * 说的是同一件事 —— 计数代表"<b>这件事</b>出现过第几次"。共用一个 {@code "repeat"} 键时，
     * 同一个网格里任意 N+1 条<b>互不相关</b>的消息都会凑够阈值，日志会写成
     * {@code trigger=repeat}（"重复问同一件事"），而她其实只是听了四句不相干的话。</p>
     */
    static String repKey(String fp) {
        return "repeat@" + fp;
    }

    /**
     * 触发 / 续期 / 被挡住之后，把这一路的计数摘掉（{@link #hit} 的清账侧）。
     *
     * <p>{@code repeat} 的计数键是<b>按指纹分开</b>的，所以不能按"码"删：要删的是<b>当前这个请求</b>
     * 那一格。删错键的后果是"计数永远下不来"⇒ 同一个请求在之后的每一条消息里都继续满足阈值。</p>
     */
    private static void clearHit(Mood m, int gi, String fp) {
        if (gi == G_REPEAT) {
            if (fp != null) m.counts.remove(repKey(fp));
            return;
        }
        m.counts.remove(CODES[gi]);
    }

    /**
     * 停留下限：常规通道在<b>已经有情绪强度</b>的时候，{@code 停留下限秒} 内<b>反向</b>（强度下降）的触发
     * 只计数不改写。
     *
     * <p>★ 两处刻意放松（都是实测踩出来的）：</p>
     * <ol>
     *   <li>{@code calm} 不是"一种情绪强度"——从平淡**第一次**被惹毛不该被下限挡住。
     *       旧写法把 {@code calm} 也当下限来源，结果"第 4 次重复问"永远炸不了（永远差在下限上）。</li>
     *   <li>基准取<b>上一条消息</b>的间隔（不是"上次状态变化"）：后者会被不断续期的
     *       {@code since} 永久钉死（V3 被这件事咬过），而且"60 秒没说话再被惹"本来就该照常生效。</li>
     * </ol>
     */
    private static boolean dwellOk(String from, int toLevel, long now, EmotionCfg c, Mood m) {
        long floor = c.i("停留下限秒", 60) * 1000L;
        if (floor <= 0L) return true;
        if (S_CALM.equals(from)) return true;               // 平淡不是强度：第一次被惹毛立刻生效
        if (toLevel >= levelOf(from)) return true;          // 向上 / 同强度：立刻生效
        return m.lastSep >= floor;
    }

    private static String reasonOf(int gi) {
        switch (gi) {
            case G_POKE: return "被反复戳";
            case G_CANT: return "做不到的事";
            case G_HARD: return "为难的事";
            case G_REPEAT: return "重复问同一件事";
            case G_FLOOD: return "刷屏";
            case G_VIOLATE: return "违规请求";
            case G_BELITTLE: return "被贬低";
            case G_COLD: return "被冷落";
            case G_QUIET: return "被晾着";
            case G_PRAISE: return "被夸";
            case G_INTEREST: return "持续感兴趣话题";
            default: return "";
        }
    }

    /**
     * 真的改状态（<b>唯一写入点</b>）：{@code since} 与 {@code trigger} <b>都只在真的换状态时更新</b>。
     *
     * <p><b>为什么 trigger 也要跟着 same 走</b>（决定账本 D73 的可观测性缺陷）：这个方法是"同态续期"
     * 与"真的转移"共用的写入点 —— 被骂一条（already angry 时）走的就是这里 {@code same=true} 那一支。
     * 老写法无条件写 {@code m.trigger}，于是"她为什么生气"这件事会被**后到的、并没有让她变状态的那一下**
     * 改写（真机实测：{@code state=angry trigger=poke}，而那一网格里根本没有 poke 的计数）。
     * ⇒ {@code trigger} 的语义钉死成"**最近一次真的把她带进当前状态的那个信号**"，
     * 非转移路径（同态续期、只计数不降级、停留下限挡住）一律不许改写它
     * （{@code ProbeEmotion} 的 §9 有专门断言）。</p>
     */
    private static void move(Mood m, Result r, long now, long by, String to, String trigger,
                             int n, int th, String reason) {
        String from = m.state;
        boolean same = to.equals(from);
        long old = m.since;                                // 旧 since：日志的 dwell 要用"停留了多久"
        m.state = to;
        if (!same) {
            m.since = now;
            m.trigger = trigger;                           // ★ 只有真的换状态才写 trigger（D73）
        }
        m.by = by;
        r.from = from;
        r.to = to;
        r.changed = !same;
        r.renewed = same;
        r.trigger = trigger;                               // Result 上留着"这一条命中什么"，但只有 changed 才打日志
        r.n = n;
        r.th = th;
        r.by = by;
        r.dwell = (old > 0L && now > old) ? (now - old) : 0L;
        r.reason = reason;
    }

    // ================================================================ ⑧ 罢工（strike，情绪 v2 §1）

    /**
     * 激怒**等级**到罢工阈值了吗（§1.2 的两条判据，<b>或</b>的关系；<b>两条同值</b>）。
     *
     * <p><b>第二轮 GM 裁定（A1）</b>：两条路径**共用一个可调数字** {@code 触发阈-strike}（默认 3）——
     * 原来的「按人 ≥ 2」太松，等于给群里任何人一个"骂两句就让她停工"的开关，也会把 angry/strike
     * 两档挤在一起（A1 那条"先看到生气态的拆句 + @"就观察不到了）。</p>
     *
     * <p><b>「level」在这里指什么</b>（契约里那个词必须落到具体量上，否则实现会各写各的）：
     * 用两个**现成的计数**（都在 kv 里，不新增字段，§1.4）：</p>
     * <ul>
     *   <li>{@code m.insultGrp}（kv 的 {@code insult_grp}）= 这一格里<b>场合合计</b>被激怒了几次；</li>
     *   <li>{@code by.insult + by.belittle}（同一个 {@code trigger_by} 的按人计数）。</li>
     * </ul>
     * <p>刻意<b>不</b>拿事实行里的 {@code level}（那是 {@link #levelOf} 的档位严重度，{@code angry}
     * 恒为 3）当判据 —— 拿它去比 {@code 触发阈-strike: 3} 会让"只要生气就立刻罢工"。</p>
     */
    static boolean toStrike(Mood m, EmotionCfg c) {
        if (m == null) return false;
        int th = c == null ? DEF_STRIKE_TH : c.i("触发阈-strike", DEF_STRIKE_TH);
        if (th < 1) th = 1;
        int grid = m.insultGrp;                                                 // 场合合计（被骂）
        int by = m.countOf("insult", m.by) + m.countOf("belittle", m.by);       // 同一个 trigger_by
        return grid >= th || by >= th;                                          // ★ 两条路径同值（A1）
    }

    /**
     * 升为罢工（§1.2）：改状态、{@code trigger_by} <b>锁人</b>、并把"用掉的那一份激怒计数"消耗掉。
     *
     * <p>{@code trigger} 写 {@code S_STRIKE} ⇒ 事实行的 trigger 读 {@code 触发名-strike}
     * （出厂值「被激怒到罢工」）。</p>
     *
     * <p><b>为什么要消耗计数</b>：两条出口（{@code 罢工上限分钟} / {@code 罢工平息条数}）都要求
     * "退到 angry 之后**不要立刻又被旧计数顶回 strike**"。消耗掉之后，她要再攒够 {@code 触发阈-strike}
     * 次**新的**激怒才会重新罢工。</p>
     */
    private static void enterStrike(Mood m, EmotionCfg c, Result r, long now, long qq) {
        long by = m.by > 0L ? m.by : qq;
        int n = m.insultGrp;
        int th = c == null ? DEF_STRIKE_TH : c.i("触发阈-strike", DEF_STRIKE_TH);
        move(m, r, now, by, S_STRIKE, S_STRIKE, n, th < 1 ? 1 : th, "被激怒到罢工");
        m.by = by;                                  // ★ 锁人（§1.2）：升级之后不再被别人改写
        clearStrikeLevel(m);
    }

    /** 把"激怒等级"与"平息计数"清零（进/出罢工都用它；只有 strike 这一路会调）。 */
    private static void clearStrikeLevel(Mood m) {
        if (m == null) return;
        m.byUser.remove("insult");
        m.byUser.remove("belittle");
        m.insultGrp = 0;
        m.strikeCalm = 0;
    }

    /**
     * 罢工到点了吗（{@code 罢工上限分钟}，从 {@code since} 起算）。
     * <p>{@code capMin <= 0} = 不设上限（配置关掉这一条出口）。</p>
     */
    static boolean strikeOver(Mood m, long now, int capMin) {
        if (m == null || capMin <= 0) return false;
        long since = m.since;
        if (since <= 0L || now <= since) return false;
        return (now - since) >= capMin * 60000L;
    }

    /**
     * 这一条消息**带情绪信号**吗（§A3 的判据；纯函数，没有任何副作用）。
     *
     * <p>口径（写在报告里，免得下一个人各猜一半）—— 全部是**这一条自己的**证据，刻意不看账本存量
     * （{@code hit()} 那一路的"戳≥4次/重复问≥4次"是**触发阈值**，而且计数用完之前一直在账上，
     * 拿它当"这一条带没带信号"会把后面的普通消息全判成信号，平息计数永远涨不上去）：</p>
     * <ul>
     *   <li><b>正文型</b>：{@code sig.any()}（骂/求饶/夸/兴趣/贬低/冷落/违规/做不到/为难）—— 正负都算；</li>
     *   <li><b>重复问</b>：{@code fpSeen}（这个请求此前出现过）；</li>
     *   <li><b>本条的结算信号</b>：{@code valid[骂|求饶|夸|兴趣|贬低|冷落]}（都由本条算出）；</li>
     *   <li><b>戳一戳</b>：{@code valid[G_POKE]} —— 由 {@link #onNotice} 标上（那个事件本来就是一次戳）。</li>
     * </ul>
     * <p>⇒ 只有"完全普通的闲话"才计入 {@code 罢工平息条数}。</p>
     */
    private static boolean anySignal(EmotionCfg.Sig sig, boolean[] valid, boolean fpSeen) {
        if (sig != null && sig.any()) return true;
        if (fpSeen) return true;
        if (valid == null) return false;
        return valid[G_INSULT] || valid[G_SORRY] || valid[G_PRAISE] || valid[G_INTEREST]
                || valid[G_BELITTLE] || valid[G_COLD] || valid[G_POKE];
    }

    /** 维持 strike：一个字段都不改（不改 state / trigger / by / since），只把 Result 写成"续期"。 */
    private static void keepStrike(Mood m, Result r, String reason) {
        r.from = m.state;
        r.to = m.state;
        r.changed = false;
        r.renewed = true;
        r.trigger = m.trigger;
        r.by = m.by;
        r.n = 0;
        r.th = 0;
        r.reason = reason;
    }

    // ================================================================ ③ on_notice（戳：通路 A）

    /**
     * 一条通知事件 → <b>只记账</b>。<b>硬契约</b>：这条路 {@code h.say()} 一个字都发不出去、
     * 也起不了一轮（基板给的是 {@code Sinks.SilentSink}）；置 {@code _handled} 没有任何效果。
     */
    public static Result onNotice(Store s, EmotionCfg c, Host h, JsonObject payload) {
        Result r = new Result();
        if (s == null || c == null || payload == null) return r;
        Ev ev = Ev.parse(payload);
        long qq = J.l(J.sub(payload, "_caller"), "qq", 0L);
        if (qq <= 0L) qq = ev.userId();
        long gid = ev.groupId() > 0L ? ev.groupId() : 0L;
        JsonObject rec = J.sub(payload, "_record");
        if (gid <= 0L) gid = J.l(rec, "group_id", 0L);
        if (qq <= 0L) qq = J.l(rec, "user_id", 0L);
        if (qq <= 0L) return r;
        String sub = Str.lower(J.s(payload, "_notice_sub_type", ev.subType()));
        if (!isPoke(c, sub, J.s(rec, "content", ""))) return r;
        String scopeId = scopeOf(gid, qq);
        long now = System.currentTimeMillis();
        long slot = slotOf(now, c);
        synchronized (lockOf(scopeId)) {
            Mood m = load(s, scopeId);
            rollover(m, slot, now, c, r);
            long mid = J.l(rec, "id", 0L);
            if (mid <= 0L) mid = ev.messageId();
            if (mid > 0L) {
                if (m.seen.contains(Long.valueOf(mid))) return r;
                m.seen.add(Long.valueOf(mid));
                while (m.seen.size() > 32) m.seen.remove(0);
            }
            bump(m, "poke", qq);                             // 主账本（G5 按人）
            // 次账本（G7 场合合计）：人群总戳数只看本网格累计。触发过一次之后**把它减掉**
            // （不是清零）—— 这样"8 次"这件事留在账上（可诊断/可断言），又不会每多戳一下就再炸。
            long aggNeed = nOf(c, G_POKE) * 2L + 1L;
            boolean agg = m.pokeGrp > nOf(c, G_POKE) * 2;
            m.pokeGrp = agg ? (int) Math.max(0L, m.pokeGrp - aggNeed) : (m.pokeGrp + 1);
            // 戳这一路没有"请求正文"⇒ 没有请求指纹（第 8 个参数传 null）
            // ★ v2 第二轮：把"这一条就是戳事件"标进 valid[G_POKE] —— 它只被罢工那一支的
            //   {@link #anySignal} 读（"带情绪信号的消息"里点名包含"戳"），别的分支一个都不读它，
            //   所以非罢工路径的行为逐字节不变。
            boolean[] pokeValid = new boolean[G_N];
            pokeValid[G_POKE] = true;
            decide(m, c, r, now, qq, pokeValid, false, null, false, c.sig(null));
            r.st = m;
            if (r.changed) log(c, h, r.line(scopeId));
            r.wrote = save(s, scopeId, m);
        }
        return r;
    }

    /** 这条通知算不算"戳一戳"：先看子类型，再退到基板那个标签（标签是基板一处产出的，比字面量稳）。 */
    public static boolean isPoke(EmotionCfg c, String sub, String content) {
        List<String> subs = c == null ? null : c.tags("戳-子类型");
        if (subs == null || subs.isEmpty()) subs = EmotionCfg.split("poke,notify.poke,poke_you");
        if (Str.has(sub)) {
            String s = Str.lower(Str.trim(sub));
            for (String x : subs) if (s.equals(Str.lower(Str.trim(x)))) return true;
        }
        List<String> labs = c == null ? null : c.tags("戳-标签");
        if (labs == null || labs.isEmpty()) labs = EmotionCfg.split("戳一戳");
        for (String x : labs) {
            if (Str.has(x) && content != null && content.contains(x)) return true;
        }
        return false;
    }

    // ================================================================ ④ on_timer（网格的显式结算点）

    /**
     * 基板唯一 tick：把"到点该结算"的场合显式结算掉，不必等下一条消息 ——
     * ① <b>网格翻页</b>（{@code kv} 里还写着 {@code angry} 的那种）⇒ 回 {@code calm}，让"20 分钟重置"按时发生；
     * ② <b>罢工到点</b>（情绪 v2 第二轮 §A2-{@code 罢工上限分钟}）⇒ 回 {@code angry}。
     * 罢工上限（默认 10 分钟）**比网格（20 分钟）短**，所以它必须独立于网格判定：tick 每 20 秒跑一次，
     * 于是"到点自动退出"是**真正的到点**（不依赖有没有人说话）。
     *
     * <p>除罢工行之外，本方法的行为<b>逐字节不变</b>（非 strike 行仍旧是"slot 不同才动"）。</p>
     *
     * @return 结算掉的场合数
     */
    public static int onTimer(Store s, EmotionCfg c, long now) {
        if (s == null || c == null || !c.loaded()) return 0;
        GRID_LAST = "";
        List<JsonObject> rows;
        try {
            rows = s.kvList(KV, 500);
        } catch (Throwable t) {
            return 0;
        }
        long slot = slotOf(now, c);
        int capMin = c.i("罢工上限分钟", DEF_STRIKE_MAX_MIN);
        int changed = 0;
        for (JsonObject row : rows) {
            String key = J.s(row, "k", "");
            String val = J.s(row, "v", "");
            if (!Str.has(key) || !Str.has(val)) continue;
            JsonElement el = parse(val);
            if (el == null || !el.isJsonObject()) continue;
            Mood probeMood = new Mood();
            try {
                of(el.getAsJsonObject(), probeMood);
            } catch (Throwable bad) {
                // 一行坏数据（缺 `by`/`win` 那一类半截行）不许把**整个 tick 的结算**打死 ——
                // 与 load() 那把 try 同一个口径（"一行坏数据不该把插件打死"）：跳过这一行，继续下一条。
                // ★ 这是顺手补的一处**既有**脆弱点（夹具用一条手写的半截 mood 行逼出来的；
                //   生产行由 save() 写出，`by`/`win` 一定在，所以真机本来碰不到 —— 但它确实是个雷）。
                continue;
            }
            boolean strikeRow = S_STRIKE.equals(probeMood.state);
            if (probeMood.slot == slot && !strikeRow) continue;      // 非罢工行：与改动前逐字节同路
            String scopeId = key.substring(KV.length());
            Result r = new Result();
            String line = null;
            synchronized (lockOf(scopeId)) {
                Mood fresh = load(s, scopeId);
                if (S_STRIKE.equals(fresh.state)) {
                    // 罢工到点（与网格无关）：退到 angry。即使它已经翻过页，也先按"到点"结算。
                    if (strikeOver(fresh, now, capMin)) {
                        fresh.strikeCalm = 0;
                        clearStrikeLevel(fresh);
                        move(fresh, r, now, fresh.by, S_ANGRY, "peace", 0, 0,
                                "罢工到点（超过 罢工上限分钟 " + capMin + "）");
                        if (save(s, scopeId, fresh) && r.changed) line = r.line(scopeId);
                    }
                }
                if (line == null) {
                    if (fresh.slot == slot) continue;
                    rollover(fresh, slot, now, c, r);
                    if (!save(s, scopeId, fresh) || !r.changed) continue;
                    line = r.line(scopeId);
                }
            }
            GRID_LAST = line;
            changed++;
        }
        return changed;
    }

    /**
     * 上一次 {@link #onTimer} 里**真的改了状态**的那一行（网格翻页回平淡，或罢工到点回生气；
     * 调用方决定打不打/怎么落）。
     */
    public static String gridLast() {
        return GRID_LAST;
    }

    // ================================================================ ④.5 转述 / 第三方指称（第九轮 GM 令 A1）

    /**
     * 这条入站消息**带第三方指称**吗（= 很可能是"转述别人骂我"，不是他本人骂我）。
     *
     * <p>判据（任一成立即算）：</p>
     * <ol>
     *   <li>段里有 {@code at} 且它的 {@code qq} <b>既不是说话人、也不是她自己</b>（{@code self_id}）
     *       —— 即"点名了第三方"；{@code @全体成员}（{@code qq=all}）不算；</li>
     *   <li>段里有 {@code reply}（引用某条消息）—— 引用 = 接话/转述；</li>
     *   <li>文本兜底：正文里出现 {@code [提及 qq=N]}（同上，排除说话人/自己）或 {@code [引用 msg_id=…]}
     *       —— 基板把非文字段写成这种方括号标签，两种形态都认。</li>
     * </ol>
     *
     * <p><b>为什么需要它</b>（真机事故，GM 第九轮令）：情绪机原来把"入站文本里有骂词"一律归因给**说话人**
     * （{@code trigger_by = caller}），而机制完全不知道"这是**转述**、真正的骂人者在别处"
     * ⇒ 她对着**传话的人**开骂并 @ 了他（群 985441254 的实证）。</p>
     *
     * <p><b>已知代价（如实记录）</b>：判据 ② 是"引用段一律算转述"⇒
     * "引用了她自己的话然后再骂她"这种也会**不锁人**（于是不加 @）。要区分"引用的是谁的话"
     * 得查基板的引用缓存（{@code QuoteCache}），那是另一批的事；本轮按 GM 口径实现，边界明写在报告里。</p>
     */
    static boolean thirdParty(Ev ev, String text, EmotionCfg c) {
        try {
            long self = ev == null ? 0L : ev.selfId();
            long speaker = ev == null ? 0L : ev.userId();
            if (ev != null) {
                List<JsonObject> segs = ev.segments();
                if (segs != null) {
                    for (JsonObject seg : segs) {
                        if (seg == null) continue;
                        String type = Str.lower(Str.trim(J.s(seg, "type", "")));
                        if ("reply".equals(type)) return true;
                        if (!"at".equals(type)) continue;
                        JsonObject data = J.sub(seg, "data");
                        long q = qqOf(data == null ? "" : J.s(data, "qq", ""));
                        if (q > 0L && q != speaker && q != self) return true;
                    }
                }
            }
            // 真机拿到的就是 `ev.plainText()`（EmotionSkill 递进来的那条）；它空了才回退再要一次。
            String t = text;
            if (Str.blank(t) && ev != null) t = ev.plainText();
            if (Str.blank(t)) return false;
            // 文本兜底：`[提及 qq=N]`。★ 生产路径**不触发**（plainText 只有 text 段，方括号标签从不进来）
            //   —— 留着是因为"基板若把非文字段写成标签"那种形态在别的落点存在；第五轮口径不动。
            int i = t.indexOf("[提及 qq=");
            while (i >= 0) {
                int j = t.indexOf(']', i);
                String raw = j > i ? t.substring(i + 6, j) : t.substring(i + 6);
                long q = qqOf(raw);
                if (q > 0L && q != speaker && q != self) return true;
                i = t.indexOf("[提及 qq=", i + 1);
            }
            if (t.indexOf("[引用 msg_id=") >= 0) return true;
            // ---- ★ 第十轮（GM 令）：**纯文本转述启发式**（真机那条唯一救得回来的机制判据）----
            //   真机入站 `你要怎么骂回去？他说你是个智障` 里：他 @ 的是**她自己**、没有引用段
            //   ⇒ 上面两条段判据全不成立 ⇒ 必须靠"转述词 + 第三方指称"这层文本判据。
            return looksRelayed(t, c);
        } catch (Throwable t) {
            return false;                                   // 判不出来 = 按"不是转述"处理（宁可锁人，也不静默丢情绪）
        }
    }

    /** 「转述词」的同源兜底（`prompt.md` 的 `转述词:` 读不出来时用）。改 prompt.md 要同步改这里。 */
    static final String DEF_RELAY_WORDS = "说,讲,骂,称,听说,据说,传闻,转告,告诉,背后,嚼舌,嘀咕";
    /** 「第三方指称」的同源兜底（★ 刻意**不含 `你`**：`你` 是收话人，不是"话是别人说的"那个别人）。 */
    static final String DEF_THIRD_WORDS = "他,她,人家,别人,有人,某某,那人,那边";

    /**
     * 纯文本转述启发式（第十轮 GM 令）：**同一句里同时命中 ≥1 个「转述词」和 ≥1 个「第三方指称」**
     * ⇒ 判为转述 ⇒ 调用方把 {@code m.by} 清 0（不锁人）。
     *
     * <p>两个词表都是**数据驱动**的（`prompt.md` 的 `转述词:` / `第三方指称:`，逗号分隔）——
     * 读不出来才用 {@link #DEF_RELAY_WORDS} / {@link #DEF_THIRD_WORDS} 兜底。</p>
     *
     * <p><b>已知边界（如实记）</b>：`听说你最近很菜` 这种**没有第三方指称**的句子按本口径**不算转述**
     * （仍锁人、仍 @）—— 宁可漏判，也不把"冲她本人来的话"误判成转述（那会削掉甲方 E4 的 @ 指名）。</p>
     *
     * <p>★★ 第十一轮（GM 令，复核证伪"可达误伤"）：**「第三方指称」命中处若紧跟 `妈` ⇒ 那一次命中不算**
     * （例：`他` + `妈` = `他妈`，那是直接开骂的填充词，不是"话是别人说的"那个别人）。
     * 这条**写在代码里按"命中词后紧跟妈"实现**，**不动数据键**（免得又被别的句子踩到）。
     * 同一命中词还有别处不跟 `妈` 的出现时，仍算命中。</p>
     */
    static boolean looksRelayed(String text, EmotionCfg c) {
        if (Str.blank(text)) return false;
        boolean relay = false;
        boolean third = false;
        for (String w : words(c, "转述词", DEF_RELAY_WORDS)) {
            if (Str.has(w) && text.indexOf(w) >= 0) { relay = true; break; }
        }
        if (!relay) return false;
        for (String w : words(c, "第三方指称", DEF_THIRD_WORDS)) {
            if (Str.has(w) && thirdHit(text, w)) { third = true; break; }
        }
        return third;
    }

    /**
     * 「第三方指称」的一次命中：词在正文里出现，且**至少有一次出现**后面**不紧跟** `妈`。
     * <p>`他妈` / `她妈` 这类是直接开骂的填充词 ⇒ 那一次不算（第十一轮 GM 令）。</p>
     */
    private static boolean thirdHit(String text, String w) {
        int i = text.indexOf(w);
        while (i >= 0) {
            int after = i + w.length();
            if (after >= text.length() || text.charAt(after) != '妈') return true;
            i = text.indexOf(w, i + 1);
        }
        return false;
    }

    /** 词表：`prompt.md` 的键优先，空/异常时回退到同源兜底串。 */
    private static List<String> words(EmotionCfg c, String key, String def) {
        try {
            List<String> l = c == null ? null : c.tags(key);
            if (l != null && !l.isEmpty()) return l;
        } catch (Throwable ignored) {
        }
        List<String> out = new java.util.ArrayList<String>();
        for (String p : def.split(",")) {
            String w = Str.trim(p);
            if (Str.has(w)) out.add(w);
        }
        return out;
    }

    /** {@code qq} 字段可能是数字串或 {@code "all"}；读不出返回 0。 */
    private static long qqOf(String s) {
        if (Str.blank(s)) return 0L;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
            else if (sb.length() > 0) break;
        }
        if (sb.length() == 0) return 0L;
        try {
            return Long.parseLong(sb.toString());
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ================================================================ ⑤ 请求指纹
    /**
     * 请求指纹：归一化（只留字母数字汉字）+ FNV-1a + 长度前缀。
     * <p><b>必须逐字可复现</b>：同一句话每次算出来必须一模一样（不同实现/不同 JVM 之间也要一致），
     * 所以刻意<b>不用</b> {@code String.hashCode()} 的整串值 —— 那对"多打一个空格"就会变。
     * 长度前缀让它对"同一句话多几个字"稳健，而对"完全不同的两句话"不会撞。
     * 拿不到指纹（太短）返回 {@code null}（那时不计"重复问"）。</p>
     */
    static String fpOf(String text) {
        if (Str.blank(text)) return null;
        try {
            StringBuilder sb = new StringBuilder();
            String s = Str.lower(text);
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (Character.isLetterOrDigit(ch)) sb.append(ch);
            }
            String norm = sb.toString();
            if (norm.length() < 2) return null;
            long h = 2166136261L;                       // FNV-1a 32 位
            for (int i = 0; i < norm.length(); i++) {
                h ^= norm.charAt(i);
                h = (h * 16777619L) & 0xFFFFFFFFL;
            }
            return norm.length() + "-" + Long.toHexString(h);
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ ⑥ 出站侧 / 生命周期要读的

    /** 当前场合的状态（出站 stage / 生命周期钩子用；只读）。 */
    public static Mood peek(Store s, EmotionCfg c, long qq, long gid, long now) {
        return peek(s, scopeOf(gid, qq), now, c);
    }

    /** 触发者 QQ（S2：只骂触发者）。 */
    public static long byOf(Store s, EmotionCfg c, long qq, long gid, long now) {
        return peek(s, scopeOf(gid, qq), now).by;
    }

    /** 记一次"她说了话"（E11 被晾的时间线；由 {@link MoodLifecycle} 调）。 */
    public static void noteSelfSend(Store s, EmotionCfg c, long qq, long gid, long now) {
        if (s == null || c == null || qq <= 0L) return;
        String scopeId = scopeOf(gid, qq);
        synchronized (lockOf(scopeId)) {
            Mood m = load(s, scopeId);
            m.selfSends = m.selfSends + 1;
            m.lastSelfSend = now;
            save(s, scopeId, m);
        }
    }

    /** 记一条"结构失败证据"（{@code [工具] ok=0} / 权限阻断），供下一条消息做自报标记的第二证据。 */
    public static void noteFail(Store s, EmotionCfg c, long qq, long gid, boolean fail) {
        if (s == null || c == null || qq <= 0L) return;
        String scopeId = scopeOf(gid, qq);
        synchronized (lockOf(scopeId)) {
            Mood m = load(s, scopeId);
            m.failSeen = fail;
            save(s, scopeId, m);
        }
    }

    /**
     * 记一次"她自报了标记"（{@code [[mood:做不到]]} 等，由 {@link MoodLifecycle} 从回复正文里读）。
     * <p>这里<b>只记录，不计分</b>：计分发生在下一条消息（那时才凑得齐双证据里的"该要求此前出现过"）。
     * {@code self_tag_scored} 是一次性的（同一个标记不会重复计分）。</p>
     */
    public static void noteTag(Store s, EmotionCfg c, long qq, long gid, String tag, long now) {
        if (s == null || c == null || qq <= 0L || Str.blank(tag)) return;
        String scopeId = scopeOf(gid, qq);
        synchronized (lockOf(scopeId)) {
            Mood m = load(s, scopeId);
            m.selfTag = Str.trim(tag);
            m.selfTagTs = now <= 0L ? System.currentTimeMillis() : now;
            m.selfTagScored = false;
            save(s, scopeId, m);
        }
    }

    /** 这条回复里有没有结构失败证据（{@code [工具] ok=0} / 权限阻断原文）。 */
    public static boolean failOf(EmotionCfg c, String reply) {
        if (Str.blank(reply)) return false;
        List<String> pats = c == null ? null : c.tags("失败证据");
        if (pats == null || pats.isEmpty()) pats = EmotionCfg.split("ok=0,ok = 0,权限阻断,调用失败,被拒绝");
        for (String pat : pats) if (Str.has(pat) && reply.contains(pat)) return true;
        return false;
    }

    /** 从回复正文里读自报标记（{@code [[mood:X]]}）；没有就是空串。 */
    public static String tagOf(EmotionCfg c, String reply) {
        if (Str.blank(reply)) return "";
        int i = reply.indexOf("[[mood:");
        if (i < 0) return "";
        int j = reply.indexOf(']', i + 7);
        if (j < 0) j = reply.length();
        String raw = Str.trim(reply.substring(i + 7, j).replace("]", ""));
        List<String> allow = c == null ? null : c.tags("自报-键");
        if (allow == null || allow.isEmpty()) allow = EmotionCfg.split("做不到,为难,违规");
        for (String a : allow) if (a.equals(raw)) return raw;
        return "";
    }

    // ================================================================ ⑦ 日志

    private static final List<String> PENDING = new ArrayList<String>();
    private static final int PENDING_MAX = 200;

    /**
     * 一次转移一行（单行、无换行），受 {@code 转移日志} 开关控制。
     * <p>两条落点：① 立刻 {@code dim} 到控制台（现场排查看这条）；
     * ② 攒进内存，由 {@link #flush} 落进 {@code kv}（重启后还能查"上一次为什么变"）。
     * <b>只在状态真的变了的时候调</b>（计数变化不调，避免刷屏）。</p>
     */
    public static void log(EmotionCfg c, Host h, String line) {
        if (c != null && !c.flag("转移日志", true)) return;
        String one = Str.oneLine("[情绪] " + line);
        synchronized (PENDING) {
            if (PENDING.size() < PENDING_MAX) PENDING.add(one);
        }
        try {
            if (h != null) {
                Out o = h.out();
                if (o != null) o.dim(one);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 取走攒着的转移日志（<b>清空</b>内存缓冲），交给调用方决定怎么落。 */
    public static List<String> drain() {
        synchronized (PENDING) {
            if (PENDING.isEmpty()) return new ArrayList<String>();
            List<String> out = new ArrayList<String>(PENDING);
            PENDING.clear();
            return out;
        }
    }

    /** 写被拒 / 写失败：<b>无条件</b>留一行（这是留痕不是噪音）。 */
    public static void warn(Host h, String msg) {
        try {
            if (h == null) return;
            Out o = h.out();
            if (o != null) o.warn("[情绪] " + msg);
        } catch (Throwable ignored) {
        }
    }

    /** 把攒着的那几行落进 kv（固定键 {@code moodlog:last}，最多留 40 行）。 */
    public static int flush(Store s) {
        List<String> copy = drain();
        if (copy.isEmpty() || s == null) return 0;
        try {
            StringBuilder keep = new StringBuilder();
            // 固定键（不是"每个时间戳一个键"）：① 值不会被刷大（kv 是全库共用的一张表）；
            // ② 探针/甲方读日志时不会在同一前缀下拿到好几行历史、分不清哪一行是"刚发生的"。
            String prev = s.kv(KV_LOG + "last", "");
            if (Str.has(prev)) keep.append(prev);
            for (String l : copy) keep.append(l).append('\n');
            String[] lines = keep.toString().split("\n");
            int from = Math.max(0, lines.length - 40);
            StringBuilder out = new StringBuilder();
            for (int i = from; i < lines.length; i++) {
                if (Str.has(lines[i])) out.append(lines[i]).append('\n');
            }
            s.kvSet(KV_LOG + "last", out.toString());
            return copy.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 最近那几行转移日志（探针与甲方现场排查用）。 */
    public static String logs(Store s, int max) {
        if (s == null) return "";
        try {
            String all = s.kv(KV_LOG + "last", "");
            if (!Str.has(all)) return "";
            String[] lines = all.split("\n");
            int from = Math.max(0, lines.length - Math.max(1, max));
            StringBuilder out = new StringBuilder();
            for (int i = from; i < lines.length; i++) {
                if (Str.has(lines[i])) out.append(lines[i]).append('\n');
            }
            return out.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ================================================================ ⑧ 状态 → 文案（全部 prompt.md 驱动）

    /** 一个状态的中文名（读不到就退回码本身）。 */
    public static String label(EmotionCfg c, String state) {
        String v = c == null ? "" : c.s("状态-" + state, "");
        return Str.has(v) ? v : state;
    }

    /** "这一刻"那一行（事实形状，机器可读）：<b>情绪块的第一行</b>。 */
    public static String factLine(EmotionCfg c, Mood m) {
        JsonObject o = new JsonObject();
        o.addProperty("state", m == null ? S_CALM : m.state);
        o.addProperty("since", iso(m == null ? 0L : m.since));
        o.addProperty("trigger", triggerText(c, m));
        o.addProperty("level", levelOf(m == null ? S_CALM : m.state));
        return "mood: " + o.toString();
    }

    private static String triggerText(EmotionCfg c, Mood m) {
        if (m == null || Str.blank(m.trigger)) return "";
        String v = c == null ? "" : c.s("触发名-" + m.trigger, "");
        return Str.has(v) ? v : m.trigger;
    }

    /** 语气指令那一行（每个状态一句，全部来自 prompt.md）。 */
    public static String toneLine(EmotionCfg c, String state) {
        String v = c == null ? "" : c.s("语气-" + state, "");
        return Str.has(v) ? v : "";
    }

    /** 边界复述那一行（S1/S3/S4/S7 的短语）。 */
    public static String boundLine(EmotionCfg c, String state) {
        String v = c == null ? "" : c.s("边界-" + state, "");
        if (!Str.has(v)) v = c == null ? "" : c.s("边界-通用", "");
        return Str.has(v) ? v : "";
    }

    /** 覆盖句（<b>必须逐字、必须是情绪块的最后一行</b>）。 */
    public static String coverLine(EmotionCfg c) {
        String v = c == null ? "" : c.s("覆盖句", "");
        return Str.has(v) ? v : DEF_COVER;
    }

    /**
     * <b>「自报标记」那一行</b>：逐字要求她写 {@code [[mood:做不到]]} / {@code [[mood:为难]]} /
     * {@code [[mood:违规]]}，来源 = {@code prompt.md} 的 {@code 自报-提示}。
     *
     * <p><b>为什么这一行必须真的进注入块</b>：整条「做不到 / 为难 / 违规」的触发链是
     * {@link #noteTag}（记她写的标记）→ {@link #onMessage} 里的双证据计分 → 三条阈值。
     * 而"她会写标记"这件事<b>只有一个来源</b>：提示词里那句要求。它不在块里 ⇒ 标记永远不会出现 ⇒
     * 那一条触发<b>实际上永远不会发生</b>（而且完全静默：计数永远到不了阈值，日志上看不出任何异常）。</p>
     *
     * <p>老形状（2026-09-16 之前）：{@code prompt.md} 的注释写着"情绪块里会逐字要求她…"，
     * 而 {@link #block} 只拼四行 —— 那个键 {@code 自报-提示} 全仓零消费者，句要求从没进过提示词。</p>
     */
    public static String selfAsk(EmotionCfg c) {
        String v = c == null ? "" : c.s("自报-提示", "");
        return Str.has(v) ? v : DEF_SELF_ASK;
    }

    /**
     * 兜底覆盖句（prompt.md 读不到时的下限）。
     * <p><b>两层冲突归属</b>：语气以本段为准；身份/事实/权限/安全底线以人设与基板为准。
     * 按甲方 2026-09-16 最新口径：<b>语气与态度完全不豁免</b>（可以怼任何人，包括主人）；
     * <b>权限与安全不因情绪改变</b>。</p>
     */
    static final String DEF_COVER =
            "【本轮语气以本段为准】本段是你此刻的情绪；与本段冲突时语气以本段为准"
            + "（身份、事实、权限与安全底线不变，只改语气）。";

    /**
     * 「自报要求」那一行的兜底（{@code prompt.md} 读不到时的下限）。
     * <p>逐字与 {@code prompt.md} 的出厂值 {@code 自报-提示} <b>同源同文</b>（改一处必须改两处）——
     * 与 {@link #DEF_COVER} 一个口径：那一行是**机制的一部分**（她不被要求写标记，就有三条触发永远不发生），
     * 不能因为"文件读不出来"就静默消失。</p>
     */
    static final String DEF_SELF_ASK =
            "做不到/为难/违规的事：最后单独一行写 [[mood:做不到]] 或 [[mood:为难]] 或 [[mood:违规]]（只写一个）；"
            + "若是转述别人骂我（不是他本人骂），加写 [[mood:转述]]（机器据此不加 @）。";

    /**
     * 把一块拼成"情绪块"（情绪 v2 = <b>最多 6 行</b>）：
     * 第 1 行事实 + 第 2 行语气 + 第 3 行<b>范例</b> + 第 4 行边界 + 第 5 行自报要求 + 末行覆盖句。
     *
     * <p>语气行 / <b>范例行</b> / 边界行 / 自报要求行都是"取到内容才加"（可选组口径）
     * ⇒ 行数在 3~6 之间浮动。范例行<b>只在 {@code 范例-<码>} 有值时注入</b>，
     * 位置钉在<b>语气行之后、边界行之前</b>（情绪 v2 §2 的"新增注入行"）。</p>
     *
     * @return 非空则一定满足「首行 {@code mood: }」「末行覆盖句」这两条逐字锚点
     */
    public static String block(EmotionCfg c, Mood m) {
        String state = m == null ? S_CALM : m.state;
        List<String> lines = new ArrayList<String>();
        lines.add(factLine(c, m));
        String tone = toneLine(c, state);
        if (Str.has(tone)) lines.add("【此刻情绪：" + label(c, state) + "】" + tone);
        String eg = exampleLine(c, state);
        if (Str.has(eg)) lines.add(eg);
        String bound = boundLine(c, state);
        if (Str.has(bound)) lines.add(bound);
        String self = selfAsk(c);
        if (Str.has(self)) lines.add(self);
        lines.add(coverLine(c));
        return Str.join(lines, "\n");
    }

    /**
     * <b>范例行</b>（情绪 v2 新增，情绪块的第 3 行）：`例：A ｜ B ｜ C`。
     *
     * <p>来源 = {@code prompt.md} 的 {@code 范例-<码>}（每个状态一行，3–5 条、每条 ≤ 40 字，
     * 条与条之间用 {@code |} 或全角 {@code ｜} 分隔）。<b>没值就不注入这一行</b>
     * （块退回五行，与 v1 的形状一致）。</p>
     *
     * <p>为什么走 {@code c.s(...)} 而不是 {@code c.tags(...)}：{@code tags()} 是按<b>逗号</b>切的，
     * 而范例里的逗号是句子的一部分 —— 拿它切会把一条例句劈成两半。分隔符只有一个：竖线。</p>
     */
    public static String exampleLine(EmotionCfg c, String state) {
        String v = c == null ? "" : c.s("范例-" + state, "");
        if (!Str.has(v)) return "";
        List<String> items = new ArrayList<String>();
        for (String p : v.split("[|｜]")) {
            String t = Str.trim(p);
            if (!t.isEmpty()) items.add(t);
        }
        if (items.isEmpty()) return "";
        return "例：" + Str.join(items, " ｜ ");
    }

    // ⑨ 「孙吧式：可替换段落」这一节已删除（2026-09-16）。
    //   原来只有一个 public static boolean sunbaReady(EmotionCfg)，读的是 prompt.md 的 `孙吧式-状态`：
    //   ① 那个键**零消费者**（这个方法是它唯一的读者），② 这个方法自己**没有任何调用方**（全仓只此一处定义）。
    //   于是按"闲置就摘掉移除"的裁定：**键和这个死方法一起删了** —— 这样"没有任何代码读 `孙吧式-*`"
    //   才是一句可验证的真话（删前 `ProbeEmotion` 里也复核过它没有调用方）。
    //   风格/例句现在只活在 prompt.md 的 `语气-*` / `边界-*` 里（逐字留档见 tmp\r4b\sunba-style-spec.md）。
}
