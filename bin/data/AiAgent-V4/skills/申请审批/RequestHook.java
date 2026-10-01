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
import sair.v4.skill.FavorView;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;

/**
 * 申请审批（{@code on_request} 钩子）。三条策略，其余一律**只记录**：
 *
 * <ol>
 *   <li><b>群邀请 {@code sub_type=invite}</b>（三条"或"，先判主人那一支）：① 邀请人就是主人
 *       （{@code masterQQ}，开关 {@code 自动同意群-主人邀请} 出厂 {@code true}）⇒ 自动同意；
 *       ② 邀请人的<b>当前好感度</b> ≥ {@code 群邀请自动同意好感度}（出厂 {@code 300} = 朋友档；
 *       {@code 0}/负数 = 关）⇒ 自动同意；③ 群号在 {@code 自动同意群} 白名单里 ⇒ 自动同意（旧行为）。
 *       三条都不成立 ⇒ 只记录。好感度一律现读 {@link FavorView#of(long)}，技能自己不记账。</li>
 *   <li><b>好友申请</b>：申请人好感度 ≥ {@code 好友自动同意好感度}（出厂 {@code 100} = 眼熟档；
 *       {@code 0}/负数 = 关）⇒ 自动同意；否则**照旧只记录**（与改前逐字一致）。</li>
 *   <li><b>{@code sub_type=add}（别人申请进她管的群）一律不自动同意</b>，只记录 ——
 *       {@code invite} 才是"邀请她进群"，{@code add} 是"陌生人要进她的群"，自动同意等于开活洞。</li>
 * </ol>
 *
 * <p><b>反馈</b>：两种自动同意之后各私聊主人一条（{@code 同意后通知主人} 出厂 {@code true}；
 * 文案 {@code 同意群通知} / {@code 同意好友通知}）。<b>发送失败只记日志、不影响审批结果</b>
 * —— 审批已经发生。</p>
 *
 * <p>记录走基板运行表 {@code kv}（不污染六库）；通知文案全部读自技能自己的 {@code prompt.md}，
 * 文案缺失就不发那条通知，{@code 通知主人} 没开（内置默认 {@code false}）也不发
 * —— 技能里没有写死的用户文案。</p>
 */
public class RequestHook implements Hooked {

    private static final String NAME = "申请审批";
    private static final String KV_FRIEND_LAST = "approve_friend_last";
    private static final String KV_FRIEND_COUNT = "approve_friend_count";
    private static final String KV_GROUP_LAST = "approve_group_last";
    private static final String KV_GROUP_COUNT = "approve_group_count";

