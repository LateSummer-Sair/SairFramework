import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sair.v4.auth.Caller;
import sair.v4.ext.BuildContext;
import sair.v4.ext.ContextProvider;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.ConfView;
import sair.v4.skill.Host;
import sair.v4.store.Store;

/**
 * 记忆召回：拿本轮用户话当查询，把「相关的记忆」与「跨群群聊片段」注入上下文。
 *
 * <h3>挂在哪</h3>
 * <ul>
 *   <li>{@link ContextProvider}，{@link #stable()} = {@code false}（<b>易变段</b>）——
 *       这一段随本轮用户话变，放进稳定前缀会让前缀缓存每轮都失效
 *       （段位口径见 {@code ContextProvider} 类注释）；</li>
 *   <li>{@link #order()} = {@code 10}；{@link #maxChars()} = {@code prompt.md} 的「预算」（出厂 2000）。</li>
 * </ul>
 *
 * <h3>★ 热召回（批 18 / ID-CROSS+MEM，2026-09-27）：查询串 = 本轮正文 + 最近 K 条本会话对话</h3>
 * <p>甲方原话：「记忆库各种记忆应该是<b>热数据</b>而不是冷数据，在对话中要索引到以前交流过的类似事情中。」
 * 改之前查询<b>只有本轮这一句正文</b>（{@code b.text()}）：像「那我养的那只呢？」这种<b>没有关键词的短追问</b>
 * 拿去查库，命中的是"那 / 只"这类噪声，聊过的类似事一条都捞不上来 —— 那正是"冷"。</p>
 * <p>现在查询串 = <b>本轮正文 + 最近 K 条本会话对话正文</b>（{@link #query}，K = {@code prompt.md} 的
 * 「追问条数」，出厂 {@value #DEF_FOLLOW}；拼完的上限 = 「查询上限」，出厂 {@value #DEF_QCAP} 字符，
 * 超了保留<b>最新</b>的那一段）。三条纪律：</p>
 * <ol>
 *   <li><b>会话隔离</b>：只用 {@code Store.recentDialog(本轮会话键, K)}（SQL 里 {@code WHERE session = ?}，
 *       走 {@code idx_dialog_session}）—— <b>本会话</b>的对话，绝不并别的会话的行。
 *       会话键拿不到（空串）⇒ <b>退回"只有本轮正文"</b>，绝不退化成"全表最近 K 条"
 *       （{@code recentDialog("")} 的口径是整表，所以这一条必须在调用方挡住）；</li>
 *   <li><b>门槛判据不变形</b>：{@link #meaningful} 改用<b>合并后的串</b>判（短追问因此也过得了门槛），
 *       合并后仍不够格 ⇒ <b>仍不查</b>。没有本轮正文（收尾回合 / 系统装配）⇒ 直接不查 —— 与改前逐字节相同；</li>
 *   <li><b>归属过滤一个字不动</b>：{@link #visible} 照旧按"这一轮的目标会话"逐条比
 *       （{@code grouplog} 认群、{@code memory} 认 {@code global}/{@code user:目标人}/{@code group:目标群}）。
 *       查询变长<b>不会</b>让别人的行变成"他的事"—— 能不能注入只由 {@link #visible} 回答。</li>
 * </ol>
 * <p><b>已知代价（如实）</b>：查询变长之后，{@code Store.search} 的第②层（拆词 OR，最多
 * {@code FtsQuery.DEFAULT_MAX_TERMS} = 8 项、超出时<b>均匀采样</b>）会按整串抽词，本轮正文的词
 * 在长查询里只占少数名额。实测口径与后果见报告；本轮正文放在<b>串首</b>（采样含下标 0），
 * 所以它至少保得住一项。</p>
 *
 * <h3>★ 窗口剔重（批 18b / ID-CROSS+MEM-FIX，2026-09-27）：本轮上下文里已经有的行不再注入第二遍</h3>
 * <p><b>治什么（全量门禁唯一一条红的靶子）</b>：热召回让查询串变长以后，FTS 会命中<b>上一轮刚说过</b>
 * 的那条群聊 —— 而那条消息本来就已经由基板注入了：要么在「本会话最近 N 条聊天记录」那个窗口里，
 * 要么（被 {@code ChatWindow} 的"历史已有"去重压掉时）在对话历史里。于是<b>同一条消息在同一个请求体里
 * 出现两次</b>（一次是基板给的那一份，一次是本段"相关记忆"里那一份）。
 * 门禁 {@code ProbeMediaOnly} ⑫ M3「甲的渲染串在乙的请求体里只出现一次」就是钉这件事的
 * （既有判据，只许修机制、不许放松）。</p>
 *
 * <p><b>判据是行身份，不是内容</b>（见 {@link Win#drops}）：被剔掉的条件是
 * "<b>这一行自己的 {@code id}</b> 落在本会话窗口的取数范围里"，不是"这条内容看着像窗口里的某条"。
 * 内容比对会误杀（同一个人两次说一样的话是两条不同的消息），行身份不会。</p>
 *
 * <p><b>窗口是什么</b>（与基板 {@code ctx.ChatWindow} 同一个判据、同一个数）：本会话最近的
 * {@code chatWindowSize} 条 —— 群会话取该群 {@code grouplog}、私聊/控制台取 {@code dialog}；
 * 条数从配置面 {@code Host.conf().getInt("chatWindowSize", …)} 读，缺配置面用基板出厂默认
 * {@value sair.v4.Conf#DEF_CHAT_WINDOW_SIZE}，并按 {@code ChatWindow.MAX_ROWS} 夹住
 * （{@code <=0} = 基板不带窗口 ⇒ 不剔）。</p>
 *
 * <p><b>为什么可以用"窗口的取数范围"而不是"窗口最终渲染出来的那几行"</b>：
 * {@code ChatWindow} 之后还有三道自己的去重（同 {@code msg_id} 重复 / 她自己那两行 / 历史已有），
 * 被它们压掉的行，<b>它的内容仍然在上下文里</b>（在那条重复行上，或在对话历史里）——
 * 所以按取数范围剔重<b>不会丢信息</b>，而按"最终渲染"剔就会漏（M3 那条红的行恰恰是被
 * "历史已有"压掉的那一行）。</p>
 *
 * <p><b>只剔本会话窗口那一支</b>：跨群 {@code grouplog}（别的群的群聊）与 {@code memory} 库
 * <b>一个字都不动</b> —— 那是本段的正当产出。跨表主键不可比，所以 {@link Win} 自带"哪张表"的标签，
 * <b>结构上不可能</b>拿 {@code dialog} 的 id 去剔 {@code grouplog} 的行（反之亦然）。
 * 私聊/控制台的窗口是 {@code dialog}，而本段在那些会话里<b>一条 {@code grouplog} 都不产出</b>
 * （{@link #visible} 认目标群），所以那一支恒为空交集。</p>
 *
 * <p><b>退化（逐条）</b>：配置面拿不到 ⇒ 按出厂默认 30 算（<b>宁可少剔、绝不误删</b>）；
 * {@code chatWindowSize <= 0} ⇒ 不剔（基板那一轮本来就没有窗口）；窗口读不出来
 * （{@code Store} 抛异常）⇒ 不剔（少一层，不报错）；行 {@code id <= 0} ⇒ 不剔。
 * 剔重发生在取回之后，<b>不回填</b>：那一段可能因此少于 {@code 跨群条数} 行 ——
 * 少掉的那些本来就已在上下文里，不构成信息损失。</p>
 *
 * <h3>数据从哪来（只读）</h3>
 * <p>只用 {@link Host} 能力面：{@code h.store().search(...)} 与 {@code h.store().recentDialog(...)}
 * —— 两支都是读。本插件<b>不建库、不写库、无副作用</b>（{@code BuildContext.subagent()} 为真时也一样 ——
 * 因为压根没有写路径）。查询 {@code memory}（本会话口径 12 条）与 {@code grouplog}（跨群群聊 8 条）。</p>
 *
 * <h3>归属硬判（技能管控表只管技能 —— 这里判的是数据归属）</h3>
 * <p>检索结果<b>不能整锅端进这一轮的上下文</b>：{@code Store.search} 没有作用域过滤，
 * 库里既有别人的 {@code user} 记忆、也有别群的 {@code group} 记忆与别群的群聊记录。
 * 所以这里在渲染前逐条按<b>这一轮要说话的那个会话</b>做显式归属比较（见 {@link #visible}）。</p>
 *
 * <p><b>为什么按"会话"而不是按"主体"</b>：触发者可能是 {@code SYSTEM}（钩子 / 自主路径，
 * 自己既没有 QQ 也没有群号），但这一轮照样有目标会话（群号藏在会话键 / {@code Turn} 的
 * {@code snapshot} 事实里）。要守的边界是"<b>不能把别的群、别人的东西注入给这个会话</b>"，
 * 不是"谁都别想看群聊" —— 后者会把"本群今天聊过什么"这种正当上下文一起砍掉。
 * 目标会话的解出见 {@link #aim}（优先级：触发者的群号 → {@link BuildContext#session()} →
 * {@code snapshot} 事实里的 {@code session}）。</p>
 * <ul>
 *   <li>{@code MASTER}：全部条目（照常，与"主人恒全权"一致）；</li>
 *   <li>其它（{@code ALLUSER} / {@code SYSTEM} / 没有触发者）：{@code global}（全体，人人可见）、
 *       {@code scope=user && scope_id==目标那个人}、{@code scope=group && scope_id==目标群}；
 *       {@code grouplog} 只留 {@code group_id == 目标群}（<b>任何情况下都不跨群</b>）。</li>
 * </ul>
 * <p>"目标那个人"只在有真人触发者时才有（{@code ALLUSER} 的 QQ）：{@code SYSTEM} / 没有触发者时
 * <b>任何人的 {@code user} 行都不注入</b>（她自己的自主行为不该替某个人把私事摆上桌）。
 * 目标群拿不到时（例如自主回合既没有会话键也没有快照事实）群聊段与 {@code group} 记忆一律不注入 ——
 * 那时只剩 {@code global}，绝不退化成"跨群都可以"。</p>
 *
 * <h3>★ 她自己的标记行（P1-selfsee）：跨群群聊那一段整行跳过</h3>
 * <p>基板 P0-1 之后 {@code grouplog} 里会多出她<b>自己发出去</b>的那一行「标记行」
 * （{@code content} 形如 {@code [我发的 图片 msg_id=<id>]} / {@code [我发的 msg_id=<id>] <正文>}，
 * {@code extra.self=true}）。它是<b>机器写的标记行 —— 不是群友发言</b>，所以本段这样处置
 * （判据只有 {@link #isSelfMark} 一处）：</p>
 * <ol>
 *   <li>{@code grouplog}（跨群群聊）命中行：<b>① 整行跳过</b>。理由三条：① 这一段是<b>预算稀缺</b>的注入面
 *       （{@code 跨群条数} 8 行 / {@code 预算} 2000 字），机器外壳会挤掉真群友的行；
 *       ② 行模板 {@code - [群{group}] {nickname}: {content}} 里<b>没有</b>"是她发的"这个面，
 *       而标记行没有可信昵称（{@code nickname} 空 ⇒ 退成 {@code 某人}）⇒ 会被当成<b>某个群友的发言</b>；
 *       ③ 她自己的行已经由基板窗口给她看（P0-1 的本意），{@code grouplog} 里那一行也没被删 ——
 *       <b>事实面一行没少</b>，少掉的只是"把机器外壳当别人的话注入"这一层。</li>
 *   <li>{@code memory} 命中行：<b>③ 照旧（不过谓词）</b> —— {@code memory} 不是聊天记录；
 *       万一里面有一条 {@code content} 以 {@code [我发的 } 开头的<b>记忆本体</b>，那是她自己的素材，
 *       跳过就等于抹掉事实。</li>
 * </ol>
 *
 * <h3>数字出处（V3 原样，见 notes/feel-plugins.md 第二节；★ 项是本批抬过的档）</h3>
 * <ul>
 *   <li>★ 12 条 / 8 条（抬档前 8 / 5）：{@code QQPromptBuilder.java:695} / {@code :706}；</li>
 *   <li>查询门槛：{@code hasMeaningfulQuery} = {@code text.replaceAll("[\\s\\p{Punct}]+","").length() >= 4}
 *       （{@code QQPromptBuilder.java:719-722}）—— 短语气词不触发检索；
 *       {@code prompt.md} 现写 2（比 V3 松，本车道不改这个数）；</li>
 *   <li>单条 300 字截断：{@code QQPromptBuilder.java:700}；跨群单条 160 字：{@code UnifiedQQMemoryManager.java:798}；</li>
 *   <li>跨群行是 {@code "[群" + groupId + "] " + 昵称 + ": " + 内容}（{@code UnifiedQQMemoryManager.java:799}）；</li>
 *   <li>标题：{@code labels/qq.md} 的 {@code title.related-memories}=相关记忆、
 *       {@code title.cross-group-related}=跨群相关群聊记录（中文标题，见 md 第六节 1 的默认做法）；</li>
 *   <li>★ 热召回（追问条数 / 查询上限）：V3 <b>没有</b>对应物（V3 也只用本轮正文当查询，见
 *       {@code QQPromptBuilder.java:695} 附近）—— 这两项是本批新加的，出厂值
 *       {@value #DEF_FOLLOW} 条 / {@value #DEF_QCAP} 字符，与 {@code prompt.md} 一一对应。</li>
 * </ul>
 *
 * <h3>文案与阈值</h3>
 * <p>全在本插件目录的 {@code prompt.md}（{@code 键: 值}）；下面的常量只是<b>文件读不到时的兜底</b>。
 * {@code host()} 可能为 null（装配期绑不上）——那就用兜底值，绝不抛异常。</p>
 */
