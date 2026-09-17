import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import sair.v4.ext.BuildContext;
import sair.v4.ext.ContextProvider;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.qq.Api;
import sair.v4.skill.Host;

/**
 * 群成员映射：把「这个群里谁是谁」注入上下文（群名片/昵称 ↔ QQ + 角色）。
 *
 * <h3>挂在哪</h3>
 * <ul>
 *   <li>{@link ContextProvider}，{@link #stable()} = {@code true}（<b>稳定段</b>）——
 *       内容只由「这个群 + 这一批成员 + 一份配置」决定，<b>同输入逐字节不变</b>：
 *       不放时间戳、不放随机数、不放本轮正文、<b>也不放说话人</b>（谁在说话不是这一段的内容，
 *       渲染函数签名里就没有调用者信息）；段位口径见 {@code ContextProvider} 类注释：
 *       稳定段排在同一条系统提示词之后、事实块之前，一起构成可复用的前缀；</li>
 *   <li>{@link #order()} = {@code 30}（V4 已有：记忆召回 10 / 用户画像 20）；</li>
 *   <li>{@link #maxChars()} = {@code prompt.md} 的「预算」（默认 800）。</li>
 * </ul>
 *
 * <h3>V3 依据（{@code C:\Sair\Local\AiAgent}，包 {@code sair.aiagent}）</h3>
 * <ul>
 *   <li><b>顺序</b>：{@code onebot/QQPromptBuilder.java:63-64} 的稳定段顺序注释
 *       「技能索引 → 群印象 → 群身份 → <b>群成员映射</b> → 结构化偏好 → 用户画像 → 会话日志」——
 *       这一段在 V3 里就是稳定段的一员，V4 的差距就是它没了；</li>
 *   <li><b>条数</b>：V3 本群映射 {@code ≤20} 人（{@code QQPromptBuilder.java:311} 的 {@code if (nc >= 20) break;}）、
 *       全局（跨群）昵称映射 {@code ≤15}（{@code :354} 的 {@code if (pc >= 15) break;}）；</li>
 *   <li><b>角色标记</b>：{@code roleMarks}({@code :740-748}) —— 群主 👑 / 管理 🔧
 *       （V3 的标记文案外挂在 {@code labels/qq.md} 的 {@code mark.owner-crown} / {@code mark.admin-wrench}，
 *       这里照抄成 {@code prompt.md} 的「群主标记/管理员标记」）。
 *       <b>V3 的 ⭐主人 这里没有照搬</b>：V3 的 {@code roleMarks} 按「主人 QQ <b>集合</b>」标，
 *       而 V4 没有主人清单的只读口（V3 有 {@code AiConfig.getMasterQQs()}）。用
 *       {@code b.master()}/{@code b.qq()} 去标"说话人自己是主人"能让它看起来像 V3，
 *       但那会让同一群、同一批成员的这一段的字节<b>随谁在说话而变</b>（主人一开口，整段前缀缓存就废一次）——
 *       违反稳定段纪律。所以这里<b>刻意不标 ⭐</b>：这一段的字节只由「群 + 成员快照 + 配置」决定，
 *       与说话人无关（V3 的标记同样与"谁在说话"无关，这就是它当年能进稳定段的原因）。</li>
 *   <li><b>排序稳定（V3 自己就踩过这个坑）</b>：{@code UnifiedQQMemoryManager.java:894-896}
 *       「固定顺序：{@code updated_at} 会随群里每次发言被刷新，按它排序会让「群昵称映射」这段稳定前缀的
 *       字节每次都可能变，打断 KV 前缀缓存」→ {@code ORDER BY nickname ASC, user_id ASC}；
 *       角色表同理 {@code :1152-1153}「避免 {@code updated_at} 抖动导致提示词字节漂移」
 *       → {@code ORDER BY role='owner' DESC, user_id ASC}。本插件把这条纪律固化在代码里（见 {@link #ORDER}）；</li>
 *   <li><b>后台取数</b>：{@code QQPromptBuilder.java:22-35} 的 {@code bgFetchPool}（1~2 个守护线程 + 有界队列
 *       + {@code DiscardOldestPolicy}）—— V3 的注释写得很直白：{@code getGroupInfo}/{@code getFriendList}
 *       这类<b>阻塞</b>调用不能丢进 AI 主链路，「一次卡住的 NapCat 调用就会吃掉 1/3 的 AI 处理能力」。
 *       本插件的 NapCat 查询同样走自己的后台守护线程（见 {@link #kickRefresh}），<b>build 永不阻塞</b>。</li>
 * </ul>
 *
 * <h3>数据从哪来（只读外部，不建库不写库）</h3>
 * <p>只看 {@link Host} 能力面：{@code h.napcat().call("get_group_member_list", {"group_id": gid})}
 * （OneBot v11；{@code data} 数组每项取 {@code user_id}/{@code nickname}/{@code card}/{@code role}）。
 * 缓存在<b>插件自己的内存</b>里（{@code ConcurrentHashMap}，按群、按 TTL），<b>不落盘、不写库、不建表</b>。</p>
 *
 * <p><b>权限闸门（重要，三条都要能容忍）</b>：{@code Host.napcat()} 是<b>带闸门</b>的接口
 * （{@code Boot.java} 装配第⑧步：{@code guardedApi} 的 {@code Guard} 按 {@code Ctx.caller()} 复核
 * 这个平台动作的 <b>ACL E 位</b>（{@code Res.platform(动作)} 的 {@code X} 位）；E 类默认分配
 * {@code MASTER RWX} / {@code SYSTEM RWX} / {@code ALLUSER 无}）。
 * 闸门只看<b>调用线程的 {@code Ctx} 绑定</b>：没有绑定主体 = 她自己的自主行为（{@code SYSTEM}）→ 放行；
 * 池满退回调用线程（caller-runs）时才按调用者裁决。
 * 所以这里<b>两种结果都可能出现</b>：数据，或 {@code [权限阻断] …} 文本
 * （{@code Api.call} 把拒绝原文放在 {@code error} 字段里）。
 * 本插件一律按"拿不到"处理（{@link #parse} 见到 {@code error} 字段就返回 null → 这一段不出），
 * <b>不抛异常、不重试轰炸</b>（失败也进缓存，见「失败缓存秒」）。</p>
 *
 * <h3>文案与阈值</h3>
 * <p>全在本插件目录的 {@code prompt.md}（{@code 键: 值}）：标题 / 说明 / 行模板 / 全局标题 / 全局行模板 /
 * 角色名 / 群主标记 / 管理员标记 / 无名占位 / 人数上限 / 全局昵称上限 / 全局昵称开关 /
 * 缓存秒 / 失败缓存秒 / 单名上限 / 预算 / 动作名 / 开关。
 * 下面的常量只是<b>文件读不到时的兜底</b>，与 {@code prompt.md} 的出厂值一一对应。
 * 读法：<b>惰性缓存 + 按文件 mtime 失效</b>（每轮不读盘，改完保存下一轮生效）。</p>
 *
 * <h3>护栏</h3>
 * <ul>
 *   <li>{@code build()} 外层 {@code catch (Throwable)} —— 插件绝不拖垮一轮对话；</li>
 *   <li>{@code host()}/{@code napcat()} 为 null → 空串；NapCat 未连接 → 空串（连查都不查）；</li>
 *   <li>整段在<b>独立守护线程</b>上查（不占用基板给的 200ms 扩展点预算：冷缓存那一轮先不出这一段，
 *       下一轮数据就到了；过期时先出旧快照、后台换新）；</li>
 *   <li>同群在飞不重复排队、失败按「失败缓存秒」进缓存 —— 最坏情况 = 每群每 30 秒一次查询。</li>
 * </ul>
 */