    /** {@code 好友自动同意好感度} 的出厂默认（= 眼熟档，见 {@code sair.v4.auth.Favor} 档位表）。 */
    private static final int DEF_FRIEND_AUTO_FAVOR = 100;
    /** {@code 群邀请自动同意好感度} 的出厂默认（= 朋友档）。 */
    private static final int DEF_GROUP_INVITE_FAVOR = 300;

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
        long qq = e.userId();
        double fav = favorOf(h, qq);
        int need = c.friendAutoFavor;
        String action;
        if (need > 0 && fav >= (double) need) {
            // 好感度达标：自动同意 + 反馈主人（审批已经发生，通知失败绝不影响审批结果）。
            if (!h.napcatConnected()) {
                action = "offline";
                log(h, "好友申请 qq=" + qq + " 到了好感度门槛（" + need + "），但 "
                        + Api.NOT_CONNECTED + "，无法自动同意");
            } else {
                JsonObject r = h.napcat().setFriendAddRequest(e.flag(), true, "");
                if (Api.ok(r)) {
                    action = "auto_accept";
                    String nt = notifyAccept(c, h, c.acceptFriendNotice, e, "",
                            favorStr(fav), String.valueOf(need), favorLevel(h, fav));
                    log(h, "好友申请 qq=" + qq + " flag=" + e.flag() + " favor=" + favorStr(fav)
                            + " → 自动同意（好感度 " + favorStr(fav) + " ≥ " + need + "）通知=" + nt);
                } else {
                    action = "accept_failed:" + Api.error(r);
                    log(h, "自动同意好友申请失败：" + Api.error(r));
                }
            }
        } else {
            // 没到门槛 / 关了：与改前逐字一致 —— 只记录（或按 通知主人 提醒一句），绝不自动同意。
            String text = Str.blank(c.friendNotice) ? "" : render(c.friendNotice, e, "");
            if (text.isEmpty() || !c.notifyMaster) {
                action = "recorded";               // 只记录：通知文案为空，或按配置刻意不通知
            } else if (notify(c, h, text)) {
                action = "notified";
            } else {
                action = h.napcatConnected() ? "notify_failed" : "offline";
            }
            log(h, "好友申请 qq=" + qq + " flag=" + e.flag() + " → " + action + "（不自动同意）");
        }
        record(h, KV_FRIEND_LAST, KV_FRIEND_COUNT, e, "friend", action, 0L, false);
        payload.addProperty("_handled", true);
    }

    // ---------------------------------------------------------------- 群邀请 / 加群申请

    private void group(Cfg c, Ev e, Host h, JsonObject payload) {
        long gid = e.groupId();
        String sub = Str.trim(e.subType());
        if (sub.isEmpty()) sub = "add";
        // ★ 精度修正：只有 sub_type=invite（邀请她进群）才在自动同意之列；
        //   sub_type=add（别人申请进她管的群）一律不自动同意 —— 陌生人进来是活洞。
        final boolean invite = "invite".equalsIgnoreCase(sub);
        final boolean white = gid > 0L && c.autoGroups.contains(Long.valueOf(gid));
        final long qq = e.userId();
        final long master = masterQQ(h);
        final boolean byMaster = qq > 0L && master > 0L && qq == master;
        // 好感度现读（Host.favor()），技能自己不记账；读不到按 0（= 任何阈值都不到）。
        final double inviterFavor = invite ? favorOf(h, qq) : 0.0D;
        final int gNeed = c.groupInviteFavor;
        // 三条"或"：主人亲自邀请（开关开着）/ 邀请人好感度达标 / 群在白名单里。
        // 主人那一支优先（事实行要能说清是哪一支）；主人自己被 {@code 自动同意群-主人邀请} 关掉时，
        // 不再用他的好感度兜一遍 —— 那个开关就是"别自动同意他的邀请"。
        final boolean masterInvite = invite && byMaster && c.masterInvite;
        final boolean byFavor = invite && !byMaster && gNeed > 0 && inviterFavor >= (double) gNeed;
        final boolean accept = masterInvite || byFavor || (invite && white);
        String action;
        if (accept) {
            final String why = masterInvite ? "主人邀请"
                    : (byFavor ? "好感度 " + favorStr(inviterFavor) + " ≥ " + gNeed : "白名单");
            if (!h.napcatConnected()) {
                action = "offline";
                log(h, "群 " + gid + " 该自动同意（" + why + "），但 "
                        + Api.NOT_CONNECTED + "，无法自动同意");
            } else {
                JsonObject r = h.napcat().setGroupAddRequest(e.flag(), sub, true, "");
                if (Api.ok(r)) {
                    action = "auto_accept";
                    // 新键「同意群通知」优先；留空时回落到旧键「自动同意通知」（老配置口径不变）。
                    String tpl = Str.has(c.acceptGroupNotice) ? c.acceptGroupNotice : c.acceptNotice;
                    // {need} 只在那条"阈值真的是本次判据"的分支给值；主人邀请 / 白名单没有阈值 ⇒ 空。
                    String nt = notifyAccept(c, h, tpl, e, sub,
                            favorStr(inviterFavor), byFavor ? String.valueOf(gNeed) : "",
                            favorLevel(h, inviterFavor));
                    if (masterInvite) {
                        log(h, "群邀请 群=" + gid + " qq=" + qq
                                + " → 自动同意（主人邀请）通知=" + nt);
                    } else if (byFavor) {
                        log(h, "群邀请 群=" + gid + " qq=" + qq + " favor=" + favorStr(inviterFavor)
                                + " → 自动同意（好感度 " + favorStr(inviterFavor) + " ≥ " + gNeed
                                + "）通知=" + nt);
                    } else {
                        log(h, "群 " + gid + " 在白名单里，已自动同意（flag=" + e.flag() + "）通知=" + nt);
                    }
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
            if (invite) {
                log(h, "群邀请 群=" + gid + " qq=" + qq + " → " + action + "（不在白名单）");
            } else {
                log(h, "群申请 群=" + gid + " qq=" + qq + " subtype=" + sub + " → " + action
                        + "（不是群邀请，不自动同意）");
            }
        }
        record(h, KV_GROUP_LAST, KV_GROUP_COUNT, e, "group", action, gid, white);
        payload.addProperty("_handled", true);
    }

    // ---------------------------------------------------------------- 通知 / 记录

    /**
     * 自动同意之后的反馈（{@code 同意后通知主人} 出厂 {@code true}）。
     *
     * <p><b>绝不影响审批结果</b>：本方法返回的只是"通知这一支"的结果（{@code ok}/{@code off}/
     * {@code failed}），调用点在审批成功之后才调它，成败都不改 {@code action}。</p>
     *
     * @return {@code ok} 已发出 / {@code off} 开关没开或文案留空 / {@code failed} 发送失败（已记日志）
     */
    private static String notifyAccept(Cfg c, Host h, String tpl, Ev e, String sub,
                                       String favor, String need, String level) {
        if (!c.acceptNotify) return "off";
        if (Str.blank(tpl)) return "off";
        String text = render(tpl, e, sub, favor, need, level);
        return sendMaster(h, text) ? "ok" : "failed";
    }

    /** 私聊通知主人：{@code 通知主人} 没开（内置默认 {@code false}）、没配 masterQQ、没连 NapCat、发失败都返回 false。 */
    private static boolean notify(Cfg c, Host h, String text) {
        if (!c.notifyMaster || Str.blank(text)) return false;
        return sendMaster(h, text);
    }

    /** 真正发出去那一跳（无开关，开关由调用点判）。 */
    private static boolean sendMaster(Host h, String text) {
        if (Str.blank(text)) return false;
        long master = masterQQ(h);
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

    // ---------------------------------------------------------------- 宿主能力

    private static long masterQQ(Host h) {
        try {
            return h.conf() == null ? 0L : h.conf().masterQQ();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 读某个 QQ 的好感度（宿主没给好感度面 / 出任何事都按 0 = 初识档，fail-safe 不自动同意）。 */
    private static double favorOf(Host h, long qq) {
        try {
            FavorView f = h.favor();
            return f == null ? 0.0D : f.of(qq);
        } catch (Throwable t) {
            return 0.0D;
        }
    }

    /** 好感度档位名（档位表见 {@code sair.v4.auth.Favor#levelName}）；读不到就空串。 */
    private static String favorLevel(Host h, double v) {
        try {
            FavorView f = h.favor();
            return f == null ? "" : Str.nz(f.levelName(v));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 好感度显示：整数值不打小数点（{@code 250} 而不是 {@code 250.0}）。 */
    private static String favorStr(double v) {
        long r = Math.round(v);
        if (Math.abs(v - (double) r) < 0.0001D) return String.valueOf(r);
        return String.valueOf(v);
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
                else if ("自动同意群-主人邀请".equals(k)) c.masterInvite = truthy(v);
                else if ("通知主人".equals(k)) c.notifyMaster = truthy(v);
                else if ("同意后通知主人".equals(k)) c.acceptNotify = truthy(v);
                // 留空 = 0 = 关（最保守：这两个键开着就会自动放人进来，绝不把"没写值"当默认开）
                else if ("好友自动同意好感度".equals(k)) {
                    c.friendAutoFavor = Str.blank(v) ? 0 : num(v, DEF_FRIEND_AUTO_FAVOR);
                }
                else if ("群邀请自动同意好感度".equals(k)) {
                    c.groupInviteFavor = Str.blank(v) ? 0 : num(v, DEF_GROUP_INVITE_FAVOR);
                }
                else if ("好友通知".equals(k)) c.friendNotice = v;
                else if ("群邀请通知".equals(k)) c.groupNotice = v;
                else if ("自动同意通知".equals(k)) c.acceptNotice = v;
                else if ("同意群通知".equals(k)) c.acceptGroupNotice = v;
                else if ("同意好友通知".equals(k)) c.acceptFriendNotice = v;
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
        /** {@code 自动同意群-主人邀请}：出厂 {@code true} = 主人自己拉她进群就同意。 */
        boolean masterInvite = true;
        /** {@code 好友自动同意好感度}：出厂 100，{@code <=0} = 关（绝不自动同意好友）。 */
        int friendAutoFavor = DEF_FRIEND_AUTO_FAVOR;
        /** {@code 群邀请自动同意好感度}：出厂 300，{@code <=0} = 关（不看好感度，只看主人/白名单）。 */
        int groupInviteFavor = DEF_GROUP_INVITE_FAVOR;
        /** {@code 同意后通知主人}：出厂 {@code true} = 自动同意之后私聊主人一条。 */
        boolean acceptNotify = true;
        /** {@code 同意群通知}：群自动同意后的文案（留空回落到旧键 {@code 自动同意通知}）。 */
        String acceptGroupNotice = "";
        /** {@code 同意好友通知}：好友自动同意后的文案（留空 = 不发）。 */
        String acceptFriendNotice = "";
    }

    private static String render(String tpl, Ev e, String sub) {
        return render(tpl, e, sub, "", "", "");
    }

    /**
     * 占位符渲染。{@code favor}/{@code need}/{@code level} 只有自动同意文案用；
     * 老调用点走 3 参重载传空串 ⇒ 老文案（不含这三个占位符）与改前逐字一致。
     */
    private static String render(String tpl, Ev e, String sub,
                                String favor, String need, String level) {
        String s = tpl;
        s = s.replace("{name}", Str.nz(e.senderName()));
        s = s.replace("{qq}", String.valueOf(e.userId()));
        s = s.replace("{group}", String.valueOf(e.groupId()));
        s = s.replace("{subtype}", Str.nz(sub));
        s = s.replace("{flag}", Str.nz(e.flag()));
        s = s.replace("{comment}", Str.nz(e.comment()));
        s = s.replace("{favor}", Str.nz(favor));
        s = s.replace("{need}", Str.nz(need));
        s = s.replace("{level}", Str.nz(level));
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

    private static int num(String v, int def) {
        try {
            return Integer.parseInt(Str.trim(v));
        } catch (Exception ignored) {
            return def;
        }
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
