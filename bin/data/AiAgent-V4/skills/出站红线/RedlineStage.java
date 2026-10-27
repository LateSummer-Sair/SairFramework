import sair.v4.ext.Outbound;
import sair.v4.ext.OutboundStage;
import sair.v4.kit.Str;
import sair.v4.skill.Host;

/**
 * 「出站红线」出站 stage：<b>本条技能现在只负责"上一条被拦"那条给人看的会话提示</b>。
 *
 * <p>★★ 2026-09-22「基板提炼批」（审计 §5-2）：<b>判据与词表已整体搬进基板</b>
 * （{@link sair.v4.term.Redline} + {@code data/prompts/redline.md}，热读）。理由：这一族原先
 * <b>只有</b>技能里这一份实现，于是技能移走 / 编译失败 / {@code config.json: extEnabled=false}
 * （整条 stage 管线不跑）/ 工具发送路 任一形态都会让<b>整族</b>消失；而它同时是<b>唯一</b>覆盖
 * "无 caller 落点"（定时任务/闹钟 {@code Sinks.TaskSink}，caller 恒为 null）的安全闸
 * —— 情绪那一层明确"没 caller 一律不否决"（{@code MoodStage.veto}）。安全级规则不许住在
 * "可开、可关、可卸、可编译失败"的技能里。</p>
 *
 * <p>本类现在只做两件事（都<b>不是门限</b>）：</p>
 * <ol>
 *   <li>{@link #veto}：命中基板红线那一族"不可替换"类目时——<b>门限由基板判</b>（本方法只是让
 *       stage 这一层也照旧否决，于是"技能在册 / 不在册"的用户可见结果逐字相同），然后把
 *       "上一条被拦"那条提示写进<b>本会话</b>的 context 槽；</li>
 *   <li>{@link #transform}：出站即成 ⇒ <b>清掉</b>那条提示；另外把"可替换"类目的命中词换掉
 *       （同样调基板 {@link sair.v4.term.Redline#replace}，本类不做任何词表判断）。</li>
 * </ol>
 *
 * <p><b>★ {@code 开关} 现在只管那条提示</b>：{@code 开关: false} ⇒ 不再往 context 槽写
 * "上一条被拦"，<b>红线本身照旧生效</b>（判据在基板，不受任何技能开关影响）。
 * 措辞也写在 {@code prompt.md} 里（那一行现在只声明这一点）。</p>
 *
 * <p><b>注册口径</b>（已核 {@code sair.v4.hot.Skills#registerExt} + {@code ExtRegistry.isExtClass}）：
 * 实现 {@link OutboundStage} 的<b>顶层类</b>会被基板自动登记，owner = 技能文件夹名，md 里不用声明。
 * {@link #order()} = 80 ⇒ <b>早于</b>情绪的 90（红线先判"这条能不能发"，情绪再决定"要不要软化/拆句/@"）。</p>
 */
public final class RedlineStage implements OutboundStage {

    /** 顺序：80 < 情绪的 90（小的在前）。 */
    public static final int ORDER = 80;

    /** 被拦时写进会话 context 的键（**不再占用情绪那个 `emotion_veto`**）。 */
    public static final String VETO_KEY = "redline_veto";

    @Override
    public int order() {
        return ORDER;
    }

