import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Api;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 待处理申请（工具 {@code request}）：<b>主人手工</b>处理好友申请 / 群邀请。
 *
 * <p>与已有技能「申请审批」的分工：「申请审批」是 {@code on_request} 自动策略钩子（好友申请绝不自动同意、
 * 白名单群邀请才自动同意），本技能是主人事后手工处理的那一半。两者**共用同一份清单**：</p>
 * <ul>
 *   <li>清单主体存在基板运行表 {@code kv} 里，键前缀 {@code pending:}（本技能用
 *       {@code kvList}/{@code kvDelete} 读写，键形如 {@code pending:friend:<flag>}、{@code pending:group:<flag>}）；</li>
 *   <li>同时**复用「申请审批」钩子写的键**（{@code approve_friend_last} / {@code approve_group_last}，
 *       字段名也照它的记录格式：{@code ts/type/qq/group/flag/subtype/comment/action}），不另起一套；</li>
 *   <li>可选：{@code prompt.md} 里配了「系统消息动作」（例如 {@code get_group_system_msg}）时，
 *       再向 NapCat 实时问一次群系统消息，把还没处理的邀请/加群请求并进清单。</li>
 * </ul>
 *
 * <p>权限：每个动作各判自己那个 op（技能管控表 {@code skillctl.json}）——
 * {@code request.pending} / {@code request.approve} / {@code request.reject}，判定放在动作分支开头
 * （没放行 = 未授权 = 不可用；{@code MASTER} 与她本人 {@code SYSTEM} 恒全权）。
 * {@code approve}/{@code reject} 是**机器人自己账号**上的动作，所以发动作前还要判那个 NapCat 动作自己的 op：
 * {@code h.need("napcat.set_friend_add_request")} / {@code h.need("napcat.set_group_add_request")}；
 * {@code pending} 的实时系统消息查询（配置了才发）同样按 {@code napcat.<动作名>} 判。
 * 动作成功后才把该条从 {@code pending:} 清单里移出。</p>
 */
public class RequestSkill implements Skill {