public class GroupMemberMapProvider implements ContextProvider {

    // ==================== 兜底默认值（真正生效的值在 prompt.md） ====================

    private static final String DEF_TITLE = "## 群成员映射";
    private static final String DEF_LEAD = "无图标=普通成员；要 @ 人时从上表挑一个称呼";
    private static final String DEF_LINE = "- {name} (QQ {qq}) {role}{mark}";
    private static final String DEF_GTITLE = "## 跨群昵称映射";
    private static final String DEF_GLINE = "- {name} (QQ {qq}) {role}{mark}";
    private static final String DEF_ROLES = "owner=群主,admin=管理员,member=成员";
    private static final String DEF_MARK_OWNER = "\ud83d\udc51";      // 👑
    private static final String DEF_MARK_ADMIN = "\ud83d\udd27";      // 🔧
    private static final String DEF_UNNAMED = "未知";
    private static final String DEF_ACTION = "get_group_member_list";
    private static final int DEF_LIMIT = 20;
    private static final int DEF_GLIMIT = 15;
    private static final int DEF_TTL = 60;
    private static final int DEF_FAIL_TTL = 30;
    private static final int DEF_NAME_CAP = 24;
    private static final int DEF_BUDGET = 800;

    /** 群快照缓存条数上限（内部常量，不是旋钮）：超了就整体丢弃，宁可重新查也不让内存无界。 */
    private static final int MAX_GROUPS = 64;
    /** 跨群昵称表条数上限（内部常量）：超了丢最旧的。 */
    private static final int MAX_NICKS = 256;
    /** 后台取数队列长度（V3 的 bgFetchPool 是 16；满了直接丢弃这一单，不扩线程）。 */
    private static final int FETCH_QUEUE = 16;

