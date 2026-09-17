import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sair.v4.ai.Msg;
import sair.v4.ai.Res;
import sair.v4.auth.Caller;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;
import sair.v4.skill.Skill;
import sair.v4.store.LibSpec;
import sair.v4.store.Store;

/**
 * 印象蒸馏（工具 {@code impression} + {@code on_timer} 钩子）：定时把某人最近的发言蒸馏成"长期印象"，
 * 写进六库的 {@code memory}（{@code scope=user} / {@code scope_id=<qq>} / {@code source=<本技能名>}）。
 *
 * <p>一条流水线四步：<b>取样</b>（{@code grouplog} 按 {@code user_id} 聚合 + {@code dialog} 里 {@code qq:<id>}
 * 会话中该人的发言）→ <b>问模型</b>（提示词模板、输出格式、阈值、文案全在技能自己的 {@code prompt.md}，
 * 运行期读、改即生效）→ <b>查重</b>（按 scope/scope_id/source 列出现有条目做"归一见容"判断）→
 * <b>写库</b>（重复的只更新 importance，不重复的才新增）。</p>
 *
 * <p>只用 {@link Host} 的能力面：{@code h.store()} 读写六库、{@code h.ai()} 打模型、{@code h.out()} 打日志；
 * 不自己建表、不自己写 SQL、不碰基板代码。模型失败（{@code Res.error}，例如没配 apiKey）不抛异常、
 * 不写脏数据，只把这一轮的状态记进 kv，下次 tick 再试。</p>
 *
 * <h3>权限：位判定 + 归属硬判（ACL 之后）</h3>
 * <p>{@code memory} 是 C 类资源，C 类对非主人只有 {@code R} ⇒ {@code h.needDb(c.lib,'W')}
 * 把非主人写一律拦下。而"写的是谁的数据"这条路仍靠<b>归属硬判</b>分人
 * （拒绝文案以 {@code [权限阻断]} 开头，不是 prompt.md 文案）：{@link #run}
 * 是按候选池给<b>别人</b>写 {@code scope_id} 的批量入口，非主人（{@code ALLUSER}）一律拒；
 * {@code MASTER} 与她自己的自主行为（{@code SYSTEM}，{@code on_timer} 那条路）照常。</p>
 * <p>{@link #get} / {@link #list} 是读（读印象库不设限），但它们<b>强制取自己 qq</b>，
 * {@link #refresh} 同理（非主人不给定向到别人）：定向别人这件事本身由归属判定管，不由权限位管。</p>
 *
 * <h3>★ 她自己的标记行（P1-selfsee）：读 grouplog/dialog 的每个读点先过一道判据</h3>
 * <p>基板 P0-1 之后 {@code grouplog}/{@code dialog} 里会多出她<b>自己发出去</b>的那一行「标记行」
 * （{@code content} 形如 {@code [我发的 图片 msg_id=<id>]} / {@code [我发的 msg_id=<id>] <正文>}，
 * {@code extra.self=true}）。它是<b>机器写的标记行 —— 不是群友发言，也不是她的人格素材</b>。
 * 本技能四个读点分开处置（判据只有 {@link #isSelfMark} 一处）：</p>
 * <ol>
 *   <li>{@link #pick}（挑人候选池）：<b>① 整行跳过</b> —— 否则 {@code user_id=self_id} 的标记行
 *       会让她自己变成一个「要蒸的人」；</li>
 *   <li>{@link #one}（按 {@code user_id} 取 {@code grouplog} 样本 ⇒ 问模型 ⇒ 写印象）：
 *       <b>① 整行跳过</b> —— 机器外壳不许进「人格/印象/气质」素材；</li>
 *   <li>{@link #dialogOf}（{@code dialog} 里 {@code qq:<id>} 的发言）：<b>① 整行跳过</b>
 *       （口径 {@code 对话角色: user}，标记行会不会被收取决于基板给的 {@code role}，所以按行判、不按 role 判）；</li>
 *   <li>{@link #nameOf}（按 {@code user_id} 取最近一条的 {@code nickname}）：<b>③ 照旧</b> ——
 *       它只读 {@code nickname} 列取一个<b>显示名</b>（不读 {@code content}、不喂模型、不算素材），
 *       而它是 {@code limit=1} 的「最近一条」：跳过标记行只会把名字降级成空串 ＝ 抹掉一个事实。</li>
 * </ol>
 *
 * <h3>节流（跨重启不重置）</h3>
 * <p>闸门全部落在基板 {@code kv} 表里（{@code Host.store()} 的 kv 读写），进程重启不丢：</p>
 * <ul>
 *   <li>{@code distill:impression:last}：上次<b>开跑</b>的毫秒时间戳；</li>
 *   <li>{@code distill:impression:done}：上次<b>成功</b>（ok=true）的毫秒时间戳 —— 只用于判"该不该补一轮"
 *       与留痕（"距上次成功 X 小时"）；</li>
 *   <li>{@code distill:impression:status}：上一轮状态 JSON（含每人明细、抽到的模型回文片段）。</li>
 * </ul>
 * <p>上一轮成功 → 等 {@code 间隔小时}；上一轮失败 → 只等 {@code 失败重试分钟}。所以：
 * <b>过期就补一轮</b>（进程起来后第一次 tick 时，只要距上次成功 ≥ {@code 间隔小时} 就正常跑），
 * 而未到间隔的 tick 一行模型都不打。每次决策都留一行 {@code warn} 级日志（基板默认
 * {@code logConsole=tool,model}，技能自己的 {@code dim} 行会被丢掉 —— 决策行必须走 warn 才看得见）。</p>
 *
 * <h3>挑人：轮转，不是"每次挑当下最活跃的三个"</h3>
 * <p>按 {@code grouplog} 最近发言排出候选池（{@code 挑人候选} 人），用 kv {@code distill:impression:cursor}
 * 记住轮转游标，每轮从游标处往后取 {@code 每轮人数} 个<b>没在「间隔小时」内蒸过</b>的人。
 * 老口径（每轮重挑"最近活跃的 N 个"）在群里人一多就会一直重复蒸同几个话痨，别人永远轮不到 ——
 * 而"问自己的人偏偏就是没被蒸过的那个"。轮转 + 持久化游标保证：重启不倒退、每轮往前走。</p>
 *
 * <h3>写入形状：与「用户画像」的解析约定对齐</h3>
 * <p>「用户画像」把 {@code memory} 行的 {@code content} 按<b>一行一条「标签: 值」</b>解析成五维
 * （情绪稳定 / 兴趣 / 说话风格 / 诚实度 / 发图习惯）与「态度」。所以提示词要求模型给一个
 * {@code label}，命中 {@code 维度标签} 时正文写成 {@code <标签>: <句子>}；模型没给合法标签就写纯句子
 * （「用户画像」会把它当"原文补充"渲染，不会丢）。</p>
 */
public class ImpressionSkill implements Skill, Hooked {

