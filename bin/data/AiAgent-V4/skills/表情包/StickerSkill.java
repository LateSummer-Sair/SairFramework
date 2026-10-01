import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.auth.Caller;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Api;
import sair.v4.qq.InternalFacts;
import sair.v4.qq.MoodMarks;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;
import sair.v4.skill.Skill;
import sair.v4.store.LibSpec;
import sair.v4.store.Libs;
import sair.v4.store.Store;
import sair.v4.term.Redline;

/**
 * 表情包（工具 {@code sticker}，钩子 {@code on_timer}）：语境 → 一张图；另加收藏 / 列表 / 改语境 / 清空。
 *
 * <p>库就是基板六库的 {@code sticker}（{@code id/ts/file/hash/scene/source/uses/extra}），
 * 只用 {@link Host#store()}，不建表、不写 SQL。</p>
 *
 * <p>挑图：{@code id}/{@code hash} 指定 → {@code scene} 精确 → 基板 LIKE 模糊 → 最常用（或随机）。
 * 发出成功才 {@code uses+1}。</p>
 *
 * <p>{@code on_timer} 做 TTL：超龄（{@code 保持天数}）与超上限（{@code 最多张数}）清理，
 * 用 kv {@code sticker:cleanup:last} 节流（{@code 清理间隔小时}）；钩子的判定主体是 SYSTEM
 * （她自己的维护动作），照旧在入口判一次本技能的 op（恒放行）。阈值与一切文案都读同目录的 {@code prompt.md}。</p>
 *
 * <h3>权限：每个动作各判自己的 op + 归属硬判</h3>
 * <p>五个动作（{@code send} / {@code collect} / {@code list} / {@code remark} / {@code clear}）在各自分支开头
 * 判自己的 op（技能管控表：空 = 未授权 = 不可用），判不过就把基板的拒绝原文原样回给模型。</p>
 * <p>库是<b>全局共享</b>的（{@code sticker} 表的行里没有"谁的库"这个键），所以 op 判定之外还有一道
 * <b>归属硬判</b>（它回答的是"这是不是你的数据"，不是"这个动作给不给他用"）：</p>
 * <ul>
 *   <li>{@code collect}（收表情）本意对非主人开放，只是"改<b>别人收的</b>条目"这一支（同 hash 命中别人写的行）拒
 *       —— 按行里的 {@code extra.added_by} 判归属；</li>
 *   <li>{@code remark}（改语境/备注）与 {@code clear}（清空整库）非主人一律拒
 *       （实现上 {@code MASTER} 与她本人 {@code SYSTEM} 都放行，见 {@link #ownerDeny}）。</li>
 * </ul>
 * <p>拒绝文案以 {@code [权限阻断]} 开头（结构前缀，不是 prompt.md 文案）。</p>
 *
 * <h3>QQ 侧自定义表情（{@code cf_list} / {@code cf_add} / {@code cf_del} / {@code cf_desc}）</h3>
 * <p><b>与本地表情包库是两个不同的东西，不并库</b>：库里的一行 = 我们这边的一张图
 * （{@code file}/{@code hash}）；QQ 侧的一条自定义表情 = QQ 账号侧的一条真实资源，它的身份是
 * {@code emoji_id}/{@code res_id}/{@code md5}（由 NapCat 4.18.5+ 的四个动作读写）。桥只有一条：
 * {@code cf_add} 把库里的一行（或直接给的本机文件）推上去，成功后在<b>那一行的 {@code extra.cf}</b>
 * 里记下 QQ 侧的标识 —— <b>不改 {@code sticker} 表结构、不新建表</b>（改结构 = 迁移 = 不允许）。</p>
 * <p>四个动作<b>一律只给主人与她本人</b>（{@link #cfGate}）：它们动的是 QQ 账号侧的真实资源，
 * 读也一并收紧（是否"已添加过"是账号侧的事实）。删除另有<b>二次确认</b>（{@code confirm}）。
 * 每个动作都：① 先判 op；② 再判归属；③ 参数校验不通过回<b>结构化错误</b>（不抛异常穿透）；
 * ④ 成功写一行可观测的事实（{@code h.out()} 的结构化行 + 回给模型的那句话）。</p>
 * <p><b>出站纪律照旧</b>：所有动作都经 {@link Api#call}（{@code h.napcat()} 那份带闸门实例），
 * 所以自我记账头 / SILENT / 标记族 / 事实行 / 红线这几道闸一条都不绕；描述文本另外在本地
 * 先过同一套判据（{@link #cfDescBlock}，判据同源、fail-closed），过了才交给 NapCat。</p>
 */
public class StickerSkill implements Skill, Hooked {

    /** 技能名（= 文件夹名 = md 文件名）。 */
    private static final String NAME = "表情包";
    /** 六库里的表情包库。 */
    private static final String LIB = "sticker";
    /** sticker 行的 source 值（结构性常量，不是文案）。 */
    private static final String SRC = "skill:sticker";
    /** 节流闸门：上次真正跑 TTL 清理的毫秒时间戳。 */
    private static final String KV_LAST = "sticker:cleanup:last";
    /** 上轮清理状态（JSON）。 */
    private static final String KV_STATUS = "sticker:cleanup:status";
    /** 一次批量删除的条数（结构性常量）。 */
    private static final int BATCH = 200;
    /** 清空的兜底轮数（防止"删不掉又列得出来"时死循环）。 */
    private static final int CLEAR_ROUNDS = 200;

    // ---- QQ 侧自定义表情（cf_*；NapCat 4.18.5+） ----

    /** 取自定义表情详情（NapCat 动作名；结构性常量，不是文案）。 */
    private static final String ACT_CF_LIST = "fetch_custom_face_detail";
    /** 添加自定义表情。 */
    private static final String ACT_CF_ADD = "add_custom_face";
    /** 删除自定义表情。 */
    private static final String ACT_CF_DEL = "delete_custom_face";
    /** 改自定义表情描述。 */
    private static final String ACT_CF_DESC = "set_custom_face_desc";
    /**
     * 描述文本的长度上限（<b>我们自己的护栏</b>，不是 NapCat 的契约 —— 上游快照没给上限）。
     * 超了明确拒，别把一长段内部文字推上 QQ 侧。
     */
    private static final int DESC_MAX = 200;
    /** 响应深扫的最大深度（防畸形 / 超大响应把递归吃满）。 */
    private static final int SCAN_DEPTH = 8;
    /** {@code cf_add} 查重时向 NapCat 要的条数（只用于"远端已有"这一判，拿不到就跳过这一判）。 */
    private static final int CF_SCAN_COUNT = 200;

