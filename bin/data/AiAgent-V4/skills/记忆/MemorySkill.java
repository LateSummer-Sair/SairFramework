import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sair.v4.auth.Caller;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;
import sair.v4.store.LibSpec;
import sair.v4.store.Libs;
import sair.v4.store.Store;

/**
 * 记忆（工具 {@code memory}）：把"该长期记住的事"按<b>作用域</b>存进六库的 {@code memory} 库。
 *
 * <p>四个 op：<br>
 * ① {@code remember}：记一条（<b>先查重</b>：同作用域下内容相同 / 一方包含另一方 → 只累加命中次数；
 *    并记下<b>来源场合</b> {@code extra.origin}）；<br>
 * ② {@code recall}：按作用域取回；不给 scope 时分成三层节 ——「关于这个人」（{@code user}，跨群）→
 *    「本群」（{@code group}）→「全体」（{@code global}），每条都标出来源场合；<br>
 * ③ {@code search}：全文检索（{@code Store.search} 没有 filter，先多取再在 Java 侧按作用域过滤）；<br>
 * ④ {@code forget}：按 id 删（<b>只能删本人的 user 记忆，或主人删任意一条</b>）。</p>
 *
 * <p><b>人是跨群的同一人</b>：关于一个人的事默认落 {@code user}（{@code scope_id}=他的 QQ），
 * 换个群还取得到；只在某个群成立的事落 {@code group}，不会跟着人跨群跑。判断口径写在
 * {@code prompt.md} 与本技能 md 的工具说明里。</p>
 *
 * <p><b>归属口径（ACL 之后的新口径 —— 与权限位无关）</b>：{@code MASTER}（主人）与 {@code SYSTEM}
 * （她自己的自主行为）照常全放行；受限的只有 {@code ALLUSER}（普通用户），而且判的是<b>代码里的显式归属</b>：
 * <ul>
 *   <li>写：只能写 {@code scope=user && scope_id==自己 QQ}，或 {@code scope=group && scope_id==自己所在群}；
 *       {@code global} 与别人的／别群的归属一律<b>拒</b>（不再是"降级成自己的 user"）；</li>
 *   <li>读：{@code 本人 / 本群 / global}（与 {@link #visible} 同口径），别人的 {@code user}、别群的 {@code group} 拒；</li>
 *   <li>删：{@code master || 自己}（自己的 {@code user} 记忆）。</li>
 * </ul>
 * <p><b>为什么还要归属硬判</b>：{@code memory} 是 C 类资源，C 类对非主人只有 {@code R}（{@code RWX} 只有 {@code SYSTEM} 有）
 * ⇒ {@code h.needDb("memory",'W')} 把非主人写一律拦下；而"这是不是你的数据"仍靠这里的显式归属比较（归属硬判仍在，读口与非本人行仍靠它）。</p>
 */
public class MemorySkill implements Skill {

    /** 技能名（= 文件夹名 = md 名），也是 memory 行 source 列的值与日志前缀。 */
    private static final String NAME = "记忆";
    /** 六库里的长期记忆库。 */
    private static final String LIB = "memory";
    /** 三个作用域（结构性取值，不是文案）。 */
    private static final String S_USER = "user";
    private static final String S_GROUP = "group";
    private static final String S_GLOBAL = "global";
    /** 标签的结构性兜底（真正生效的值在 prompt.md 的「记忆标签」，默认是「记忆」）。 */
    private static final String TAG_DEF = "memory";
    /** 删「别人的 / 全局的」记忆时用的 C 类内存资源名（历史遗留的权限位判定，见 {@link #checkForgetOther}）。 */
    private static final String RES_FORGET_OTHER = "memory.forget";
    /** 写「global」记忆时用的 C 类内存资源名（与整库写分开，免得放宽整库写顺带放开全局写）。 */
    private static final String RES_WRITE_GLOBAL = "memory.remember.global";

    /**
     * 归属阻断的固定前缀。<b>必须由代码保证</b>（探针按这个前缀断言），不能走 prompt.md ——
     * 否则一句配置改动就能把"非主人的数据守卫"改没。
     */
    private static final String DENY = "[权限阻断] ";

    // ================================================================ 库声明（装载期）

    /**
     * 声明 {@code memory} 表：<b>谁写入、谁召回，谁声明</b>（表结构不再写在基板里）。
     *
     * <p>列/类型/默认值/FTS 列/索引名/中文别名逐字对应现网 {@code v4.db} —— 线上已经有真实数据，
     * 改结构 = 迁移 = 不允许。基板 {@code Db} 只负责"缺表即建、缺列即补"（幂等）。</p>
     */
    @Override
    public void declare(Libs libs) {
        libs.register(new LibSpec(LIB, "长期记忆", NAME)
                .ts()
                .col("scope", "TEXT", "'global'")
                .col("scope_id", "TEXT", "''")
                .col("content", "TEXT", "''")
                .col("importance", "INTEGER", "0")
                .col("source", "TEXT", "''")
                .col("tags", "TEXT", "''")
                .col("extra", "TEXT", "''")
                .fts("content", "tags")
                .alias("记忆", "长期记忆", "记忆库", "mem")
                .idx("idx_memory_ts", "ts")
                .idx("idx_memory_scope", "scope, scope_id")
                .idx("idx_memory_importance", "importance"));
    }