    private static final String NAME = "待处理申请";
    private static final String OPS = "pending, approve, reject";
    /** 本技能自己的待处理清单前缀。 */
    private static final String KV_PREFIX = "pending:";
    /** 复用「申请审批」钩子写的键（与 RequestHook.java 完全一致，不另起一套）。 */
    private static final String[] HOOK_KEYS = {"approve_friend_last", "approve_group_last"};

    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        // 每个动作各一条：技能管控表就是按这些名字放行 / 封禁的
        ops.add("request", "pending", "列待处理的好友申请与群邀请");
        ops.add("request", "approve", "同意一条申请");
        ops.add("request", "reject", "拒绝一条申请");
    }

    @Override
    public Object call(Args a, Host h) {
        Cfg c = load(h);
        try {
            return run(a, h, c);
        } catch (Throwable t) {
            log(h, "op=" + Str.trim(a == null ? "" : a.str("op")) + " exception=" + t);
            return msg(c, "执行异常", "[request] error={err}", "err", String.valueOf(t));
        }
    }

    // ---------------------------------------------------------------- 分发

    private Object run(Args a, Host h, Cfg c) {
        if (a == null) return msg(c, "缺参数", "[request] missing {k}", "k", "op");
        String op = Str.trim(a.str("op")).toLowerCase();
        if (op.isEmpty()) op = "pending";
        if (!c.enabled) return msg(c, "未启用", "[request] disabled");
        if ("pending".equals(op)) return pending(c, h);
        if ("approve".equals(op)) return decide(c, a, h, true);
        if ("reject".equals(op)) return decide(c, a, h, false);
        log(h, "unknown op=" + op);
        return msg(c, "不支持的操作", "[request] unknown op={op}; ops: " + OPS, "op", op, "ops", OPS);
    }

    // ---------------------------------------------------------------- pending

    /** 列出待处理的好友申请与群邀请（kv 清单 + 申请审批钩子的记录 + 可选实时查询）。 */
    private String pending(Cfg c, Host h) {
        // op 判定（动作分支开头）：列待处理清单 = request.pending
        String deny = h.need("request.pending");
        if (deny != null) return deny;

        int cap = c.num("待处理最多", 50);
        List<JsonObject> rows = new ArrayList<JsonObject>();

        // ① 本技能自己的清单：kv 前缀 pending:
        List<JsonObject> kvRows = h.store().kvList(KV_PREFIX, cap);
        if (kvRows != null) {
            for (JsonObject row : kvRows) {
                JsonObject rec = J.obj(J.s(row, "v", ""));
                JsonObject item = normalize(rec, J.s(row, "k", ""), "kv");
                if (item != null && !dup(rows, item)) rows.add(item);
            }
        }

        // ② 「申请审批」钩子写的最近记录（同一批键，不另起一套）
        List<String> used = new ArrayList<String>();
        for (String key : HOOK_KEYS) {
            JsonObject rec = J.obj(h.store().kv(key, ""));
            if (rec == null) continue;
            JsonObject item = normalize(rec, key, "hook");
            if (item == null || dup(rows, item)) continue;
            rows.add(item);
            used.add(key);
        }

        // ③ 可选：向 NapCat 实时问一次系统消息（默认不查，prompt.md 里配「系统消息动作」才查）
        String liveAction = Str.trim(c.str("系统消息动作", ""));
        String liveErr = "";
        if (Str.has(liveAction)) {
            // 这个动作也走技能管控表：op 名 = napcat.<动作名>
            String d = h.need("napcat." + liveAction);
            if (d != null) {
                liveErr = d;
            } else {
                JsonObject resp = h.napcat().call(liveAction, new JsonObject());
                if (!Api.ok(resp)) {
                    liveErr = Api.error(resp);
                } else {
                    liveRows(resp, rows);
                }
            }
        }

        if (rows.isEmpty()) {
            if (Str.has(liveErr)) return liveErr;      // 例如「Napcat 没有连接」，原样给模型
            log(h, "pending empty");
            return msg(c, "没有待处理", "[request] no pending");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, "申请头", "[request] pending={n}",
                "n", String.valueOf(rows.size()))).append('\n');
        int i = 0;
        for (JsonObject r : rows) {
            i++;
            long gid = J.l(r, "group", 0L);
            sb.append(msg(c, "申请行", "{i}|{type}|{qq}|{group}|{flag}|{subtype}|{comment}|{source}",
                    "i", String.valueOf(i),
                    "type", J.s(r, "type", ""),
                    "qq", String.valueOf(J.l(r, "qq", 0L)),
                    "group", gid > 0L ? String.valueOf(gid) : "",
                    "flag", J.s(r, "flag", ""),
                    "subtype", J.s(r, "subtype", ""),
                    "comment", J.s(r, "comment", ""),
                    "source", J.s(r, "src", ""),
                    "k", J.s(r, "k", ""))).append('\n');
        }
        if (!used.isEmpty()) {
            sb.append(msg(c, "钩子记录说明", "[request] hook-rows={n} keys={keys}",
                    "n", String.valueOf(used.size()), "keys", Str.join(used, ","))).append('\n');
        }
        if (Str.has(liveErr)) {
            sb.append(msg(c, "实时查询失败", "[request] live failed: {err}", "err", liveErr)).append('\n');
        }
        log(h, "pending rows=" + rows.size() + " hook=" + used.size()
                + (Str.has(liveErr) ? " live_err=" + liveErr : ""));
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- approve / reject

    /** 同意 / 拒绝一条申请：先判这个动作自己的 op（同意与拒绝各一条），成功后把它从清单里移出。 */
    private String decide(Cfg c, Args a, Host h, boolean approve) {
        // op 判定（动作分支开头）：同意与拒绝是两个动作、各判自己的 op（一个判定点拆成两个）
        String deny = h.need(approve ? "request.approve" : "request.reject");
        if (deny != null) return deny;
        String op = approve ? "approve" : "reject";
        String type = Str.trim(a.str("type")).toLowerCase();
        if (type.isEmpty()) return missing(c, "type");
        String flag = Str.trim(a.str("flag"));
        if (Str.blank(flag)) return missing(c, "flag");

        String action;
        JsonObject params;
        String kvType;
        if ("friend".equals(type)) {
            kvType = "friend";
            action = "set_friend_add_request";
            params = J.obj("flag", flag, "approve", approve, "remark", Str.oneLine(a.str("remark")));
        } else if ("group".equals(type) || "invite".equals(type) || "join".equals(type)) {
            kvType = "group";
            action = "set_group_add_request";
            String sub = Str.trim(a.str("subtype"));
            if (sub.isEmpty()) sub = Str.trim(a.str("sub"));
            if (sub.isEmpty()) {
                sub = "invite".equals(type) ? "invite"
                        : ("join".equals(type) ? "add" : c.str("默认加群子类型", "add"));
            }
            params = J.obj("flag", flag, "sub_type", sub, "approve", approve, "reason", Str.oneLine(a.str("reason")));
        } else {
            return msg(c, "类型无法识别", "[request] unknown type={type}; use friend|group",
                    "type", type, "ops", OPS);
        }

        // 发出去的 NapCat 动作自己那一层：op 名 = napcat.<动作名>
        String napcatDeny = h.need("napcat." + action);
        if (napcatDeny != null) return denied(c, napcatDeny);

        JsonObject resp = h.napcat().call(action, params);
        if (!Api.ok(resp)) return failed(c, op, resp);

        String key = KV_PREFIX + kvType + ":" + flag;
        int removed = h.store().kvDelete(key, false);
        log(h, op + " type=" + kvType + " flag=" + flag + " removed=" + removed);
        String out = approve
                ? msg(c, "同意已执行", "[request] approved {type} flag={flag}", "type", kvType, "flag", flag)
                : msg(c, "拒绝已执行", "[request] rejected {type} flag={flag}", "type", kvType, "flag", flag);
        if (removed > 0) {
            out = out + "\n" + msg(c, "清单已移出", "[request] removed {key}", "key", key);
        }
        return out;
    }

    // ---------------------------------------------------------------- 清单记录

    /** 把一条申请记录统一成同一形状（字段名照「申请审批」钩子的记录格式）。 */
    private static JsonObject normalize(JsonObject rec, String key, String src) {
        if (rec == null) return null;
        String type = Str.trim(J.s(rec, "type", ""));
        String flag = Str.trim(J.s(rec, "flag", ""));
        if (type.isEmpty() && flag.isEmpty()) return null;
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("qq", J.l(rec, "qq", 0L));
        o.addProperty("group", J.l(rec, "group", 0L));
        o.addProperty("flag", flag);
        o.addProperty("subtype", Str.trim(J.s(rec, "subtype", "")));
        o.addProperty("comment", Str.oneLine(Str.cut(J.s(rec, "comment", ""), 120)));
        o.addProperty("ts", J.l(rec, "ts", 0L));
        o.addProperty("action", J.s(rec, "action", ""));
        o.addProperty("k", Str.nz(key));
        o.addProperty("src", Str.nz(src));
        return o;
    }

    /** 同一条申请（type + flag；flag 为空时退化成 type + qq + group）不重复列。 */
    private static boolean dup(List<JsonObject> rows, JsonObject row) {
        String key = idOf(row);
        for (JsonObject r : rows) if (key.equals(idOf(r))) return true;
        return false;
    }

    private static String idOf(JsonObject r) {
        String type = j(J.s(r, "type", ""));
        String flag = j(J.s(r, "flag", ""));
        if (!flag.isEmpty()) return type + "|" + flag;
        return type + "|" + J.l(r, "qq", 0L) + "|" + J.l(r, "group", 0L);
    }

    private static String j(String s) { return Str.trim(s).toLowerCase(); }

    /** 实时系统消息 → 清单行（群邀请 + 加群请求）。 */
    private static void liveRows(JsonObject resp, List<JsonObject> rows) {
        JsonObject d = J.sub(resp, "data");
        if (d == null) return;
        addLive(rows, J.list(d, "invited_requests"), "group", "invite");
        addLive(rows, J.list(d, "join_requests"), "group", "add");
    }

    private static void addLive(List<JsonObject> rows, JsonArray arr, String type, String sub) {
        if (arr == null) return;
        for (JsonElement e : arr) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            JsonObject rec = new JsonObject();
            rec.addProperty("type", type);
            rec.addProperty("subtype", Str.trim(J.s(o, "sub_type", sub)));
            rec.addProperty("flag", J.s(o, "flag", J.s(o, "request_id", "")));
            rec.addProperty("qq", J.l(o, "invitor_uin",
                    J.l(o, "requester_uin", J.l(o, "user_id", 0L))));
            rec.addProperty("group", J.l(o, "group_id", 0L));
            rec.addProperty("comment", J.s(o, "comment", ""));
            long sec = J.l(o, "time", 0L);
            rec.addProperty("ts", sec > 0L ? sec * 1000L : System.currentTimeMillis());
            JsonObject row = normalize(rec, "", "live");
            if (row != null && !dup(rows, row)) rows.add(row);
        }
    }

    // ---------------------------------------------------------------- 动作 / 权限

    private static String denied(Cfg c, String deny) {
        return msg(c, "权限不足", "{deny}", "deny", deny);
    }

    private static String missing(Cfg c, String k) {
        return msg(c, "缺参数", "[request] missing {k}", "k", k);
    }

    private static String failed(Cfg c, String op, JsonObject resp) {
        String err = Api.error(resp);
        if (Api.NOT_CONNECTED.equals(err)) return err;
        return msg(c, "操作失败", "[request] op={op} failed: {err}", "op", op, "err", err);
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
