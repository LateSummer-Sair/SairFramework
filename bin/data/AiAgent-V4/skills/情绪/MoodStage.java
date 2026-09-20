import java.util.List;

import sair.v4.ext.Outbound;
import sair.v4.ext.OutboundStage;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Host;
import sair.v4.store.Store;

/**
 * 「情绪」的出站兜底：<b>禁词否决 / 软替换（隐私·机密键）/ 只骂触发者 / 自报标记剥离 / 分句成条 + @ 指名</b>
 * （规格 §6 S1–S3、S10；情绪 v2 的 §3 + 第二轮裁定 E）。
 *
 * <h3>只做纯文本变换</h3>
 * <p>本插件不提供工具（没有 op 可判、也不做任何权限判定）：出站 stage 只读状态、改正文，
 * 所以这里<b>不写库、不发消息</b>。状态读取用 {@link Store#kv} + {@link Host} 的只读口。</p>
 *
 * <h3>四个口怎么分工</h3>
 * <ul>
 *   <li>{@link #veto}：命中「禁词」类目 ⇒ <b>整条不发</b>。v2 起分两族（见 {@link #banned}）：
 *       <b>常开族</b>（{@code 禁词常开} 列的类目）在<b>任何状态</b>都处理；
 *       <b>语气族</b>（其余 {@code 禁词-<类目>}）只在 {@code angry} / {@code strike} 否决。
 *       哪些类目"只否决、绝不替换"由 {@code 禁词不替换} 那条**清单键**决定
 *       （第二轮 GM 裁定 E：改成数据驱动；**第三轮甲方 2026-09-20 令：只剩 威胁/自伤**，
 *        {@code 涉政}/{@code 黄赌毒} 已删键下线——那两类只审图片内容；{@code 机密键} 走软替换）。
 *       类目与清单都只在 {@code prompt.md} 里（<b>不硬编码</b>：改一个词/一个类目要动 {@code .java}
 *       就得 reload，reload 会掉内存态）。</li>
 *   <li>{@link #transform}：① 剥掉自报标记那一行（用户不许看到 {@code [[mood:…]]}）；
 *       ② <b>软替换</b>：命中类目若配了 {@code 禁词替换-<类目>}（出厂：隐私、机密键）
 *       ⇒ 把那几个词换成替换串，<b>不是</b>整条否决；③ <b>只骂触发者</b>：状态里记的
 *       {@code trigger_by} 与这条消息的落点不是同一个人、却带着攻击性词 ⇒ 把词降级（S2）。
 *       若这条正文<b>已经 @ 到 trigger_by</b>（点名了），S2 不算"误伤"（v2 §3.4）。</li>
 *   <li>{@link #extra}：不用。</li>
 *   <li>{@link #finish}（v2 新增）：整批收尾 —— 群 + 落点人就是 {@code trigger_by} +
 *       本场合 {@code angry}/{@code strike} 时，把她糊在一条里的几句话<b>分句成多条</b>
 *       并给<b>首条</b>加 {@code [CQ:at,qq=…]} 指名（见 {@link #finish}）。</li>
 * </ul>
 *
 * <h3>已知缺口（诚实记录，不许假装覆盖）</h3>
 * <p>她若自己调 {@code send} / 表情包工具发消息，那条路<b>完全绕过出站管线</b>
 * （{@code Agent} 的工具发送路径），于是这里的剥离与拦截<b>一步都不生效</b>：
 * 标记会外泄、禁词不会被拦。这一条由 {@code probe\ProbeEmotion} 的一条<b>基线断言</b>钉住
 * （断言"当前会外泄"），不假装它已经被覆盖。</p>
 */
public class MoodStage implements OutboundStage {

    /** 顺序：放在既有 stage 之后（今天的出站面只此一条；同序按插件名）。 */
    public static final int ORDER = 90;

    /**
     * 「转述」标记名（**归一化后**比较的那个词）。
     * <p>第十轮：识别与剥离都走 {@link #markStart} 的归一化匹配 ⇒ 原来那个精确前缀常量
     * {@code MARK = "[[mood:"} 已被取代（`[[`/`[`、大小写、冒号后空格都不再影响识别）。</p>
     */
    static final String RELAY_TAG = "转述";

