import java.util.List;

import sair.v4.ext.Outbound;
import sair.v4.ext.OutboundStage;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Host;
import sair.v4.store.Store;

/**
 * 「情绪」的出站兜底：<b>禁词否决 / 只骂触发者 / 自报标记剥离</b>（规格 §6 S1–S3、S10）。
 *
 * <h3>只做纯文本变换</h3>
 * <p>出站 stage 的判定主体是<b>当轮调用者</b>（不是 SYSTEM），
 * 所以这里<b>不写库、不发消息</b>（{@code h.napcat()} 与按调用者的 {@code needDb(…, 'W')} 会被拒）。
 * 状态读取用 {@link Store#kv}（只要读位）+ {@link Host} 的只读口。</p>
 *
 * <h3>三个口怎么分工</h3>
 * <ul>
 *   <li>{@link #veto}：当前场合是 {@code angry} 且这一条命中「禁词」类目 ⇒ <b>整条不发</b>。
 *       类目清单只在 {@code prompt.md} 的「禁词」表里（<b>不硬编码</b>：改一个词要动 {@code .java}
 *       就得 reload，reload 会掉内存态）。</li>
 *   <li>{@link #transform}：① 剥掉自报标记那一行（用户不许看到 {@code [[mood:…]]}）；
 *       ② <b>只骂触发者</b>：状态里记的 {@code trigger_by} 与这条消息的落点不是同一个人，
 *       却带着攻击性词 ⇒ 把那几个词降级掉（S2）。</li>
 *   <li>{@link #finish}：不用（逐条口够了；整表改是别的插件的活）。</li>
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

    /** 自报标记：{@code [[mood:做不到]]} 这一族。 */
    private static final String MARK = "[[mood:";

    @Override
    public int order() {
        return ORDER;
    }

    // ================================================================ veto：禁词整条不发

    @Override
    public boolean veto(Outbound m) {
        try {
            if (m == null) return false;
            Host h = m.host();
            EmotionCfg c = EmotionCfg.of(h);
            if (!c.flag("开关", true) || !c.flag("情绪开关", true)) return false;
            if (!c.flag("出站兜底", true)) return false;
            Store s = store(h);
            if (s == null) return false;
            long qq = m.caller() == null ? 0L : m.caller().qq();
            long gid = m.group() ? m.target() : 0L;
            if (qq <= 0L) return false;
            EmotionMood.Mood mood = EmotionMood.peek(s, c, qq, gid, System.currentTimeMillis());
            if (!EmotionMood.S_ANGRY.equals(mood.state)) return false;
            String hit = banned(c, m.text());
            if (hit == null) return false;
            EmotionMood.warn(h, "出站否决：命中禁词类目「" + hit + "」（场景 " + EmotionMood.scopeOf(gid, qq) + "）");
            return true;
        } catch (Throwable t) {
            return false;                                   // 出站兜底绝不拖垮一条发送
        }
    }

    // ================================================================ transform：剥标记 + 只骂触发者

    @Override
    public String transform(Outbound m) {
        try {
            if (m == null) return null;
            String text = m.text();
            if (!Str.has(text)) return null;
            Host h = m.host();
            EmotionCfg c = EmotionCfg.of(h);
            String out = text;
            boolean touched = false;
            // ---- ① S10：自报标记必须剥离（含"只写了一半"的残行）
            String stripped = stripMarks(out);
            if (!stripped.equals(out)) {
                out = stripped;
                touched = true;
            }
            // ---- ② S2：只骂触发者（不是他，就把攻击性词降级）
            if (c.flag("出站兜底", true) && c.flag("开关", true)) {
                Store s = store(h);
                long gid = m.group() ? m.target() : 0L;
                if (s != null) {
                    // 看的是**这个场合**的情绪态（情绪按场合隔离存，trigger_by 记的是"谁把她惹毛的"）。
                    // 场合里没人惹过她（trigger_by=0）就不管；场合是 calm 也不管（S2 只在生气态生效）。
                    //
                    // 两条判据（都只看"要发给谁 vs 谁惹的她"）：
                    //   ① 私聊：落点 QQ ≠ trigger_by ⇒ 降级（给无关的人带刺）；
                    //   ② 群聊：这条出站的 caller ≠ trigger_by ⇒ 由她"替甲骂乙"的风险 ⇒ 降级。
                    //   ★ 群聊那条刻意保守（群回复的落点是**群**，基板不暴露"@了谁"）：
                    //     宁可偶尔把该冲的话软化，也不让无关的人挨骂 —— 红线优先于风格。
                    // 三条判据（都只看"要发给谁 vs 谁惹的她"）：
                    //   ① 群聊：这条出站的 caller ≠ trigger_by ⇒ 她可能在"替甲骂乙" ⇒ 降级；
                    //   ② 私聊：落点 QQ ≠ trigger_by ⇒ 给无关的人带刺 ⇒ 降级；
                    //   ③ 私聊但对方就是 trigger_by ⇒ 带刺允许（有分寸地怼他本人）。
                    //   ★ 群聊那条刻意保守（群回复的落点是**群**，基板不暴露"@了谁"）：
                    //     宁可偶尔把该冲的话软化，也不让无关的人挨骂 —— 红线优先于风格。
                    long who = m.caller() == null ? 0L : m.caller().qq();
                    boolean group = gid > 0L;
                    long scopeQq = group ? 0L : who;           // 状态按"谁在跟她说话"那个场合存
                    EmotionMood.Mood mood = EmotionMood.peek(s, EmotionMood.scopeOf(gid, scopeQq),
                            System.currentTimeMillis(), c);
                    boolean other = mood.by > 0L && EmotionMood.S_ANGRY.equals(mood.state)
                            && (group ? (who > 0L && who != mood.by) : (m.target() != mood.by));
                    if (other) {
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

    // ================================================================ 禁词 / 攻击词

    /** 这条正文命中了哪个禁词类目（没有就 null）。类目清单全在 prompt.md。 */
    static String banned(EmotionCfg c, String text) {
        if (c == null || Str.blank(text)) return null;
        for (String cat : categories(c)) {
            for (String w : c.tags("禁词-" + cat)) {
                if (Str.has(w) && text.contains(w)) return cat;
            }
        }
        return null;
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

    // ================================================================ 自报标记

    /** 剥掉 {@code [[mood:…]]} 那一行（以及只剩半截的残行）。 */
    static String stripMarks(String text) {
        if (text == null || text.indexOf(MARK) < 0) return text;
        StringBuilder out = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.indexOf(MARK) >= 0) {
                String keep = cut(line, MARK);
                if (Str.has(keep)) out.append(keep).append('\n');
                continue;                                   // 整行丢掉（标记必须单独一行）
            }
            out.append(line).append('\n');
        }
        String s = out.toString();
        while (s.endsWith("\n\n")) s = s.substring(0, s.length() - 1);
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    /** 标记所在行"标记之前"的内容（标记是她写在最后单独一行的，所以一般就是空）。 */
    private static String cut(String line, String mark) {
        int i = line.indexOf(mark);
        return i <= 0 ? "" : line.substring(0, i).trim();
    }

    /**
     * 这一条文本里认出来的标记（{@code 做不到 / 为难 / 违规}；没有就是空串）。
     * <p>只读：真正的"记分"发生在 {@link MoodLifecycle}（那条路拿得到整段回复，
     * 也才凑得齐"双证据"里的结构失败证据）。</p>
     */
    static String markOf(String text) {
        if (text == null) return "";
        int i = text.indexOf(MARK);
        if (i < 0) return "";
        int j = text.indexOf(']', i + MARK.length());
        if (j < 0) j = text.length();
        return Str.trim(text.substring(i + MARK.length(), j).replace("]", ""));
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