    // ================================================================ 工具入口

    @Override
    public Object call(Args a, Host h) {
        Cfg c = load(h);
        Store s = h == null ? null : h.store();
        if (s == null) return msg(c, "文案-无库", "store unavailable");
        String op = Str.trim(a.str("op")).toLowerCase();
        if (op.isEmpty()) op = "recall";
        try {
            if ("remember".equals(op)) return remember(a, h, s, c);
            if ("recall".equals(op)) return recall(a, h, s, c);
            if ("search".equals(op)) return search(a, h, s, c);
            if ("forget".equals(op)) return forget(a, h, s, c);
            return msg(c, "文案-未知op", "unsupported op={op}", "{op}", op);
        } catch (Throwable t) {
            log(h, "op=" + op + " error: " + t);
            return msg(c, "文案-执行异常", "error: {error}", "{error}", String.valueOf(t));
        }
    }

    // ================================================================ remember

    /**
     * 并发写闸门：同一个（库 + 作用域 + 作用域号）的「查重 → 写库」必须串行。
     *
     * <p>两条会话道（或主回合 + 定时钩子）同时 remember 同一句时，{@code findDup} 两边都查不到、
     * 于是各写一行 —— 实测过：并发写 {@code scope=global} 同一句 → 库里 **2 行**。
     * 锁粒度放在"库 + 作用域 + 作用域号"：不同作用域照常并行，同一作用域严格串行。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> WRITE_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<String, Object>();

    private static Object writeLock(String key) {
        String k = Str.blank(key) ? LIB : key;
        Object o = WRITE_LOCKS.get(k);
        if (o != null) return o;
        Object fresh = new Object();
        Object prev = WRITE_LOCKS.putIfAbsent(k, fresh);
        return prev == null ? fresh : prev;
    }

    /** 记一条记忆：先查重，再落库；非主人只能记自己的 user / 自己所在群的 group（见 {@link #guardWriteScope}）。 */
    private static String remember(Args a, Host h, Store s, Cfg c) {
        // 查重与写库之间不能有缝：按目标作用域串行（scope/scope_id 现算，算不出就退化成整库一把锁）
        String sc = scopeOf(a, "");
        if (!Str.has(sc) || "?".equals(sc)) sc = "?";
        String sid = idOf(a, h, sc);
        synchronized (writeLock(LIB + "|" + sc + "|" + Str.nz(sid))) {
            return rememberLocked(a, h, s, c);
        }
    }

