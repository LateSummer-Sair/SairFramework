import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import sair.v4.auth.Caller;
import sair.v4.ext.BuildContext;
import sair.v4.ext.ContextProvider;
import sair.v4.kit.J;
import sair.v4.kit.Str;
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
 *   <li>{@link #order()} = {@code 10}；{@link #maxChars()} = {@code prompt.md} 的「预算」（默认 1200）。</li>
 * </ul>
 *
 * <h3>数据从哪来（只读）</h3>
 * <p>只用 {@link Host} 能力面：{@code h.store().search(...)}。本插件<b>不建库、不写库、无副作用</b>
 * （{@code BuildContext.subagent()} 为真时也一样 —— 因为压根没有写路径）。
 * 查询 {@code memory}（本会话口径 8 条）与 {@code grouplog}（跨群群聊 5 条）。</p>
 *
 * <h3>归属硬判（ACL 之后的新口径 —— 与权限位无关）</h3>
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
 *       （{@code 跨群条数} 5 行 / {@code 预算} 1200 字），机器外壳会挤掉真群友的行；
 *       ② 行模板 {@code - [群{group}] {nickname}: {content}} 里<b>没有</b>"是她发的"这个面，
 *       而标记行没有可信昵称（{@code nickname} 空 ⇒ 退成 {@code 某人}）⇒ 会被当成<b>某个群友的发言</b>；
 *       ③ 她自己的行已经由基板窗口给她看（P0-1 的本意），{@code grouplog} 里那一行也没被删 ——
 *       <b>事实面一行没少</b>，少掉的只是"把机器外壳当别人的话注入"这一层。</li>
 *   <li>{@code memory} 命中行：<b>③ 照旧（不过谓词）</b> —— {@code memory} 不是聊天记录；
 *       万一里面有一条 {@code content} 以 {@code [我发的 } 开头的<b>记忆本体</b>，那是她自己的素材，
 *       跳过就等于抹掉事实。</li>
 * </ol>
 *
 * <h3>数字出处（V3 原样，见 notes/feel-plugins.md 第二节）</h3>
 * <ul>
 *   <li>8 条 / 5 条：{@code QQPromptBuilder.java:695} / {@code :706}；</li>
 *   <li>查询门槛：{@code hasMeaningfulQuery} = {@code text.replaceAll("[\\s\\p{Punct}]+","").length() >= 4}
 *       （{@code QQPromptBuilder.java:719-722}）—— 短语气词不触发检索；</li>
 *   <li>单条 300 字截断：{@code QQPromptBuilder.java:700}；跨群单条 160 字：{@code UnifiedQQMemoryManager.java:798}；</li>
 *   <li>跨群行是 {@code "[群" + groupId + "] " + 昵称 + ": " + 内容}（{@code UnifiedQQMemoryManager.java:799}）；</li>
 *   <li>标题：{@code labels/qq.md} 的 {@code title.related-memories}=相关记忆、
 *       {@code title.cross-group-related}=跨群相关群聊记录（中文标题，见 md 第六节 1 的默认做法）。</li>
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
    private static final int DEF_N = 8;
    private static final int DEF_XN = 5;
    private static final int DEF_MINQ = 4;
    private static final int DEF_BUDGET = 1200;
    private static final int DEF_CUT = 300;
    private static final int DEF_XCUT = 160;

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
     * 所以只能把「最近一次从 prompt.md 读到的预算」记下来 —— 第一次调用之前是兜底 1200。
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

            // 资源判定（ACL）：本 provider 只读、不写 —— 读 memory / grouplog 各判一次读位。
            // 上下文 provider 一般跑在"这一轮的触发者"主体下（C 类对非主人只有 R ⇒ 读得到）；
            // 注意这一层只表达"这一类资源能不能读"，**不表达"这是不是你的数据"** ——
            // 归属过滤在 render() 里逐条做（见 visible）。
            // 没有触发者时按 SYSTEM 判（与 {@code ExtRegistry.guarded} 同口径：扩展点没有触发者 = 她自己的
            // 自主行为，不是"没人 = 放行"）。真被拒时这一段安静地不产出，不拖垮这一轮。
            if (denied(h, LIB_MEM)) return "";
            if (flag(c, "跨群开关", true) && denied(h, LIB_LOG)) return "";

            String q = b.text();
            String filter = one(c, "查询过滤正则", DEF_FILTER);
            int min = num(c, "最小查询字数", DEF_MINQ);
            if (!meaningful(q, filter, min)) return "";     // 短语气词不触发检索（V3 口径）

            // 过滤依据 = "这一轮要说话的那个会话"（不是触发者）：SYSTEM / 没有触发者时
            // 目标群号从会话键或 snapshot 事实里解出来（见 aim）。
            Aim aim = aim(b);
            SimpleDateFormat fmt = timeFmt(one(c, "时间格式", DEF_TIME));
            String tail = one(c, "截断尾巴", DEF_TAIL);
            String anon = one(c, "匿名昵称", DEF_ANON);
            List<String> blocks = new ArrayList<String>();

            List<String> mem = render(s, LIB_MEM, q, num(c, "条数", DEF_N),
                    one(c, "行模板", DEF_LINE), num(c, "单条上限", DEF_CUT), tail, fmt, anon, false, aim);
            if (!mem.isEmpty()) blocks.add(pack(one(c, "标题", DEF_TITLE), one(c, "说明", DEF_LEAD), mem));

            if (flag(c, "跨群开关", true)) {
                List<String> cross = render(s, LIB_LOG, q, num(c, "跨群条数", DEF_XN),
                        one(c, "跨群行模板", DEF_XLINE), num(c, "跨群单条上限", DEF_XCUT), tail, fmt, anon, true,
                        aim);
                if (!cross.isEmpty()) blocks.add(pack(one(c, "跨群标题", DEF_XTITLE), one(c, "跨群说明", ""), cross));
            }

            if (blocks.isEmpty()) return "";
            return Str.join(blocks, "\n");
        } catch (Throwable t) {
            return "";                                      // 插件绝不拖垮一轮对话
        }
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
     * @param aim    本轮的目标会话（{@link #aim}）：能不能注入由它裁，不看权限位
     */
    private static List<String> render(Store s, String lib, String q, int n, String tpl,
                                       int cut, String tail, SimpleDateFormat fmt,
                                       String anon, boolean dedupe, Aim aim) {
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
     * <p>判的是<b>代码里的显式归属比较</b>，不是权限位 —— {@code memory} / {@code grouplog} 都是 C 类资源，
     * ACL 的 C 类对非主人只有 {@code R}，所以 {@link #denied}（只判 C 的 {@code R} 位）对新旧口径都拦不住
     * 「别人的数据」。</p>
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

    /**
     * 读这个库被拒了吗（ACL：读库 → {@code C} 的 {@code R} 位）。
     *
     * <p>主体取当轮绑定的调用者；<b>没有触发者时按 SYSTEM</b>（{@code Caller.systemActor()}）——
     * 与 {@code ExtRegistry.guarded} 同口径：扩展点没有触发者 = 她自己的自主行为，不是"没人 = 放行"。
     * 权限面没装配（{@code acl()==null}，例如假宿主）时不拦，避免把基板自己的装配流程弄成"什么都不产出"。</p>
     *
     * <p><b>它拦不住"别人的数据"</b>：C 类对非主人只有 {@code R}（读位恒给），这一条对谁都是放行；
     * 归属由 {@link #visible} 逐条硬判。</p>
     */
    private static boolean denied(Host h, String lib) {
        if (h == null) return false;
        try {
            sair.v4.auth.Auth a = h.acl();
            if (a == null) return false;
            Caller who = h.subject();
            return a.allowRes(who != null ? who : Caller.systemActor(), sair.v4.auth.Res.db(lib), 'R') != null;
        } catch (Throwable t) {
            return false;
        }
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