    /** 本技能全部 op（一个动作一条）：装载期声明，技能管控表就是按这些名字放行或封禁的。 */
    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        ops.add("sticker", "send", "按语境发一张表情包");
        ops.add("sticker", "collect", "收藏一张表情包");
        ops.add("sticker", "list", "列表情包库");
        ops.add("sticker", "remark", "改某张的语境 / 备注");
        ops.add("sticker", "clear", "清空表情包库");
        // ★ 四个新 op 必须在这里声明：技能自己的 perms.jsonc 里写了"注册表里没有的动作"会被
        //   装载期按越界忽略（Acl.inScope 的 registryGate）。声明了，那几行才真的生效。
        ops.add("sticker", "cf_list", "取 QQ 自定义表情详情（只给主人）");
        ops.add("sticker", "cf_add", "添加一条 QQ 自定义表情（只给主人）");
        ops.add("sticker", "cf_del", "删除一条 QQ 自定义表情（只给主人）");
        ops.add("sticker", "cf_desc", "改一条 QQ 自定义表情的描述（只给主人）");
    }

    // ================================================================ 库声明（装载期）

    /**
     * 声明 {@code sticker} 表：<b>谁写入、谁召回，谁声明</b>（表结构不再写在基板里）。
     *
     * <p>列/索引名/中文别名逐字对应现网 {@code v4.db}（改结构 = 迁移 = 不允许）。
     * 表情包没有正文列，所以<b>没有 FTS</b>，检索走 {@code LIKE} 回退（{@code file/scene/source}）。</p>
     */
    @Override
    public void declare(Libs libs) {
        libs.register(new LibSpec(LIB, "表情包", NAME)
                .ts()
                .col("file", "TEXT", "''")
                .col("hash", "TEXT", "''")
                .col("scene", "TEXT", "''")
                .col("source", "TEXT", "''")
                .col("uses", "INTEGER", "0")
                .col("extra", "TEXT", "''")
                .like("file", "scene", "source")
                .alias("表情", "表情包", "表情库", "stickers", "face")
                .idx("idx_sticker_scene", "scene")
                .idx("idx_sticker_hash", "hash")
                .idx("idx_sticker_uses", "uses"));
    }

    // ================================================================ 工具入口

    @Override
    public Object call(Args a, Host h) {
        Cfg c = load(h);
        if (h == null) return "[" + NAME + "] host=null";
        try {
            String op = Str.trim(a.str("op")).toLowerCase();
            if ("send".equals(op)) return send(a, h, c);
            if ("collect".equals(op)) return collect(a, h, c);
            if ("list".equals(op)) return list(a, h, c);
            if ("remark".equals(op)) return remark(a, h, c);
            if ("clear".equals(op)) return clear(a, h, c);
            if ("cf_list".equals(op)) return cfList(a, h, c);
            if ("cf_add".equals(op)) return cfAdd(a, h, c);
            if ("cf_del".equals(op)) return cfDel(a, h, c);
            if ("cf_desc".equals(op)) return cfDesc(a, h, c);
            return msg(c, "不支持op", "unsupported_op op={op}", "{op}", op);
        } catch (Throwable t) {
            log(h, "工具调用异常：" + t);
            return msg(c, "异常", "error={error}", "{error}", String.valueOf(t));
        }
    }

    // ================================================================ 钩子入口

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        if (h == null) return;
        // ★ 甲方 2026-09-17 令：收集跟着**看图手**走（"是在看图时的一个顺带"），
        //   **不再主动监听每一条消息** ⇒ 本技能不再处理 on_message：这条分支**整条删掉**
        //   （md 的 hooks 也只剩 on_timer）。原来那句 `if ("on_message".equals(hook)) StickerCollect.capture(...)`
        //   一并删干净；而 StickerCollect.capture 本身也已停用（恒 return）⇒ **双保险**：
        //   md 不声明 + 这里不调用 + 被调方恒 return，三层都断，不留任何"能被误调用"的路。
        if (!"on_timer".equals(hook)) return;                        // 只认 on_timer，其余钩子直接不管
        // 钩子（她自己的维护动作）：判定主体是 SYSTEM ⇒ h.need 恒放行；照旧写这一句，口径统一。
        String deny = h.need("sticker");
        if (deny != null) {
            log(h, "on_timer 跳过（本技能没被授权）：" + deny);
            return;
        }
        // ★ 收集段（在现有 TTL 之前）：消费看图手写在 kv 里的候选行（sticker:cand/）→ 下载 →
        //   内容去重 → 入库 → 磁盘账。这条路**一次视觉调用都没有**（判定来自候选行，不再有视觉审查）。
        try {
            StickerCollect.sweep(h, load(h));
        } catch (Throwable t) {
            log(h, "on_timer 收集异常：" + t);
        }
        try {
            tick(h);
        } catch (Throwable t) {
            log(h, "on_timer 异常：" + t);
        }
    }

    // ================================================================ op：send

    /** 按语境挑一张，发到当前会话。 */
    private static String send(Args a, Host h, Cfg c) {
        // 技能管控表：这个动作自己的 op —— 判不过就把拒绝原文原样回给模型，一张图都不发。
        String deny = h.need("sticker.send");
        if (deny != null) return deny;
        Caller me = h.caller();
        boolean group = me != null && me.toGroup();
        long to = group ? me.groupId() : (me == null || me.isConsole() ? 0L : me.qq());
        if (to <= 0L) return msg(c, "无会话", "no_session");

        if (!h.napcatConnected()) return Api.NOT_CONNECTED;

        Store store = h.store();
        if (store == null) return msg(c, "异常", "error={error}", "{error}", "store=null");
        String scene = Str.trim(a.str("scene"));
        long wantId = a.l("id", 0L);
        String wantHash = Str.trim(a.str("hash"));
        JsonObject row;
        if (wantId <= 0L && !Str.has(wantHash) && !Str.has(scene) && c.moodMatch) {
            // ★ 甲方的发图规则（纯增，可用一行配置关掉）：没给 scene ⇒ 先按"当前心情与现状"
            //   匹配索引内容（情绪事实读的是「情绪」技能写的 emotion 表 —— 跨技能唯一可读的情绪面），
            //   匹配不到就随机（照 V3 的发图机制）。给了 scene/id/hash 的调用完全不变。
            List<String> mood = StickerCollect.moodTokens(h);
            row = StickerCollect.pickByMood(h, store, c, mood);
        } else {
            row = pick(store, c, scene, wantId, wantHash);
        }
        String file = row == null ? "" : Str.trim(J.s(row, "file", ""));
        if (file.isEmpty()) return msg(c, "库空", "sticker_empty");
        String alt = Str.trim(J.s(J.sub(row, "extra"), "url", ""));
        // ★ 绝不把盘符路径交给 NapCat（甲方 2026-09-16 的硬规则：NapCat 在另一台机器上，读不到本机路径）。
        //   基板的"本地路径 → 外链 URL"转换口是 Api.rewriteLocalFile（src\sair\v4\qq\Api.java:505-524）：
        //   中转在跑 → 换成 http://host:port/<token>/<绝对路径>；**中转没跑 → 只告警、原样透传**（裸 C:\…）。
        //   所以这里先问一句中转就绪没有（StickerCollect.relayReady）：能转才交本地件；不能转就用 extra.url；
        //   连 extra.url 都没有就**跳过不发**（宁可这条不发，也不发半截）。
        if (StickerCollect.isLocalPath(file) && !localMediaOk(h, c)) {
            if (!Str.has(alt)) return msg(c, "本机图发不出去", "sticker_local_no_relay file={file}",
                    "{file}", Str.cut(file, 160));
            log(h, "外链中转没就绪 ⇒ 这一张改用 extra.url 发：" + Str.cut(alt, 120));
            file = alt;
        } else if (!new File(file).isFile() && Str.has(alt)) {
            // 本地件被磁盘账清掉了（或本来就是个不存在的路径）⇒ 回退原始链接 —— 与「随机表情包」口径一致。
            file = alt;
        }
        if (Str.blank(file)) return msg(c, "库空", "sticker_empty");

        // ★ 2026-09-24（批 10 头号修复）：这张图是<b>她自己</b>发的，所以走基板的"以她自己身份发媒体"
        //   出口（`Host.sayMedia`）—— 技能 op（上面那一判 `sticker.send`）仍是唯一授权闸门，本口只把
        //   **内层平台动作** `napcat.send_group_msg` 改按 SYSTEM（她本人恒全权）判。
        //   改前：群友触发的回合里她自发发图 ⇒ `[权限阻断] 「napcat.send_group_msg」在技能管控表的黑名单里`
        //   （真机逐字：`[tool] sticker ms=5 chars=49 ok=0`），而主人的回合 ok=1 —— 这个不对称就是本条消的。
        //   本地件的写法一个字没动：仍交给 Api.call 的外链中转改写层（交出去的是 http URL，不是 C:\…）。
        JsonObject r = h.sayMedia(group, to, "image", file);
        if (r == null) {
            // 老宿主 / 本口不承接（不是当前会话、NapCat 面没装配）：按改前那条路原样发，行为逐字一致。
            r = group ? h.napcat().sendGroupImage(to, file) : h.napcat().sendPrivateImage(to, file);
            log(h, "say image via=old ok=" + (Api.ok(r) ? 1 : 0)
                    + " by=" + (me == null ? "?" : me.kind() + ":" + me.qq())
                    + " target=" + (group ? "group:" : "qq:") + to
                    + (Api.ok(r) ? "" : " error=" + err(c, r)));
        }
        if (!Api.ok(r)) return msg(c, "发送失败", "send_fail error={error}", "{error}", err(c, r));

        long id = J.l(row, "id", 0L);
        int uses = J.i(row, "uses", 0) + 1;
        if (id > 0L) store.update(LIB, id, J.obj("uses", uses));
        return msg(c, "发送成功", "sticker_sent id={id} scene={scene} uses={uses}",
                "{id}", String.valueOf(id), "{scene}", J.s(row, "scene", ""), "{uses}", String.valueOf(uses));
    }

    /**
     * 本地件能不能直接交给 NapCat：<b>中转就绪</b>（基板会把它换成外链 URL），
     * 或者主人显式选了 {@code 本机图外发: 本机路径}（同机部署的老口径）。
     *
     * <p>两条都不成立 ⇒ 调用方必须改用 {@code extra.url} 或跳过 —— 绝不交出盘符路径。</p>
     */
    private static boolean localMediaOk(Host h, Cfg c) {
        if (c != null && c.localMedia) return true;
        return StickerCollect.relayReady(h);
    }

    /** 挑一张：id/hash 指定 → scene 精确 → LIKE 模糊 → 最常用（或随机）。 */
    private static JsonObject pick(Store store, Cfg c, String scene, long id, String hash) {        if (id > 0L) {
            JsonObject row = store.get(LIB, id);
            return row == null || Str.blank(J.s(row, "file", "")) ? null : row;
        }
        if (Str.has(hash)) {
            List<JsonObject> hit = usable(byHash(store, hash, c.pool));
            return hit.isEmpty() ? null : hit.get(0);
        }
        List<JsonObject> pool = new ArrayList<JsonObject>();
        if (Str.has(scene)) {
            pool = usable(store.list(LIB, J.obj("scene", scene), c.pool, "uses DESC, ts DESC"));
            if (pool.isEmpty()) pool = usable(store.search(LIB, scene, c.pool));
        }
        if (pool.isEmpty()) pool = usable(store.list(LIB, null, c.pool, "uses DESC, ts DESC"));
        if (pool.isEmpty()) return null;
        if (c.randomPick && pool.size() > 1) {
            int i = (int) (Math.abs(System.nanoTime()) % pool.size());
            return pool.get(i);
        }
        return pool.get(0);
    }

    /**
     * 只留"真能挑出去"的行 —— 判据与计数侧<b>逐字同一份</b>
     * （{@link StickerCollect#usableOne}：{@code file} 非空；本地路径形态的文件必须在盘上；
     * 带 {@code extra.index}（自动收的）必须 {@code safe=true}）。
     *
     * <p>老写法只查 {@code Str.has(J.s(r, "file", ""))} ⇒ 这与 {@code prompt.md} 里
     * 「可用」的定义、与年龄账/状态里的 {@code usable} 计数<b>不是同一件事</b>：
     * 磁盘账把本地文件删掉、又没有 {@code extra.url} 可回退的那一行会被 {@link #pick} 挑中，
     * 交给发送侧的结果只有两种坏结局 —— 回一句"本机图发不出去"，或者把一条**已经不存在的
     * 盘符路径**交给 NapCat（同机部署的口径下）。现在两侧共用 {@code usableOne}，
     * "可用"只有一个定义。</p>
     */
    private static List<JsonObject> usable(List<JsonObject> rows) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (rows == null) return out;
        for (JsonObject r : rows) {
            if (r != null && StickerCollect.usableOne(r)) out.add(r);
        }
        return out;
    }

    // ================================================================ 归属硬判（与 op 判定是两件事）

    /**
     * "只有主人或她本人能做的写动作"的硬判（{@code remark} / {@code clear}）：{@code MASTER} 与
     * {@code SYSTEM}（她自己的自主维护）放行，{@code ALLUSER} 一律拒。
     *
     * <p>库是全局共享的（表里没有归属键）⇒ "谁能不能清库 / 改库里的条目"必须在 op 判定之外显式判身份：
     * op 只回答"这个动作给不给他用"，回答不了"这会动到谁的数据"。</p>
     *
     * <p><b>文案要跟这条判对齐</b>：{@code SYSTEM} 也放行 ⇒ 拒绝文案不能写成"仅主人"，
     * 要如实写成"只能由主人或她本人（MASTER/SYSTEM）做"（写窄了就是假话）。</p>
     *
     * @param deny 非主人时要回的完整拒绝文案（已带 {@code [权限阻断]} 前缀）
     * @return 放行返回 {@code null}
     */
    private static String ownerDeny(Host h, String deny) {
        Caller c = h == null ? null : h.caller();
        if (c == null) return "[权限阻断] 表情包库是主人的：拿不到调用者身份，按拒绝处理。";
        Caller.Kind k = c.kind();
        if (k == Caller.Kind.MASTER || k == Caller.Kind.SYSTEM) return null;
        return deny;
    }

    /**
     * "别碰别人收的条目"的硬判（{@code collect} 命中既有行那一支）：
     * 按行里 {@code extra.added_by} 判归属，非主人只能改自己收的条目。
     *
     * <p>老行可能没有 {@code added_by}（V3 迁移 / 钩子收进来的）：<b>没有归属字段就不算"别人的"</b>，
     * 照旧放行（这一支本来就会把 {@code added_by} 补成本次调用者）。要改成"没有归属字段也拒"
     * 属于改口径，得主人拍板 —— 这里不擅自改 schema、不改写入口以外的东西。</p>
     *
     * @return 放行返回 {@code null}
     */
    private static String otherOwnerDeny(Host h, long myQq, JsonObject row) {
        long addedBy = J.l(J.sub(row, "extra"), "added_by", 0L);
        if (addedBy <= 0L || addedBy == myQq) return null;          // 没有归属字段 / 就是自己收的
        Caller c = h == null ? null : h.caller();
        if (c == null) return "[权限阻断] 这张表情包是别人收的：拿不到调用者身份，按拒绝处理。";
        Caller.Kind k = c.kind();
        if (k == Caller.Kind.MASTER || k == Caller.Kind.SYSTEM) return null;
        return "[权限阻断] 这张表情包是别人收的（#" + J.l(row, "id", 0L) + "，added_by=" + addedBy
                + "）：非主人只能改自己收的条目。";
    }

    // ================================================================ op：collect

    /** 收藏一张：hash 相同就只更新语境/备注（不重复堆）。 */
    private static String collect(Args a, Host h, Cfg c) {
        // 技能管控表：这个动作自己的 op —— 判不过就不写库。
        // "非主人也允许 collect"这个口径现在就写在管控表里（给 collect 一行 Run），不需要别的特例；
        // 能不能改某一条，由下面按 extra.added_by 的归属硬判回答，不靠这一判。
        String deny = h.need("sticker.collect");
        if (deny != null) return deny;
        String file = Str.trim(a.str("file"));
        if (file.isEmpty()) return msg(c, "缺文件", "missing_file");
        String scene = Str.trim(a.str("scene"));
        if (scene.isEmpty()) scene = c.defScene;
        if (scene.isEmpty()) return msg(c, "缺语境", "missing_scene");
        String note = Str.trim(a.str("note"));

        Store store = h.store();
        if (store == null) return msg(c, "异常", "error={error}", "{error}", "store=null");
        String hash = hashOf(file);
        List<JsonObject> hit = byHash(store, hash, 1);
        Caller me = h.caller();
        long myQq = me == null ? 0L : me.qq();
        if (!hit.isEmpty()) {
            // 归属硬判：同 hash 命中一行 = 这条走的是"更新既有条目"的分支（会改语境、并重写 added_by）。
            // 非主人只能更新<b>自己收的</b>条目；别人的条目一律拒（库里是全局共享的，没有别的隔离键）。
            String owner = otherOwnerDeny(h, myQq, hit.get(0));
            if (owner != null) return owner;
        }
        JsonObject row = new JsonObject();
        row.addProperty("file", file);
        row.addProperty("hash", hash);
        row.addProperty("scene", scene);
        row.addProperty("source", SRC);
        JsonObject extra = hit.isEmpty() ? new JsonObject() : J.sub(hit.get(0), "extra");
        if (extra == null) extra = new JsonObject();
        extra.addProperty("kind", kind(file));
        if (me != null) extra.addProperty("added_by", me.qq());
        if (Str.has(note)) extra.addProperty("note", note);
        row.add("extra", extra);

        if (!hit.isEmpty()) {
            long id = J.l(hit.get(0), "id", 0L);
            row.addProperty("id", id);
            long rid = store.put(LIB, row);
            if (rid <= 0L) return msg(c, "异常", "error={error}", "{error}", "collect_update_failed");
            return msg(c, "收藏重复", "sticker_exists id={id} scene={scene} hash={hash}",
                    "{id}", String.valueOf(rid), "{scene}", scene, "{hash}", hash);
        }
        row.addProperty("uses", 0);
        long id = store.put(LIB, row);
        if (id <= 0L) return msg(c, "异常", "error={error}", "{error}", "collect_insert_failed");
        return msg(c, "收藏成功", "sticker_added id={id} scene={scene} hash={hash}",
                "{id}", String.valueOf(id), "{scene}", scene, "{hash}", hash);
    }

    // ================================================================ op：list

    /** 看库里有什么（可按语境过滤）。 */
    private static String list(Args a, Host h, Cfg c) {
        String deny = h.need("sticker.list");          // 列库 = 这个动作自己的 op
        if (deny != null) return deny;
        Store store = h.store();
        if (store == null) return msg(c, "异常", "error={error}", "{error}", "store=null");
        String scene = Str.trim(a.str("scene"));
        int limit = atLeast(a.i("limit", c.listLimit), 1, 100);
        List<JsonObject> rows = Str.has(scene)
                ? store.list(LIB, J.obj("scene", scene), limit, "uses DESC, ts DESC")
                : store.list(LIB, null, limit, "uses DESC, ts DESC");
        if (rows == null || rows.isEmpty()) return msg(c, "列表空", "sticker_list_empty");
        long total = store.count(LIB, null);
        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, "列表头", "sticker_list count={count} n={n}",
                "{count}", String.valueOf(total), "{n}", String.valueOf(rows.size())));
        int i = 0;
        for (JsonObject r : rows) {
            i++;
            sb.append('\n').append(msg(c, "列表行", "{i}. #{id} [{scene}] uses={uses} {hash} {file}",
                    "{i}", String.valueOf(i),
                    "{id}", String.valueOf(J.l(r, "id", 0L)),
                    "{scene}", J.s(r, "scene", ""),
                    "{uses}", String.valueOf(J.i(r, "uses", 0)),
                    "{hash}", Str.cut(J.s(r, "hash", ""), 8),
                    "{file}", Str.cut(Str.oneLine(J.s(r, "file", "")), 80)));
        }
        return sb.toString();
    }

    // ================================================================ op：remark

    /** 按 hash（支持前几位）或 id 改语境/备注。 */
    private static String remark(Args a, Host h, Cfg c) {
        // 技能管控表：这个动作自己的 op —— 判不过就一个字节都不改。
        String deny = h.need("sticker.remark");
        if (deny != null) return deny;
        // 归属硬判（隔离手段，与 op 判定是两件事）：改库里的条目 = 改一份全局共享的数据，非主人一律拒。
        // 文案如实点名"谁能做"：ownerDeny 对 MASTER 与 SYSTEM 都放行（她自己的自主维护也走这条路），
        // 所以这里不写"仅主人"—— 写了就是文案比行为窄。
        String owner = ownerDeny(h,
                "[权限阻断] 改表情包的语境/备注只能由主人或她本人（MASTER/SYSTEM）做。");
        if (owner != null) return owner;
        String hash = Str.trim(a.str("hash"));
        long id = a.l("id", 0L);
        if (!Str.has(hash) && id <= 0L) return msg(c, "缺标记", "missing_mark");
        String scene = Str.trim(a.str("scene"));
        String note = Str.trim(a.str("note"));
        if (!Str.has(scene) && !Str.has(note)) return msg(c, "缺语境", "missing_scene");

        Store store = h.store();
        if (store == null) return msg(c, "异常", "error={error}", "{error}", "store=null");
        List<JsonObject> rows = new ArrayList<JsonObject>();
        if (id > 0L) {
            JsonObject one = store.get(LIB, id);
            if (one != null) rows.add(one);
        } else {
            rows = byHash(store, hash, 100);
        }
        if (rows.isEmpty()) return msg(c, "找不到", "sticker_missing");

        int n = 0;
        for (JsonObject r : rows) {
            long rid = J.l(r, "id", 0L);
            if (rid <= 0L) continue;
            JsonObject patch = new JsonObject();
            if (Str.has(scene)) patch.addProperty("scene", scene);
            if (Str.has(note)) {
                JsonObject extra = J.sub(r, "extra");
                if (extra == null) extra = new JsonObject();
                extra.addProperty("note", note);
                patch.add("extra", extra);
            }
            if (store.update(LIB, rid, patch)) n++;
        }
        if (n == 0) return msg(c, "找不到", "sticker_missing");
        return msg(c, "改语境成功", "sticker_remarked n={n} scene={scene} hash={hash}",
                "{n}", String.valueOf(n), "{scene}", scene, "{hash}", hash);
    }

    // ================================================================ op：clear

    /** 清空整库（op = {@code sticker.clear}：非主人拒，主人与她本人放行）。 */
    private static String clear(Args a, Host h, Cfg c) {
        // 技能管控表：这个动作自己的 op —— 判不过就一张都不删。
        String deny = h.need("sticker.clear");
        if (deny != null) return deny;
        // 归属硬判（隔离手段，与 op 判定是两件事）：清空是"整库级"的破坏性写，非主人一律拒。
        // 文案如实点名"谁能做"（MASTER 与 SYSTEM 都放行），不写"仅主人"。
        String owner = ownerDeny(h,
                "[权限阻断] 清空表情包库只能由主人或她本人（MASTER/SYSTEM）做。");
        if (owner != null) return owner;
        Store store = h.store();
        if (store == null) return msg(c, "异常", "error={error}", "{error}", "store=null");
        int n = 0;
        for (int round = 0; round < CLEAR_ROUNDS; round++) {
            List<JsonObject> rows = store.list(LIB, null, BATCH, null);
            if (rows == null || rows.isEmpty()) break;
            int before = n;
            for (JsonObject r : rows) {
                if (store.delete(LIB, J.l(r, "id", 0L))) n++;
            }
            if (n == before) {
                return msg(c, "清空中断", "sticker_clear_stuck n={n}", "{n}", String.valueOf(n));
            }
        }
        return msg(c, "清空成功", "sticker_cleared n={n}", "{n}", String.valueOf(n));
    }

    // ================================================================ op：custom face（QQ 侧自定义表情）

    /**
     * 自定义表情四件的<b>统一前置</b>：① 这个动作自己的 op；② 归属硬判（{@code ALLUSER} 一律拒）。
     *
     * <p>顺序与既有动作一致：<b>op 先判</b>（管控表说"这个动作给不给他用"），<b>归属后判</b>
     * （"这会动到谁的东西"）。两道都过才轮到参数校验与 NapCat 调用。</p>
     *
     * @param what 中文动作名（拼拒绝文案用）
     * @return 放行返回 {@code null}
     */
    private static String cfGate(Host h, String op, String what) {
        String deny = h.need(op);
        if (deny != null) return deny;
        // 归属硬判：这四个动作改/读的都是 QQ 账号侧的真实资源（不是库里的行），非主人一律拒。
        // 文案如实点名"谁能做"（MASTER 与 SYSTEM 都放行），不写"仅主人"。
        return ownerDeny(h,
                "[权限阻断] " + what + "动的是 QQ 账号侧的真实资源，只能由主人或她本人（MASTER/SYSTEM）做。");
    }

    /** 取 QQ 侧自定义表情详情 / 列表（{@code fetch_custom_face_detail}）。 */
    private static String cfList(Args a, Host h, Cfg c) {
        String gate = cfGate(h, "sticker.cf_list", "取自定义表情详情");
        if (gate != null) return gate;
        if (!h.napcatConnected()) return Api.NOT_CONNECTED;
        int count = atLeast(a.i("count", 48), 1, 500);
        JsonObject r = h.napcat().call(ACT_CF_LIST, J.obj("count", count));
        if (!Api.ok(r)) {
            return msg(c, "自定义表情详情失败", "sticker_cf_list_fail action=" + ACT_CF_LIST + " error={error}",
                    "{error}", err(c, r));
        }
        JsonArray faces = facesOf(r);
        int n = faces == null ? -1 : faces.size();
        String raw = J.json(r);
        // 结构化事实行进日志（与 prompt.md 文案无关，永远可观测）
        log(h, "sticker_cf_list ok=1 action=" + ACT_CF_LIST + " count=" + count
                + " faces=" + n + " chars=" + Str.nz(raw).length());
        String head = msg(c, "自定义表情列表头", "sticker_cf_list count={count} faces={faces}",
                "{count}", String.valueOf(count), "{faces}", String.valueOf(n));
        return head + "\n" + Str.cut(Str.oneLine(raw), 1200);
    }

    /**
     * 把一张图添加为 QQ 自定义表情（{@code add_custom_face}）。
     *
     * <p>目标是<b>库里的某一行</b>（{@code id} / {@code hash}）或直接给的本机文件（{@code file}）。
     * 上游契约要的是<b>本地表情文件路径</b>（它自己会读文件算 md5 / 体积），所以 URL / fileid
     * 形态在这里明确拒（{@code cf_need_local}）—— 不交一个"也许能懂"的值过去。
     * 我们仍然把 {@code md5} / {@code file_size} / {@code file_name} 一并算好传下去。</p>
     *
     * <p>幂等两道：① <b>本地账</b>（库里那一行的 {@code extra.cf.md5} 与本次 md5 相同 ⇒
     * {@code sticker_cf_exists}）；② <b>远端账（尽力而为）</b>（问一次现有清单，命中同一个 md5 ⇒
     * {@code sticker_cf_exists_remote}；拿不到 / 解析不了就跳过这一判，绝不因此拒加）。</p>
     */
    private static String cfAdd(Args a, Host h, Cfg c) {
        String gate = cfGate(h, "sticker.cf_add", "添加自定义表情");
        if (gate != null) return gate;
        if (!h.napcatConnected()) return Api.NOT_CONNECTED;
        Store store = h.store();
        if (store == null) return msg(c, "异常", "error={error}", "{error}", "store=null");

        // ---- 目标解析：id / hash → 库里那一行；没有行就用给定的 file ----
        long id = a.l("id", 0L);
        String wantHash = Str.trim(a.str("hash"));
        String file = Str.trim(a.str("file"));
        JsonObject row = null;
        if (id > 0L) row = store.get(LIB, id);
        if (row == null && Str.has(wantHash)) {
            List<JsonObject> hit = byHash(store, wantHash, 1);
            if (!hit.isEmpty()) row = hit.get(0);
        }
        if (row != null) file = Str.trim(J.s(row, "file", ""));
        if (file.isEmpty()) return msg(c, "缺表情", "cf_missing_target");
        File f = new File(file);
        if (!f.isFile()) {
            return msg(c, "要本机文件", "cf_need_local file={file}", "{file}", Str.cut(Str.oneLine(file), 160));
        }
        String md5 = hashOf(file);
        if (Str.blank(md5)) return msg(c, "异常", "error={error}", "{error}", "cf_hash_failed");
        long size = f.length();
        String name = f.getName();
        String desc = Str.trim(a.str("desc"));

        JsonObject extra = row == null ? null : J.sub(row, "extra");
        JsonObject cf = extra == null ? null : J.sub(extra, "cf");
        String knownMd5 = cf == null ? "" : Str.trim(J.s(cf, "md5", ""));
        // 幂等①（本地账）：同一行已经记过这个 md5 ⇒ 明确回"已添加过"，不再打一次 NapCat
        if (Str.has(knownMd5) && knownMd5.equalsIgnoreCase(md5)) {
            return msg(c, "已添加过", "sticker_cf_exists md5={md5} emoji_id={emoji_id} res_id={res_id}",
                    "{md5}", md5,
                    "{emoji_id}", J.s(cf, "emoji_id", ""),
                    "{res_id}", J.s(cf, "res_id", ""));
        }
        // 幂等②（远端账，尽力而为）：拿不到清单 / 解析不了就跳过 —— 只可能"多问一次"，不可能"因此拒加"
        if (c.cfDedup) {
            JsonObject lr = h.napcat().call(ACT_CF_LIST, J.obj("count", CF_SCAN_COUNT));
            if (Api.ok(lr) && hasText(lr, md5, 0)) {
                return msg(c, "QQ 侧已有", "sticker_cf_exists_remote md5={md5}", "{md5}", md5);
            }
        }

        // ---- 添加（本地路径交给基板：中转开着会被换成外链 URL，同机部署就是裸路径） ----
        String emojiId = Str.trim(a.str("emoji_id"));
        JsonObject p = new JsonObject();
        p.addProperty("file", file);
        p.addProperty("file_name", name);
        p.addProperty("file_size", size);
        p.addProperty("md5", md5);
        // 上游契约：emoji_id 未提供时传空字符串；package_id 未提供时传 0
        p.addProperty("emoji_id", emojiId);
        p.addProperty("package_id", Math.max(0L, a.l("package_id", 0L)));
        JsonObject r = h.napcat().call(ACT_CF_ADD, p);
        if (!Api.ok(r)) {
            return msg(c, "添加失败", "sticker_cf_add_fail action=" + ACT_CF_ADD + " error={error}",
                    "{error}", err(c, r));
        }
        // 从响应里尽量取出 QQ 侧标识（键名大小写 / 下划线都不敏感；取不到就是空）
        String newEmoji = pickStr(r, "emojiid", "emojifaceid");
        String newRes = pickStr(r, "resid", "resourceid");
        if (!Str.has(newEmoji)) newEmoji = emojiId;

        // ---- 描述（可选）：添加动作本身不吃 desc，拿到 emoji_id + res_id 就顺手改一次 ----
        String note = "desc=none";
        if (Str.has(desc)) {
            if (!Str.has(newEmoji) || !Str.has(newRes)) {
                note = "desc=pending";
            } else {
                JsonObject dr = h.napcat().call(ACT_CF_DESC,
                        J.obj("emoji_id", newEmoji, "res_id", newRes, "md5", md5, "desc", desc));
                note = Api.ok(dr) ? "desc=set" : ("desc=fail(" + err(c, dr) + ")");
            }
        }
        // 记账（只记在那一行的 extra.cf 里；不动表结构、不新建表）
        if (row != null && J.l(row, "id", 0L) > 0L) {
            JsonObject ne = extra == null ? new JsonObject() : extra.deepCopy();
            JsonObject nc = new JsonObject();
            if (Str.has(newEmoji)) nc.addProperty("emoji_id", newEmoji);
            if (Str.has(newRes)) nc.addProperty("res_id", newRes);
            nc.addProperty("md5", md5);
            if (Str.has(desc)) nc.addProperty("desc", desc);
            nc.addProperty("ts", System.currentTimeMillis());
            ne.add("cf", nc);
            JsonObject patch = new JsonObject();
            patch.add("extra", ne);
            store.update(LIB, J.l(row, "id", 0L), patch);
        }
        log(h, "sticker_cf_added ok=1 md5=" + md5 + " size=" + size
                + " file=" + Str.cut(Str.oneLine(file), 160)
                + " emoji_id=" + newEmoji + " res_id=" + newRes + " " + note);
        return msg(c, "添加自定义表情成功",
                "sticker_cf_added md5={md5} size={size} emoji_id={emoji_id} res_id={res_id} {note}",
                "{md5}", md5, "{size}", String.valueOf(size),
                "{emoji_id}", newEmoji, "{res_id}", newRes, "{note}", note);
    }

    /**
     * 删除一条 QQ 自定义表情（{@code delete_custom_face}）。
     *
     * <p><b>二次确认</b>：{@code confirm} 必须是显式的真值（{@code true} / {@code yes} / {@code 确认}），
     * 否则回 {@code cf_need_confirm} 且<strong>一次 NapCat 都不打</strong> —— 删掉的是 QQ 侧既有资源，
     * 删了就回不来。</p>
     *
     * <p>目标标识：库里的行（{@code id} / {@code hash} → 读 {@code extra.cf}）或直接给
     * {@code res_id} / {@code md5}。库里那一行没记过 {@code extra.cf} ⇒ {@code cf_not_linked}
     * （我们不知道它在 QQ 侧的标识，不许瞎猜一个值去删）。</p>
     */
    private static String cfDel(Args a, Host h, Cfg c) {
        String gate = cfGate(h, "sticker.cf_del", "删除自定义表情");
        if (gate != null) return gate;
        if (!h.napcatConnected()) return Api.NOT_CONNECTED;
        if (!confirm(a)) return msg(c, "要二次确认", "cf_need_confirm");
        Store store = h.store();
        if (store == null) return msg(c, "异常", "error={error}", "{error}", "store=null");

        long id = a.l("id", 0L);
        String wantHash = Str.trim(a.str("hash"));
        String resId = Str.trim(a.str("res_id"));
        String emojiId = Str.trim(a.str("emoji_id"));
        String md5 = Str.trim(a.str("md5"));
        JsonObject row = null;
        if (id > 0L) row = store.get(LIB, id);
        if (row == null && Str.has(wantHash)) {
            List<JsonObject> hit = byHash(store, wantHash, 1);
            if (!hit.isEmpty()) row = hit.get(0);
        }
        JsonObject extra = row == null ? null : J.sub(row, "extra");
        JsonObject cf = extra == null ? null : J.sub(extra, "cf");
        if (cf == null && row != null) {
            // 指定的那一行没有 QQ 侧标识 ⇒ 明说（不拿 md5 猜一个去删）
            return msg(c, "库里没记", "cf_not_linked id={id}", "{id}", String.valueOf(J.l(row, "id", 0L)));
        }
        if (cf != null) {
            if (!Str.has(resId)) resId = Str.trim(J.s(cf, "res_id", ""));
            if (!Str.has(emojiId)) emojiId = Str.trim(J.s(cf, "emoji_id", ""));
            if (!Str.has(md5)) md5 = Str.trim(J.s(cf, "md5", ""));
        }
        // 上游 delete_custom_face 的字段是 res_id / id / ids / md5（没有 emoji_id）：
        // 只能靠 res_id 与 md5 定位；两个都没有 ⇒ 结构化拒绝（要什么说清楚）。
        if (!Str.has(resId) && !Str.has(md5)) {
            return msg(c, "自定义表情缺标记", "cf_missing_target need=res_id|md5",
                    "{need}", "res_id|md5");
        }
        JsonObject p = new JsonObject();
        if (Str.has(resId)) p.addProperty("res_id", resId);
        if (Str.has(md5)) p.addProperty("md5", md5);
        JsonObject r = h.napcat().call(ACT_CF_DEL, p);
        if (!Api.ok(r)) {
            return msg(c, "删除失败", "sticker_cf_del_fail action=" + ACT_CF_DEL + " error={error}",
                    "{error}", err(c, r));
        }
        // 删掉了就把那一行的 QQ 侧记账摘掉（行本身留着 —— 库是本地库，删 QQ 侧的不该删本地图）
        if (row != null && J.l(row, "id", 0L) > 0L && extra != null) {
            JsonObject ne = new JsonObject();
            for (Map.Entry<String, JsonElement> e : extra.entrySet()) {
                if (e.getKey() == null || "cf".equals(e.getKey())) continue;
                ne.add(e.getKey(), e.getValue());
            }
            JsonObject patch = new JsonObject();
            patch.add("extra", ne);
            store.update(LIB, J.l(row, "id", 0L), patch);
        }
        log(h, "sticker_cf_deleted ok=1 md5=" + md5 + " res_id=" + resId
                + " emoji_id=" + emojiId + " id=" + J.l(row == null ? new JsonObject() : row, "id", 0L));
        return msg(c, "删除自定义表情成功",
                "sticker_cf_deleted md5={md5} res_id={res_id} emoji_id={emoji_id}",
                "{md5}", md5, "{res_id}", resId, "{emoji_id}", emojiId);
    }

    /**
     * 改一条 QQ 自定义表情的描述（{@code set_custom_face_desc}）。
     *
     * <p><b>描述是"要写到 QQ 侧、别人看得见"的文本</b>，所以先在本地过一遍<b>与出站同一套判据</b>
     * （{@link #cfDescBlock}：标记族 → 事实行 → 红线，判据同源、fail-closed），命中就回
     * {@code cf_desc_blocked} 且一次 NapCat 都不打；过了才交给 {@code Api.call}（那里还有一道同样的闸）。</p>
     *
     * <p>目标标识：{@code emoji_id} + {@code res_id} + {@code md5} 三个都是上游的必填项，缺一不可 ——
     * 从库里的行（{@code extra.cf}）补齐，补不齐就是结构化拒绝。</p>
     */
    private static String cfDesc(Args a, Host h, Cfg c) {
        String gate = cfGate(h, "sticker.cf_desc", "改自定义表情描述");
        if (gate != null) return gate;
        if (!h.napcatConnected()) return Api.NOT_CONNECTED;
        String desc = Str.trim(a.str("desc"));
        if (desc.isEmpty()) return msg(c, "缺描述", "cf_missing_desc");
        if (desc.length() > DESC_MAX) {
            return msg(c, "描述太长", "cf_desc_too_long len={len} max={max}",
                    "{len}", String.valueOf(desc.length()), "{max}", String.valueOf(DESC_MAX));
        }
        // ★ 内容闸（本地先判；判据与出站那一层同源，不写第二套）
        String block = cfDescBlock(desc);
        if (block != null) {
            log(h, "cf_desc 被内容闸拦下 " + block + " chars=" + desc.length());
            return msg(c, "描述没过内容闸", "cf_desc_blocked reason={reason}", "{reason}", block);
        }
        Store store = h.store();
        if (store == null) return msg(c, "异常", "error={error}", "{error}", "store=null");
        long id = a.l("id", 0L);
        String wantHash = Str.trim(a.str("hash"));
        String emojiId = Str.trim(a.str("emoji_id"));
        String resId = Str.trim(a.str("res_id"));
        String md5 = Str.trim(a.str("md5"));
        JsonObject row = null;
        if (id > 0L) row = store.get(LIB, id);
        if (row == null && Str.has(wantHash)) {
            List<JsonObject> hit = byHash(store, wantHash, 1);
            if (!hit.isEmpty()) row = hit.get(0);
        }
        JsonObject extra = row == null ? null : J.sub(row, "extra");
        JsonObject cf = extra == null ? null : J.sub(extra, "cf");
        if (cf == null && row != null) {
            return msg(c, "库里没记", "cf_not_linked id={id}", "{id}", String.valueOf(J.l(row, "id", 0L)));
        }
        if (cf != null) {
            if (!Str.has(emojiId)) emojiId = Str.trim(J.s(cf, "emoji_id", ""));
            if (!Str.has(resId)) resId = Str.trim(J.s(cf, "res_id", ""));
            if (!Str.has(md5)) md5 = Str.trim(J.s(cf, "md5", ""));
        }
        if (!Str.has(emojiId) || !Str.has(resId) || !Str.has(md5)) {
            return msg(c, "自定义表情缺标记", "cf_missing_target need=emoji_id+res_id+md5",
                    "{need}", "emoji_id+res_id+md5");
        }
        JsonObject r = h.napcat().call(ACT_CF_DESC,
                J.obj("emoji_id", emojiId, "res_id", resId, "md5", md5, "desc", desc));
        if (!Api.ok(r)) {
            return msg(c, "改描述失败", "sticker_cf_desc_fail action=" + ACT_CF_DESC + " error={error}",
                    "{error}", err(c, r));
        }
        if (row != null && J.l(row, "id", 0L) > 0L && extra != null) {
            JsonObject ne = extra.deepCopy();
            JsonObject nc = cf == null ? new JsonObject() : cf.deepCopy();
            nc.addProperty("desc", desc);
            nc.addProperty("ts", System.currentTimeMillis());
            ne.add("cf", nc);
            JsonObject patch = new JsonObject();
            patch.add("extra", ne);
            store.update(LIB, J.l(row, "id", 0L), patch);
        }
        log(h, "sticker_cf_desc_set ok=1 emoji_id=" + emojiId + " res_id=" + resId
                + " md5=" + md5 + " chars=" + desc.length());
        return msg(c, "改描述成功",
                "sticker_cf_desc_set emoji_id={emoji_id} res_id={res_id} md5={md5} chars={chars}",
                "{emoji_id}", emojiId, "{res_id}", resId, "{md5}", md5,
                "{chars}", String.valueOf(desc.length()));
    }

    // ================================================================ 自定义表情的小工具

    /**
     * 描述文本的<b>内容闸</b>：与出站那一层<b>同一套判据</b>（标记族 / 事实行 / 红线），不写第二套。
     *
     * <p>顺序照 {@code Api.contentFactAny}：整条视图先剥标记族 → 再判事实行 → 再判红线；
     * 红线里"可替换类目"在出站层是<b>就地换词</b>，这里改成<b>拦下</b>（描述是要写进 QQ 侧的
     * 一句话，写一个被换过词的残句不如让主人重说一遍）。<b>只可能更严，不可能更松。</b></p>
     *
     * <p>判不动（判据抛异常）⇒ 返 {@code judge_error}（fail-closed），绝不放行。</p>
     *
     * @return 放行返回 {@code null}；否则是给日志的结构化理由（不含正文）
     */
    private static String cfDescBlock(String desc) {
        try {
            String stripped = MoodMarks.strip(desc);
            if (stripped != null && !stripped.equals(desc)) return "mood_mark";
            if (InternalFacts.fact(desc) != null) return "internal_fact";
            String cat = Redline.veto(desc);
            if (cat != null) return "redline:" + cat;
            String rep = Redline.replace(desc);
            if (rep != null && !rep.equals(desc)) return "redline_replaceable";
        } catch (Throwable t) {
            return "judge_error";
        }
        return null;
    }

    /** 二次确认（删这类不可逆动作）：{@code confirm} 是布尔真值或认得出的中文/英文真值。 */
    private static boolean confirm(Args a) {
        if (a.b("confirm", false)) return true;
        String v = Str.trim(a.str("confirm")).toLowerCase();
        return "yes".equals(v) || "true".equals(v) || "1".equals(v) || "on".equals(v)
                || "确认".equals(v) || "是".equals(v);
    }

    /**
     * 响应里有没有这个字符串值（<b>幂等判据</b>：同一个 md5 是不是已经躺在 QQ 侧的清单里）。
     * 深扫任意嵌套，深度上限 {@link #SCAN_DEPTH}；只比<b>整值相等</b>（不做子串匹配），
     * 所以不会因为 md5 出现在别处的长文本里而误判。
     */
    private static boolean hasText(JsonElement el, String needle, int depth) {
        if (el == null || el.isJsonNull() || depth > SCAN_DEPTH || !Str.has(needle)) return false;
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            return p.isString() && needle.equalsIgnoreCase(Str.trim(p.getAsString()));
        }
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) if (hasText(e, needle, depth + 1)) return true;
            return false;
        }
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                if (hasText(e.getValue(), needle, depth + 1)) return true;
            }
        }
        return false;
    }

    /**
     * 从响应里取第一个形状相符的键值（键名<b>大小写与下划线都不敏感</b>）。
     * 用途只有一个：{@code add_custom_face} 的响应里把 {@code emoji_id} / {@code res_id} 捞出来，
     * 好让"顺手改描述"那一步有东西可用。<b>捞不到就是空串</b> —— 不猜、不编。
     */
    private static String pickStr(JsonObject resp, String... names) {
        Map<String, String> flat = new LinkedHashMap<String, String>();
        collectLeaves(resp, "", 0, flat);
        if (names != null) {
            for (String n : names) {
                String v = flat.get(normKey(n));
                if (Str.has(v)) return Str.trim(v);
            }
        }
        return "";
    }

    /** 把 JSON 里的（键 → 字符串值）拍平（第一个出现的先赢；只收字符串/数字/布尔叶子）。 */
    private static void collectLeaves(JsonElement el, String key, int depth, Map<String, String> out) {
        if (el == null || el.isJsonNull() || depth > SCAN_DEPTH || out == null) return;
        if (el.isJsonPrimitive()) {
            String k = normKey(key);
            if (!k.isEmpty() && !out.containsKey(k)) out.put(k, el.getAsString());
            return;
        }
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) collectLeaves(e, key, depth + 1, out);
            return;
        }
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                collectLeaves(e.getValue(), e.getKey(), depth + 1, out);
            }
        }
    }

    /** 键名归一：去掉下划线 + 转小写（{@code emoji_id} / {@code emojiId} / {@code Emoji_ID} 同一个键）。 */
    private static String normKey(String k) {
        if (k == null) return "";
        StringBuilder sb = new StringBuilder(k.length());
        for (int i = 0; i < k.length(); i++) {
            char ch = k.charAt(i);
            if (ch == '_' || ch == '-' || ch == ' ') continue;
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    /**
     * 自定义表情清单（响应里的第一条数组；{@code data} 优先）。
     * 拿不到就 {@code null} —— 这只是一个"有几条"的可观测数字，不是判据。
     */
    private static JsonArray facesOf(JsonObject resp) {
        if (resp == null) return null;
        JsonArray a = firstArray(resp.get("data"), 0);
        if (a != null) return a;
        return firstArray(resp, 1);
    }

    /** 深扫第一条数组（深度上限 {@link #SCAN_DEPTH}）。 */
    private static JsonArray firstArray(JsonElement el, int depth) {
        if (el == null || el.isJsonNull() || depth > SCAN_DEPTH) return null;
        if (el.isJsonArray()) return el.getAsJsonArray();
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                JsonArray a = firstArray(e.getValue(), depth + 1);
                if (a != null) return a;
            }
        }
        return null;
    }

    // ================================================================ on_timer：TTL 清理

    /** 每个 tick 都进来，但节流与开关会让绝大多数 tick 直接返回。 */
    private static void tick(Host h) {
        Cfg c = load(h);
        if (!c.cleanEnabled) return;
        Store store = h.store();
        if (store == null) return;
        long now = System.currentTimeMillis();
        long last = num(store.kv(KV_LAST, "0"), 0L);
        if (last > 0L && c.intervalMs > 0L && now - last < c.intervalMs) return;

        // 自主维护钩子（on_timer）：判定主体是 SYSTEM（她自己的维护动作）⇒ h.need 恒放行；照旧写这一句。
        String deny = h.need("sticker");
        if (deny != null) {
            log(h, "TTL 清理跳过（本技能没被授权）：" + deny);
            return;
        }
        store.kvSet(KV_LAST, String.valueOf(now));      // 先落闸门：异常也不会变成每 tick 重扫

        int aged = 0;
        // 年龄账：超龄（保持天数，甲方定 15 天）先删，但**删到"可用数 = 最低保有量"就停**
        // （甲方：任何情况下库里可用的图不得少于 15 张 ⇒ 不足就保住最老的也不删）。
        if (c.keepDays > 0) aged = dropOlder(store, now - c.keepDays * 86400000L, c.minKeep);
        int over = 0;
        if (c.maxRows > 0) over = trimTo(store, c.maxRows);

        JsonObject st = J.obj("ts", now, "aged", aged, "over", over,
                "count", store.count(LIB, null), "keep_days", c.keepDays, "max", c.maxRows,
                "min_keep", c.minKeep, "usable", StickerCollect.countUsable(store));
        store.kvSet(KV_STATUS, J.json(st));
        log(h, "TTL 清理 " + J.json(st));
    }

    /**
     * 删 ts 早于 cutoff 的行 —— <b>但要保证删完还有 {@code minKeep} 张可用</b>。
     *
     * <p>口径（甲方 2026-09-16 的硬规则）：一张图超时（{@code 保持天数}）可淘汰，
     * 但"任何情况下库里可用的图不得少于 {@code 最低保有量}"。构造性实现：
     * 先数全库可用数，本轮最多删 {@code 可用数 - 最低保有量} 张（不足就 0 张），
     * 再从超龄行里按 {@code ts ASC}（最老的先）删。实现体在 {@link StickerCollect#dropAged}。</p>
     */
    private static int dropOlder(Store store, long cutoff, int minKeep) {
        return StickerCollect.dropAged(store, cutoff, minKeep);
    }

    /**
     * 超上限：<b>纯滚动 —— 最旧的先走</b>（甲方 2026-09-16 拍板），删到不超过 {@code max}。
     *
     * <p>{@code uses} <b>不再参与淘汰顺序</b>（它只影响"发图选中谁"）：排序就是 {@code ts ASC}
     * ⇒ 留下的永远是"最近收进来的 max 张"。这一条是<b>行数账</b>，不受"最低保有量"约束
     * （默认 60 &gt; 15，两者不冲突）。</p>
     */
    private static int trimTo(Store store, int max) {
        long count = store.count(LIB, null);
        if (count <= max) return 0;
        int need = (int) Math.min(count - max, 5000L);
        List<JsonObject> rows = store.list(LIB, null, need, "ts ASC");
        int n = 0;
        if (rows != null) {
            for (JsonObject r : rows) {
                if (store.delete(LIB, J.l(r, "id", 0L))) n++;
            }
        }
        return n;
    }

    // ================================================================ 小工具

    /** hash：本地存在的文件取字节 md5，否则取字符串（URL / fileid）的 md5。 */
    private static String hashOf(String file) {
        try {
            String t = Str.trim(file);
            File f = new File(t);
            if (f.isFile()) {
                String h = Fs.md5(f);
                if (Str.has(h)) return h;
            }
            return Fs.md5(t.getBytes(Fs.UTF8));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 来源类型（结构性命名的取值，写进 extra.kind）。 */
    private static String kind(String file) {
        String s = Str.lower(Str.trim(file));
        if (s.startsWith("http://") || s.startsWith("https://")) return "url";
        try {
            if (new File(Str.trim(file)).isFile()) return "file";
        } catch (Throwable ignored) {
        }
        return "fileid";
    }

    /** hash 精确命中优先，其次按片段（前几位）模糊找。 */
    private static List<JsonObject> byHash(Store store, String hash, int limit) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (Str.blank(hash)) return out;
        List<JsonObject> exact = store.list(LIB, J.obj("hash", hash), limit, null);
        if (exact != null && !exact.isEmpty()) return exact;
        List<JsonObject> like = store.list(LIB, J.obj("_like", J.obj("hash", hash)), limit, null);
        return like == null ? out : like;
    }

    private static String err(Cfg c, JsonObject r) {
        String e = Api.error(r);
        return Str.has(e) ? e : msg(c, "异常", "error={error}", "{error}", "napcat_unknown");
    }

    // ================================================================ 配置 / 文案

    /**
     * {@code prompt.md} 是唯一配置源：读不到 / 缺项 = 那一项不生效。
     *
     * <p><b>读不到时的失败方向是 fail-closed</b>（2026-09-16 改正）：回落到
     * "不收集 / 不审查 / 收集范围按最保守的白名单档 / 私聊不收（{@code private_note=prompt_unreadable}）"，
     * 并留一行可观测日志。
     * 老写法是直接 {@code return c;}（也就是 {@link Cfg} 的字段默认值）⇒ 配置读不出来时
     * 自动收集<b>仍然开着</b>、范围是<b>全群</b>、审查也开着 ⇒ 照旧下载、照旧调
     * {@code ai.vision()} <b>花钱</b>。"读不出来"绝不该是"更放开"的方向。</p>
     */
    private static Cfg load(Host h) {
        Cfg c = new Cfg();
        String text = "";
        try {
            File dir = h == null ? null : h.skillDir();
            if (dir == null || !dir.isDirectory()) {
                if (h != null && h.conf() != null) dir = new File(h.conf().skillsDir(), NAME);
            }
            text = Fs.read(new File(dir, "prompt.md"), "");
        } catch (Throwable ignored) {
        }
        if (Str.blank(text)) {
            // ★ fail-closed（不是"更放开"）：读不到配置 ⇒ 关掉两个花钱的闸门 + 最保守的收集范围。
            //   采集面全关之后：capture() 一条都不登记（零成本），sweep() 只写一行状态就返回
            //   ⇒ 不下载任何图、不调一次 ai.vision()。
            c.collectOn = false;
            c.review = false;
            c.scope = "";
            // ★ 私聊那一档同样落到最保守（关），并点名"是整份文件读不到这一档" —— 与 scope 一样，
            //   失败方向在**这里显式写**，不靠 Cfg 的字段默认值。
            c.collectPrivate = false;
            c.privateNote = "prompt_unreadable";
            // ★ 自定义表情查重同样落到最保守那一档（开）：它只是"多问一次 NapCat"，
            //   方向只能是"更不容易重复添加"，与上面两个花钱的闸门不同向但同理。
            c.cfDedup = true;
            log(h, "prompt.md 读不到（或为空）⇒ 按最保守处理：自动收集关、审查关、收集范围按白名单"
                    + "、私聊不收（fail-closed：不下载、不调视觉、不花钱）");
            return c;
        }
        Map<String, String> kv = pairs(section(text, "配置"));
        c.texts = pairs(section(text, "文案"));
        c.cleanEnabled = truthy(kv.get("启用清理"));
        c.intervalMs = (long) (dec(kv.get("清理间隔小时"), 6.0D) * 3600_000.0D);
        c.keepDays = atLeast(num(kv.get("保持天数"), 15L), 0, 3650);
        c.minKeep = atLeast(num(kv.get("最低保有量"), 15L), 0, 100000);
        // 最多张数：甲方的容量决定 —— 出厂 60（池子上限；旧值 200 见 tmp\r6\REPORT.md §2.2）
        c.maxRows = atLeast(num(kv.get("最多张数"), 60L), 0, 100000);
        c.randomPick = "随机".equals(Str.trim(kv.get("挑选方式"))) || "random".equalsIgnoreCase(Str.trim(kv.get("挑选方式")));
        c.pool = atLeast(num(kv.get("候选池"), 8L), 1, 200);
        c.defScene = Str.nz(kv.get("默认语境"));
        c.listLimit = atLeast(num(kv.get("列表条数"), 10L), 1, 100);
        // ★ 自动收集与发图（外挂）的配置项（纯增；全是新键，缺项 = 那一项不生效）。
        //   这些键**不进** Conf.knownKeys() / ensureDefaults() / config.json：本技能的唯一配置源是
        //   同目录的 prompt.md（与上面那些项同一口径），进 config 面会制造"两份真相"。
        c.collectOn = truthy(kv.get("收集开关"));
        c.scope = Str.nz(kv.get("收集范围"));
        c.groups = ids(kv.get("收集群白名单"));
        // ★ 收集私聊（甲方 2026-09-17：「群 + 私聊都收」）：**技能自己的开关**，只存在于本目录的
        //   prompt.md —— 不进 Conf.knownKeys() / ensureDefaults() / config.json（与上面那一族同一口径）。
        //   这一档**只管私聊**：群那一支仍然只由 收集范围/收集群白名单 决定，语义一字不动。
        //   ★ 失败方向 = 关（最保守），三条路都在这里**显式**落到 false，并留 privateNote 说明"关在哪一档"：
        //     ① prompt.md 里没这一行 ⇒ missing；② 值不认 ⇒ invalid；③ 整份 prompt.md 读不到 ⇒ prompt_unreadable
        //     （见上面 Str.blank(text) 那一段）。与 收集范围 同一条纪律：失败方向**不靠字段默认值**表达。
        String priv = kv.get("收集私聊");
        if (priv == null) {
            c.collectPrivate = false;
            c.privateNote = "missing";
        } else if (onValue(priv)) {
            c.collectPrivate = true;
            c.privateNote = "on";
        } else if (offValue(priv)) {
            c.collectPrivate = false;
            c.privateNote = "off";
        } else {
            c.collectPrivate = false;
            c.privateNote = "invalid";
            log(h, "「收集私聊」的值不认（" + Str.cut(Str.oneLine(priv), 20)
                    + "）⇒ 按最保守处理：私聊不收（群那一支不受影响）");
        }
        c.maxMb = atLeast(num(kv.get("最大体积MB"), 2L), 0, 200);
        // ★ 「每轮上限: 0」= 用出厂默认 5（**不是** 1）。口径的**唯一事实源就在这一行**：
        //   Cfg 的字段注释（{@link Cfg#perTick}）与 StickerCollect 的兜底原本都写着"0 视为 5"，
        //   而这里老的 `atLeast(num(...), 5L), 1, 100)` 会把 0 抬到 **1** ⇒ 文档说 5、代码给 1，
        //   并且让 StickerCollect 里那句 `c.perTick > 0 ? c.perTick : 5` 变成**永远不可达的死兜底**。
        //   现在在这里一次归一（0 / 负数 ⇒ 5，其余夹到 [1,100]），那句死兜底已删 ——
        //   一处兜底，一个答案。
        long perTickRaw = num(kv.get("每轮上限"), 5L);
        c.perTick = perTickRaw <= 0L ? 5 : atLeast(perTickRaw, 1, 100);
        c.queueMax = atLeast(num(kv.get("队列上限"), 500L), 1, 100000);
        c.queueTtlMin = atLeast(num(kv.get("队列TTL分钟"), 45L), 0, 10080);
        c.diskMb = atLeast(num(kv.get("磁盘上限MB"), 256L), 0, 102400);
        c.review = truthy(kv.get("收集审查"));
        c.moodMatch = truthy(kv.get("发图心情匹配"));
        // 本机图外发：中转（默认，安全）/ 本机路径（同机部署的老口径，显式才开）
        c.localMedia = "本机路径".equals(Str.trim(kv.get("本机图外发")));
        // ★ 自定义表情查重（cf_add 的"远端账"那一道）：**失败方向 = 开**（最保守）——
        //   少一次查重只可能"多添一条重复的"，那是要防的事，所以缺项 / 写错值一律按开处理；
        //   只有显式写「关」（或 off/0/false/no/否）才关掉这一次额外询问。
        String dedup = kv.get("自定义表情查重");
        if (dedup == null) c.cfDedup = true;
        else if (offValue(dedup)) c.cfDedup = false;
        else c.cfDedup = true;                       // 开值 与 写错的值：都按开（安全方向）
        return c;
    }

    /** 群号表：逗号 / 顿号 / 空格 / 分号分隔（空 = 空表）。 */
    private static List<Long> ids(String v) {
        List<Long> out = new ArrayList<Long>();
        if (Str.blank(v)) return out;
        for (String raw : Str.nz(v).split("[,，、;；\\s]+", -1)) {
            String s = Str.trim(raw);
            if (s.isEmpty()) continue;
            try {
                long n = Long.parseLong(s);
                if (n > 0L && !out.contains(Long.valueOf(n))) out.add(Long.valueOf(n));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * 自动收集的配置（字段是包内可见的：{@code StickerCollect} 与它同类同包，直接读）。
     *
     * <p>可见性说明：这个类原来写的是 {@code private static final class Cfg}；为了让
     * {@code StickerCollect} 能接收同一个配置对象（而不是自己再解析一遍 prompt.md ⇒ 两份真相），
     * 只把类本身的可见性从 {@code private} 放宽成包内可见 —— <b>字段与行为一个字节都没动</b>。</p>
     */
    static final class Cfg {
        boolean cleanEnabled = false;
        long intervalMs = 6L * 3600_000L;
        int keepDays = 15;
        int minKeep = 15;
        int maxRows = 60;
        boolean randomPick = false;
        int pool = 8;
        String defScene = "";
        int listLimit = 10;
        Map<String, String> texts = new LinkedHashMap<String, String>();
        // ---- 自动收集 / 发图（新增）----
        /**
         * 收集总闸门。
         * <p>默认 {@code false}（fail-closed）：{@code prompt.md} 读不到时这是最后一道闸门，
         * 那时"默认开着"就等于"配置坏了反而更放开"。</p>
         */
        boolean collectOn = false;
        /** 收集范围：{@code 全群}（出厂值，忽略白名单）= 甲方"当然可以全收集"；{@code 白名单} 只收点名的群。
         *  <p>字段默认留 {@code 全群}（= 出厂配置的值）；<b>配置读不到</b>那一路由 {@link #load} 显式改成
         *  空串（= 最保守的白名单档，一个群都不收）—— 不靠字段默认值表达失败方向。</p> */
        String scope = "全群";
        /** 收集群白名单（只在 {@code 收集范围: 白名单} 时生效；空 = 一个群都不收）。 */
        List<Long> groups = new ArrayList<Long>();
        /**
         * <b>收集私聊</b>（默认关，fail-closed）：私聊那一支的总闸门。
         *
         * <p>{@code prompt.md} 里只有<b>显式读到「开」</b>（或 {@code 1/true/yes/on/是}）才为 {@code true}；
         * <b>缺这一行 / 写错值 / 整份 prompt.md 读不到</b>三条路一律落到 {@code false}（＝关，最保守），
         * 由 {@link #load} 显式写、并把"关在哪一档"记进 {@link #privateNote}（状态 kv 里可见）。
         * 与 {@link #scope} 同一条纪律：<b>失败方向不靠字段默认值表达</b>。</p>
         *
         * <p>这一档<b>只管私聊</b>：群那一支仍然只由 {@link #scope} + {@link #groups} 决定。</p>
         */
        boolean collectPrivate = false;
        /**
         * 私聊那一档是"怎么读出来的"（结构性字符串，写进收集状态 kv 的 {@code private_note}）。
         * 取值：{@code on} / {@code off} / {@code missing} / {@code invalid} / {@code prompt_unreadable}。
         */
        String privateNote = "missing";
        /** 单张体积上限（MB；0 = 不限）。 */
        int maxMb = 2;
        /** 单次 tick 最多处理几条（{@code 0} / 负数 ⇒ <b>5</b>，在 {@link #load} 里归一；这里只说结果）。 */
        int perTick = 5;
        /** 队列行数上限（超了丢最旧）。 */
        int queueMax = 500;
        /** 队列条目 TTL（分钟；0 = 不过期）。 */
        int queueTtlMin = 45;
        /** 图片目录磁盘上限（MB；0 = 不限）。 */
        int diskMb = 256;
        /**
         * 视觉审查（甲方：入库硬闸门 —— 审查结果就是索引内容，没有它不许入库）。
         * <p>默认 {@code false}（fail-closed）：{@code prompt.md} 读不到时不许"审查照开、照花钱"。</p>
         */
        boolean review = false;
        /** 发图时按"当前心情与现状"匹配索引内容（匹配不到则随机）。 */
        boolean moodMatch = true;
        /**
         * 自定义表情添加前的<b>远端查重</b>（cf_add 的幂等第二道）。
         *
         * <p>{@code true}（默认，也是 prompt.md 读不到时的方向）：添加前先问一次
         * {@code fetch_custom_face_detail}，清单里已有同一个 md5 就不重复添加。
         * 拿不到清单 / 解析不了 ⇒ 跳过这一判（只可能多问一次，不可能因此拒加）。
         * 显式写「关」才省掉这一次询问。这条只是<b>额外</b>的一道：本地账
         * （那一行的 {@code extra.cf.md5}）与它无关，永远生效。</p>
         */
        boolean cfDedup = true;
        /**
         * 本机图外发口径：{@code false}（默认，值 = {@code 中转}）= 本地件只在<b>外链中转就绪</b>时才交给 NapCat
         * （基板 {@code Api.rewriteLocalFile} 会换成外链 URL），中转没就绪就改用 {@code extra.url} / 跳过；
         * {@code true}（值 = {@code 本机路径}）= 老口径，直接交本地路径（只适合同机部署）。
         */
        boolean localMedia = false;

        /**
         * 这个群要不要收。
         *
         * <p>{@code 收集范围: 全群}（默认）⇒ 忽略白名单，收所有群（仍受每轮上限/队列上限/磁盘上限管）；
         * {@code 白名单} ⇒ 只收 {@code 收集群白名单} 里点名的群，白名单为空就<b>一个群都不收</b>。
         * 写错的值按最保守的白名单那一档处理，不按"全收"。</p>
         */
        boolean groupAllowed(long groupId) {
            if (groupId <= 0L) return false;
            String s = Str.trim(scope);
            if ("全群".equals(s) || "all".equalsIgnoreCase(s)) return true;
            return groups != null && groups.contains(Long.valueOf(groupId));
        }
    }

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

    private static long num(String v, long def) {
        try {
            return Long.parseLong(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static double dec(String v, double def) {
        try {
            return Double.parseDouble(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static int atLeast(long v, int min, int max) {
        long x = v < min ? min : v;
        return (int) (x > max ? max : x);
    }

    private static boolean truthy(String v) {
        String s = Str.trim(v).toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "是".equals(s);
    }

    /**
     * 「收集私聊」认的<b>开值</b>：只有显式写这些才算开，其余一律按关（最保守）。
     *
     * <p>为什么不直接复用 {@link #truthy}：那个是"总开关"的口径（认 {@code 1/true/yes/on/是}），
     * 而这一档的文档写的是 {@code 开|关} —— 两个中文值都要认，且<b>写错值必须落到关</b>，
     * 不能因为"truthy 返回 false"与"值不认"在状态里分不开。所以这里成对写，
     * 并在 {@link #load} 里把 invalid 与 off 分开记（{@code private_note}）。</p>
     */
    private static boolean onValue(String v) {
        String s = Str.trim(v).toLowerCase();
        return "开".equals(s) || "开启".equals(s) || "1".equals(s) || "true".equals(s)
                || "yes".equals(s) || "on".equals(s) || "是".equals(s);
    }

    /** 「收集私聊」认的<b>关值</b>（写这些 = 明确关；不认的值也按关处理，只是状态里区分开）。 */
    private static boolean offValue(String v) {
        String s = Str.trim(v).toLowerCase();
        return "关".equals(s) || "关闭".equals(s) || "0".equals(s) || "false".equals(s)
                || "no".equals(s) || "off".equals(s) || "否".equals(s);
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
