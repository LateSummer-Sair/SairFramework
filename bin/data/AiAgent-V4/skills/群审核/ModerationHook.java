import com.google.gson.JsonObject;

import java.util.List;

import sair.v4.auth.Caller;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Api;
import sair.v4.qq.Ev;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;

/**
 * 群审核（{@code on_message} 钩子）：逐条判定群消息，命中违规词就禁言 + 警告。
 *
 * <p>词表与文案全在技能自己的 {@code prompt.md} 里（运行期读，改完要显式重载）；技能里没有任何写死的用户文案 ——
 * 配置缺项就是"这项不生效"。</p>
 *
 * <p><b>按群配置</b>：生效配置 = {@code prompt.md} 的全局默认 + {@code kv} 里 {@code modcfg:<群号>}
 * 的按群覆盖（键与 V3 {@code app_state} 的 {@code modcfg:<群号>} 对齐，字段映射见 {@link ModCfg}）。
 * 一个群想换词表/时长/豁免/文案，只改那一个群的 JSON，不影响别的群；运行期改的入口是工具
 * {@code moderation} 的 {@code op=config}（{@link ModerationSkill}，写操作要过归属硬判：
 * 主人、或"目标群就是我说话的群且我是群主/管理员"）。</p>
 *
 * <p><b>执法前先判定"机器人自己在这个群有没有执法权"</b>（{@code h.selfIsGroupAdmin(群号)}，
 * 见下面 {@code on} 里的分支）：机器人不是本群管理员（或 NapCat 未连接）只记录不执法。
 * 这里判的是<b>群内角色</b>（机器人是不是管理员），不是调用者的权限档位 —— 旧写法
 * {@code h.auth().allow("napcat.set_group_ban", "GROUP_MASTER", false, h.caller())} 这个方法在
 * 权限面换成 ACL 资源模型后已不存在，别再照它写。命中即置 {@code payload._handled = true}，
 * 基板不再自动回复这条消息。</p>
 */
public class ModerationHook implements Hooked {

    private static final String KV_LAST = "mod_last";
    private static final String KV_COUNT = "mod_count";

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        try {
            if (!"on_message".equals(hook) || payload == null || h == null) return;
            Ev e = Ev.parse(payload);
            if (!e.isGroup() || e.groupId() <= 0L || e.userId() <= 0L) return;

            // 生效配置：全局默认 + 这个群的按群覆盖（没有按群配置就是全局默认）
            ModCfg.Cfg c = ModCfg.effective(h, e.groupId());
            if (!c.enabled || c.words.isEmpty()) return;

            Caller caller = h.caller();
            if (caller != null && caller.master()) return;                        // 主人不审核
            if (c.skipGroups.contains(Long.valueOf(e.groupId()))) return;
            if (ModCfg.contains(c.skipRoles, e.senderRole())) return;
            if (c.users.contains(Long.valueOf(e.userId()))) return;               // 按群/全局豁免 QQ
            String text = e.plainText();
            if (Str.blank(text)) return;
            String word = hit(c.words, text);
            if (word == null) return;

            // ---- 命中：先判定"机器人自己在这个群有没有执法权"，再决定怎么处置 ----
            String action;
            boolean allowed = h.selfIsGroupAdmin(e.groupId());
            if (!allowed) {
                action = "denied";
                log(h, "命中「" + word + "」但机器人在本群不是管理员（或 NapCat 未连接），只记录不执法："
                        + e.senderName() + "(" + e.userId() + ") 群 " + e.groupId());
            } else if (!h.napcatConnected()) {
                action = "offline";
                log(h, "命中「" + word + "」但 " + Api.NOT_CONNECTED + "，只记录日志："
                        + e.senderName() + "(" + e.userId() + ") 群 " + e.groupId());
            } else {
                JsonObject r = h.napcat().setGroupBan(e.groupId(), e.userId(), c.mute);
                if (Api.ok(r)) {
                    action = "muted";
                    log(h, "已禁言 " + c.mute + " 秒：" + e.senderName() + "(" + e.userId()
                            + ") 群 " + e.groupId() + "，命中「" + word + "」（配置来源 " + c.source + "）");
                    if (Str.has(c.warn)) {
                        JsonObject w = h.napcat().sendGroupMsg(e.groupId(), render(c.warn, e, word, c.mute));
                        if (!Api.ok(w)) log(h, "警告发送失败：" + Api.error(w));
                    }
                } else {
                    action = "failed:" + Api.error(r);
                    log(h, "禁言失败（" + Api.error(r) + "）：" + e.userId() + " 群 " + e.groupId());
                }
            }

            record(h, e, word, action, c);
            payload.addProperty("_handled", true);                                 // 基板不再自动回复
        } catch (Throwable t) {
            log(h, "处理异常：" + t);
        }
    }

    // ---------------------------------------------------------------- 记录

    private void record(Host h, Ev e, String word, String action, ModCfg.Cfg c) {
        try {
            JsonObject rec = new JsonObject();
            rec.addProperty("ts", System.currentTimeMillis());
            rec.addProperty("group", e.groupId());
            rec.addProperty("qq", e.userId());
            rec.addProperty("name", e.senderName());
            rec.addProperty("word", word);
            rec.addProperty("action", action);
            rec.addProperty("mute", c.mute);
            rec.addProperty("cfg", c.source);                                      // 用的是全局还是按群配置
            rec.addProperty("text", Str.cut(Str.oneLine(e.plainText()), 120));
            h.store().kvSet(KV_LAST, J.json(rec));
            long n = 0L;
            try {
                n = Long.parseLong(h.store().kv(KV_COUNT, "0").trim());
            } catch (Exception ignored) {
            }
            h.store().kvSet(KV_COUNT, String.valueOf(n + 1L));
        } catch (Throwable t) {
            log(h, "落库失败：" + t);
        }
    }

    // ---------------------------------------------------------------- 判定

    /** 命中第一个违规词就返回它（大小写不敏感）。 */
    private static String hit(List<String> words, String text) {
        String low = text.toLowerCase();
        for (String w : words) {
            if (Str.blank(w)) continue;
            if (low.indexOf(w.toLowerCase()) >= 0) return w;
        }
        return null;
    }

    private static String render(String tpl, Ev e, String word, int mute) {
        String s = tpl;
        s = s.replace("{name}", Str.nz(e.senderName()));
        s = s.replace("{qq}", String.valueOf(e.userId()));
        s = s.replace("{group}", String.valueOf(e.groupId()));
        s = s.replace("{word}", Str.nz(word));
        s = s.replace("{mute}", String.valueOf(mute));
        return s;
    }

    private static void log(Host h, String msg) {
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.warn("[" + ModCfg.NAME + "] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
