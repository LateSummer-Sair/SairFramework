import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sair.v4.QqGateway;
import sair.v4.auth.Caller;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Api;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 好友管理（工具 {@code friend}）：好友列表 / 用户资料 / 机器人自身账号 + 机器人自己的动作。
 *
 * <p>权限：工具是她的手段，能不能用<b>只看技能管控表</b>（{@code skillctl.json}）——
 * 每个 op 各判自己那一行（{@code friend.list} / {@code friend.info} / …），判定放在动作分支开头；
 * 真正的平台动作还有一层：发动作前用 {@code h.need("napcat.<动作名>")} 复核一次
 * （NapCat 动作的 op 名 = {@code napcat.<动作名>}）。旧口径里"某动作 = ROOT / 某动作 = {@code AFFECTION:N}"
 * 的档位条目已随档位体系退休 —— 现在只剩"这个身份能不能用这个 op"，好感度不参与判权。</p>
 *
 * <p>文案 / 阈值 / 输出模板全在 {@code prompt.md}（运行期读，改即生效）；
 * NapCat 未连接时动作返回基板话术「Napcat 没有连接」，异常一律吞掉并给出说明。</p>
 *
 * <h3>两套"拉黑"：本地是权威，平台是尽力</h3>
 * <p>为什么需要本地那一套：QQ 层面的拉黑/删好友<b>管不到群内互动</b>（NapCat 没有"群内屏蔽某人"
 * 这个动作），对方照样能在群里 @ 你。所以 {@code block} 的两半各有分工：</p>
 * <ul>
 *   <li><b>本地黑名单（权威）</b>：kv {@code block:<qq>}（{@link QqGateway#BLOCK_PREFIX}），
 *       入站网关据此<b>不触发 AI</b>（私聊与群内一视同仁），消息照旧进 grouplog 留痕。</li>
 *   <li><b>平台动作（尽力）</b>：动作名读 {@code prompt.md} 的「拉黑动作」/「解除拉黑动作」；
 *       没配、不支持、没连接都<b>不回滚本地</b>，只把原因原样拼进 {@code {act}} 回报。</li>
 * </ul>
 * <p>{@code blocklist} 是本地那一套的读取口（NapCat 侧看不到本地名单，没有它主人就不知道拉黑了谁）。</p>
 *
 * <h3>拉黑这一套的归属硬判（不是 op 判定）</h3>
 * <p>本地黑名单是<b>全局共享</b>的一份名单（键里只有"被拉黑的人"，没有"谁拉的"这一维度），
 * 所以除了技能管控表的 op 授权之外，另有一道归属硬判（代码里的显式身份判定，见 {@link #ownerDeny}）。
 * 所以：</p>
 * <ul>
 *   <li>{@link #block}（{@code block} / {@code unblock}）非主人一律拒 —— 在本地写入<b>之前</b>返回；</li>
 *   <li>{@link #blocklist}（读名单）与它<b>一体</b>：非主人同样拒，且拒绝文案里不带任何名单内容
 *       （连条目数都不报）。{@code MASTER} / {@code SYSTEM} 两个口都照常。</li>
 * </ul>
 */
public class FriendSkill implements Skill {

    private static final String NAME = "好友管理";
    private static final String OPS = "list, info, delete, block, unblock, blocklist, like, self, poke, rename, signature";

    /** 本地黑名单的 kv 键前缀：与基板入站网关共用同一个常量（键名漂移 = 拉黑了却不生效）。 */
    private static final String BLOCK_PREFIX = QqGateway.BLOCK_PREFIX;

    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        // 每个动作各一条：技能管控表就是按这些名字放行 / 封禁的
        ops.add("friend", "list", "看好友列表");
        ops.add("friend", "info", "查某人资料（陌生人 / 群成员）");
        ops.add("friend", "delete", "删好友");
        ops.add("friend", "block", "拉黑（本地黑名单 + 尽力发平台动作）");
        ops.add("friend", "unblock", "解除拉黑");
        ops.add("friend", "blocklist", "看本地黑名单有谁");
        ops.add("friend", "like", "点赞");
        ops.add("friend", "self", "看机器人自己的账号");
        ops.add("friend", "poke", "戳一戳");
        ops.add("friend", "rename", "改机器人自己的昵称");
        ops.add("friend", "signature", "改机器人自己的个性签名");
    }

    @Override
    public Object call(Args a, Host h) {
        Cfg c = load(h);
        try {
            return run(a, h, c);
        } catch (Throwable t) {
            log(h, "op=" + Str.trim(a == null ? "" : a.str("op")) + " exception=" + t);
            return msg(c, "执行异常", "[friend] error={err}", "err", String.valueOf(t));
        }
    }

    // ---------------------------------------------------------------- 分发

    private Object run(Args a, Host h, Cfg c) {
        if (a == null) return msg(c, "缺参数", "[friend] missing {k}", "k", "op");
        String op = Str.trim(a.str("op")).toLowerCase();
        if (op.isEmpty()) op = "list";
        if (!c.enabled) return msg(c, "未启用", "[friend] disabled");
        if ("list".equals(op)) return list(c, h);
        if ("info".equals(op)) return info(c, a, h);
        if ("delete".equals(op)) return delete(c, a, h);
        if ("block".equals(op)) return block(c, a, h, true);
        if ("unblock".equals(op)) return block(c, a, h, false);
        if ("blocklist".equals(op)) return blocklist(c, h);
        if ("like".equals(op)) return like(c, a, h);
        if ("self".equals(op)) return self(c, h);
        if ("poke".equals(op)) return poke(c, a, h);
        if ("rename".equals(op)) return rename(c, a, h);
        if ("signature".equals(op)) return signature(c, a, h);
        log(h, "unknown op=" + op);
        return msg(c, "不支持的操作", "[friend] unknown op={op}; ops: " + OPS, "op", op, "ops", OPS);
    }

    // ---------------------------------------------------------------- 查询

    /** 好友列表：{@code get_friend_list}。 */
    private String list(Cfg c, Host h) {
        // op 判定（动作分支开头）：看好友列表 = friend.list
        String deny = h.need("friend.list");
        if (deny != null) return deny;
        R r = act(h, "get_friend_list", new JsonObject());
        if (r.deny != null) return denied(c, r.deny);
        if (!Api.ok(r.resp)) return failed(c, "list", r.resp);
        JsonArray arr = rows(r.resp);
        int cap = c.num("好友列表最多", 200);
        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, "好友表头", "[friend] friends={n}",
                "n", String.valueOf(arr.size()))).append('\n');
        int i = 0;
        for (JsonElement e : arr) {
            if (i >= cap) break;
            JsonObject f = e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
            i++;
            sb.append(msg(c, "好友行", "{i}|{qq}|{name}|{remark}",
                    "i", String.valueOf(i),
                    "qq", String.valueOf(J.l(f, "user_id", 0L)),
                    "name", Str.oneLine(J.s(f, "nickname", "")),
                    "remark", Str.oneLine(J.s(f, "remark", "")))).append('\n');
        }
        if (arr.size() > i) {
            sb.append(msg(c, "截断说明", "[friend] total={n} shown={shown}",
                    "n", String.valueOf(arr.size()), "shown", String.valueOf(i))).append('\n');
        }
        log(h, "list friends=" + arr.size() + " shown=" + i);
        return sb.toString().trim();
    }

    /** 用户资料：给了群号走 {@code get_group_member_info}（成员详情），否则 {@code get_stranger_info}。 */
    private String info(Cfg c, Args a, Host h) {
        // op 判定（动作分支开头）：查资料 = friend.info
        String deny = h.need("friend.info");
        if (deny != null) return deny;
        long qq = user(a, h);
        if (qq <= 0L) return missing(c, "qq");
        long gid = a.l("group", a.l("group_id", 0L));
        if (gid > 0L) {
            R r = act(h, "get_group_member_info", J.obj("group_id", gid, "user_id", qq));
            if (r.deny != null) return denied(c, r.deny);
            if (!Api.ok(r.resp)) return failed(c, "info", r.resp);
            JsonObject m = J.sub(r.resp, "data");
            if (m == null) m = new JsonObject();
            log(h, "info member group=" + gid + " qq=" + qq);
            return msg(c, "成员详情", "{group}|{qq}|{name}|{card}|{role}|{level}",
                    "group", String.valueOf(gid),
                    "qq", String.valueOf(J.l(m, "user_id", qq)),
                    "name", Str.oneLine(J.s(m, "nickname", "")),
                    "card", Str.oneLine(J.s(m, "card", "")),
                    "role", J.s(m, "role", ""),
                    "level", String.valueOf(J.l(m, "level", 0L)));
        }
        R r = act(h, "get_stranger_info", J.obj("user_id", qq));
        if (r.deny != null) return denied(c, r.deny);
        if (!Api.ok(r.resp)) return failed(c, "info", r.resp);
        JsonObject u = J.sub(r.resp, "data");
        if (u == null) u = new JsonObject();
        log(h, "info stranger qq=" + qq);
        return msg(c, "陌生人详情", "{qq}|{name}|{sex}|{age}|{level}",
                "qq", String.valueOf(J.l(u, "user_id", qq)),
                "name", Str.oneLine(J.s(u, "nickname", "")),
                "sex", J.s(u, "sex", ""),
                "age", String.valueOf(J.l(u, "age", 0L)),
                "level", String.valueOf(J.l(u, "level", 0L)));
    }

    /** 机器人自己的账号信息：{@code get_login_info}。 */
    private String self(Cfg c, Host h) {
        // op 判定（动作分支开头）：看自己的账号 = friend.self
        String deny = h.need("friend.self");
        if (deny != null) return deny;
        R r = act(h, "get_login_info", new JsonObject());
        if (r.deny != null) return denied(c, r.deny);
        if (!Api.ok(r.resp)) return failed(c, "self", r.resp);
        JsonObject u = J.sub(r.resp, "data");
        if (u == null) u = new JsonObject();
        long qq = J.l(u, "user_id", h.conf() == null ? 0L : h.conf().selfId());
        log(h, "self qq=" + qq);
        return msg(c, "账号信息", "{qq}|{name}",
                "qq", String.valueOf(qq),
                "name", Str.oneLine(J.s(u, "nickname", "")));
    }

    // ---------------------------------------------------------------- 机器人自身动作

    /** 删除好友：{@code delete_friend}（平台动作，非主人被拒）。 */
    private String delete(Cfg c, Args a, Host h) {
        // op 判定（动作分支开头）：删好友 = friend.delete
        String deny = h.need("friend.delete");
        if (deny != null) return deny;
        long qq = user(a, h);
        if (qq <= 0L) return missing(c, "qq");
        R r = act(h, "delete_friend", J.obj("user_id", qq));
        if (r.deny != null) return denied(c, r.deny);
        if (!Api.ok(r.resp)) return failed(c, "delete", r.resp);
        log(h, "delete friend qq=" + qq);
        return msg(c, "已删除好友", "[friend] deleted qq={qq}", "qq", String.valueOf(qq));
    }

    /**
     * 拉黑 / 解除拉黑 = <b>本地黑名单（权威）＋ 尽力而为的平台动作</b>。
     *
     * <p>V4 侧"不再理会某人的消息"由本地黑名单决定：kv 键 {@code block:<qq>}（存在且值非空 = 已拉黑），
     * 由基板入站网关（{@link QqGateway#blocked(long)}）在消息进来时读它 —— 命中就<b>不触发 AI</b>
     * （私聊与群内一视同仁，群内正是 QQ 侧拉黑管不到的地方），消息本身照旧进 grouplog 留痕；
     * {@code unblock} 删掉这条键，触发就恢复。</p>
     *
     * <p>平台动作名从 {@code prompt.md} 读 —— NapCat 的标准动作目录里没有这一对，不同构建支持的名字不一样，
     * 所以：没配动作名 / 动作不被支持 / NapCat 没连接，都<b>不影响本地黑名单生效</b>，
     * 只把平台侧的结果原样回报（{@code {act}}）。平台动作的门槛不再由基板 {@code napcatLevel} 档位给出
     * （档位体系已退休），而是按动作名判技能管控表里的 {@code napcat.<动作名>}（{@link #act}）。
     * 平台动作使用同一个动作名做开/关（靠 {@code enable} 参数区分；名字可分别配置）。</p>
     *
     * <p><b>谁能拉黑/解除</b>：{@code block} / {@code unblock} 都是"改一份全局共享的名单"，
     * 非主人一律拒（见 {@link #ownerDeny}）—— 这一判在本地写入<b>之前</b>，
     * 所以被拒时本地黑名单一个字都不会动。</p>
     */
    private String block(Cfg c, Args a, Host h, boolean on) {
        // op 判定（动作分支开头）：拉黑与解除拉黑是两个动作、各判自己的 op（共用实现，但判定分开）
        String deny = h.need(on ? "friend.block" : "friend.unblock");
        if (deny != null) return deny;
        long qq = user(a, h);
        if (qq <= 0L) return missing(c, "qq");

        // 归属硬判（数据隔离手段，不是 op 判定）：本地黑名单是一份<b>全局共享</b>的名单
        // （kv 键 block:<qq> 里只有"被拉黑的人"，没有"谁拉的"这一维度），
        // 所以 op 授权之外还要显式判身份：MASTER 与 SYSTEM 照常，ALLUSER 一律拒（拒绝文案以 [权限阻断] 开头）。
        String owner = ownerDeny(h, on);
        if (owner != null) return owner;

        // ① 本地黑名单：先落袋为安
        boolean localOk = true;
        try {
            if (on) {
                JsonObject v = new JsonObject();
                v.addProperty("reason", Str.trim(a.str("reason")));
                v.addProperty("at", System.currentTimeMillis());
                v.addProperty("by", h.caller() == null ? "bot" : String.valueOf(h.caller().qq()));
                h.store().kvSet(BLOCK_PREFIX + qq, v.toString());
            } else {
                h.store().kvDelete(BLOCK_PREFIX + qq, false);
            }
        } catch (Throwable t) {
            localOk = false;
            log(h, (on ? "block" : "unblock") + " local kv failed qq=" + qq + " err=" + t);
        }

        // ② 平台动作：动作名没配就跳过；失败原样回报，不回滚本地
        String key = on ? "拉黑动作" : "解除拉黑动作";
        String action = Str.trim(c.str(key, ""));
        String actNote;
        if (!localOk) {
            actNote = msg(c, "本地写入失败", "[friend] local block list write failed");
        } else if (Str.blank(action)) {
            actNote = msg(c, "平台动作-未配置", "[friend] config key empty: {k}", "k", key);
        } else {
            R r = act(h, action, J.obj("user_id", qq, "enable", on));
            if (r.deny != null) actNote = denied(c, r.deny);
            else if (!Api.ok(r.resp)) actNote = failed(c, on ? "block" : "unblock", r.resp);
            else actNote = msg(c, "平台动作-已执行", "已执行");
        }
        log(h, (on ? "block" : "unblock") + " qq=" + qq + " local=" + localOk + " action=" + action + " act=" + actNote);

        if (on) {
            return msg(c, "已拉黑", "[friend] block qq={qq} action={action}",
                    "qq", String.valueOf(qq), "action", action, "act", actNote);
        }
        return msg(c, "已解除拉黑", "[friend] unblock qq={qq} action={action}",
                "qq", String.valueOf(qq), "action", action, "act", actNote);
    }

    /**
     * 本地黑名单清单（{@code blocklist}）：只列<b>本地</b>那一份。
     *
     * <p>为什么必须有这个读取口：NapCat / QQ 那边<b>看不到</b>本地黑名单，没有它主人无法知道
     * 本地到底拉黑了谁（更没法核对 {@code block} 到底有没有写进去）。读的是 kv 前缀
     * {@link #BLOCK_PREFIX}，一行一个 QQ；时间字段兼容两代写法：V4 写 {@code at}，
     * V3 迁移来的是 {@code blocked_at}。</p>
     *
     * <p><b>谁能看</b>：拉黑名单与 {@code block}/{@code unblock} 一体 —— 名单里是"谁被挡在门外"，
     * 属于主人的信息，非主人一律拒（见 {@link #readOwnerDeny}）。旧的 op 级档位
     * （{@code 好友管理.friend.blocklist} = MASTER）已随档位体系退休，而技能管控表只能表达
     * "能不能用 {@code friend.blocklist} 这个动作" ⇒ "谁被拉黑了"这道门由显式归属判定承接。
     * 拒绝时<b>一个字节的名单内容都不回</b>（连条目数都不报），免得"拒绝"本身变成信息泄露。</p>
     */
    private String blocklist(Cfg c, Host h) {
        // op 判定（动作分支开头）：看本地黑名单 = friend.blocklist
        String deny = h.need("friend.blocklist");
        if (deny != null) return deny;
        // 归属硬判（数据隔离手段，不是 op 判定）：读名单同 block/unblock，非主人一律拒。
        // 必须在读库之前返回 —— 拒绝文案里不能带任何 QQ 号，连"有几条"都不带。
        String owner = readOwnerDeny(h);
        if (owner != null) return owner;

        int cap = c.num("黑名单最多", 100);
        List<JsonObject> rows = h.store() == null ? null : h.store().kvList(BLOCK_PREFIX, cap);
        if (rows == null || rows.isEmpty()) return msg(c, "黑名单空", "[friend] blocklist_empty");

        SimpleDateFormat df = new SimpleDateFormat(c.str("时间格式", "yyyy-MM-dd HH:mm"));
        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, "黑名单头", "[friend] blocklist n={n}",
                "n", String.valueOf(rows.size()))).append('\n');
        int i = 0;
        for (JsonObject r : rows) {
            String key = J.s(r, "k", "");
            if (!key.startsWith(BLOCK_PREFIX)) continue;         // LIKE 前缀理论上不会多给，双保险
            i++;
            JsonObject v = J.obj(J.s(r, "v", ""));
            if (v == null) v = new JsonObject();
            long at = J.l(v, "at", J.l(v, "blocked_at", 0L));
            sb.append(msg(c, "黑名单行", "{i}. {qq}｜{time}｜{reason}｜by {by}",
                    "i", String.valueOf(i),
                    "qq", key.substring(BLOCK_PREFIX.length()),
                    "time", at > 0L ? df.format(new Date(at)) : "",
                    "reason", Str.oneLine(J.s(v, "reason", "")),
                    "by", Str.oneLine(J.s(v, "by", J.s(v, "blocked_by", ""))))).append('\n');
        }
        if (i == 0) return msg(c, "黑名单空", "[friend] blocklist_empty");
        if (rows.size() >= cap) {
            sb.append(msg(c, "截断说明", "[friend] total={n} shown={shown}",
                    "n", String.valueOf(rows.size()), "shown", String.valueOf(i))).append('\n');
        }
        log(h, "blocklist n=" + i);
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- 归属硬判

    /**
     * 拉黑 / 解除拉黑的<b>归属硬判</b>：{@code MASTER}（主人）与 {@code SYSTEM}（她自己的自主行为）放行，
     * {@code ALLUSER}（普通用户）一律拒。
     *
     * <p>为什么 op 授权之外还要这一道：本地黑名单是全局共享的一份名单（键 {@code block:<qq>} 只有"被拉黑的人"，
     * 没有"谁拉的"这一维度）—— 技能管控表只回答"能不能用 {@code friend.block} 这个动作"，
     * 回答不了"这份名单该不该由他改"；归属硬判回答的就是后者。</p>
     *
     * <p>拒绝文案以 {@code [权限阻断]} 开头（结构前缀，不是 prompt.md 文案）：归属判定不是可配置话术。</p>
     *
     * @param on {@code true} = 拉黑 / {@code false} = 解除拉黑
     * @return 放行返回 {@code null}
     */
    private static String ownerDeny(Host h, boolean on) {
        Caller c = h == null ? null : h.caller();
        if (c == null) return "[权限阻断] 拉黑/解除拉黑是主人的事：拿不到调用者身份，按拒绝处理。";
        Caller.Kind k = c.kind();
        if (k == Caller.Kind.MASTER || k == Caller.Kind.SYSTEM) return null;
        // 文案如实点名"谁能做"：放行面是 MASTER + SYSTEM 两个（只写"仅主人"就是文案比行为窄）。
        // 保留"仅主人"这四个字是既有探针断言的要求（ProbeSkillsA 的 block/unblock 非主人被拒时点名"仅主人"），
        // 故写成"仅 X 与 Y 能…"。
        return "[权限阻断] " + (on ? "拉黑" : "解除拉黑")
                + "是主人的事：仅主人（MASTER）与她自己的自主行为（SYSTEM）能改本地黑名单。";
    }

    /**
     * 读拉黑名单（{@code blocklist}）的<b>归属硬判</b>：与 {@link #ownerDeny} 同一把钥匙 ——
     * 名单和"改名单"是一件事的两面，能改的人才能看。{@code MASTER} 与 {@code SYSTEM} 照常，
     * {@code ALLUSER} 一律拒。
     *
     * <p>旧的 op 级档位（{@code 好友管理.friend.blocklist} = MASTER）随档位体系退休后，
     * 技能管控表只管"能不能用 {@code friend.blocklist}" —— "谁被拉黑了"变成谁都能读。
     * 这一判就是把那道门接回来。</p>
     *
     * <p><b>拒绝文案里没有任何名单内容</b>：不带 QQ 号、不带条数、不带时间/原因（连"有几条"都不报），
     * 否则"拒绝"本身就变成了信息泄露。</p>
     *
     * @return 放行返回 {@code null}
     */
    private static String readOwnerDeny(Host h) {
        Caller c = h == null ? null : h.caller();
        if (c == null) return "[权限阻断] 黑名单是主人的信息：拿不到调用者身份，按拒绝处理。";
        Caller.Kind k = c.kind();
        if (k == Caller.Kind.MASTER || k == Caller.Kind.SYSTEM) return null;
        return "[权限阻断] 黑名单是主人的信息：名单里是「谁被挡在门外」，仅主人（MASTER）与她自己的自主行为（SYSTEM）能看。";
    }

    // ---------------------------------------------------------------- 机器人自身：轻互动 / 账号资料

    /**
     * 戳一戳：群里用 {@code group_poke}（group_id + user_id），私聊用 {@code friend_poke}（user_id）。
     *
     * <p>门槛不硬编码在技能里：两个动作各按动作名判（{@link #act}），也就是
     * 技能管控表 {@code skillctl.json} 里的 {@code napcat.group_poke} / {@code napcat.friend_poke}
     * 两行（{@code MASTER} / {@code SYSTEM} 恒全权，{@code ALLUSER} 按表判、**空 = 未授权**）。
     * 要改只有两个入口：控制台的授权操作，或让主人改 {@code skillctl.json}；
     * {@code skills/权限注册表.md} 只是自动生成的视图，改它无效。</p>
     */
    private String poke(Cfg c, Args a, Host h) {
        // op 判定（动作分支开头）：戳一戳 = friend.poke
        String deny = h.need("friend.poke");
        if (deny != null) return deny;
        long qq = user(a, h);
        if (qq <= 0L) return missing(c, "qq");
        long gid = a.l("group", a.l("group_id", 0L));
        if (gid <= 0L && h.caller() != null && h.caller().isGroup()) gid = h.caller().groupId();

        if (gid > 0L) {
            R r = act(h, "group_poke", J.obj("group_id", gid, "user_id", qq));
            if (r.deny != null) return denied(c, r.deny);
            if (!Api.ok(r.resp)) return failed(c, "poke", r.resp);
            log(h, "poke group=" + gid + " qq=" + qq);
            return msg(c, "已戳群", "[friend] poke group={group} qq={qq}",
                    "group", String.valueOf(gid), "qq", String.valueOf(qq));
        }
        R r = act(h, "friend_poke", J.obj("user_id", qq));
        if (r.deny != null) return denied(c, r.deny);
        if (!Api.ok(r.resp)) return failed(c, "poke", r.resp);
        log(h, "poke private qq=" + qq);
        return msg(c, "已戳私聊", "[friend] poke qq={qq}", "qq", String.valueOf(qq));
    }

    /** 改机器人自己的 QQ 昵称：{@code set_qq_profile}（只发 nickname，避免清掉签名）。平台动作，非主人被拒。 */
    private String rename(Cfg c, Args a, Host h) {
        // op 判定（动作分支开头）：改机器人昵称 = friend.rename
        String deny = h.need("friend.rename");
        if (deny != null) return deny;
        String name = Str.trim(a.str("name", a.str("nickname", "")));
        if (Str.blank(name)) return missing(c, "name");
        R r = act(h, "set_qq_profile", J.obj("nickname", name));
        if (r.deny != null) return denied(c, r.deny);
        if (!Api.ok(r.resp)) return failed(c, "rename", r.resp);
        log(h, "rename -> " + name);
        return msg(c, "已改昵称", "[friend] rename {name}", "name", name);
    }

    /** 改机器人自己的个性签名：{@code set_self_longnick}（NapCat 扩展）。平台动作，非主人被拒。 */
    private String signature(Cfg c, Args a, Host h) {
        // op 判定（动作分支开头）：改签名 = friend.signature
        String deny = h.need("friend.signature");
        if (deny != null) return deny;
        String sig = Str.trim(a.str("signature", a.str("text", "")));
        if (Str.blank(sig)) return missing(c, "signature");
        R r = act(h, "set_self_longnick", J.obj("longNick", sig));
        if (r.deny != null) return denied(c, r.deny);
        if (!Api.ok(r.resp)) return failed(c, "signature", r.resp);
        log(h, "signature -> " + sig);
        return msg(c, "已改签名", "[friend] signature {sig}", "sig", sig);
    }

    /** 点赞：{@code send_like}（次数的缺省值在 prompt.md）。 */
    private String like(Cfg c, Args a, Host h) {
        // op 判定（动作分支开头）：点赞 = friend.like
        String deny = h.need("friend.like");
        if (deny != null) return deny;
        long qq = user(a, h);
        if (qq <= 0L) return missing(c, "qq");
        int times = a.i("times", c.num("默认点赞次数", 10));
        if (times <= 0) times = c.num("默认点赞次数", 10);
        R r = act(h, "send_like", J.obj("user_id", qq, "times", times));
        if (r.deny != null) return denied(c, r.deny);
        if (!Api.ok(r.resp)) return failed(c, "like", r.resp);
        log(h, "like qq=" + qq + " times=" + times);
        return msg(c, "已点赞", "[friend] like qq={qq} times={times}",
                "qq", String.valueOf(qq), "times", String.valueOf(times));
    }

    // ---------------------------------------------------------------- 动作 / 权限

    /** 动作结果：{@code deny} 非空 = 被技能管控表拦下（不发动作）；否则 {@code resp} 是 NapCat 响应。 */
    private static final class R {
        String deny;
        JsonObject resp;
    }

    /**
     * 复核后发一个 NapCat 动作：动作的 op 名 = {@code napcat.<动作名>}，按技能管控表判
     * （没放行 = 未授权 = 不可用；{@code MASTER} 与她本人 {@code SYSTEM} 恒全权）。
     */
    private static R act(Host h, String action, JsonObject params) {
        R r = new R();
        r.deny = h.need("napcat." + action);
        if (r.deny == null) r.resp = h.napcat().call(action, params == null ? new JsonObject() : params);
        return r;
    }

    private static String denied(Cfg c, String deny) {
        return msg(c, "权限不足", "{deny}", "deny", deny);
    }

    private static String missing(Cfg c, String k) {
        return msg(c, "缺参数", "[friend] missing {k}", "k", k);
    }

    private static String failed(Cfg c, String op, JsonObject resp) {
        String err = Api.error(resp);
        if (Api.NOT_CONNECTED.equals(err)) return err;
        return msg(c, "操作失败", "[friend] op={op} failed: {err}", "op", op, "err", err);
    }

    // ---------------------------------------------------------------- 小工具

    /** QQ 号：显式给优先，否则回退到调用者自己。 */
    private static long user(Args a, Host h) {
        long qq = a.l("qq", a.l("user", a.l("user_id", 0L)));
        if (qq > 0L) return qq;
        Caller me = h.caller();
        return me == null ? 0L : me.qq();
    }

    /** 统一取 {@code data} 段的行数组：数组直接用，对象取它的 list 字段。 */
    private static JsonArray rows(JsonObject resp) {
        JsonElement d = J.get(resp, "data");
        if (d == null) return new JsonArray();
        if (d.isJsonArray()) return d.getAsJsonArray();
        if (d.isJsonObject()) {
            JsonObject o = d.getAsJsonObject();
            for (String k : new String[] {"list", "friends", "data"}) {
                JsonElement e = J.get(o, k);
                if (e != null && e.isJsonArray()) return e.getAsJsonArray();
            }
        }
        return new JsonArray();
    }

    // ---------------------------------------------------------------- 配置 / 文案

    private static Cfg load(Host h) {
        Cfg c = new Cfg();
        try {
            File dir = h.skillDir();
            if (dir == null) dir = new File(h.conf().skillsDir(), NAME);
            String text = Fs.read(new File(dir, "prompt.md"), "");
            if (Str.blank(text)) return c;
            for (String raw : text.split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int a = line.indexOf(':');
                int b = line.indexOf('：');
                int i = a < 0 ? b : (b < 0 ? a : Math.min(a, b));
                if (i <= 0) continue;
                c.kv.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
            c.enabled = c.bool("启用", true);
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static final class Cfg {
        boolean enabled = true;
        final Map<String, String> kv = new HashMap<String, String>();

        String str(String k, String def) {
            String v = kv.get(k);
            return Str.blank(v) ? def : v.trim();
        }

        boolean bool(String k, boolean def) {
            String s = Str.trim(kv.get(k)).toLowerCase();
            if ("1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s)) return true;
            if ("0".equals(s) || "false".equals(s) || "no".equals(s) || "off".equals(s)) return false;
            return def;
        }

        int num(String k, int def) {
            try {
                return Integer.parseInt(str(k, "").trim());
            } catch (Exception e) {
                return def;
            }
        }
    }

    private static String msg(Cfg c, String key, String fallback, String... kv) {
        String tpl = c == null ? null : c.kv.get(key);
        if (Str.blank(tpl)) tpl = fallback;
        return render(tpl, kv);
    }

    private static String render(String tpl, String... kv) {
        if (tpl == null) return "";
        String s = tpl;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return s;
    }

    private static void log(Host h, String msg) {
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.warn("[" + NAME + "] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