    /** 技能名（= 文件夹名 = md 文件名），也是 memory 行 source 列的值。 */
    private static final String NAME = "印象蒸馏";
    /** 节流闸门：上次开跑的毫秒时间戳。 */
    private static final String KV_LAST = "distill:impression:last";
    /** 上次<b>成功</b>蒸馏的毫秒时间戳（判"补一轮"与留痕用）。 */
    private static final String KV_DONE = "distill:impression:done";
    /** 上一轮状态（JSON）：ts/op/ok/ran/每人明细。 */
    private static final String KV_STATUS = "distill:impression:status";
    /** 挑人轮转游标（候选池下标）：跨重启不重置，保证每轮往前走。 */
    private static final String KV_CURSOR = "distill:impression:cursor";
    /** {@code 目标库} 缺省值（结构性的库名，不是文案）。 */
    private static final String LIB_DEF = "memory";
    /** memory 的作用域取值：按人存。 */
    private static final String SCOPE_USER = "user";
    /** {@code 印象标签} 缺省值（prompt.md 缺项时的结构性兜底）。 */
    private static final String TAG_DEF = "impression";
    /** 私聊会话键前缀（基板 dialog.session 的形状：{@code qq:<id>}）。 */
    private static final String SESSION_QQ = "qq:";
    /** 写入侧「标签: 值」的分隔符（半角冒号 + 空格；「用户画像」半角/全角都认）。 */
    private static final String LABEL_SEP = ": ";
    /** {@code 维度标签} 的缺省值：与「用户画像」prompt.md 的「维度」+「态度键」逐字对齐。 */
    private static final String[] LABEL_DEF = {"情绪稳定", "兴趣", "说话风格", "诚实度", "发图习惯", "态度"};
    /** 拒绝文案的结构前缀（不是 prompt.md 文案）：归属判定不能被改配置改掉。 */
    private static final String DENY = "[权限阻断] ";

    // ================================================================ ★ 她自己的标记行（P1-selfsee 唯一一处判据）

    /** 标记行在 {@code content} 上的<b>逐字</b>前缀（尾部的空格也算，判据不许过宽）。 */
    private static final String SELF_MARK_PREFIX = "[我发的 ";

    /**
     * ★ P1「她自己的标记行」判据 —— <b>本技能唯一一处</b>（不许在业务逻辑里散写
     * {@code if (content.startsWith(...))}）。
     *
     * <p>基板 P0-1 落地后，她<b>自己发出去</b>的消息/图会在 {@code grouplog}（群）/
     * {@code dialog}（私聊）里补一行标记行：{@code content} =
     * {@code [我发的 图片 msg_id=<id>]} 或 {@code [我发的 msg_id=<id>] <正文>}，并带
     * {@code extra.self=true} + {@code extra.action} + {@code extra.sent_id}。
     * <b>它是机器写的标记行 —— 不是群友发言，也不是她的人格素材。</b></p>
     *
     * <p>两条判据<b>都要</b>（只读消费面可能把 {@code extra} 丢掉）：</p>
     * <ol>
     *   <li>{@code extra.self == true}：{@code extra} 是 TEXT 列的 JSON 串（{@code Db.decode}
     *       让出参是对象；解析失败/缺失就当<b>没有它</b>）；</li>
     *   <li>兜底：{@code content} <b>逐字</b>以 {@link #SELF_MARK_PREFIX} 开头（含尾部空格）。</li>
     * </ol>
     *
     * <p><b>判据不许走样</b>：① 只能用 {@code startsWith} —— 不许 {@code contains("[我发的")}，
     * 也不许退化成「以 {@code [} 开头」：{@code [图片 …]}/{@code [折叠转发 …]}/{@code [戳一戳]}
     * 那些是<b>基板事实标签</b>，本判据一律 {@code false}（本任务不许过滤它们）；
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

    /** {@code extra} 列（TEXT 的 JSON 串，或已被 {@code Db.decode} 解析成对象）；解析不出来返回 null。 */
    private static JsonObject rowExtra(JsonObject row) {
        try {
            JsonElement e = J.get(row, "extra");
            if (e == null || e.isJsonNull()) return null;
            if (e.isJsonObject()) return e.getAsJsonObject();
            return e.isJsonPrimitive() ? J.obj(e.getAsString()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 去掉标记行（{@link #isSelfMark}）。
     * <p>一个标记行都没有时<b>原样返回入参</b>（同一引用、同一顺序）—— 这就是「没有标记行 ⇒ 与改动前逐字等价」。</p>
     */
    static List<JsonObject> withoutSelfMarks(List<JsonObject> rows) {
        if (rows == null || rows.isEmpty()) return rows;
        boolean has = false;
        for (int i = 0; i < rows.size(); i++) {
            if (isSelfMark(rows.get(i))) {
                has = true;
                break;
            }
        }
        if (!has) return rows;
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            if (!isSelfMark(r)) out.add(r);
        }
        return out;
    }

    /** 「未到间隔」这类每个 tick 都会撞上的决策行：本进程第一条必打，之后最多每小时一条。 */
    private static volatile long lastIdleNote = 0L;
    /** 上面那条的间隔（毫秒）。 */
    private static final long IDLE_NOTE_GAP = 3600_000L;

    // ================================================================ 工具入口

    @Override
    public Object call(Args a, Host h) {
        if (h == null || h.store() == null) return "[" + NAME + "] 拿不到六库接口，技能不可用。";
        try {
            String op = Str.trim(a.str("op")).toLowerCase();
            if (op.isEmpty()) op = "get";
            if ("get".equals(op)) return get(a, h);
            if ("list".equals(op)) return list(a, h);
            if ("refresh".equals(op)) return refresh(a, h);
            if ("run".equals(op)) return run(a, h);
            return "[" + NAME + "] 不支持的 op：" + op + "（只支持 get / list / refresh / run）";
        } catch (Throwable t) {
            log(h, "工具调用异常：" + t);
            return "[" + NAME + "] 执行异常：" + t;
        }
    }

    // ================================================================ 钩子入口

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        if (!"on_timer".equals(hook) || h == null) return;
        try {
            tickTimer(h);
        } catch (Throwable t) {
            log(h, "on_timer 异常：" + t);
        }
    }

    /** 定时流水线：读配置 → 节流 → 挑人（轮转）→ 逐个蒸馏 → 落状态 + 留痕。 */
    private static void tickTimer(Host h) {
        Cfg c = load(h);
        if (!c.enabled) {
            noteIdle(h, msg(c, "文案-未启用日志", "未启用：prompt.md 的「启用」不是 true，这一轮不动"));
            return;
        }
        Gate g = gate(h, c);
        if (g.wait > 0L) {
            noteIdle(h, g.why);                                  // 未到间隔：一行字说清为什么没蒸
            return;
        }
        if (!Str.blank(g.why)) note(h, g.why);                   // 开跑/补一轮：留痕
        Pick p = pick(h, c);
        if (p.people.isEmpty()) {
            note(h, msg(c, "文案-没有候选", "本轮没有可蒸的人：{why}", "{why}", p.why));
            round(h, c, new ArrayList<Who>(), "timer", p);
            return;
        }
        note(h, msg(c, "文案-挑人", "本轮挑中 {who}（候选池 {pool} 人，游标 {from}→{to}，跳过刚蒸过 {skip} 人）",
                "{who}", who(p.people), "{pool}", String.valueOf(p.pool),
                "{from}", String.valueOf(p.start), "{to}", String.valueOf(p.next),
                "{skip}", String.valueOf(p.skipFresh)));
        round(h, c, p.people, "timer", p);
    }

    // ================================================================ 工具 op

    /** {@code get}：读某个人的印象条目（缺省 = 当前调用者）。 */
    private static String get(Args a, Host h) {
        Cfg c = load(h);
        String deny = h.needDb(c.lib, 'R');            // 读印象库（C:R）
        if (deny != null) return deny;
        long qq = a.l("qq", 0L);
        Caller me = h.caller();
        if (qq <= 0L) qq = me == null ? 0L : me.qq();
        String forced = "";
        if (!isMaster(me) && me != null && qq != me.qq()) {
            qq = me.qq();
            forced = " " + msg(c, "文案-非主人限自己", "self_only qq={qq}", "{qq}", String.valueOf(qq));
        }
        if (qq <= 0L) return "[" + NAME + "] 不知道要读谁的印象（qq 缺省时按当前调用者取，而调用者没有 QQ）。";
        Store s = h.store();
        JsonObject f = J.obj("source", NAME, "scope", SCOPE_USER, "scope_id", String.valueOf(qq));
        List<JsonObject> rows = s.list(c.lib, f, c.readLimit, "importance DESC, ts DESC");
        if (rows.isEmpty()) {
            return msg(c, "文案-无印象", "no_impression qq={qq}", "{qq}", String.valueOf(qq)) + forced;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, "文案-条目头", "qq={qq} n={n}",
                "{qq}", String.valueOf(qq), "{n}", String.valueOf(rows.size()))).append("\n");
        int i = 0;
        for (JsonObject r : rows) {
            i++;
            sb.append(msg(c, "文案-条目行", "{i}. #{id} [{importance}] {content} tags={tags}",
                    "{i}", String.valueOf(i),
                    "{id}", String.valueOf(J.l(r, "id", 0L)),
                    "{importance}", String.valueOf(J.i(r, "importance", 0)),
                    "{content}", J.s(r, "content", ""),
                    "{tags}", tagsOf(r))).append("\n");
        }
        return sb.toString().trim() + forced;
    }

    /** {@code list}：列出已有印象的人（去重 + 条数）。 */
    private static String list(Args a, Host h) {
        Cfg c = load(h);
        String deny = h.needDb(c.lib, 'R');            // 读印象库（C:R）
        if (deny != null) return deny;
        Caller me = h.caller();
        boolean master = isMaster(me);
        Store s = h.store();
        JsonObject f = J.obj("source", NAME, "scope", SCOPE_USER);
        if (!master && me != null) f.addProperty("scope_id", String.valueOf(me.qq()));
        List<JsonObject> rows = s.list(c.lib, f, 1000, null);
        Map<String, int[]> agg = new LinkedHashMap<String, int[]>();
        for (JsonObject r : rows) {
            String id = J.s(r, "scope_id", "");
            if (Str.blank(id)) continue;
            int[] n = agg.get(id);
            if (n == null) {
                n = new int[] {0, 0};
                agg.put(id, n);
            }
            n[0]++;
            n[1] = Math.max(n[1], J.i(r, "importance", 0));
        }
        if (agg.isEmpty()) return msg(c, "文案-没有覆盖", "no_person n=0");
        List<String> ids = new ArrayList<String>(agg.keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, "文案-人数头", "people={n}",
                "{n}", String.valueOf(ids.size()))).append("\n");
        int shown = 0;
        for (String id : ids) {
            if (shown >= c.listLimit) break;
            shown++;
            int[] n = agg.get(id);
            sb.append(msg(c, "文案-人一行", "{qq} × {n} (max {importance})",
                    "{qq}", id, "{n}", String.valueOf(n[0]),
                    "{importance}", String.valueOf(n[1]))).append("\n");
        }
        if (ids.size() > shown) {
            sb.append(msg(c, "文案-人省略", "…还有 {more} 个", "{more}", String.valueOf(ids.size() - shown))).append("\n");
        }
        return sb.toString().trim();
    }

