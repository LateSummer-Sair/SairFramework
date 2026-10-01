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
import sair.v4.store.Store;

/**
 * 经验蒸馏（工具 {@code experience} + {@code on_timer} 钩子）：把最近"有用的对话/群聊片段"蒸馏成
 * <b>可复用的经验</b>，写进六库的 {@code note}（{@code source=<本技能名>}、{@code tags} 含经验标签），
 * 并在满足条件时把一条经验<b>提升成一个真技能</b>（技能自进化：调基板的 {@code skill_write} 写 md + java）。
 *
 * <p>note 库没有 {@code importance} 列（基板六库的 note 只有 title/content/tags/source/extra），
 * 所以重要度、命中次数、是否已提技都放在 {@code extra}（JSON 文本列，Store 自动序列化/解析）里。</p>
 *
 * <p>提技是<b>默认关闭</b>的高风险动作：只有 {@code 自动提技: on}、重要度 ≥ {@code 提技门槛}、
 * 命中次数 ≥ {@code 提技最低命中}、模型标记 {@code skill_candidate=true}、且这一条尚未提技过，
 * 才会把那一条经验按 {@code ## 提技模板} 渲染成 md + java 交给基板的 {@code skill_write}
 * （真正拦人的是 {@link #promote} 里那句 <b>op 判定</b> {@code h.need("skill_write.add")}：
 * 提技 = 调 {@code skill_write} 的 {@code add} 动作写技能源码 ⇒ 按技能管控表要这个 op 的授权；
 * {@code on_timer} 与她自己这条路的主体是 {@code SYSTEM}，恒放行）。
 * 生成的技能只做"查库 + 回话"（读 note 库里的那一条经验），不执行命令、不读写文件。</p>
 *
 * <h3>★ 她自己的标记行（P1-selfsee）+ 外部来源行（能力②/D1）：素材面（dialog + grouplog）整行跳过</h3>
 * <p>基板 P0-1 之后 {@code dialog}/{@code grouplog} 里会多出她<b>自己发出去</b>的那一行「标记行」
 * （{@code content} 形如 {@code [我发的 图片 msg_id=<id>]} / {@code [我发的 msg_id=<id>] <正文>}，
 * {@code extra.self=true}）。它是<b>机器写的标记行 —— 不是群友发言，也不是给模型看的素材</b>：
 * 本技能唯一的素材面 {@link #lines}（吃 {@link #dialogSample} 的对话行 + {@code grouplog} 的群聊行）
 * 对标记行做 <b>① 整行跳过</b> —— 否则模型会拿机器外壳去蒸，蒸出「回复 {@code [我发的 msg_id=…]}」
 * 这种垃圾经验写进共享 {@code note} 库（还会挤掉真素材的名额、并让"内容去重"多留一条重复行）。
 * 判据只有 {@link #isSelfMark} 一处。</p>
 * <p>基板 D1（2026-09-28）之后同一个 {@code selfId} 下<b>另一个机器人服务</b>发出去的消息
 * （{@code extra.foreign=true}）也会出现在这两张表里：它<b>同样</b>不是群友发言、也不是人的话 ⇒
 * 处置<b>逐字相同</b>（同样整行跳过）。两个判据的合流处只有 {@link #notHuman} 一处，
 * 素材面的两处调用都问它。</p>
 * <p>为什么不担心"把她说过的话抹掉"：私聊里她自己的正文另有基板写的 {@code role=assistant} 那一行
 * （出站本来就落库），群聊那一行则仍留在 {@code grouplog} 与她自己的窗口里 —— 事实面一行没少，
 * 去掉的只是"喂给蒸馏模型的素材"这一层。</p>
 *
 * <p>节流：kv {@code distill:experience:last}（上次开跑的时间戳）。上一轮成功 → {@code 间隔小时}；
 * 上一轮失败 → 只等 {@code 失败重试分钟}。未到间隔直接 return，绝不每 20 秒打一次模型。</p>
 *
 * <p><b>日志闸门（2026-09-27，甲方反馈"日志比较频繁"）</b>：未到间隔时那一行
 * 「还没到间隔（还需 …），本轮不动模型」原来是<b>每个 tick 都打</b>（tick 默认 20 秒 ⇒ 实测
 * 约 176 行/小时、一天四千多行）。它证明的是"没动模型"，本身没有信息量，于是一天里同一句话说四千遍。
 * 现在照姐妹技能 {@code 印象蒸馏} 的同一道闸门：
 * <b>本进程第一条必打，之后最多每小时一条</b>（按小时桶去重）。判定与行为<b>一个字没改</b> ——
 * 只是同一句话不再重复四千遍；真的开跑那一轮、失败、提技等行照旧每次都打。</p>
 */
public class ExperienceSkill implements Skill, Hooked {

    /**
     * 「还没到间隔」那行的去重闸门：当前小时桶（{@code 本进程第一条必打}）。
     * <p>{@link Long#MIN_VALUE} = 本进程还没打过；换小时才允许再打一次。</p>
     */
    private static volatile long waitLoggedBucket = Long.MIN_VALUE;

