import java.util.List;

import sair.v4.ext.Outbound;
import sair.v4.ext.OutboundStage;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Host;
import sair.v4.store.Store;

/**
 * 「情绪」的出站兜底：<b>语气族否决 / 只骂触发者 / 自报标记剥离（转发基板那一份）/ 分句成条 + @ 指名</b>
 * （规格 §6 S1–S3、S10；情绪 v2 的 §3 + 第二轮裁定 E）。
 *
 * <h3>只做纯文本变换</h3>
 * <p>本插件不提供工具（没有 op 可判、也不做任何权限判定）：出站 stage 只读状态、改正文，
 * 所以这里<b>不写库、不发消息</b>。状态读取用 {@link Store#kv} + {@link Host} 的只读口。</p>
 *
 * <h3>四个口怎么分工</h3>
 * <p>★★ 第十二轮（GM 令，甲方原话「skill 肯定要拆的，**情绪不能负责输出门限**，宁愿单独放置」）：
 * <b>常开族（隐私/威胁/自伤/机密键）连同它们的软替换已经整体搬去</b>
 * {@code data\skills\出站红线\}（{@code RedlineStage}，{@code order=80}，**早于**本 stage 的 90）。
 * 本类从此<b>只管情绪自己的三件事</b>：语气族否决 / 只骂触发者（S2）/ 拆句与加 @；
 * 外加把「自报标记剥离」<b>转发</b>给基板那一份唯一实现（见下面「标记族」一节）。</p>
 * <ul>
 *   <li>{@link #veto}：命中<b>语气族</b>禁词类目（{@code 固有属性} / {@code 情绪操纵} / {@code 敬语} /
 *       {@code 身份豁免语句}）⇒ <b>整条不发</b>，而且<b>只在 {@code angry} / {@code strike}</b>
 *       （语气族要看情绪态 ⇒ 没有 caller 就没有场合 ⇒ 不否决）。类目词表只在 {@code prompt.md} 里
 *       （<b>不硬编码</b>：改一个词要动 {@code .java} 就得 reload，reload 会掉内存态）。</li>
 *   <li>{@link #transform}：① 剥掉自报标记那一行（用户不许看到 {@code [[mood:…]]}）——
 *       <b>判据与实现都在基板</b> {@link sair.v4.qq.MoodMarks}，这里只是转发；
 *       ② <b>只骂触发者</b>：状态里记的 {@code trigger_by} 与这条消息的落点不是同一个人、却带着攻击性词
 *       ⇒ 把词降级（S2）。若这条正文<b>已经 @ 到 trigger_by</b>（点名了），S2 不算"误伤"（v2 §3.4）。
 *       ★ 第十二轮之前这里还有一步"软替换"（隐私/机密键），现在那一步在出站红线里。</li>
 *   <li>{@link #extra}：不用。</li>
 *   <li>{@link #finish}（v2 新增）：整批收尾 —— 群 + 落点人就是 {@code trigger_by} +
 *       本场合 {@code angry}/{@code strike} 时，把她糊在一条里的几句话<b>分句成多条</b>
 *       并给<b>首条</b>加 {@code [CQ:at,qq=…]} 指名（见 {@link #finish}）。
 *       <b>2026-09-26 敬重档</b>：落点人是敬重档（基板好感度 &gt;= {@code 敬重档-好感度}）
 *       ⇒ 这一步<b>整个不做</b>（他连"生气的交付形状"都不该收到）。</li>
 * </ul>
 *
 * <h3>标记族：判据已下沉基板（本类只调用，不持有实现）</h3>
 * <p>{@code [[mood:…]]} 的<b>识别与剥离</b>已经搬进基板 {@link sair.v4.qq.MoodMarks}
 * （2026-09-22「基板提炼批」，审计 §5-1）：标记是<b>基板与模型之间的约定</b>，不该随一个可选技能的
 * 生死而变。本类下面那几个 {@code static} 方法（{@code stripMarks} / {@code markStart} / {@code markTag}
 * / {@code hasRelayMark} / {@code markOf}）现在都是<b>转发</b>，一行判据都没留。
 * 基板另外在 {@code term.Sinks.gate}（逐条）与 {@code qq.Api}（工具发送路）各调一次同一份实现
 * ⇒ 技能移走 / 编译失败 / {@code extEnabled=false} / 工具发送路 这四种形态都不再外泄字面量。</p>
 *
 * <p><b>本类仍然负责的</b>：{@code [[mood:转述]]} 的<b>语义</b>（这一批一个字都不 @、不拆句）——
 * 它要看情绪态与场合，仍住在这里（{@link #finish} 的 {@code takeRelay}）。</p>
 */