    /**
     * {@link #maxChars()} 的签名里没有 Host（基板调它时不给上下文），
     * 所以只能把「最近一次从 prompt.md 读到的预算」记下来 —— 第一次调用之前是兜底 800。
     */
    private volatile int budget = DEF_BUDGET;

    // ==================== ContextProvider ====================

    @Override
    public boolean stable() {
        return true;               // 稳定段：同输入逐字节不变（前缀缓存友好）
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public int maxChars() {
        int b = budget;
        return b > 0 ? b : DEF_BUDGET;
    }

    /** 只在群聊出这一段；私聊没有"群成员"可言，拿不到 NapCat（host=null）也别出。 */
    @Override
    public boolean appliesTo(BuildContext b) {
        try {
            return b != null && b.host() != null && b.group() && b.groupId() > 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String build(BuildContext b) {
        try {
            if (b == null) return "";
            Host h = b.host();
            Map<String, List<String>> c = Cfg.of(h);
            budget = num(c, "预算", DEF_BUDGET);               // 先记预算：这一段不出也要让 maxChars() 跟上
            if (!flag(c, "开关", true)) return "";
            if (h == null) return "";                          // 拿不到 NapCat 就别出这一段
            if (!b.group()) return "";                         // 只在群聊
            long gid = b.groupId();
            if (gid <= 0L) return "";

            Api api = napcat(h);
            if (api == null) return "";
            if (!connected(h)) return "";                      // 未连接：连查都不查（也不写失败缓存）

            String action = one(c, "动作名", DEF_ACTION);
            int ttl = positive(num(c, "缓存秒", DEF_TTL), DEF_TTL);
            int failTtl = positive(num(c, "失败缓存秒", DEF_FAIL_TTL), DEF_FAIL_TTL);

            Cache.Entry e = Cache.get(gid);
            long now = System.currentTimeMillis();
            if (e == null) {
                kickRefresh(gid, api, action, failTtl);        // 冷缓存：这一轮先不出，后台取，下一轮就有
                return "";
            }
            int win = e.members == null ? failTtl : ttl;
            if (now - e.at >= win * 1000L) {
                kickRefresh(gid, api, action, failTtl);        // 过期：先出旧快照（stale-while-revalidate）
            }
            if (e.members == null || e.members.isEmpty()) return "";   // 上次查询失败：安静降级
            return render(c, e.members, e.global, budget);
        } catch (Throwable t) {
            return "";                                         // 插件绝不拖垮一轮对话
        }
    }

    // ==================== 渲染（纯函数：配置 + 快照 → 文本；不读盘、不查网、不看时间） ====================

    /**
     * 把成员快照渲染成这一段文本。
     *
     * <p><b>为什么是纯函数</b>：稳定段的字节只允许由「配置 + 快照」决定。
     * 快照本身是 {@link Cache.Entry} 里那份不可变副本（含跨群快照），所以在同一个缓存窗口里，
     * 无论调用多少次、<b>从哪个会话/哪个人调用</b>，输出都<b>逐字节一致</b> ——
     * 函数签名里因此<b>没有</b>调用者信息（谁在说话不是这一段的内容）。</p>
     *
     * @param c         prompt.md 键值表（null = 全用兜底）
     * @param group     本群成员快照
     * @param global    跨群昵称快照（可为 null）
     * @param budget    字符预算（{@code <=0} = 本段不按自身预算截断）
     * @return 文本块；一个成员行都放不下时返回空串（不出"有标题没内容"的空壳）
     */
    static String render(Map<String, List<String>> c, List<M> group, List<M> global, int budget) {
        if (c == null) c = Collections.emptyMap();
        Map<String, String> roles = pairs(one(c, "角色名", DEF_ROLES));
        String markOwner = one(c, "群主标记", DEF_MARK_OWNER);
        String markAdmin = one(c, "管理员标记", DEF_MARK_ADMIN);
        String unnamed = one(c, "无名占位", DEF_UNNAMED);
        int nameCap = num(c, "单名上限", DEF_NAME_CAP);
        int limit = positive(num(c, "人数上限", DEF_LIMIT), DEF_LIMIT);

        // 本群行（排序 + 按 QQ 去重 + 截断到「人数上限」）
        String lineTpl = one(c, "行模板", DEF_LINE);
        List<String> own = new ArrayList<String>();
        Set<Long> shown = new HashSet<Long>();
        for (M m : pick(group, limit)) {
            String line = fill(lineTpl,
                    "name", display(m, unnamed, nameCap),
                    "card", clip(m.card, "", nameCap),
                    "nickname", clip(m.nick, "", nameCap),
                    "nick", clip(m.nick, "", nameCap),
                    "qq", String.valueOf(m.qq),
                    "role", roleName(roles, m.role),
                    "role_raw", Str.nz(m.role),
                    "mark", marks(m, markOwner, markAdmin));
            if (!Str.blank(line)) {
                own.add(line.trim());
                shown.add(Long.valueOf(m.qq));
            }
        }

        // 跨群行（V3 的「全局昵称映射」：≤15 条，按 V3 的排法确定顺序）
        String glineTpl = one(c, "全局行模板", DEF_GLINE);
        List<String> other = new ArrayList<String>();
        if (flag(c, "全局昵称开关", true)) {
            int gl = positive(num(c, "全局昵称上限", DEF_GLIMIT), DEF_GLIMIT);
            for (M m : pickExcept(global, shown, gl)) {
                String line = fill(glineTpl,
                        "name", display(m, unnamed, nameCap),
                        "card", clip(m.card, "", nameCap),
                        "nickname", clip(m.nick, "", nameCap),
                        "nick", clip(m.nick, "", nameCap),
                        "qq", String.valueOf(m.qq),
                        "role", roleName(roles, m.role),
                        "role_raw", Str.nz(m.role),
                        "mark", marks(m, markOwner, markAdmin));
                if (!Str.blank(line)) other.add(line.trim());
            }
        }
        if (own.isEmpty() && other.isEmpty()) return "";

        // 预算：从尾部整行丢（先丢跨群、再丢本群）。一行的最小单位是"一个人"，
        // 绝不切半行（半截上下文比没有更坏 —— 与基板 ExtRegistry 的"整段丢"同口径）。
        String title = one(c, "标题", DEF_TITLE);
        String lead = one(c, "说明", DEF_LEAD);
        String gtitle = one(c, "全局标题", DEF_GTITLE);
        int ownN = own.size();
        int otherN = other.size();
        String body = assemble(title, lead, own, ownN, gtitle, other, otherN);
        while (budget > 0 && body.length() > budget && (otherN > 0 || ownN > 0)) {
            if (otherN > 0) otherN--;
            else ownN--;
            body = assemble(title, lead, own, ownN, gtitle, other, otherN);
        }
        if (ownN <= 0 && otherN <= 0) return "";               // 连一个人都放不下 → 这一段不出现
        return body;
    }

    /** 组装（标题/说明只在其小节有行时才出；空小节不出空壳）。 */
    private static String assemble(String title, String lead, List<String> own, int ownN,
                                   String gtitle, List<String> other, int otherN) {
        StringBuilder sb = new StringBuilder();
        if (ownN > 0) {
            if (!Str.blank(title)) sb.append(title.trim()).append('\n');
            if (!Str.blank(lead)) sb.append(lead.trim()).append('\n');
            for (int i = 0; i < ownN; i++) sb.append(own.get(i)).append('\n');
        }
        if (otherN > 0) {
            if (!Str.blank(gtitle)) sb.append(gtitle.trim()).append('\n');
            for (int i = 0; i < otherN; i++) sb.append(other.get(i)).append('\n');
        }
        int n = sb.length();
        while (n > 0 && sb.charAt(n - 1) == '\n') n--;         // 去尾换行（基板还会 trim 一次）
        return sb.substring(0, n);
    }

    /**
     * 排序键：<b>角色 → QQ → 名字</b>。
     *
     * <p>V3 的两条注解（{@code UnifiedQQMemoryManager.java:894} / {@code :1152}）说的就是这件事：
     * 顺序必须由<b>数据本身的稳定字段</b>决定，不能让"谁最后发言"（{@code updated_at}）之类的东西参与 ——
     * 否则同一批成员每轮排出来的字节都不同，稳定段的前缀缓存全废。
     * 三个键都是成员的固有属性，所以「同一批成员 → 同一段文本」是构造性成立的。</p>
     */
    private static final Comparator<M> ORDER = new Comparator<M>() {
        @Override
        public int compare(M a, M b) {
            int r = roleRank(a.role) - roleRank(b.role);
            if (r != 0) return r;
            if (a.qq != b.qq) return a.qq < b.qq ? -1 : 1;
            return FULL.compare(a, b);
        }
    };

    /** 同角色同 QQ（理论上不会发生）：用名片+昵称兜底，仍然是确定顺序。 */
    private static final Comparator<M> FULL = new Comparator<M>() {
        @Override
        public int compare(M a, M b) {
            return (Str.nz(a.card) + "\u0000" + Str.nz(a.nick))
                    .compareTo(Str.nz(b.card) + "\u0000" + Str.nz(b.nick));
        }
    };

    /** 角色次序：群主 → 管理员 → 成员 → 未知（未知排最后，但顺序仍然确定）。 */
    private static int roleRank(String role) {
        if ("owner".equalsIgnoreCase(role)) return 0;
        if ("admin".equalsIgnoreCase(role)) return 1;
        if ("member".equalsIgnoreCase(role)) return 2;
        return 3;
    }

    /** 排序 + 按 QQ 去重（同 QQ 多行时留下角色最高的那行）+ 截断到 {@code limit} 人。 */
    static List<M> pick(List<M> src, int limit) {
        List<M> out = new ArrayList<M>();
        if (src == null || src.isEmpty()) return out;
        List<M> l = new ArrayList<M>(src);
        Collections.sort(l, ORDER);
        Set<Long> seen = new HashSet<Long>();
        for (M m : l) {
            if (m == null || m.qq <= 0L) continue;
            if (!seen.add(Long.valueOf(m.qq))) continue;
            if (limit > 0 && out.size() >= limit) break;
            out.add(m);
        }
        return out;
    }

    /** 同上，但跳过已经在本群小节里出现过的 QQ（免得同一个人印两遍）。 */
    private static List<M> pickExcept(List<M> src, Set<Long> skip, int limit) {
        List<M> out = new ArrayList<M>();
        if (src == null || src.isEmpty()) return out;
        List<M> l = new ArrayList<M>(src);
        Collections.sort(l, ORDER);
        Set<Long> seen = new HashSet<Long>();
        for (M m : l) {
            if (m == null || m.qq <= 0L) continue;
            if (skip != null && skip.contains(Long.valueOf(m.qq))) continue;
            if (!seen.add(Long.valueOf(m.qq))) continue;
            if (limit > 0 && out.size() >= limit) break;
            out.add(m);
        }
        return out;
    }

    /** 角色中文名：命中「角色名」用表里的，表里没有就原样印（不发明新词），空 = member。 */
    private static String roleName(Map<String, String> roles, String raw) {
        String r = Str.trim(raw);
        if (r.isEmpty()) r = "member";
        String v = roles == null ? null : roles.get(r);
        if (v == null && roles != null) v = roles.get(r.toLowerCase());
        if (Str.blank(v)) return r;
        return v;
    }

    /** 可见标记：👑 群主 / 🔧 管理员（**只看成员自己的角色**，与谁在说话无关）。 */
    private static String marks(M m, String markOwner, String markAdmin) {
        if ("owner".equalsIgnoreCase(m.role)) return Str.nz(markOwner);
        if ("admin".equalsIgnoreCase(m.role)) return Str.nz(markAdmin);
        return "";
    }

    /** 称呼：群名片优先，空则昵称，再空则「无名占位」。 */
    private static String display(M m, String unnamed, int cap) {
        return clip(Str.blank(m.card) ? m.nick : m.card, unnamed, cap);
    }

    /** 压成单行 + 长度上限（防一张超长名片把整段预算吃掉）。 */
    private static String clip(String s, String fallback, int cap) {
        String t = Str.oneLine(s);
        if (t.isEmpty()) t = Str.nz(fallback);
        if (cap > 0 && t.length() > cap) t = t.substring(0, cap);
        return t;
    }

    // ==================== 取数（只读外部） ====================

    /**
     * 查一次 NapCat（本插件唯一的出网口）。
     *
     * @return 成员表；权限阻断 / 未连接 / 超时 / 非 JSON 一律 {@code null}
     */
    static List<M> fetch(Api api, String action, long gid) {
        if (api == null || gid <= 0L) return null;
        String act = Str.blank(action) ? DEF_ACTION : action.trim();
        JsonObject resp;
        try {
            resp = api.call(act, J.obj("group_id", gid));
        } catch (Throwable t) {
            return null;
        }
        return parse(resp);
    }

    /**
     * OneBot 响应 → 成员表。
     *
     * <p><b>容忍权限阻断</b>：{@code Api.call} 的失败口径是"补一个 {@code error} 字段"
     * （{@code {"error":"缺 X 位 —— [权限阻断] 需要 get_group_member_list 的 X 位（默认分配 NONE，当前有效位 NONE）"}}、
     * {@code {"error":"Napcat 没有连接"}}、{@code status=failed}…）。见到 {@code error} 就返回 null =
     * "拿不到"，<b>不是异常、也不再试</b>。</p>
     */
    static List<M> parse(JsonObject resp) {
        if (resp == null || J.get(resp, "error") != null) return null;
        JsonArray a = J.list(resp, "data");
        if (a == null) return null;
        List<M> out = new ArrayList<M>();
        for (JsonElement e : a) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            long qq = J.l(o, "user_id", 0L);
            if (qq <= 0L) continue;
            out.add(new M(qq, J.s(o, "card", ""), J.s(o, "nickname", ""), J.s(o, "role", "member")));
        }
        return out;
    }

    // ==================== 后台取数（V3 的 bgFetchPool 口径：绝不阻塞主链路） ====================

    /** 同群在飞集合：同一个群不重复排队（不轰炸 NapCat）。 */
    private static final Set<Long> INFLIGHT =
            Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());

    private static volatile ThreadPoolExecutor pool;

    /** 后台取数线程池（懒建；1 个守护线程 + 有界队列 —— V3 的 bgFetchPool 是 1~2 线程 + 16 队列）。 */
    private static ThreadPoolExecutor pool() {
        ThreadPoolExecutor p = pool;
        if (p != null) return p;
        synchronized (GroupMemberMapProvider.class) {
            if (pool == null) {
                pool = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<Runnable>(FETCH_QUEUE),
                        new ThreadFactory() {
                            private final AtomicInteger n = new AtomicInteger();

                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r, "v4-gmm-fetch-" + n.incrementAndGet());
                                t.setDaemon(true);                  // 守护线程：不挡进程退出
                                return t;
                            }
                        },
                        new ThreadPoolExecutor.AbortPolicy());
            }
            return pool;
        }
    }

    /**
     * 触发一次后台刷新（build 里唯一会"发起动作"的地方，且它立即返回）。
     *
     * <p>为什么不在 build 里同步查：基板给每个扩展点的预算是 {@code extTimeoutMs}（默认 200ms），
     * 超时就把这一段丢掉并<b>打断</b>线程 —— 同步查会被打断在半路，白查一次（还被记一行 timeout）。
     * 丢到自己的守护线程上：build 立刻返回（微秒级），结果落进缓存，下一轮就是缓存命中。</p>
     */
    private static void kickRefresh(final long gid, final Api api, final String action, final int failTtl) {
        if (api == null || !INFLIGHT.add(Long.valueOf(gid))) return;
        try {
            pool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        long at = System.currentTimeMillis();
                        List<M> list = fetch(api, action, gid);
                        if (list != null && !list.isEmpty()) {
                            List<M> cross = Nicks.observeAndSnapshot(list);
                            Cache.put(gid, at, list, cross);              // 本群快照 + 跨群快照一起冻住
                        } else {
                            Cache.put(gid, at, null, null);               // 失败也进缓存（失败缓存秒）
                        }
                    } catch (Throwable ignored) {
                        // 后台线程里绝不外抛
                    } finally {
                        INFLIGHT.remove(Long.valueOf(gid));
                    }
                }
            });
        } catch (Throwable t) {
            INFLIGHT.remove(Long.valueOf(gid));        // 队列满被拒：安静放弃，下一轮再试
        }
    }

    // ==================== 缓存（插件自己的内存，不落盘、不写库） ====================

    /**
     * 群快照缓存。
     *
     * <p>一条 Entry = 一次查询的<b>不可变</b>结果：本群成员表 + 那一刻的跨群昵称快照。
     * 两份都冻在同一条里，所以在一个缓存窗口内，"这一段"是纯粹的常量 —— 别的群在聊、
     * 别人改了名片，都不会让这一段的字节漂移（跨群的增量只会在本群下一次刷新时才进来）。</p>
     */
    static final class Cache {

        static final Map<Long, Entry> MAP = new ConcurrentHashMap<Long, Entry>();

        static final class Entry {
            /** 这一份快照是什么时候取到的（毫秒）。 */
            final long at;
            /** 成员表；{@code null} = 上一次查询失败（按「失败缓存秒」压住，不立刻重试）。 */
            final List<M> members;
            /** 取数那一刻的跨群昵称快照（已经排好序的副本）。 */
            final List<M> global;

            Entry(long at, List<M> members, List<M> global) {
                this.at = at;
                this.members = members;
                this.global = global;
            }
        }

        static Entry get(long gid) {
            return MAP.get(Long.valueOf(gid));
        }

        static void put(long gid, long at, List<M> members, List<M> global) {
            if (MAP.size() >= MAX_GROUPS && !MAP.containsKey(Long.valueOf(gid))) {
                MAP.clear();                       // 群太多：整体丢弃（内存有界优先，下一轮重新查）
            }
            MAP.put(Long.valueOf(gid), new Entry(at, members, global));
        }

        private Cache() {}
    }

    /**
     * 跨群昵称表（V3 的 {@code getPersonalNicknameMap} 在 V4 的落位）。
     *
     * <p>V4 没有成员/昵称表（{@code notes/v3-migration-map.md:128}：那三张表 V4 没有落位），
     * 所以这里用<b>插件自己的内存</b>被动累积：每次真查到一批群成员，就记下「QQ → 最近一次的称呼」。
     * 不额外查 NapCat、不落盘、不建表；有界（{@code MAX_NICKS}），满了丢最旧的。</p>
     */
    static final class Nicks {

        static final Map<Long, String> MAP = new LinkedHashMap<Long, String>();

        static synchronized void observe(List<M> list) {
            if (list == null) return;
            for (M m : list) {
                if (m == null || m.qq <= 0L) continue;
                String n = display(m, "", 0);
                if (Str.blank(n)) continue;
                MAP.remove(Long.valueOf(m.qq));     // 重新插入 = 更新（顺便挪到队尾 = 最近见到）
                MAP.put(Long.valueOf(m.qq), n);
            }
            while (MAP.size() > MAX_NICKS) {
                Long k = MAP.keySet().iterator().next();
                MAP.remove(k);
            }
        }

        /** 记下这批观测，并<b>在同一次锁内</b>取一份快照 —— 快照之后不会再有别的群挤进来。 */
        static synchronized List<M> observeAndSnapshot(List<M> list) {
            observe(list);
            return snapshot();
        }

        /** 取一份快照（副本；渲染只读它，不再看这张活表）。 */
        static synchronized List<M> snapshot() {
            List<M> out = new ArrayList<M>(MAP.size());
            for (Map.Entry<Long, String> e : MAP.entrySet()) {
                out.add(new M(e.getKey().longValue(), e.getValue(), "", "member"));
            }
            return out;
        }

        private Nicks() {}
    }

    // ==================== 一个成员（纯数据） ====================

    /**
     * 一行成员。
     * <p><b>刻意没有任何时间字段</b>（没有 join_time / last_sent_time / 取数时刻）——
     * 这是"同输入逐字节不变"的前提：渲染只看得到固有属性。</p>
     */
    static final class M {
        final long qq;
        final String card;
        final String nick;
        final String role;

        M(long qq, String card, String nick, String role) {
            this.qq = qq;
            this.card = Str.nz(card);
            this.nick = Str.nz(nick);
            this.role = Str.nz(role);
        }
    }

    // ==================== 只读能力面 ====================

    private static Api napcat(Host h) {
        if (h == null) return null;
        try {
            return h.napcat();
        } catch (Throwable t) {
            return null;
        }
    }

    /** NapCat 连没连（查不动就别查；查询本身还有闸门与失败两种"拿不到"）。 */
    private static boolean connected(Host h) {
        if (h == null) return false;
        try {
            return h.napcatConnected();
        } catch (Throwable t) {
            return true;               // 问不出来就按"连着"处理（让后面的失败缓存兜住），别静默关掉功能
        }
    }

    // ==================== 小工具 ====================

    private static int positive(int v, int def) {
        return v > 0 ? v : def;
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

    private static int colon(String s) {
        int a = s.indexOf(':');
        int b = s.indexOf('\uff1a');
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private static String[] split(String s) {
        if (Str.blank(s)) return new String[0];
        String[] parts = s.split("[,，]");
        List<String> out = new ArrayList<String>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[out.size()]);
    }

    /** {@code 别名=正式名} 的逗号表 → 映射。 */
    private static Map<String, String> pairs(String s) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String one : split(s)) {
            int k = one.indexOf('=');
            if (k <= 0) continue;
            String a = one.substring(0, k).trim();
            String b = one.substring(k + 1).trim();
            if (!a.isEmpty() && !b.isEmpty()) m.put(a, b);
        }
        return m;
    }

    // ==================== prompt.md（惰性缓存 + 按 mtime 失效） ====================

    /**
     * 本插件目录下 {@code prompt.md} 的键值表。
     * <p>读不到文件 / {@code host()} 为 null → 返回空表（调用方逐项落到兜底默认值）。
     * 缓存按「文件 + mtime」失效：每轮不读盘，改了文件下一轮生效。</p>
     */
    private static final class Cfg {

        static final Map<String, List<String>> EMPTY = Collections.emptyMap();

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
                    int k = colon(s);
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
