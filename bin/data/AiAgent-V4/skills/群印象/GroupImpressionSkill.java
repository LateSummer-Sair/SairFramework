import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import sair.v4.ext.BuildContext;
import sair.v4.ext.ContextProvider;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Ev;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;
import sair.v4.skill.Skill;
import sair.v4.store.LibSpec;
import sair.v4.store.Libs;
import sair.v4.store.Store;

/**
 * 群印象（V3 的「这个群什么氛围」）：<b>一个类挂三种身份，不给任何工具</b>。
 *
 * <ol>
 *   <li>{@link Skill}：<b>只</b>用 {@link #declare(Libs)} 声明自己的库 {@code group_impression}；
 *       {@link #call(Args, Host)} 是空实现（本插件没有工具，md 里也没有 {@code tool:}）；</li>
 *   <li>{@link Hooked}：{@code on_message} 累积（活跃度 / 关键词命中），{@code on_timer} 蒸馏（基板唯一 tick）；</li>
 *   <li>{@link ContextProvider}：每轮往上下文加一段「这个群什么氛围」（<b>只在群聊</b>，
 *       {@code stable()=false} 易变段、{@code order()=50}、预算读 {@code prompt.md} 默认 500）。</li>
 * </ol>
 *
 * <h3>口径出处（V3 原样，见 {@code notes/feel-plugins.md} 第一/二/四节）</h3>
 * <ul>
 *   <li>群印象 = 「以群号为唯一键的整体氛围」：友好度（-100~100）/ 氛围 / 活跃度
 *       （{@code sair.aiagent.model.GroupImpression}）；</li>
 *   <li>注入形状照抄 V3 的 {@code modules/context/impression-group.md}
 *       （{@code [群印象] 群:{group}{namePart} 友好度:{friendliness} 活跃度:{activity}{atmosphere}}）
 *       与 {@code impression-group-atmosphere.md}（{@code - 群氛围: {text}}）；</li>
 *   <li>活跃度是<b>分档标签</b>而不是条数（V3 {@code activityLabel}：5000=极高 / 500=高 / 50=中 / 其余低）；</li>
 *   <li>「氛围差」的门槛 = V3 的 {@code GroupImpression.FRIENDLINESS_LOW = -30}；到这一档才额外给一句态度
 *       （V3 那边是概率拒答 + 情绪反向联动；V4 没有情绪表，这一段只影响 AI 的态度，不发消息、不拒答）；</li>
 *   <li><b>回写节流</b>照 V3 的闸门精神（{@code EmotionStateManager.noteUnfriendlyGroup}：
 *       「每群 10 分钟最多联动一次」+「此来源最多把怒气推到 40」）：这里的对应物是
 *       {@code prompt.md} 的「回写节流分钟」（默认 10，每群每窗口最多写一次库）
 *       与「回写最大幅度」（默认 3，单次回写最多把友好度推 ±3）——
 *       <b>每条群消息都只动内存，绝不写库</b>。</li>
 * </ul>
 *
 * <h3>数据从哪来（只有两条，都是只读）</h3>
 * <ol>
 *   <li>{@code grouplog}（基板写的群聊历史）：<b>只读</b>。自上次回写以来的条数、
 *       自上次蒸馏以来的窗口内容全从它统计（{@code h.store().count/list}）——
 *       本插件<b>绝不写</b> {@code grouplog}，也不碰 {@code memory}/{@code dialog}/{@code pref}；</li>
 *   <li>事件本身（{@code on_message} 的 payload）：只用来判群号/发言人、判有没有被 @
 *       （{@code grouplog} 里不带 @ 信息），以及"这个群刚才动了一下"。</li>
 * </ol>
 *
 * <h3>★ 她自己的标记行（P1-selfsee）+ 外部来源行（能力②/D1）：读 grouplog 的每个读点先过一道判据</h3>
 * <p>基板 P0-1 之后 {@code grouplog} 里会多出她<b>自己发出去</b>的那一行「标记行」
 * （{@code content} 形如 {@code [我发的 图片 msg_id=<id>]} / {@code [我发的 msg_id=<id>] <正文>}，
 * {@code extra.self=true}）。它是<b>机器写的标记行 —— 不是群友发言，也不是她的人格素材</b>。
 * 基板 D1（2026-09-28）之后同一个 {@code selfId} 下<b>另一个机器人服务</b>发出去的消息
 * （{@code extra.foreign=true}）也会出现在 {@code grouplog} 里 —— 它<b>同样</b>不是群友发言，
 * 处置逐字相同（两个判据的合流处 = {@link #notMemberWords}）。
 * 本插件两个读点分开处置：</p>
 * <ol>
 *   <li>{@link #scan}（取样行 ⇒ 负面/正面词计分、成员去重、话题 2-gram、{@code extra.sampled}）：
 *       <b>① 整行跳过</b> —— 机器外壳不进任何「群氛围素材」，也不参与计分；</li>
 *   <li>{@code count(grouplog)} 的条数（{@code activity} 的「这个群累计多少条记录」）：
 *       <b>③ 照旧</b> —— 它是<b>日志水位/记录条数</b>，不是「多少条群友发言」；且 {@code Store}
 *       的过滤面只有等值 + {@code _like/_gte/_lte}（<b>没有否定位</b>），SQL 层写不出与本判据
 *       语义一致的「非标记行」条件（用 {@code LIKE '%…%'} 就成了 {@code contains}，
 *       违反「判据不许过宽」）。两者分别断言，见 {@code tmp\ski\p1\REPORT.md}。</li>
 * </ol>
 *
 * <h3>蒸馏不调模型</h3>
 * <p>{@code on_timer} 的更新是<b>规则/统计</b>：关键词命中 + 发言人去重 + 2-gram 词频。
 * 这条 tick 默认 20 秒一次、可能长期空转，调模型等于按 tick 烧钱，所以这里<b>一次模型调用都没有</b>。</p>
 *
 * <h3>容忍与自保</h3>
 * <ul>
 *   <li>{@code h} / {@code host()} / {@code store()} / {@code libs()} 任一为 null、库不存在、
 *       表还没建出来 —— 一律安静早退（返回空串 / 不写库），<b>绝不抛异常</b>；</li>
 *   <li>自己的库只有一张 {@code group_impression}，写路径<b>只有</b>本插件的 {@code flush}/{@code distill}；</li>
 *   <li>所有阈值与文案在 {@code prompt.md}（惰性缓存 + 按 mtime 失效），本文件里只有兜底默认值。</li>
 * </ul>
 */
public class GroupImpressionSkill implements Skill, Hooked, ContextProvider {

    // ================================================================ 结构性常量（不是文案）

    /** 技能名（= 文件夹名 = md 名 = 日志前缀）= 本插件声明的库的 owner。 */
    private static final String NAME = "群印象";
    /** 本插件自己的库（只有这一张，写路径只有本插件）。 */
    private static final String LIB = "group_impression";
    /** 基板的群聊历史（<b>只读</b>：活跃度与信号统计的唯一来源）。 */
    private static final String LIB_LOG = "grouplog";
    /** 钩子名（与 md 的 {@code hooks:} 一致）。 */
    private static final String HOOK_MSG = "on_message";
    private static final String HOOK_TIMER = "on_timer";
    /** 友好度的区间（V3 同区间；结构性取值，不是阈值）。 */
    private static final int FL_MIN = -100;
    private static final int FL_MAX = 100;

