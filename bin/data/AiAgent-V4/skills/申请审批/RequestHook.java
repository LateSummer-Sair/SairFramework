import com.google.gson.JsonObject;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Api;
import sair.v4.qq.Ev;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;

/**
 * 申请审批（{@code on_request} 钩子）：好友申请**只记录**（<b>绝不自动同意</b>；通知要显式开 {@code 通知主人: true}）；
 * 群邀请只有群号在技能 {@code prompt.md} 的白名单里才自动同意，否则也只记录（同样默认不通知）。
 *
 * <p>记录走基板运行表 {@code kv}（不污染六库）；通知文案全部读自技能自己的 {@code prompt.md}，
 * 文案缺失就不发那条通知，{@code 通知主人} 没开（内置默认 {@code false}）也不发 —— 技能里没有写死的用户文案。</p>
 */
public class RequestHook implements Hooked {

    private static final String NAME = "申请审批";
    private static final String KV_FRIEND_LAST = "approve_friend_last";
    private static final String KV_FRIEND_COUNT = "approve_friend_count";
    private static final String KV_GROUP_LAST = "approve_group_last";
    private static final String KV_GROUP_COUNT = "approve_group_count";

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        if (!"on_request".equals(hook) || payload == null || h == null) return;
        try {
            Cfg c = load(h);
            if (!c.enabled) return;
            Ev e = Ev.parse(payload);
            String type = Str.trim(e.requestType());
            if ("friend".equalsIgnoreCase(type)) {
                friend(c, e, h, payload);
            } else if ("group".equalsIgnoreCase(type)) {
                group(c, e, h, payload);
            } else {
                log(h, "忽略不认识的申请类型：" + type + "（只处理 friend / group）");
            }
        } catch (Throwable t) {
            log(h, "处理异常：" + t);
        }
    }

    // ---------------------------------------------------------------- 好友申请

    private void friend(Cfg c, Ev e, Host h, JsonObject payload) {
        String text = Str.blank(c.friendNotice) ? "" : render(c.friendNotice, e, "");
        String action;
        if (text.isEmpty() || !c.notifyMaster) {
            action = "recorded";                   // 只记录：通知文案为空，或按配置刻意不通知
        } else if (notify(c, h, text)) {
            action = "notified";
        } else {
            action = h.napcatConnected() ? "notify_failed" : "offline";
        }
        record(h, KV_FRIEND_LAST, KV_FRIEND_COUNT, e, "friend", action, 0L, false);
        payload.addProperty("_handled", true);
        log(h, "好友申请 qq=" + e.userId() + " flag=" + e.flag() + " → " + action + "（不自动同意）");
    }

    // ---------------------------------------------------------------- 群邀请

    private void group(Cfg c, Ev e, Host h, JsonObject payload) {
        long gid = e.groupId();
        String sub = Str.trim(e.subType());
        if (sub.isEmpty()) sub = "add";
        String action;
        final boolean white = gid > 0L && c.autoGroups.contains(Long.valueOf(gid));
        if (white) {
            if (!h.napcatConnected()) {
                action = "offline";
                log(h, "群 " + gid + " 在白名单里，但 " + Api.NOT_CONNECTED + "，无法自动同意");
            } else {
                JsonObject r = h.napcat().setGroupAddRequest(e.flag(), sub, true, "");
                if (Api.ok(r)) {
                    action = "auto_accept";
                    log(h, "群 " + gid + " 在白名单里，已自动同意（flag=" + e.flag() + "）");
                    if (!Str.blank(c.acceptNotice)) notify(c, h, render(c.acceptNotice, e, sub));
                } else {
                    action = "accept_failed:" + Api.error(r);
                    log(h, "自动同意失败：" + Api.error(r));
                }
            }
        } else {
            String text = Str.blank(c.groupNotice) ? "" : render(c.groupNotice, e, sub);
            if (text.isEmpty() || !c.notifyMaster) {
                action = "recorded";               // 只记录：通知文案为空，或按配置刻意不通知
            } else if (notify(c, h, text)) {
                action = "notified";
            } else {
                action = h.napcatConnected() ? "notify_failed" : "offline";
            }
            log(h, "群邀请 群=" + gid + " qq=" + e.userId() + " → " + action + "（不在白名单）");
        }
        record(h, KV_GROUP_LAST, KV_GROUP_COUNT, e, "group", action, gid, white);
        payload.addProperty("_handled", true);
    }

    // ---------------------------------------------------------------- 通知 / 记录

    /** 私聊通知主人：{@code 通知主人} 没开（内置默认 {@code false}）、没配 masterQQ、没连 NapCat、发失败都返回 false。 */
    private static boolean notify(Cfg c, Host h, String text) {
        if (!c.notifyMaster || Str.blank(text)) return false;
        long master = h.conf() == null ? 0L : h.conf().masterQQ();
        if (master <= 0L) {
            log(h, "配置里没有 masterQQ，通知发不出去");
            return false;
        }
        if (!h.napcatConnected()) return false;
        JsonObject r = h.napcat().sendPrivateMsg(master, text);
        if (!Api.ok(r)) {
            log(h, "通知主人失败：" + Api.error(r));
            return false;
        }
        return true;
    }

    private void record(Host h, String lastKey, String countKey, Ev e, String type,
                        String action, long groupId, boolean whitelisted) {
        try {
            JsonObject rec = new JsonObject();
            rec.addProperty("ts", System.currentTimeMillis());
            rec.addProperty("type", type);
            rec.addProperty("qq", e.userId());
            if (groupId > 0L) rec.addProperty("group", groupId);
            rec.addProperty("flag", e.flag());
            if (Str.has(e.subType())) rec.addProperty("subtype", e.subType());
            rec.addProperty("comment", Str.cut(Str.oneLine(e.comment()), 200));
            rec.addProperty("action", action);
            rec.addProperty("whitelisted", whitelisted);
            h.store().kvSet(lastKey, J.json(rec));
            // 把申请投进"待处理申请"技能读的队列（键格式由「待处理申请」技能定义：
            // pending:<type>:<flag>），这样主人/AI 才有真队列可查、可逐条处置。
            if (Str.has(e.flag()) && Str.has(type)) {
                h.store().kvSet("pending:" + type + ":" + e.flag(), J.json(rec));
            }
            long n = 0L;
            try {
                n = Long.parseLong(h.store().kv(countKey, "0").trim());
            } catch (Exception ignored) {
            }
            h.store().kvSet(countKey, String.valueOf(n + 1L));
        } catch (Throwable t) {
            log(h, "落库失败：" + t);
        }
    }

    // ---------------------------------------------------------------- 配置

    private static Cfg load(Host h) {
        Cfg c = new Cfg();
        try {
            File dir = new File(h.conf().skillsDir(), NAME);
            String text = Fs.read(new File(dir, "prompt.md"), "");
            if (Str.blank(text)) return c;
            for (String raw : text.split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int i = line.indexOf(':');
                if (i < 0) i = line.indexOf('：');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                if ("启用".equals(k)) c.enabled = truthy(v);
                else if ("自动同意群".equals(k)) c.autoGroups = longs(v);
                else if ("通知主人".equals(k)) c.notifyMaster = truthy(v);
                else if ("好友通知".equals(k)) c.friendNotice = v;
                else if ("群邀请通知".equals(k)) c.groupNotice = v;
                else if ("自动同意通知".equals(k)) c.acceptNotice = v;
            }
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static final class Cfg {
        boolean enabled = false;
        boolean notifyMaster = false;
        Set<Long> autoGroups = new HashSet<Long>();
        String friendNotice = "";
        String groupNotice = "";
        String acceptNotice = "";
    }

    private static String render(String tpl, Ev e, String sub) {
        String s = tpl;
        s = s.replace("{name}", Str.nz(e.senderName()));
        s = s.replace("{qq}", String.valueOf(e.userId()));
        s = s.replace("{group}", String.valueOf(e.groupId()));
        s = s.replace("{subtype}", Str.nz(sub));
        s = s.replace("{flag}", Str.nz(e.flag()));
        s = s.replace("{comment}", Str.nz(e.comment()));
        return s;
    }

    private static Set<Long> longs(String v) {
        Set<Long> out = new HashSet<Long>();
        if (Str.blank(v)) return out;
        for (String p : v.split("[,，\\s]+")) {
            try {
                out.add(Long.valueOf(Long.parseLong(p.trim())));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static boolean truthy(String v) {
        String s = Str.trim(v).toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "是".equals(s);
    }

    private static void log(Host h, String msg) {
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.warn("[" + NAME + "] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