    private static String rememberLocked(Args a, Host h, Store s, Cfg c) {
        String deny = checkWrite(h);
        if (deny != null) return deny;
        String content = Str.trim(a.str("content"));
        if (content.isEmpty()) return a.missing("content");
        if (content.length() > atLeast(P(c, "内容长度上限", "1000"), 20, 20000)) {
            return msg(c, "文案-内容太长", "content too long limit={limit}",
                    "{limit}", P(c, "内容长度上限", "1000"));
        }
        String scope = scopeOf(a, "");
        if (scope.isEmpty()) {
            // 不给 scope 就用 prompt.md 的「默认作用域」；这一项缺失时**直接报缺配置**，
            // 不偷偷按 user 记（缺键不兜底：口径只在 prompt.md 里，代码不替它决定）。
            String def = Str.trim(c.kv.get("默认作用域"));
            if (!Str.has(def)) {
                return msg(c, "文案-缺默认作用域", "prompt.md missing config: 默认作用域");
            }
            scope = scopeOf(a, def);
        }
        if ("?".equals(scope)) {
            return msg(c, "文案-作用域非法", "bad scope={scope}", "{scope}", a.str("scope"));
        }
        long me = qq(h);
        boolean master = master(h);
        String downgraded = "";
        if (S_GLOBAL.equals(scope) && restricted(h)) {
            // 归属硬判：global 记忆没有归属可依（全体共享的），非主人一律拒 —— 与权限位无关。
            // 注意：这一条在「越权全局降级为个人」之前 —— 降级是"写成了自己的 user"，不是拒绝。
            return guardWriteScope(h, scope, "");
        }
        if (S_GLOBAL.equals(scope) && !master) {
            String g = checkGlobalWrite(h);
            if (g != null) {
                if (!truthy(P(c, "越权全局降级为个人", "true"))) return g;
                scope = S_USER;
                downgraded = msg(c, "文案-降级尾巴", " (downgraded to user:{qq})", "{qq}", String.valueOf(me));
            }
        }
        String id = idOf(a, h, scope);
        if (id == null) return msg(c, "文案-缺作用域号", "scope id required scope={scope}", "{scope}", scope);
        // 归属硬判：非主人只能写「自己的 user / 自己所在群的 group」（原来这里只是 checkWrite(h)，
        // 而 C 类对非主人只有 R ⇒ 非主人写一律被位拦下；"写别人的、写别群的"仍由归属硬判裁定）。
        String scopeDeny = guardWriteScope(h, scope, id);
        if (scopeDeny != null) return scopeDeny;

        int imp = a.has("importance") ? clamp(a.i("importance", 5), 0, 10)
                : clamp((int) num(P(c, "默认重要度", "5"), 5L), 0, 10);
        String tags = tags(c, a.str("tags"));

        JsonObject same = findDup(s, c, scope, id, content);
        if (same != null) {
            long sid = J.l(same, "id", 0L);
            int hits = bump(s, same, imp);
            return msg(c, "文案-重复", "dup #{id} hits={hits}",
                    "{id}", String.valueOf(sid), "{hits}", String.valueOf(hits),
                    "{scope}", scope, "{sid}", id) + downgraded;
        }

        JsonObject row = new JsonObject();
        row.addProperty("scope", scope);
        row.addProperty("scope_id", id);
        row.addProperty("content", content);
        row.addProperty("importance", imp);
        row.addProperty("source", NAME);
        row.addProperty("tags", tags);
        JsonObject ex = new JsonObject();
        ex.addProperty("hits", 1);
        if (me > 0L) ex.addProperty("by", me);
        String who = name(h);
        if (Str.has(who)) ex.addProperty("by_name", who);
        JsonObject org = origin(h);
        if (org != null) ex.add("origin", org);
        row.add("extra", ex);
        long rid = s.put(LIB, row);
        if (rid <= 0L) return msg(c, "文案-写入失败", "store put failed");
        return msg(c, "文案-已记住", "remembered #{id} {scope}:{sid} importance={importance}",
                "{id}", String.valueOf(rid), "{scope}", scope, "{sid}", id,
                "{importance}", String.valueOf(imp), "{tags}", tags,
                "{content}", Str.cut(Str.oneLine(content), 80)) + downgraded;
    }

    // ================================================================ recall

    /**
     * 取回记忆。
     *
     * <p><b>不给 scope</b>：分三层节返回，而不是混成一锅 ——<br>
     * ①「关于这个人」（{@code scope=user, scope_id=<当前说话的人>}）：<b>跨群</b>的，换个群照样取得到；<br>
     * ②「本群」（{@code scope=group}，只有当前群）；<br>
     * ③「全体」（{@code scope=global}）。<br>
     * 每一条都带<b>来源场合</b>（写这条时在哪个群 / 私聊 / 控制台），让模型自己判断
     * "这件事能不能在别的群说出来"。</p>
     *
     * <p><b>给了 scope</b>：只查那一层（与老口径一致），行上一样带来源场合。</p>
     */
    private static String recall(Args a, Host h, Store s, Cfg c) {
        String deny = checkRead(h);
        if (deny != null) return deny;
        int limit = a.i("limit", atLeast(P(c, "列表条数", "10"), 1, 200));
        if (limit <= 0) limit = atLeast(P(c, "列表条数", "10"), 1, 200);
        String q = Str.trim(a.str("query"));
        String scope = scopeOf(a, "");
        if (!scope.isEmpty()) {
            if ("?".equals(scope)) {
                return msg(c, "文案-作用域非法", "bad scope={scope}", "{scope}", a.str("scope"));
            }
            String id = idOf(a, h, scope);
            if (id == null) return msg(c, "文案-缺作用域号", "scope id required scope={scope}", "{scope}", scope);
            // 归属硬判（样板 = search 分支的 {@link #visible}）：非主人只能读「本人的 user / 本群的 group
            // / 全体 global」；别人的 user、别群的 group 一律拒（原来这里只是 checkRead(h),恒放行）。
            String scopeDeny = guardReadScope(h, scope, id);
            if (scopeDeny != null) return scopeDeny;
            List<JsonObject> rows = dedupe(fetch(s, c, scope, id, q, limit));
            sort(rows);
            if (rows.isEmpty()) return emptyOf(c, q);
            StringBuilder sb = new StringBuilder();
            sb.append(head(c, q, rows.size())).append('\n');
            int i = 0;
            for (JsonObject row : rows) {
                if (i >= limit) break;
                i++;
                sb.append(line(c, row, i)).append('\n');
            }
            return sb.toString().trim();
        }
        // 三层分节：关于这个人（跨群）→ 本群 → 全体
        List<JsonObject> mine = qq(h) > 0L
                ? dedupe(fetch(s, c, S_USER, String.valueOf(qq(h)), q, limit))
                : new ArrayList<JsonObject>();
        List<JsonObject> here = group(h) > 0L
                ? dedupe(fetch(s, c, S_GROUP, String.valueOf(group(h)), q, limit))
                : new ArrayList<JsonObject>();
        List<JsonObject> all = dedupe(fetch(s, c, S_GLOBAL, "", q, limit));
        sort(mine);
        sort(here);
        sort(all);
        int total = mine.size() + here.size() + all.size();
        if (total == 0) return emptyOf(c, q);
        StringBuilder sb = new StringBuilder();
        sb.append(head(c, q, total)).append('\n');
        int i = 0;
        i = block(sb, c, mine, i, limit, "文案-分节关于这个人", "person: {name} ({qq}) cross_group n={n}",
                "{name}", Str.nz(name(h)), "{qq}", String.valueOf(qq(h)), "{n}", String.valueOf(mine.size()));
        i = block(sb, c, here, i, limit, "文案-分节本群", "this_group: {group} n={n}",
                "{group}", String.valueOf(group(h)), "{n}", String.valueOf(here.size()));
        block(sb, c, all, i, limit, "文案-分节全体", "global n={n}", "{n}", String.valueOf(all.size()));
        return sb.toString().trim();
    }