public class MoodStage implements OutboundStage {

    /** 顺序：放在既有 stage 之后（今天的出站面只此一条；同序按插件名）。 */
    public static final int ORDER = 90;

    /**
     * 「转述」标记名（**归一化后**比较的那个词）。
     * <p>第十轮：识别与剥离都走 {@link sair.v4.qq.MoodMarks#start} 的归一化匹配；
     * 实现已搬进基板（本类只留常量与转发，见类 javadoc）。</p>
     */
    static final String RELAY_TAG = sair.v4.qq.MoodMarks.RELAY_TAG;

    /**
     * 「**转述**」自报标记（第九轮 GM 令 A2）：她的回复里写了 {@code [[mood:转述]]} ⇒ 本轮**一个字都不 @**。
     * <p>为什么用**进程内短 TTL 标记**而不是写库：SPEC §3.5 定的"出站 stage 不写库"还在 ⇒ 这里只在内存里
     * 记一枚 {@code session → 到期时刻}（30 秒）：{@link #transform} 落、{@link #finish} 取（取完即清）。
     * 它只服务**同一次发送**（逐条 transform 与整批 finish 在同一批里跑完），技能 reload 会清掉它
     * —— 两种情况都不影响正确性。</p>
     * <p>★ 形态归一化由基板 {@link sair.v4.qq.MoodMarks} 负责；这个常量只是"她该写什么"的
     * 规范写法（提示词与夹具引用它），取值直接来自那一处（不写第二份字面量）。</p>
     */
    static final String RELAY_MARK = sair.v4.qq.MoodMarks.mark();

    /** 记一枚转述标记（只对**同一次发送**有效；实现与 TTL 都在基板 {@code MoodMarks} 一处）。 */
    static void markRelay(String session) {
        sair.v4.qq.MoodMarks.relay(session);
    }

    /** 取一枚转述标记（**取完即清**）：这一批是不是"转述轮"。 */
    static boolean takeRelay(String session) {
        return sair.v4.qq.MoodMarks.takeRelay(session);
    }

    @Override
    public int order() {
        return ORDER;
    }

    // ================================================================ veto：语气族整条不发