    /**
     * 一票否决（<b>判据在基板</b>）：命中"只否决、绝不替换"的常开族类目 ⇒ 这一条不发。
     *
     * <p>判据 = {@link sair.v4.term.Redline#veto(String)}（词表 {@code prompts/redline.md}，热读）；
     * 本方法<b>不读任何词表</b>，一个词都没硬编码。异常一律吞掉（出站兜底绝不拖垮一条发送）。</p>
     *
     * <p>命中之后：留一行 warn（<b>门限事实，任何时候都打</b>——拦了就要留痕），
     * 再按 {@code 开关} 决定要不要写那条给人看的提示。</p>
     */
    @Override
    public boolean veto(Outbound m) {
        try {
            if (m == null) return false;
            String text = m.text();
            if (Str.blank(text)) return false;
            String hit = sair.v4.term.Redline.veto(text);      // ★ 基板判据（唯一实现）
            if (hit == null) return false;
            Host h = m.host();
            warn(h, "出站否决：命中禁词类目「" + hit + "」（场景 " + scene(m) + "）");
            // 让她自己也知道"这条没发出去"（否决要有可见回执）：写一行进本会话的 context 槽。
            // ★★ 第十三轮（GM 令，复核者在"提示生命周期"上证明了可达回归）：**空会话不许写** ——
            //   控制台落点（caller==null / target<=0）拿不到会话 ⇒ `Inject.put` 会把空场合映射成
            //   **全局 `*`**（`Inject:48`）⇒ 那条提示**在每一个群里都看得见**（无关群也会看到"上一条被拦了"）。
            //   这种落点没有会话可挂 ⇒ **不写**（否决本身照旧生效）。
            // ★★ 2026-09-22 基板提炼批：`开关` 从此**只管这条提示**（红线判据在基板，不受它影响）。
            if (flag(h, "开关", true) && canHang(m)) {
                try {
                    if (h != null && h.inject() != null) {
                        h.inject().put(m.session(), "context", VETO_KEY,
                                "【出站被拦】上一条回复被出站红线拦下、没有发出去（命中「" + hit + "」，"
                                + scene(m) + "）。想说的话换个说法再说，别原样重发。", RedlineCfg.NAME);
                    }
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这条提示挂得上吗：**空会话 = 控制台落点 ⇒ 不写**（第十三轮 GM 令）。
     * <p>{@code Inject.put} 对空 scope 的处理是"写成**全局** {@code *}"（{@code Inject:48}），
     * 那会让提示污染**所有**会话 ⇒ 宁可少写一条，也不许写全局。情绪侧同一处也有同样的守卫。</p>
     */
    static boolean canHang(Outbound m) {
        return m != null && Str.has(m.session()) && m.target() > 0L;
    }

    /**
     * 软替换：命中**配了** {@code 禁词替换-<类目>} 的常开族类目 ⇒ 把命中词换成替换串
     * （同样调基板 {@link sair.v4.term.Redline#replace}，本类不读词表）；其余一律返回 {@code null}（不改）。
     *
     * <p>★ 顺序与语义与搬走之前<b>逐字相同</b>：先无条件清掉自己那条提示（"出站即成 ⇒ 上一条被拦的
     * 提示已经完成使命"），再换词。★ 唯一的口径变化：换词这一步<b>不再读 {@code 开关}</b>
     * —— 它现在属于基板红线，"关掉技能开关不会让红线失效"。</p>
     */
    @Override
    public String transform(Outbound m) {
        try {
            if (m == null) return null;
            Host h0 = m.host();
            // ---- ⓪ 第十三轮（GM 令，复核者实证的回归）：**出站即成 ⇒ 清掉自己那条提示** ----
            //   依据（自己核过 `ExtRegistry.runStages`）：管线是**先整趟 veto、再整趟 transform**
            //   ⇒ 能走到 transform 的正文已经过了**所有** stage 的否决 ⇒ 这时候"上一条被拦"那条
            //   提示已经完成使命，必须清掉（否则每轮渲染都带着它，她会**永远**以为上一条被拦了）。
            //   被否决的消息**不会**走到 transform ⇒ 提示保留 —— 那正是我们要的"上一条被拦"。
            try {
                if (h0 != null && h0.inject() != null) h0.inject().remove(m.session(), "context", VETO_KEY);
            } catch (Throwable ignored) {
            }
            String text = m.text();
            if (Str.blank(text)) return null;
            String out = sair.v4.term.Redline.replace(text);
            return out.equals(text) ? null : out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 本技能自己的配置面（只为一个键：{@code 开关} = 要不要写那条提示）。读不到就用默认。 */
    private static boolean flag(Host h, String key, boolean def) {
        try {
            return RedlineCfg.of(h).flag(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 场景标签（纯诊断用；本插件**不读任何库、不读情绪**）。 */
    private static String scene(Outbound m) {
        try {
            long qq = m.caller() == null ? 0L : m.caller().qq();
            long gid = m.group() ? m.target() : 0L;
            if (qq <= 0L) return gid > 0L ? ("group:" + gid + "（定时/闹钟落点，无 caller）")
                    : "（定时/闹钟落点，无 caller）";
            return gid > 0L ? ("group:" + gid + ":" + qq) : ("private:" + qq);
        } catch (Throwable t) {
            return "（场景读不到）";
        }
    }

    /** 一行 warn（留痕，不是噪音）——本插件唯一会打的日志。 */
    private static void warn(Host h, String msg) {
        try {
            if (h == null || h.out() == null) return;
            h.out().warn(Str.oneLine("[出站红线] " + msg));
        } catch (Throwable ignored) {
        }
    }

    /** 便于诊断/夹具：**基板**这一轮读到的红线快照（类目 / 词数 / 表文件 mtime）。 */
    static String catsFact() {
        return sair.v4.term.Redline.fact();
    }
}