    /** 一节：分节头（模板在 prompt.md，缺了退回 ASCII 头）+ 这一节的条目行（最多 limit 条）。 */
    private static int block(StringBuilder sb, Cfg c, List<JsonObject> rows, int i, int limit,
                             String headKey, String headFallback, String... kv) {
        if (rows == null || rows.isEmpty()) return i;
        sb.append(msg(c, headKey, headFallback, kv)).append('\n');
        int n = 0;
        for (JsonObject row : rows) {
            if (n >= limit) break;
            i++;
            n++;
            sb.append(line(c, row, i)).append('\n');
        }
        return i;
    }

    /** 没有任何可回想的记忆时的回复（有 query 用「没有匹配」，没有 query 用「无记忆」）。 */
    private static String emptyOf(Cfg c, String q) {
        return q.isEmpty()
                ? msg(c, "文案-无记忆", "no_memory")
                : msg(c, "文案-没有匹配", "no_match query={query}", "{query}", q);
    }

    /** 列表头（有 query / 无 query 两个模板）。 */
    private static String head(Cfg c, String q, int n) {
        return q.isEmpty()
                ? msg(c, "文案-条目头", "memories={n}", "{n}", String.valueOf(n))
                : msg(c, "文案-条目头查", "memories={n} query={query}",
                        "{n}", String.valueOf(n), "{query}", q);
    }

    // ================================================================ search

    /** 全文检索：库侧多取，Java 侧按 source + 作用域过滤（非主人只看 全局 / 本群 / 本人）。 */
    private static String search(Args a, Host h, Store s, Cfg c) {
        String deny = checkRead(h);
        if (deny != null) return deny;
        String q = Str.trim(a.str("query"));
        if (q.isEmpty()) return a.missing("query");
        int limit = a.i("limit", atLeast(P(c, "检索条数", "5"), 1, 100));
        if (limit <= 0) limit = atLeast(P(c, "检索条数", "5"), 1, 100);
        List<JsonObject> found = s.search(LIB, q, limit * atLeast(P(c, "检索放大倍数", "6"), 1, 50));
        List<JsonObject> rows = new ArrayList<JsonObject>();
        for (JsonObject row : found) {
            if (!visible(h, row)) continue;
            rows.add(row);
            if (rows.size() >= limit) break;
        }
        if (rows.isEmpty()) return msg(c, "文案-没有匹配", "no_match query={query}", "{query}", q);
        StringBuilder sb = new StringBuilder();
        sb.append(head(c, q, rows.size())).append('\n');
        int i = 0;
        for (JsonObject row : rows) {
            i++;
            sb.append(line(c, row, i)).append('\n');
        }
        return sb.toString().trim();
    }

    // ================================================================ forget

