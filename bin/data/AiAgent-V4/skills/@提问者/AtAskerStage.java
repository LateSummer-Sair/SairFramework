import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import sair.v4.auth.Caller;
import sair.v4.ext.Outbound;
import sair.v4.ext.OutboundStage;
import sair.v4.kit.Str;
import sair.v4.qq.MarkerTags;
import sair.v4.skill.Host;

/**
 * 「@提问者」：群里回复时，决定<b>要不要 @ 触发人</b>、<b>要不要引用触发者的那条消息</b>，
 * 并把决定好的前缀一次性加到"真正会发出去的首条"最前面。
 *
 * <h3>挂在哪</h3>
 * <p>{@link OutboundStage}，{@link #order()} = {@code 20}，走 {@link #finish(List, Outbound)}（整批收尾）：
 * 逐条口（{@code transform}）看不到后面哪条会被别的 stage 丢掉或洗空，"首条"就会挂到一条根本没发的消息上。
 * 基板在 {@code extra}（追加消息）之后调 {@code finish}，所以追加的表情包也算在 {@code parts} 里 ——
 * 本插件取 {@code parts} 里<b>第一个非空白元素</b>，与 V3 的 {@code out.get(0)} 同义。</p>
 *
 * <h3>★ 一条产法（重要）</h3>
 * <p>{@link #finish(List, Outbound)} 是<b>唯一</b>决定 @ 与引用的地方，也是唯一拼前缀的地方：
 * 两个决定共用同一份判据与同一份记账，不存在"两处各判一次"。要改口径只改这一个方法
 * （{@code prompt.md} 只给阈值与模板）。</p>
 *
 * <h3>R1 —— @ 分情况（判据全部可机器判定）</h3>
 * <p>要 @（任一成立）：</p>
 * <ol>
 *   <li>触发消息<b>@ 了她</b>（群内点名）—— 来自 {@code on_message} 记的账；</li>
 *   <li>触发者是<b>主人</b> —— 出站 stage 里直接可得（{@code m.caller().master()}），不依赖记账；</li>
 *   <li><b>本轮做了实事</b> —— ⚠ 见下面「拿不到的判据」；</li>
 *   <li>回复<b>正文长度 ≥ {@code 重要长度}</b>（默认 40 字符，不是一句应付）。</li>
 * </ol>
 * <p>都不成立 = 普通群聊搭话、未点名、非主人、短回复 ⇒ <b>不 @</b>。</p>
 *
 * <h3>⚠ 拿不到的判据：「本轮她调用了工具」</h3>
 * <p>需求里的 ③「她调用了工具（做了实事）」在本轮<b>拿不到</b>，而且这不是"没找对 API"：</p>
 * <ul>
 *   <li>发送类工具（{@code send/sendimage/sticker}）成功时基板会置 {@code Turn.spoke()}，
 *       而 {@code Agent.java:183-187} 在 {@code alreadyOut(t, session)}（= {@code t.spoke()}
 *       或 {@code sent} 台账里本回合已有发往此会话的记录）为真时<b>根本不走这条出站管线</b>
 *       ⇒ <b>stage 能跑起来，就说明本回合没有成功发出过任何东西</b>；</li>
 *   <li>非发送类工具（查库、上网、记忆维护……）在基板里<b>不留任何 stage 可读的痕迹</b>：
 *       {@code Turn} 的 state 只有 {@code media}/{@code chat_window}/{@code subagent} 三个键，
 *       而出站 stage 连 {@code Turn} 都拿不到（{@code Outbound} 没有它，{@code BuildContext.state}
 *       在出站路径上恒返回兜底值）。</li>
 * </ul>
 * <p><b>替代判据（本轮采用）</b>：模型自己在回复里写的控制标记 {@code <at>}（{@code prompt.md} 的
 * 「重要标记」，默认 {@code at}）—— 它在 {@link MarkerTags} 的白名单里<b>本来就有</b>
 * （{@code qq\MarkerTags.java:25-30}），今天<b>没有任何消费者</b>，而且剥除发生在 stage <b>之后</b>
 * （{@code term\Sinks.java:168-171}：先 {@code stage(...)}，再逐条 {@code MarkerTags.strip}），
 * 所以 stage 读得到、用户看不到 —— 零新增语法。这是"她自己判定这条重要"的显式信号。
 * <b>代价说清</b>：模型不写这个标记时这条判据等于不存在（提示词今天没教她写），所以 ③ 的替代
 * 覆盖率有限，真正兜底的是 ④（长回复）。</p>
 *
 * <h3>R2 —— 按时差决定是否引用触发者</h3>
 * <p>{@code dt = 本方法的执行时刻 − 触发消息的入站时刻}（{@code on_message} 钩子记的真入站毫秒，
 * 在 {@code onTurnStart} 钉住）：</p>
 * <ul>
 *   <li>{@code dt < 3 秒}（{@code 秒回不引用}）⇒ <b>绝不引用</b>；</li>
 *   <li>{@code dt > 10 秒}（{@code 慢回必引用}）⇒ <b>必须引用</b>；</li>
 *   <li>{@code 3 ≤ dt ≤ 10} ⇒ 只有"重要"（与 R1 <b>同一套判据</b>）才引用。</li>
 * </ul>
 * <p>引用 = 首条文本前加 {@code [CQ:reply,id=<触发消息的 message_id>]}（模板在 {@code prompt.md}）。
 * 不写别的引用写法。</p>
 *
 * <h3>拼接顺序：先 @ 再引用</h3>
 * <p>首条 = {@code [CQ:at,qq=…] [CQ:reply,id=…] 正文}。理由：</p>
 * <ol>
 *   <li>@ 在前<b>保住既有判据</b> —— {@code 已at前缀} 判的是"首条是否以 {@code [CQ:at,} 开头"
 *       （V3 逐字口径），引用码若插在最前，这条判据立刻失效，"模型自己 @ 过"就认不出来了；</li>
 *   <li>两个都是 CQ 码，<b>同一串里 NapCat 按段解析</b>，顺序不改变段的类型与内容
 *       （是否真被解析成引用气泡 = 真机待验项，见 {@code tmp\r12\REPORT.md}）。</li>
 * </ol>
 *
 * <h3>降级（拿不到记账时怎么办）</h3>
 * <ul>
 *   <li><b>@：保持旧口径（照加）</b>。理由：拿不到记账 = 连"这条是不是点名/主人"都不知道，
 *       这时把"该 @ 的仍然 @"（甲方明确要求不许破坏的部分）改成不 @，等于用一个不存在的证据
 *       去取消一个既有行为。缺记账只是记账面出问题（权限面/库不可用/钩子没跑），不是"这条不重要"。</li>
 *   <li><b>引用：不引用</b>。理由：引用是全新行为，且它要的是"精确的那一条 message_id"与"精确的时差"，
 *       猜错就是引用错人（V3 当年放弃引用回复正是怕这个）⇒ 取不到就绝不猜。</li>
 *   <li>单项缺失：记录在但 {@code message_id <= 0} ⇒ 只不引用（@ 照判）；记录超期（{@code 记录有效期秒}，
 *       默认 600）⇒ 整条当作没有。</li>
 * </ul>
 *
 * <h3>范围与护栏</h3>
 * <ul>
 *   <li>私聊 / 定时任务落点（{@code caller} 为 null）/ 提问者 QQ ≤ 0 → 返回 {@code null}（不改）——
 *       与既有 V3 口径一致，引用也<b>不</b>外扩到私聊；</li>
 *   <li>首条已经以 {@code [CQ:at,} 开头（模型自己 @ 了）→ 不重复加 @（引用照样可加）；</li>
 *   <li>只改"首条"那一个元素，返回的是<b>新表</b>；</li>
 *   <li>{@code m.host()} 可能为 null（探针直接注册的合成 stage）→ 读不到记账 ⇒ 走上面的降级，
 *       绝不抛异常；</li>
 *   <li>本方法<b>只读</b>状态（{@code Host.selfDb} 自主入口判定，判定主体钉死 SYSTEM）——
 *       出站 stage 里判定主体是当轮调用者，普通群友对 C 类库几乎没有位，所以不能用
 *       {@code h.needDb}；也<b>不写库、不发消息</b>。</li>
 * </ul>
 */