    /** {@code refresh}：立刻对一个人做一次蒸馏。 */
    private static String refresh(Args a, Host h) {
        Cfg c = load(h);
        // 这是她自己的主动行为，不是"碰某个库"：判的是这个工具的 X 位（工具属 T 类，只判入口）。
        // 定向到别人靠下面那句"非主人强制取自己 qq"（归属硬判），不靠权限位。
        String denyRun = h.need(sair.v4.auth.Res.tool("impression"), 'X');
        if (denyRun != null) return denyRun;
        Caller me = h.caller();
        long qq = a.l("qq", 0L);
        if (qq <= 0L) qq = me == null ? 0L : me.qq();
        String forced = "";
        if (!isMaster(me) && me != null && qq != me.qq()) {
            qq = me.qq();
            forced = " " + msg(c, "文案-非主人限自己", "self_only qq={qq}", "{qq}", String.valueOf(qq));
        }
        if (qq <= 0L) return "[" + NAME + "] 不知道要蒸馏谁（qq 缺省时按当前调用者取，而调用者没有 QQ）。";
        if (!c.enabled) return msg(c, "文案-未启用", "disabled");
        Gate g = gate(h, c);
        if (g.wait > 0L) return msg(c, "文案-跳过节流", "skip wait={wait}ms", "{wait}", String.valueOf(g.wait));
        List<Who> people = new ArrayList<Who>();
        people.add(new Who(qq, nameOf(h, qq)));
        note(h, msg(c, "文案-手动蒸", "手动 refresh：{qq}（{name}）", "{qq}", String.valueOf(qq),
                "{name}", nameOf(h, qq)));
        String r = round(h, c, people, "refresh", null);
        return (Str.blank(r) ? msg(c, "文案-没有语料", "no_corpus") : r) + forced;
    }

    /** {@code run}：立刻对"轮转到的下一批人"批量跑一轮（受 每轮人数 约束，仅主人与她本人）。 */
    private static String run(Args a, Host h) {
        Cfg c = load(h);
        Caller me = h.caller();
        // 先判归属，再判位：本轮是"她自己的维护"（按 candidate 池给**别人**写印象），
        // 归属硬判在前 —— 被拒时一行模型都不打、库一个字节都不动。
        String ownerDeny = noDistillDeny(h);
        if (ownerDeny != null) return ownerDeny;
        // 同一把工具的 X 位（工具属 T 类，只判入口）：归属过了之后再按位兜一次。
        String deny = h.need(sair.v4.auth.Res.tool("impression"), 'X');
        if (deny != null) return deny;
        if (!c.enabled) return msg(c, "文案-未启用", "disabled");
        Gate g = gate(h, c);
        if (g.wait > 0L) return msg(c, "文案-跳过节流", "skip wait={wait}ms", "{wait}", String.valueOf(g.wait));
        Pick p = pick(h, c);
        if (p.people.isEmpty()) return msg(c, "文案-没有候选", "本轮没有可蒸的人：{why}", "{why}", p.why);
        note(h, msg(c, "文案-挑人", "本轮挑中 {who}（候选池 {pool} 人，游标 {from}→{to}，跳过刚蒸过 {skip} 人）",
                "{who}", who(p.people), "{pool}", String.valueOf(p.pool),
                "{from}", String.valueOf(p.start), "{to}", String.valueOf(p.next),
                "{skip}", String.valueOf(p.skipFresh)));
        String r = round(h, c, p.people, "run", p);
        return Str.blank(r) ? msg(c, "文案-没有语料", "no_corpus") : r;
    }

    // ================================================================ 一轮蒸馏

