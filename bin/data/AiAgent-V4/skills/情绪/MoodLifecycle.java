import sair.v4.ext.BuildContext;
import sair.v4.ext.LifecycleHook;
import sair.v4.kit.Str;
import sair.v4.skill.Host;
import sair.v4.store.Store;

/**
 * 「情绪」的生命周期记账：<b>被晾的时间线 + 自报标记 + 转移日志刷盘</b>（规格 §3.4 / §E11 / §S10）。
 *
 * <h3>为什么放在这一层</h3>
 * <p>它只做"记账与清理"（接口本来就禁止改正文）：</p>
 * <ul>
 *   <li>{@link #onSend}：这一轮<b>要对外说话了</b> ⇒ 给"她连续说了几条 / 最后一次说话是什么时候"记一笔。
 *       这是 E11「被晾着」唯一的判据来源（她连说 ≥2 条之后 ≥10 分钟没人说话 ⇒ 下一条消息到达时结算 1 次）。</li>
 *   <li>{@link #onTurnEnd}：拿到<b>整段回复</b>，于是能读两样东西 ——
 *       ① 自报标记 {@code [[mood:做不到]]}（只记分、不在这里改用户看到的东西；剥离是 {@link MoodStage} 的事）；
 *       ② 结构失败证据（{@code [工具] ok=0} / 权限阻断）—— 它是自报标记的<b>第二证据</b>。</li>
 * </ul>
 *
 * <h3>写库走谁</h3>
 * <p>本插件不提供工具（没有 op 可判）：生命周期扩展点跑在 <b>SYSTEM</b> 主体上，
 * 写自己的表恒放行（技能管控表管的是工具/op，不管她自己的自主行为）。
 * 拿不到 store 就少做点事，绝不抛异常。</p>
 *
 * <h3>只管主回合</h3>
 * <p>子 Agent 与后台收尾回合不触发（基板保证）—— 对"她说了几句"这件事正好是对的：
 * 后台回合对外不可见，不该算进"她连续说了几条"。</p>
 */
public class MoodLifecycle implements LifecycleHook {

    /** 顺序（今天只有这一条生命周期钩子；同序按插件名）。 */
    public static final int ORDER = 90;

    @Override
    public int order() {
        return ORDER;
    }

    /** 她要说话了：给"被晾"的时间线记一笔，并把攒着的转移日志落盘。 */
    @Override
    public void onSend(BuildContext b) {
        try {
            if (b == null) return;
            long qq = b.qq();
            if (qq <= 0L) return;
            Host h = b.host();
            EmotionCfg c = EmotionCfg.of(h);
            if (!c.flag("开关", true) || !c.flag("情绪开关", true)) return;
            Store s = selfStore(h);
            if (s == null) return;
            long gid = b.group() ? b.groupId() : 0L;
            // ★ 批 15 / 2026-09-26：生命周期这一层也走唯一接缝（EmotionMood.nowMs()）——
            //   "她说了几条 / 最后一次说话是什么时候"与状态机写在同一个时刻上。
            //   默认（没注入）= 真实墙上时钟，生产与改前逐字节相同。
            EmotionMood.noteSelfSend(s, c, qq, gid, EmotionMood.nowMs());
            EmotionMood.flush(s);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 回合收尾：读整段回复里的"自报标记"与"结构失败证据"，写进状态供下一条消息做双证据计分。
     * <p>{@code reply} 失败时为空串（那种情况交给 {@code error} 判断，这里不动）。</p>
     */
    @Override
    public void onTurnEnd(BuildContext b, String reply, String error) {
        try {
            if (b == null) return;
            long qq = b.qq();
            if (qq <= 0L) return;
            Host h = b.host();
            EmotionCfg c = EmotionCfg.of(h);
            if (!c.flag("开关", true) || !c.flag("情绪开关", true)) return;
            Store s = selfStore(h);
            if (s == null) return;
            long gid = b.group() ? b.groupId() : 0L;
            long now = EmotionMood.nowMs();                    // ★ 批 15：同一个接缝（默认 = 真实时钟）
            boolean fail = EmotionMood.failOf(c, reply);
            if (fail) EmotionMood.noteFail(s, c, qq, gid, true);
            String tag = EmotionMood.tagOf(c, reply);
            if (Str.has(tag)) EmotionMood.noteTag(s, c, qq, gid, tag, now);
            EmotionMood.flush(s);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 自己那一份库。
     *
     * <p>本插件不提供工具（没有 op 可判）：生命周期钩子跑在 <b>SYSTEM</b> 主体上，
     * 写自己的表恒放行（技能管控表管的是工具/op，不管她自己的自主行为）。
     * 拿不到 store 就少做点事，绝不抛异常。</p>
     */
    private static Store selfStore(Host h) {
        try {
            if (h == null) return null;
            return h.store();
        } catch (Throwable t) {
            return null;
        }
    }
}