    // ================================================================ ★ 她自己的标记行（P1-selfsee 唯一一处判据）

    /** 标记行在 {@code content} 上的<b>逐字</b>前缀（尾部的空格也算，判据不许过宽）。 */
    private static final String SELF_MARK_PREFIX = "[我发的 ";

    /**
     * ★ P1「她自己的标记行」判据 —— <b>本技能唯一一处</b>（不许在业务逻辑里散写
     * {@code if (content.startsWith(...))}）。
     *
     * <p>基板 P0-1 落地后，她<b>自己发出去</b>的消息/图会在 {@code grouplog} 里补一行标记行：
     * {@code content} = {@code [我发的 图片 msg_id=<id>]} 或 {@code [我发的 msg_id=<id>] <正文>}，
     * 并带 {@code extra.self=true} + {@code extra.action} + {@code extra.sent_id}。
     * <b>它是机器写的标记行 —— 不是群友发言，也不是她的人格素材。</b></p>
     *
     * <p>两条判据<b>都要</b>（只读消费面可能把 {@code extra} 丢掉）：</p>
     * <ol>
     *   <li>{@code extra.self == true}：{@code extra} 是 TEXT 列的 JSON 串（{@code Db.decode}
     *       让出参是对象；解析失败/缺失就当<b>没有它</b> —— 绝不因此把整行判成标记行）；</li>
     *   <li>兜底：{@code content} <b>逐字</b>以 {@link #SELF_MARK_PREFIX} 开头（含尾部空格）。</li>
     * </ol>
     *
     * <p><b>判据不许走样</b>：① 只能用 {@code startsWith} —— 不许 {@code contains("[我发的")}，
     * 也不许退化成「以 {@code [} 开头」：{@code [图片 …]}/{@code [语音 12秒]}/{@code [戳一戳]}/
     * {@code [撤回]} 那些是<b>基板事实标签</b>，本判据一律 {@code false}（本任务不许过滤它们）；
     * ② {@code "[我发的]"}（没有尾部空格）也判 {@code false}（逐字前缀）。</p>
     */
    static boolean isSelfMark(JsonObject row) {
        if (row == null) return false;
        try {
            JsonObject ex = rowExtra(row);
            if (ex != null && J.b(ex, "self", false)) return true;
        } catch (Throwable ignored) {
        }
        try {
            return Str.nz(J.s(row, "content", "")).startsWith(SELF_MARK_PREFIX);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * ★ <b>外部来源行</b>判据（能力②，D1 2026-09-28）：同一个 QQ 账号下<b>另一个机器人服务</b>
     * 发出去的消息（{@code extra.foreign=true}，基板 {@code QqGateway.foreignEcho} 的唯一产法）。
     *
     * <p>它在本技能里的处置与标记行<b>逐字相同</b>：不是群友发言 ⇒ 不进任何统计素材
     * （取样行、成员去重、话题 2-gram 都吃 {@link #withoutSelfMarks} 那一支）。
     * 判据只看 {@code extra.foreign}（内容伪造不出这个键）。</p>
     */
    static boolean isForeign(JsonObject row) {
        if (row == null) return false;
        try {
            JsonObject ex = rowExtra(row);
            return ex != null && J.b(ex, "foreign", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 这一行是不是"<b>不是群友的话</b>"（两个判据的唯一合流处：她的标记行 / 外部来源行）。 */
    static boolean notMemberWords(JsonObject row) {
        return isSelfMark(row) || isForeign(row);
    }

    /** {@code extra} 列（TEXT 的 JSON 串，或已被 {@code Db.decode} 解析成对象）；解析不出来返回 null。 */
    private static JsonObject rowExtra(JsonObject row) {
        try {
            com.google.gson.JsonElement e = J.get(row, "extra");
            if (e == null || e.isJsonNull()) return null;
            if (e.isJsonObject()) return e.getAsJsonObject();
            return e.isJsonPrimitive() ? J.obj(e.getAsString()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 去掉"不是群友的话"的行（{@link #notMemberWords}：她的标记行 + 外部来源行）。
     * <p>一个都不命中时原样返回入参（同一引用、同一顺序）—— 这就是「没有这类行 ⇒ 与改动前逐字等价」。</p>
     */
    static List<JsonObject> withoutSelfMarks(List<JsonObject> rows) {
        if (rows == null || rows.isEmpty()) return rows;
        boolean has = false;
        for (int i = 0; i < rows.size(); i++) {
            if (notMemberWords(rows.get(i))) {
                has = true;
                break;
            }
        }
        if (!has) return rows;
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            if (!notMemberWords(r)) out.add(r);
        }
        return out;
    }

    // ================================================================ 兜底默认值（真正生效的值在 prompt.md）

    /** {@code 预算}：本段的字符上限，也是 {@link #maxChars()} 的返回值。 */
    private static final int DEF_BUDGET = 500;
    private static final boolean DEF_ENABLED = true;
    /** {@code 回写节流分钟}：每群多久最多回写一次库（V3 的 10 分钟闸门同值）。 */
    private static final int DEF_WRITE_MIN = 10;
    /** {@code 回写最大幅度}：一次回写最多把友好度推 ±N（V3「此来源最多把怒气推到 40」的同精神闸门）。 */
    private static final int DEF_WRITE_AMP = 3;
    /** {@code 蒸馏间隔小时}：同一个群两次蒸馏之间的最小间隔。 */
    private static final int DEF_INTERVAL_H = 6;
    /** {@code 最少消息数}：自上次蒸馏以来至少累计这么多条才值得更新一次。 */
    private static final int DEF_MIN_MSGS = 30;
    /** {@code 取样条数}：一次统计最多看这个群最近多少条群聊。 */
    private static final int DEF_SAMPLE = 400;
    /** {@code 每轮群数}：一次扫描最多蒸馏几个群（防一个 tick 拖长）。 */
    private static final int DEF_BATCH = 3;
    /** {@code 候选群数}：一次扫描最多取几行来挑（按 last_seen 新的在前）。 */
    private static final int DEF_CAND = 20;
    /** {@code 扫描间隔分钟}：tick 很密（默认 20 秒），扫描降到这个量级。 */
    private static final int DEF_SWEEP_MIN = 10;
    /** {@code 每人信号上限}：同一个成员在窗口里最多计几分（刷屏不算"很多人"）。 */
    private static final int DEF_MEMBER_CAP = 3;
    /** {@code 信号饱和分}：累计到这么多分就吃满「最大幅度」。 */
    private static final int DEF_SAT = 6;
    /** {@code 蒸馏最大幅度}：一次蒸馏最多把友好度推 ±N。 */
    private static final int DEF_DISTILL_AMP = 25;
    /** {@code 友好度差门槛}：低于它 = 氛围差（V3 FRIENDLINESS_LOW = -30），多出一句态度。 */
    private static final int DEF_BAD_LINE = -30;
    /** {@code 友好度好门槛}：高于它 = 氛围好。 */
    private static final int DEF_GOOD_LINE = 30;
    /** {@code 被@负面加权}：这条消息 @ 了机器人且命中负面词时，该成员多算几分。 */
    private static final int DEF_BOT_WEIGHT = 2;
    /** 活跃度分档（V3 activityLabel 原值）。 */
    private static final int DEF_ACT_VERY_HIGH = 5000;
    private static final int DEF_ACT_HIGH = 500;
    private static final int DEF_ACT_MID = 50;
    private static final int DEF_TOPIC_MAX = 4;
    private static final int DEF_TOPIC_WEIGHT = 2;
    private static final int DEF_TOPIC_MIN = 3;
    private static final String DEF_TIME = "yyyy-MM-dd HH:mm";
    private static final String DEF_TITLE = "## 群印象";
    private static final String DEF_LINE =
            "[群印象] 群:{group} 友好度:{friendliness} 活跃度:{activity} 话题:{topic}";
    private static final String DEF_LINE_NOTOPIC =
            "[群印象] 群:{group} 友好度:{friendliness} 活跃度:{activity}";
    private static final String DEF_ATMO_LINE = "- 群氛围: {atmosphere}";
    private static final String DEF_TONE_BAD =
            "- （这个群最近不友好：说话简短一点，别主动热络、别硬找话题）";
    private static final String DEF_LAB_VERY_HIGH = "极高";
    private static final String DEF_LAB_HIGH = "高";
    private static final String DEF_LAB_MID = "中";
    private static final String DEF_LAB_LOW = "低";
    private static final String DEF_ATMO_BAD =
            "最近不太友好（友好度 {friendliness}，取样 {msgs} 条里有 {negative} 处负面、{positive} 处正面；{participants} 人冒过泡）";
    private static final String DEF_ATMO_FLAT =
            "平常（友好度 {friendliness}，取样 {msgs} 条；{participants} 人冒过泡）";
    private static final String DEF_ATMO_GOOD =
            "氛围不错（友好度 {friendliness}，取样 {msgs} 条里有 {positive} 处正面；{participants} 人冒过泡）";
    /** 默认词表（真正生效的是 prompt.md 的「负面词 / 正面词 / 话题词表 / 话题停用词」）。 */
    private static final String DEF_NEG_WORDS = "滚,傻,废物,闭嘴,烦,恶心,讨厌,举报,垃圾,辣鸡,呵呵,无语,菜,坑";
    private static final String DEF_POS_WORDS = "谢谢,收到,辛苦,哈哈,厉害,牛,赞,欢迎,可爱,喜欢,加油,好玩";
    private static final String DEF_TOPIC_WORDS = "";
    private static final String DEF_STOP_WORDS = "什么,这个,那个,就是,然后,现在,可以,不是,没有,知道,感觉,真的,哈哈,呵呵,我们,你们,他们";

    // ================================================================ 进程内状态（钩子每次都是新实例 → 状态必须静态）

    /**
     * 每个群一个槽：一把锁（保证"每群一行"）+ 内存里的回写节流闸门。
     *
     * <p>钩子是按事件 {@code new} 实例的（{@code Skills.invokeHook}），所以状态只能放在静态表里；
     * 表的大小 = 进程里见过的群数（与基板别的内存缓存同一量级）。</p>
     */
    private static final class Slot {
        final Object lock = new Object();
        /** 内存里的「上次回写时间」；{@code 0} = 还不知道（问一次库上的 {@code last_write}）。 */
        volatile long lastWrite;
    }

    private static final ConcurrentHashMap<Long, Slot> SLOTS = new ConcurrentHashMap<Long, Slot>();

    /** 上次扫描（挑该蒸馏的群）的时间；{@code on_timer} 很密，扫描按「扫描间隔分钟」降频。 */
    private static volatile long lastSweep;

    /** 最近一次 {@link #maxChars()} 用的预算（这个接口拿不到 Host，只能记住上一次读到的值）。 */
    private static volatile int budget = DEF_BUDGET;

    /** 最近一次拿到的技能目录（{@code host()} 为 null 时仍然读得到自己的 prompt.md）。 */
    private static volatile File dir;

    private static Slot slot(long gid) {
        Long k = Long.valueOf(gid);
        Slot s = SLOTS.get(k);
        if (s != null) return s;
        Slot fresh = new Slot();
        Slot prev = SLOTS.putIfAbsent(k, fresh);
        return prev == null ? fresh : prev;
    }

    // ================================================================ ① Skill：库声明（装载期）

    /**
     * 声明 {@code group_impression}：<b>谁写入、谁声明</b>。
     *
     * <p>一个群一行（{@code group_id} 是语义上的唯一键）。唯一性由本插件在写路径上保证
     * （{@link #flush} 在"每群一把锁"里做「先查后写」）—— {@link LibSpec#idx} 只产出
     * {@code CREATE INDEX}，表达不了 {@code UNIQUE}，所以不在 DDL 上赌约束，见 md 的「表结构」一节。</p>
     */
    @Override
    public void declare(Libs libs) {
        if (libs == null) return;
        libs.register(spec());
    }

    /** 表声明本体（测试与文档都读它，所以单独留一个方法）。 */
    public static LibSpec spec() {
        return new LibSpec(LIB, "群印象", NAME)
                .ts()
                .col("group_id", "INTEGER", "0", true)
                .col("group_name", "TEXT", "''")
                .col("friendliness", "INTEGER", "0")
                .col("activity", "INTEGER", "0")
                .col("topic", "TEXT", "''")
                .col("atmosphere", "TEXT", "''")
                .col("keywords", "TEXT", "''")
                .col("distill_msgs", "INTEGER", "0")
                .col("rounds", "INTEGER", "0")
                .col("last_seen", "INTEGER", "0")
                .col("last_write", "INTEGER", "0")
                .col("distill_ts", "INTEGER", "0")
                .col("extra", "TEXT", "''")
                .like("topic", "atmosphere", "group_name")
                .alias("群印象", "群氛围")
                .idx("idx_group_impression_group", "group_id")
                .idx("idx_group_impression_distill", "distill_ts")
                .idx("idx_group_impression_seen", "last_seen");
    }

    /** 没有工具：入口只是为了让装载器认得这个插件、并把库声明挂上去。 */
    @Override
    public Object call(Args a, Host h) {
        return null;
    }

    /**
     * 本插件<b>不提供工具</b>（md 里没有 {@code tool:}）⇒ <b>没有 op 可声明</b>：
     * 技能管控表管的是工具（模型能调的那些动作），而它的三种身份都是钩子 / 扩展点，
     * 由基板派发、跑在 SYSTEM 主体上（她自己的自主行为，恒放行）。
     */
    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        // 没有工具 = 没有 op
    }

    // ================================================================ ② Hooked：事件入口

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        try {
            if (HOOK_MSG.equals(hook)) {
                onMessage(payload, h);
            } else if (HOOK_TIMER.equals(hook)) {
                onTick(h);
            }
        } catch (Throwable t) {
            log(h, "钩子 " + hook + " 异常：" + t);
        }
    }

    /**
     * {@code on_message}：<b>每条群消息都进来，但绝大多数只动内存</b>。
     *
     * <p>顺序：判群（私聊没有群印象）→ 读配置 → 判库在不在 → 判节流窗口 →
     * 到点了才 {@link #flush}（读 {@code grouplog} 统计 + 写自己那一行）。</p>
     */
    private static void onMessage(JsonObject payload, Host h) {
        if (payload == null) return;
        Ev e = Ev.parse(payload);
        if (!e.isGroup()) return;                        // 私聊没有"群氛围"可言
        long gid = e.groupId();
        if (gid <= 0L || e.userId() <= 0L) return;
        Cfg c = load(h);
        if (!c.enabled) return;
        if (!hasLib(h)) return;                          // 插件没声明成库 / 表没建出来 → 安静早退
        long now = System.currentTimeMillis();
        Slot sl = slot(gid);
        long mem = sl.lastWrite;
        if (mem <= 0L) {                                 // 本进程第一次见这个群：问一次库（每群一辈子一次）
            JsonObject row = find(h, gid);
            mem = row == null ? 0L : J.l(row, "last_write", 0L);
            sl.lastWrite = mem;
        }
        if (c.writeMs > 0L && mem > 0L && now - mem < c.writeMs) {
            return;                                      // 节流窗口内：只当"这个群刚才动了一下"，不写库
        }
        synchronized (sl.lock) {
            if (flush(h, c, gid, e, now)) sl.lastWrite = now;
            else if (sl.lastWrite <= 0L) sl.lastWrite = now;   // 写失败/没东西写：也把闸门推一下，别每条都重试
        }
    }

    /**
     * 回写一次：<b>只读 {@code grouplog}</b> 拿权威条数与信号，然后写自己那一行。
     *
     * <p>为什么条数从 {@code grouplog} 数而不是拿内存计数器：内存计数器在重启后就没了，
     * 而 {@code grouplog} 是基板写的、跨重启的事实。窗口是
     * {@code ts >= 上次回写时间}（首次 = 这个群这辈子全部），所以不会重复计也不会漏计
     * （同一毫秒的边界最多重复计 1 条，见 md「已知限制」）。</p>
     *
     * @return true = 真的写了库（{@code last_write} 前进了）
     */
    private static boolean flush(Host h, Cfg c, long gid, Ev e, long now) {
        Store s = store(h);
        if (s == null) return false;
        // 权限口径：本插件是纯行为插件（md 里没有 tool:、不提供工具）⇒ 没有任何 op 要判 ——
        // 技能管控表管的是工具（模型能调的那些动作），而 on_message 由基板派发、主体是 SYSTEM（她自己的自主行为，恒放行）。
        // 读写面只有两个：只读 grouplog、只写自己的 group_impression。
        JsonObject row = find(h, gid);
        long since = row == null ? 0L : J.l(row, "last_write", 0L);
        JsonObject f = window(gid, since);
        long n = s.count(LIB_LOG, f);                                  // 权威条数（走 idx_grouplog_group）
        // ★ P1-selfsee：这条 count 是「这个群累计多少条记录」的日志水位，不区分谁说的 —— 按 ③ 照旧保留
        // （她自己的标记行也算这个群的一段记录，属事实面；素材/计分在 scan 里整行跳过）。
        List<JsonObject> rows = s.list(LIB_LOG, f, c.sample, "ts DESC");   // 取样（≤取样条数）做信号
        Stat st = scan(rows, c);
        // 被 @ 且命中负面词：同一个成员多算几分（只判得动事件，grouplog 里没有 @ 信息）
        if (e != null && e.atSelf() && e.userId() > 0L && c.botWeight > 1) {
            Long uid = Long.valueOf(e.userId());
            int hit = val(st.negBy, uid);
            if (hit > 0) st.negBy.put(uid, Integer.valueOf(hit + c.botWeight - 1));
        }
        int score = st.score(c);
        if (n <= 0L && st.msgs <= 0 && score == 0) return false;        // 窗口里什么都没有 → 不空写
        int old = row == null ? 0 : J.i(row, "friendliness", 0);
        int fl = clamp(old + graded(c.writeAmp, score, c.satScore), FL_MIN, FL_MAX);
        long act = Math.max(0L, (row == null ? 0L : J.l(row, "activity", 0L)) + n);
        JsonObject patch = new JsonObject();
        patch.addProperty("group_id", gid);
        patch.addProperty("friendliness", fl);
        patch.addProperty("activity", act);
        patch.addProperty("keywords", hitsJson(st, c));
        patch.addProperty("last_seen", now);
        patch.addProperty("last_write", now);
        long id = row == null ? 0L : J.l(row, "id", 0L);
        boolean ok;
        if (id > 0L) {
            ok = s.update(LIB, id, patch);
        } else {
            patch.addProperty("distill_msgs", 0L);      // 新行：一次都还没蒸馏过
            patch.addProperty("distill_ts", 0L);
            patch.addProperty("rounds", 0);
            ok = s.put(LIB, patch) > 0L;
        }
        if (ok) {
            log(h, "回写 群" + gid + " +" + n + " 条（累计 " + act + "）友好度 "
                    + old + "→" + fl + "（信号 " + score + "，取样 " + st.msgs + "）");
        }
        return ok;
    }

    // ================================================================ ③ Hooked：定时蒸馏（基板唯一 tick）

    /**
     * {@code on_timer}：按「扫描间隔分钟」降频扫一次"该蒸馏的群"，再按
     * 「蒸馏间隔小时」+「最少消息数」逐个判定。<b>不调模型</b>：全是规则/统计。
     */
    private static void onTick(Host h) {
        Cfg c = load(h);
        if (!c.enabled) return;
        Store s = store(h);
        if (s == null || !hasLib(h)) return;
        long now = System.currentTimeMillis();
        long prev = lastSweep;
        if (prev > 0L && now - prev < c.sweepMs) return;
        lastSweep = now;
        JsonObject lte = new JsonObject();
        lte.addProperty("distill_ts", Long.valueOf(now - c.intervalMs));
        JsonObject f = new JsonObject();
        f.add("_lte", lte);
        List<JsonObject> due = s.list(LIB, f, c.candMax, "last_seen DESC");
        if (due == null || due.isEmpty()) return;
        int done = 0;
        for (int i = 0; i < due.size(); i++) {
            if (done >= c.batch) break;
            JsonObject row = due.get(i);
            if (row == null) continue;
            long gid = J.l(row, "group_id", 0L);
            if (gid <= 0L) continue;
            long pending = J.l(row, "activity", 0L) - J.l(row, "distill_msgs", 0L);
            if (pending < c.minMsgs) continue;                 // 消息太少，不值得更新一次印象
            if (distill(h, c, row, now)) done++;
        }
    }

    /**
     * 蒸馏一个群：读窗口（{@code ts >= 上次蒸馏}）→ 统计 → 更新友好度/氛围/话题/活跃度。
     *
     * <p>友好度的走法：先按成员各自封顶（一个人刷屏不算"很多人"），再把成员分相加成 {@code score}，
     * 最后按「信号饱和分」折算成至多 ±「蒸馏最大幅度」的位移 ——
     * <b>群内多个成员印象变差时，群印象随之变差</b>就落在"成员分相加"这一步上。</p>
     */
    private static boolean distill(Host h, Cfg c, JsonObject row, long now) {
        Store s = store(h);
        if (s == null || row == null) return false;
        // 权限口径同上：on_timer 也由基板派发、主体是 SYSTEM；本插件没有工具 ⇒ 没有 op 要判。
        long gid = J.l(row, "group_id", 0L);
        long id = J.l(row, "id", 0L);
        if (gid <= 0L || id <= 0L) return false;
        long since = J.l(row, "distill_ts", 0L);
        List<JsonObject> rows = s.list(LIB_LOG, window(gid, since), c.sample, "ts DESC");
        Stat st = scan(rows, c);                                       // ★ scan 里整行跳过她自己的标记行
        if (st.msgs <= 0) {
            // 窗口里一条都读不到（例如群聊历史被基板维护清掉了）：只把闸门推过去，别每个 tick 重试。
            JsonObject gate = new JsonObject();
            gate.addProperty("distill_ts", now);
            gate.addProperty("distill_msgs", J.l(row, "activity", 0L));
            gate.addProperty("rounds", J.i(row, "rounds", 0) + 1);
            gate.addProperty("extra", J.json(extra(st, 0, 0, J.l(row, "activity", 0L), now)));
            boolean g = s.update(LIB, id, gate);
            if (g) log(h, "蒸馏 群" + gid + " 窗口为空（群聊历史已清），只推闸门");
            return g;
        }
        long lifetime = Math.max(0L, s.count(LIB_LOG, single(gid)));   // 活跃度 = 这辈子累计条数（V3 messageCount 口径）
        // ★ P1-selfsee：同上，这条 count 是记录水位（③ 照旧），不是「群友发言数」；素材面在 scan 里过滤。
        int old = J.i(row, "friendliness", 0);
        int score = st.score(c);
        int fl = clamp(old + graded(c.distillAmp, score, c.satScore), FL_MIN, FL_MAX);
        String topic = topicOf(st, c);
        if (Str.blank(topic)) topic = Str.trim(J.s(row, "topic", ""));   // 这一窗没词：留上一次的，别抹掉
        String atmo = atmosphere(c, fl, st, topic);
        JsonObject patch = new JsonObject();
        patch.addProperty("friendliness", fl);
        patch.addProperty("activity", lifetime);
        patch.addProperty("topic", topic);
        patch.addProperty("atmosphere", atmo);
        patch.addProperty("keywords", hitsJson(st, c));
        patch.addProperty("distill_msgs", lifetime);                    // 未消化的消息数归零
        patch.addProperty("rounds", J.i(row, "rounds", 0) + 1);
        patch.addProperty("distill_ts", now);
        patch.add("extra", extra(st, score, fl - old, lifetime, now));
        boolean ok = s.update(LIB, id, patch);
        if (ok) {
            log(h, "蒸馏 群" + gid + " 第 " + (J.i(row, "rounds", 0) + 1) + " 轮：友好度 "
                    + old + "→" + fl + "（信号 " + score + "，取样 " + st.msgs
                    + " 条 / " + st.members.size() + " 人）话题=" + topic);
        }
        return ok;
    }

    // ================================================================ ④ ContextProvider：注入这一段

    @Override
    public boolean stable() {
        return false;      // 易变段：它随群里的消息累积而变，放进稳定前缀会让前缀缓存每轮失效
    }

    @Override
    public int order() {
        return 50;         // V4 现有 provider 是 10（记忆召回）/ 20（用户画像）/ 30（群成员映射）
    }

    @Override
    public int maxChars() {
        int b = budget;
        return b > 0 ? b : DEF_BUDGET;
    }

    /**
     * 只在群聊出这一段，并且只在启用时。
     * <p>顺手把配置读进来刷一次 {@link #budget} —— 基板调 {@link #maxChars()} 时不给 Host，
     * 而 {@code appliesTo} 一定是这一轮里<b>最先</b>被调到的（{@code ExtRegistry.blocks} 的顺序），
     * 所以"第一次出现这一段"的预算也是对的。</p>
     */
    @Override
    public boolean appliesTo(BuildContext b) {
        try {
            if (b == null || !b.group() || b.groupId() <= 0L) return false;
            Cfg c = load(b.host());
            budget = c.budget;
            return c.enabled;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 拼这一段。库不存在 / 这个群还没建过印象 / 什么都没攒到 → 返回空串（这一段不出现，不是异常）。
     */
    @Override
    public String build(BuildContext b) {
        try {
            if (b == null || !b.group() || b.groupId() <= 0L) return "";
            Host h = b.host();
            Cfg c = load(h);
            budget = c.budget;
            if (!c.enabled) return "";
            Store s = store(h);
            if (s == null || !hasLib(h)) return "";
            JsonObject row = find(h, b.groupId());
            if (row == null) return "";                  // 还没建过印象 → 这一段不出现
            return render(c, row, b.groupId());
        } catch (Throwable t) {
            return "";                                   // 插件绝不拖垮一轮对话
        }
    }

    /** 渲染这一段（全部形状来自 prompt.md 的「文案」；缺键时用兜底模板）。 */
    private static String render(Cfg c, JsonObject row, long gid) {
        int fl = J.i(row, "friendliness", 0);
        long act = J.l(row, "activity", 0L);
        String label = activityLabel(c, act);
        String topic = Str.trim(J.s(row, "topic", ""));
        if (topic.isEmpty()) topic = keywordsText(J.s(row, "keywords", ""), c.topicMax);
        String atmo = Str.trim(J.s(row, "atmosphere", ""));
        if (Str.blank(atmo) && topic.isEmpty() && fl == 0 && act <= 0L) return "";   // 没内容 → 不出这一段
        String name = Str.trim(J.s(row, "group_name", ""));
        String line = topic.isEmpty() ? c.lineNoTopic : c.line;
        JsonObject ex = J.sub(row, "extra");
        StringBuilder sb = new StringBuilder();
        if (Str.has(c.title)) sb.append(c.title.trim()).append('\n');
        sb.append(fill(line,
                "{group}", String.valueOf(gid),
                "{name}", name,
                "{friendliness}", String.valueOf(fl),
                "{activity}", label,
                "{activity_n}", String.valueOf(act),
                "{topic}", topic,
                "{rounds}", String.valueOf(J.i(row, "rounds", 0)),
                "{time}", time(c, J.l(row, "last_seen", 0L)),
                "{msgs}", String.valueOf(ex == null ? 0L : J.l(ex, "sampled", 0L)),
                "{members}", String.valueOf(ex == null ? 0 : J.i(ex, "members", 0)))).append('\n');
        if (Str.has(atmo)) {
            sb.append(fill(c.atmoLine,
                    "{atmosphere}", atmo,
                    "{friendliness}", String.valueOf(fl),
                    "{activity}", label,
                    "{topic}", topic)).append('\n');
        }
        // 氛围差 → 多给一句态度（V3 到 FRIENDLINESS_LOW 以下才有的那一档；V3 那边是概率拒答，
        // V4 这里只影响态度：不发消息、不拒答、不改基板判定）
        if (fl <= c.badLine && Str.has(c.toneBad)) sb.append(c.toneBad.trim()).append('\n');
        return sb.toString().trim();
    }

    /** 活跃度分档标签（V3 {@code activityLabel} 的口径：累计条数分档，不是精确条数）。 */
    private static String activityLabel(Cfg c, long act) {
        if (act >= c.actVeryHigh) return c.labVeryHigh;
        if (act >= c.actHigh) return c.labHigh;
        if (act >= c.actMid) return c.labMid;
        return c.labLow;
    }

    // ================================================================ 统计（规则/统计蒸馏的全部算法）

    /** 一次窗口统计的结果。 */
    private static final class Stat {
        /** 窗口里读到的条数（取样后的）。 */
        long msgs;
        /** 发言人（去重）。 */
        final Set<Long> members = new LinkedHashSet<Long>();
        /** 命中词 → 次数（负面词 + 正面词 + 话题词）。 */
        final Map<String, Integer> hits = new LinkedHashMap<String, Integer>();
        /** 成员 → 负面命中次数。 */
        final Map<Long, Integer> negBy = new LinkedHashMap<Long, Integer>();
        /** 成员 → 正面命中次数。 */
        final Map<Long, Integer> posBy = new LinkedHashMap<Long, Integer>();
        /** 中文 2-gram 词频（话题兜底）。 */
        final Map<String, Integer> terms = new LinkedHashMap<String, Integer>();
        long negHits;
        long posHits;

        /**
         * 信号分：<b>先按成员各自封顶，再把成员分相加</b>。
         * <p>这一条就是"群内多个成员印象变差 → 群印象随之变差"的落点：
         * 一个人刷十句负面最多计 {@code 每人信号上限} 分，而十个人各一句负面会累加到饱和。</p>
         */
        int score(Cfg c) {
            int cap = c.memberCap <= 0 ? 1 : c.memberCap;
            Set<Long> all = new LinkedHashSet<Long>();
            all.addAll(negBy.keySet());
            all.addAll(posBy.keySet());
            int total = 0;
            for (Long uid : all) {
                int v = val(posBy, uid) - val(negBy, uid);
                if (v > cap) v = cap;
                if (v < -cap) v = -cap;
                total += v;
            }
            return total;
        }
    }

    /**
     * 扫一批群聊行，出统计（{@code rows} 为 null/空 = 空统计，不抛异常）。
     *
     * <p>★ 这是本插件读 {@code grouplog} 的<b>素材面唯一入口</b>：她自己的标记行在这里被整行跳过
     * （{@link #withoutSelfMarks}）—— 「机器标签不得被当成群友发言、也不得被当成人格素材」
     * 就落在这一行上（取样条数、负面/正面词计分、成员去重、话题 2-gram 一起不吃它）。
     * 条数（{@code count}）不经这里、按「日志水位」照旧收，理由见类注释。</p>
     */
    private static Stat scan(List<JsonObject> rows, Cfg c) {
        Stat st = new Stat();
        rows = withoutSelfMarks(rows);           // ★ P1+能力②：标记行 / 外部来源行都不进任何统计
        if (rows == null) return st;
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            if (r == null) continue;
            st.msgs++;
            long uid = J.l(r, "user_id", 0L);
            if (uid > 0L) st.members.add(Long.valueOf(uid));
            String text = Str.nz(J.s(r, "content", ""));
            if (text.isEmpty()) continue;
            String low = Str.lower(text);
            int neg = count(low, c.negWords, st.hits);
            int pos = count(low, c.posWords, st.hits);
            count(low, c.topicWords, st.hits);
            st.negHits += neg;
            st.posHits += pos;
            if (uid > 0L) {
                if (neg > 0) st.negBy.put(Long.valueOf(uid),
                        Integer.valueOf(val(st.negBy, Long.valueOf(uid)) + neg));
                if (pos > 0) st.posBy.put(Long.valueOf(uid),
                        Integer.valueOf(val(st.posBy, Long.valueOf(uid)) + pos));
            }
            terms(text, st.terms, c);
        }
        return st;
    }

    /** 命中计数：{@code low} 里出现过的词各自 +1（记进 {@code hits}），返回命中总数。 */
    private static int count(String low, List<String> words, Map<String, Integer> hits) {
        int n = 0;
        if (Str.blank(low) || words == null || words.isEmpty()) return 0;
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (Str.blank(w)) continue;
            if (low.indexOf(Str.lower(w)) < 0) continue;
            n++;
            if (hits != null) {
                Integer old = hits.get(w);
                hits.put(w, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
            }
        }
        return n;
    }

    /** 话题兜底：中文 2-gram 词频（表长有上限，防一条超长正文把内存吃掉）。 */
    private static void terms(String text, Map<String, Integer> out, Cfg c) {
        String t = Str.nz(text);
        int n = t.length();
        for (int i = 0; i + 2 <= n; i++) {
            if (out.size() >= 4000) return;
            char a = t.charAt(i);
            char b = t.charAt(i + 1);
            if (!cjk(a) || !cjk(b)) continue;
            String g = t.substring(i, i + 2);
            if (c.stopTerms.contains(g)) continue;
            Integer old = out.get(g);
            out.put(g, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
        }
    }

    private static boolean cjk(char ch) {
        return (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF)
                || (ch >= 0xF900 && ch <= 0xFAFF);
    }

    /** 话题：话题词表命中（加权）优先，再用 2-gram 频次（≥话题最少次数）补齐，取前「话题上限」个。 */
    private static String topicOf(Stat st, final Cfg c) {
        final Map<String, Integer> sc = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < c.topicWords.size(); i++) {
            String w = c.topicWords.get(i);
            int n = val(st.hits, w);
            if (n > 0) sc.put(w, Integer.valueOf(n * (c.topicWeight <= 0 ? 1 : c.topicWeight)));
        }
        for (Map.Entry<String, Integer> e : st.terms.entrySet()) {
            if (e.getValue().intValue() < c.topicMin) continue;
            if (sc.containsKey(e.getKey())) continue;
            sc.put(e.getKey(), e.getValue());
        }
        if (sc.isEmpty()) return "";
        List<String> words = new ArrayList<String>(sc.keySet());
        Collections.sort(words, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int va = val(sc, a);
                int vb = val(sc, b);
                if (va != vb) return vb > va ? 1 : -1;      // 分高的在前
                return a.compareTo(b);                      // 同分按字典序：结果确定，可复现
            }
        });
        List<String> top = new ArrayList<String>();
        for (int i = 0; i < words.size(); i++) {
            if (top.size() >= c.topicMax) break;
            top.add(words.get(i));
        }
        return Str.join(top, "、");
    }

    /** 命中词 → JSON（分高的在前，最多「话题上限」×2 个词，够注入与观测看）。 */
    private static String hitsJson(Stat st, final Cfg c) {
        if (st == null || st.hits.isEmpty()) return "";
        final Map<String, Integer> hits = st.hits;
        List<String> words = new ArrayList<String>(hits.keySet());
        Collections.sort(words, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int va = val(hits, a);
                int vb = val(hits, b);
                if (va != vb) return vb > va ? 1 : -1;
                return a.compareTo(b);
            }
        });
        JsonObject o = new JsonObject();
        int max = Math.max(1, c.topicMax) * 2;
        for (int i = 0; i < words.size() && i < max; i++) {
            o.addProperty(words.get(i), val(hits, words.get(i)));
        }
        return o.size() == 0 ? "" : J.json(o);
    }

    /** 从 {@code keywords} 那串 JSON 里取前几个词（降级：解析不了就当普通文本）。 */
    private static String keywordsText(String json, int max) {
        if (Str.blank(json)) return "";
        try {
            JsonObject o = J.obj(json);
            if (o == null || o.size() == 0) return Str.cut(Str.oneLine(json), 40);
            List<String> out = new ArrayList<String>();
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                if (out.size() >= Math.max(1, max)) break;
                out.add(e.getKey());
            }
            return Str.join(out, "、");
        } catch (Throwable t) {
            return Str.cut(Str.oneLine(json), 40);
        }
    }

    /** 氛围短句（规则生成，模板全在 prompt.md）。 */
    private static String atmosphere(Cfg c, int fl, Stat st, String topic) {
        String tpl = fl <= c.badLine ? c.atmoBad : (fl >= c.goodLine ? c.atmoGood : c.atmoFlat);
        return fill(tpl,
                "{friendliness}", String.valueOf(fl),
                "{msgs}", String.valueOf(st == null ? 0L : st.msgs),
                "{participants}", String.valueOf(st == null ? 0 : st.members.size()),
                "{negative}", String.valueOf(st == null ? 0L : st.negHits),
                "{positive}", String.valueOf(st == null ? 0L : st.posHits),
                "{topic}", Str.nz(topic));
    }

    private static JsonObject extra(Stat st, int score, int delta, long lifetime, long now) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", now);
        o.addProperty("sampled", st == null ? 0L : st.msgs);
        o.addProperty("members", st == null ? 0 : st.members.size());
        o.addProperty("negative", st == null ? 0L : st.negHits);
        o.addProperty("positive", st == null ? 0L : st.posHits);
        o.addProperty("score", score);
        o.addProperty("delta", delta);
        o.addProperty("activity", lifetime);
        return o;
    }

    /** 幅度折算：{@code score} 到「信号饱和分」就吃满 {@code amp}，方向按正负，四舍五入但不为 0。 */
    private static int graded(int amp, int score, int sat) {
        if (amp <= 0 || score == 0) return 0;
        int s = sat <= 0 ? 1 : sat;
        int v = (int) Math.round((double) amp * (double) score / (double) s);
        if (v > amp) v = amp;
        if (v < -amp) v = -amp;
        if (v == 0) v = score > 0 ? 1 : -1;
        return v;
    }

    // ================================================================ 库读取小工具（全部只读 + 容错）

    /** 这个群那一行（每群一行；万一有历史残留行，取 id 最小的那行当主角）。 */
    private static JsonObject find(Host h, long gid) {
        try {
            Store s = store(h);
            if (s == null || gid <= 0L) return null;
            List<JsonObject> rows = s.list(LIB, single(gid), 1, "id ASC");
            if (rows == null || rows.isEmpty()) return null;
            return rows.get(0);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code {group_id: gid}}。 */
    private static JsonObject single(long gid) {
        JsonObject f = new JsonObject();
        f.addProperty("group_id", gid);
        return f;
    }

    /** {@code {group_id: gid, _gte: {ts: since}}}（{@code since<=0} = 不加时间下界）。 */
    private static JsonObject window(long gid, long since) {
        JsonObject f = single(gid);
        if (since > 0L) {
            JsonObject gte = new JsonObject();
            gte.addProperty("ts", since);
            f.add("_gte", gte);
        }
        return f;
    }

    private static Store store(Host h) {
        try {
            return h == null ? null : h.store();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 注册表里有没有这张表（没有 = 库没声明/没建出来 → 后面一切都别做）。 */
    private static boolean hasLib(Host h) {
        try {
            Libs reg = h == null ? null : h.libs();
            return reg != null && reg.get(LIB) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================ prompt.md（惰性缓存 + mtime 失效）

    /** 配置与文案（全部来自 prompt.md；读不到就是这里的兜底默认值）。 */
    private static final class Cfg {
        boolean enabled = DEF_ENABLED;
        int budget = DEF_BUDGET;
        long writeMs = DEF_WRITE_MIN * 60000L;
        int writeAmp = DEF_WRITE_AMP;
        long intervalMs = DEF_INTERVAL_H * 3600000L;
        int minMsgs = DEF_MIN_MSGS;
        int sample = DEF_SAMPLE;
        int batch = DEF_BATCH;
        int candMax = DEF_CAND;
        long sweepMs = DEF_SWEEP_MIN * 60000L;
        int memberCap = DEF_MEMBER_CAP;
        int satScore = DEF_SAT;
        int distillAmp = DEF_DISTILL_AMP;
        int badLine = DEF_BAD_LINE;
        int goodLine = DEF_GOOD_LINE;
        int botWeight = DEF_BOT_WEIGHT;
        int actVeryHigh = DEF_ACT_VERY_HIGH;
        int actHigh = DEF_ACT_HIGH;
        int actMid = DEF_ACT_MID;
        int topicMax = DEF_TOPIC_MAX;
        int topicWeight = DEF_TOPIC_WEIGHT;
        int topicMin = DEF_TOPIC_MIN;
        String timeFmt = DEF_TIME;
        List<String> negWords = split(DEF_NEG_WORDS);
        List<String> posWords = split(DEF_POS_WORDS);
        List<String> topicWords = split(DEF_TOPIC_WORDS);
        Set<String> stopTerms = new LinkedHashSet<String>(split(DEF_STOP_WORDS));
        String title = DEF_TITLE;
        String line = DEF_LINE;
        String lineNoTopic = DEF_LINE_NOTOPIC;
        String atmoLine = DEF_ATMO_LINE;
        String toneBad = DEF_TONE_BAD;
        String labVeryHigh = DEF_LAB_VERY_HIGH;
        String labHigh = DEF_LAB_HIGH;
        String labMid = DEF_LAB_MID;
        String labLow = DEF_LAB_LOW;
        String atmoBad = DEF_ATMO_BAD;
        String atmoFlat = DEF_ATMO_FLAT;
        String atmoGood = DEF_ATMO_GOOD;
    }

    /** 读配置（惰性：文件没变就不读盘；改了文件下一轮生效，不需要重启）。 */
    private static Cfg load(Host h) {
        Cfg c = new Cfg();
        String text = Cache.of(skillDir(h));
        if (Str.blank(text)) return c;
        Map<String, String> kv = pairs(section(text, "配置"));
        Map<String, String> tx = pairs(section(text, "文案"));
        c.enabled = flag(kv.get("启用"), DEF_ENABLED);
        c.budget = atLeast(kv.get("预算"), DEF_BUDGET, 1, 20000);
        c.writeMs = Math.max(0L, (long) (dec(kv.get("回写节流分钟"), DEF_WRITE_MIN) * 60000.0D));
        c.writeAmp = atLeast(kv.get("回写最大幅度"), DEF_WRITE_AMP, 0, 100);
        c.intervalMs = Math.max(0L, (long) (dec(kv.get("蒸馏间隔小时"), DEF_INTERVAL_H) * 3600000.0D));
        c.minMsgs = atLeast(kv.get("最少消息数"), DEF_MIN_MSGS, 1, 100000);
        c.sample = atLeast(kv.get("取样条数"), DEF_SAMPLE, 1, 10000);
        c.batch = atLeast(kv.get("每轮群数"), DEF_BATCH, 1, 100);
        c.candMax = atLeast(kv.get("候选群数"), DEF_CAND, 1, 1000);
        c.sweepMs = Math.max(0L, (long) (dec(kv.get("扫描间隔分钟"), DEF_SWEEP_MIN) * 60000.0D));
        c.memberCap = atLeast(kv.get("每人信号上限"), DEF_MEMBER_CAP, 1, 100);
        c.satScore = atLeast(kv.get("信号饱和分"), DEF_SAT, 1, 1000);
        c.distillAmp = atLeast(kv.get("蒸馏最大幅度"), DEF_DISTILL_AMP, 0, 100);
        c.badLine = clamp(num(kv.get("友好度差门槛"), DEF_BAD_LINE), FL_MIN, FL_MAX);
        c.goodLine = clamp(num(kv.get("友好度好门槛"), DEF_GOOD_LINE), FL_MIN, FL_MAX);
        c.botWeight = atLeast(kv.get("被@负面加权"), DEF_BOT_WEIGHT, 1, 20);
        c.actVeryHigh = atLeast(kv.get("活跃极高"), DEF_ACT_VERY_HIGH, 1, Integer.MAX_VALUE);
        c.actHigh = atLeast(kv.get("活跃高"), DEF_ACT_HIGH, 1, Integer.MAX_VALUE);
        c.actMid = atLeast(kv.get("活跃中"), DEF_ACT_MID, 1, Integer.MAX_VALUE);
        c.topicMax = atLeast(kv.get("话题上限"), DEF_TOPIC_MAX, 1, 20);
        c.topicWeight = atLeast(kv.get("话题权重"), DEF_TOPIC_WEIGHT, 1, 100);
        c.topicMin = atLeast(kv.get("话题最少次数"), DEF_TOPIC_MIN, 1, 1000);
        c.timeFmt = def(kv.get("时间格式"), DEF_TIME);
        if (kv.containsKey("负面词")) c.negWords = split(kv.get("负面词"));
        if (kv.containsKey("正面词")) c.posWords = split(kv.get("正面词"));
        if (kv.containsKey("话题词表")) c.topicWords = split(kv.get("话题词表"));
        if (kv.containsKey("话题停用词")) c.stopTerms = new LinkedHashSet<String>(split(kv.get("话题停用词")));
        c.title = pick(tx, "标题", DEF_TITLE);
        c.line = pick(tx, "行模板", DEF_LINE);
        c.lineNoTopic = pick(tx, "行模板-无话题", DEF_LINE_NOTOPIC);
        c.atmoLine = pick(tx, "氛围行", DEF_ATMO_LINE);
        c.toneBad = pick(tx, "态度行-差", DEF_TONE_BAD);
        c.labVeryHigh = pick(tx, "活跃-极高", DEF_LAB_VERY_HIGH);
        c.labHigh = pick(tx, "活跃-高", DEF_LAB_HIGH);
        c.labMid = pick(tx, "活跃-中", DEF_LAB_MID);
        c.labLow = pick(tx, "活跃-低", DEF_LAB_LOW);
        c.atmoBad = pick(tx, "氛围-差", DEF_ATMO_BAD);
        c.atmoFlat = pick(tx, "氛围-平", DEF_ATMO_FLAT);
        c.atmoGood = pick(tx, "氛围-好", DEF_ATMO_GOOD);
        if (Str.blank(c.lineNoTopic)) c.lineNoTopic = c.line;   // 没单独给"无话题"模板 → 用带话题那条
        return c;
    }

    /** prompt.md 的文本缓存（按 File + mtime 失效；Host/目录拿不到就是空串）。 */
    private static final class Cache {
        private static File file;
        private static long stamp = -1L;
        private static String text = "";

        static synchronized String of(File d) {
            if (d == null) return "";
            try {
                File f = new File(d, "prompt.md");
                boolean exists = f.isFile();
                long m = exists ? f.lastModified() : 0L;
                if (file != null && file.equals(f) && m == stamp) return text;
                String t = exists ? Fs.read(f, "") : "";
                file = f;
                stamp = m;
                text = t;
                return t;
            } catch (Throwable t) {
                return "";
            }
        }
    }

    /**
     * 本插件的文件夹：优先 {@code h.skillDir()}，退到 {@code h.conf().skillsDir()/群印象}。
     * <p>拿到过就记住 —— 下一轮 {@code host()} 为 null 时，这一段仍然读得到自己的 prompt.md。</p>
     */
    private static File skillDir(Host h) {
        try {
            if (h != null) {
                File d = h.skillDir();
                if (d != null) {
                    dir = d;
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (h != null && h.conf() != null) {
                File d = new File(h.conf().skillsDir(), NAME);
                dir = d;
                return d;
            }
        } catch (Throwable ignored) {
        }
        return dir;
    }

    // ================================================================ 文本小工具

    /** {@code 键} → {@code 文案} 的取值（缺键/空值 = 兜底模板）。 */
    private static String pick(Map<String, String> tx, String key, String def) {
        String v = tx == null ? null : tx.get(key);
        return Str.has(v) ? v.trim() : def;
    }

    /** {@code {键}} 字面替换（不用正则：模板里带 {@code $} 也不怕）。 */
    private static String fill(String tpl, String... kv) {
        String s = Str.nz(tpl);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] == null) continue;
            s = s.replace(kv[i], Str.nz(kv[i + 1]));
        }
        return s;
    }

    /** 取 {@code ## 名称} 这一节（到下一个 {@code ## } 为止）；没有这一节返回空串。 */
    private static String section(String text, String name) {
        if (Str.blank(text) || Str.blank(name)) return "";
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String head = "## " + name.trim();
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(head)) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (lines[i].trim().startsWith("## ")) break;
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }

    /** {@code 键: 值} 一行一条；{@code #} 开头是注释；第一个（半角/全角）冒号是分隔符。 */
    private static Map<String, String> pairs(String body) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (Str.blank(body)) return m;
        for (String raw : body.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf(':');
            int j = line.indexOf('\uff1a');
            int k = i < 0 ? j : (j < 0 ? i : Math.min(i, j));
            if (k <= 0) continue;
            String key = line.substring(0, k).trim();
            String val = line.substring(k + 1).trim();
            if (!key.isEmpty()) m.put(key, val);
        }
        return m;
    }

    /** 逗号/空格分隔的词表（去空、去重、保序）。 */
    private static List<String> split(String v) {
        List<String> out = new ArrayList<String>();
        if (Str.blank(v)) return out;
        for (String p : v.split("[,，\\s]+")) {
            String t = p.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static int num(String v, int def) {
        try {
            return Integer.parseInt(Str.trim(v));
        } catch (Throwable t) {
            return def;
        }
    }

    private static int atLeast(String v, int def, int min, int max) {
        int n = v == null ? def : num(v, def);
        if (n < min) n = min;
        return n > max ? max : n;
    }

    private static double dec(String v, double def) {
        try {
            return Double.parseDouble(Str.trim(v));
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean flag(String v, boolean def) {
        String s = Str.trim(v).toLowerCase();
        if (s.isEmpty()) return def;
        if ("1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "是".equals(s)) return true;
        if ("0".equals(s) || "false".equals(s) || "no".equals(s) || "off".equals(s) || "否".equals(s)) return false;
        return def;
    }

    private static String def(String v, String fallback) {
        return Str.has(v) ? v.trim() : fallback;
    }

    private static int val(Map<Long, Integer> m, Long k) {
        if (m == null || k == null) return 0;
        Integer v = m.get(k);
        return v == null ? 0 : v.intValue();
    }

    private static int val(Map<String, Integer> m, String k) {
        if (m == null || k == null) return 0;
        Integer v = m.get(k);
        return v == null ? 0 : v.intValue();
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        return v > max ? max : v;
    }

    private static String time(Cfg c, long ts) {
        if (ts <= 0L) return "";
        try {
            return new SimpleDateFormat(Str.blank(c.timeFmt) ? DEF_TIME : c.timeFmt).format(new Date(ts));
        } catch (Throwable t) {
            return "";
        }
    }

    private static void log(Host h, String message) {
        if (Str.blank(message)) return;
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.dim("[" + NAME + "] " + message);
        } catch (Throwable ignored) {
        }
    }
}