    /**
     * 「**转述**」自报标记（第九轮 GM 令 A2）：她的回复里写了 {@code [[mood:转述]]} ⇒ 本轮**一个字都不 @**。
     * <p>为什么用**进程内短 TTL 标记**而不是写库：SPEC §3.5 定的"出站 stage 不写库"还在 ⇒ 这里只在内存里
     * 记一枚 {@code session → 到期时刻}（30 秒）：{@link #transform} 落、{@link #finish} 取（取完即清）。
     * 它只服务**同一次发送**（逐条 transform 与整批 finish 在同一批里跑完），技能 reload 会清掉它
     * —— 两种情况都不影响正确性。</p>
     * <p>★ 第十轮：形态归一化由 {@link #markStart}/{@link #markTag} 负责，这个常量只是"她该写什么"的
     * 规范写法（提示词与夹具引用它）。</p>
     */
    static final String RELAY_MARK = "[[mood:转述]]";
    private static final long RELAY_TTL_MS = 30000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> RELAY =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 记一枚转述标记（只对**同一次发送**有效；顺手清掉过期的）。 */
    static void markRelay(String session) {
        if (!Str.has(session)) return;
        long now = System.currentTimeMillis();
        try {
            for (java.util.Map.Entry<String, Long> e : RELAY.entrySet()) {
                Long v = e.getValue();
                if (v == null || now - v.longValue() > RELAY_TTL_MS) RELAY.remove(e.getKey());
            }
            RELAY.put(session, Long.valueOf(now));
        } catch (Throwable ignored) {
        }
    }