    /**
     * 一票否决（**只剩语气族**）。
     *
     * <p>★★ 第十二轮（GM 令，甲方原话「skill 肯定要拆的，**情绪不能负责输出门限**，宁愿单独放置」）：
     * <b>常开族（隐私/威胁/自伤/机密键）连同它们的软替换整体搬去 {@code data\skills\出站红线\}</b>
     * （那边 order=80，**早于**本 stage 的 90）。本方法从此只管一件事：
     * <b>语气族</b>（{@code 固有属性/情绪操纵/敬语/身份豁免语句}）**只在 angry / strike** 命中即整条否决
     * —— 它是"治客服腔"的，不是法律红线，所以 calm 态照旧放行。</p>
     *
     * <p>因为语气族要看情绪态，它**仍然需要场合**：没有 caller（定时任务/闹钟落点）就没有场合
     * ⇒ 一律不否决（这条口径一字未改；那种落点上的红线交给新技能，它不需要场合）。</p>
     */
    @Override
    public boolean veto(Outbound m) {
        try {
            if (m == null) return false;
            Host h = m.host();
            EmotionCfg c = EmotionCfg.of(h);
            if (!c.flag("开关", true) || !c.flag("情绪开关", true)) return false;
            if (!c.flag("出站兜底", true)) return false;
            long qq = m.caller() == null ? 0L : m.caller().qq();
            long gid = m.group() ? m.target() : 0L;
            if (qq <= 0L) return false;          // 没有"谁在说话"= 没有场合 ⇒ 语气族不生效
            Store s = store(h);
            if (s == null) return false;
            // ★ 批 15 / 2026-09-26（时钟接缝第二步）：出站这一层读"现在"也必须走
            //   EmotionMood.nowMs()（唯一真源）。WHY：peek() 在 m.slot != slotOf(now) 时把状态当成
            //   calm 返回（懒翻页），而情绪行是按**接缝那个时刻**写下的 slot ⇒ 这一处若读裸墙上时钟，
            //   注入/平移时钟的夹具里"每条出站都把情绪重置回 calm"（分句/@ 全失效，V15 实测 17 红）。
            //   默认（没注入）= 真实墙上时钟，生产与改前逐字节相同。
            EmotionMood.Mood mood = EmotionMood.peek(s, c, qq, gid, EmotionMood.nowMs());
            String hit = bannedTone(c, m.text(), mood == null ? "" : mood.state);
            if (hit == null) return false;
            // 场景标签：有 caller 时**逐字与改动前相同**
            String scene = EmotionMood.scopeOf(gid, qq);
            EmotionMood.warn(h, "出站否决：命中禁词类目「" + hit + "」（场景 " + scene + "）");
            // ★ 让她自己也知道"这条没发出去"（主人 2026-09-19 定：否决要有可见回执）：
            //   往本会话的 context 槽写一行，下一轮她读得到；她自己换了说法发出去时会被 transform 清掉。
            // ★ 第十二轮：常开族那条回执改用 `redline_veto`（在出站红线插件里）；这里仍是 `emotion_veto`。
            //   ★★ 第十三轮（GM 令）：**空会话不许写** —— `m.session()` 为空（控制台落点：caller==null /
            //   target<=0）时 `Inject.put` 会把空场合映射成**全局 `*`**（`Inject:48`），那条提示就会在
            //   **每一个会话**里都看得见。空会话没有提示可挂 ⇒ **不写**（否决本身照旧生效）。
            //   出站红线那侧（`RedlineStage.veto`）有同一处守卫，两处口径一致。
            if (Str.has(m.session()) && m.target() > 0L) {
                try {
                    if (h != null) {
                        h.inject().put(m.session(), "context", "emotion_veto",
                                "【出站被拦】上一条回复被情绪规则拦下、没有发出去（命中「" + hit + "」，"
                                + scene + "）。想说的话换个说法再说，别原样重发。", "情绪");
                    }
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable t) {
            return false;                                   // 出站兜底绝不拖垮一条发送
        }
    }

    // ================================================================ transform：剥标记 + 软替换 + 只骂触发者

    @Override
    public String transform(Outbound m) {
        try {
            if (m == null) return null;
            String text = m.text();
            Host h0 = m.host();
            // 这一轮真有话要出去 ⇒ 把"上一条被拦"那条提示清掉（她已经重新说过了）
            try {
                if (h0 != null) h0.inject().remove(m.session(), "context", "emotion_veto");
            } catch (Throwable ignored) {
            }
            if (!Str.has(text)) return null;
            Host h = m.host();
            EmotionCfg c = EmotionCfg.of(h);
            String out = text;
            boolean touched = false;
            // ---- ⓪ 第九/十轮（GM 令 A2）：她自报了「转述」⇒ 记一枚只对本批有效的标记（finish 据此不加 @）
            //      第十轮起**归一化识别**（`[[mood: 转述]]` / `[[MOOD:转述]]` / `[mood:转述]` 都算）。
            boolean relay = hasRelayMark(out);
            if (relay) markRelay(m.session());
            // ---- ① S10：自报标记必须剥离（含"只写了一半"的残行）
            //      ★ 2026-09-22 基板提炼批：实现已搬进基板 `sair.v4.qq.MoodMarks`（这里只转发）。
            //      ★★ 事实（第十四轮审计 §7-2 更正的那条注释）：这一步写在**所有 flag 判断之前**
            //        （它在下面的 `if (c.flag("出站兜底") && c.flag("开关"))` 之外）——
            //        所以 `出站兜底:false` / `开关:false` **停不掉**它；能停掉它的只有
            //        "这个技能不装载"（移走 / 编译失败 / `extEnabled:false`），而那几种形态现在由
            //        基板 `term.Sinks.gate` 与 `qq.Api` 用同一份实现兜住。
            String stripped = stripMarks(out);
            if (!stripped.equals(out)) {
                out = stripped;
                touched = true;
            }
            // ---- ② S2（只骂触发者）----
            //      ★ 第十二轮（GM 令「情绪不能负责输出门限」）：这里的**软替换**那一步已经搬去
            //      `data\skills\出站红线\`（`RedlineStage.transform`，order=80，**早于**本 stage 的 90）
            //      ⇒ 情绪这一层不再碰任何"禁词-隐私/机密键"式的常开族替换，只做它自己的两件事：
            //      语气族否决（veto）+ 只骂触发者/拆句/加 @（transform/finish）。
            if (c.flag("出站兜底", true) && c.flag("开关", true)) {
                Store s = store(h);
                long gid = m.group() ? m.target() : 0L;
                if (s != null) {
                    // 看的是**这个场合**的情绪态（情绪按场合隔离存，trigger_by 记的是"谁把她惹毛的"）。
                    // 场合里没人惹过她（trigger_by=0）就不管；场合是 calm/happy/sad/letdown 也不管。
                    //
                    // 判据（只看"要发给谁 vs 谁惹的她"）：
                    //   ① 群聊：这条出站的 caller ≠ trigger_by ⇒ 她可能在"替甲骂乙" ⇒ 降级；
                    //   ② 私聊：落点 QQ ≠ trigger_by ⇒ 给无关的人带刺 ⇒ 降级；
                    //   ③ 私聊但对方就是 trigger_by ⇒ 带刺允许（有分寸地怼他本人）。
                    //   ★ 群聊那条刻意保守（群回复的落点是**群**，基板不暴露"@了谁"）：
                    //     宁可偶尔把该冲的话软化，也不让无关的人挨骂 —— 红线优先于风格。
                    //   ★★ v2 §3.4 的**唯一放松**：这条正文已经 @ 到 trigger_by 时，说明骂的
                    //     就是他本人（点名了），不再算"误伤" ⇒ 不软化。没 @ 到 trigger_by 的落点
                    //     仍按上面的保守口径软化。
                    long who = m.caller() == null ? 0L : m.caller().qq();
                    boolean group = gid > 0L;
                    long scopeQq = group ? 0L : who;           // 状态按"谁在跟她说话"那个场合存
                    EmotionMood.Mood mood = EmotionMood.peek(s, EmotionMood.scopeOf(gid, scopeQq),
                            EmotionMood.nowMs(), c);      // ★ 批 15：同一个接缝（默认 = 真实时钟）
                    String state = mood == null ? "" : mood.state;
                    boolean hot = EmotionMood.S_ANGRY.equals(state) || EmotionMood.S_STRIKE.equals(state);
                    // 罢工与生气同档处理：罢工是生气的升级态，S2 在这里对它更该生效，不该失效。
                    boolean other = mood != null && mood.by > 0L && hot
                            && (group ? (who > 0L && who != mood.by) : (m.target() != mood.by));
                    // ★ 第九轮（GM 令 A1/A2）：**转述场景也要软化说话人** ——
                    //   ① 她自报了 `[[mood:转述]]`（这一批就是 relay）；或
                    //   ② 本场合在 angry/strike 但**没有锁人**（`by <= 0`）—— 那正是"第三方指称 ⇒ 不锁人"
                    //      留下的现场（正常情况下任何让状态升级的信号都会带一个 by>0 的人）。
                    //   两者都意味着"火不该撒在说话人身上" ⇒ 走 S2 软化（只换攻击词，不改别的）。
                    boolean relayScene = relay || (hot && mood != null && mood.by <= 0L);
                    if ((other || relayScene) && !(group && mood != null && atOf(out, mood.by))) {
                        String soft = soften(c, out);
                        if (!soft.equals(out)) {
                            out = soft;
                            touched = true;
                        }
                    }
                }
            }
            return touched ? out : null;                    // null = 不改（这是"没意见"的默认）
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ finish：分句成条 + @ 指名（v2 §3.3）

    /** {@code [CQ:at,qq=} 前缀（幂等判据与"点名"都按它）。 */
    static final String AT = "[CQ:at,qq=";

    /**
     * <b>整批收尾</b>：群 + 落点人就是本场合的 {@code trigger_by} + 本场合 {@code angry}/{@code strike} 时，
     * ① 把她糊在一条里的几句话<b>分句成多条</b>，② 给<b>首条</b>加 {@code [CQ:at,qq=<trigger_by>] }。
     *
     * <p><b>返回 {@code null} = 不改</b>（"没意见"的默认）；返回<b>新表</b> = 改（可以改条数）。
     * 其余任何情况一律返回 {@code null}：非群 / {@code caller == null}（定时任务落点）/ caller 不是
     * 触发者 / 状态不在 {@code angry|strike} / {@code 分句上限-<码> <= 1}。<b>不碰落点</b>——
     * 只改正文文本与条数（§3.3 最后一条）。</p>
     *
     * @param parts 最终消息表（基板已逐条 trim、已丢掉空串）
     */
    @Override
    public List<String> finish(List<String> parts, Outbound m) {
        try {
            if (m == null || parts == null || parts.isEmpty()) return null;
            // ---- ⓪ 第九轮（GM 令 A2）：她自报了「转述」⇒ **本轮一个字都不 @**（她要指向谁，自己在正文里写
            //        `<at qq="N"/>`，`ChatMarkers` 会转成 CQ）。这里整批不改（连分句都不做），
            //        免得把她手写的点名标记拆到别的条里。标记**取一次就清**（只对本批有效）。
            if (takeRelay(m.session())) return null;
            if (!m.group()) return null;                                  // 只做群（私聊没有"指名"这回事）
            if (m.caller() == null) return null;                          // 定时任务落点：没有触发者（必须容忍）
            long who = m.caller().qq();
            long gid = m.target();
            if (who <= 0L || gid <= 0L) return null;
            Host h = m.host();
            EmotionCfg c = EmotionCfg.of(h);
            if (!c.flag("开关", true) || !c.flag("情绪开关", true)) return null;
            if (!c.flag("出站兜底", true)) return null;
            Store s = store(h);
            if (s == null) return null;
            // 本场合的情绪（群 = group:<群号>，与 transform / veto 同一个 scope）
            EmotionMood.Mood mood = EmotionMood.peek(s, EmotionMood.scopeOf(gid, 0L),
                    EmotionMood.nowMs(), c);              // ★ 批 15：同一个接缝（默认 = 真实时钟）
            if (mood == null) return null;
            String st = mood.state;
            if (!EmotionMood.S_ANGRY.equals(st) && !EmotionMood.S_STRIKE.equals(st)) return null;
            if (mood.by <= 0L || mood.by != who) return null;             // 只对**触怒者本人**（不误伤）
            // ★ 2026-09-26 敬重档（K7 批，甲方规格：好感度 >=700 ⇒ 不再对此人发怒）：
            //   连"分句连发 + @ 指名"这种**生气的交付形状**也不给他 —— 语气那一半在注入面
            //   （EmotionProvider → EmotionMood.block）里已经换成敬重档，这里只是同一条口径在出站面的落点，
            //   判据同源（EmotionMood.respect，只读基板好感度）。没进档 ⇒ 一个字都不改（与改前一致）。
            //   ★ 只做这一件事：出站那一层的**语气族禁词否决**（veto）没有跟着豁免 —— 它是"治客服腔"的
            //     （禁词-敬语 那几类），不是"发给谁"的问题；敬重档文案本身也不含那一族的词。
            if (EmotionMood.respect(c, h, m.caller(), who).on) return null;
            int cap = c.i("分句上限-" + st, 1);
            if (cap <= 1) return null;                                    // §3.3：分句上限 == 1 ⇒ 不改
            boolean changed = false;
            List<String> out = new java.util.ArrayList<String>();
            if (parts.size() == 1) {
                // 只有一条时才重组：她自己用 <split> / 空行分好的，尊重她（§3.3①）
                List<String> split = reflow(parts.get(0), cap);
                if (!split.equals(parts)) changed = true;
                out.addAll(split);
            } else {
                out.addAll(parts);
            }
            // ② @ 指名（只加首条；**幂等**：首条已含 [CQ:at,qq= 就不加）
            if (c.flag("点名-" + st, false) && !out.isEmpty()) {
                String first = out.get(0) == null ? "" : out.get(0);
                if (first.indexOf(AT) < 0) {
                    out.set(0, AT + mood.by + "] " + first);
                    changed = true;
                }
            }
            return changed ? out : null;
        } catch (Throwable t) {
            return null;                                   // 出站兜底绝不拖垮一条发送
        }
    }

    /** 这条正文里有没有 @ 到<b>某个具体的人</b>（v2 §3.4 的 S2 判据）。 */
    static boolean atOf(String text, long qq) {
        return Str.has(text) && qq > 0L && text.contains(AT + qq + "]");
    }

    /** 句末标点（§3.3①：。！？…）；换行也算一句的边界。 */
    private static final String ENDS = "。！？…";

    /**
     * 把一条正文拆成句子、再重组为<b>最多 {@code cap} 条</b>（§3.3①）。
     *
     * <p>口径逐条对齐契约：句子 = 到句末标点（{@code 。！？…}，连着写算同一处）或换行为止；
     * 句子是<b>原子</b>（不再二次切分）；超出的句子<b>并进最后一条</b>。
     * 句数 ≤ {@code cap} 时一句一条（这是"分句成条"的常态），句数 &gt; {@code cap} 时
     * 前 {@code cap-1} 句各自成条、剩下的全并进末条 ⇒ 契约举的"1 条 4 句 ⇒ 3 条"就是这样来的。</p>
     */
    static List<String> reflow(String text, int cap) {
        List<String> out = new java.util.ArrayList<String>();
        String t = text == null ? "" : text;
        if (cap <= 1) {
            out.add(Str.trim(t));
            return out;
        }
        List<String> sents = sentences(t);
        if (sents.size() <= 1) {
            out.add(Str.trim(t));
            return out;
        }
        int head = Math.min(cap - 1, sents.size() - 1);
        for (int i = 0; i < head; i++) out.add(sents.get(i));
        StringBuilder last = new StringBuilder();
        for (int i = head; i < sents.size(); i++) last.append(sents.get(i));
        out.add(last.toString());
        return out;
    }

    /** 按句末标点切句（空白句丢弃；标点跟着它那一句走）。 */
    static List<String> sentences(String text) {
        List<String> out = new java.util.ArrayList<String>();
        if (text == null) return out;
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n') {
                flush(cur, out);
                continue;
            }
            cur.append(ch);
            if (ENDS.indexOf(ch) >= 0) {
                char nxt = (i + 1 < s.length()) ? s.charAt(i + 1) : 0;
                if (ENDS.indexOf(nxt) < 0) flush(cur, out);   // 连着的！！！算同一句的结尾
            }
        }
        flush(cur, out);
        return out;
    }

    private static void flush(StringBuilder cur, List<String> out) {
        String one = Str.trim(cur.toString());
        cur.setLength(0);
        if (Str.has(one)) out.add(one);
    }

    // ================================================================ 禁词 / 攻击词

    // ★★ 第十二轮（GM 令「情绪不能负责输出门限」）★★
    //   这一段原来有：`DEF_ALWAYS`（常开族下限）、`DEF_NEVER_REPLACE`（绝不替换清单下限）、
    //   `CAT_SECRET`、`bannedAlways()`、`alwaysCats()`、`neverReplaceCats()`、`neverReplace()`、
    //   `replaceable()`、`replace()`、以及合并入口 `banned()` —— **全部删除**（不是留着不用）。
    //   它们连同四个常开族（隐私/威胁/自伤/机密键）与两条替换走向，整体搬去
    //   `data\skills\出站红线\`（`RedlineCfg` 自带同源兜底 `DEF_ALWAYS` / `DEF_NEVER_REPLACE`，
    //   `RedlineStage.banned()` / `replace` 逻辑逐字照搬）。两个技能**互不依赖**：情绪不 import 它，
    //   它也不 import 情绪。

    /**
     * <b>语气族</b>（= 情绪 `prompt.md` 里剩下的那些 {@code 禁词-<类目>}：固有属性 / 情绪操纵 /
     * 敬语 / 身份豁免语句）命中，且<b>只在 angry / strike</b>
     * （没有场合 ⇒ 没有情绪态 ⇒ 一律不否决，这条口径没变）。
     *
     * <p>★ 第十二轮：常开族已经不在这里判（搬去出站红线），所以本方法不再做"跳过常开族 / 跳过可替换类目"
     * 那两步 —— 剩下的类目**全都是语气族**，一条一条扫即可。</p>
     */
    static String bannedTone(EmotionCfg c, String text, String state) {
        if (c == null || Str.blank(text)) return null;
        boolean hot = EmotionMood.S_ANGRY.equals(state) || EmotionMood.S_STRIKE.equals(state);
        if (!hot) return null;
        for (String cat : categories(c)) {
            for (String w : c.tags("禁词-" + cat)) {
                if (Str.has(w) && text.contains(w)) return cat;
            }
        }
        return null;
    }

    /**
     * 语气族类目名（从 `prompt.md` 的 {@code 禁词-<类目>} 键里读；读不到用同源兜底类目名）。
     * <p>★ 第十二轮：兜底清单里**不再有** 隐私/威胁/自伤/机密键（那四类已搬去出站红线）。</p>
     */
    static List<String> categories(EmotionCfg c) {
        List<String> out = new java.util.ArrayList<String>();
        if (c != null) {
            for (String key : c.all().keySet()) {
                if (key.startsWith("禁词-") && key.length() > 3) {
                    String name = key.substring(3);
                    if (!out.contains(name)) out.add(name);
                }
            }
        }
        if (out.isEmpty()) {
            out.add("固有属性");
            out.add("情绪操纵");
            out.add("敬语");
            out.add("身份豁免语句");
        }
        return out;
    }

    /** S2：把攻击性词换成中性说法（不是他干的，就别骂他）。 */
    static String soften(EmotionCfg c, String text) {
        if (c == null || Str.blank(text)) return text;
        String out = text;
        for (String w : c.tags("攻击词")) {
            if (!Str.has(w) || !out.contains(w)) continue;
            String rep = c.s("攻击词替换", "（这条我语气可能冲了）");
            out = out.replace(w, rep);
        }
        return out;
    }

    // ================================================================ 自报标记（★ 判据已下沉基板；这里只转发）

    /**
     * 归一化：这一行里第一个 {@code mood} 标记的**起始下标**；没有返回 {@code -1}。
     * <p><b>转发</b> {@link sair.v4.qq.MoodMarks#start}：本类不再持有实现（判据只有基板那一处）。</p>
     */
    static int markStart(String line) {
        return sair.v4.qq.MoodMarks.start(line);
    }

    /** 归一化后的标记名（{@code 转述} / {@code 做不到} / {@code 为难} / {@code 违规}）。转发基板那一处。 */
    static String markTag(String line) {
        return sair.v4.qq.MoodMarks.tag(line);
    }

    /** 这一批文本里有没有「转述」自报标记（归一化）。转发基板那一处。 */
    static boolean hasRelayMark(String text) {
        return sair.v4.qq.MoodMarks.hasRelay(text);
    }

    /**
     * 这一条文本里认出来的标记（归一化；没有就是空串）。
     * <p>只读：真正的"记分"发生在 {@link MoodLifecycle}。转发基板那一处。</p>
     */
    static String markOf(String text) {
        return sair.v4.qq.MoodMarks.tagOf(text);
    }

    /**
     * 剥掉 {@code [[mood:…]]} 那一行（以及只剩半截的残行）。<b>转发</b> {@link sair.v4.qq.MoodMarks#strip}。
     * <p>语义（整行丢弃、保留标记之前的文字、尾部空行压到一个）与实现**都在基板那一处**；
     * 基板另外还在 {@code term.Sinks.gate} 与 {@code qq.Api} 调同一份 —— 这就是"技能关掉也不外泄"的保证。</p>
     */
    static String stripMarks(String text) {
        return sair.v4.qq.MoodMarks.strip(text);
    }

    private static Store store(Host h) {
        try {
            return h == null ? null : h.store();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 诊断用：把标记拼成一行可读文本（不参与任何判断）。 */
    static String jsonTag(String tag) {
        return J.json(tag);
    }
}