public class MemoryRecallProvider implements ContextProvider {

    // ==================== 兜底默认值（真正生效的值在 prompt.md） ====================

    /** 记忆库（基板六库之一的业务表名，由「记忆」插件声明；不是文案）。 */
    private static final String LIB_MEM = "memory";
    /** 群聊历史（基板环境库；跨群检索用它）。 */
    private static final String LIB_LOG = "grouplog";
    private static final String DEF_TITLE = "## 相关记忆";
    private static final String DEF_LEAD = "以前记住的、可能和这次有关的信息：";
    private static final String DEF_LINE = "- [{ts}] {content}";
    private static final String DEF_XTITLE = "## 跨群相关群聊记录";
    private static final String DEF_XLINE = "- [群{group}] {nickname}: {content}";
    /** V3 原样：{@code [\s\p{Punct}]+}（只去空白与 ASCII 标点）。 */
    private static final String DEF_FILTER = "[\\s\\p{Punct}]+";
    private static final String DEF_TIME = "yyyy-MM-dd HH:mm";
    private static final String DEF_TAIL = "...";
    private static final String DEF_ANON = "某人";
    private static final int DEF_N = 12;
    private static final int DEF_XN = 8;
    private static final int DEF_MINQ = 4;
    private static final int DEF_BUDGET = 2000;
    private static final int DEF_CUT = 300;
    private static final int DEF_XCUT = 160;
    /** ★ 热召回：并进查询的"最近 N 条本会话对话"（{@code prompt.md} 的「追问条数」）。 */
    private static final int DEF_FOLLOW = 8;
    /** ★ 热召回：合并后查询串的字符上限（{@code prompt.md} 的「查询上限」）；超了保留最新的一段。 */
    private static final int DEF_QCAP = 600;

