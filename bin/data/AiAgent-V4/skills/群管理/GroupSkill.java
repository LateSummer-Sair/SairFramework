import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * 群管理（工具 {@code group}）：群查询 + 群管动作 + NapCat 4.18 边缘能力（群待办 / 群打卡 / 群成员策略 /
 * 群分享链接 / 群相册取消点赞 / QQ 空间说说），动作一律经 {@code h.napcat()} 发出。
 *
 * <p>权限：技能管控表只管控技能（op），工具入口由基板按工具级 op / 动作级 op 放行；
 * 每个动作分支在动第一件事之前<b>各自判一次自己的 op</b>（{@code group.list} / {@code group.ban} …），
 * 判不过就把基板的拒绝原文原样回给模型、<b>动作不发</b>；技能里没有任何硬编码门槛。
 * 退群且 {@code dismiss=true}（解散群）时配置键「解散需主人」会在发动作前<b>再过一遍同一个 op</b>
 * （{@link #leave}）：解散不可撤销，但这个"不可撤销"不再由好感度大小的门槛表达，
 * 也不再有单独的主人授权条目 —— 旧写法调 {@code h.gate("leave.dismiss")} 会按 {@code favor.of(qq)}
 * 与 {@code AFFECTION:<N>} 判权，与"好感度只决定回复热情度"直接冲突，已删除。</p>
 *
 * <p><b>NapCat 4.18 边缘能力（本批新增的十个 op）</b>：新增动作一律按同一套纪律写 ——
 * ① 先 {@code h.need("group.xxx")}（唯一的权限真源，管控表说了算）；
 * ② 高危写类（待办完成/取消、三个群成员策略、取消相册点赞、删空间说说）与对外发声（发空间说说）
 * 在权限之后再过一道<b>确认闸</b>（{@link #confirm}，配置键「高危需确认」）——
 * <b>它不是权限门槛</b>（不为任何身份开/关权限），只是"不可撤销 / 对外可见"这类动作的一致性闸；
 * ③ 参数校验失败回结构化错误（{@link #missing} / {@link #bad}），不抛穿透；
 * ④ 成功回一行可观测事实（群号 / 目标 / 字符数，不落正文）。</p>
 *
 * <p><b>QQ 空间不是群消息</b>：{@code send_qzone_msg} 发出去的是<b>公开可见的空间动态</b>，
 * 收件人不是群成员。它照样只走 {@code h.napcat()} → {@code Api.call}，
 * 因此<b>基板的内容级闸逐条照过</b>（自我记账头 / 内部事实行 / {@code [[mood:…]]} 标记族 / 出站红线，
 * 判据与词表都在基板，见 {@code qq.Api} 的 {@code selfHeadFact} / {@code contentFactAny}）。
 * 本技能<b>不给自己开旁路</b>：不调 {@code say()}、不碰 {@code Sinks}、不 import 红线判据、
 * 不写第二种"能不能发"的判断 —— 那等于把安全级规则复制到可开可关的技能里。</p>
 *
 * <p>文案 / 阈值 / 输出模板全在本技能自己的 {@code prompt.md}（运行期读，改即生效）：
 * Java 里只有 op 名、动作名、参数名、配置键与结构性 ASCII 兜底。
 * NapCat 未连接时动作返回基板话术「Napcat 没有连接」，任何异常都被吞掉并换成说明。</p>
 */
public class GroupSkill implements Skill {

    private static final String NAME = "群管理";
    private static final String OPS = "list, members, member, ban, kick, muteall, setadmin, setname, setcard, leave, "
            + "tododone, todocancel, signedlist, invitepolicy, memberperm, newmembervis, sharelink, albumunlike, "
            + "qzonesend, qzonedel";
    private static final String DF = "yyyy-MM-dd HH:mm";

    /** 群成员邀请策略的取值（NapCat 4.18.14 的枚举，与上游逐字一致）。 */
    private static final List<String> POLICIES = Arrays.asList(
            "disabled", "require_approval", "no_approval", "no_approval_under_100");

    /** QQ 空间说说的可见范围（NapCat 4.18.14：1 所有人 / 4 好友 / 16 部分好友 / 64 仅自己 / 128 部分好友不可见）。 */
    private static final List<Integer> RIGHTS = Arrays.asList(
            Integer.valueOf(1), Integer.valueOf(4), Integer.valueOf(16), Integer.valueOf(64), Integer.valueOf(128));

    /** 本技能全部 op（一个动作一条）：装载期声明，技能管控表就是按这些名字放行或封禁的。 */
    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        ops.add("group", "list", "群列表");
        ops.add("group", "members", "群成员列表");
        ops.add("group", "member", "单个成员资料");
        ops.add("group", "ban", "禁言 / 解禁");
        ops.add("group", "kick", "踢人（可拉黑）");
        ops.add("group", "muteall", "全员禁言开关");
        ops.add("group", "setadmin", "设置 / 取消管理员");
        ops.add("group", "setname", "改群名");
        ops.add("group", "setcard", "改群名片");
        ops.add("group", "leave", "退群（可顺带解散）");
        ops.add("group", "tododone", "完成群待办");
        ops.add("group", "todocancel", "取消群待办");
        ops.add("group", "signedlist", "今日群打卡名单");
        ops.add("group", "invitepolicy", "群成员邀请策略");
        ops.add("group", "memberperm", "群成员功能权限");
        ops.add("group", "newmembervis", "新成员历史消息可见性");
        ops.add("group", "sharelink", "群分享链接");
        ops.add("group", "albumunlike", "取消点赞群相册");
        ops.add("group", "qzonesend", "发 QQ 空间说说");
        ops.add("group", "qzonedel", "删 QQ 空间说说");
    }

    @Override
    public Object call(Args a, Host h) {
        Cfg c = load(h);
        try {
            return run(a, h, c);
        } catch (Throwable t) {
            log(h, "op=" + Str.trim(a == null ? "" : a.str("op")) + " exception=" + t);
            return msg(c, "执行异常", "[group] error={err}", "err", String.valueOf(t));
        }
    }

    // ---------------------------------------------------------------- 分发

    private Object run(Args a, Host h, Cfg c) {
        if (a == null) return msg(c, "缺参数", "[group] missing {k}", "k", "op");
        String op = Str.trim(a.str("op")).toLowerCase();
        if (op.isEmpty()) op = "list";
        if (!c.enabled) return msg(c, "未启用", "[group] disabled");
        if ("list".equals(op)) return list(c, h);
        if ("members".equals(op)) return members(c, a, h);
        if ("member".equals(op)) return member(c, a, h);
        if ("ban".equals(op)) return ban(c, a, h);
        if ("kick".equals(op)) return kick(c, a, h);
        if ("muteall".equals(op)) return muteall(c, a, h);
        if ("setadmin".equals(op)) return setadmin(c, a, h);
        if ("setname".equals(op)) return setname(c, a, h);
        if ("setcard".equals(op)) return setcard(c, a, h);
        if ("leave".equals(op)) return leave(c, a, h);
        // ---- NapCat 4.18 边缘能力 ----
        if ("tododone".equals(op)) return todo(c, a, h, true);
        if ("todocancel".equals(op)) return todo(c, a, h, false);
        if ("signedlist".equals(op)) return signedlist(c, a, h);
        if ("invitepolicy".equals(op)) return invitepolicy(c, a, h);
        if ("memberperm".equals(op)) return memberperm(c, a, h);
        if ("newmembervis".equals(op)) return newmembervis(c, a, h);
        if ("sharelink".equals(op)) return sharelink(c, a, h);
        if ("albumunlike".equals(op)) return albumunlike(c, a, h);
        if ("qzonesend".equals(op)) return qzonesend(c, a, h);
        if ("qzonedel".equals(op)) return qzonedel(c, a, h);
        log(h, "unknown op=" + op);
        return msg(c, "不支持的操作", "[group] unknown op={op}; ops: " + OPS, "op", op, "ops", OPS);
    }

    // ---------------------------------------------------------------- 查询

    /** 群列表：{@code get_group_list}（op = {@code group.list}）。 */
    private String list(Cfg c, Host h) {
        String deny = h.need("group.list");
        if (deny != null) return denied(c, deny);
        R r = act(h, "get_group_list", new JsonObject());
        if (!Api.ok(r.resp)) return failed(c, "list", r.resp);
        JsonArray arr = rows(r.resp);
        int cap = c.num("群列表最多", 100);
        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, "群列表头", "[group] groups={n}",
                "n", String.valueOf(arr.size()))).append('\n');
        int i = 0;
        for (JsonElement e : arr) {
            if (i >= cap) break;
            JsonObject g = e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
            i++;
            sb.append(msg(c, "群行", "{i}|{group}|{name}|{count}|{max}",
                    "i", String.valueOf(i),
                    "group", String.valueOf(J.l(g, "group_id", 0L)),
                    "name", Str.oneLine(J.s(g, "group_name", "")),
                    "count", String.valueOf(J.l(g, "member_count", 0L)),
                    "max", String.valueOf(J.l(g, "max_member_count", 0L)))).append('\n');
        }
        tail(sb, c, arr.size(), i);
        log(h, "list groups=" + arr.size() + " shown=" + i);
        return sb.toString().trim();
    }

    /** 成员列表：{@code get_group_member_list}（op = {@code group.members}）。 */
    private String members(Cfg c, Args a, Host h) {
        String deny = h.need("group.members");
        if (deny != null) return denied(c, deny);
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        R r = act(h, "get_group_member_list", J.obj("group_id", gid));
        if (!Api.ok(r.resp)) return failed(c, "members", r.resp);
        JsonArray arr = rows(r.resp);
        int cap = c.num("成员列表最多", 200);
        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, "成员表头", "[group] group={group} members={n}",
                "group", String.valueOf(gid), "n", String.valueOf(arr.size()))).append('\n');
        int i = 0;
        for (JsonElement e : arr) {
            if (i >= cap) break;
            JsonObject m = e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
            i++;
            sb.append(memberLine(c, i, m)).append('\n');
        }
        tail(sb, c, arr.size(), i);
        log(h, "members group=" + gid + " total=" + arr.size() + " shown=" + i);
        return sb.toString().trim();
    }

    /** 单个成员：{@code get_group_member_info}（op = {@code group.member}）。 */
    private String member(Cfg c, Args a, Host h) {
        String deny = h.need("group.member");
        if (deny != null) return denied(c, deny);
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        long qq = user(a, h);
        if (qq <= 0L) return missing(c, "qq");
        R r = act(h, "get_group_member_info", J.obj("group_id", gid, "user_id", qq));
        if (!Api.ok(r.resp)) return failed(c, "member", r.resp);
        JsonObject m = J.sub(r.resp, "data");
        if (m == null) m = new JsonObject();
        log(h, "member group=" + gid + " qq=" + qq);
        return msg(c, "成员详情", "{group}|{qq}|{name}|{card}|{role}|{level}|{title}|{join}",
                "group", String.valueOf(gid),
                "qq", String.valueOf(J.l(m, "user_id", qq)),
                "name", Str.oneLine(J.s(m, "nickname", "")),
                "card", Str.oneLine(J.s(m, "card", "")),
                "role", J.s(m, "role", ""),
                "level", String.valueOf(J.l(m, "level", 0L)),
                "title", Str.oneLine(J.s(m, "title", "")),
                "join", stamp(J.l(m, "join_time", 0L)));
    }

    // ---------------------------------------------------------------- 群管动作

    /** 禁言 / 解禁：{@code set_group_ban}（op = {@code group.ban}），{@code duration=0} 表示解除。 */
    private String ban(Cfg c, Args a, Host h) {
        String deny = h.need("group.ban");
        if (deny != null) return denied(c, deny);
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        long qq = a.l("qq", a.l("user", a.l("user_id", 0L)));
        if (qq <= 0L) return missing(c, "qq");
        int sec = a.i("duration", c.num("默认禁言秒数", 600));
        if (sec < 0) sec = 0;
        R r = act(h, "set_group_ban", J.obj("group_id", gid, "user_id", qq, "duration", sec));
        if (!Api.ok(r.resp)) return failed(c, "ban", r.resp);
        log(h, "ban group=" + gid + " qq=" + qq + " sec=" + sec);
        if (sec <= 0) {
            return msg(c, "已解除禁言", "[group] unban group={group} qq={qq}",
                    "group", String.valueOf(gid), "qq", String.valueOf(qq));
        }
        return msg(c, "禁言已设置", "[group] ban group={group} qq={qq} sec={sec}",
                "group", String.valueOf(gid), "qq", String.valueOf(qq), "sec", String.valueOf(sec));
    }

    /** 踢人（可选拉黑 = 拒绝再加群）：{@code set_group_kick}（op = {@code group.kick}）。 */
    private String kick(Cfg c, Args a, Host h) {
        String deny = h.need("group.kick");
        if (deny != null) return denied(c, deny);
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        long qq = a.l("qq", a.l("user", a.l("user_id", 0L)));
        if (qq <= 0L) return missing(c, "qq");
        boolean reject = flag(a, "reject", flag(a, "blacklist", c.bool("踢人默认拉黑", false)));
        R r = act(h, "set_group_kick",
                J.obj("group_id", gid, "user_id", qq, "reject_add_request", reject));
        if (!Api.ok(r.resp)) return failed(c, "kick", r.resp);
        log(h, "kick group=" + gid + " qq=" + qq + " reject=" + reject);
        return msg(c, "踢人已执行", "[group] kick group={group} qq={qq} reject={reject}",
                "group", String.valueOf(gid), "qq", String.valueOf(qq), "reject", String.valueOf(reject));
    }

    /** 全员禁言开关：{@code set_group_whole_ban}（op = {@code group.muteall}）；没给 enable 时先读群信息再取反。 */
    private String muteall(Cfg c, Args a, Host h) {
        String deny = h.need("group.muteall");
        if (deny != null) return denied(c, deny);
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        Boolean enable = a.has("enable") ? Boolean.valueOf(flag(a, "enable", false)) : null;
        if (enable == null) {
            R g = act(h, "get_group_info", J.obj("group_id", gid));
            if (!Api.ok(g.resp)) return failed(c, "muteall", g.resp);
            JsonObject d = J.sub(g.resp, "data");
            if (d == null || J.get(d, "group_all_shut") == null) return missing(c, "enable");
            enable = Boolean.valueOf(J.l(d, "group_all_shut", 0L) <= 0L);
        }
        R r = act(h, "set_group_whole_ban",
                J.obj("group_id", gid, "enable", enable.booleanValue()));
        if (!Api.ok(r.resp)) return failed(c, "muteall", r.resp);
        log(h, "muteall group=" + gid + " enable=" + enable);
        return msg(c, "全员禁言已设置", "[group] whole-ban group={group} enable={enable}",
                "group", String.valueOf(gid), "enable", String.valueOf(enable));
    }

    /** 设置 / 取消管理员：{@code set_group_admin}（op = {@code group.setadmin}）。 */
    private String setadmin(Cfg c, Args a, Host h) {
        String deny = h.need("group.setadmin");
        if (deny != null) return denied(c, deny);
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        long qq = a.l("qq", a.l("user", a.l("user_id", 0L)));
        if (qq <= 0L) return missing(c, "qq");
        boolean enable = flag(a, "enable", true);
        R r = act(h, "set_group_admin",
                J.obj("group_id", gid, "user_id", qq, "enable", enable));
        if (!Api.ok(r.resp)) return failed(c, "setadmin", r.resp);
        log(h, "setadmin group=" + gid + " qq=" + qq + " enable=" + enable);
        return msg(c, "管理员已设置", "[group] set-admin group={group} qq={qq} enable={enable}",
                "group", String.valueOf(gid), "qq", String.valueOf(qq), "enable", String.valueOf(enable));
    }

    /** 改群名：{@code set_group_name}（op = {@code group.setname}）。 */
    private String setname(Cfg c, Args a, Host h) {
        String deny = h.need("group.setname");
        if (deny != null) return denied(c, deny);
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        String name = Str.oneLine(a.str("name"));
        if (Str.blank(name)) return missing(c, "name");
        R r = act(h, "set_group_name", J.obj("group_id", gid, "group_name", name));
        if (!Api.ok(r.resp)) return failed(c, "setname", r.resp);
        log(h, "setname group=" + gid + " len=" + name.length());
        return msg(c, "群名已改", "[group] set-name group={group} name={name}",
                "group", String.valueOf(gid), "name", name);
    }

    /** 改群名片（空串 = 清空）：{@code set_group_card}（op = {@code group.setcard}）；没给 qq 就是改自己的。 */
    private String setcard(Cfg c, Args a, Host h) {
        String deny = h.need("group.setcard");
        if (deny != null) return denied(c, deny);
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        long qq = user(a, h);
        if (qq <= 0L) return missing(c, "qq");
        String card = Str.oneLine(a.str("card"));
        R r = act(h, "set_group_card", J.obj("group_id", gid, "user_id", qq, "card", card));
        if (!Api.ok(r.resp)) return failed(c, "setcard", r.resp);
        log(h, "setcard group=" + gid + " qq=" + qq + " len=" + card.length());
        return msg(c, "名片已改", "[group] set-card group={group} qq={qq} card={card}",
                "group", String.valueOf(gid), "qq", String.valueOf(qq), "card", card);
    }

    /**
     * 退群 / 解散：{@code set_group_leave}（op = {@code group.leave}）。
     *
     * <p>{@code 解散需主人} 这个配置键的实际语义是"解散前把同一个 op（{@code group.leave}）再判一遍"
     * （默认开）：管控表里 {@code group.leave} 没给这个身份授权（空 / Ban）时当场拒，
     * 非主人（含群主 / 管理员，群角色不参与判定）一律拒，主人与她自己的自主行为照常。
     * 下面这次判定与分支开头那次是同一个 op，重复一次只是让"解散"这点在代码里看得见
     * （也让配置键有个明确含义）；真正的动作仍由后面那一次发出去。</p>
     */
    private String leave(Cfg c, Args a, Host h) {
        String deny = h.need("group.leave");
        if (deny != null) return denied(c, deny);
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        boolean dismiss = flag(a, "dismiss", c.bool("退群默认解散", false));
        if (dismiss && c.bool("解散需主人", true)) {
            // h.need 的拒绝原文本身就是那句带 [权限阻断] 前缀的原文，不再加工（与分支开头那条路径逐字一致）。
            String again = h.need("group.leave");
            if (again != null) return denied(c, again);
        }
        R r = act(h, "set_group_leave",
                J.obj("group_id", gid, "is_dismiss", dismiss));
        if (!Api.ok(r.resp)) return failed(c, "leave", r.resp);
        log(h, "leave group=" + gid + " dismiss=" + dismiss);
        return msg(c, "已退群", "[group] leave group={group} dismiss={dismiss}",
                "group", String.valueOf(gid), "dismiss", String.valueOf(dismiss));
    }

    // ---------------------------------------------------------------- NapCat 4.18 边缘能力

    /**
     * 完成 / 取消群待办：{@code complete_group_todo} / {@code cancel_group_todo}
     * （op = {@code group.tododone} / {@code group.todocancel}）。
     *
     * <p>待办挂在一条消息上（{@code message_id}，可选 {@code message_seq}），所以 {@code msg} 必填 ——
     * 没给就回结构化「缺参数」，绝不"随便挑一条待办"。</p>
     */
    private String todo(Cfg c, Args a, Host h, boolean done) {
        String op = done ? "group.tododone" : "group.todocancel";
        String deny = h.need(op);
        if (deny != null) return denied(c, deny);
        String cf = confirm(c, a, h, op);
        if (cf != null) return cf;
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        String mid = Str.trim(a.str("msg"));
        if (Str.blank(mid)) return missing(c, "msg");
        JsonObject p = J.obj("group_id", gid, "message_id", mid);
        String seq = Str.trim(a.str("seq"));
        if (!Str.blank(seq)) p.addProperty("message_seq", seq);
        R r = act(h, done ? "complete_group_todo" : "cancel_group_todo", p);
        if (!Api.ok(r.resp)) return failed(c, op, r.resp);
        log(h, (done ? "todo-done" : "todo-cancel") + " group=" + gid + " msg=" + mid);
        return msg(c, done ? "待办已完成" : "待办已取消",
                done ? "[group] todo-done group={group} msg={msg}" : "[group] todo-cancel group={group} msg={msg}",
                "group", String.valueOf(gid), "msg", mid);
    }

    /**
     * 今日群打卡名单：{@code get_group_signed_list}（op = {@code group.signedlist}）。
     *
     * <p>读类动作：过 op 判定后直接取列表，按「打卡名单最多」截断。
     * 行字段按 OneBot 惯例取（{@code user_id}/{@code uin}、{@code nickname}/{@code name}、
     * 时间戳一族），<b>字段名只有真机能核</b>（上游只给了必填参数，没给返回结构）——
     * 认不出的字段留空，不猜、不抛。</p>
     */
    private String signedlist(Cfg c, Args a, Host h) {
        String deny = h.need("group.signedlist");
        if (deny != null) return denied(c, deny);
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        R r = act(h, "get_group_signed_list", J.obj("group_id", gid));
        if (!Api.ok(r.resp)) return failed(c, "signedlist", r.resp);
        JsonArray arr = rows(r.resp);
        int cap = c.num("打卡名单最多", 200);
        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, "打卡名单头", "[group] group={group} signed={n}",
                "group", String.valueOf(gid), "n", String.valueOf(arr.size()))).append('\n');
        int i = 0;
        for (JsonElement e : arr) {
            if (i >= cap) break;
            JsonObject m = e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
            i++;
            sb.append(msg(c, "打卡行", "{i}|{qq}|{name}|{time}",
                    "i", String.valueOf(i),
                    "qq", String.valueOf(num(m, new String[] {"user_id", "uin", "qq"}, 0L)),
                    "name", Str.oneLine(txt(m, new String[] {"nickname", "nick", "name", "card"})),
                    "time", stamp(num(m, new String[] {"time", "signed_time", "timestamp", "stamp"}, 0L)))).append('\n');
        }
        tail(sb, c, arr.size(), i);
        log(h, "signedlist group=" + gid + " total=" + arr.size() + " shown=" + i);
        return sb.toString().trim();
    }

    /**
     * 群成员邀请策略：{@code set_group_member_invite_policy}（op = {@code group.invitepolicy}）。
     * {@code policy} 四个取值逐字按上游枚举，认不出的值回结构化「参数不合法」，不猜一个最近似的。
     */
    private String invitepolicy(Cfg c, Args a, Host h) {
        String deny = h.need("group.invitepolicy");
        if (deny != null) return denied(c, deny);
        String cf = confirm(c, a, h, "group.invitepolicy");
        if (cf != null) return cf;
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        String policy = Str.trim(a.str("policy")).toLowerCase();
        if (Str.blank(policy)) return missing(c, "policy");
        if (!POLICIES.contains(policy)) return bad(c, "policy", policy, join(POLICIES));
        R r = act(h, "set_group_member_invite_policy", J.obj("group_id", gid, "policy", policy));
        if (!Api.ok(r.resp)) return failed(c, "invitepolicy", r.resp);
        log(h, "invite-policy group=" + gid + " policy=" + policy);
        return msg(c, "邀请策略已改", "[group] invite-policy group={group} policy={policy}",
                "group", String.valueOf(gid), "policy", policy);
    }

    /**
     * 群成员功能权限：{@code set_group_member_permissions}（op = {@code group.memberperm}）。
     * 三个开关都<b>没给</b> = 没有要改的东西 ⇒ 回结构化「缺参数」（上游语义是"未传入的项目保持不变"，
     * 全不传等于空动作，不如让模型补参）；给了哪个就只带哪个，没给的保持 NapCat 侧不变。
     */
    private String memberperm(Cfg c, Args a, Host h) {
        String deny = h.need("group.memberperm");
        if (deny != null) return denied(c, deny);
        String cf = confirm(c, a, h, "group.memberperm");
        if (cf != null) return cf;
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        JsonObject p = J.obj("group_id", gid);
        StringBuilder facts = new StringBuilder();
        if (a.has("upload_album")) {
            boolean v = flag(a, "upload_album", false);
            p.addProperty("allow_member_upload_album", v);
            facts.append(" upload_album=").append(v);
        }
        if (a.has("temp_session")) {
            boolean v = flag(a, "temp_session", false);
            p.addProperty("allow_member_temporary_session", v);
            facts.append(" temp_session=").append(v);
        }
        if (a.has("create_group")) {
            boolean v = flag(a, "create_group", false);
            p.addProperty("allow_member_create_group", v);
            facts.append(" create_group=").append(v);
        }
        if (facts.length() == 0) return missing(c, "upload_album/temp_session/create_group");
        R r = act(h, "set_group_member_permissions", p);
        if (!Api.ok(r.resp)) return failed(c, "memberperm", r.resp);
        log(h, "member-perm group=" + gid + facts);
        return msg(c, "成员权限已改", "[group] member-perm group={group}{facts}",
                "group", String.valueOf(gid), "facts", facts.toString());
    }

    /** 新成员历史消息可见性：{@code set_group_new_member_history_visibility}（op = {@code group.newmembervis}）。 */
    private String newmembervis(Cfg c, Args a, Host h) {
        String deny = h.need("group.newmembervis");
        if (deny != null) return denied(c, deny);
        String cf = confirm(c, a, h, "group.newmembervis");
        if (cf != null) return cf;
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        if (!a.has("visible")) return missing(c, "visible");
        boolean visible = flag(a, "visible", false);
        R r = act(h, "set_group_new_member_history_visibility",
                J.obj("group_id", gid, "visible", visible));
        if (!Api.ok(r.resp)) return failed(c, "newmembervis", r.resp);
        log(h, "new-member-history group=" + gid + " visible=" + visible);
        return msg(c, "历史可见性已改", "[group] new-member-history group={group} visible={visible}",
                "group", String.valueOf(gid), "visible", String.valueOf(visible));
    }

    /**
     * 群分享链接：{@code get_group_share_link}（op = {@code group.sharelink}）。
     * 读类动作（NapCat 4.18.26 起）：{@code need_short_url} 缺省读 prompt.md「分享默认短链」。
     */
    private String sharelink(Cfg c, Args a, Host h) {
        String deny = h.need("group.sharelink");
        if (deny != null) return denied(c, deny);
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        boolean shortUrl = flag(a, "short", c.bool("分享默认短链", true));
        int src = a.i("src", 0);
        JsonObject p = J.obj("group_id", gid, "need_short_url", shortUrl);
        if (src > 0) p.addProperty("src_id", src);
        String extra = Str.trim(a.str("extra"));
        if (!Str.blank(extra)) p.addProperty("additional_param", extra);
        R r = act(h, "get_group_share_link", p);
        if (!Api.ok(r.resp)) return failed(c, "sharelink", r.resp);
        String url = linkOf(r.resp);
        if (Str.blank(url)) {
            log(h, "sharelink group=" + gid + " short=" + shortUrl + " got=0");
            return msg(c, "未取到链接", "[group] sharelink group={group} short={short} got=0",
                    "group", String.valueOf(gid), "short", String.valueOf(shortUrl));
        }
        log(h, "sharelink group=" + gid + " short=" + shortUrl + " chars=" + url.length());
        return msg(c, "群链接已取", "群 {group} 的分享链接：{url}",
                "group", String.valueOf(gid), "url", url);
    }

    /**
     * 取消点赞群相册媒体：{@code cancel_group_album_media_like}（op = {@code group.albumunlike}）。
     * 必填三项（{@code group_id}/{@code album_id}/{@code batch_id}）；{@code lloc} 可空 =
     * 整批操作（上游 4.18.4 起 {@code lloc} 从必填改为非必填）。
     */
    private String albumunlike(Cfg c, Args a, Host h) {
        String deny = h.need("group.albumunlike");
        if (deny != null) return denied(c, deny);
        String cf = confirm(c, a, h, "group.albumunlike");
        if (cf != null) return cf;
        long gid = group(a, h);
        if (gid <= 0L) return missing(c, "group");
        String album = Str.trim(a.str("album"));
        if (Str.blank(album)) return missing(c, "album");
        String batch = Str.trim(a.str("batch"));
        if (Str.blank(batch)) return missing(c, "batch");
        String lloc = Str.trim(a.str("lloc"));
        JsonObject p = J.obj("group_id", gid, "album_id", album, "batch_id", batch);
        if (!Str.blank(lloc)) p.addProperty("lloc", lloc);
        R r = act(h, "cancel_group_album_media_like", p);
        if (!Api.ok(r.resp)) return failed(c, "albumunlike", r.resp);
        log(h, "album-unlike group=" + gid + " batch=" + batch + " whole=" + Str.blank(lloc));
        return msg(c, "相册点赞已取消", "[group] album-unlike group={group} batch={batch} lloc={lloc}",
                "group", String.valueOf(gid), "batch", batch, "lloc", Str.blank(lloc) ? "-" : lloc);
    }

    /**
     * 发表 QQ 空间说说：{@code send_qzone_msg}（op = {@code group.qzonesend}）。
     *
     * <p><b>这不是群消息</b>：发出去的是<b>公开可见的空间动态</b>（默认 {@code ugc_right=1} 所有人可见），
     * 收件人不是群成员、撤回要凭 tid。所以本分支和其它写类一样先判 op、再过确认闸，
     * 然后<b>老老实实走 {@code h.napcat()} → {@code Api.call}</b> ——
     * 基板的出站内容闸（自我记账头 / 内部事实行 / {@code [[mood:…]]} 标记族 / 出站红线）会在
     * 交给 NapCat <b>之前</b>判这一条，命中就整条拦下、拒绝原文原样回给模型。
     * 本技能<b>不复制</b>任何一条判据（不 import 红线、不自己正则、不调 {@code say()}）。</p>
     *
     * <p>图片只收 {@code http(s)://}：{@code images} <b>不在</b>基板 {@code Api} 的 {@code FILE_KEYS} 里
     * ⇒ 本地路径不会被中转换成跨机可取的 URL，交出去只会静默发不出去；所以从技能这一层就不收本地路径
     * （顺带避开"把本机文件交出去却不过 File 域判定"这条缝）。</p>
     */
    private String qzonesend(Cfg c, Args a, Host h) {
        String deny = h.need("group.qzonesend");
        if (deny != null) return denied(c, deny);
        String cf = confirm(c, a, h, "group.qzonesend");
        if (cf != null) return cf;
        String text = Str.trim(a.str("text"));
        if (Str.blank(text)) return missing(c, "text");
        int right = a.i("right", c.num("空间默认可见范围", 1));
        if (!RIGHTS.contains(Integer.valueOf(right))) return bad(c, "right", String.valueOf(right), join(RIGHTS));
        List<String> images = new ArrayList<String>();
        String badImg = images(c, a, images);
        if (badImg != null) return badImg;
        List<Long> targets = new ArrayList<Long>();
        String badTarget = targets(c, a, targets);
        if (badTarget != null) return badTarget;
        if ((right == 16 || right == 128) && targets.isEmpty()) return missing(c, "targets");
        JsonObject p = new JsonObject();
        p.addProperty("content", text);
        p.addProperty("ugc_right", right);
        if (!images.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (int i = 0; i < images.size(); i++) arr.add(new JsonPrimitive(images.get(i)));
            p.add("images", arr);
        }
        if (!targets.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (int i = 0; i < targets.size(); i++) arr.add(new JsonPrimitive(targets.get(i)));
            p.add("target_uins", arr);
        }
        R r = act(h, "send_qzone_msg", p);
        if (!Api.ok(r.resp)) return failed(c, "qzonesend", r.resp);
        log(h, "qzone-send right=" + right + " chars=" + text.length() + " images=" + images.size()
                + " targets=" + targets.size());
        return msg(c, "空间已发表", "[group] qzone-send right={right} chars={chars} images={images}",
                "right", String.valueOf(right), "chars", String.valueOf(text.length()),
                "images", String.valueOf(images.size()));
    }

    /**
     * 删除 QQ 空间说说：{@code delete_qzone_msg}（op = {@code group.qzonedel}）。
     * 必填 {@code tid}（来自 {@code send_qzone_msg} 或空间列表）；缺了就回结构化「缺参数」。
     */
    private String qzonedel(Cfg c, Args a, Host h) {
        String deny = h.need("group.qzonedel");
        if (deny != null) return denied(c, deny);
        String cf = confirm(c, a, h, "group.qzonedel");
        if (cf != null) return cf;
        String tid = Str.trim(a.str("tid"));
        if (Str.blank(tid)) return missing(c, "tid");
        R r = act(h, "delete_qzone_msg", J.obj("tid", tid));
        if (!Api.ok(r.resp)) return failed(c, "qzonedel", r.resp);
        log(h, "qzone-del chars=" + tid.length());
        return msg(c, "空间已删除", "[group] qzone-del chars={n}", "n", String.valueOf(tid.length()));
    }

    // ---------------------------------------------------------------- 动作

    /** 动作结果：{@code resp} 是 NapCat 响应（权限判定在各动作分支开头，判不过就不发动作）。 */
    private static final class R {
        JsonObject resp;
    }

    /** 发一个 NapCat 动作（权限已由调用方的动作级 op 判定过）。 */
    private static R act(Host h, String action, JsonObject params) {
        R r = new R();
        r.resp = h.napcat().call(action, params == null ? new JsonObject() : params);
        return r;
    }

    private static String denied(Cfg c, String deny) {
        return msg(c, "权限不足", "{deny}", "deny", deny);
    }

    private static String missing(Cfg c, String k) {
        return msg(c, "缺参数", "[group] missing {k}", "k", k);
    }

    /** 参数取值不合法（枚举一类）：回结构化错误，把可用的取值一并告诉模型，别猜一个最近似的。 */
    private static String bad(Cfg c, String k, String v, String opts) {
        return msg(c, "参数不合法", "[group] bad {k}={v}; allowed: {opts}",
                "k", k, "v", Str.oneLine(v), "opts", opts);
    }

    /**
     * 高危动作的<b>确认闸</b>（配置键「高危需确认」，默认开）。
     *
     * <p><b>它不是权限门槛</b>：不为任何身份开或关权限（权限只有管控表那一道），
     * 只要求"不可撤销 / 对外可见"的动作被显式确认一次（{@code confirm=true}）——
     * 与既有 {@code leave+dismiss} 的口径同族：后果重的那一步在代码里看得见。
     * 判定顺序是<b>先权限、后确认</b>：没被授权的人拿到的是 {@code [权限阻断]} 原文，
     * 不会因为"缺确认"而拿不到真正的拒绝理由。</p>
     */
    private static String confirm(Cfg c, Args a, Host h, String op) {
        if (!c.bool("高危需确认", true)) return null;
        if (flag(a, "confirm", false)) return null;
        log(h, op + " blocked=confirm");
        return msg(c, "需二次确认", "[group] op={op} needs confirm=true", "op", op);
    }

    /** 动作失败：未连接时原样返回基板话术，其余走模板（基板的出站拦截原文整句留在 {err} 里）。 */
    private static String failed(Cfg c, String op, JsonObject resp) {
        String err = Api.error(resp);
        if (Api.NOT_CONNECTED.equals(err)) return err;
        return msg(c, "操作失败", "[group] op={op} failed: {err}", "op", op, "err", err);
    }

    // ---------------------------------------------------------------- 小工具

    /** 群号：显式给优先，否则用调用者所在会话的群。 */
    private static long group(Args a, Host h) {
        long gid = a.l("group", a.l("group_id", 0L));
        if (gid > 0L) return gid;
        Caller me = h.caller();
        return me == null ? 0L : me.groupId();
    }

    /** QQ 号：显式给优先，否则回退到调用者自己。 */
    private static long user(Args a, Host h) {
        long qq = a.l("qq", a.l("user", a.l("user_id", 0L)));
        if (qq > 0L) return qq;
        Caller me = h.caller();
        return me == null ? 0L : me.qq();
    }

    private static String memberLine(Cfg c, int i, JsonObject m) {
        return msg(c, "成员行", "{i}|{qq}|{name}|{card}|{role}|{level}",
                "i", String.valueOf(i),
                "qq", String.valueOf(J.l(m, "user_id", 0L)),
                "name", Str.oneLine(J.s(m, "nickname", "")),
                "card", Str.oneLine(J.s(m, "card", "")),
                "role", J.s(m, "role", ""),
                "level", String.valueOf(J.l(m, "level", 0L)));
    }

    private static void tail(StringBuilder sb, Cfg c, int total, int shown) {
        if (total > shown) {
            sb.append(msg(c, "截断说明", "[group] total={n} shown={shown}",
                    "n", String.valueOf(total), "shown", String.valueOf(shown))).append('\n');
        }
    }

    private static String stamp(long ts) {
        if (ts <= 0L) return "";
        return new SimpleDateFormat(DF).format(new Date(ts > 100000000000L ? ts : ts * 1000L));
    }

    /** 取第一个存在的数值字段（上游没给返回结构时按惯例一族试过去；都没有 = 0）。 */
    private static long num(JsonObject o, String[] keys, long def) {
        for (int i = 0; i < keys.length; i++) {
            JsonElement e = J.get(o, keys[i]);
            if (e == null || e.isJsonNull()) continue;
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsLong();
            try {
                return Long.parseLong(Str.trim(e.getAsString()));
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    /** 取第一个非空文本字段（同上）。 */
    private static String txt(JsonObject o, String[] keys) {
        for (int i = 0; i < keys.length; i++) {
            String s = J.s(o, keys[i], "");
            if (!Str.blank(s)) return s;
        }
        return "";
    }

    /** 从 {@code get_group_share_link} 的响应里取链接（字符串直给，对象翻几个常见键；取不到 = 空串）。 */
    private static String linkOf(JsonObject resp) {
        JsonElement d = J.get(resp, "data");
        if (d == null || d.isJsonNull()) return "";
        if (d.isJsonPrimitive()) return Str.trim(d.getAsString());
        if (!d.isJsonObject()) return "";
        JsonObject o = d.getAsJsonObject();
        String s = txt(o, new String[] {"url", "link", "share_link", "shareLink", "data"});
        return Str.trim(s);
    }

    /** 图片地址白名单：只收 http(s)（见 {@link #qzonesend} 的理由）；不行就回结构化错误。 */
    private static String images(Cfg c, Args a, List<String> out) {
        List<String> raw = a.strs("images");
        if (raw == null) return null;
        for (int i = 0; i < raw.size(); i++) {
            String u = Str.trim(raw.get(i));
            if (u.isEmpty()) continue;
            String low = u.toLowerCase();
            if (!low.startsWith("http://") && !low.startsWith("https://")) {
                // 只报序号与长度：URL 常见的 rkey 一类查询串不进日志、不回模型。
                return msg(c, "图片地址不合法", "[group] bad image #{i} chars={n}: only http(s)://",
                        "i", String.valueOf(i + 1), "n", String.valueOf(u.length()));
            }
            out.add(u);
        }
        return null;
    }

    /** 部分可见（{@code ugc_right} 16 / 128）时要给的目标 QQ 列表：非数字 = 结构化错误。 */
    private static String targets(Cfg c, Args a, List<Long> out) {
        List<String> raw = a.strs("targets");
        if (raw == null) return null;
        for (int i = 0; i < raw.size(); i++) {
            String s = Str.trim(raw.get(i));
            if (s.isEmpty()) continue;
            long qq;
            try {
                qq = Long.parseLong(s);
            } catch (Exception e) {
                return bad(c, "targets", s, "QQ number");
            }
            if (qq <= 0L) return bad(c, "targets", s, "QQ number");
            out.add(Long.valueOf(qq));
        }
        return null;
    }

    private static String join(List<?> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(String.valueOf(l.get(i)));
        }
        return sb.toString();
    }

    /** 统一取 {@code data} 段的行数组：数组直接用，对象取它的 list/数组字段。 */
    private static JsonArray rows(JsonObject resp) {
        JsonElement d = J.get(resp, "data");
        if (d == null) return new JsonArray();
        if (d.isJsonArray()) return d.getAsJsonArray();
        if (d.isJsonObject()) {
            JsonObject o = d.getAsJsonObject();
            for (String k : new String[] {"list", "groups", "members", "signed_list", "signed", "items"}) {
                JsonElement e = J.get(o, k);
                if (e != null && e.isJsonArray()) return e.getAsJsonArray();
            }
        }
        return new JsonArray();
    }

    private static boolean flag(Args a, String key, boolean def) {
        if (a == null || !a.has(key)) return def;
        String s = Str.trim(a.str(key)).toLowerCase();
        if ("1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s)) return true;
        if ("0".equals(s) || "false".equals(s) || "no".equals(s) || "off".equals(s)) return false;
        return def;
    }

    // ---------------------------------------------------------------- 配置 / 文案

    /** {@code prompt.md} 是唯一配置源：运行期读，改即生效；读不到就用结构性 ASCII 兜底。 */
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

    /** 文案：{@code prompt.md} 里的同名键优先，缺省用结构性 ASCII 兜底；{@code {k}} 按对填。 */
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