    /** 技能名（= 文件夹名 = md 文件名），也是 note 行 source 列的值。 */
    private static final String NAME = "经验蒸馏";
    /** 节流闸门：上次开跑的毫秒时间戳。 */
    private static final String KV_LAST = "distill:experience:last";
    /** 上一轮状态（JSON）：ts/op/ok/ran/error/本轮明细。 */
    private static final String KV_STATUS = "distill:experience:status";
    /** 经验落在 note 库（有 title 的知识库）。 */
    private static final String LIB = "note";
    /** {@code 经验标签} 缺省值（prompt.md 缺项时的结构性兜底）。 */
    private static final String TAG_DEF = "experience";
    /** 技能与工具名的结构性兜底模板（真正生效的是 prompt.md 里的同名配置）。 */
    private static final String NAME_TPL_DEF = "exp_{slug}";
    private static final String TOOL_TPL_DEF = "exp_{slug}";
    /**
     * 基板技能写入口（工具名；{@code add} 是它的动作名 ⇒ 对应的 op 是 {@code skill_write.add}）。
     * <p>它是<b>独立于本技能</b>的另一把工具：调用前由本技能先判一次 {@code h.need("skill_write.add")}
     * （见 {@link #promote}），{@code h.call(...)} 自己还会走一遍工具入口的 op 闸门。</p>
     */
    private static final String TOOL_SKILL_WRITE = "skill_write";
    /**
     * 归属阻断的固定前缀（探针按它断言）。<b>必须由代码保证</b>，不走 prompt.md。
     */
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
     * <b>它是机器写的标记行 —— 不是群友发言，也不是给模型看的素材。</b></p>
     *
     * <p>两条判据<b>都要</b>（只读消费面可能把 {@code extra} 丢掉）：</p>
     * <ol>
     *   <li>{@code extra.self == true}：{@code extra} 是 TEXT 列的 JSON 串（{@code Db.decode}
     *       让出参是对象；解析失败/缺失就当<b>没有它</b>）；</li>
     *   <li>兜底：{@code content} <b>逐字</b>以 {@link #SELF_MARK_PREFIX} 开头（含尾部空格）。</li>
     * </ol>
     *
     * <p><b>判据不许走样</b>：① 只能用 {@code startsWith} —— 不许 {@code contains("[我发的")}，
     * 也不许退化成「以 {@code [} 开头」：{@code [图片 …]}/{@code [未知段 …]} 那些是
     * <b>基板事实标签</b>，本判据一律 {@code false}（本任务不许过滤它们）；
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
     * <p>它在本技能里的处置与标记行<b>逐字相同</b>：不是群友发言、不是这个人说的话、不许进素材
     * （连"某个人的话"都不是）。判据只看 {@code extra.foreign}（内容伪造不出这个键）。</p>
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

    /** 这一行是不是"<b>不是人的话</b>"（两个判据的唯一合流处：她的标记行 / 外部来源行）。 */
    static boolean notHuman(JsonObject row) {
        return isSelfMark(row) || isForeign(row);
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
     * 去掉"不是人的话"的行（{@link #notHuman}：她的标记行 + 外部来源行）。
     * <p>一个都不命中时<b>原样返回入参</b>（同一引用、同一顺序）—— 这就是「没有这类行 ⇒ 与改动前逐字等价」。</p>
     */
    static List<JsonObject> withoutSelfMarks(List<JsonObject> rows) {
        if (rows == null || rows.isEmpty()) return rows;
        boolean has = false;
        for (int i = 0; i < rows.size(); i++) {
            if (notHuman(rows.get(i))) {
                has = true;
                break;
            }
        }
        if (!has) return rows;
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            if (!notHuman(r)) out.add(r);
        }
        return out;
    }

    // ================================================================ 工具入口

    @Override
    public Object call(Args a, Host h) {
        if (h == null || h.store() == null) return "[" + NAME + "] 拿不到六库接口，技能不可用。";
        try {
            String op = Str.trim(a.str("op")).toLowerCase();
            if (op.isEmpty()) op = "list";
            // op 判定：五个动作各判自己的 op（技能管控表按这些名字放行/封禁），判在动作分支的开头。
            // 判完 op 之后，写 note 的那些分支仍要过归属硬判（noteWriteGuard）—— 那是数据归属，不是权限。
            if ("list".equals(op)) {
                String deny = h.need("experience.list");
                if (deny != null) return deny;
                return list(a, h);
            }
            if ("search".equals(op)) {
                String deny = h.need("experience.search");
                if (deny != null) return deny;
                return search(a, h);
            }
            if ("add".equals(op)) {
                String deny = h.need("experience.add");
                if (deny != null) return deny;
                return add(a, h);
            }
            if ("forget".equals(op)) {
                String deny = h.need("experience.forget");
                if (deny != null) return deny;
                return forget(a, h);
            }
            if ("run".equals(op)) {
                String deny = h.need("experience.run");
                if (deny != null) return deny;
                return run(a, h);
            }
            return "[" + NAME + "] 不支持的 op：" + op + "（只支持 list / search / add / forget / run）";
        } catch (Throwable t) {
            log(h, "工具调用异常：" + t);
            return "[" + NAME + "] 执行异常：" + t;
        }
    }

    /**
     * 声明本技能的全部 op（技能管控表的骨架）：五个动作各一条 ——
     * "允许他查"与"允许他删 / 跑蒸馏"因此能在管控表里分开写。
     */
    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        ops.add("experience", "list", "列最近的经验（可按关键词过滤）");
        ops.add("experience", "search", "按关键词检索经验");
        ops.add("experience", "add", "手工记一条经验（先查重）");
        ops.add("experience", "forget", "按 id 删一条经验");
        ops.add("experience", "run", "立刻跑一轮蒸馏（可能提技）");
    }

    // ================================================================ 钩子入口

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        if (!"on_timer".equals(hook) || h == null) return;
        try {
            Cfg c = load(h);
            if (!c.enabled) return;
            long wait = gate(h, c);
            if (wait > 0) {
                // 日志闸门：本进程第一条必打，之后最多每小时一条（判定与行为不受影响）。
                long bucket = System.currentTimeMillis() / 3600_000L;
                if (waitLoggedBucket == Long.MIN_VALUE || bucket != waitLoggedBucket) {
                    waitLoggedBucket = bucket;
                    log(h, "还没到间隔（还需 " + wait + "ms），本轮不动模型");
                }
                return;
            }
            round(h, c, "timer");
        } catch (Throwable t) {
            log(h, "on_timer 异常：" + t);
        }
    }

    // ================================================================ 工具 op

    /** {@code list}：最近的几条经验（可按关键词过滤）。 */
    private static String list(Args a, Host h) {
        Cfg c = load(h);
        int limit = a.i("limit", c.listLimit);
        if (limit <= 0) limit = c.listLimit;
        String q = Str.trim(a.str("query"));
        List<JsonObject> rows = Str.blank(q)
                ? h.store().list(LIB, J.obj("source", NAME), limit, "ts DESC")
                : filter(h.store().search(LIB, q, limit * 3), limit);
        if (rows.isEmpty()) {
            return Str.blank(q)
                    ? msg(c, "文案-无条目", "no_experience")
                    : msg(c, "文案-没有匹配", "no_match query={query}", "{query}", q);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Str.blank(q)
                ? msg(c, "文案-条目头", "experiences={n}", "{n}", String.valueOf(rows.size()))
                : msg(c, "文案-条目头查", "experiences={n} query={query}",
                        "{n}", String.valueOf(rows.size()), "{query}", q)).append("\n");
        int i = 0;
        for (JsonObject r : rows) {
            i++;
            sb.append(line(c, r, i)).append("\n");
        }
        return sb.toString().trim();
    }

    /** {@code search}：按关键词检索经验（同样只看本技能写的行）。 */
    private static String search(Args a, Host h) {
        Cfg c = load(h);
        String q = Str.trim(a.str("query"));
        if (q.isEmpty()) return a.missing("query");
        int limit = a.i("limit", c.searchLimit);
        if (limit <= 0) limit = c.searchLimit;
        List<JsonObject> rows = filter(h.store().search(LIB, q, limit * 3), limit);
        if (rows.isEmpty()) return msg(c, "文案-没有匹配", "no_match query={query}", "{query}", q);
        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, "文案-条目头查", "experiences={n} query={query}",
                "{n}", String.valueOf(rows.size()), "{query}", q)).append("\n");
        int i = 0;
        for (JsonObject r : rows) {
            i++;
            sb.append(line(c, r, i)).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 并发写闸门：经验的查重是"标题/正文模糊匹配"，所以整库一把锁 ——
     * 两条会话道（或主回合 + on_timer 蒸馏流水线）同时进来时，查重与写库之间不能有缝。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> WRITE_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<String, Object>();

    private static Object writeLock(String key) {
        String k = Str.blank(key) ? LIB : key;
        Object o = WRITE_LOCKS.get(k);
        if (o != null) return o;
        Object fresh = new Object();
        Object prev = WRITE_LOCKS.putIfAbsent(k, fresh);
        return prev == null ? fresh : prev;
    }

    /** {@code add}：手工记一条经验（先查重；重复的只累加命中次数 / 抬高重要度）。 */
    private static String add(Args a, Host h) {
        synchronized (writeLock(LIB)) {
            return addLocked(a, h);
        }
    }

    private static String addLocked(Args a, Host h) {
        Cfg c = load(h);
        // 归属硬判：note 是她的共享知识库（行里只有 source，没有 per-user 归属列）⇒ 非主人一律拒。
        // 覆盖本方法里的 put、命中重复时的 bump()→update、以及提技后的 update（promote）。
        String guard = noteWriteGuard(h);
        if (guard != null) return guard;
        String content = Str.trim(a.str("content"));
        if (content.isEmpty()) return a.missing("content");
        String title = Str.trim(a.str("title"));
        if (title.isEmpty()) title = Str.cut(Str.oneLine(content), 24);
        int imp = a.has("importance") ? atLeast(a.l("importance", c.defImportance), 0, 10) : c.defImportance;
        boolean cand = a.b("skill_candidate", false);
        JsonObject exist = findDup(h.store().list(LIB, J.obj("source", NAME), 500, null), title, content, c);
        One one = new One();
        if (exist != null) {
            long id = J.l(exist, "id", 0L);
            String r = bump(h, c, exist, imp, cand, one);
            return msg(c, "文案-重复", "dup #{id}", "{id}", String.valueOf(id),
                    "{title}", title, "{importance}", String.valueOf(imp)) + (Str.blank(r) ? "" : "\n" + r);
        }
        JsonObject row = new JsonObject();
        row.addProperty("title", title);
        row.addProperty("content", content);
        row.addProperty("tags", tags(c, a.str("tags")));
        row.addProperty("source", NAME);
        JsonObject extra = extra(imp, 1, cand, false, "");
        row.add("extra", extra);
        long id = h.store().put(LIB, row);
        if (id <= 0L) return "[" + NAME + "] 写不进 note 库（六库写入失败）。";
        String tail = "";
        if (eligible(c, imp, cand, 1)) {
            tail = promote(h, c, id, title, content, extra, one);
        }
        return msg(c, "文案-新增", "add #{id} title={title}",
                "{id}", String.valueOf(id), "{title}", title,
                "{importance}", String.valueOf(imp)) + (Str.blank(tail) ? "" : "\n" + tail);
    }

    /** {@code forget}：按 id 删（只能删本技能自己写的行 source=NAME）。 */
    private static String forget(Args a, Host h) {
        Cfg c = load(h);
        long id = a.l("id", 0L);
        if (id <= 0L) return a.missing("id");
        // 归属硬判：note 是共享知识库、行里没有归属列 ⇒ 非主人一律拒。
        // （source==NAME 只表达"是不是本技能写的"，不表达"这是不是你的数据"。）
        String guard = noteWriteGuard(h);
        if (guard != null) return guard;
        JsonObject row = h.store().get(LIB, id);
        if (row == null || !NAME.equals(J.s(row, "source", ""))) {
            return msg(c, "文案-没有这条", "not_found id={id}", "{id}", String.valueOf(id));
        }
        boolean ok = h.store().delete(LIB, id);
        return ok
                ? msg(c, "文案-已忘记", "forgotten #{id}", "{id}", String.valueOf(id),
                        "{title}", J.s(row, "title", ""))
                : "[" + NAME + "] 删除 #" + id + " 失败。";
    }

    /** {@code run}：立刻跑一轮蒸馏（与定时同一道闸门）。 */
    private static String run(Args a, Host h) {
        // 权限口径：调用面已判过 op experience.run（调模型、写 note 都算这一个动作的授权）。
        // 这里只剩"是不是你的数据"——归属硬判：这一路会写 note（蒸馏结果落库），
        // 非主人不能借 "run" 让别人替她写共享知识库。
        // 定时钩子那条路（on_timer）的主体是 SYSTEM（她自己的自主行为）⇒ 不受影响。
        String guard = noteWriteGuard(h);
        if (guard != null) return guard;
        // 蒸馏流水线也做"查重 → 写库"：与手工 add 共用同一把锁，避免两条路同时写进同一条经验
        synchronized (writeLock(LIB)) {
            return runLocked(a, h);
        }
    }

    private static String runLocked(Args a, Host h) {
        Cfg c = load(h);
        if (!c.enabled) return msg(c, "文案-未启用", "disabled");
        long wait = gate(h, c);
        if (wait > 0) return msg(c, "文案-跳过节流", "skip wait={wait}ms", "{wait}", String.valueOf(wait));
        return round(h, c, "run");
    }

    // ================================================================ 一轮蒸馏

    /** 取样 → 问模型 → 查重 → 写库 →（可选）提技。返回可读结果，同时落 kv 状态。 */
    private static String round(Host h, Cfg c, String op) {
        // 这一轮要写 note 库（蒸馏结果落库）+ 读 dialog/grouplog：定时钩子路径的主体是 SYSTEM
        // （她自己的自主行为，技能管控表恒放行），所以她自己的维护照常；被主人叫去跑的人按自己的
        // op 授权判（run 的 op 已在 call 里判过）。
        // 剩下的只有归属："非主人不许写共享知识库"由 noteWriteGuard 归属硬判守住 ——
        // round 是"写 note"的唯一汇集点，这里再判一次 = 兜底（on_timer 那条的主体是 SYSTEM）。
        String guardRound = noteWriteGuard(h);
        if (guardRound != null) return guardRound;
        long now = System.currentTimeMillis();
        One r = new One();
        List<JsonObject> dlg = dialogSample(h.store(), c);
        List<JsonObject> gl = h.store().list("grouplog", null, c.sample, "ts DESC");
        List<Line> lines = lines(c, dlg, gl);
        r.sampled = lines.size();
        if (r.sampled < c.minMsgs) {
            r.skip = msg(c, "文案-样本不足", "skip sampled={sampled} min={min}",
                    "{sampled}", String.valueOf(r.sampled), "{min}", String.valueOf(c.minMsgs));
            return finish(h, c, op, now, r, false);
        }
        if (Str.blank(c.tpl)) {
            r.skip = msg(c, "文案-缺提示词", "skip no_template");
            return finish(h, c, op, now, r, false);
        }
        String body = join(lines);
        String prompt = fill(c.tpl, "{msgs}", body, "{count}", String.valueOf(r.sampled), "{source}", NAME);
        JsonArray msgs = new JsonArray();
        msgs.add(Msg.user(prompt));
        JsonObject opts = new JsonObject();
        opts.addProperty("temperature", c.temperature);
        Res res = h.ai() == null ? null : h.ai().chat(msgs, null, opts);
        if (res == null || !res.ok()) {
            r.error = res == null ? "ai client missing" : Str.nz(res.error);
            r.skip = msg(c, "文案-模型失败", "model_failed error={error}", "{error}", r.error);
            return finish(h, c, op, now, r, true);
        }
        List<JsonObject> items = parseItems(res.text());
        if (items == null) {
            r.error = "parse_failed";
            r.skip = msg(c, "文案-解析失败", "parse_failed not_json_array");
            return finish(h, c, op, now, r, true);
        }
        r.cand = items.size();
        List<JsonObject> exist = h.store().list(LIB, J.obj("source", NAME), 500, null);
        List<String> tails = new ArrayList<String>();
        for (JsonObject it : items) {
            String title = Str.trim(J.s(it, "title", ""));
            String content = Str.trim(J.s(it, "content", ""));
            if (content.isEmpty() && title.isEmpty()) continue;
            if (title.isEmpty()) title = Str.cut(Str.oneLine(content), 24);
            int imp = atLeast(J.l(it, "importance", 0L), 0, 10);
            if (imp < c.minImportance) {
                r.low++;
                continue;
            }
            boolean cand = J.b(it, "skill_candidate", false);
            JsonObject same = findDup(exist, title, content, c);
            if (same != null) {
                String tail = bump(h, c, same, imp, cand, r);
                r.dup++;
                if (!Str.blank(tail)) tails.add(tail);
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("title", title);
            row.addProperty("content", content);
            row.addProperty("tags", tags(c, tagsOf(it)));
            row.addProperty("source", NAME);
            JsonObject extra = extra(imp, 1, cand, false, "");
            row.add("extra", extra);
            long id = h.store().put(LIB, row);
            if (id <= 0L) {
                r.error = "store put failed";
                continue;
            }
            row.addProperty("id", id);
            exist.add(row);
            r.add++;
            if (eligible(c, imp, cand, 1)) {
                String tail = promote(h, c, id, title, content, extra, r);
                if (!Str.blank(tail)) tails.add(tail);
            }
        }
        r.line = msg(c, "文案-单条结果",
                "sampled={sampled} cand={cand} add={add} upd={upd} dup={dup} low={low} promoted={promoted}",
                "{sampled}", String.valueOf(r.sampled), "{cand}", String.valueOf(r.cand),
                "{add}", String.valueOf(r.add), "{upd}", String.valueOf(r.upd),
                "{dup}", String.valueOf(r.dup), "{low}", String.valueOf(r.low),
                "{promoted}", String.valueOf(r.promoted));
        for (String t : tails) r.line = r.line + "\n" + t;
        return finish(h, c, op, now, r, true);
    }

    /** 收尾：状态落 kv；只有真的调用过模型才推进节流时间戳。 */
    private static String finish(Host h, Cfg c, String op, long now, One r, boolean modelCalled) {
        if (modelCalled) h.store().kvSet(KV_LAST, String.valueOf(now));
        JsonObject st = new JsonObject();
        st.addProperty("ts", now);
        st.addProperty("op", op);
        st.addProperty("ok", Str.blank(r.error));
        st.addProperty("model", modelCalled);
        st.addProperty("sampled", r.sampled);
        st.addProperty("cand", r.cand);
        st.addProperty("add", r.add);
        st.addProperty("upd", r.upd);
        st.addProperty("dup", r.dup);
        st.addProperty("low", r.low);
        st.addProperty("promoted", r.promoted);
        if (!Str.blank(r.error)) st.addProperty("error", r.error);
        if (!Str.blank(r.skip)) st.addProperty("skip", r.skip);
        if (!Str.blank(r.promoteNote)) st.addProperty("promote", r.promoteNote);
        st.addProperty("last", modelCalled ? now : num(h.store().kv(KV_LAST, "0"), 0L));
        try {
            h.store().kvSet(KV_STATUS, J.json(st));
        } catch (Throwable ignored) {
        }
        String text = Str.blank(r.skip) ? Str.trim(r.line) : r.skip;
        log(h, text);
        return text;
    }

    // ================================================================ 查重 / 命中

    /**
     * 查重：标题归一化后相等，或正文归一化后相等 / 一方包含另一方（短的那边 ≥ 查重最短长度）；
     * 都不中时再按基板 {@code kit/Fingerprint} 的中文 2-gram Jaccard 判<b>近似</b>
     * （标题与正文各判一次，阈值 {@code 相似阈值}）—— 同一件事换个说法写下来时不会各堆一份。
     * 命中就只更新重要度与命中次数，绝不重复堆。
     */
    private static JsonObject findDup(List<JsonObject> exist, String title, String content, Cfg c) {
        String t = norm(title);
        String b = norm(content);
        java.util.Set<String> ft = sair.v4.kit.Fingerprint.bigram(title);
        java.util.Set<String> fb = sair.v4.kit.Fingerprint.bigram(content);
        boolean useT = sair.v4.kit.Fingerprint.enough(ft, c.dupMinTerms);
        boolean useB = sair.v4.kit.Fingerprint.enough(fb, c.dupMinTerms);
        JsonObject near = null;
        double best = 0.0D;
        for (JsonObject r : exist) {
            String rt = norm(J.s(r, "title", ""));
            String rb = norm(J.s(r, "content", ""));
            if (!t.isEmpty() && t.equals(rt)) return r;
            if (b.isEmpty()) continue;
            if (b.equals(rb)) return r;
            String sh = b.length() <= rb.length() ? b : rb;
            String lo = b.length() <= rb.length() ? rb : b;
            if (!sh.isEmpty() && sh.length() >= c.dupMin && lo.contains(sh)) return r;
            // 近似层（V3 TextFingerprint 的等价物）：标题优先，其次正文
            double score = 0.0D;
            if (useT) {
                java.util.Set<String> rtg = sair.v4.kit.Fingerprint.bigram(J.s(r, "title", ""));
                if (sair.v4.kit.Fingerprint.enough(rtg, c.dupMinTerms)) {
                    score = sair.v4.kit.Fingerprint.jaccard(ft, rtg);
                }
            }
            if (score < c.dupSim && useB) {
                java.util.Set<String> rbg = sair.v4.kit.Fingerprint.bigram(J.s(r, "content", ""));
                if (sair.v4.kit.Fingerprint.enough(rbg, c.dupMinTerms)) {
                    double s2 = sair.v4.kit.Fingerprint.jaccard(fb, rbg);
                    if (s2 > score) score = s2;
                }
            }
            if (score >= c.dupSim && score > best) {
                best = score;
                near = r;
            }
        }
        return near;
    }

    /** 命中已有条目：hits+1（写进 extra），重要度更高就抬高；顺手记一下 skill_candidate。 */
    private static String bump(Host h, Cfg c, JsonObject row, int imp, boolean cand, One r) {
        long id = J.l(row, "id", 0L);
        JsonObject ex = J.sub(row, "extra");
        if (ex == null) ex = new JsonObject();
        int hits = J.i(ex, "hits", 0) + 1;
        int old = J.i(ex, "importance", 0);
        boolean wasCand = J.b(ex, "skill_candidate", false);
        boolean promoted = J.b(ex, "promoted", false);
        int best = Math.max(old, imp);
        if (best > old && r != null) r.upd++;
        JsonObject patch = extra(best, hits, wasCand || cand, promoted, J.s(ex, "skill", ""));
        if (!h.store().update(LIB, id, J.obj("extra", patch))) return "";
        row.add("extra", patch);
        if (promoted) return "";
        if (eligible(c, best, wasCand || cand, hits)) {
            return promote(h, c, id, J.s(row, "title", ""), J.s(row, "content", ""), patch, r);
        }
        return "";
    }

    /**
     * 提技资格：配置开关开着 + 模型标了 skill_candidate + 重要度 ≥ 提技门槛 + 命中次数 ≥ 提技最低命中。
     * 四个条件缺一不可；真正写技能前还会再过一次 op 判定（{@code skill_write.add}，见 {@link #promote}）。
     */
    private static boolean eligible(Cfg c, int importance, boolean cand, int hits) {
        return c.autoPromote && cand && importance >= c.promoteMin && hits >= c.promoteHits;
    }

    /** extra 行的结构（重要度/命中数/是否候选/是否已提技/提技后的技能名）。 */
    private static JsonObject extra(int importance, int hits, boolean cand, boolean promoted, String skill) {
        JsonObject o = new JsonObject();
        o.addProperty("importance", importance);
        o.addProperty("hits", hits);
        o.addProperty("skill_candidate", cand);
        o.addProperty("promoted", promoted);
        if (Str.has(skill)) o.addProperty("skill", skill);
        return o;
    }

    private static String tags(Cfg c, String raw) {
        Set<String> set = new LinkedHashSet<String>();
        if (Str.has(c.tag)) set.add(c.tag.trim());
        if (Str.has(raw)) {
            String s = raw.trim();
            if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
            for (String p : s.split("[,，\"\\[\\]]+")) {
                String v = Str.trim(p);
                if (!v.isEmpty()) set.add(v);
            }
        }
        return Str.join(set, ",");
    }

    private static String tagsOf(JsonObject item) {
        JsonElement e = J.get(item, "tags");
        if (e == null) return "";
        if (e.isJsonArray()) {
            List<String> l = new ArrayList<String>();
            for (JsonElement x : e.getAsJsonArray()) l.add(x.getAsString());
            return Str.join(l, ",");
        }
        return e.getAsString();
    }

    private static String tagsOfRow(JsonObject row) {
        JsonElement e = J.get(row, "tags");
        if (e == null) return "";
        if (e.isJsonArray()) {
            List<String> l = new ArrayList<String>();
            for (JsonElement x : e.getAsJsonArray()) l.add(x.getAsString());
            return Str.join(l, ",");
        }
        return e.getAsString();
    }

    // ================================================================ 提技（技能自进化）

    /**
     * 把一条经验提升成一个真技能：按 {@code ## 提技模板} 渲染 md + java，交给基板的 {@code skill_write}。
     * 三道闸门：配置开关、<b>op 授权（{@code skill_write.add}）</b>、以及"写完之后注册表里真的多了这个工具"才算成功。
     *
     * <p>提技的本质是<b>调 {@code skill_write} 往技能目录里写一个新技能（md + java）</b>——
     * 所以要 {@code skill_write.add} 这个 op 的授权；她自己在 {@code on_timer} 里跑时主体是 {@code SYSTEM}
     * （恒放行），主人也不受技能管控表影响，其余身份按账本里那几行判（没写 = 未授权 = 做不到）。</p>
     */
    private static String promote(Host h, Cfg c, long id, String title, String content, JsonObject extra, One r) {
        if (!Str.has(c.mdTpl) || !Str.has(c.javaTpl)) {
            String why = "no_template";
            if (r != null) r.promoteNote = why;
            return msg(c, "文案-提技失败", "promote_failed reason={reason}", "{reason}", why);
        }
        String deny = h.need("skill_write.add");
        if (deny != null) {
            log(h, "提技被技能管控表拦下：" + deny);
            if (r != null) r.promoteNote = Str.cut(Str.oneLine(deny), 200);
            return msg(c, "文案-提技失败", "promote_failed reason={reason}", "{reason}", deny);
        }
        String slug = slug(title, id);
        String tool = toolName(h, c, slug, id);
        String name = Str.safeName(fill(c.nameTpl, "{slug}", slug));
        if (Str.blank(name)) name = fill(NAME_TPL_DEF, "{slug}", slug);
        try {
            if (new File(h.conf().skillsDir(), name).exists()) name = Str.safeName(name + "_" + id);
        } catch (Throwable ignored) {
        }
        String md = fill(c.mdTpl,
                "{name}", name,
                "{tool}", tool,
                "{id}", String.valueOf(id),
                "{title}", Str.oneLine(title),
                "{tags}", tags(c, ""),
                "{content}", content);
        String java = fill(c.javaTpl, "{id}", String.valueOf(id), "{name}", name, "{tool}", tool);
        String written = h.call(TOOL_SKILL_WRITE, J.obj("op", "add", "name", name, "md", md, "java", java));
        boolean loaded = false;
        try {
            loaded = h.tools() != null && h.tools().getView(tool) != null;
        } catch (Throwable ignored) {
        }
        if (!loaded) {
            log(h, "提技失败：" + Str.cut(Str.oneLine(written), 200));
            if (r != null) r.promoteNote = Str.cut(Str.oneLine(written), 200);
            return msg(c, "文案-提技失败", "promote_failed reason={reason}",
                    "{reason}", Str.cut(Str.oneLine(written), 200));
        }
        JsonObject patch = extra(J.i(extra, "importance", 0), J.i(extra, "hits", 1), true, true, name);
        try {
            if (h.store().update(LIB, id, J.obj("extra", patch))) {
                extra.addProperty("promoted", true);
                extra.addProperty("skill", name);
            }
        } catch (Throwable ignored) {
        }
        if (r != null) r.promoted++;
        log(h, "已提技：经验 #" + id + " → 技能 " + name + "（工具 " + tool + "）");
        return msg(c, "文案-提技成功", "promoted skill={skill} tool={tool}",
                "{skill}", name, "{tool}", tool, "{id}", String.valueOf(id), "{title}", title);
    }

    /** 工具名必须 ASCII 安全（function calling 的 name 限制），所以 slug 只留字母数字，撞名再补 id。 */
    private static String toolName(Host h, Cfg c, String slug, long id) {
        String ascii = slug.replaceAll("[^0-9A-Za-z]", "").toLowerCase();
        if (ascii.isEmpty()) ascii = "n" + id;
        if (ascii.length() > 40) ascii = ascii.substring(0, 40);
        String tool = fill(c.toolTpl, "{slug}", ascii, "{id}", String.valueOf(id));
        tool = tool.replaceAll("[^0-9A-Za-z_]", "_");
        if (Str.blank(tool) || "_".equals(tool)) tool = "exp_" + ascii;
        try {
            if (h.tools() != null && h.tools().getView(tool) != null) tool = tool + "_" + id;
        } catch (Throwable ignored) {
        }
        return tool;
    }

    private static String slug(String title, long id) {
        String s = Str.safeName(title == null ? "" : title).replaceAll("\\s+", "");
        if (s.length() > 24) s = s.substring(0, 24);
        return s.isEmpty() ? ("n" + id) : s;
    }

    // ================================================================ 取样

    private static List<JsonObject> dialogSample(Store s, Cfg c) {
        List<JsonObject> rows = s.recentDialog("", c.sample);
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (c.roles.isEmpty()) return rows;
        for (JsonObject r : rows) {
            String role = Str.trim(J.s(r, "role", ""));
            if (c.roles.contains(role)) out.add(r);
        }
        return out;
    }

    /** 合成素材行（dialog 的"成功回合" + grouplog 的问题/结论片段），按时间升序、内容去重。 */
    private static List<Line> lines(Cfg c, List<JsonObject> dlg, List<JsonObject> gl) {
        List<Line> out = new ArrayList<Line>();
        Set<String> seen = new LinkedHashSet<String>();
        SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm");
        for (JsonObject row : dlg) {
            if (notHuman(row)) continue;              // ★ P1+能力②：标记行 / 外部来源行都不进素材（① 整行跳过）
            String text = Str.cut(Str.oneLine(J.s(row, "content", "")), c.maxChars);
            if (Str.blank(text)) continue;
            String key = norm(text);
            if (!key.isEmpty() && !seen.add(key)) continue;
            long ts = J.l(row, "ts", 0L);
            out.add(new Line(ts, fill(c.lineDialog,
                    "{time}", ts > 0 ? df.format(new Date(ts)) : "-",
                    "{role}", Str.nz(J.s(row, "role", "")),
                    "{session}", Str.nz(J.s(row, "session", "")),
                    "{text}", text)));
        }
        for (JsonObject row : gl) {
            if (notHuman(row)) continue;              // ★ P1+能力②：标记行 / 外部来源行都不进素材（① 整行跳过）
            String text = Str.cut(Str.oneLine(J.s(row, "content", "")), c.maxChars);
            if (Str.blank(text)) continue;
            String key = norm(text);
            if (!key.isEmpty() && !seen.add(key)) continue;
            long ts = J.l(row, "ts", 0L);
            out.add(new Line(ts, fill(c.lineGroup,
                    "{time}", ts > 0 ? df.format(new Date(ts)) : "-",
                    "{group}", String.valueOf(J.l(row, "group_id", 0L)),
                    "{qq}", String.valueOf(J.l(row, "user_id", 0L)),
                    "{name}", Str.nz(J.s(row, "nickname", "")),
                    "{text}", text)));
        }
        java.util.Collections.sort(out, new java.util.Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return a.ts < b.ts ? -1 : (a.ts > b.ts ? 1 : 0);
            }
        });
        return out;
    }

