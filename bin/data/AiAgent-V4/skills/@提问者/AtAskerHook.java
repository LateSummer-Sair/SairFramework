import com.google.gson.JsonObject;

import java.util.Map;
import java.util.List;

import sair.v4.auth.Caller;
import sair.v4.ext.BuildContext;
import sair.v4.ext.LifecycleHook;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Ev;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;

/**
 * 「@提问者」的<b>记账面</b>：入站时记一条、回合开始时钉一条。它自己<b>不做任何决定</b>。
 *
 * <h3>两个身份（一个类，两件只记账的事）</h3>
 * <ul>
 *   <li>{@link Hooked#on(String, JsonObject, Host)} —— md 的 {@code hooks: on_message}。
 *       群里来一条消息就把它记进入站环（谁、什么时候、message_id、@ 没 @ 她、是不是主人）。
 *       钩子的判定主体是 {@code SYSTEM}（{@code Skills.invokeHook} 绑的），所以它能写 kv。</li>
 *   <li>{@link LifecycleHook#onTurnStart(BuildContext)} —— 回合开始那一刻，把"这一轮的触发消息"
 *       从环里挑出来钉住。出站 stage 拿不到入站时间（{@code Outbound} 里没有、{@code BuildContext}
 *       在出站路径上 {@code turn == null}），只能在这里把时差所需的三个量交给它。</li>
 * </ul>
 *
 * <h3>为什么不用 static 缓存</h3>
 * <p>热重载 = 新建类加载器 + 新静态字段（{@code Skills.java:314}），static 里的东西会成孤儿。
 * 所以两个面都<b>只写库</b>（{@code kv}，键名与 TTL/清理见 {@link AtAskerMemo}），不留任何内存态。</p>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>只管群（{@code isGroup() && groupId>0}）：@ 与引用今天都只作用在群，私聊/控制台一个键都不写；</li>
 *   <li>开关关掉（{@code prompt.md} 的「开关」）→ 一条都不记（等于没装）；</li>
 *   <li>异常全吞：这两条路都是"记账"，记不上最多回到旧口径，绝不能拖垮一条入站或一个回合。</li>
 * </ul>
 */
public class AtAskerHook implements Hooked, LifecycleHook {

    /** 出站 stage 的 order 是 20；这里 60 只为顺序确定（本插件两条路互不依赖先后）。 */
    private static final int ORDER = 60;

    // ==================== on_message：入站记账 ====================

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        if (!"on_message".equals(hook) || payload == null || h == null) return;
        try {
            Map<String, List<String>> c = AtAskerCfg.of(h);
            if (!AtAskerCfg.flag(c, "开关", true)) return;

            Ev e = Ev.parse(payload);
            if (e == null || !e.isGroup() || e.groupId() <= 0L) return;
            if (e.userId() <= 0L) return;
            if (e.userId() == e.selfId()) return;              // 自己说的不算

            long now = System.currentTimeMillis();
            long ttl = ttlMs(c);
            int keep = (int) Math.max(1L, Math.min(20L, AtAskerCfg.num(c, "保留条数", 5L)));

            AtAskerMemo.Rec r = new AtAskerMemo.Rec();
            r.ts = now;                                        // ★真入站时刻（秒级 event.time 不够用）
            r.id = e.messageId();
            r.at = e.atSelf();
            Caller real = h.caller();                          // 钩子里 caller() 仍是"真实发送者"
            r.master = real != null && real.master();
            r.qq = e.userId();
            r.text = Str.cut(Str.oneLine(e.plainText()), 40);   // 只留 40 字：给"匹配本轮输入"与排障

            boolean ok = AtAskerMemo.push(h, e.groupId(), r, keep, now, ttl);
            AtAskerMemo.sweep(h, now, ttl, AtAskerCfg.num(c, "清理间隔秒", 3600L) * 1000L);
            if (!ok) log(h, "入站记账没落库（权限/库不可用？）群=" + e.groupId() + " id=" + r.id);
        } catch (Throwable t) {
            log(h, "入站记账异常（不影响这条消息）：" + t);
        }
    }

    // ==================== onTurnStart：钉住本轮触发 ====================

    @Override
    public int order() { return ORDER; }

    @Override
    public void onTurnStart(BuildContext b) {
        if (b == null) return;
        try {
            if (b.subagent()) return;                          // 子 Agent 回合不对外说话
            if (!b.group() || b.groupId() <= 0L) return;       // 只管群
            Host h = b.host();
            if (h == null) return;
            Map<String, List<String>> c = AtAskerCfg.of(h);
            if (!AtAskerCfg.flag(c, "开关", true)) return;

            long now = System.currentTimeMillis();
            AtAskerMemo.pin(h, b.groupId(), b.text(), now, ttlMs(c));
        } catch (Throwable t) {
            log(b.host(), "钉住触发异常（本轮按旧口径处理）：" + t);
        }
    }

    // ==================== 小工具 ====================

    private static long ttlMs(Map<String, List<String>> c) {
        long sec = AtAskerCfg.num(c, "记录有效期秒", 600L);
        if (sec <= 0L) sec = 600L;
        return sec * 1000L;
    }

    private static void log(Host h, String msg) {
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.dim("[@提问者] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