    /** 对一批人各跑一次蒸馏，返回可读结果（并按口径落 kv：last / done / status）。 */
    private static String round(Host h, Cfg c, List<Who> people, String op, Pick p) {
        // 这一轮要写印象库（C:W）+ 读 dialog/grouplog。注意 C 类对非主人只有 R ⇒ 这道 W 位判定
        // 把非主人写一律拦下；而"分人"仍靠**归属硬判**：
        // 定时钩子路径主体是 SYSTEM（她自己的维护，在 {@link #run}/{@link #refresh} 里已判过归属）；
        // 工具路径上 ALLUSER 一律在 {@link #run} 就被拒，refresh 则强制只能蒸自己。
        String denyWrite = h.needDb(c.lib, 'W');
        if (denyWrite != null) return denyWrite;
        long now = System.currentTimeMillis();
        JsonArray detail = new JsonArray();
        StringBuilder sb = new StringBuilder();
        int ran = 0, add = 0, upd = 0, dup = 0, low = 0, cand = 0, skipped = 0, failed = 0;
        String error = "";
        List<String> reasons = new ArrayList<String>();
        for (Who w : people) {
            One r = one(h, c, w);
            if (r.model) ran++;
            if (!Str.blank(r.error)) {
                error = r.error;
                failed++;
            }
            if (!Str.blank(r.skip)) {
                skipped++;
                reasons.add(Str.cut(Str.oneLine(r.skip), 160));
            }
            add += r.add;
            upd += r.upd;
            dup += r.dup;
            low += r.low;
            cand += r.cand;
            JsonObject o = new JsonObject();
            o.addProperty("qq", r.qq);
            o.addProperty("name", r.name);
            o.addProperty("sampled", r.sampled);
            o.addProperty("cand", r.cand);
            o.addProperty("add", r.add);
            o.addProperty("upd", r.upd);
            o.addProperty("dup", r.dup);
            o.addProperty("low", r.low);
            o.addProperty("model", r.model);
            o.addProperty("label", r.label);
            if (!Str.blank(r.skip)) o.addProperty("skip", r.skip);
            if (!Str.blank(r.error)) o.addProperty("error", r.error);
            if (!Str.blank(r.raw)) o.addProperty("raw", r.raw);
            detail.add(o);
            String line = r.line(c);
            if (!Str.blank(line)) sb.append(line).append("\n");
            log(h, line);
        }
        boolean ok = Str.blank(error);
        if (ran > 0) h.store().kvSet(KV_LAST, String.valueOf(now));
        if (ok && ran > 0) h.store().kvSet(KV_DONE, String.valueOf(now));
        JsonObject st = new JsonObject();
        st.addProperty("ts", now);
        st.addProperty("op", op);
        st.addProperty("ok", ok);
        st.addProperty("ran", ran);
        st.addProperty("skipped", skipped);
        st.addProperty("failed", failed);
        st.addProperty("cand", cand);
        st.addProperty("add", add);
        st.addProperty("upd", upd);
        st.addProperty("dup", dup);
        st.addProperty("low", low);
        if (!Str.blank(error)) st.addProperty("error", error);
        if (p != null) {
            st.addProperty("pool", p.pool);
            st.addProperty("cursor", p.next);
            st.addProperty("skip_fresh", p.skipFresh);
            if (!Str.blank(p.why)) st.addProperty("why", p.why);
            JsonArray picked = new JsonArray();
            for (Who w : p.people) picked.add(w.qq);
            st.add("picked", picked);
        }
        st.add("people", detail);
        st.addProperty("last", ran > 0 ? now : num(h.store().kv(KV_LAST, "0"), 0L));
        st.addProperty("done", num(h.store().kv(KV_DONE, "0"), 0L));
        try {
            h.store().kvSet(KV_STATUS, J.json(st));
        } catch (Throwable ignored) {
        }
        if (people.size() > 0) {
            note(h, summary(c, op, people.size(), ran, add, upd, dup, low, skipped, failed, error, reasons));
        }
        return sb.toString().trim();
    }

    /**
     * 一轮的人话汇总（warn：默认 logConsole=tool,model 也看得见），并说清"为什么这轮没写成"。
     * <p>跳过原因（样本不足 / 模型失败 / 解析失败）也并进这一行 —— 否则"缺料"这种最常见的
     * "为什么没蒸"只会落在 {@code dim} 过程行上，线上默认档位里一句都看不到。</p>
     */
    private static String summary(Cfg c, String op, int people, int ran, int add, int upd,
                                  int dup, int low, int skipped, int failed, String error,
                                  List<String> reasons) {
        StringBuilder tail = new StringBuilder();
        if (add > 0 || upd > 0) {
            tail.append("");
        } else if (failed > 0) {
            tail.append(" → 这一轮没写成印象（失败：" + Str.cut(Str.oneLine(error), 120) + "）");
        } else if (dup > 0) {
            tail.append(" → 这一轮没有新印象（候选都被查重跳过）");
        } else if (low > 0) {
            tail.append(" → 这一轮没有新印象（候选都低于最低重要度）");
        } else if (skipped > 0) {
            tail.append(" → 这一轮没有新印象（人都被跳过）");
        } else {
            tail.append(" → 这一轮没有新印象（模型没给出可写的条目）");
        }
        StringBuilder why = new StringBuilder();
        if (reasons != null && !reasons.isEmpty()) {
            int shown = 0;
            for (String x : reasons) {
                if (Str.blank(x)) continue;
                if (shown >= 3) break;
                if (why.length() > 0) why.append("；");
                why.append(x);
                shown++;
            }
            if (reasons.size() > shown) why.append("；…还有 ").append(reasons.size() - shown).append(" 条");
        }
        return msg(c, "文案-本轮汇总",
                "本轮（{op}）{people} 人：调用模型 {ran}、新增 {add}、更新 {upd}、查重跳过 {dup}、"
                + "低分丢弃 {low}、跳过 {skipped}、失败 {failed}{tail}{why}",
                "{op}", Str.nz(op), "{people}", String.valueOf(people), "{ran}", String.valueOf(ran),
                "{add}", String.valueOf(add), "{upd}", String.valueOf(upd), "{dup}", String.valueOf(dup),
                "{low}", String.valueOf(low), "{skipped}", String.valueOf(skipped),
                "{failed}", String.valueOf(failed), "{tail}", tail.toString(),
                "{why}", why.length() == 0 ? "" : "｜跳过原因：" + why);
    }

