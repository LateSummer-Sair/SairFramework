import sair.v4.ext.BuildContext;
import sair.v4.ext.ContextProvider;
import sair.v4.kit.Str;
import sair.v4.skill.Host;
import sair.v4.store.Store;

/**
 * 「情绪」的注入面：<b>全工程唯一的语气来源</b>（一行 §5 的"情绪块"）。
 *
 * <h3>为什么只有一个来源</h3>
 * <p>注入面本来是两条语义（离散 5 态 + 连续 0~100 的强度/亲疏）。两个 provider 各说一套语气 =
 * 模型看到互相冲突的指令 = 行为不可控，所以这里<b>只保留一个 provider</b>：
 * 默认产「情绪块」（{@link EmotionMood#block}），只有档位层整块产不出来时
 * （{@code 情绪开关: false} / {@code prompt.md} 读不出来）才退回既有那一行（{@link #legacy}）。</p>
 *
 * <h3>挂在哪</h3>
 * <ul>
 *   <li>{@link ContextProvider}，{@link #stable()} = {@code false}（<b>易变段</b>）；</li>
 *   <li>{@link #order()} = {@code 90} —— 必须<b>大于所有既有 provider</b>
 *       （记忆召回 10 / 用户画像 20 / 群成员映射 30 / 群印象 50），越靠后越有权威；</li>
 *   <li>{@link #maxChars()} = {@code prompt.md} 的「预算」（出厂 {@value EmotionCfg#DEF_BUDGET}），
 *       情绪块 ≤ 预算字符是<b>防被全局预算丢掉</b>的硬要求（丢弃从 order 最大的整段开始）。
 *       为什么是 1000（情绪 v2）：情绪块现在要装<b>六行</b>（事实 + 语气 + <b>范例</b> + 边界 +
 *       自报要求 + 覆盖句），而 v1 的 400 是按"五行、实测最坏 382"定的 ——
 *       见 {@code prompt.md} 的「预算」那一段（那一行与 {@code EmotionCfg.DEF_BUDGET} 同源）。</li>
 * </ul>
 *
 * <h3>只读</h3>
 * <p>本类<b>一个字都不写</b>：读 {@code kv} 的 {@code mood:<场合>}、读 {@code emotion} 表
 * （{@link EmotionState#peek} 不落库）、读同目录的 {@code prompt.md}，然后拼块。
 * <b>不衰减、不取时间戳、不用随机数</b> —— 网格翻页的落盘只在 {@code on_message} / {@code on_timer} 里发生，
 * 所以"同一状态同一轮两次 build 必然逐字节一致"。</p>
 *
 * <h3>块的两条逐字锚点（探针按字面断言）</h3>
 * <ol>
 *   <li>首行匹配 {@code ^mood: \{"state":"…"}（机器可读的事实形状）；</li>
 *   <li>末行逐字以 {@code 【本轮语气以本段为准】} 开头（覆盖句，见 {@link EmotionMood#coverLine}）。</li>
 * </ol>
 */
public class EmotionProvider implements ContextProvider {

    /** 兜底默认值（真正生效的值在 prompt.md 的「行模板」与各档位表里）。 */
    private static final String DEF_LINE =
            "情绪：{emoji}{mood}｜怒{angry}/100｜悲{sad}/100[｜{romance}]；亲疏{affinity}；"
            + "对{who}：{attitude}[；关系：{relation}][；语气：{tone}][；TA刚说这句话时是「{last}」]";

    /**
     * {@link #maxChars()} 的签名里没有 Host（基板调它时不给上下文），
     * 所以只能记下"最近一次从 prompt.md 读到的预算" —— 第一次调用之前用兜底值
     * （{@code EmotionCfg.DEF_BUDGET}，与 prompt.md 的出厂值同源，v2 = 1000）。
     */
    private volatile int budget = EmotionCfg.DEF_BUDGET;

    // ================================================================ ContextProvider

