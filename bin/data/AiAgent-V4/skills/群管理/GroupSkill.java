import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
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
 * 群管理（工具 {@code group}）：群查询 + 群管动作，动作一律经 {@code h.napcat()} 发出。
 *
 * <p>权限：技能管控表只管控技能（op），工具入口由基板按工具级 op / 动作级 op 放行；
 * 每个动作分支在动第一件事之前<b>各自判一次自己的 op</b>（{@code group.list} / {@code group.ban} …），
 * 判不过就把基板的拒绝原文原样回给模型、<b>动作不发</b>；技能里没有任何硬编码门槛。
 * 退群且 {@code dismiss=true}（解散群）时配置键「解散需主人」会在发动作前<b>再过一遍同一个 op</b>
 * （{@link #leave}）：解散不可撤销，但这个"不可撤销"不再由好感度大小的门槛表达，
 * 也不再有单独的主人授权条目 —— 旧写法调 {@code h.gate("leave.dismiss")} 会按 {@code favor.of(qq)}
 * 与 {@code AFFECTION:<N>} 判权，与"好感度只决定回复热情度"直接冲突，已删除。</p>
 *
 * <p>文案 / 阈值 / 输出模板全在本技能自己的 {@code prompt.md}（运行期读，改即生效）：
 * Java 里只有 op 名、动作名、参数名、配置键与结构性 ASCII 兜底。
 * NapCat 未连接时动作返回基板话术「Napcat 没有连接」，任何异常都被吞掉并换成说明。</p>
 */
public class GroupSkill implements Skill {

    private static final String NAME = "群管理";
    private static final String OPS = "list, members, member, ban, kick, muteall, setadmin, setname, setcard, leave";
    private static final String DF = "yyyy-MM-dd HH:mm";

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

    /** 动作失败：未连接时原样返回基板话术，其余走模板。 */
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

    /** 统一取 {@code data} 段的行数组：数组直接用，对象取它的 list/数组字段。 */
    private static JsonArray rows(JsonObject resp) {
        JsonElement d = J.get(resp, "data");
        if (d == null) return new JsonArray();
        if (d.isJsonArray()) return d.getAsJsonArray();
        if (d.isJsonObject()) {
            JsonObject o = d.getAsJsonObject();
            for (String k : new String[] {"list", "groups", "members"}) {
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