    /** 对一个人跑一次：取样 → 问模型 → 查重 → 写库。任何一步不通过都只是"这一轮没有产出"。 */
    private static One one(Host h, Cfg c, Who w) {
        One r = new One();
        r.qq = w.qq;
        r.name = w.name;
        Store s = h.store();
        List<JsonObject> gl = s.list("grouplog", J.obj("user_id", w.qq), c.perPerson, "ts DESC");
        gl = withoutSelfMarks(gl);            // ★ P1-selfsee：标记行不进「人格素材」取样
        List<JsonObject> dlg = dialogOf(s, c, w.qq);
        r.sampled = gl.size() + dlg.size();
        if (r.sampled < c.minMsgs) {
            r.skip = msg(c, "文案-跳过样本不足", "skip sampled={sampled} min={min} qq={qq}",
                    "{qq}", String.valueOf(w.qq), "{sampled}", String.valueOf(r.sampled),
                    "{min}", String.valueOf(c.minMsgs));
            return r;
        }
        if (Str.blank(c.tpl)) {
            r.skip = msg(c, "文案-缺提示词", "skip no_template");
            return r;
        }
        String body = sample(c, gl, dlg, w);
        // 这一轮取样覆盖到的群（印象是"按人"的，跨群；把来源群记下来，回想时标得出场合）
        List<Long> srcGroups = groupIds(gl);
        String prompt = fill(c.tpl, "{qq}", String.valueOf(w.qq), "{name}", Str.nz(w.name),
                "{msgs}", body, "{count}", String.valueOf(r.sampled), "{source}", NAME,
                "{labels}", Str.join(c.labels, " / "));
        JsonArray msgs = new JsonArray();
        msgs.add(Msg.user(prompt));
        JsonObject opts = new JsonObject();
        opts.addProperty("temperature", c.temperature);
        Res res = h.ai() == null ? null : h.ai().chat(msgs, null, opts);
        r.model = true;
        if (res == null || !res.ok()) {
            r.error = res == null ? "ai client missing" : Str.nz(res.error);
            r.skip = msg(c, "文案-模型失败", "model_failed error={error}",
                    "{error}", r.error);
            note(h, "qq=" + w.qq + " " + r.skip);                 // 模型失败必须看得见
            return r;
        }
        List<JsonObject> items = parseItems(res.text());
        if (items == null) {
            r.error = "parse_failed";
            r.raw = excerpt(res.text());
            r.skip = msg(c, "文案-解析失败", "parse_failed not_json_array raw={raw}", "{raw}", r.raw);
            note(h, "qq=" + w.qq + " " + r.skip);                 // 解析失败必须看得见（带模型回文片段）
            return r;
        }
        r.cand = items.size();
        List<JsonObject> exist = s.list(c.lib,
                J.obj("source", NAME, "scope", SCOPE_USER, "scope_id", String.valueOf(w.qq)), 500, null);
        for (JsonObject it : items) {
            String content = Str.trim(J.s(it, "content", ""));
            if (Str.blank(content)) continue;
            int imp = J.i(it, "importance", 0);
            if (imp < c.minImportance) {
                r.low++;
                continue;
            }
            String label = labelOf(c, it);
            String shaped = shape(c, label, content);             // 命中「维度标签」→「标签: 值」（「用户画像」认这个形状）
            JsonObject same = findDup(exist, c, shaped);
            if (same != null) {
                long id = J.l(same, "id", 0L);
                if (imp > J.i(same, "importance", 0)) {
                    if (s.update(c.lib, id, J.obj("importance", imp))) {
                        same.addProperty("importance", imp);
                        r.upd++;
                    } else {
                        r.dup++;
                    }
                } else {
                    r.dup++;
                }
                // 已经在库里的印象：把这一轮取样覆盖到的群并进 extra.groups（来源群会随时间累积）
                JsonObject merged = mergeGroups(J.sub(same, "extra"), srcGroups);
                if (merged != null && s.update(c.lib, id, J.obj("extra", merged))) {
                    same.add("extra", merged);
                }
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("scope", SCOPE_USER);
            row.addProperty("scope_id", String.valueOf(w.qq));
            row.addProperty("content", shaped);
            row.addProperty("importance", imp);
            row.addProperty("source", NAME);
            row.addProperty("tags", tagsFor(c, it));
            // 来源群（纯数据）：印象是按人记的，但"在哪些群见过他聊这些"要留痕
            JsonObject ex = mergeGroups(null, srcGroups);
            if (ex != null) row.add("extra", ex);
            long id = s.put(c.lib, row);
            if (id <= 0L) {
                r.error = "store put failed";
                continue;
            }
            row.addProperty("id", id);
            exist.add(row);
            r.add++;
            if (Str.blank(r.label)) r.label = label;             // 状态里留一份"这一轮用的标签"
        }
        return r;
    }

    // ================================================================ 取样

    /** {@code dialog} 里这个人的发言（默认只收 role=user，见 prompt.md 的 对话角色）。 */
    private static List<JsonObject> dialogOf(Store s, Cfg c, long qq) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (c.roles.isEmpty()) {
            // ★ P1-selfsee：她自己的标记行整行跳过（机器外壳不是这个人说的话）
            out.addAll(withoutSelfMarks(s.list("dialog", J.obj("session", SESSION_QQ + qq), c.perPerson, "ts DESC")));
        } else {
            for (String role : c.roles) {
                out.addAll(withoutSelfMarks(s.list("dialog",
                        J.obj("session", SESSION_QQ + qq, "role", role), c.perPerson, "ts DESC")));
            }
            sortDesc(out);
            while (out.size() > c.perPerson) out.remove(out.size() - 1);
        }
        return out;
    }

    /** 样本里出现过的群号（去重、按出现顺序）：印象按人记，这些群是它的来源场合。 */
    private static List<Long> groupIds(List<JsonObject> gl) {
        List<Long> out = new ArrayList<Long>();
        if (gl == null) return out;
        for (JsonObject r : gl) {
            long g = J.l(r, "group_id", 0L);
            if (g > 0L && !out.contains(Long.valueOf(g))) out.add(Long.valueOf(g));
        }
        return out;
    }

    /**
     * 把来源群并进 {@code extra.groups}（已有的保留并去重）。
     * <p>印象是<b>按人</b>的（跨群），但"这些印象是在哪些群看他聊出来的"要留痕：
     * 记忆技能回想时会用它标出来源场合。没有群、也没有旧 extra 时返回 null（不写空 extra）。</p>
     */
    private static JsonObject mergeGroups(JsonObject ex, List<Long> groups) {
        List<Long> all = new ArrayList<Long>();
        if (ex != null) {
            JsonArray had = J.list(ex, "groups");
            if (had != null) {
                for (JsonElement e : had) {
                    if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) continue;
                    try {
                        long g = e.getAsLong();
                        if (g > 0L && !all.contains(Long.valueOf(g))) all.add(Long.valueOf(g));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (groups != null) {
            for (Long g : groups) {
                if (g != null && g.longValue() > 0L && !all.contains(g)) all.add(g);
            }
        }
        if (all.isEmpty()) return null;
        JsonObject out = ex == null ? new JsonObject() : ex.deepCopy();
        JsonArray a = new JsonArray();
        for (Long g : all) a.add(g);
        out.add("groups", a);
        return out;
    }

    /** 把样本渲染成给模型看的文本（行格式也是 prompt.md 的配置项）。 */
    private static String sample(Cfg c, List<JsonObject> gl, List<JsonObject> dlg, Who w) {
        List<Line> lines = new ArrayList<Line>();
        SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm");
        for (JsonObject row : gl) {
            long ts = J.l(row, "ts", 0L);
            String name = Str.trim(J.s(row, "nickname", ""));
            lines.add(new Line(ts, fill(c.lineGroup,
                    "{time}", ts > 0 ? df.format(new Date(ts)) : "-",
                    "{group}", String.valueOf(J.l(row, "group_id", 0L)),
                    "{qq}", String.valueOf(J.l(row, "user_id", w.qq)),
                    "{name}", Str.has(name) ? name : Str.nz(w.name),
                    "{text}", Str.cut(Str.oneLine(J.s(row, "content", "")), c.maxChars))));
        }
        for (JsonObject row : dlg) {
            long ts = J.l(row, "ts", 0L);
            lines.add(new Line(ts, fill(c.lineDialog,
                    "{time}", ts > 0 ? df.format(new Date(ts)) : "-",
                    "{role}", Str.nz(J.s(row, "role", "")),
                    "{qq}", String.valueOf(w.qq),
                    "{name}", Str.nz(w.name),
                    "{text}", Str.cut(Str.oneLine(J.s(row, "content", "")), c.maxChars))));
        }
        java.util.Collections.sort(lines, new java.util.Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return a.ts < b.ts ? -1 : (a.ts > b.ts ? 1 : 0);
            }
        });
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) sb.append(l.text).append("\n");
        return sb.toString().trim();
    }

    // ================================================================ 挑人（轮转）

    /** 一次挑人的结果（含"为什么挑了这些/为什么没得挑"，供日志与 kv 状态留痕）。 */
    private static final class Pick {
        final List<Who> people = new ArrayList<Who>();
        int pool;
        int start;
        int next;
        int skipFresh;
        String why = "";
    }