    @Override
    public boolean stable() {
        return false;              // 易变段：状态每轮都可能不一样
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public int maxChars() {
        int b = budget;
        return b > 0 ? b : EmotionCfg.DEF_BUDGET;
    }

    /** 没有具体的人（定时任务落点、没有调用者）就没有"情绪"可言。 */
    @Override
    public boolean appliesTo(BuildContext b) {
        try {
            return b != null && b.qq() > 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String build(BuildContext b) {
        try {
            if (b == null) return "";
            Host h = b.host();
            if (h != null && !EmotionCfg.matches(h)) EmotionCfg.forget();
            EmotionCfg c = EmotionCfg.of(h);
            budget = c.i("预算", EmotionCfg.DEF_BUDGET);
            if (!c.flag("开关", true)) return "";
            long qq = b.qq();
            if (qq <= 0L) return "";

            // 拿不到库（host 为 null / store 为 null）→ 这一段不出现（不是异常）
            Store s = store(h);
            if (s == null) return "";

            // 本插件不提供工具（没有 op 可判）：本 provider 只读情绪库，跑在 SYSTEM 主体上恒放行。

            // ---- 档位层（离散 5 态）：事实源 = kv 的 mood:<场合>
            // 关掉档位层（prompt.md 的「情绪开关」= false）= 回到旧口径那一行（下面是 legacy）。
            // ★ 注意这里是"整段跳过"而不是 return ""：关掉档位层不该让"什么语气都不注入"，
            //   旧那一行（0~100 的怒气/亲疏）还在，它本来就是兜底来源。
            if (c.flag("情绪开关", true)) {
                long gid = b.group() ? b.groupId() : 0L;
                // now 传 0：provider 是**只读**的，网格翻页的落盘只发生在 on_message / on_timer 里；
                // 这里不取时间戳 ⇒ "同一状态两次 build 逐字节一致"永远成立（前缀缓存与探针都靠它）。
                EmotionMood.Mood m = EmotionMood.peek(s, EmotionMood.scopeOf(gid, qq), 0L, c);
                String block = EmotionMood.block(c, m);
                int lim = budget > 0 ? budget : EmotionCfg.DEF_BUDGET;
                if (Str.has(block) && block.length() > lim) block = trimBlock(c, m, lim);
                if (Str.has(block)) return block;
            }

            // ---- 档位层被关掉 / 整块产不出来：退回既有那一行（连续 0~100 的强度与亲疏）
            return legacy(b, h, c, qq);
        } catch (Throwable t) {
            return "";                                   // 插件绝不拖垮一轮对话
        }
    }

    /**
     * 档位块的预算护栏：超了就逐行丢<b>可选的中间行</b>，<b>首行（事实）与末行（覆盖句）一个字都不动</b>
     * —— 那两条是逐字锚点（探针按字面断言，模型靠它们认权威）。
     *
     * <p>丢弃次序（先丢最不致命的，与方法体里的 ①②③④⑤ 逐条对应）：
     * ① 整行丢边界行 → ② 整行丢<b>范例行</b> → ③ 整行丢自报要求行
     * → ④ 把语气行截短到"刚够放下首行与末行"（不是固定丢一半）→ ⑤ 只剩首行 + 末行（最后一道，绝不再退）。
     * 留语气不留边界是刻意的：
     * 模型"怎么说话"靠语气行，"不许越什么线"在禁词表 + 出站 stage 那里还有一道代码兜底，
     * 所以预算真的不够时，先保语气。范例行排在第三刀之后（它只是"教她怎么说话"的样例，
     * 比红线复述与机制要求都更可牺牲）。</p>
     *
     * <p><b>自报要求行为什么排在末尾那一档</b>：它是"她会写标记"的唯一来源，丢了那一条触发这一轮就不会发生
     * （见 {@link EmotionMood#selfAsk}）—— 但它丢的是<b>一轮的机制可用性</b>，而边界行丢的是
     * <b>红线复述</b>；两者都有代码侧兜底（标记那一路是"少一次可能的计分"，不会误判），
     * 所以按"先丢最不致命的"排在同一档。正常配置下这些刀都不会落下：
     * v2 的出厂预算 1000 比六行块的最坏体量宽裕得多。</p>
     */
    private static String trimBlock(EmotionCfg c, EmotionMood.Mood m, int lim) {
        String fact = EmotionMood.factLine(c, m);
        String cover = EmotionMood.coverLine(c);
        String tone = EmotionMood.toneLine(c, m.state);
        String eg = EmotionMood.exampleLine(c, m.state);
        String bound = EmotionMood.boundLine(c, m.state);
        String self = EmotionMood.selfAsk(c);
        String state = EmotionMood.label(c, m.state);
        String mid = "【此刻情绪：" + state + "】" + tone;
        // 与 EmotionMood.block() 同一份形状（那边是唯一的"正常路径"）；超长才走到下面的降级梯子
        String full = EmotionMood.block(c, m);
        if (full.length() <= lim) return full;
        // ① 丢边界行
        String noBound = lines(fact, mid, eg, self, cover);
        if (noBound.length() <= lim) return noBound;
        // ② 丢范例行（v2：它是"教她怎么说话"的样例，比红线复述与机制要求都更可牺牲）
        String noEg = lines(fact, mid, self, cover);
        if (noEg.length() <= lim) return noEg;
        // ③ 丢自报要求行
        String noSelf = lines(fact, mid, cover);
        if (noSelf.length() <= lim) return noSelf;
        // ④ 语气行截到还能放下事实与覆盖句为止
        int room = lim - fact.length() - cover.length() - 3 - "【此刻情绪：" .length() - state.length() - 1;
        if (room > 12) {
            String cut = tone.length() > room ? tone.substring(0, room) : tone;
            return fact + "\n" + "【此刻情绪：" + state + "】" + cut + "\n" + cover;
        }
        // ⑤ 只剩两条锚点（这是最后一道，绝不会再退）
        return fact + "\n" + cover;
    }

    /** 把非空的几行拼起来（空行不参与 —— 与 {@link EmotionMood#block} 的可选行口径一致）。 */
    private static String lines(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!Str.has(p)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(p);
        }
        return sb.toString();
    }

    /**
     * 既有那一行（连续 0~100 的怒气/伤心/亲疏/恋爱）。
     * <p>只在<b>档位层没产出任何东西</b>时才会走到这里（{@code 情绪开关: false} 或 prompt.md 读不出来），
     * 所以正常路径下"注入只有一个来源"依然成立。</p>
     */
    private String legacy(BuildContext b, Host h, EmotionCfg c, long qq) {
        try {
            Store s = store(h);
            if (s == null) return "";
            long gid = b.group() ? b.groupId() : 0L;

            // peek：没有这一行时给一份"平静/陌生人"的空状态，但**不落库**（provider 不写库）
            EmotionState.St st = EmotionState.peek(s, c, qq, gid, 0L);
            if (st == null) return "";
            // 第一次说话（还没有行）默认也注入一行"平静/陌生人"的态度 —— V3 永远注入情绪块；
            // 想要"只有留下过痕迹才注入"就把 prompt.md 的「无记录也注入」改成 false。
            if (st.id <= 0L && !c.flag("无记录也注入", true)) return "";

            double angry = st.angry;
            double sad = st.sad;
            int aff = st.affinity;
            String rom = st.romance;

            String emoji = EmotionCfg.pick(c.levels("表情"), angry, sad, aff, rom);
            String mood = EmotionCfg.pick(c.levels("心情"), angry, sad, aff, rom);
            String attitude = EmotionCfg.pick(c.levels("态度"), angry, sad, aff, rom);
            String relation = EmotionCfg.pick(c.levels("关系"), angry, sad, aff, rom);
            String tone = EmotionCfg.pickTones(c.levels("语气"), angry, sad, aff, rom,
                    c.s("语气分隔符", "；"));

            // V3 口径：对方情绪是「平静」时不注入那一段（EMOTION_NEUTRAL 不显示）
            String calm = c.s("平静标签", EmotionCfg.DEF_CALM);
            String last = st.lastEmotion;
            if (!Str.has(last) || last.equals(calm)) last = "";

            // 恋爱后缀（V3 的 emotion-romance-suffix：只在恋人时出现）
            String romance = "";
            if (EmotionState.R_LOVER.equals(rom)) {
                romance = EmotionCfg.fill(c.s("恋爱后缀", ""), "lover", lover(st));
            }

            String line = EmotionCfg.fill(c.s("行模板", DEF_LINE),
                    "emoji", emoji,
                    "mood", mood,
                    "angry", num(angry),
                    "sad", num(sad),
                    "affinity", String.valueOf(aff),
                    "attitude", attitude,
                    "relation", relation,
                    "tone", tone,
                    "last", last,
                    "romance", romance,
                    "lover", lover(st),
                    "state", rom,
                    "who", who(b, c),
                    "qq", String.valueOf(qq),
                    "group", gid > 0L ? String.valueOf(gid) : "");
            String out = Str.trim(line);
            return Str.has(out) ? out : "";
        } catch (Throwable t) {
            return "";
        }
    }

    // ================================================================ 小工具

    private static Store store(Host h) {
        if (h == null) return null;
        try {
            return h.store();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 对象名（群名片/昵称；拿不到就用 prompt.md 的「无名对象」）。 */
    private static String who(BuildContext b, EmotionCfg c) {
        String def = c.s("无名对象", EmotionCfg.DEF_WHO);
        try {
            String n = b.caller() == null ? "" : b.caller().name();
            return Str.blank(n) ? def : n;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 恋爱对象 QQ（没有就是空串 —— 可选组会把它整段吃掉）。 */
    private static String lover(EmotionState.St st) {
        return st != null && st.romanceId > 0L ? String.valueOf(st.romanceId) : "";
    }

    /** 数值：按 V3 的 {@code %.0f} 取整（避免把 14.999999 印进上下文）。 */
    private static String num(double v) {
        if (Double.isNaN(v)) return "0";
        return String.valueOf(Math.round(v));
    }
}