    private static String join(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) sb.append(l.text).append("\n");
        return sb.toString().trim();
    }

    private static List<JsonObject> filter(List<JsonObject> rows, int limit) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (JsonObject r : rows) {
            if (!NAME.equals(J.s(r, "source", ""))) continue;
            out.add(r);
            if (out.size() >= limit) break;
        }
        return out;
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

    // ================================================================ 展示 / 节流

    private static String line(Cfg c, JsonObject row, int i) {
        JsonObject ex = J.sub(row, "extra");
        boolean promoted = ex != null && J.b(ex, "promoted", false);
        String mark = "";
        if (promoted && Str.has(c.mark)) {
            mark = fill(c.mark, "{skill}", ex == null ? "" : J.s(ex, "skill", ""));
        }
        return msg(c, "文案-条目行", "{i}. #{id} [{importance}] {title} hits={hits} tags={tags}{mark}",
                "{i}", String.valueOf(i),
                "{id}", String.valueOf(J.l(row, "id", 0L)),
                "{importance}", String.valueOf(ex == null ? 0 : J.i(ex, "importance", 0)),
                "{hits}", String.valueOf(ex == null ? 0 : J.i(ex, "hits", 0)),
                "{title}", Str.nz(J.s(row, "title", "")),
                "{tags}", tagsOfRow(row),
                "{mark}", mark);
    }

    /** 返回还需要等多少毫秒（0 = 现在就能跑）。 */
    private static long gate(Host h, Cfg c) {
        long last = num(h.store().kv(KV_LAST, "0"), 0L);
        if (last <= 0L) return 0L;
        long interval = c.intervalMs;
        JsonObject st = J.obj(h.store().kv(KV_STATUS, ""));
        if (st != null && !J.b(st, "ok", true)) interval = c.retryMs;
        if (interval <= 0L) return 0L;
        long wait = last + interval - System.currentTimeMillis();
        return wait > 0L ? wait : 0L;
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
        String tpl = section(text, "提技模板");
        c.mdTpl = fence(tpl, "md");
        c.javaTpl = fence(tpl, "java");
        c.enabled = truthy(kv.get("启用"));
        c.intervalMs = (long) (dec(kv.get("间隔小时"), 12.0D) * 3600_000.0D);
        c.retryMs = (long) (dec(kv.get("失败重试分钟"), 10.0D) * 60_000.0D);
        c.sample = atLeast(num(kv.get("取样条数"), 40L), 1, 500);
        c.minMsgs = atLeast(num(kv.get("最少条数"), 4L), 1, 1000);
        c.minImportance = atLeast(num(kv.get("最低重要度"), 5L), 0, 10);
        c.defImportance = atLeast(num(kv.get("默认重要度"), 5L), 0, 10);
        c.promoteMin = atLeast(num(kv.get("提技门槛"), 8L), 0, 10);
        c.promoteHits = atLeast(num(kv.get("提技最低命中"), 1L), 1, 1000);
        c.listLimit = atLeast(num(kv.get("列表条数"), 15L), 1, 200);
        c.searchLimit = atLeast(num(kv.get("检索条数"), 5L), 1, 200);
        c.maxChars = atLeast(num(kv.get("每条最大字数"), 200L), 20, 2000);
        c.dupMin = atLeast(num(kv.get("查重最短长度"), 8L), 2, 1000);
        c.dupSim = dec(kv.get("相似阈值"), 0.85D);
        c.dupMinTerms = atLeast(num(kv.get("指纹最短项数"), 4L), 2, 200);
        double t = dec(kv.get("温度"), 0.3D);
        c.temperature = t < 0 ? 0.3D : t;
        c.autoPromote = truthy(kv.get("自动提技"));
        c.tag = Str.trim(kv.get("经验标签"));
        if (c.tag.isEmpty()) c.tag = TAG_DEF;
        c.nameTpl = def(kv.get("提技技能名模板"), NAME_TPL_DEF);
        c.toolTpl = def(kv.get("提技工具名模板"), TOOL_TPL_DEF);
        c.mark = def(kv.get("提技标记"), "");
        c.roles = splitList(kv.get("对话角色"));
        c.lineDialog = def(kv.get("样本行对话"), "{time} [{role}] {session}: {text}");
        c.lineGroup = def(kv.get("样本行群聊"), "{time} [{group}] {name}: {text}");
        return c;
    }

    private static final class Cfg {
        boolean enabled = false;
        boolean autoPromote = false;
        long intervalMs = 12L * 3600_000L;
        long retryMs = 10L * 60_000L;
        int sample = 40;
        int minMsgs = 4;
        int minImportance = 5;
        int defImportance = 5;
        int promoteMin = 8;
        int promoteHits = 1;
        int listLimit = 15;
        int searchLimit = 5;
        int maxChars = 200;
        int dupMin = 8;
        double dupSim = 0.85D;
        int dupMinTerms = 4;
        double temperature = 0.3D;
        String tag = TAG_DEF;
        String nameTpl = NAME_TPL_DEF;
        String toolTpl = TOOL_TPL_DEF;
        String mark = "";
        String tpl = "";
        String mdTpl = "";
        String javaTpl = "";
        String lineDialog = "{time} [{role}] {session}: {text}";
        String lineGroup = "{time} [{group}] {name}: {text}";
        List<String> roles = new ArrayList<String>();
        Map<String, String> texts = new LinkedHashMap<String, String>();
    }

    private static final class Line {
        final long ts;
        final String text;

        Line(long ts, String text) {
            this.ts = ts;
            this.text = Str.nz(text);
        }
    }

    /** 一轮的结果。 */
    private static final class One {
        int sampled;
        int cand;
        int add;
        int upd;
        int dup;
        int low;
        int promoted;
        String line = "";
        String skip = "";
        String error = "";
        String promoteNote = "";
    }

    // ================================================================ 文案 / 模板

    /** 取 {@code ## 文案} 里的模板渲染；模板缺失时用调用方给的结构性兜底。 */
    private static String msg(Cfg c, String key, String fallback, String... kv) {
        String tpl = c.texts == null ? null : c.texts.get(key);
        return fill(Str.has(tpl) ? tpl : fallback, kv);
    }

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

    /** 取正文里 {@code ```tag … ```} 之间的内容（提技模板用）。 */
    private static String fence(String body, String tag) {
        if (Str.blank(body) || Str.blank(tag)) return "";
        String open = "```" + tag.trim();
        String[] lines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean in = false;
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            String t = raw.trim();
            if (!in) {
                if (t.equals(open)) in = true;
                continue;
            }
            if (t.startsWith("```")) return sb.toString().trim();
            sb.append(raw).append('\n');
        }
        return "";
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
     * note 表的<b>归属硬判</b>：{@code note} 是她的共享知识库（行里只有 {@code source}、
     * 没有 per-user 归属列），所以非主人（{@code ALLUSER}）对它的<b>写/改/删一律拒</b>；
     * {@code MASTER} 与她自己的自主行为（{@code SYSTEM}，即 {@code on_timer} 那条路）照常。
     *
     * <p><b>为什么还要归属硬判</b>：op（技能管控表）只回答"这个身份能不能用
     * {@code experience.add} / {@code experience.forget} / {@code experience.run} 这个动作"，
     * 回答不了"这一条是不是他的"（共享库没有归属列）—— 归属问题由这一判回答。</p>
     */
    private static String noteWriteGuard(Host h) {
        if (!restricted(h)) return null;
        return DENY + "知识库（note）是她的共享知识库，没有按人归属的条目 —— 只有主人和她自己能写/改/删。";
    }

    /**
     * 受限主体判定（新口径）：受限的只有 {@code ALLUSER}（普通用户）。
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

    private static String norm(String s) {
        return Str.nz(s).toLowerCase().replaceAll("[^0-9a-z\\u4e00-\\u9fff]+", "");
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

    private static void log(Host h, String message) {
        if (Str.blank(message)) return;
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.dim("[" + NAME + "] " + message);
        } catch (Throwable ignored) {
        }
    }
}