    /**
     * 挑"这一轮蒸谁"：候选池 = {@code grouplog} 最近发言去重后的 {@code 挑人候选} 个人（新的在前）；
     * 游标落在 kv {@code distill:impression:cursor}（跨重启不重置），从游标往后取 {@code 每轮人数} 个
     * <b>没在「间隔小时」内蒸过</b>的人。轮转的语义：每轮往前走，重启不倒退，不会一直重复蒸同几个话痨。
     */
    private static Pick pick(Host h, Cfg c) {
        Pick p = new Pick();
        Store s = h.store();
        List<JsonObject> rows = s.list("grouplog", null, Math.max(200, c.cands * 4), "ts DESC");
        LinkedHashMap<Long, String> pool = new LinkedHashMap<Long, String>();
        for (JsonObject r : rows) {
            if (isSelfMark(r)) continue;                              // ★ P1-selfsee：标记行不许让"她"进候选池
            long qq = J.l(r, "user_id", 0L);
            if (qq <= 0L || pool.containsKey(Long.valueOf(qq))) continue;
            pool.put(Long.valueOf(qq), Str.nz(J.s(r, "nickname", "")));
            if (pool.size() >= c.cands) break;
        }
        p.pool = pool.size();
        if (pool.isEmpty()) {
            p.why = "grouplog 里没有带 user_id 的发言（库里没料）";
            return p;
        }
        List<Long> ring = new ArrayList<Long>(pool.keySet());
        Map<Long, Long> fresh = lastImpression(s, c);
        int start = (int) (num(s.kv(KV_CURSOR, "0"), 0L) % ring.size());
        if (start < 0) start = 0;
        p.start = start;
        long now = System.currentTimeMillis();
        int i = 0;
        while (p.people.size() < c.batch && i < ring.size()) {
            Long qq = ring.get((start + i) % ring.size());
            i++;
            Long last = fresh.get(qq);
            if (last != null && last.longValue() > 0L && c.intervalMs > 0L
                    && now - last.longValue() < c.intervalMs) {
                p.skipFresh++;                                    // 刚蒸过（间隔内）→ 跳过，往后走
                continue;
            }
            p.people.add(new Who(qq.longValue(), pool.get(qq)));
        }
        p.next = (start + i) % ring.size();
        if (p.people.isEmpty()) {
            p.why = "候选池 " + p.pool + " 人都在「间隔小时」内蒸过（这轮没有到期的人）";
        }
        try {
            s.kvSet(KV_CURSOR, String.valueOf(p.next));           // 游标落 kv：重启不倒退
        } catch (Throwable ignored) {
        }
        return p;
    }

