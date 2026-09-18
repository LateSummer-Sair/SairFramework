import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Ev;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;

/**
 * 「点名引用」的<b>记账面</b>：群里来一条消息，就把「谁、几号、说了什么、什么时候」记一条。
 *
 * <p>它<b>不做任何判断</b>：不决定 @ 谁、不决定引用哪条，只是把清单备好，交给
 * {@link AtPickList} 摆到她眼前，由她自己决定（这是本插件与旧的「@提问者」的根本区别）。</p>
 *
 * <p>边界：只管群（私聊一个键都不写）；{@code 开关} 关掉就一条都不记（等于没装）；
 * 自己发的消息不算（{@code user_id == self_id}）；异常全吞 —— 记不上最多是清单少一条，
 * <b>绝不能拖垮一条入站</b>。</p>
 */
public class AtPickHook implements Hooked {

    private static final String HOOK = "on_message";
    private static final int DEF_KEEP = 10;
    private static final int DEF_TEXT_CUT = 40;
    private static final long DEF_TTL_SEC = 600L;
    private static final long DEF_SWEEP_SEC = 3600L;

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        if (!HOOK.equals(hook) || payload == null || h == null) return;
        try {
            Map<String, List<String>> c = AtPickCfg.of(h);
            if (!AtPickCfg.flag(c, "开关", true)) return;

            Ev e = Ev.parse(payload);
            if (e == null || !e.isGroup() || e.groupId() <= 0L) return;
            if (e.userId() <= 0L) return;
            if (e.userId() == e.selfId()) return;                  // 自己说的不算

            long now = System.currentTimeMillis();
            long ttl = ttlMs(c);

            AtPickMemo.Rec r = new AtPickMemo.Rec();
            r.ts = now;                                            // ★真入站时刻（秒级 event.time 不够用）
            r.id = e.messageId();
            r.qq = e.userId();
            r.name = Str.cut(Str.oneLine(e.senderName()), 20);
            if (Str.blank(r.name)) r.name = "#" + r.qq;            // 没有昵称就用 QQ（与聊天窗口的兜底同一口径）
            r.text = Str.cut(Str.oneLine(e.plainText()), textCut(c));

            boolean ok = AtPickMemo.push(h, e.groupId(), r, keep(c), now, ttl);
            AtPickMemo.sweep(h, now, ttl, AtPickCfg.num(c, "清理间隔秒", DEF_SWEEP_SEC) * 1000L);
            if (!ok) log(h, "入站记账没落库（权限/库不可用？）群=" + e.groupId() + " id=" + r.id);
        } catch (Throwable t) {
            log(h, "入站记账异常（不影响这条消息）：" + t);
        }
    }

    private static int keep(Map<String, List<String>> c) {
        long v = AtPickCfg.num(c, "清单条数", DEF_KEEP);
        return (int) Math.max(1L, Math.min(30L, v));
    }

    private static int textCut(Map<String, List<String>> c) {
        long v = AtPickCfg.num(c, "清单文本字数", DEF_TEXT_CUT);
        return (int) Math.max(8L, Math.min(200L, v));
    }

    private static long ttlMs(Map<String, List<String>> c) {
        long sec = AtPickCfg.num(c, "记录有效期秒", DEF_TTL_SEC);
        if (sec <= 0L) sec = DEF_TTL_SEC;
        return sec * 1000L;
    }

    private static void log(Host h, String msg) {
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.dim("[点名引用] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