    // ==================== ★ 她自己的标记行（P1-selfsee 唯一一处判据） ====================

    /** 标记行在 {@code content} 上的<b>逐字</b>前缀（尾部的空格也算，判据不许过宽）。 */
    private static final String SELF_MARK_PREFIX = "[我发的 ";

    /**
     * ★ P1「她自己的标记行」判据 —— <b>本技能唯一一处</b>（不许在业务逻辑里散写
     * {@code if (content.startsWith(...))}）。
     *
     * <p>基板 P0-1 落地后，她<b>自己发出去</b>的消息/图会在 {@code grouplog}（群）/
     * {@code dialog}（私聊）里补一行标记行：{@code content} =
     * {@code [我发的 图片 msg_id=<id>]} 或 {@code [我发的 msg_id=<id>] <正文>}，并带
     * {@code extra.self=true} + {@code extra.action} + {@code extra.sent_id}。
     * <b>它是机器写的标记行 —— 不是群友发言，也不是她要回忆的"群友说过的话"。</b></p>
     *
     * <p>两条判据<b>都要</b>（只读消费面可能把 {@code extra} 丢掉）：</p>
     * <ol>
     *   <li>{@code extra.self == true}：{@code extra} 是 TEXT 列的 JSON 串（{@code Db.decode}
     *       让出参是对象；解析失败/缺失就当<b>没有它</b>）；</li>
     *   <li>兜底：{@code content} <b>逐字</b>以 {@link #SELF_MARK_PREFIX} 开头（含尾部空格）。</li>
     * </ol>
     *
     * <p><b>判据不许走样</b>：① 只能用 {@code startsWith} —— 不许 {@code contains("[我发的")}，
     * 也不许退化成「以 {@code [} 开头」：{@code [图片 …]}/{@code [引用 msg_id=…]} 那些是
     * <b>基板事实标签</b>，本判据一律 {@code false}（本任务不许过滤它们）；
     * ② {@code "[我发的]"}（没有尾部空格）也判 {@code false}（逐字前缀）。</p>
     */
    static boolean isSelfMark(JsonObject row) {
        if (row == null) return false;
        try {
            JsonObject ex = rowExtra(row);
            if (ex != null && J.b(ex, "self", false)) return true;
        } catch (Throwable ignored) {
        }
        try {
            return Str.nz(J.s(row, "content", "")).startsWith(SELF_MARK_PREFIX);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** {@code extra} 列（TEXT 的 JSON 串，或已被 {@code Db.decode} 解析成对象）；解析不出来返回 null。 */
    private static JsonObject rowExtra(JsonObject row) {
        try {
            com.google.gson.JsonElement e = J.get(row, "extra");
            if (e == null || e.isJsonNull()) return null;
            if (e.isJsonObject()) return e.getAsJsonObject();
            return e.isJsonPrimitive() ? J.obj(e.getAsString()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@link #maxChars()} 的签名里没有 Host（基板调它时不给上下文），
     * 所以只能把「最近一次从 prompt.md 读到的预算」记下来 —— 第一次调用之前是兜底 2000。
     */
    private volatile int budget = DEF_BUDGET;

    // ==================== ContextProvider ====================

    @Override
    public boolean stable() {
        return false;              // 易变段：内容随本轮用户话变
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public int maxChars() {
        int b = budget;
        return b > 0 ? b : DEF_BUDGET;
    }

    @Override
    public String build(BuildContext b) {
        try {
            if (b == null) return "";
            Host h = b.host();
            Map<String, List<String>> c = Cfg.of(h);
            budget = num(c, "预算", DEF_BUDGET);
            if (!flag(c, "开关", true)) return "";
            Store s = store(h);
            if (s == null) return "";                       // 拿不到库 → 这一段不出现（不是异常）

            // 本 provider 只读、不写，也没有工具：它跑在扩展点的判定主体下（没有触发者时 = SYSTEM），
            // 技能管控表只管控技能（工具 / op），所以这里<b>没有</b>任何 op 判定 ——
            // 能不能注入由"目标会话"与下面的逐条归属过滤回答（见 visible）。
            String q = query(b, s, c);
            String filter = one(c, "查询过滤正则", DEF_FILTER);
            int min = num(c, "最小查询字数", DEF_MINQ);
            if (!meaningful(q, filter, min)) return "";     // 合并后仍不够格 ⇒ 仍不查（与改前同一判据）

            // 过滤依据 = "这一轮要说话的那个会话"（不是触发者）：SYSTEM / 没有触发者时
            // 目标群号从会话键或 snapshot 事实里解出来（见 aim）。
            Aim aim = aim(b);
            // ★ 窗口剔重（ID-CROSS+MEM-FIX）：本轮上下文里**已经有的那几行**的行身份集合。
            // 只在这一轮真的要查库时才取（门槛已经过了），且只作用于 grouplog 那一支。
            int winSize = windowSize(h);
            Win win = winSize <= 0 ? null : window(s, aim.group, b.session(), winSize);
            SimpleDateFormat fmt = timeFmt(one(c, "时间格式", DEF_TIME));
            String tail = one(c, "截断尾巴", DEF_TAIL);
            String anon = one(c, "匿名昵称", DEF_ANON);
            List<String> blocks = new ArrayList<String>();

            List<String> mem = render(s, LIB_MEM, q, num(c, "条数", DEF_N),
                    one(c, "行模板", DEF_LINE), num(c, "单条上限", DEF_CUT), tail, fmt, anon, false, aim, null);
            if (!mem.isEmpty()) blocks.add(pack(one(c, "标题", DEF_TITLE), one(c, "说明", DEF_LEAD), mem));

            if (flag(c, "跨群开关", true)) {
                List<String> cross = render(s, LIB_LOG, q, num(c, "跨群条数", DEF_XN),
                        one(c, "跨群行模板", DEF_XLINE), num(c, "跨群单条上限", DEF_XCUT), tail, fmt, anon, true,
                        aim, win);
                if (!cross.isEmpty()) blocks.add(pack(one(c, "跨群标题", DEF_XTITLE), one(c, "跨群说明", ""), cross));
            }

            if (blocks.isEmpty()) return "";
            return Str.join(blocks, "\n");
        } catch (Throwable t) {
            return "";                                      // 插件绝不拖垮一轮对话
        }
    }

    // ==================== ★ 热召回：本轮检索用的查询串（批 18 / ID-CROSS+MEM） ====================

    /**
     * ★ <b>本轮检索用的查询串</b>（批 18 / ID-CROSS+MEM，2026-09-27）= <b>本轮正文 + 最近 K 条本会话对话</b>。
     *
     * <p><b>治什么</b>：改动之前查询只有本轮这一句正文 —— 「那我养的那只呢？」这类<b>没有关键词的短追问</b>
     * 拿去查库，既过不了"有意义的词"，也捞不回"以前聊过的类似事"。把刚说过的那几句一起带上，
     * 同一句话就能索引到之前那轮问答（"我养的那只叫什么 / 团子"）。</p>
     *
     * <p><b>退化链（逐条都要能断言）</b>：</p>
     * <ol>
     *   <li>{@code b == null} / 本轮正文为空 ⇒ 返回空串（调用方的 {@link #meaningful} 判假 ⇒
     *       <b>不查库</b>）—— 收尾回合 {@code assembleSystem} 传的正文就是空串，与改前逐字节相同；</li>
     *   <li>「追问条数」{@code <= 0} 或「查询上限」{@code <= 0}（配置写坏 / 主人关掉）⇒ 只有本轮正文；</li>
     *   <li>会话键为空 ⇒ 只有本轮正文（{@code recentDialog("")} 是<b>整表</b>口径，绝不能走到那里）；</li>
     *   <li>本轮正文本身就 ≥ 查询上限 ⇒ 只有本轮正文（一个字都不加，也不截断正文）；</li>
     *   <li>取不到对话 / 没有可用的历史行 ⇒ 只有本轮正文；</li>
     *   <li>拼完超过上限 ⇒ 保留<b>最新</b>的那一段（{@link #tail}，不劈开代理对）。</li>
     * </ol>
     *
     * <p><b>顺序</b>：本轮正文在<b>串首</b>，历史按"老 → 新"跟在后面。串首这一位不是装饰：
     * {@code Store.search} 的拆词 OR 层最多 8 项、超出时<b>均匀采样</b>，采样点含下标 0
     * ⇒ 本轮正文的词<b>至少保得住一项</b>（这是在不改 {@code FtsQuery} 的前提下能做的最稳的摆法）。</p>
     */
    private static String query(BuildContext b, Store s, Map<String, List<String>> c) {
        String base = b == null ? "" : Str.trim(Str.nz(b.text()));
        if (!Str.has(base)) return "";                          // 没有本轮正文 ⇒ 不查（原语义）
        int k = num(c, "追问条数", DEF_FOLLOW);
        int cap = num(c, "查询上限", DEF_QCAP);
        if (k <= 0 || cap <= 0) return base;                    // 关掉 / 配置写坏 ⇒ 只有本轮正文
        String sess = b == null ? "" : Str.trim(b.session());
        if (Str.blank(sess)) return base;                       // ★ 会话隔离：拿不到会话键就不并历史
        if (base.length() >= cap) return base;                  // 正文本身超上限 ⇒ 只查正文
        String his = history(s, sess, k, base);
        if (Str.blank(his)) return base;
        int room = cap - base.length() - 1;                     // 1 = 中间那个空格
        if (his.length() > room) his = tail(his, room);         // 超上限：保留最新的一段
        return base + " " + his;
    }

    /**
     * 最近 {@code k} 条<b>本会话</b>对话正文，按"老 → 新"拼成一行；取不到返回空串。
     *
     * <p>{@code Store.recentDialog(session, k)} 的 SQL 是 {@code WHERE session = ? ORDER BY ts DESC, id DESC
     * LIMIT k}（走 {@code idx_dialog_session}）—— <b>会话隔离由这一句 SQL 保证</b>，
     * 本方法不自己拼任何跨会话的东西。</p>
     *
     * <p><b>剔掉本轮正文那一行</b>：{@code Agent.ask} 在装配<b>之前</b>就把本轮输入写进 {@code dialog} 了
     * （见它那一行的注释），所以最新一条往往就是本轮正文本人。逐字相等就跳过 ——
     * 同一句话在查询里出现两遍只会白白挤掉别的词。</p>
     */
    private static String history(Store s, String session, int k, String base) {
        List<JsonObject> rows;
        try {
            rows = s.recentDialog(session, k);
        } catch (Throwable t) {
            return "";                                          // 取不到 ⇒ 退回"只有本轮正文"
        }
        if (rows == null || rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonObject r : rows) {
            if (r == null) continue;
            String txt = Str.oneLine(Str.nz(J.s(r, "content", "")));
            if (Str.blank(txt)) continue;
            if (txt.equals(base)) continue;                     // 本轮正文那一行：不重复计入
            if (sb.length() > 0) sb.append(' ');
            sb.append(txt);
        }
        return sb.toString();
    }

    /** 取字符串<b>尾部</b> {@code n} 个字符（不劈开代理对）；{@code n <= 0} → 空串。 */
    private static String tail(String s, int n) {
        if (s == null) return "";
        if (n <= 0) return "";
        if (s.length() <= n) return s;
        int from = s.length() - n;
        if (from > 0 && Character.isLowSurrogate(s.charAt(from))) from--;   // 别把代理对劈成两半
        return s.substring(from);
    }

    // ==================== ★ 窗口剔重：本轮上下文里已经有的行不再注入第二遍（批 18b） ====================

    /**
     * 「本轮上下文里<b>已经有</b>的那几行」的行身份集合 —— 本段据此不重复注入（见类注释「窗口剔重」）。
     *
     * <p><b>为什么要带一个"哪张表"的标签</b>：{@code grouplog} 与 {@code dialog} 的主键各自成序列，
     * <b>跨表比 id 是错的</b>（{@code dialog} 的 42 与 {@code grouplog} 的 42 是两条毫不相干的行）。
     * 所以这里记住这份 id 是从哪张表取的，{@link #drops} 只在<b>同表</b>时才可能命中 ——
     * 结构上不可能拿私聊窗口的 id 去剔一条群聊记录。</p>
     */
    private static final class Win {

        /** {@code true} = 这份 id 来自群窗口（{@code grouplog}）；{@code false} = 私聊/控制台窗口（{@code dialog}）。 */
        final boolean group;
        final Set<Long> ids;

        Win(boolean group, Set<Long> ids) {
            this.group = group;
            this.ids = ids;
        }

        /**
         * 这一条（{@code lib} 这个库里的 {@code id}）该不该因为"窗口里已经有"被剔掉。
         *
         * <p>三个条件：① {@code id > 0}（{@code <=0} = 这一行没有稳定身份，绝不拿它去比）；
         * ② 这份集合与这个库<b>同表</b>（跨表主键不可比）；③ id 真的在集合里。</p>
         */
        boolean drops(String lib, long id) {
            if (id <= 0L) return false;
            if (group != LIB_LOG.equals(lib)) return false;
            return ids.contains(Long.valueOf(id));
        }
    }

    /**
     * <b>本会话窗口的大小</b>：配置 {@code chatWindowSize}，与基板 {@code ctx.ChatWindow.size(conf)}
     * 同一个数、同一个夹取（{@code <=0} = 不带窗口 ⇒ 0）。
     *
     * <p><b>为什么问基板配置而不在 {@code prompt.md} 里再立一个键</b>：窗口是基板注进去的，
     * 大小只有那一个真源。本插件再存一份就会漂（主人把 {@code chatWindowSize} 改成 200、
     * 本插件还按 30 剔 ⇒ 后 170 行会重新出现"同一行两遍"）。
     * 所以这里只<b>读</b>那一个键，缺配置面时用基板出厂默认
     * （{@value sair.v4.Conf#DEF_CHAT_WINDOW_SIZE}）—— 方向是"<b>宁可少剔、绝不误删</b>"：
     * 少剔只可能漏掉一条去重，误删会把用户没见过的内容一起砍掉。</p>
     *
     * @return 窗口条数；{@code <=0} = 这一轮没有窗口（不剔）
     */
    private static int windowSize(Host h) {
        int n = sair.v4.Conf.DEF_CHAT_WINDOW_SIZE;
        try {
            ConfView cf = h == null ? null : h.conf();
            if (cf != null) n = cf.getInt("chatWindowSize", n);
        } catch (Throwable ignored) {
            // 配置面拿不到 ⇒ 按出厂默认算（见上面"宁可少剔"）
        }
        if (n <= 0) return 0;
        // 夹取与基板同源（ChatWindow.MAX_ROWS）：写大了两边一起夹住，不会一边 200 一边 100000
        return n > sair.v4.ctx.ChatWindow.MAX_ROWS ? sair.v4.ctx.ChatWindow.MAX_ROWS : n;
    }

    /**
     * <b>本会话窗口里那几行的 id</b>（行身份）。与基板 {@code ctx.ChatWindow.facts} 同一判据、同一批行：
     * 群会话取该群 {@code grouplog}、私聊/控制台取 {@code dialog}，都是"最近 {@code n} 条"。
     *
     * <p>两条查询复用 {@link Store} 上<b>现成</b>的两支（{@code recentGrouplog} / {@code recentDialog}）：
     * 它们的内层 SQL 与 {@code ChatWindow.tailGrouplog} / {@code tailDialog} <b>逐字同口径</b>
     * （{@code ORDER BY ts DESC, id DESC LIMIT n}，走各自那个索引），所以拿到的就是窗口取数范围那一批行
     * —— 本插件<b>不新写 SQL</b>、不建表、不写库。</p>
     *
     * <p><b>为什么是"取数范围"而不是"窗口最终渲染出来的行"</b>：{@code ChatWindow} 之后还有三道
     * 自己的去重，被压掉的行其内容仍在上下文里（重复行/对话历史），所以按取数范围剔不会丢信息，
     * 而按最终渲染剔会漏掉门禁 M3 那条红的行（它是被"历史已有"压掉的那一行）。见类注释。</p>
     *
     * @param gid     目标群号（{@code >0} = 群会话）；{@code <=0} = 私聊/控制台
     * @param session 会话键（私聊/控制台那一支要用）
     * @param n       窗口条数（{@link #windowSize}；{@code <=0} = 不剔）
     * @return 这份集合；<b>拿不到窗口 / 读不出来 / 一行都没有 ⇒ {@code null}</b>（= 不剔，安静降级）
     */
    private static Win window(Store s, long gid, String session, int n) {
        if (s == null || n <= 0) return null;
        List<JsonObject> rows;
        try {
            if (gid > 0L) {
                rows = s.recentGrouplog(gid, n);                 // 群：窗口取的就是该群 grouplog
            } else if (!Str.blank(session)) {
                rows = s.recentDialog(session, n);               // 私聊/控制台：窗口取 dialog
            } else {
                return null;                                     // 连会话键都拿不到 ⇒ 不知道窗口是什么，不剔
            }
        } catch (Throwable t) {
            return null;                                         // 读不出来 ⇒ 少一层去重（不报错、不抛）
        }
        if (rows == null || rows.isEmpty()) return null;
        Set<Long> ids = new HashSet<Long>();
        for (JsonObject r : rows) {
            if (r == null) continue;
            long id = J.l(r, "id", 0L);
            if (id > 0L) ids.add(Long.valueOf(id));
        }
        return ids.isEmpty() ? null : new Win(gid > 0L, ids);
    }

    // ==================== 渲染 ====================

    private static String pack(String title, String lead, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.trim().isEmpty()) sb.append(title.trim()).append("\n");
        if (lead != null && !lead.trim().isEmpty()) sb.append(lead.trim()).append("\n");
        for (String l : lines) sb.append(l).append("\n");
        return sb.toString();
    }

    /**
     * 查一个库并把命中行渲染成文本行（只读：{@code Store.search}）。
     *
     * <p><b>逐条过归属硬判</b>（{@link #visible}）：{@code Store.search} 没有作用域过滤，
     * 返回的是整库命中 —— 别人的 {@code user} 记忆、别群的 {@code group} 记忆、别群的群聊记录
     * 都在里面。这里是"跨群 / 别人的条目"被剔除的唯一位置。</p>
     *
     * @param dedupe 跨群那一段按 V3 口径去重（V3 用 LinkedHashSet 收跨群命中行）
     * @param aim    本轮的目标会话（{@link #aim}）：能不能注入由它裁
     * @param win    本轮"窗口里已经有"的行身份集合（{@link Win}）；{@code null} = 不做这一层。
     *               只对 {@code grouplog} 生效（跨表主键不可比，见 {@link Win#drops}）
     */
    private static List<String> render(Store s, String lib, String q, int n, String tpl,
                                       int cut, String tail, SimpleDateFormat fmt,
                                       String anon, boolean dedupe, Aim aim, Win win) {
        List<String> out = new ArrayList<String>();
        List<JsonObject> rows;
        try {
            rows = s.search(lib, q, n);
        } catch (Throwable t) {
            return out;
        }
        if (rows == null) return out;
        LinkedHashSet<String> seen = dedupe ? new LinkedHashSet<String>() : null;
        for (JsonObject r : rows) {
            if (r == null) continue;
            if (!visible(aim, lib, r)) continue;             // 归属硬判：别人的 / 别群的条目在这里出局
            // ★ 窗口剔重（ID-CROSS+MEM-FIX）：这一行**本轮上下文里已经有**（在窗口里，或在被
            //   "历史已有"压掉后仍在对话历史里）⇒ 不再注入第二遍。判据是行身份（id），不是内容。
            if (win != null && win.drops(lib, J.l(r, "id", 0L))) continue;
            // ★ P1-selfsee：她自己的标记行不进"跨群群聊"那一段（机器外壳不是群友发言）。
            // 只对 grouplog 生效：memory 是记忆本体，不适用（见类注释）。
            if (LIB_LOG.equals(lib) && isSelfMark(r)) continue;
            String content = J.s(r, "content", "");
            if (Str.blank(content)) continue;
            String nick = J.s(r, "nickname", "");
            if (Str.blank(nick)) nick = anon;
            String line = fill(tpl,
                    "ts", stamp(fmt, J.l(r, "ts", 0L)),
                    "content", clamp(content.trim(), cut, tail),
                    "id", String.valueOf(J.l(r, "id", 0L)),
                    "importance", String.valueOf(J.l(r, "importance", 0)),
                    "scope", J.s(r, "scope", ""),
                    "scope_id", J.s(r, "scope_id", ""),
                    "source", J.s(r, "source", ""),
                    "tags", J.s(r, "tags", ""),
                    "group", String.valueOf(J.l(r, "group_id", 0L)),
                    "qq", String.valueOf(J.l(r, "user_id", 0L)),
                    "nickname", nick,
                    "name", nick);
            if (Str.blank(line)) continue;
            if (seen != null && !seen.add(line)) continue;
            out.add(line);
        }
        return out;
    }

    private static Store store(Host h) {
        if (h == null) return null;
        try {
            return h.store();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 本轮"要对谁说话"（过滤依据 = <b>目标会话</b>，不是触发者）。
     *
     * <p><b>为什么不能只看触发者</b>：钩子 / 自主路径的触发者是 {@code SYSTEM}
     * （{@code Caller.systemActor()}，QQ 与群号都是 0），可这一轮照样有目标会话 ——
     * 群号藏在会话键或者 {@code Turn} 的 {@code snapshot} 事实里。只看触发者会把
     * "本群今天聊过什么"这种正当上下文一起砍掉。</p>
     */
    private static final class Aim {
        /** 触发者（可能为 null = 没有触发者）。 */
        final Caller me;
        /** 目标群号（0 = 不是群会话，或拿不到 —— 拿不到时群聊段与 group 记忆一律不注入）。 */
        final long group;
        /** 目标那个人（0 = 不是某个真人 ⇒ 谁的 user 记忆都不注入）。 */
        final long qq;

        Aim(Caller me, long group, long qq) {
            this.me = me;
            this.group = group;
            this.qq = qq;
        }

        /** 主人恒全权（照常）：与 {@code MemorySkill.visible} 的主人分支一致。 */
        boolean master() { return me != null && me.kind() == Caller.Kind.MASTER; }
    }

    /**
     * 解出目标会话。优先级：<br>
     * ① 触发者的群号（真人在群里说话，最权威）；<br>
     * ② {@link BuildContext#session()}（形如 {@code group:888} / {@code qq:30003} / {@code console}）；<br>
     * ③ {@code Turn} 事实里的 {@code snapshot: {"session":"group:888",…}}。</p>
     *
     * <p><b>为什么需要 ③</b>：{@code Turn} 的会话字段在 caller 为 null 或 SYSTEM（控制台入口）时是
     * {@code console}，不带群号（见 {@code Turn#Turn(Caller,Sink)} 与 {@code Caller#session()}），
     * 而快照事实里冻结了真正的会话键 —— 这是那两条路上唯一能拿到目标群号的地方。</p>
     *
     * <p><b>拿不到时会怎样</b>（例如将来的自主回合既没有会话键、也没有快照事实）：目标群 = 0，
     * 于是群聊段与 {@code group} 记忆一律不注入，只剩 {@code global} ——
     * <b>绝不退化成"跨群都可以"</b>。</p>
     *
     * <p><b>钩子 payload 里的群号取不到</b>：{@code ExtRegistry.blocks(caller, turn, text)} 不放 payload，
     * 而钩子派发本身也不装配上下文（{@code blocks()} 只由 {@code CtxBuild.assemble} 调用）——
     * 所以钩子那条路要拿群号只能靠它的 caller / turn。</p>
     */
    private static Aim aim(BuildContext b) {
        if (b == null) return new Aim(null, 0L, 0L);
        Caller me = b.caller();
        long group = me == null ? 0L : me.groupId();
        // 她自己的自主行为不是"某个人"：不给它注入任何人的 user 记忆
        long qq = (me == null || me.kind() == Caller.Kind.SYSTEM) ? 0L : me.qq();
        if (group <= 0L) group = groupOf(b.session());
        if (group <= 0L) group = groupOf(sessionInSnapshot(b));
        return new Aim(me, group, qq);
    }

    /** {@code group:888} → 888（不是群会话 / 认不出 → 0）。 */
    private static long groupOf(String session) {
        String s = Str.trim(session);
        if (s == null || !s.startsWith("group:")) return 0L;
        try {
            return Long.parseLong(Str.trim(s.substring(6)));
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** {@code Turn} 事实里的会话键（{@code snapshot: {"session":"group:888",…}}）；没有/认不出 → 空串。 */
    private static String sessionInSnapshot(BuildContext b) {
        try {
            String facts = b.state("snapshot", "");
            if (Str.blank(facts)) return "";
            String line = facts;
            int nl = line.indexOf('\n');
            if (nl >= 0) line = line.substring(0, nl);
            int i = line.indexOf('{');
            if (i < 0) return "";
            JsonObject o = J.obj(line.substring(i));
            return o == null ? "" : Str.nz(J.s(o, "session", ""));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * <b>归属硬判</b>：这一条能不能进"这一轮这个会话"。
     *
     * <p>判的是<b>代码里的显式归属比较</b>，不是技能管控表 —— 技能管控表只管控技能（工具 / op），
     * 而"别人的 {@code user} 记忆、别群的群聊记录该不该注入"是数据隔离问题，
     * 只能在这里逐条比（{@code Store.search} 没有 filter，命中结果是整库的）。</p>
     *
     * <ul>
     *   <li>{@code MASTER}（触发者是主人）→ 全部条目，与"主人恒全权"一致；</li>
     *   <li>其它（含 {@code ALLUSER} / {@code SYSTEM} / 没有触发者）→
     *       {@code grouplog} 只留 {@code group_id == 目标群}；
     *       {@code memory} 只留 {@code global}（全体共享，与 {@code MemorySkill.visible} 同口径）、
     *       {@code user: 目标那个人}（没有真人触发者时一条都不放）、{@code group: 目标群}；
     *       认不出的作用域一律不注入。</li>
     * </ul>
     *
     * <p>作用域里的 {@code user}/{@code group}/{@code global} 是结构性取值（与 {@code MemorySkill}
     * 的常量同字面量），这两个插件之间没有共享代码，所以这里按字面量比。</p>
     */
    private static boolean visible(Aim aim, String lib, JsonObject r) {
        if (aim == null) return false;
        if (aim.master()) return true;
        if (LIB_LOG.equals(lib)) {
            long g = J.l(r, "group_id", 0L);
            return aim.group > 0L && g == aim.group;          // 任何情况下都不跨群
        }
        String scope = J.s(r, "scope", "");
        String sid = Str.nz(J.s(r, "scope_id", ""));
        if ("global".equals(scope)) return true;
        if ("user".equals(scope)) return aim.qq > 0L && String.valueOf(aim.qq).equals(sid);
        if ("group".equals(scope)) return aim.group > 0L && String.valueOf(aim.group).equals(sid);
        return false;
    }

    /** V3 的 {@code hasMeaningfulQuery}：去空白与标点后长度 ≥ 门槛。 */
    private static boolean meaningful(String text, String filter, int min) {
        if (Str.blank(text)) return false;
        int m = min < 0 ? 0 : min;
        String t = text;
        try {
            t = text.replaceAll(filter, "");
        } catch (Throwable bad) {
            try {
                t = text.replaceAll(DEF_FILTER, "");         // 正则写坏了退回 V3 口径
            } catch (Throwable ignored) {
                t = text;
            }
        }
        return t.length() >= m;
    }

    private static String clamp(String s, int cut, String tail) {
        if (s == null) return "";
        if (cut <= 0 || s.length() <= cut) return s;
        return s.substring(0, cut) + (tail == null ? "" : tail);
    }

    private static SimpleDateFormat timeFmt(String pattern) {
        try {
            return new SimpleDateFormat(Str.blank(pattern) ? DEF_TIME : pattern);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stamp(SimpleDateFormat fmt, long ts) {
        if (fmt == null || ts <= 0L) return "";
        try {
            return fmt.format(new Date(ts));
        } catch (Throwable t) {
            return "";
        }
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

    // ==================== prompt.md（惰性缓存 + 按 mtime 失效） ====================

    /**
     * 本插件目录下 {@code prompt.md} 的键值表。
     * <p>读不到文件 / {@code host()} 为 null → 返回空表（调用方逐项落到兜底默认值）。
     * 缓存按「文件 + mtime」失效：每轮不读盘，改了文件下一轮生效。</p>
     */
    private static final class Cfg {

        private static File file;
        private static long stamp = -1L;
        private static Map<String, List<String>> kv = new LinkedHashMap<String, List<String>>();

        static synchronized Map<String, List<String>> of(Host h) {
            if (h == null) return EMPTY;
            try {
                File dir = h.skillDir();
                if (dir == null) return EMPTY;
                File f = new File(dir, "prompt.md");
                boolean exists = f.isFile();
                long m = exists ? f.lastModified() : 0L;
                if (file != null && file.equals(f) && m == stamp) return kv;
                Map<String, List<String>> next = exists ? parse(f) : new LinkedHashMap<String, List<String>>();
                file = f;
                stamp = m;
                kv = next;
                return next;
            } catch (Throwable t) {
                return EMPTY;
            }
        }

        static final Map<String, List<String>> EMPTY = Collections.emptyMap();

        /** {@code 键: 值} 一行一条；{@code #} 开头是注释；行内第一个（半角/全角）冒号是分隔符。 */
        private static Map<String, List<String>> parse(File f) {
            Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty() && line.charAt(0) == '\ufeff') line = line.substring(1);
                    String s = line.trim();
                    if (s.isEmpty() || s.charAt(0) == '#') continue;
                    int a = s.indexOf(':');
                    int b = s.indexOf('\uff1a');
                    int k = (a < 0) ? b : (b < 0 ? a : Math.min(a, b));
                    if (k <= 0) continue;
                    String key = s.substring(0, k).trim();
                    String val = s.substring(k + 1).trim();
                    if (key.isEmpty()) continue;
                    List<String> one = m.get(key);
                    if (one == null) {
                        one = new ArrayList<String>();
                        m.put(key, one);
                    }
                    one.add(val);
                }
            } catch (Throwable ignored) {
                // 读失败 = 空表 = 全用兜底值
            } finally {
                if (r != null) {
                    try {
                        r.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return m;
        }

        private Cfg() {}
    }

    private static String one(Map<String, List<String>> c, String key, String def) {
        if (c == null) return def;
        List<String> l = c.get(key);
        if (l == null || l.isEmpty()) return def;
        String v = l.get(0);
        return v == null ? def : v;
    }

    private static List<String> all(Map<String, List<String>> c, String key) {
        if (c == null) return Collections.emptyList();
        List<String> l = c.get(key);
        return l == null ? Collections.<String>emptyList() : l;
    }

    private static int num(Map<String, List<String>> c, String key, int def) {
        String v = one(c, key, null);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean flag(Map<String, List<String>> c, String key, boolean def) {
        String v = one(c, key, null);
        if (v == null) return def;
        String s = v.trim().toLowerCase();
        if (s.isEmpty()) return def;
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")
                || s.equals("开") || s.equals("是")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")
                || s.equals("关") || s.equals("否")) return false;
        return def;
    }
}
