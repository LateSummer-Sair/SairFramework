import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import sair.v4.auth.Caller;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Ev;
import sair.v4.skill.FavorView;
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
 *
 * <p><b>批 15 / 2026-09-26</b>：本类所有读墙上时钟的地方都走 {@link #nowMs()}（可注入的"现在"，
 * 生产默认 = 真实墙上时钟）—— 于是「疲惫」那条时段门（{@code 深夜-起时}/{@code 深夜-止时}）
 * 能在夹具里被钉死，不会出现"白天绿、夜里红"。</p>
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

    // ---- 8 个「轻态」（情绪 v3，2026-09-26 甲方令「加入更多拟人的情绪状态」）----
    //   码一律 ASCII（与既有 6 档同风格）；中文名 / 语气 / 范例 / 边界 / 触发名全部走 prompt.md
    //   的既有动态族（`状态-<码>` / `语气-<码>` / `范例-<码>` / `边界-<码>` / `触发名-<码>`），
    //   判定信号也只用既有的五种（词表命中 / 时段 / 基板 favor / 既有档位 / 本轮正文），
    //   **不新增随机、不新增判定源、不新增 kv 字段**（见 {@link #lite}）。
    /** 戒备：低好感 + 要隐私/敏感话题/陌生人试探。 */
    public static final String S_CAREFUL = "careful";
    /** 委屈：被误解 / 被凶 / 被冤枉。 */
    public static final String S_WRONGED = "wronged";
    /** 心虚：她答错了、被指出错。 */
    public static final String S_GUILTY = "guilty";
    /** 吃醋：当着她的面夸别人（本轮「夸」+「第三方指称」同句）。 */
    public static final String S_JEALOUS = "jealous";
    /** 得意：被夸/被捧/办成事。 */
    public static final String S_PROUD = "proud";
    /** 害羞：被直球夸 / 被调情。 */
    public static final String S_SHY = "shy";
    /** 期待：他提到约定/约好的时间快到了。 */
    public static final String S_EXPECT = "expect";
    /** 疲惫：深夜 + 长聊/被连着盘。 */
    public static final String S_TIRED = "tired";

    private static final String[] STATES = {S_CALM, S_ANGRY, S_HAPPY, S_SAD, S_LETDOWN, S_STRIKE,
            S_CAREFUL, S_WRONGED, S_GUILTY, S_JEALOUS, S_PROUD, S_SHY, S_EXPECT, S_TIRED};

    /** {@code 触发阈-strike} 的兜底默认值（写 N = 「超过 N 次」，与其余触发阈同一个口径）。 */
    private static final int DEF_STRIKE_TH = 3;

    /** {@code 罢工上限分钟} 的兜底默认值（罢工最长几分钟；与 prompt.md 那行**同源**）。 */
    private static final int DEF_STRIKE_MAX_MIN = 10;

    /** {@code 罢工平息条数} 的兜底默认值（连续几条"没有情绪信号"的消息就退到 angry）。 */
    private static final int DEF_STRIKE_QUIET = 3;

    // ---- 敬重档（2026-09-26 甲方规格：好感度 >=700 ⇒ 不再对此人发怒 + 敬重口气）----
    //   ★ 批 15 / 2026-09-26（GM 裁定「阈值以基板为准」）：">=" 后面那个数**只有一个旋钮** ——
    //     基板配置 `favorNoAngerMin`；本技能那个键降级成**老 jar 的回退**（见 DEF_RESPECT / respectNeed）。

    /**
     * {@code 敬重档-好感度} 的兜底默认值（甲方规格的硬数字 700）。
     * <p>与 {@code prompt.md} 那一行<b>同源</b>（改一处必须改两处），口径与 {@link EmotionCfg#DEF_BUDGET} 相同。</p>
     *
     * <p><b>★ 批 15 / 2026-09-26（GM 裁定「阈值以基板为准」）：这条线现在只有一个旋钮 —— 基板
     * {@code favorNoAngerMin}。本常量（连带 {@code prompt.md} 里那个键）降级成**老 jar 的回退**：
     * 基板值读得到就用基板的，读不到（类/方法不存在）才用这里的 700。读数在
     * {@link #respectNeed(EmotionCfg)} 一处收口，判定仍然只有 {@link #respect} 那一处。</b></p>
     */
    public static final int DEF_RESPECT = 700;

    /** {@code 敬重-标签} 的兜底（与 prompt.md 同源；她/主人可以改文案，判据不变）。 */
    public static final String DEF_RESPECT_LABEL = "敬重";

    /**
     * {@code 敬重-行} 的兜底（{@code prompt.md} 读不出来时的下限，与那边<b>同源同文</b>，改一处必须改两处）。
     * <p>可用占位符：{@code {档位}}（基板 {@code FavorView.levelName} 的原话）、{@code {favor}}（基板读数）、
     * {@code {阈值}}。文案里刻意<b>不含</b> {@code 禁词-敬语} 的任何词（您/请问/很抱歉…）——
     * 那一族只在 angry/strike 生效，而敬重档照样可能落在 angry 态上（见 {@code MoodStage.veto}）。</p>
     */
    public static final String DEF_RESPECT_LINE =
            "他在我账上是「{档位}」（好感度 {favor}）：对这个人只发敬重、平稳的口气 —— "
            + "不发火、不阴阳、不怼、不说脏话；就算他骂我、挑衅我，这一轮也不进生气/暴怒/罢工那种语气。";

    /** 四个配置键名（只在源码里写一次，免得四处拼错；{@code prompt.md} 里逐键有说明）。 */
    public static final String K_RESPECT_ON = "敬重档开关";
    public static final String K_RESPECT_NEED = "敬重档-好感度";
    public static final String K_RESPECT_LABEL = "敬重-标签";
    public static final String K_RESPECT_LINE = "敬重-行";

    // ---- 轻态（情绪 v3）的 5 个配置键名（{@code prompt.md} 里逐键有说明）----

    /** 轻态总开关（{@code false} = 8 个新态一个都不进，整层回到 6 态）。 */
    public static final String K_LITE_ON = "轻态开关";
    /** 戒备的「低好感」门槛：基板 {@code FavorView.of(qq)} 低于它才算"低好感"。 */
    public static final String K_LITE_SHY_FAVOR = "戒备-好感度";
    /** 深夜时段的起（含）与止（不含）小时；{@code 起 > 止} = 跨零点（出厂 23→6）。 */
    public static final String K_NIGHT_FROM = "深夜-起时";
    public static final String K_NIGHT_TO = "深夜-止时";
    /** 「长聊」判据：她自己连续说了几条（{@code Mood.selfSends}）。 */
    public static final String K_TIRED_CHAT = "疲惫-长聊条数";

    /** {@code 戒备-好感度} 的兜底（与 prompt.md 同源）。 */
    private static final int DEF_LITE_FAVOR = 300;
    /** {@code 深夜-起时}/{@code 深夜-止时} 的兜底（与 prompt.md 同源）。 */
    private static final int DEF_NIGHT_FROM = 23;
    private static final int DEF_NIGHT_TO = 6;
    /** {@code 疲惫-长聊条数} 的兜底（与 prompt.md 同源）。 */
    private static final int DEF_TIRED_CHAT = 3;

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

    // ---- 可注入的「现在」（批 15 / 2026-09-26）----
    //
    // WHY：情绪里第一个**读墙上时钟**的判据是「疲惫」（`深夜-起时`/`深夜-止时` + `词-疲惫`）。
    //   门禁当前与时钟无关（既有夹具正文都不命中疲惫词表），但"将来谁往夹具里写一句含疲惫词表的
    //   正文，就会白天绿、夜里红"这件事必须能被**钉死** —— 所以给技能一个可注入的"现在"。
    //   默认（没注入）= 真实墙上时钟，生产行为一个字不改。

    /** 注入的「现在」（毫秒；{@code <=0} = 没注入 = 真实墙上时钟）。 */
    private static volatile long CLOCK_MS = 0L;

    /**
     * <b>把「现在」钉成一个固定值</b>（毫秒；{@code <=0} = 撤销注入、回到真实墙上时钟）。
     * <p>给夹具/探针用（本批：{@code tmp\v6-real\batch6\W15e}）：进程内任何一条 {@link #nowMs()} 都跟着它走，
     * 于是"白天 12:00 vs 夜里 02:00"能在同一台机器上**逐条复现**。生产路径永远不调它。</p>
     */
    public static void setNowMs(long ms) { CLOCK_MS = ms; }

    /** 注入的「现在」（{@code <=0} = 没注入）；只读，给口径回执用。 */
    public static long injectedNowMs() { return CLOCK_MS; }

    /**
     * <b>现在是什么时候</b>（毫秒）：注入值优先，否则真实墙上时钟。
     * <p>本类里**所有**读墙上时钟的地方都走这里（{@code on_message} 的 wall / {@code on_notice} /
     * {@code noteTag} / 事件时间兜底）。<b>新加读时钟的点也必须走它</b>，否则"钉住时间"这件事会漏。</p>
     * <p>参数里已经带着时刻的地方（{@code onMessage(...,now)} / {@code onTimer(...,now)} /
     * {@code noteSelfSend(...,now)}）仍然用参数：那是调用方给的**事件时间**（或它自己的墙上时钟），
     * 与"本类看见的现在"是两件事 —— 见 {@code onMessage} 里"网格只跟墙上时钟走"那段注释。</p>
     */
    public static long nowMs() {
        long v = CLOCK_MS;
        return v > 0L ? v : System.currentTimeMillis();
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
        // ---- 轻态（v3）的严重度：strike/angry/sad/letdown 那四个值**一个字都没动**（探针按字面钉 angry=3）。
        //   这里只给"从没出现过的新码"一个值；它只影响新码自己的事实行 `level` 字段与 dwellOk 的
        //   向上/向下判定（`from` 是新码才会读到），既有 6 态的路径逐字节不变。
        if (S_CAREFUL.equals(state)) return 2;      // 戒备 / 委屈 / 吃醋：比 letdown(1) 重
        if (S_WRONGED.equals(state)) return 2;
        if (S_JEALOUS.equals(state)) return 2;
        if (S_GUILTY.equals(state)) return 1;       // 心虚 / 期待 / 得意 / 害羞 / 疲惫：与 letdown 同档
        if (S_EXPECT.equals(state)) return 1;
        if (S_PROUD.equals(state)) return 1;
        if (S_SHY.equals(state)) return 1;
        if (S_TIRED.equals(state)) return 1;
        return 0;
    }

    /**
     * <b>「谁压谁」的优先级梯子</b>（2026-09-26 GM 已钉死的 14 态优先级；<b>只有这一个来源</b>）。
     *
     * <p>GM 原话的次序：{@code 罢工 > 敬重（对该人 favor>=阈值；阈值 = 基板 favorNoAngerMin，批 15）> 发怒 > 戒备 > 委屈 ≈ 吃醋 > 心虚 >
     * 失落 > 期待/得意/害羞 > 疲惫 > 开心 > 平静}。落地成整数之后，{@link #decide} 用它做
     * <b>「一格」裁决</b>（同一轮只有一个状态赢，绝不叠加）。</p>
     *
     * <p><b>两处必须写清的落差</b>（都不改这个梯子，如实记在报告里）：</p>
     * <ol>
     *   <li>GM 那张表里<b>没有「伤心」（sad）</b>。它是既有的第 4 档（被贬低攒出来的），
     *       我把它插在<b>发怒之后、戒备之前</b>（11）—— 依据是既有的 {@link #levelOf}：
     *       angry(3) &gt; sad(2) &gt; letdown(1)，且 sad 是"负向态"，不该被一个轻态顶掉。</li>
     *   <li><b>敬重不是状态</b>（K7 的落点是语气面）：它排在 12 与 11 之间，实现方式是
     *       "进敬重档时<b>不让任何轻态进来</b>"（{@link #lite} 的 {@code respOn} 前置判）——
     *       于是敬重照样压过发怒与下面所有轻态（K7 的成果一个字没改）。</li>
     * </ol>
     */
    static int rank(String state) {
        if (S_STRIKE.equals(state)) return 13;
        if (S_ANGRY.equals(state)) return 12;
        if (S_SAD.equals(state)) return 11;         // GM 表里没有它：插在发怒之后（见上）
        if (S_CAREFUL.equals(state)) return 10;     // 戒备
        if (S_WRONGED.equals(state)) return 9;      // 委屈 ≈ 吃醋：同档，先判委屈（见 lite 的判序）
        if (S_JEALOUS.equals(state)) return 9;      // 吃醋
        if (S_GUILTY.equals(state)) return 8;       // 心虚
        if (S_LETDOWN.equals(state)) return 7;      // 失落
        if (S_EXPECT.equals(state)) return 6;       // 期待 / 得意 / 害羞：同档
        if (S_PROUD.equals(state)) return 6;
        if (S_SHY.equals(state)) return 6;
        if (S_TIRED.equals(state)) return 5;        // 疲惫
        if (S_HAPPY.equals(state)) return 4;        // 开心
        return 3;                                   // 平静（含一切读不出来的码）
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
        long evNow = now > 0L ? now : nowMs();                        // ★ 批 15：兜底也走接缝（默认=真实时钟）
        long gid = ev.groupId() > 0L ? ev.groupId() : 0L;
        String scopeId = scopeOf(gid, qq);
        // ★ 网格只跟**墙上时钟**走，不跟"这条消息的事件时间"走。
        //   理由（我踩到的一个真坑）：带外钩子（on_notice）与 tick 用的都是墙上时钟，
        //   而 on_message 用的是事件时间（可能是上游机器给的、可能比本地早很多）。
        //   两者混用会让网格**往回跳**，于是网格要么被反复重置、要么永远重置不了。
        //   事件时间仍然有用——它只用来算"被晾了多久/间隔"，不参与网格。
        long wall = nowMs();                    // ★ 批 15：可注入的"现在"（默认 = 真实墙上时钟）
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
            // ---- ★ v3 轻态（6 态 → 14 态）：先算出"这一条消息想把她带进哪个轻态"（至多一个），
            //   再把它交给 decide() 与现场的高优先级态**比一个 rank**（"一格"，不叠加）。
            //   判据全部是既有信号；敬重档进档时整层让位（敬重 > 全部轻态，K7 的成果一个字没改）。
            boolean respOn = respect(c, h, null, qq).on;
            Lite lt = lite(c, h, m, text, wall, qq, sig, fpSeen, respOn);
            String fromD = m.state;
            // ---- 评估（唯一结论）
            decide(m, c, r, wall, qq, valid, praiseOk, fp, fpSeen, sig, lt);
            // ---- 轻态口径回执（一行 dim；只在真有候选时打，绝不进情绪块 —— A21 靠它）
            if (Str.has(lt.code)) traceLite(h, scopeId, lt, fromD, m.state);
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

    // ================================================================ ①b 轻态（情绪 v3：6 态 → 14 态）

    /**
     * 一次轻态判定的结果（{@link #lite} 的返回）。
     * <p>{@code code} 为空串 = 这一轮**不进任何轻态**（这也是"轻态开关关掉/读不到好感/没命中"的表示）；
     * {@code why} 只用于<b>口径回执</b>（一行 dim，见 {@link #traceLite}），<b>绝不进情绪块</b>。</p>
     */
    public static final class Lite {
        /** 中选的轻态码（{@link #S_CAREFUL} … {@link #S_TIRED}；空串 = 没有）。 */
        public String code = "";
        /** 为什么（人话，进回执）。 */
        public String why = "";
    }

    /**
     * <b>轻态判定</b>（情绪 v3，2026-09-26 甲方令「加入更多拟人的情绪状态进去，原理同愤怒情绪一样
     * 可以轻度干涉设定」）：<b>至多返回一个</b>轻态码。
     *
     * <h3>只用既有信号（一个都不新造）</h3>
     * <ul>
     *   <li>{@code 词-<标签>} 词表命中（既有「关键词表」机制，热改即生效）：8 张新表；</li>
     *   <li>{@code 时段}（墙上时钟的小时数；{@code 深夜-起时}/{@code 深夜-止时}，走 {@link #nowMs()}）—— 只用于「疲惫」；</li>
     *   <li>基板 {@code favor}（{@code Host.favor() → FavorView.of(qq)}，与敬重档同一个读数）—— 只用于「戒备」；</li>
     *   <li>既有档位（{@code m.state} 的当前值）—— 由 {@link #rank} 的裁决使用；</li>
     *   <li>本轮已有的正文信号（{@code sig.praise}）与既有账本（{@code fpSeen} = 同一个要求又来了、
     *       {@code m.selfSends} = 她自己连说了几条）。</li>
     * </ul>
     * <p><b>刻意没用的两个东西</b>：① <b>没有随机</b>（同输入两次必然同结果，A21 靠它）；
     * ② <b>没有在 provider 里算</b> —— 这一层只在 {@code on_message}/{@code on_timer} 里有真实时钟，
     * provider 走的是 {@code peek(now=0)}（只读、不取时钟）⇒ "同一状态两次 build 逐字节一致"永远成立。</p>
     *
     * <h3>判序 = 优先级梯子的一部分</h3>
     * <p>从高到低逐态判（戒备 → 委屈 → 吃醋 → 心虚 → 期待 → 得意 → 害羞 → 疲惫），
     * <b>第一个命中的就是候选</b>；它能不能真的赢，由 {@link #decide} 拿 {@link #rank} 与
     * 现场的高优先级态比出来（"一格"）。委屈与吃醋同档（GM 写的 {@code ≈}）⇒ 这里<b>委屈先判</b>。</p>
     *
     * @param respOn 这一轮是不是敬重档（对该人 favor ≥ 阈值）—— 是 ⇒ 轻态一个都不进（敬重 &gt; 全部轻态）
     */
    static Lite lite(EmotionCfg c, Host h, Mood m, String text, long now, long qq,
                     EmotionCfg.Sig sig, boolean fpSeen, boolean respOn) {
        Lite r = new Lite();
        try {
            if (c == null || m == null) return r;
            if (!c.flag(K_LITE_ON, true)) { r.why = "开关:false"; return r; }
            if (respOn) { r.why = "敬重档（轻态让位）"; return r; }
            if (Str.blank(text)) return r;
            // ---- ① 戒备：低好感 + 要隐私/敏感话题/陌生人试探（词表命中 **且** 基板 favor 低于门槛）
            if (c.hasWord(text, "戒备")) {
                double fav = favorNum(h, qq);
                int need = c.i(K_LITE_SHY_FAVOR, DEF_LITE_FAVOR);
                if (Double.isNaN(fav)) {
                    r.why = "戒备:读不到好感（fail-closed）";
                } else if (fav < (double) need) {
                    r.code = S_CAREFUL;
                    r.why = "词:戒备 favor=" + favorStr(fav) + "<" + need;
                    return r;
                } else {
                    r.why = "戒备:好感 " + favorStr(fav) + ">=" + need;
                }
            }
            // ---- ② 委屈：被误解 / 被凶 / 被冤枉（词表）
            if (c.hasWord(text, "委屈")) { r.code = S_WRONGED; r.why = "词:委屈"; return r; }
            // ---- ③ 吃醋：当着她的面夸别人（词表；或"本轮有夸 + 同句有第三方指称"这一对既有信号）
            if (c.hasWord(text, "吃醋")) { r.code = S_JEALOUS; r.why = "词:吃醋"; return r; }
            if (sig != null && sig.praise && c.hasWord(text, "第三方指称")) {
                r.code = S_JEALOUS;
                r.why = "夸+第三方指称";
                return r;
            }
            // ---- ④ 心虚：她答错了、被指出错（词表）
            if (c.hasWord(text, "心虚")) { r.code = S_GUILTY; r.why = "词:心虚"; return r; }
            // ---- ⑤ 期待：他提到约好的事/时间快到了（词表；"快到了"无法从库里判，见报告）
            if (c.hasWord(text, "期待")) { r.code = S_EXPECT; r.why = "词:期待"; return r; }
            // ---- ⑥ 得意：被夸/被捧/办成事（词表）
            if (c.hasWord(text, "得意")) { r.code = S_PROUD; r.why = "词:得意"; return r; }
            // ---- ⑦ 害羞：被直球夸 / 被调情（词表；红线仍在，见 prompt.md 的 边界-shy）
            if (c.hasWord(text, "害羞")) { r.code = S_SHY; r.why = "词:害羞"; return r; }
            // ---- ⑧ 疲惫：深夜 **且**（她自己也喊累 / 被连着盘 / 长聊）
            if (night(c, now)) {
                if (c.hasWord(text, "疲惫")) { r.code = S_TIRED; r.why = "深夜+词:疲惫"; return r; }
                if (fpSeen) { r.code = S_TIRED; r.why = "深夜+被连着盘"; return r; }
                int chat = c.i(K_TIRED_CHAT, DEF_TIRED_CHAT);
                if (chat > 0 && m.selfSends >= chat) {
                    r.code = S_TIRED;
                    r.why = "深夜+长聊 " + m.selfSends + ">=" + chat;
                    return r;
                }
            }
        } catch (Throwable t) {
            r.code = "";
            r.why = "异常";
        }
        return r;
    }

    /**
     * 现在是不是「深夜」（{@code 深夜-起时} 含 / {@code 深夜-止时} 不含）。
     * <p>{@code 起 <= 止} = 不跨零点（例：{@code 1}/{@code 5} ⇒ 01:00–04:59）；
     * {@code 起 > 止} = 跨零点（出厂 {@code 23}/{@code 6} ⇒ 23:00–05:59）；
     * {@code 起 == 止} 或 {@code now <= 0} ⇒ 这一条判据<b>关掉</b>（fail-closed，不会"整天都算深夜"）。</p>
     * <p>它只在 {@code on_message}/{@code on_timer}（有真实时钟）里被读；provider 从不读时钟。
     * 批 15：{@code on_message} 那条路上传进来的 {@code now} 就是 {@link #nowMs()}
     * （可注入，默认 = 真实墙上时钟）。</p>
     */
    static boolean night(EmotionCfg c, long now) {
        if (now <= 0L) return false;
        int from = c == null ? DEF_NIGHT_FROM : c.i(K_NIGHT_FROM, DEF_NIGHT_FROM);
        int to = c == null ? DEF_NIGHT_TO : c.i(K_NIGHT_TO, DEF_NIGHT_TO);
        if (from == to) return false;
        int h = hourOf(now);
        if (h < 0) return false;
        if (from < to) return h >= from && h < to;
        return h >= from || h < to;
    }

    /** 墙上时钟的小时（0–23）；拿不到 = -1。 */
    static int hourOf(long now) {
        try {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now);
            return cal.get(Calendar.HOUR_OF_DAY);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 基板好感度读数（{@code FavorView.of(qq)}；读不到 = {@code NaN}）。
     * <p>与敬重档<b>同一个来源</b>：本插件不自己算一个数，也不读「亲疏」（那是另一台机器）。</p>
     */
    static double favorNum(Host h, long qq) {
        try {
            if (h == null || qq <= 0L) return Double.NaN;
            FavorView f = h.favor();
            return f == null ? Double.NaN : f.of(qq);
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /**
     * 轻态的口径回执（一行 dim，{@code logConsole} 含 {@code skill} 才看得见）。
     * <p>用途与 {@code cue=} / {@code respect=} 同一条：<b>把"这一轮没进轻态"与"进了她没听"分开</b>，
     * 并把冲突裁决摊开（{@code win=0} + {@code from>to} 就是"轻态被更高优先级压住了"的原始证据）。
     * <b>只进日志、绝不进情绪块</b>。</p>
     */
    private static void traceLite(Host h, String scopeId, Lite lt, String from, String to) {
        try {
            if (h == null || lt == null || !Str.has(lt.code)) return;
            Out o = h.out();
            if (o == null) return;
            o.dim("[情绪] lite=" + lt.code
                    + " rank=" + rank(lt.code)
                    + " from=" + from + ">" + to
                    + " win=" + (lt.code.equals(to) ? 1 : 0)
                    + " scene=" + scopeId
                    + " why=" + lt.why);
        } catch (Throwable ignored) {
        }
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
     * @param L  v3 轻态候选（{@link #lite} 的产物；{@code null} 或 {@code code} 为空 = 这一轮没有轻态候选）。
     *           它在<b>即刻通道（骂/求饶）之后</b>、常规负向通道与正向通道<b>内部</b>参与裁决，
     *           裁决判据只有一个：{@link #rank}（同一轮只有一个状态赢 —— "一格"）。
     */
    private static void decide(Mood m, EmotionCfg c, Result r, long now, long qq,
                               boolean[] valid, boolean praiseOk, String fp, boolean fpSeen,
                               EmotionCfg.Sig sig, Lite L) {
        String from = m.state;
        r.from = from;
        r.to = from;
        r.by = qq;
        int cur = levelOf(from);
        boolean neg = S_ANGRY.equals(from) || S_SAD.equals(from) || S_LETDOWN.equals(from);
        int peaceNeed = c.i("缓和分", 3);
        // ---- v3 轻态候选的现场视图（空串 = 没有候选；下面三处裁决都用它）
        String liteCode = L == null ? "" : L.code;
        String liteWhy = L == null ? "" : L.why;
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
                // ---- ★ v3 轻态裁决（正向这一路）：轻态比 happy 高、也比当前态高 ⇒ 走轻态。
                //   （得意/害羞/期待/疲惫 都比 happy 高；戒备/委屈/心虚/吃醋 也比它高 ⇒ 这一句自然覆盖。）
                if (Str.has(liteCode) && rank(liteCode) > rank(S_HAPPY) && rank(liteCode) > rank(from)) {
                    move(m, r, now, qq, liteCode, liteCode, 0, 0, "轻态：" + liteWhy);
                    return;
                }
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
            // ---- ★ v3 轻态裁决（没有常规负向通道命中这一路）：这才轮到轻态说话。
            //   两条口径：已经在轻态里 ⇒ 同态续期（不重复打转移行，与那 6 档同一个口径）；
            //   不在（或在一个更低的档） ⇒ 只有 rank 更高才改判 —— 于是"疲惫+开心"里开心让位、
            //   "失落 vs 疲惫"里失落不被降级（GM 的梯子：失落 > 疲惫）。
            if (Str.has(liteCode)) {
                if (liteCode.equals(from)) {
                    m.by = qq;
                    r.renewed = true;
                    r.n = 0;
                    r.th = 0;
                    r.reason = "轻态续期：" + liteWhy;
                    return;
                }
                if (rank(liteCode) > rank(from)) {
                    move(m, r, now, qq, liteCode, liteCode, 0, 0, "轻态：" + liteWhy);
                    return;
                }
            }
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
        // ---- ★ v3 轻态裁决（常规负向这一路）：轻态比这一路的 `to` 高、**也比当前态高** ⇒ 改判轻态。
        //   这一句就是"同一轮只能进一个状态"的落点：两边比的是同一个 {@link #rank}，谁也不叠加。
        //   被改判时这一路的计数照旧消耗（与它自己命中时的口径一致），免得旧计数在账上再顶回来。
        //   走不到这一句的常见侧：`from` 已经是 strike/angry/sad（ranker 更高，轻态赢不了）。
        if (Str.has(liteCode) && rank(liteCode) > rank(from)
                && (to == null || rank(liteCode) > rank(to))) {
            if (gi == G_POKE) m.pokeGrp = 0;
            clearHit(m, gi, fp);
            move(m, r, now, qq, liteCode, liteCode, 0, 0, "轻态：" + liteWhy);
            return;
        }
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
        if (!dwellOk(from, rank(to), now, c, m)) {
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
     *
     * <p><b>v3（14 态）只改了一处</b>：强弱比较从 {@code levelOf} 换成 {@link #rank}（那条
     * "谁压谁"的梯子）。对既有 6 态<b>完全等价</b> —— 两条函数在 6 态上的次序一模一样
     * （strike &gt; angry &gt; sad &gt; letdown &gt; happy = calm），而这里只做次序比较
     * （{@code >=}）；对新加的 8 个轻态则得到与 GM 梯子一致的方向（例：心虚(8) 不会被 失落(7) 降级）。</p>
     */
    private static boolean dwellOk(String from, int toLevel, long now, EmotionCfg c, Mood m) {
        long floor = c.i("停留下限秒", 60) * 1000L;
        if (floor <= 0L) return true;
        if (S_CALM.equals(from)) return true;               // 平淡不是强度：第一次被惹毛立刻生效
        if (toLevel >= rank(from)) return true;             // 向上 / 同强度：立刻生效
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
        long now = nowMs();                     // ★ 批 15：同一个接缝（默认 = 真实墙上时钟）
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
            // ★ v3：戳这一路**没有正文** ⇒ 一个轻态都不可能命中（lite() 对空正文直接返回空），
            //   所以最后一个参数传 null —— 这一路的判据与结果与改前逐字节一致。
            decide(m, c, r, now, qq, pokeValid, false, null, false, c.sig(null), null);
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
            m.selfTagTs = now <= 0L ? nowMs() : now;               // ★ 批 15：同一个接缝
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

    /**
     * 从回复正文里读自报标记（{@code [[mood:X]]}）；没有、或不在允许清单里就是空串。
     *
     * <p><b>★★ 2026-09-22「归一与可观测批」（W2）：识别只有一处（基板）★★</b>
     * 这里原来自己写了一份<b>更窄</b>的识别（{@code reply.indexOf("[[mood:")} —— 只认
     * <b>小写 {@code mood}、恰好两个半角中括号、冒号紧跟</b>）⇒ 她换个写法自报
     * "做不到 / 为难 / 违规"就<b>根本不记分</b>（记分口径 {@code self_tag}）。
     * 现在那份自带识别<b>整段删除</b>，直接转发基板
     * {@link sair.v4.qq.MoodMarks#tagOf(String)} —— 与 {@code MoodStage} 的剥离、
     * {@code term.Sinks.gate} 与 {@code qq.Api} 的内容级闸<b>同一个判据</b>
     * （"全树只有一处识别"从本批起真的成立）。</p>
     *
     * <p><b>这是行为变化（变宽，不是变窄）</b>：{@code [mood:做不到]}（一个中括号）、
     * {@code [[MOOD:做不到]]}（大写）、{@code [[mood: 做不到 ]]}（冒号后带空格）
     * 现在<b>也会记分</b>；基板<b>刻意不认</b>全角方括号 {@code ［［mood:x］］}
     * 与夹了零宽字符的写法（见 {@code MoodMarks} 类注释："扩大识别面 = 把今天照发的东西
     * 改成丢掉，那是行为回归"）。{@code 转述} 的识别结果与改前**逐字相同**（它本来就在
     * {@code prompt.md} 的 {@code 自报-键} 里），而记分那一侧本来就不为它加分
     * （{@code onMessage} 只把 做不到/为难/违规 映射成 cant/hard/violate）；
     * "本轮一个字都不 @" 那半件事仍归 {@code MoodStage}。</p>
     */
    public static String tagOf(EmotionCfg c, String reply) {
        if (Str.blank(reply)) return "";
        String raw = sair.v4.qq.MoodMarks.tagOf(reply);   // ★ 识别只有一处（基板 MoodMarks）
        if (!Str.has(raw)) return "";
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

    // ================================================================ ⑦b 敬重档（基板好感度分档）

    /**
     * 「敬重档」的一次判定结果（<b>只读</b>：读的是<b>基板好感度</b>，本插件一个字都不写、
     * 也不改状态机 —— 状态该生气还生气，改的只是"对她怎么说话"）。
     *
     * <p><b>两套数，别混（甲方 2026-09-26 规格里最容易读错的一处）</b>：
     * {@code 亲疏}（{@link EmotionState} 那一台 0~1000 的机器，档位层在「态度/关系」表里读它）
     * 是她<b>当下的情绪量</b> —— 随消息涨落、一个 20 分钟网格里就能变；
     * 基板 {@link FavorView#of(long)} 是<b>对这个人长期的账</b>（事实块的 {@code caller.favor}、
     * 她用 {@code <favor …/>} 写的那个数）。敬重档<b>只认后者</b>，{@code 亲疏} 一个字段都不读。</p>
     */
    public static final class Respect {
        /** 进档了吗（{@code true} = 这一轮对他按敬重档说话）。 */
        public boolean on;
        /** 读到的好感度（<b>读数</b>，不自算；回执 {@link #why} 里的那个数就是它）。 */
        public double favor;
        /** 生效阈值（**基板 {@code favorNoAngerMin}**；读不到 = 本技能的 {@code 敬重档-好感度}，见 {@link #respectNeed}）。 */
        public int need;
        /** 档位名：基板 {@link FavorView#levelName(double)} 的原话，<b>不是</b>本插件自己算的。 */
        public String level = "";
        /** 填好占位符的敬重文案（语气行去掉前缀的那一半，{@code EmotionProvider.trimBlock} 的第 ④ 刀用它）。 */
        public String body = "";
        /** 已经拼好的语气行（{@code 【此刻情绪：敬重】…}）；空串 = 没进档。 */
        public String head = "";
        /** 口径回执（形如 {@code favor:750>=700}；只进日志，见 {@link EmotionProvider}）。 */
        public String why = "";
    }

    /**
     * <b>敬重档的生效阈值</b>（这条线**唯一的收口处**）：<b>基板 {@code favorNoAngerMin} 优先</b>；
     * 读不到（老 jar / 类或方法不存在）才退回本技能的 {@code 敬重档-好感度}
     * （{@link #K_RESPECT_NEED}，出厂 {@link #DEF_RESPECT}）。
     *
     * <p><b>★ 批 15 / 2026-09-26（GM 裁定「阈值以基板为准」）</b>：这条线原来有**两个旋钮** ——
     * 基板 {@code favorNoAngerMin}（出厂 700，唯一真源 {@code sair.v4.auth.FavorGate}）与本技能的
     * {@code 敬重档-好感度}（出厂 700）。主人把基板那个改成 650，情绪侧仍然按 700 判"敬重档"：
     * 同一个门槛、两份真相。裁定：**以基板为准**；技能只在读不到基板值时退回自己那个键。</p>
     *
     * <p><b>为什么用反射、不直接写 {@code FavorGate.noAngerMin()}</b>：技能源码是在**运行期**由基板
     * 编译的（{@code sair.v4.hot.SkCompile}）。老 jar 上那个方法不存在 ⇒ 直接调用会让**编译**失败
     * ⇒ **整层技能不加载** —— 那正是既有硬要求禁止的事（技能不能因为 jar 版本差异整层不加载）。
     * 反射把"版本差异"关进 try/catch：类不存在 / 方法不存在 / 调不动，一律走回退，**绝不抛出去**。</p>
     *
     * <p>读到的值**原样使用**（含 {@code <=0}）：{@code <=0} 仍按 {@link #respect} 里那条既有
     * fail-safe 走「整档关」——基板把门槛写成 0、和本地键写成 0 是同一件事，不再有两套口径。</p>
     */
    public static int respectNeed(EmotionCfg c) {
        int sub = substrateNoAngerMin();
        if (sub != NO_SUBSTRATE_NEED) return sub;
        return c == null ? DEF_RESPECT : c.i(K_RESPECT_NEED, DEF_RESPECT);
    }

    /** 基板值读不到的哨兵（{@link Integer#MIN_VALUE} = 没有 / 读不动 ⇒ 退回本技能那个键）。 */
    private static final int NO_SUBSTRATE_NEED = Integer.MIN_VALUE;

    /**
     * 基板 {@code favorNoAngerMin} 的**防御式**读数（反射；读不到 = {@link #NO_SUBSTRATE_NEED}）。
     * <p>读的是基板的**生效值**（{@code FavorGate.noAngerMin()} 每次现读它自己那份 {@code Conf}）——
     * 主人改了配置、重启之后这一侧跟着走；基板没注入 {@code Conf} 时它自己给出口 700（基板的出厂值）。</p>
     * <p><b>这里必须整段吞 {@code Throwable}</b>：一个读数取不到，绝不能把技能装载/一轮对话搞死。</p>
     */
    private static int substrateNoAngerMin() {
        try {
            Class<?> fg = Class.forName("sair.v4.auth.FavorGate");
            Method m = fg.getMethod("noAngerMin");
            Object v = m.invoke(null);
            if (v instanceof Number) return ((Number) v).intValue();
            return NO_SUBSTRATE_NEED;
        } catch (Throwable t) {
            return NO_SUBSTRATE_NEED;               // 老 jar / 类或方法不存在：退回本地键（绝不抛）
        }
    }

    /**
     * 判「这个人是不是敬重档」—— 甲方 2026-09-26 规格：{@code favor >= 阈值}
     * （阈值见 {@link #respectNeed}：基板 {@code favorNoAngerMin} 优先）⇒
     * <b>不再对此人发怒</b>（压过既有的生气/暴怒/罢工语气）且<b>用敬重、平稳的口气说话</b>。
     *
     * <p><b>不进档 ⇒ 与改前逐字节一致</b>，共五种情况（每一条都留了回执）：</p>
     * <ol>
     *   <li>{@code 敬重档开关: false}（总开关关掉）；</li>
     *   <li>生效阈值 {@code <= 0} ⇒ <b>整档关掉</b>（fail-safe：一行写坏的阈值不该让所有人都进
     *       敬重档）—— 批 15 之后这个阈值默认取自基板 {@code favorNoAngerMin}（见 {@link #respectNeed}），
     *       本地 {@code 敬重档-好感度} 只在读不到基板值时才用它；</li>
     *   <li>读数低于阈值；</li>
     *   <li><b>主人与她本人（SYSTEM）不受影响</b>：主人恒定走原路（旧口径"语气与态度完全不豁免，
     *       可以怼任何人，包括主人"一个字不改），判据同时看 caller 身份与 {@code masterQQ}；</li>
     *   <li>拿不到好感面（host 为 null / {@code Host.favor()} 为 null）或读的时候出了任何事
     *       ⇒ 一律旧路（宁可发怒，也不因为一个读数失败改变语气）。</li>
     * </ol>
     *
     * @param who 本轮的说话人（{@code BuildContext.caller()} / {@code Outbound.caller()}）；可空
     * @param qq  情绪的对象（通常是同一人；主人判定按它兜一遍）
     */
    public static Respect respect(EmotionCfg c, Host h, Caller who, long qq) {
        Respect r = new Respect();
        try {
            if (c == null) {
                r.why = "无配置";
                return r;
            }
            r.need = respectNeed(c);            // ★ 批 15：基板 favorNoAngerMin 优先（读不到才回退本地键）
            if (!c.flag(K_RESPECT_ON, true)) {
                r.why = "开关:false";
                return r;
            }
            if (r.need <= 0) {
                r.why = "阈值<=0（整档关）";
                return r;
            }
            if (qq <= 0L) {
                r.why = "无对象";
                return r;
            }
            if (who != null && (who.master() || who.system())) {
                r.why = "主人/SYSTEM";
                return r;
            }
            if (isMaster(h, qq)) {
                r.why = "主人";
                return r;
            }
            FavorView f = h == null ? null : h.favor();
            if (f == null) {
                r.why = "无好感面";
                return r;
            }
            r.favor = f.of(qq);                        // ★ 读数：基板那一份，本插件不自己算
            r.level = Str.nz(f.levelName(r.favor));    // ★ 档位名：基板那一份
            if (r.favor < (double) r.need) {
                r.why = "favor:" + favorStr(r.favor) + "<" + r.need;
                return r;
            }
            r.on = true;
            r.why = "favor:" + favorStr(r.favor) + ">=" + r.need;
            r.body = EmotionCfg.fill(respectLine(c),
                    "档位", r.level,
                    "favor", favorStr(r.favor),
                    "阈值", String.valueOf(r.need),
                    "谁", "他");
            r.head = Str.has(r.body) ? ("【此刻情绪：" + respectLabel(c) + "】" + r.body) : "";
        } catch (Throwable t) {
            r.on = false;
            r.head = "";
            r.why = "异常";
        }
        return r;
    }

    /** 这个 QQ 是不是主人（读不到 masterQQ = 不是）。 */
    private static boolean isMaster(Host h, long qq) {
        try {
            long m = h == null || h.conf() == null ? 0L : h.conf().masterQQ();
            return m > 0L && qq == m;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 敬重档的展示标签（{@code 敬重-标签}，读不到用 {@link #DEF_RESPECT_LABEL}）。 */
    public static String respectLabel(EmotionCfg c) {
        String v = c == null ? "" : c.s(K_RESPECT_LABEL, "");
        return Str.has(v) ? v : DEF_RESPECT_LABEL;
    }

    /** 敬重档的语气文案（{@code 敬重-行} 逐字，读不到用 {@link #DEF_RESPECT_LINE}）。 */
    public static String respectLine(EmotionCfg c) {
        String v = c == null ? "" : c.s(K_RESPECT_LINE, "");
        return Str.has(v) ? v : DEF_RESPECT_LINE;
    }

    /** 好感度显示：整数值不打小数点（{@code 750} 而不是 {@code 750.0}；回执与占位符共用）。 */
    static String favorStr(double v) {
        long r = Math.round(v);
        if (Math.abs(v - (double) r) < 0.0001D) return String.valueOf(r);
        return String.valueOf(v);
    }

    // ================================================================ ⑧ 状态 → 文案（全部 prompt.md 驱动）

    /** 一个状态的中文名（读不到就退回码本身）。 */
    public static String label(EmotionCfg c, String state) {
        String v = c == null ? "" : c.s("状态-" + state, "");
        return Str.has(v) ? v : state;
    }

    /** "这一刻"那一行（事实形状，机器可读）：<b>情绪块的第一行</b>（不带"对方情绪标签"那一格）。 */
    public static String factLine(EmotionCfg c, Mood m) {
        return factLine(c, m, "");
    }

    /**
     * {@link #factLine(EmotionCfg, Mood)} + <b>「对方最近一条消息的情绪标签」那一格</b>
     * （2026-09-27 批 17 L1：把状态机算出来的东西<b>还给她</b>）。
     *
     * <p><b>甲方口径（逐字）</b>：「为什么不把语境语义这些东西交给AI自己分析呢？我们提供这个情绪状态机
     * 就好了呀。」—— 这个标签每轮都算出来了（{@link EmotionState#peek} 的 {@code lastEmotion}），
     * 但在批 17 之前它<b>只被 {@link #cue} 的否决②读</b>（当"不许发"的判据），<b>从不给她看</b>。
     * 本批在事实行里加一格 {@code "them"}，并在<b>同一行</b>再给一格 {@code "themRead"}
     * （= {@code prompt.md} 的 {@code 标签读法}，教她怎么用这一格）。</p>
     *
     * <p><b>为什么两格都只加在首行里</b>：情绪块 ≤7 行是硬约束（首行事实 + 语气 + 范例 + 边界 +
     * 自报 + 可选时机 + 覆盖句已经满了），所以"提供 + 引导"都只能落在**已经存在的那一行**里 ——
     * 不加行、不改行序、不动 {@link EmotionProvider#trimBlock} 的降级梯子。</p>
     *
     * <p><b>显示口径 = legacy 那一行同一份</b>（{@link #themTag}）：空值不显示、"平静"不显示；
     * 于是"平静/空值"那一轮的事实行与"加这一格之前"<b>逐字节一致</b>。
     * {@code themRead} 与 {@code them} 同进同出（没有标签那格时不必解释那一格）。</p>
     *
     * <p><b>纪律（措辞已按磁盘校正 —— 非写者 V17 抓到"一个中文字都不新增"按字面不成立）</b>：
     * <b>面向她的文案 100% 住在 {@code prompt.md}</b> —— 标签来自既有规则层（{@code 情绪-*}/{@code 触发-*}），
     * 读法文案来自 {@code prompt.md} 的 {@code 标签读法}（读不到就不给那一格，不造第二份文案）。Java 里新增的
     * <b>不是文案</b>，只有<b>两个配置键名</b>：{@code "标签读法"}（新增）与 {@code "平静标签"}
     * （从 {@link EmotionProvider#legacy} 搬迁到 {@link #themTag}，同键同义）。</p>
     *
     * @param lastEmotion 对方最近一条消息的情绪标签（{@link EmotionProvider#build} 里那一次
     *                    {@code EmotionState.peek} 的产物；拿不到就给空串）
     */
    public static String factLine(EmotionCfg c, Mood m, String lastEmotion) {
        JsonObject o = new JsonObject();
        o.addProperty("state", m == null ? S_CALM : m.state);
        o.addProperty("since", iso(m == null ? 0L : m.since));
        o.addProperty("trigger", triggerText(c, m));
        o.addProperty("level", levelOf(m == null ? S_CALM : m.state));
        String them = themTag(c, lastEmotion);
        if (Str.has(them)) {
            o.addProperty("them", them);
            String how = c == null ? "" : c.s("标签读法", "");
            if (Str.has(how)) o.addProperty("themRead", how);   // ★ 引导面：文案住 prompt.md
        }
        return "mood: " + o.toString();
    }

    /**
     * 「对方最近一条消息的情绪标签」<b>要不要显示</b>（V3 口径，与 legacy 那一行**同一处判据**）：
     * <b>空值不显示、"平静"不显示</b>（{@code 平静标签} 那个值 = "这一条没有情绪"）。
     * <p><b>与改前的关系（措辞已按磁盘校正 —— 非写者 V17 抓到）</b>：<b>空值 / "平静" ⇒ 空串</b>这一条判据与改前
     * <b>一致</b>；但本方法比旧写法<b>多做了一次 {@code trim()}</b>（{@code " 难过 "} ⇒ {@code "难过"}）——
     * <b>实测不可达</b>（{@code lastEmotion} 唯一不 trim 的写入点就是读库那一行），方向是<b>收紧</b>，不是等价。</p>
     *
     * <p>抽成一个方法只为一件事：{@link EmotionProvider#legacy} 与 {@link #factLine} 用同一条口径 ——
     * 免得出现"legacy 行不显示、档位块显示平静"这种两套判据的漂移。</p>
     *
     * @return 该显示的那一格的值；不显示时给<b>空串</b>（调用方按"空串 = 这一格不存在"处理）
     */
    static String themTag(EmotionCfg c, String lastEmotion) {
        String last = lastEmotion == null ? "" : lastEmotion.trim();
        if (!Str.has(last)) return "";
        String calm = c == null ? EmotionCfg.DEF_CALM : c.s("平静标签", EmotionCfg.DEF_CALM);
        return last.equals(calm) ? "" : last;
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
     * 「发表情包的时机提示」那一行的兜底（{@code prompt.md} 的 {@code 时机-行} 读不到时的下限）。
     * <p>与 {@link #DEF_SELF_ASK} 同一个口径（同源同文：改一处必须改两处），但它与那一行有一处
     * 关键差别：<b>这一行是可选的</b> —— {@link #cueLine} 判"有一条否决命中"时返回空串，
     * 一个字都不加；三条否决全过时才把这一行拼进块。</p>
     * <p>★ <b>批 16 改向 / 2026-09-27 甲方裁定</b>：文案从「这会儿像在逗乐/接梗」改成
     * "此刻没什么不妥、想发就发一张" —— 旧文案是<b>被删掉的那条正向白名单在说话</b>
     * （它替她判了"像在逗乐"），与新口径「语境语义交给 AI 自己分析」矛盾。</p>
     */
    static final String DEF_CUE =
            "【时机】这会儿没什么不妥：想发就发一张表情包（sticker，一条最多一张，不用问）。";

    /**
     * <b>「发表情包的时机提示」那一行</b>（2026-09-24 甲方令「她得自发愿意发表情包、愿意去调这个东西」；
     * 情绪块的第 7 行，<b>可选</b>：不合适时返回空串）。
     *
     * <h3>为什么装在「情绪」里</h3>
     * <p>这一行要的是"<b>此刻正合适</b>"这个判断，而"此刻"的全部事实（本轮正文、档位状态、对方最近情绪标签）
     * 只有情绪块这一处每轮都在手边，且它本来就是"每轮注入的那一段"的唯一写者 ⇒ 挂在它后面最省、最不会飘。</p>
     *
     * <h3>它只说话，不替她发图</h3>
     * <p>本方法<b>只产出一行文字</b>：发不发、发哪一张仍是她自己调 {@code sticker} 工具决定的，
     * 出站纪律（禁词 veto / 只骂触发者 / 标记剥离）一步都没绕过 —— 这一行永远不碰 {@code Outbound}、
     * 不碰 NapCat、不写任何库。</p>
     *
     * <h3>★★ 批 16 改向 / 2026-09-27 甲方裁定（逐字）</h3>
     * <p>「<b>为什么不把语境语义这些东西交给AI自己分析呢？我们提供这个情绪状态机就好了呀。</b>」
     * 「<b>我们提供东西给AI用，当然也要引导AI如何用，并且用起来，这才是我们的初衷，
     * 而不是我们把一些东西写死，让AI按部就班。</b>」</p>
     * <p>⇒ 本状态机从此<b>只提供"事实与状态"</b>：心情档（{@code mood:<场合>} 的 {@code state}）、
     * 对方最近一条消息的情绪标签（{@code emotion.last_emotion}）、亲疏、场合，以及下面那三条既有否决。
     * <b>"此刻合不合适发表情包"改由她（模型）自己判</b> —— 不由本方法替她判；那一行只负责
     * <b>引导她"这件事可以主动做、怎么做"</b>（文案 = {@code prompt.md} 的 {@code 时机-行}）。</p>
     *
     * <h3>判据（本批改向之后只剩否决面）</h3>
     * <ul>
     *   <li><b>总开关</b>：{@code 时机提示}（出厂 {@code true}）。关掉 ⇒ <b>不加</b>
     *     （回执 {@code why=开关:false}）。</li>
     *   <li><b>正向判据：没有了。</b>本批<b>删掉了旧的三条</b>（README 里那段"像逗乐/接梗"的正向口径
     *     随之一并作废）—— 旧读数就是下面这三行，现在一行都不剩：
     *     <ol>
     *       <li>旧 正向① {@code hasTag(c,"时机-标签",last)} —— 对方标签命中白名单；</li>
     *       <li>旧 正向② {@code c.flag("时机-开心",true) && S_HAPPY.equals(state)} —— 她自己 happy 档；</li>
     *       <li>旧 正向③ {@code !group && c.flag("时机-私聊",false)} —— 私聊且无否决。</li>
     *     </ol></li>
     *   <li><b>否决 = 一个字都不加</b>（任一命中就退出；这三条是甲方唯一要保的安全面，本批<b>一个字未动</b>）：
     *     <ol>
     *       <li>档位 ∈ {angry, strike, sad, letdown}（对方在生气/低落、或她正罢工）+ v3 的四档
     *         {careful, wronged, guilty, jealous}（戒备/委屈/心虚/吃醋）⇒ 回执 {@code why=state:<档>}；</li>
     *       <li>对方最近情绪标签命中 {@code 时机-否标签}（内容 = 生气/难过/疲惫）
     *         ⇒ 回执 {@code why=否标签:<标签>}；</li>
     *       <li>本轮正文命中既有否决词表 {@code 词-违规}（红线）/ {@code 词-做不到} / {@code 词-为难}
     *         （求助办不到的事）/ {@code 词-骂} / {@code 词-冷落}
     *         ⇒ 回执 {@code why=词:<表名>}。</li>
     *       <li>★ 批 17 L4（2026-09-27 甲方裁定）：这一串里原来还有 {@code 词-正经}（办事/排障/正经话题），已
     *         <b>降级为软引导</b> —— 机器不再拿一张日常高频词表替她判"这条算不算正经办事"。引导面一个字没丢：
     *         它住在<b>常驻可见</b>的教学文本 {@code data\skills\表情包\表情包.md} 的工具描述里（「不该用：正经办事、
     *         排障、求助…」逐字仍在）。{@code prompt.md} 的 {@code 词-正经} 那张词表<b>已整行删除</b>（2026-09-27
     *         裁定：留一个"改了没反应"的旋钮比没有这个旋钮更贵）。<b>回退 = 两处都要做</b>（少任何一处都完全没
     *         反应）：① 把 {@code "正经"} 加回本方法那一串；② 把那一行加回 {@code prompt.md}（逐字节同文的原文
     *         见 {@code tmp\v6-real\batch6\W17-Ldata\dry\data\skills\情绪\prompt.md}）。</li>
     *     </ol></li>
     *   <li><b>★ 三条否决全过 ⇒ 默认给出那一行</b>（回执 {@code cue=1 why=默认:无否决}）。
     *     这就是新口径的落点：机器只报"此刻没有任何已知的不妥"，<b>要不要发由她自己看</b>。
     *     "没什么不妥"不等于"必须发" —— 她看了不发也完全合规，发图权限本就在 {@code sticker} 那一层。</li>
     * </ul>
     * <p>为什么是"默认加"而不是"默认不加"：甲方要的是"把语境语义交给她自己分析"。机器能可靠判的
     * 只有"明显不该发"那一侧（上面三条否决：她自己正恼/正罢工、对方情绪低落、红线/被冒犯/被冷落），
     * 而"该发"那一侧（接梗、缓和气氛、收尾、文字说不清情绪…）本就是语境语义，判不了也不该判；
     * 旧口径拿一张白名单替她判，正是本次被裁掉的东西。</p>
     *
     * <h3>回退手法（GM 要的"回退即红"；只改临时副本，工程文件不留痕）</h3>
     * <ul>
     *   <li><b>放宽否决②</b>：把 {@code prompt.md} 的 {@code 时机-否标签} 清空 ⇒ "对方难过/生气"那几格
     *     当场变"加"（实测回执 {@code cue=0 why=否标签:难过} → {@code cue=1 why=默认:无否决}）；
     *     把词放回去即复绿。证据：{@code tmp\v6-real\batch6\W16c\revert2-notag\}。</li>
     *   <li><b>回退即红（否决③；批 17 L4 之后方向相反）</b>：把 {@code "正经"} <b>加回</b>本方法那一串
     *     <b>并放回首位</b>（原顺序 {@code { "正经", "违规", "做不到", "为难", "骂", "冷落" }}；追加到末尾只在
     *     "单表命中"时行为等价，同句同时命中两张表时<b>回执表名会不同</b>）⇒ "正经办事/排障"那一格当场由"加"
     *     变"不加"（回执 {@code cue=1 why=默认:无否决} → {@code cue=0 why=词:正经}）；撤掉即复绿。
     *     ★ <b>两处都要做</b>：只加回这一串不红（表已删、标签匹配不到任何词），只加回 prompt.md 那行也不红
     *     （没有读者）—— 单路只靠<b>结构钉</b>报警（剥注释后 {@code 正经} 0→1；总键 241→242、标量 187→188）。
     *     证据：{@code tmp\v6-real\batch6\V18b\REPORT.md} §7（rb1/rb2/rb12 三跑）。
     *     ★ 批 16 之前那版方向（把它拿掉）已被 2026-09-27 裁定取代；旧的实演证据仍在
     *     {@code tmp\v6-real\batch6\W16c\revert2-noword\}（历史口径，勿再当现状读）。</li>
     *   <li><b>放宽否决①</b>：拿掉 {@code S_SAD}（临时副本）⇒ <b>本夹具 6 格一格不变</b>：
     *     那 6 格没有一格的 {@code state} 落进那一串（回执里出现的是 {@code 否标签:*} / {@code 词:*}，
     *     从来不是 {@code state:*}）⇒ <b>这条路径在这份夹具上验不到</b>（W16-C2 报告里如实记了这一条）。</li>
     *   <li><b>整体关掉</b>：{@code 时机提示: false} ⇒ 一行都不加（回执 {@code why=开关:false}）。</li>
     * </ul>
     *
     * <h3>与本类之外的两处关系（如实；不改它们，只在报告里点名）</h3>
     * <ul>
     *   <li><b>批 16 上一版那三个键已删除</b>（{@code prompt.md} 的 {@code 时机-标签} /
     *     {@code 时机-开心} / {@code 时机-私聊}；2026-09-27 甲方裁定：「留一个'改了没反应'的旋钮
     *     比没有这个旋钮更贵」）⇒ 本方法现在**一个字都不读它们**，文件里也**已经没有它们**
     *     （总键 244→241、标量唯一键 190→187）。批 16 上一版那套"补词/挪标签"
     *     （把 {@code 调侃} 从否标签挪进标签、并新增道别标签）**按本次裁定作废**：
     *     白名单已不存在，挪词没有任何效果。<b>要回退旧口径 = 加回三条判据 + 加回三个键</b>，
     *     只加键不加判据 = 一个改了没反应的旋钮。</li>
     *   <li>{@code probe\ProbeEmotion.java} §13/§5z 里还有按名读那三个键的旧钉子（测试线 T16 负责
     *     重锚）。{@code EmotionProvider.cueOf} 的 javadoc 里原来还留着"正向判据①不成立"这类话
     *     （正向判据已删）—— **批 17 L1 同批修掉了那处注释漂移**，现在它写的是"库读不出来 ⇒
     *     只影响否决②那一条"。</li>
     *   <li>{@code 情绪-道别} 那一行<b>保留</b>（它对时机已经<b>没有判据作用</b>）：
     *     它产出的是一个<b>状态标签</b>（"对方在收尾/道别"是给她的状态事实，属"我们提供状态"），
     *     删它还会连带改掉「规则表 情绪=10→9」与「无标签也算交流」那条既有计分路径
     *     （它为什么落在情绪-族而不是 {@code 正则-道别}，实测理由写在 {@code prompt.md} 里）。</li>
     * </ul>
     *
     * @param group 本轮是不是群聊 —— <b>本口径下这个参数已经不再参与任何分流</b>（旧正向③读它，已删）；
     *     留着只为不改 {@link EmotionProvider} 那边的调用签名。
     * @param text  本轮正文（{@code BuildContext.text()}；否决③读它）
     * @param lastEmotion 对方最近一条消息的情绪标签（{@code EmotionState.peek} 的产物；否决②读它）
     * @return 该加的那一行（{@code 时机-行} 读不到就用 {@link #DEF_CUE}）；
     *     任一否决命中（或 {@code 时机提示: false}）时返回<b>空串</b>
     */
    public static String cueLine(EmotionCfg c, Mood m, boolean group, String text, String lastEmotion) {
        return cue(c, m, group, text, lastEmotion, null);
    }

    /**
     * {@link #cueLine} 的"带口径回执"那一支（{@code why != null} 时把<b>为什么加/为什么不加</b>
     * 写进它，形如 {@code cue=1 why=默认:无否决} / {@code cue=0 why=否标签:难过}）。
     *
     * <p>存在的唯一理由（GM 2026-09-24 验收令）：<b>必须能把"这一行没注入"与"注入了她没听"分开</b>。
     * 回执只走<b>日志</b>（{@link EmotionProvider} 里那一行 dim）—— <b>绝不进情绪块</b>：
     * 块里凡是随轮变化的东西都会破坏"同一状态两次 build 逐字节一致"（前缀缓存与探针都靠它）。</p>
     *
     * <p>批 16 改向（2026-09-27 甲方裁定）之后，可能出现的回执只有这几种：
     * {@code 开关:false}（总开关关）、{@code state:<档>}（否决①）、{@code 否标签:<标签>}（否决②）、
     * {@code 词:<表名>}（否决③）、{@code 默认:无否决}（三条全过 ⇒ 给那一行，
     * 旧口径里的 {@code 标签:*} / {@code state:happy} / {@code 私聊} 三种已随正向判据一起删掉）。</p>
     */
    public static String cue(EmotionCfg c, Mood m, boolean group, String text, String lastEmotion, StringBuilder why) {
        boolean on = c != null && c.flag("时机提示", true);
        String state = m == null ? S_CALM : m.state;
        String last = lastEmotion == null ? "" : lastEmotion.trim();
        if (!on) return miss(why, "开关:false");
        // ---- 否决 ①：她/这个场合正恼着、正失落、正在罢工 ⇒ 此刻显然不该发
        //   ★ v3 补的四档（戒备/委屈/心虚/吃醋）：它们同样是"此刻显然不该发"。
        //     期待/得意/害羞/疲惫**不在**这一串里（前三个是好时机，疲惫只是话少，不该顺带把梗图关掉）。
        //   ★ 批 16 改向（2026-09-27 甲方裁定）：这一串**一个字未动** —— 它是甲方要保的安全面。
        if (S_ANGRY.equals(state) || S_STRIKE.equals(state) || S_SAD.equals(state) || S_LETDOWN.equals(state)
                || S_CAREFUL.equals(state) || S_WRONGED.equals(state)
                || S_GUILTY.equals(state) || S_JEALOUS.equals(state)) {
            return miss(why, "state:" + state);
        }
        // ---- 否决 ②：对方最近一条消息的情绪标签是"生气/难过/疲惫"（既有规则层的产物）
        //   清单内容住在 `prompt.md` 的 `时机-否标签`，**这里一个中文词都不硬编码**（本技能既有纪律）。
        //   ★ 批 16 改向：本方法**不再读正向白名单 `时机-标签`**（那条判据删了）⇒ 批 16 上一版
        //     "把 `调侃` 从否标签挪进标签"那类改法**从此没有任何效果**；要放宽这一条只有一条路：
        //     改 `prompt.md` 里这张清单（清空它 ⇒ "难过/生气"那两格当场变"加"）。
        if (hasTag(c, "时机-否标签", last)) return miss(why, "否标签:" + last);
        // ---- 否决 ③：红线 / 求助办不到的事 / 被骂 / 被冷落（既有词表；批 17 L4 起"正经"已降级）
        //   ★ 批 17 L4（2026-09-27 裁定）：这一串里原来还有 "正经"（办事/排障/正经话题）—— 已降级为软引导：
        //     机器不再用词表替她判"这条算不算正经办事"；引导面在常驻可见的 data\skills\表情包\表情包.md 的
        //     工具描述里（「不该用：正经办事、排障、求助…」逐字仍在）。prompt.md 的 `词-正经` 那张表**已整行删除**
        //     （同一裁定：留一个"改了没反应"的旋钮比没有这个旋钮更贵）。**回退 = 两处都要做**（少任何一处都完全
        //     没反应）：① 把 "正经" 加回这一串**并放回首位**（原顺序 `{ "正经", "违规", … }`——追加到末尾只在
        //     "单表命中"时等价，同句命中两张表时回执表名会不同）；② 把那一行加回 prompt.md（逐字节同文原文见
        //     tmp\v6-real\batch6\W17-Ldata\dry\data\skills\情绪\prompt.md）。其余五个词一个字未动。
        for (String tag : new String[] { "违规", "做不到", "为难", "骂", "冷落" }) {
            if (c.hasWord(text, tag)) return miss(why, "词:" + tag);
        }
        // ---- ★★ 三条否决全过 ⇒ **默认给出那一行**（批 16 改向 / 2026-09-27 甲方裁定）
        //   被删掉的三条正向判据见本方法 javadoc；"此刻合不合适发表情包"**交给她自己判** ——
        //   机器只报"没有任何已知的不妥"，那一行的文案只负责引导她"这件事可以主动做、怎么做"。
        return hit(c, why, "默认:无否决");
    }

    /** 不加（回执 {@code cue=0 why=<口径>}）。 */
    private static String miss(StringBuilder why, String reason) {
        if (why != null) {
            why.setLength(0);
            why.append("cue=0 why=").append(reason);
        }
        return "";
    }

    /** 加（回执 {@code cue=1 why=<口径>}）；文案取自 {@code 时机-行}，读不到就用 {@link #DEF_CUE}。 */
    private static String hit(EmotionCfg c, StringBuilder why, String reason) {
        if (why != null) {
            why.setLength(0);
            why.append("cue=1 why=").append(reason);
        }
        String v = c == null ? "" : c.s("时机-行", "");
        return Str.has(v) ? v : DEF_CUE;
    }

    /** 一个标签（逗号分隔的清单键）里有没有 {@code v}（逐字相等，忽略大小写与首尾空白）。 */
    private static boolean hasTag(EmotionCfg c, String key, String v) {
        if (c == null || Str.blank(v)) return false;
        for (String t : c.tags(key)) {
            if (v.equalsIgnoreCase(t)) return true;
        }
        return false;
    }

    /**
     * 把一块拼成"情绪块"（情绪 v2 = <b>最多 6 行</b>；2026-09-24 起最多 <b>7 行</b>）：
     * 第 1 行事实 + 第 2 行语气 + 第 3 行<b>范例</b> + 第 4 行边界 + 第 5 行自报要求
     * 〔+ 可选第 6 行：<b>时机提示</b>（发表情包，见 {@link #cueLine}）〕+ <b>末行</b>覆盖句。
     *
     * <p>语气行 / <b>范例行</b> / 边界行 / 自报要求行都是"取到内容才加"（可选组口径）
     * ⇒ 行数在 3~6 之间浮动。范例行<b>只在 {@code 范例-<码>} 有值时注入</b>，
     * 位置钉在<b>语气行之后、边界行之前</b>（情绪 v2 §2 的"新增注入行"）。</p>
     *
     * <p><b>时机提示那一行钉在覆盖句之前</b>（不是"整段的最末尾"）：末行 {@code 【本轮语气以本段为准】}
     * 是逐字锚点（探针按字面断言，模型靠它认权威），所以可选行只能插在它前面 —— 这是这一批唯一
     * 需要解释的位置决定。没有这一行时（{@code cue} 为空）整个方法与改动前<b>逐字节一致</b>。</p>
     *
     * @param cue 可选第 6 行（{@link #cueLine} 的产物；空串 = 这一行不出现）
     * @return 非空则一定满足「首行 {@code mood: }」「末行覆盖句」这两条逐字锚点
     */
    public static String block(EmotionCfg c, Mood m, String cue) {
        return block(c, m, cue, null);
    }

    /**
     * 语气行（{@code 【此刻情绪：<标签>】<语气文案>}）：语气文案取不到 ⇒ <b>空串</b>（这一整行不加）。
     * <p>抽出来只为一件事：让 {@link #block} 与 {@code EmotionProvider.trimBlock} 的降级梯子用
     * <b>同一个</b>形状 —— 梯子里那一步原来是自己拼的，现在两边只有这一个来源。</p>
     */
    public static String moodHead(EmotionCfg c, String state) {
        String tone = toneLine(c, state);
        return Str.has(tone) ? ("【此刻情绪：" + label(c, state) + "】" + tone) : "";
    }

    /**
     * {@link #block(EmotionCfg, Mood, String)} 的<b>敬重档</b>那一支（2026-09-26 甲方规格）。
     *
     * <p>{@code r} 为空 / 没进档 / 文案取不到 ⇒ <b>与 {@code block(c,m,cue)} 逐字节一致</b>
     * （这是"favor < 阈值走旧路"的实现保证：分歧只有下面那三处 {@code resp} 分支）。</p>
     *
     * <p>进档时改三处、<b>只改语气面</b>：① 语气行换成敬重档（{@code 【此刻情绪：敬重】…}）；
     * ② 范例行、③ 边界行按 {@code calm} 档取 —— 于是生气/暴怒/罢工那三档的
     * <b>语气、范例、边界一个字都进不了这一轮</b>（"不发怒"的落点就是这三行）。</p>
     *
     * <p><b>首行事实行不动</b>（{@code mood:{"state":"angry",…}} 照写）：它是<b>机器可读的事实</b>
     * （her 的状态机确实被骂到生气了），而且首行与末行是<b>逐字锚点</b>（探针按字面断言）。
     * "不发怒"落在语气面上，靠的是覆盖句那条"语气以本段为准"与敬重档那一行的逐字要求 ——
     * 这也正是 {@code identity.md} 的 [风格] 那条"生气/罢工态一律以本轮【此刻情绪】那一段为准"的用法。</p>
     */
    public static String block(EmotionCfg c, Mood m, String cue, Respect r) {
        return block(c, m, cue, r, "");
    }

    /**
     * {@link #block(EmotionCfg, Mood, String, Respect)} 的<b>带"对方情绪标签"那一支</b>
     * （2026-09-27 批 17 L1）：唯一的分歧就一处 —— 首行事实行交给
     * {@link #factLine(EmotionCfg, Mood, String)}，于是她能在块里看到 {@code them}（+ 读法）。
     *
     * <p>空串（默认这一支）⇒ 与改动前<b>逐字节一致</b>；行数、行序、降级梯子一个字都没动。</p>
     *
     * @param lastEmotion 对方最近一条消息的情绪标签（{@link EmotionProvider} 那一次 peek 的产物）
     */
    public static String block(EmotionCfg c, Mood m, String cue, Respect r, String lastEmotion) {
        boolean resp = r != null && r.on && Str.has(r.head);
        String state = m == null ? S_CALM : m.state;
        String pick = resp ? S_CALM : state;          // 敬重档：范例/边界按 calm 档取（不进怒态）
        List<String> lines = new ArrayList<String>();
        lines.add(factLine(c, m, lastEmotion));
        String head = resp ? r.head : moodHead(c, state);
        if (Str.has(head)) lines.add(head);
        String eg = exampleLine(c, pick);
        if (Str.has(eg)) lines.add(eg);
        String bound = boundLine(c, pick);
        if (Str.has(bound)) lines.add(bound);
        String self = selfAsk(c);
        if (Str.has(self)) lines.add(self);
        if (Str.has(cue)) lines.add(cue);              // ★ 可选第 6 行：时机提示（必须在覆盖句之前）
        lines.add(coverLine(c));
        return Str.join(lines, "\n");
    }

    /**
     * 不带时机提示的那一支（口径与情绪 v2 <b>逐字节一致</b>）：给"手上没有场合/本轮正文"的调用方用
     * —— 探针、以及 {@link EmotionProvider} 的降级梯子。
     */
    public static String block(EmotionCfg c, Mood m) {
        return block(c, m, "");
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