public class AtAskerStage implements OutboundStage {

    // ==================== 兜底默认值（真正生效的值在 prompt.md） ====================

    /** V3 原样的前缀字面量（<b>末尾一个空格</b>）。 */
    private static final String DEF_TPL = "[CQ:at,qq={qq}] ";
    /** V3 原样的"已经 @ 过了"判据。 */
    private static final String DEF_ALREADY = "[CQ:at,";
    /** 引用段模板（OneBot CQ 码；末尾空格由代码补）。 */
    private static final String DEF_REPLY_TPL = "[CQ:reply,id={id}] ";
    /** 「不是一句应付」的正文长度门槛（字符）。 */
    private static final long DEF_MIN_LEN = 40L;
    /** 秒回门槛（秒）：{@code dt < 它} ⇒ 绝不引用。 */
    private static final long DEF_FAST_SEC = 3L;
    /** 慢回门槛（秒）：{@code dt > 它} ⇒ 必须引用。 */
    private static final long DEF_SLOW_SEC = 10L;
    /** 记账有效期（秒）：超期 = 当作没有记录。 */
    private static final long DEF_TTL_SEC = 600L;
    /** 「重要标记」的兜底标签名（{@code MarkerTags} 白名单里本来就有）。 */
    private static final String DEF_MARK = "at";