    /** 删一条：本人自己的 user 记忆，或主人（{@code MASTER}）/她自己的自主行为（{@code SYSTEM}）删任意一条。 */
    private static String forget(Args a, Host h, Store s, Cfg c) {
        long id = a.l("id", 0L);
        if (id <= 0L) return a.missing("id");
        JsonObject row = s.get(LIB, id);
        if (row == null) return msg(c, "文案-没有这条", "not_found id={id}", "{id}", String.valueOf(id));
        boolean own = S_USER.equals(J.s(row, "scope", ""))
                && String.valueOf(qq(h)).equals(Str.nz(J.s(row, "scope_id", "")));
        // 归属硬判：非主人只能删自己的 user 记忆（原来这里只是 checkForgetOther(h) 的权限位判定，
        // 而 Res.mem("memory.forget") 不是受保护资源；C 类对非主人只有 R ⇒ 非主人写/删一律被位拦下，归属硬判仍在）。
        if (!own && restricted(h)) {
            return blocked("只能删自己的记忆（这一条是 " + Str.nz(J.s(row, "scope", ""))
                    + ":" + Str.nz(J.s(row, "scope_id", "")) + " 的）。");
        }
        String deny = own ? checkWrite(h) : checkForgetOther(h);
        if (deny != null) return deny;
        if (!s.delete(LIB, id)) return msg(c, "文案-删除失败", "delete failed id={id}", "{id}", String.valueOf(id));
        return msg(c, "文案-已忘记", "forgotten #{id}",
                "{id}", String.valueOf(id), "{scope}", Str.nz(J.s(row, "scope", "")),
                "{sid}", Str.nz(J.s(row, "scope_id", "")),
                "{content}", Str.cut(Str.oneLine(J.s(row, "content", "")), 60));
    }

    // ================================================================ 查询小工具

    /** 某一层里按关键词（或全部）取若干条。 */
    private static List<JsonObject> fetch(Store s, Cfg c, String scope, String id, String q, int limit) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (q.isEmpty()) {
            JsonObject f = new JsonObject();
            f.addProperty("scope", scope);
            f.addProperty("scope_id", id);
            f.addProperty("source", NAME);
            List<JsonObject> rows = s.list(LIB, f, limit, "importance DESC, ts DESC");
            if (rows != null) out.addAll(rows);
            return out;
        }
        List<JsonObject> found = s.search(LIB, q, limit * atLeast(P(c, "检索放大倍数", "6"), 1, 50));
        if (found == null) return out;
        for (JsonObject row : found) {
            if (!scope.equals(J.s(row, "scope", ""))) continue;
            if (!id.equals(Str.nz(J.s(row, "scope_id", "")))) continue;
            out.add(row);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /**
     * 查重：同一层里，内容归一化后相等 / 短的一边够长且被包含 → 视为同一条；
     * 都不中时再走<b>近似</b>判定（基板 {@link sair.v4.kit.Fingerprint} 的中文 2-gram Jaccard ≥ 相似阈值）——
     * "换了说法但说的是同一件事"的重复条目也压得住（阈值与最短项数在 prompt.md）。
     */
    private static JsonObject findDup(Store s, Cfg c, String scope, String id, String content) {
        int min = atLeast(P(c, "查重最短长度", "8"), 2, 200);
        String n = norm(content);
        if (n.isEmpty()) return null;
        JsonObject f = new JsonObject();
        f.addProperty("scope", scope);
        f.addProperty("scope_id", id);
        f.addProperty("source", NAME);
        List<JsonObject> rows = s.list(LIB, f, atLeast(P(c, "查重取样条数", "200"), 10, 1000), null);
        if (rows == null) return null;
        double threshold = dec(P(c, "相似阈值", "0.85"), 0.85D);
        int minTerms = atLeast(P(c, "指纹最短项数", "6"), 2, 200);
        java.util.Set<String> fp = sair.v4.kit.Fingerprint.bigram(content);
        boolean usable = sair.v4.kit.Fingerprint.enough(fp, minTerms);
        JsonObject near = null;
        double best = 0.0D;
        for (JsonObject row : rows) {
            String rn = norm(J.s(row, "content", ""));
            if (rn.isEmpty()) continue;
            if (rn.equals(n)) return row;
            String sh = n.length() <= rn.length() ? n : rn;
            String lo = n.length() <= rn.length() ? rn : n;
            if (sh.length() >= min && lo.contains(sh)) return row;
            if (!usable) continue;
            double score = sair.v4.kit.Fingerprint.jaccard(fp, sair.v4.kit.Fingerprint.bigram(J.s(row, "content", "")));
            if (score >= threshold && score > best) {
                best = score;
                near = row;
            }
        }
        return near;
    }

    /** 命中已有记忆：hits+1；新给的重要度更高就抬高。 */
    private static int bump(Store s, JsonObject row, int imp) {
        long id = J.l(row, "id", 0L);
        JsonObject ex = J.sub(row, "extra");
        if (ex == null) ex = new JsonObject();
        int hits = J.i(ex, "hits", 0) + 1;
        ex.addProperty("hits", hits);
        JsonObject patch = new JsonObject();
        patch.add("extra", ex);
        int old = J.i(row, "importance", 0);
        if (imp > old) patch.addProperty("importance", imp);
        s.update(LIB, id, patch);
        return hits;
    }

    /** 非主人可见：全局 / 本群 / 本人；主人可见全部。 */
    private static boolean visible(Host h, JsonObject row) {
        if (master(h)) return true;
        String scope = J.s(row, "scope", "");
        String id = Str.nz(J.s(row, "scope_id", ""));
        if (S_GLOBAL.equals(scope)) return true;
        if (S_USER.equals(scope)) return String.valueOf(qq(h)).equals(id);
        if (S_GROUP.equals(scope)) return group(h) > 0L && String.valueOf(group(h)).equals(id);
        return false;
    }

    private static List<JsonObject> dedupe(List<JsonObject> rows) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        Set<Long> seen = new LinkedHashSet<Long>();
        for (JsonObject row : rows) {
            long id = J.l(row, "id", 0L);
            if (id > 0L && !seen.add(Long.valueOf(id))) continue;
            out.add(row);
        }
        return out;
    }