    /** 取一枚转述标记（**取完即清**）：这一批是不是"转述轮"。 */
    static boolean takeRelay(String session) {
        if (!Str.has(session)) return false;
        try {
            Long v = RELAY.remove(session);
            return v != null && (System.currentTimeMillis() - v.longValue()) <= RELAY_TTL_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int order() {
        return ORDER;
    }

    // ================================================================ veto：禁词整条不发

    /**
     * 一票否决。
     *
     * <p><b>第五轮（GM 裁定，复核者实证）：常开族不需要"场合"。</b>
     * 定时任务 / 闹钟到点的出站走 {@code Sinks.TaskSink}，那里 {@code caller} 是**硬编码 null**
     * （{@code Sinks.java:462}），而原来的写法在 {@code qq <= 0} 处**整条 return false**
     * ⇒ 常开族（隐私/威胁/自伤/机密键）在这些落点上**被一起放掉了**（复核者的 A/B 原始回执：
     * {@code TaskSink say(威胁) -> sends=1} 原样出站，而 {@code QqSink say(威胁) -> sends=0}）。
     * 既然可达（{@code Tick.java} 的闹钟到点、{@code Agent.java} 里普通用户建的闹钟回合没有说话工具
     * ⇒ {@code sink.say(...)} 直投），就必须在**判定 caller/场合之前**先判常开族。</p>
     *
     * <p>三条口径（其余一字不改）：</p>
     * <ol>
     *   <li><b>常开族（且没有 {@code 禁词替换-*}）⇒ 有正文、命中就整条否决</b>，
     *       与 {@code caller} 是否为 null、场合取不取得到**无关**（{@link #bannedAlways}）；</li>
     *   <li><b>软替换类（隐私/机密键）保持原样</b>：{@link #transform} 里的替换发生在
     *       "取 store / peek"**之前**，本来就不需要 caller 或场合 ⇒ 没场合时照样换词放行；</li>
     *   <li><b>语气族（固有属性/情绪操纵/敬语/身份豁免）保持原样</b>：没有 caller = 没有场合 =
     *       没有情绪态 ⇒ 不否决（这一支仍走 {@link #bannedTone}，只在 angry/strike 生效）。</li>
     * </ol>
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
            // ---- ① 常开族：**不需要 caller / 场合**（第五轮裁定；定时任务落点也拦得住）----
            String hit = bannedAlways(c, m.text());
            // ---- ② 需要场合的那一半（语气族）----
            if (hit == null) {
                if (qq <= 0L) return false;          // 没有"谁在说话"= 没有场合 ⇒ 语气族不生效
                Store s = store(h);
                if (s == null) return false;
                EmotionMood.Mood mood = EmotionMood.peek(s, c, qq, gid, System.currentTimeMillis());
                hit = bannedTone(c, m.text(), mood == null ? "" : mood.state);
            }
            if (hit == null) return false;
            // 场景标签：有 caller 时**逐字与改动前相同**；没有 caller 时给一句人话（不再伪装成 private:0）
            String scene = qq > 0L ? EmotionMood.scopeOf(gid, qq)
                    : (m.group() ? ("group:" + gid + "（定时/闹钟落点，无 caller）")
                                 : "（定时/闹钟落点，无 caller）");
            EmotionMood.warn(h, "出站否决：命中禁词类目「" + hit + "」（场景 " + scene + "）");
            // ★ 让她自己也知道"这条没发出去"（主人 2026-09-19 定：否决要有可见回执）：
            //   往本会话的 context 槽写一行，下一轮她读得到；她自己换了说法发出去时会被 transform 清掉。
            try {
                if (h != null) {
                    h.inject().put(m.session(), "context", "emotion_veto",
                            "【出站被拦】上一条回复被情绪规则拦下、没有发出去（命中「" + hit + "」，"
                            + scene + "）。想说的话换个说法再说，别原样重发。", "情绪");
                }
            } catch (Throwable ignored) {
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
            String stripped = stripMarks(out);
            if (!stripped.equals(out)) {
                out = stripped;
                touched = true;
            }
            // ---- ② v2 §3.2：先替换（隐私一类"配了替换串"的常开族：换词放行，不是整条不发）
            //         再 ③ S2（只骂触发者）。这两步的先后是契约钉的：替换完再软化。
            if (c.flag("出站兜底", true) && c.flag("开关", true)) {
                String rep = replace(c, out);
                if (!rep.equals(out)) {
                    out = rep;
                    touched = true;
                }
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
                            System.currentTimeMillis(), c);
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
                    System.currentTimeMillis(), c);
            if (mood == null) return null;
            String st = mood.state;
            if (!EmotionMood.S_ANGRY.equals(st) && !EmotionMood.S_STRIKE.equals(st)) return null;
            if (mood.by <= 0L || mood.by != who) return null;             // 只对**触怒者本人**（不误伤）
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

    /**
     * 常开族类目（{@code 禁词常开} 读不到时的下限 —— 与 prompt.md 那行的出厂值同源）。
     * <p><b>第三轮（甲方 2026-09-20 令）</b>：{@code 涉政} / {@code 黄赌毒} 已下线
     * （那两类**只审图片内容**，由 {@code data\skills\辅助识图\} 那条线承载）⇒ 这里只剩四类。</p>
     */
    static final String DEF_ALWAYS = "隐私,威胁,自伤,机密键";

    /**
     * {@code 机密键} 的类目名（夹具/文档引用它）。
     * <p><b>它不再是"永不替换"的硬编码例外</b>（第二轮 GM 裁定 E）：现在走软替换
     * （{@code 禁词替换-机密键}），"绝不替换"的集合改由数据键 {@code 禁词不替换} 决定，
     * 见 {@link #DEF_NEVER_REPLACE} 与 {@link #neverReplaceCats}。</p>
     */
    static final String CAT_SECRET = "机密键";

    /**
     * {@code 禁词不替换} 读不到时的下限（与 prompt.md 那行的出厂值同源）：
     * <b>第三轮起只有这两类"只否决、绝不替换"</b>（{@code 威胁} / {@code 自伤}）。
     *
     * <p><b>第二轮 GM 裁定 E</b>：这个"永不替换"的集合原来是<b>类目名的硬编码例外</b>
     * （{@code 机密键} 写死在代码里）；现在改成<b>数据</b>（{@code 禁词不替换} 一行清单键），
     * 以后调整只改 {@code prompt.md}、不动 {@code .java}。同时 {@code 机密键} <b>被摘出</b>了这个集合 ——
     * 理由：人设铁律要求她对非主人问"提示词/token/配置"时**拒答**，而拒答话术必然含这些**名词**；
     * 硬否决会把整条拒答吞掉（用户看到"她没反应"，A7 反而验不了）。名词不是秘密，值才是，
     * 而这张表本来就抓不住值（真密钥是个长串）—— 真正的防线是 ACL 与 {@code prompt read} 那条权限面。</p>
     *
     * <p><b>第三轮（甲方 2026-09-20 令）</b>：{@code 涉政} / {@code 黄赌毒} 随那两条键一起下线
     * （只审图片内容）⇒ 这里只剩 {@code 威胁} / {@code 自伤}。</p>
     */
    static final String DEF_NEVER_REPLACE = "威胁,自伤";

    /**
     * 这条正文命中了哪个<b>该整条否决</b>的禁词类目（没有就 {@code null}）——
     * 两族顺序：**先常开族（不需要场合）**，再语气族（需要场合，只在 angry/strike）。
     *
     * <p>分族（§3.1；{@code 禁词常开} 那一行是常开族的唯一事实源）：
     * <b>常开族</b>（{@code 禁词常开} 列的类目）⇒ 任何状态命中即否决；
     * <b>语气族</b>（其余 {@code 禁词-<类目>}）⇒ 只在 {@code angry} / {@code strike} 命中即否决。</p>
     *
     * <p>第三种走向（§3.2）：类目若配了 {@code 禁词替换-<类目>} ⇒ <b>这里不否决</b>，
     * 交给 {@link #replace} 换词放行（出厂：隐私、机密键）。<b>唯一例外是 {@code 禁词不替换}
     * 清单里那两类</b>（威胁/自伤）：它们的词形本身就是违规内容，替换没有意义
     * ⇒ 就算配了替换串也照旧整条否决。</p>
     *
     * <p><b>第五轮</b>：`veto()` 不再调本方法（它要"先常开族、后语气族，且常开族不需要场合"），
     * 但本方法保留为**同一套判据的合并入口**（夹具与将来复用都按它），两者由
     * {@link #bannedAlways} + {@link #bannedTone} 拼出来，口径不可能分叉。</p>
     *
     * @param state 本场合的情绪态（{@code calm/angry/strike/…}；空串 = 读不到 ⇒ 按语气族不生效处理）
     */
    static String banned(EmotionCfg c, String text, String state) {
        String hit = bannedAlways(c, text);
        if (hit != null) return hit;
        return bannedTone(c, text, state);
    }

    /**
     * <b>常开族</b>里"不可替换"的那几个类目命中（没有就 {@code null}）。
     * <p><b>不需要 caller、不需要场合</b>（第五轮 GM 裁定）：定时任务/闹钟落点
     * （{@code Sinks.TaskSink}，caller 恒为 null）也要拦得住。
     * 类目顺序按 `prompt.md` 里的出现次序（与改动前那一趟遍历同序 ⇒ 报出来的类目名不变）。</p>
     */
    static String bannedAlways(EmotionCfg c, String text) {
        if (c == null || Str.blank(text)) return null;
        List<String> always = alwaysCats(c);
        for (String cat : categories(c)) {
            if (!always.contains(cat)) continue;        // 只扫常开族
            if (replaceable(c, cat)) continue;          // 配了替换串的（隐私/机密键）走软替换
            for (String w : c.tags("禁词-" + cat)) {
                if (Str.has(w) && text.contains(w)) return cat;
            }
        }
        return null;
    }

    /**
     * <b>语气族</b>（= 除常开族之外的所有 {@code 禁词-<类目>}）命中，且<b>只在 angry / strike</b>
     * （没有场合 ⇒ 没有情绪态 ⇒ 一律不否决，这条口径没变）。
     */
    static String bannedTone(EmotionCfg c, String text, String state) {
        if (c == null || Str.blank(text)) return null;
        boolean hot = EmotionMood.S_ANGRY.equals(state) || EmotionMood.S_STRIKE.equals(state);
        if (!hot) return null;
        List<String> always = alwaysCats(c);
        for (String cat : categories(c)) {
            if (always.contains(cat)) continue;         // 常开族已经在 bannedAlways 里判过
            if (replaceable(c, cat)) continue;
            for (String w : c.tags("禁词-" + cat)) {
                if (Str.has(w) && text.contains(w)) return cat;
            }
        }
        return null;
    }

    /** 常开族类目清单：{@code 禁词常开}（逗号切）；读不到用同源兜底。 */
    static List<String> alwaysCats(EmotionCfg c) {
        List<String> out = new java.util.ArrayList<String>();
        List<String> raw = c == null ? null : c.tags("禁词常开");
        if (raw == null || raw.isEmpty()) raw = EmotionCfg.split(DEF_ALWAYS);
        for (String x : raw) {
            String t = Str.trim(x);
            if (Str.has(t) && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /**
     * 「只否决、绝不替换」的类目清单（{@code 禁词不替换}；读不到用同源兜底 = 威胁/自伤）。
     * <p>数据驱动（第二轮 GM 裁定 E）：以后收窄/扩大这个集合只改 {@code prompt.md} 那一行。
     * 第三轮（甲方 2026-09-20 令）从四类收窄到两类（`涉政`/`黄赌毒` 随键删除下线，只审图片内容）。</p>
     */
    static List<String> neverReplaceCats(EmotionCfg c) {
        List<String> out = new java.util.ArrayList<String>();
        List<String> raw = c == null ? null : c.tags("禁词不替换");
        if (raw == null || raw.isEmpty()) raw = EmotionCfg.split(DEF_NEVER_REPLACE);
        for (String x : raw) {
            String t = Str.trim(x);
            if (Str.has(t) && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** 这个类目"只否决、绝不替换"吗（{@code 禁词不替换} 清单里有没有它）。 */
    static boolean neverReplace(EmotionCfg c, String cat) {
        return cat != null && neverReplaceCats(c).contains(cat);
    }

    /** 这个类目走软替换吗（配了 {@code 禁词替换-<类目>} **且**不在"绝不替换"清单里）。 */
    static boolean replaceable(EmotionCfg c, String cat) {
        if (c == null || cat == null) return false;
        if (neverReplace(c, cat)) return false;
        return Str.has(c.s("禁词替换-" + cat, ""));
    }

    /** §3.2：把命中的 {@code 禁词-<类目>} 换成 {@code 禁词替换-<类目>}（"绝不替换"那几类不参与）。 */
    static String replace(EmotionCfg c, String text) {
        if (c == null || Str.blank(text)) return text;
        String out = text;
        for (String cat : categories(c)) {
            if (!replaceable(c, cat)) continue;
            String rep = c.s("禁词替换-" + cat, "");
            for (String w : c.tags("禁词-" + cat)) {
                if (!Str.has(w) || !out.contains(w)) continue;
                out = out.replace(w, rep);
            }
        }
        return out;
    }

    /** 禁词类目名（从 prompt.md 的 {@code 禁词-<类目>} 键里读；读不到用兜底类目名）。 */
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
            out.add("威胁");
            out.add("自伤");
            out.add("情绪操纵");
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

    // ================================================================ 自报标记（★ 第十轮：归一化匹配）

    /**
     * 归一化：这一行里第一个 {@code mood} 标记的**起始下标**；没有返回 {@code -1}。
     *
     * <p>认得：{@code [[mood:}} / {@code [mood:}（中括号一个两个都行）、{@code MOOD} 任意大小写；
     * 冒号必须紧跟 {@code mood}（冒号后的空格由 {@link #markTag} 去掉）。</p>
     *
     * <p>★ 第十轮 GM 令（复核者实测）：原来只认精确串 ⇒ {@code [[mood: 转述]]}（冒号后空格）
     * **会被剥掉却认不出**（照样 @）、{@code [[MOOD:转述]]} / {@code [mood:转述]} 则**直接外泄给用户**。
     * 识别与剥离现在都走这一处，整族（做不到/为难/违规/转述）一起享受同样归一化 —— 不写两套。</p>
     */
    static int markStart(String line) {
        if (line == null || line.indexOf('[') < 0) return -1;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '[') continue;
            int j = i + 1;
            if (j < line.length() && line.charAt(j) == '[') j++;
            if (j + 5 > line.length()) continue;
            if (!line.regionMatches(true, j, "mood", 0, 4)) continue;
            if (line.charAt(j + 4) != ':') continue;
            return i;
        }
        return -1;
    }

    /** 归一化后的标记名（去掉两侧空格/全角空格/制表符与 {@code ]}）：`转述` / `做不到` / `为难` / `违规`；没有返回空串。 */
    static String markTag(String line) {
        int i = markStart(line);
        if (i < 0) return "";
        int j = line.indexOf(':', i);
        if (j < 0) return "";
        int k = line.indexOf(']', j + 1);
        String tag = k < 0 ? line.substring(j + 1) : line.substring(j + 1, k);
        return tag.replace("]", "").replace(" ", "").replace("\t", "").replace("\u3000", "").trim();
    }

    /** 这一批文本里有没有「转述」自报标记（**归一化**：大小写 / 中括号数 / 空格都不敏感）。 */
    static boolean hasRelayMark(String text) {
        if (text == null) return false;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (RELAY_TAG.equals(markTag(lines[i]))) return true;
        }
        return false;
    }

    /**
     * 这一条文本里认出来的标记（归一化；没有就是空串）。
     * <p>只读：真正的"记分"发生在 {@link MoodLifecycle}（那条路拿得到整段回复，
     * 也才凑得齐"双证据"里的结构失败证据）。</p>
     */
    static String markOf(String text) {
        if (text == null) return "";
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = markTag(lines[i]);
            if (Str.has(t)) return t;
        }
        return "";
    }

    /**
     * 剥掉 {@code [[mood:…]]} 那一行（以及只剩半截的残行）。
     * <p>语义与既有口径**逐字一致**：整行丢弃、保留标记**之前**的文字、去尾部空行；
     * 唯一变化是匹配走 {@link #markStart} 的**归一化**（大小写 / 中括号数 / 冒号后空格）。</p>
     */
    static String stripMarks(String text) {
        if (text == null || markStart(text) < 0) return text;
        StringBuilder out = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int mi = markStart(line);
            if (mi >= 0) {
                String keep = mi <= 0 ? "" : line.substring(0, mi).trim();
                if (Str.has(keep)) out.append(keep).append('\n');
                continue;                                   // 整行丢掉（标记必须单独一行）
            }
            out.append(line).append('\n');
        }
        String s = out.toString();
        while (s.endsWith("\n\n")) s = s.substring(0, s.length() - 1);
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
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