    // ==================== OutboundStage ====================

    @Override
    public int order() {
        return 20;
    }

    @Override
    public List<String> finish(List<String> parts, Outbound m) {
        try {
            if (parts == null || parts.isEmpty() || m == null) return null;
            Map<String, List<String>> c = AtAskerCfg.of(m.host());
            if (!AtAskerCfg.flag(c, "开关", true)) return null;
            if (!m.group()) return null;                        // 只有群聊加（V3 口径）

            Caller caller = m.caller();
            if (caller == null) return null;                     // 定时任务落点没有"提问者"
            long asker = caller.qq();
            if (asker <= 0L) return null;                        // V3：askerQQ <= 0 不加

            int i = firstNonBlank(parts);
            if (i < 0) return null;                              // 整批都是空的（基板不会这么给，防御）
            String first = parts.get(i);
            String already = AtAskerCfg.one(c, "已at前缀", DEF_ALREADY);
            boolean alreadyAt = !Str.blank(already) && first.startsWith(already);

            // ---------- 唯一的记账读入（stage 只读，绝不写） ----------
            long now = System.currentTimeMillis();
            long gid = caller.groupId() > 0L ? caller.groupId() : m.target();
            long ttl = secs(c, "记录有效期秒", DEF_TTL_SEC, 1L) * 1000L;
            AtAskerMemo.Rec rec = AtAskerMemo.pinned(m.host(), gid, now, ttl);
            boolean known = rec != null;

            // ---------- R1：这条重要吗 ----------
            long minLen = AtAskerCfg.num(c, "重要长度", DEF_MIN_LEN);
            boolean byMark = marked(parts, c);
            int len = visibleChars(parts);
            boolean important = (known && rec.at)                 // ① 触发消息 @ 了她（记账）
                    || caller.master()                            // ② 触发者是主人（stage 内直接可得）
                    || byMark                                     // ③ 替代判据：模型自己标了重要标记
                    || (minLen > 0L && (long) len >= minLen);      // ④ 正文够长

            // ---------- @ 的决定（记账缺失 ⇒ 保持既有口径） ----------
            boolean wantAt = known ? important : true;
            if (alreadyAt) wantAt = false;

            // ---------- R2：引用的决定 ----------
            boolean wantQuote = false;
            long dt = known ? Math.max(0L, now - rec.ts) : -1L;
            if (known && rec.id > 0L && AtAskerCfg.flag(c, "引用开关", true)) {
                long fast = secs(c, "秒回不引用", DEF_FAST_SEC, 0L) * 1000L;
                long slow = secs(c, "慢回必引用", DEF_SLOW_SEC, 0L) * 1000L;
                wantQuote = dt > slow || (dt >= fast && important);
            }

            // ---------- 唯一一条产法：前缀在这里拼，别处不许再拼 ----------
            StringBuilder pre = new StringBuilder();
            if (wantAt) {
                String tpl = AtAskerCfg.one(c, "首条模板", DEF_TPL);
                if (!Str.blank(tpl)) {
                    String s = fill(tpl,
                            "qq", String.valueOf(asker),
                            "group", String.valueOf(m.target()),
                            "session", Str.nz(m.session()),
                            "id", known ? String.valueOf(rec.id) : "");
                    if (!s.endsWith(" ")) s = s + " ";            // 末尾一个空格（V3 原样）
                    pre.append(s);
                }
            }
            if (wantQuote) {
                String tpl = AtAskerCfg.one(c, "引用模板", DEF_REPLY_TPL);
                if (!Str.blank(tpl)) {
                    String s = fill(tpl,
                            "id", String.valueOf(rec.id),
                            "qq", String.valueOf(asker),
                            "group", String.valueOf(m.target()),
                            "session", Str.nz(m.session()));
                    if (!s.endsWith(" ")) s = s + " ";
                    pre.append(s);
                }
            }
            if (pre.length() == 0) return null;

            List<String> out = new ArrayList<String>(parts);
            out.set(i, pre + first);
            return out;
        } catch (Throwable t) {
            return null;                                         // 插件绝不拖垮一条发送
        }
    }