    /**
     * 每个人的最新印象时间（{@code scope=user} 且 {@code source} 含本技能名 —— 与「用户画像」的
     * 「印象来源」口径一致，V3 迁移过来的 {@code source=印象蒸馏(V3迁移)} 也算数）。
     */
    private static Map<Long, Long> lastImpression(Store s, Cfg c) {
        Map<Long, Long> m = new LinkedHashMap<Long, Long>();
        try {
            JsonObject f = J.obj("scope", SCOPE_USER, "_like", J.obj("source", NAME));
            List<JsonObject> rows = s.list(c.lib, f, 5000, null);
            for (JsonObject r : rows) {
                long qq = num(J.s(r, "scope_id", ""), 0L);
                if (qq <= 0L) continue;
                long ts = J.l(r, "ts", 0L);
                Long old = m.get(Long.valueOf(qq));
                if (old == null || ts > old.longValue()) m.put(Long.valueOf(qq), Long.valueOf(ts));
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    /** 从库里找人名（有就用，没有就空）。 */
    private static String nameOf(Host h, long qq) {
        try {
            // ★ P1-selfsee：这里**不过**标记行谓词（③ 照旧）—— 只读 nickname 列取显示名，
            // 不读 content、不喂模型；而它是 limit=1 的"最近一条"，跳过标记行只会把名字降级成空串。
            List<JsonObject> rows = h.store().list("grouplog", J.obj("user_id", qq), 1, "ts DESC");
            if (!rows.isEmpty()) return Str.nz(J.s(rows.get(0), "nickname", ""));
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ================================================================ 查重

    /**
     * 查重：把两边都归一化成"只留字母数字与汉字"（先剥掉「标签: 」前缀，免得标签不同就算成两条），
     * 完全相等、或一方包含另一方（且短的那边够长）就算同一条。命中就只更新 importance，绝不重复堆。
     */
    private static JsonObject findDup(List<JsonObject> exist, Cfg c, String content) {
        String a = norm(bare(c, content));
        if (a.isEmpty()) return null;
        for (JsonObject r : exist) {
            String b = norm(bare(c, J.s(r, "content", "")));
            if (b.isEmpty()) continue;
            if (a.equals(b)) return r;
            String sh = a.length() <= b.length() ? a : b;
            String lo = a.length() <= b.length() ? b : a;
            if (sh.length() >= c.dupMin && lo.contains(sh)) return r;
        }
        return null;
    }

    private static String norm(String s) {
        return Str.nz(s).toLowerCase().replaceAll("[^0-9a-z\\u4e00-\\u9fff]+", "");
    }

    // ================================================================ 写入形状（与「用户画像」对齐）

    /**
     * 模型给的标签：只有逐字落在 {@code 维度标签} 里才算（{@code label} / {@code 维度} / {@code dim} 三个键都认）。
     * 不在清单里返回空串 —— 宁可写成纯句子（「用户画像」按"原文补充"渲染），也不写一个它解析不了的标签。
     */
    private static String labelOf(Cfg c, JsonObject item) {
        String v = Str.trim(J.s(item, "label", ""));
        if (v.isEmpty()) v = Str.trim(J.s(item, "维度", ""));
        if (v.isEmpty()) v = Str.trim(J.s(item, "dim", ""));
        if (v.isEmpty()) return "";
        for (String x : c.labels) {
            if (x.equals(v)) return x;
        }
        return "";
    }

    /** 写入侧正文：命中「维度标签」→ {@code 标签: 值}（「用户画像」按这个形状解析成五维/态度）。 */
    private static String shape(Cfg c, String label, String content) {
        return Str.blank(label) ? content : label + LABEL_SEP + content;
    }

    /** 正文里可能带着 {@code 标签: } 前缀：比对与查重时只看"值"那部分。 */
    private static String bare(Cfg c, String content) {
        String s = Str.nz(content);
        int i = colon(s);
        if (i > 0 && i <= 16) {
            String head = Str.trim(s.substring(0, i));
            for (String x : c.labels) {
                if (x.equals(head)) return Str.trim(s.substring(i + 1));
            }
        }
        return s;
    }

    /** 行内第一个半角或全角冒号的下标（没有返回 -1）。 */
    private static int colon(String s) {
        int a = s.indexOf(':');
        int b = s.indexOf('\uff1a');
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /** 模型回文的前后各 200 字（脱敏口径：只截断，不改写）。 */
    private static String excerpt(String text) {
        String s = Str.oneLine(Str.nz(text));
        if (s.isEmpty()) return "(空)";
        if (s.length() <= 400) return s;
        return s.substring(0, 200) + " … " + s.substring(s.length() - 200);
    }

    // ================================================================ 模型输出解析

    /** 解析成 JSON 数组；不是数组就返回 null（调用方跳过，不写脏数据）。 */
    private static List<JsonObject> parseItems(String text) {
        if (Str.blank(text)) return null;
        String s = text.trim();
        if (s.startsWith("```")) {
            s = s.substring(3).trim();
            if (s.toLowerCase().startsWith("json")) s = s.substring(4).trim();
            int e = s.lastIndexOf("```");
            if (e >= 0) s = s.substring(0, e).trim();
        }
        JsonArray arr = J.arr(s);
        if (arr == null) {
            int a = s.indexOf('[');
            int b = s.lastIndexOf(']');
            if (a >= 0 && b > a) arr = J.arr(s.substring(a, b + 1));
        }
        if (arr == null) return null;
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (JsonElement e : arr) {
            if (e != null && e.isJsonObject()) out.add(e.getAsJsonObject());
        }
        return out;
    }

    /** tags = 配置的印象标签 + 模型给的标签（去重、逗号分隔）。 */
    private static String tagsFor(Cfg c, JsonObject item) {
        Set<String> set = new LinkedHashSet<String>();
        if (Str.has(c.tag)) set.add(c.tag.trim());
        for (String t : J.strings(item, "tags")) {
            String v = Str.trim(t);
            if (!v.isEmpty()) set.add(v);
        }
        String raw = Str.trim(J.s(item, "tags", ""));
        if (!raw.isEmpty() && !raw.startsWith("[")) {
            for (String t : raw.split("[,，\\s]+")) {
                String v = Str.trim(t);
                if (!v.isEmpty()) set.add(v);
            }
        }
        return Str.join(set, ",");
    }

    private static String tagsOf(JsonObject row) {
        JsonElement e = J.get(row, "tags");
        if (e == null) return "";
        if (e.isJsonArray()) {
            List<String> l = new ArrayList<String>();
            for (JsonElement x : e.getAsJsonArray()) l.add(x.getAsString());
            return Str.join(l, ",");
        }
        return e.getAsString();
    }

    // ================================================================ 节流

    /** 一次闸门判断：{@code wait > 0} = 这轮不许跑；{@code why} = 一行给人看的理由。 */
    private static final class Gate {
        long wait;
        String why = "";
    }

    /**
     * 闸门（全部读 kv，跨重启不重置）：
     * 从没开跑过 → 立刻跑；上一轮成功 → 等 {@code 间隔小时}；上一轮失败 → 只等 {@code 失败重试分钟}。
     * 所以"进程重启"不会把间隔清零，也不会让过期的轮次永远轮不到 —— 过期就补一轮。
     */
    private static Gate gate(Host h, Cfg c) {
        Gate g = new Gate();
        Store s = h.store();
        long now = System.currentTimeMillis();
        long last = num(s.kv(KV_LAST, "0"), 0L);
        long done = num(s.kv(KV_DONE, "0"), 0L);
        if (last <= 0L) {
            g.why = msg(c, "文案-首次开跑", "从没开跑过（kv {key} 为空）→ 立刻跑第一轮", "{key}", KV_LAST);
            return g;
        }
        JsonObject st = J.obj(s.kv(KV_STATUS, ""));
        boolean failed = st != null && !J.b(st, "ok", true);
        long interval = failed ? c.retryMs : c.intervalMs;
        if (interval <= 0L) {
            g.why = msg(c, "文案-不节流", "间隔 <= 0（不节流）→ 立刻跑");
            return g;
        }
        long wait = last + interval - now;
        if (wait > 0L) {
            g.wait = wait;
            g.why = msg(c, "文案-未到间隔日志",
                    "未到间隔：{state}，还需 {wait} 分钟（{window}；上次开跑 {last_at}，上次成功 {done_at}）—— 这一轮不动模型",
                    "{state}", failed ? "上一轮失败，按失败重试分钟退避" : "上一轮成功",
                    "{wait}", String.valueOf(Math.max(1L, wait / 60000L)),
                    "{window}", failed ? "失败重试 " + mins(c.retryMs) + " 分钟" : "间隔 " + hours(c.intervalMs) + " 小时",
                    "{last_at}", when(last),
                    "{done_at}", done > 0L ? when(done) : "（无）");
            return g;
        }
        boolean catchup = done <= 0L || (c.intervalMs > 0L && now - done >= c.intervalMs);
        g.why = catchup
                ? msg(c, "文案-补一轮", "到点补一轮：距上次成功 {done_ago} 小时 ≥ 间隔 {interval} 小时（上次成功 {done_at}）",
                        "{done_ago}", hours(now - done), "{interval}", hours(c.intervalMs),
                        "{done_at}", done > 0L ? when(done) : "（无）")
                : msg(c, "文案-到点开跑", "到点开跑：距上次开跑 {last_ago} 小时 ≥ 间隔 {interval} 小时",
                        "{last_ago}", hours(now - last), "{interval}", hours(c.intervalMs));
        return g;
    }

    // ================================================================ 配置

    /** {@code prompt.md} 是唯一配置源：读不到 / 缺项 = 那一项不生效（技能里没有写死的策略）。 */
    private static Cfg load(Host h) {
        Cfg c = new Cfg();
        String text = "";
        try {
            File dir = h.skillDir();
            if (dir == null || !dir.isDirectory()) dir = new File(h.conf().skillsDir(), NAME);
            text = Fs.read(new File(dir, "prompt.md"), "");
        } catch (Throwable ignored) {
        }
        if (Str.blank(text)) return c;
        Map<String, String> kv = pairs(section(text, "配置"));
        c.texts = pairs(section(text, "文案"));
        c.tpl = section(text, "提取提示词");
        c.enabled = truthy(kv.get("启用"));
        c.intervalMs = (long) (dec(kv.get("间隔小时"), 12.0D) * 3600_000.0D);
        c.retryMs = (long) (dec(kv.get("失败重试分钟"), 10.0D) * 60_000.0D);
        c.batch = atLeast(num(kv.get("每轮人数"), 3L), 1, 20);
        c.perPerson = atLeast(num(kv.get("每人取样条数"), 40L), 1, 500);
        c.minMsgs = atLeast(num(kv.get("最少消息条数"), 6L), 1, 1000);
        c.minImportance = atLeast(num(kv.get("最低重要度"), 5L), 0, 10);
        c.cands = atLeast(num(kv.get("挑人候选"), 200L), 1, 5000);
        c.readLimit = atLeast(num(kv.get("读取条数"), 20L), 1, 200);
        c.listLimit = atLeast(num(kv.get("列出人数"), 20L), 1, 200);
        c.maxChars = atLeast(num(kv.get("每条最大字数"), 200L), 20, 2000);
        c.dupMin = atLeast(num(kv.get("查重最短长度"), 8L), 2, 1000);
        double t = dec(kv.get("温度"), 0.3D);
        c.temperature = t < 0 ? 0.3D : t;
        c.tag = Str.trim(kv.get("印象标签"));
        if (c.tag.isEmpty()) c.tag = TAG_DEF;
        c.lib = libOf(kv.get("目标库"), h);
        c.roles = splitList(kv.get("对话角色"));
        c.labels = splitList(kv.get("维度标签"));
        if (c.labels.isEmpty()) {
            for (String x : LABEL_DEF) c.labels.add(x);
        }
        c.lineGroup = def(kv.get("样本行群聊"), "{time} [{group}] {name}: {text}");
        c.lineDialog = def(kv.get("样本行对话"), "{time} [{role}] {text}");
        return c;
    }

    /**
     * 目标库只认"有 scope/scope_id/importance 列"的库（默认 memory）；其它一律退回 memory。
     *
     * <p>库清单问<b>注册表</b>（{@link Host#libs()}）：S2 之后表结构由插件声明、基板里不再有库枚举，
     * 所以"目标库有没有这几列"必须按当前真正注册的声明来判（插件不在 = 查不到 = 退回默认）。</p>
     */
    private static String libOf(String name, Host h) {
        if (Str.blank(name)) return LIB_DEF;
        sair.v4.store.Libs reg = h == null ? null : h.libs();
        LibSpec l = reg == null ? null : reg.get(name);
        if (l == null || !l.hasField("scope") || !l.hasField("scope_id") || !l.hasField("importance")) {
            return LIB_DEF;
        }
        return l.table();
    }

    private static final class Cfg {
        boolean enabled = false;
        long intervalMs = 12L * 3600_000L;
        long retryMs = 10L * 60_000L;
        int batch = 3;
        int perPerson = 40;
        int minMsgs = 6;
        int minImportance = 5;
        int cands = 200;
        int readLimit = 20;
        int listLimit = 20;
        int maxChars = 200;
        int dupMin = 8;
        double temperature = 0.3D;
        String tag = TAG_DEF;
        String lib = LIB_DEF;
        String tpl = "";
        String lineGroup = "{time} [{group}] {name}: {text}";
        String lineDialog = "{time} [{role}] {text}";
        List<String> roles = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        Map<String, String> texts = new LinkedHashMap<String, String>();
    }

    private static final class Who {
        final long qq;
        final String name;

        Who(long qq, String name) {
            this.qq = qq;
            this.name = Str.nz(name);
        }
    }

    private static final class Line {
        final long ts;
        final String text;

        Line(long ts, String text) {
            this.ts = ts;
            this.text = Str.nz(text);
        }
    }

    /** 一个人的一轮结果。 */
    private static final class One {
        long qq;
        String name = "";
        String label = "";
        int sampled;
        int cand;
        int add;
        int upd;
        int dup;
        int low;
        boolean model;
        String skip = "";
        String error = "";
        String raw = "";

        String line(Cfg c) {
            if (!Str.blank(skip)) return skip;
            return msg(c, "文案-单条结果",
                    "qq={qq} sampled={sampled} cand={cand} add={add} upd={upd} dup={dup} low={low}",
                    "{qq}", String.valueOf(qq), "{name}", name,
                    "{sampled}", String.valueOf(sampled), "{cand}", String.valueOf(cand),
                    "{add}", String.valueOf(add), "{upd}", String.valueOf(upd),
                    "{dup}", String.valueOf(dup), "{low}", String.valueOf(low));
        }
    }

    // ================================================================ 文案 / 模板

    /** 取 {@code ## 文案} 里的模板渲染；模板缺失时用调用方给的结构性兜底（技能里没有写死的用户文案）。 */
    private static String msg(Cfg c, String key, String fallback, String... kv) {
        String tpl = c.texts == null ? null : c.texts.get(key);
        return fill(Str.has(tpl) ? tpl : fallback, kv);
    }

    /** {@code {k}} → 值。 */
    private static String fill(String tpl, String... kv) {
        String s = Str.nz(tpl);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] == null) continue;
            s = s.replace(kv[i], Str.nz(kv[i + 1]));
        }
        return s;
    }

    /** 取 md 里 {@code ## 名} 到下一个 {@code ## } 之间的正文。 */
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

    /** {@code 键: 值} 一行一条 → map（跳过注释与没有冒号的行）。 */
    private static Map<String, String> pairs(String body) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (Str.blank(body)) return m;
        for (String raw : body.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf(':');
            if (i < 0) i = line.indexOf('：');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim();
            String v = line.substring(i + 1).trim();
            if (!k.isEmpty()) m.put(k, v);
        }
        return m;
    }

    // ================================================================ 小工具

    /**
     * {@code run} 这条路的<b>归属硬判</b>：{@code run} 是"把候选池里的人批量蒸一遍"，
     * 按候选池给<b>别人</b>的 {@code scope_id} 写 {@code memory} 行，所以非主人（{@code ALLUSER}）
     * 一律拒；{@code MASTER} 与她自己的自主行为（{@code SYSTEM}，即 {@code on_timer} 那条路）照常。
     *
     * <p><b>为什么还要归属硬判</b>：{@code memory} 是 C 类资源，C 类对非主人只有 {@code R}
     * ⇒ {@code h.needDb(c.lib,'W')}（{@link #round}）和工具 X 位（工具属 T 类，只判入口）都把非主人写一律拦下；
     * 而"写的是不是别人的数据"仍由这道归属硬判裁定（读自己那一类仍靠它）。
     * 读自己用 {@code get}/{@code list}、蒸自己用 {@code refresh}（那里强制取自己 qq），都不受影响。</p>
     *
     * @return 放行返回 {@code null}；拒返回可直接回给模型的 {@code [权限阻断] …} 原文
     */
    private static String noDistillDeny(Host h) {
        if (!restricted(h)) return null;
        return DENY + "蒸馏/重算是她自己的事，只有主人或她本人（MASTER/SYSTEM）能做。";
    }

    /**
     * 受限主体判定（ACL 之后的新口径）：受限的只有 {@code ALLUSER}（普通用户）。
     * <p>{@code MASTER} / {@code SYSTEM} 照常放行；身份取不到时按受限处理（fail-closed）。</p>
     */
    private static boolean restricted(Host h) {
        try {
            Caller c = h == null ? null : h.caller();
            if (c == null) return true;
            return c.kind() == Caller.Kind.ALLUSER;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean isMaster(Caller c) {
        return c != null && c.master();
    }

    private static void sortDesc(List<JsonObject> rows) {
        java.util.Collections.sort(rows, new java.util.Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject a, JsonObject b) {
                long x = J.l(a, "ts", 0L);
                long y = J.l(b, "ts", 0L);
                if (x != y) return x > y ? -1 : 1;
                return (int) (J.l(b, "id", 0L) - J.l(a, "id", 0L));
            }
        });
    }

    private static String def(String v, String fallback) {
        return Str.has(v) ? v.trim() : fallback;
    }

    private static List<String> splitList(String v) {
        List<String> out = new ArrayList<String>();
        if (Str.blank(v)) return out;
        for (String p : v.split("[,，\\s]+")) {
            String t = p.trim();
            if (!t.isEmpty() && !"all".equalsIgnoreCase(t) && !"全部".equals(t)) out.add(t);
        }
        return out;
    }

    private static long num(String v, long def) {
        try {
            return Long.parseLong(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static int atLeast(long v, int min, int max) {
        long x = v < min ? min : v;
        return (int) (x > max ? max : x);
    }

    private static double dec(String v, double def) {
        try {
            return Double.parseDouble(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean truthy(String v) {
        String s = Str.trim(v).toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "是".equals(s);
    }

    /** {@code 12.0} → {@code 12}，{@code 0.5} → {@code 0.5}（日志里给人看的"小时"）。 */
    private static String hours(long ms) {
        double h = ms / 3600_000.0D;
        if (Math.abs(h - Math.rint(h)) < 0.05D) return String.valueOf((long) Math.rint(h));
        if (h > 0.0D && h < 1.0D) return String.valueOf(Math.round(h * 10000.0D) / 10000.0D);
        return String.valueOf(Math.round(h * 10.0D) / 10.0D);
    }

    /** 毫秒 → 分钟（向上取整到分钟，日志里给人看的"分钟"）。 */
    private static String mins(long ms) {
        long m = ms / 60_000L;
        if (ms % 60_000L != 0L) m++;
        return String.valueOf(Math.max(1L, m));
    }

    /** 时间戳 → {@code MM-dd HH:mm:ss}（本地时区的"人话时间"）。 */
    private static String when(long ms) {
        if (ms <= 0L) return "（无）";
        try {
            return new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(ms));
        } catch (Throwable t) {
            return String.valueOf(ms);
        }
    }

    /** {@code qq(名字), qq(名字)}（日志里给人看的一行"挑中谁"）。 */
    private static String who(List<Who> people) {
        StringBuilder sb = new StringBuilder();
        for (Who w : people) {
            if (sb.length() > 0) sb.append("、");
            sb.append(w.qq);
            if (Str.has(w.name)) sb.append("(").append(Str.cut(Str.oneLine(w.name), 12)).append(")");
        }
        return sb.toString();
    }

    /** 过程行（dim）：能不能在控制台看到取决于 {@code logConsole} 里有没有 {@code skill}。 */
    private static void log(Host h, String message) {
        if (Str.blank(message)) return;
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.dim("[" + NAME + "] " + message);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 决策行（warn）：基板默认 {@code logConsole=tool,model}，技能自己的 {@code dim} 行会被丢掉，
     * 所以"为什么这轮没蒸"必须走 warn —— 否则线上就是一句都看不到的静默失败。
     */
    private static void note(Host h, String message) {
        if (Str.blank(message)) return;
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.warn("[" + NAME + "] " + message);
        } catch (Throwable ignored) {
        }
    }

    /** 每个 tick 都会撞上的决策行（未到间隔/未启用）：本进程第一条必打，之后最多每小时一条，别刷屏。 */
    private static void noteIdle(Host h, String message) {
        long now = System.currentTimeMillis();
        if (lastIdleNote > 0L && now - lastIdleNote < IDLE_NOTE_GAP) return;
        lastIdleNote = now;
        note(h, message);
    }
}