    private static void sort(List<JsonObject> rows) {
        Collections.sort(rows, new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject x, JsonObject y) {
                int ix = J.i(x, "importance", 0);
                int iy = J.i(y, "importance", 0);
                if (ix != iy) return iy - ix;
                long tx = J.l(x, "ts", 0L);
                long ty = J.l(y, "ts", 0L);
                if (tx != ty) return tx > ty ? -1 : 1;
                return 0;
            }
        });
    }

    private static String line(Cfg c, JsonObject row, int i) {
        return msg(c, "文案-条目行", "{i}. #{id} [{importance}] {content}",
                "{i}", String.valueOf(i),
                "{id}", String.valueOf(J.l(row, "id", 0L)),
                "{importance}", String.valueOf(J.i(row, "importance", 0)),
                "{content}", Str.cut(Str.oneLine(J.s(row, "content", "")), 200),
                "{scope}", Str.nz(J.s(row, "scope", "")),
                "{sid}", Str.nz(J.s(row, "scope_id", "")),
                "{origin}", originOf(c, row),
                "{tags}", tagsOf(row),
                "{time}", time(c, J.l(row, "ts", 0L)),
                "{hits}", String.valueOf(J.i(J.sub(row, "extra"), "hits", 0)));
    }

    /**
     * 这条记忆的<b>来源场合</b>：写它的时候在哪个群 / 私聊 / 控制台。
     *
     * <p>跨群回想时必须知道"这话是在哪儿说的" —— 别人在 A 群私下告诉他的事，不该在 B 群当众复述。
     * 来源由 {@code remember} 写进 {@code extra.origin}；印象蒸馏写下的行没有 {@code origin}，
     * 但有 {@code extra.groups}（蒸馏取样覆盖到的群），就按它标；两样都没有的老行
     * <b>如实说"来源未记"</b>，不猜。</p>
     */
    private static String originOf(Cfg c, JsonObject row) {
        JsonObject ex = J.sub(row, "extra");
        JsonObject o = ex == null ? null : J.sub(ex, "origin");
        if (o != null) {
            long g = J.l(o, "group", 0L);
            if (g > 0L) return msg(c, "文案-来源群", "group {group}", "{group}", String.valueOf(g));
            if ("console".equals(J.s(o, "entry", ""))) return msg(c, "文案-来源控制台", "console");
            return msg(c, "文案-来源私聊", "private");
        }
        com.google.gson.JsonArray gs = ex == null ? null : J.list(ex, "groups");
        if (gs != null && gs.size() > 0) {
            List<String> ids = new ArrayList<String>();
            for (com.google.gson.JsonElement e : gs) {
                if (e == null || e.isJsonNull()) continue;
                String v = Str.trim(e.getAsString());
                if (!v.isEmpty()) ids.add(v);
            }
            if (!ids.isEmpty()) {
                return msg(c, "文案-来源印象", "impression groups {groups}",
                        "{groups}", Str.join(ids, "、"), "{n}", String.valueOf(ids.size()));
            }
        }
        return msg(c, "文案-来源未记", "origin_unknown");
    }

    /**
     * 这条记忆是在哪个场合写下的（{@code extra.origin}）：会话键 + 入口 + 群号。
     * <p>只记数据，不记句子 —— 怎么念出来由 {@code prompt.md} 的文案模板决定。</p>
     */
    private static JsonObject origin(Host h) {
        try {
            if (h == null || h.caller() == null) return null;
            JsonObject o = new JsonObject();
            o.addProperty("entry", h.caller().isConsole() ? "console" : "qq");
            long g = h.caller().groupId();
            if (g > 0L) o.addProperty("group", g);
            String ses = Str.trim(h.session());
            if (Str.has(ses)) o.addProperty("session", ses);
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ 作用域 / 权限

    /**
     * 受限主体判定（ACL 之后的新口径）：受限的只有 {@code ALLUSER}（普通用户）。
     *
     * <p>{@code MASTER}（主人）与 {@code SYSTEM}（她自己的自主行为 = 定时/钩子/自主维护）<b>照常放行</b>；
     * 身份取不到时按受限处理（fail-closed）—— "不知道是谁"不能当成"是主人"。</p>
     */
    private static boolean restricted(Host h) {
        try {
            Caller c = h == null ? null : h.caller();
            if (c == null) return true;
            return c.kind() == Caller.Kind.ALLUSER;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 归属阻断文案。<b>前缀由代码保证</b>（{@link #DENY}）：这是安全判定的输出，
     * 不走 prompt.md，免得"改一句文案"就把守卫改没。
     */
    private static String blocked(String why) {
        return DENY + why;
    }

    /**
     * 写作用域的<b>归属硬判</b>：非主人只能写「自己的 user 记忆」或「自己所在群的 group 记忆」；
     * {@code global}、别人的 {@code user}、别群的 {@code group} 一律拒。
     *
     * <p>放行返回 {@code null}；拒返回可直接回给模型的 {@code [权限阻断] …} 原文。</p>
     */
    private static String guardWriteScope(Host h, String scope, String id) {
        if (!restricted(h)) return null;
        if (S_USER.equals(scope)) {
            if (String.valueOf(qq(h)).equals(id)) return null;
            return blocked("只能写自己的记忆（这一条是 user:" + id + " 的）。");
        }
        if (S_GROUP.equals(scope)) {
            if (group(h) > 0L && String.valueOf(group(h)).equals(id)) return null;
            return blocked("只能写自己所在群的记忆（这一条是 group:" + id + " 的）。");
        }
        return blocked("非主人不能写 " + scope + " 作用域的记忆（只能写自己的 user 或自己所在群的 group）。");
    }

    /**
     * 读作用域的<b>归属硬判</b>：与 {@link #visible} 同口径 —— 非主人只能读「本人的 user / 本群的 group /
     * 全体 global」；别人的 {@code user}、别群的 {@code group} 一律拒。
     */
    private static String guardReadScope(Host h, String scope, String id) {
        if (!restricted(h)) return null;
        if (S_GLOBAL.equals(scope)) return null;
        if (S_USER.equals(scope)) {
            if (String.valueOf(qq(h)).equals(id)) return null;
            return blocked("只能读自己的记忆（这一条是 user:" + id + " 的）。");
        }
        if (S_GROUP.equals(scope)) {
            if (group(h) > 0L && String.valueOf(group(h)).equals(id)) return null;
            return blocked("只能读自己所在群的记忆（这一条是 group:" + id + " 的）。");
        }
        return blocked("非主人不能读 " + scope + " 作用域的记忆。");
    }

    private static String scopeOf(Args a, String def) {
        String s = Str.trim(a.str("scope"));
        if (s.isEmpty()) return def;
        String low = s.toLowerCase();
        if (S_USER.equals(low)) return S_USER;
        if (S_GROUP.equals(low)) return S_GROUP;
        if (S_GLOBAL.equals(low)) return S_GLOBAL;
        return "?";
    }

    private static String idOf(Args a, Host h, String scope) {
        if (S_GLOBAL.equals(scope)) return "";
        long given = a.l("scope_id", 0L);
        if (given <= 0L && S_USER.equals(scope)) given = a.l("qq", 0L);
        if (given <= 0L && S_GROUP.equals(scope)) given = a.l("group", 0L);
        if (given <= 0L) given = S_USER.equals(scope) ? qq(h) : group(h);
        return given <= 0L ? null : String.valueOf(given);
    }

    // ================================================================ 配置 / 文案（prompt.md）

    private static Cfg load(Host h) {
        Cfg c = new Cfg();
        String text = promptText(h);
        if (Str.blank(text)) return c;
        c.kv = pairs(section(text, "配置"));
        c.tx = pairs(section(text, "文案"));
        return c;
    }

    private static String promptText(Host h) {
        try {
            File dir = h == null ? null : h.skillDir();
            if (dir == null && h != null) dir = new File(h.conf().skillsDir(), NAME);
            return dir == null ? "" : Fs.read(new File(dir, "prompt.md"), "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static final class Cfg {
        Map<String, String> kv = new LinkedHashMap<String, String>();
        Map<String, String> tx = new LinkedHashMap<String, String>();
    }

    private static String P(Cfg c, String key, String def) {
        String v = c.kv.get(key);
        return Str.has(v) ? v.trim() : def;
    }

    private static int atLeast(String v, int min, int max) {
        int n;
        try {
            n = Integer.parseInt(Str.trim(v));
        } catch (Exception e) {
            n = min;
        }
        if (n < min) n = min;
        return n > max ? max : n;
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        return v > max ? max : v;
    }

    private static String msg(Cfg c, String key, String fallback, String... kv) {
        String tpl = c == null ? null : c.tx.get(key);
        return fill(Str.has(tpl) ? tpl : fallback, kv);
    }

    private static String fill(String tpl, String... kv) {
        String s = Str.nz(tpl);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] == null) continue;
            s = s.replace(kv[i], Str.nz(kv[i + 1]));
        }
        return s;
    }

    private static String section(String text, String name) {
        if (Str.blank(text) || Str.blank(name)) return "";
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String head = "## " + name.trim();
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(head)) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (lines[i].trim().startsWith("## ")) break;
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }

    private static Map<String, String> pairs(String body) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (Str.blank(body)) return m;
        for (String raw : body.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf(':');
            if (i < 0) i = line.indexOf('：');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim();
            String v = line.substring(i + 1).trim();
            if (!k.isEmpty()) m.put(k, v);
        }
        return m;
    }

    // ================================================================ 小工具

    /** 读 {@code memory} 库（C 类）：放行返回 null，否则返回可直接回给模型的拒绝原文。 */
    private static String checkRead(Host h) {
        if (h == null) return null;
        return h.needDb(LIB, 'R');
    }

    /**
     * 写 {@code memory} 库（C 类）：放行返回 null，否则返回拒绝原文。
     * <p><b>它不再表达"这是不是你的数据"</b>：C 类对非主人只有 {@code R}，所以这一条把非主人写一律拦下；
     * 归属仍由 {@link #guardWriteScope} / {@link #guardReadScope} / {@link #restricted} 里的显式比较裁定（归属硬判仍在）。</p>
     */
    private static String checkWrite(Host h) {
        if (h == null) return null;
        return h.needDb(LIB, 'W');
    }

    /**
     * 写 global 记忆：C 类内存资源的写判定（与整库写分开判）。
     * <p>非主人（{@code ALLUSER}）在 {@link #rememberLocked} 里已被归属硬判直接拒，走不到这里；
     * 这一条现在是"她自己的自主行为"这一路的位判定。</p>
     */
    private static String checkGlobalWrite(Host h) {
        if (h == null) return null;
        return h.need(sair.v4.auth.Res.mem(RES_WRITE_GLOBAL), 'W');
    }

    /**
     * 删别人的 / 全局的记忆：C 类内存资源的写判定。
     * <p>{@code Res.mem("memory.forget")} 不是受保护资源（受保护的只有 {@code mem:acl}）
     * ⇒ ALLUSER 的 C 位只有 {@code R} ⇒ 这一条把非主人写一律拦下；而"删别人的"仍由
     * {@link #forget} 里的归属硬判（{@code master || 自己}）裁定。</p>
     */
    private static String checkForgetOther(Host h) {
        if (h == null) return null;
        return h.need(sair.v4.auth.Res.mem(RES_FORGET_OTHER), 'W');
    }

    /** 标签：记忆标签 + 调用方标签（去重）。 */
    private static String tags(Cfg c, String raw) {
        List<String> out = new ArrayList<String>();
        String tag = P(c, "记忆标签", TAG_DEF);
        if (Str.has(tag)) out.add(tag.trim());
        if (Str.has(raw)) {
            String s = raw.trim();
            if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
            for (String p : s.split("[,，\"\\[\\]]+")) {
                String v = Str.trim(p);
                if (!v.isEmpty() && !out.contains(v)) out.add(v);
            }
        }
        return Str.join(out, ",");
    }

    private static String tagsOf(JsonObject row) {
        com.google.gson.JsonElement e = J.get(row, "tags");
        if (e == null) return "";
        if (e.isJsonArray()) {
            List<String> l = new ArrayList<String>();
            for (com.google.gson.JsonElement x : e.getAsJsonArray()) l.add(x.getAsString());
            return Str.join(l, ",");
        }
        return e.getAsString();
    }

    private static String norm(String s) {
        return Str.nz(s).toLowerCase().replaceAll("[^0-9a-z\\u4e00-\\u9fff]+", "");
    }

    private static String time(Cfg c, long ts) {
        if (ts <= 0L) return "";
        return new SimpleDateFormat(P(c, "时间格式", "yyyy-MM-dd HH:mm")).format(new Date(ts));
    }

    private static long num(String v, long def) {
        try {
            return Long.parseLong(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    /** 小数配置（相似阈值一类的 0..1 参数）；解析不了就用调用方给的口径值。 */
    private static double dec(String v, double def) {
        try {
            return Double.parseDouble(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean truthy(String v) {
        String s = Str.trim(v).toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "是".equals(s);
    }

    private static boolean master(Host h) {
        try {
            return h != null && h.caller() != null && h.caller().master();
        } catch (Throwable t) {
            return false;
        }
    }

    private static long qq(Host h) {
        try {
            return h == null || h.caller() == null ? 0L : h.caller().qq();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long group(Host h) {
        try {
            return h == null || h.caller() == null ? 0L : h.caller().groupId();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String name(Host h) {
        try {
            return h == null || h.caller() == null ? "" : Str.nz(h.caller().name());
        } catch (Throwable t) {
            return "";
        }
    }

    private static void log(Host h, String message) {
        if (Str.blank(message)) return;
        try {
            Out o = h == null ? null : h.out();
            if (o != null) o.dim("[" + NAME + "] " + message);
        } catch (Throwable ignored) {
        }
    }
}