    // ==================== 小工具 ====================

    /** 秒配置 → 秒（小于 {@code floor} 的按 {@code floor} 算；防负数/荒谬值）。 */
    private static long secs(Map<String, List<String>> c, String key, long def, long floor) {
        long v = AtAskerCfg.num(c, key, def);
        return v < floor ? floor : v;
    }

    /** 第一个非空白的下标（"="真正会发出去的首条"）；全空返回 -1。 */
    private static int firstNonBlank(List<String> parts) {
        for (int i = 0; i < parts.size(); i++) {
            String s = parts.get(i);
            if (s != null && !s.trim().isEmpty()) return i;
        }
        return -1;
    }

    /**
     * 模型有没有写「重要标记」（默认 {@code <at>}）。
     *
     * <p>判据与 {@link MarkerTags} 同一条规则：标签名精确匹配、允许带属性（{@code <at/>} /
     * {@code <at qq="1">}），大小写不敏感。剥除发生在 stage <b>之后</b>（先 {@code runStages}、
     * 再逐条 {@code MarkerTags.strip}），所以这里读得到、用户看不到。</p>
     */
    private static boolean marked(List<String> parts, Map<String, List<String>> c) {
        if (parts == null || parts.isEmpty()) return false;
        List<String> names = AtAskerCfg.list(c, "重要标记");
        if (names.isEmpty()) names = Collections.singletonList(DEF_MARK);
        StringBuilder sb = new StringBuilder("</?(?:");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(Pattern.quote(names.get(i)));
        }
        sb.append(")\\b[^>]*>");
        Pattern p;
        try {
            p = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
        } catch (Throwable t) {
            return false;
        }
        for (String s : parts) {
            if (s == null) continue;
            try {
                if (p.matcher(s).find()) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 用户可见的正文长度（字符，Java {@code String.length()}）。
     * <p>先按基板自己的口径 {@link MarkerTags#strip} 把控制标记去掉（那才是用户看到的字数），
     * 再把每条拼起来 —— 分段回复的"正文"是<b>整批</b>，不是只看第一条。</p>
     */
    private static int visibleChars(List<String> parts) {
        int n = 0;
        if (parts == null) return 0;
        for (String s : parts) {
            if (s == null) continue;
            try {
                n += MarkerTags.strip(s).length();
            } catch (Throwable t) {
                n += s.length();
            }
        }
        return n;
    }

    /** {@code {键}} 字面替换（不用正则，模板里带 {@code $} 也不怕）。 */
    private static String fill(String tpl, String... kv) {
        String s = tpl == null ? "" : tpl;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = kv[i];
            String v = kv[i + 1];
            s = s.replace("{" + k + "}", v == null ? "" : v);
        }
        return s;
    }
}
