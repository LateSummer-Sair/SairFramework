import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * 偏好设定（工具 {@code pref}）：key/value 形态的偏好，三个作用域 —— {@code user}（某人）/
 * {@code group}（某群）/ {@code global}（全体）。落在六库的 {@code pref} 库。
 *
 * <p>三个 op：{@code get}（不给 scope 就按 user → group → global 逐层找）、{@code set}（写入 / 覆盖）、
 * {@code list}（列某个作用域下的全部条目）。</p>
 *
 * <p>写谁的作用域（<b>ACL 之后的新口径：代码里的显式归属比较，不是权限位</b>）：<br>
 * ① {@code global} → 非主人（{@code ALLUSER}）<b>一律拒</b>；{@code MASTER}/{@code SYSTEM} 照常；<br>
 * ② {@code group} → 非主人只能写<b>自己当前所在的那个群</b>，别的群一律拒；<br>
 * ③ {@code user} → 非主人只能写<b>自己</b>的（{@code scope_id == 自己 QQ}），写别人的一律拒。</p>
 *
 * <p><b>为什么还要归属硬判</b>：{@code pref} 是 C 类资源，C 类对非主人只有
 * {@code R} ⇒ 这个判定把非主人写一律拦下；"越权到别人/全局"仍要归属硬判
 * （见 {@link #guardWrite}）。</p>
 *
 * <p><b>读也按同一口径</b>（{@link #guardRead}）：非主人只能读自己的 {@code user}、自己所在群的
 * {@code group}、以及 {@code global}；别人的 {@code user} 一律拒 —— 偏好是 personal 数据，
 * 原来只判 {@code C} 的 {@code R} 位，等于谁都能读别人的个人偏好。</p>
 *
 * <p>零硬编码文案：全部文案、默认作用域、条数、长度上限、以及重要度的三个档值
 * （{@code 重要度-MASTER} / {@code 重要度-SYSTEM} / {@code 重要度-USER}）都在 {@code prompt.md}
 * —— <b>Java 里没有任何档位默认值</b>，缺键 / 坏值就明确拒绝这一笔写入（见 {@link #importanceOf}）。
 * 唯独归属阻断的 {@code [权限阻断]} 前缀、以及"prompt.md 缺键"这类安全 / 配置报错由代码保证
 * （不能靠配置：配置坏了不能再去读配置要文案）。</p>
 *
 * <p><b>重要度按身份三段定式</b>（不给 {@code importance} 时）：{@code MASTER}=MAX、
 * {@code SYSTEM}=HIGH、{@code USER}（= {@code ALLUSER}：群 + 普通个人）=LOW，三个数值只写在
 * {@code prompt.md}。<b>为什么要分档</b>：三层偏好（本人的 {@code user} + 当前群的 {@code group}
 * + {@code global}）会在每一轮合并成一份清单注入系统提示词，同一个 {@code key} 只留一条 ——
 * 先看 {@code importance}（大的赢），打平才看作用域具体度。所以要保证
 * <b>主人 &gt; 她本人 &gt; 群里 / 普通人</b>：主人定下的口径要压得住任何人的个人偏好，
 * 她自己的自主行为写下的判断次之（<b>{@code SYSTEM} 允许有自己的偏好</b>），全体用户与群最低。</p>
 */
public class PrefSkill implements Skill {

    /** 技能名（= 文件夹名 = md 名），也是日志前缀。 */
    private static final String NAME = "偏好设定";
    /** 六库里的偏好设定库。 */
    private static final String LIB = "pref";
    /** 三个作用域（结构性取值，不是文案）。 */
    private static final String S_USER = "user";
    private static final String S_GROUP = "group";
    private static final String S_GLOBAL = "global";
    /** 三段定式的档名（结构性取值，不是文案）：与 {@link Caller.Kind} 同名，拼成 prompt.md 的配置键。 */
    private static final String T_MASTER = "MASTER";
    private static final String T_SYSTEM = "SYSTEM";
    private static final String T_USER = "USER";
    /**
     * "prompt.md 缺键 / 坏值"的拒绝文案。<b>由代码保证</b>（与 {@link #DENY} 同理）：
     * 配置坏了不能再去读 {@code prompt.md} 要文案 —— 那样"缺的"就成了话术，报错反而消失。
     * 必须点名缺的是哪个键（{@code 重要度-MASTER} / {@code 重要度-SYSTEM} / {@code 重要度-USER}）。
     */
    private static final String MISSING_KEY = "[" + NAME + "] prompt.md 缺键：";
    private static final String BAD_VALUE = "[" + NAME + "] prompt.md 值不是数字：";
    /**
     * 归属阻断的固定前缀。<b>必须由代码保证</b>（探针按这个前缀断言），不能走 prompt.md ——
     * 否则一句配置改动就能把"非主人的作用域守卫"改没。
     */
    private static final String DENY = "[权限阻断] ";

    // ================================================================ 库声明（装载期）

    /**
     * 声明 {@code pref} 表：<b>谁写入、谁召回，谁声明</b>（表结构不再写在基板里）。
     *
     * <p>列/索引名/中文别名逐字对应现网 {@code v4.db}（改结构 = 迁移 = 不允许）。
     * key/value 形态、没有正文列，所以<b>没有 FTS</b>，检索走 {@code LIKE} 回退（{@code key/value}）。</p>
     *
     * <p><b>降级口径</b>：本插件不在 = 基板的偏好注入读不到 {@code pref} → 注入空列表、不报错
     * （{@code Store.prefsFor} 就是这么写的），一轮对话照常。</p>
     */
    @Override
    public void declare(Libs libs) {
        libs.register(new LibSpec(LIB, "偏好设定", NAME)
                .ts()
                .col("scope", "TEXT", "'global'")
                .col("scope_id", "TEXT", "''")
                .col("key", "TEXT", "''")
                .col("value", "TEXT", "''")
                .col("importance", "INTEGER", "0")
                .col("extra", "TEXT", "''")
                .like("key", "value")
                .alias("偏好", "偏好设定", "设置", "prefs", "preference")
                .idx("idx_pref_scope", "scope, scope_id")
                .idx("idx_pref_key", "key")
                .idx("idx_pref_ts", "ts"));
    }

    // ================================================================ 工具入口

    @Override
    public Object call(Args a, Host h) {
        Cfg c = load(h);
        Store s = h == null ? null : h.store();
        if (s == null) return msg(c, "文案-无库", "store unavailable");
        String op = Str.trim(a.str("op")).toLowerCase();
        if (op.isEmpty()) op = "get";
        try {
            if ("get".equals(op)) return get(a, h, s, c);
            if ("set".equals(op)) return set(a, h, s, c);
            if ("list".equals(op)) return list(a, h, s, c);
            return msg(c, "文案-未知op", "unsupported op={op}", "{op}", op);
        } catch (Throwable t) {
            log(h, "op=" + op + " error: " + t);
            return msg(c, "文案-执行异常", "error: {error}", "{error}", String.valueOf(t));
        }
    }

    // ================================================================ get

    /** 取一个偏好：不给 scope 就按「本人 → 本群 → 全局」逐层找，命中哪层就说哪层。 */
    private static String get(Args a, Host h, Store s, Cfg c) {
        String deny = check(h, 'R');
        if (deny != null) return deny;
        String key = Str.trim(a.str("key"));
        if (key.isEmpty()) return a.missing("key");
        String scope = scopeOf(a, "");
        List<String[]> chain = new ArrayList<String[]>();
        if (scope.isEmpty()) {
            if (qq(h) > 0L) chain.add(new String[] {S_USER, String.valueOf(qq(h))});
            if (group(h) > 0L) chain.add(new String[] {S_GROUP, String.valueOf(group(h))});
            chain.add(new String[] {S_GLOBAL, ""});
        } else {
            if ("?".equals(scope)) {
                return msg(c, "文案-作用域非法", "bad scope={scope}", "{scope}", a.str("scope"));
            }
            String id = idOf(a, h, scope);
            if (id == null) {
                return msg(c, "文案-缺作用域号", "scope id required scope={scope}", "{scope}", scope);
            }
            String guard = guardRead(h, scope, id, c, a);
            if (guard != null) return guard;
            chain.add(new String[] {scope, id});
        }
        for (String[] e : chain) {
            JsonObject row = find(s, e[0], e[1], key);
            if (row != null) {
                return msg(c, "文案-命中", "{scope}:{id} {key} = {value}",
                        "{scope}", e[0], "{id}", e[1], "{key}", key,
                        "{value}", Str.nz(J.s(row, "value", "")),
                        "{time}", time(c, J.l(row, "ts", 0L)),
                        "{importance}", String.valueOf(J.i(row, "importance", 0)));
            }
        }
        return msg(c, "文案-无此偏好", "not_found key={key}", "{key}", key);
    }

    // ================================================================ set

    /** 写一个偏好（存在就覆盖）：作用域越权由 {@link #guardWrite} 的归属硬判拦下。 */
    private static String set(Args a, Host h, Store s, Cfg c) {
        String deny = check(h, 'W');
        if (deny != null) return deny;
        String key = Str.trim(a.str("key"));
        if (key.isEmpty()) return a.missing("key");
        if (!a.has("value")) return a.missing("value");
        String value = Str.nz(a.str("value"));
        if (value.length() > atLeast(P(c, "值长度上限", "2000"), 8, 20000)) {
            return msg(c, "文案-值太长", "value too long limit={limit}",
                    "{limit}", P(c, "值长度上限", "2000"));
        }
        String scope = scopeOf(a, P(c, "默认作用域", S_USER));
        if ("?".equals(scope)) {
            return msg(c, "文案-作用域非法", "bad scope={scope}", "{scope}", a.str("scope"));
        }
        String id = idOf(a, h, scope);
        if (id == null) {
            return msg(c, "文案-缺作用域号", "scope id required scope={scope}", "{scope}", scope);
        }
        String guard = guardWrite(h, scope, id, c, a);
        if (guard != null) return guard;

        int imp;
        if (a.has("importance")) {
            // 显式 importance 优先：模型给了就用（越界夹到 0..10），坏值（不是数字）落到 0。
            // 0 与 10 是 importance 列的结构边界，不是档位默认值 —— 档值只在 prompt.md。
            imp = clamp(a.i("importance", 0), 0, 10);
        } else {
            // 没给才按身份取档：缺键 / 坏值 = 明确拒绝这一笔写入（不猜、不兜底）
            int[] box = new int[1];
            String bad = importanceOf(h, c, box);
            if (bad != null) return bad;
            imp = box[0];
        }
        JsonObject old = find(s, scope, id, key);
        JsonObject ex = old == null ? new JsonObject() : J.sub(old, "extra");
        if (ex == null) ex = new JsonObject();
        ex.addProperty("by", qq(h));
        String who = name(h);
        if (Str.has(who)) ex.addProperty("by_name", who);
        ex.addProperty("updated", System.currentTimeMillis());

        JsonObject row = new JsonObject();
        row.addProperty("scope", scope);
        row.addProperty("scope_id", id);
        row.addProperty("key", key);
        row.addProperty("value", value);
        row.addProperty("importance", imp);
        row.add("extra", ex);
        if (old != null) row.addProperty("id", J.l(old, "id", 0L));
        long rid = s.put(LIB, row);
        if (rid <= 0L) return msg(c, "文案-写入失败", "store put failed");
        String tpl = old == null ? "文案-已新增" : "文案-已更新";
        String fb = old == null ? "set #{id} {scope}:{id2} {key}" : "updated #{id} {scope}:{id2} {key}";
        return msg(c, tpl, fb,
                "{id}", String.valueOf(rid), "{scope}", scope, "{id2}", id,
                "{key}", key, "{value}", value, "{importance}", String.valueOf(imp),
                "{time}", time(c, System.currentTimeMillis()));
    }

    // ================================================================ list

    /** 列某个作用域下的全部偏好（默认：本人的 user 作用域）。 */
    private static String list(Args a, Host h, Store s, Cfg c) {
        String deny = check(h, 'R');
        if (deny != null) return deny;
        int limit = a.i("limit", atLeast(P(c, "列表条数", "20"), 1, 500));
        if (limit <= 0) limit = atLeast(P(c, "列表条数", "20"), 1, 500);
        String scope = scopeOf(a, P(c, "列表默认作用域", S_USER));
        if ("?".equals(scope)) {
            return msg(c, "文案-作用域非法", "bad scope={scope}", "{scope}", a.str("scope"));
        }
        String id = idOf(a, h, scope);
        if (id == null) {
            return msg(c, "文案-缺作用域号", "scope id required scope={scope}", "{scope}", scope);
        }
        String guard = guardRead(h, scope, id, c, a);
        if (guard != null) return guard;
        JsonObject filter = new JsonObject();
        filter.addProperty("scope", scope);
        filter.addProperty("scope_id", id);
        List<JsonObject> rows = s.list(LIB, filter, limit, "key ASC");
        if (rows == null || rows.isEmpty()) {
            return msg(c, "文案-该作用域为空", "empty {scope}:{id}", "{scope}", scope, "{id}", id);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, "文案-列表头", "{scope}:{id} prefs={n}",
                "{scope}", scope, "{id}", id, "{n}", String.valueOf(rows.size()))).append('\n');
        for (JsonObject row : rows) {
            sb.append(msg(c, "文案-列表行", "- {key} = {value}",
                    "{key}", Str.nz(J.s(row, "key", "")),
                    "{value}", Str.nz(J.s(row, "value", "")),
                    "{importance}", String.valueOf(J.i(row, "importance", 0)),
                    "{time}", time(c, J.l(row, "ts", 0L)))).append('\n');
        }
        return sb.toString().trim();
    }

    // ================================================================ 作用域 / 权限

    /** 作用域归一化；非法返回 "?"，空则返回 def。 */
    private static String scopeOf(Args a, String def) {
        String s = Str.trim(a.str("scope"));
        if (s.isEmpty()) return def;
        String low = s.toLowerCase();
        if (S_USER.equals(low)) return S_USER;
        if (S_GROUP.equals(low)) return S_GROUP;
        if (S_GLOBAL.equals(low)) return S_GLOBAL;
        return "?";
    }

    /** 作用域号：user 缺省 = 当前说话的人，group 缺省 = 当前群，global = 空串；缺信息返回 null。 */
    private static String idOf(Args a, Host h, String scope) {
        if (S_GLOBAL.equals(scope)) return "";
        long given = a.l("scope_id", 0L);
        if (given <= 0L && S_USER.equals(scope)) given = a.l("qq", 0L);
        if (given <= 0L && S_GROUP.equals(scope)) given = a.l("group", 0L);
        if (given <= 0L) given = S_USER.equals(scope) ? qq(h) : group(h);
        return given <= 0L ? null : String.valueOf(given);
    }

    /**
     * 读作用域：<b>归属硬判</b>（ACL 之后的新口径 —— 与权限位无关，与写口同口径）。
     *
     * <p>受限的只有 {@code ALLUSER}（普通用户）；{@code MASTER} 与 {@code SYSTEM}
     * （她自己的自主行为）照常放行。规则：非主人只能读<b>自己的 {@code user}</b>、
     * <b>自己所在群的 {@code group}</b>、以及 {@code global}；别人的 {@code user} 一律拒
     * （偏好是 personal 数据）。</p>
     *
     * <p>原来这里只判 {@code check(h,'R')}：{@code pref} 是 C 类资源、C 类对非主人只有 {@code R}
     * ⇒ 读位仍然放行 ⇒ 非主人读得到别人的个人偏好。</p>
     */
    private static String guardRead(Host h, String scope, String id, Cfg c, Args a) {
        if (!restricted(h)) return null;
        if (S_GLOBAL.equals(scope)) return null;
        if (S_USER.equals(scope)) {
            if (String.valueOf(qq(h)).equals(id)) return null;
            return blocked("只能读自己的偏好（这一条是 user:" + id + " 的）。");
        }
        if (S_GROUP.equals(scope)) {
            if (group(h) > 0L && String.valueOf(group(h)).equals(id)) return null;
            return blocked("只能读自己所在群的偏好（这一条是 group:" + id + " 的）。");
        }
        return blocked("非主人不能读 " + scope + " 作用域的偏好。");
    }

    /**
     * 写作用域：<b>归属硬判</b>（ACL 之后的新口径 —— 与权限位无关）。
     *
     * <p>受限的只有 {@code ALLUSER}（普通用户）；{@code MASTER}（主人）与 {@code SYSTEM}
     * （她自己的自主行为）照常放行。规则：</p>
     * <ul>
     *   <li>{@code global} → 拒（全体共享的口径不该由普通用户改）；</li>
     *   <li>{@code group} → 只允许自己当前所在的群（自己不在群里时也拒）；</li>
     *   <li>{@code user} → 只允许 {@code scope_id == 自己 QQ}；</li>
     *   <li>认不出的作用域 → 拒。</li>
     * </ul>
     *
     * <p>原来这三处都是 {@code return check(h,'W')}（= {@code pref} 库的写位）——
     * 而 C 类对非主人只有 {@code R} ⇒ 非主人写一律被位拦下；写别人的 / 别群的 / global 的仍由归属硬判裁定。</p>
     */
    private static String guardWrite(Host h, String scope, String id, Cfg c, Args a) {
        if (!restricted(h)) return null;
        if (S_USER.equals(scope)) {
            if (String.valueOf(qq(h)).equals(id)) return null;
            return blocked("只能写自己的偏好（这一条是 user:" + id + " 的）。");
        }
        if (S_GROUP.equals(scope)) {
            if (group(h) > 0L && String.valueOf(group(h)).equals(id)) return null;
            return blocked("只能写自己所在群的偏好（这一条是 group:" + id + " 的）。");
        }
        return blocked("非主人不能写 " + scope + " 作用域的偏好（只能写自己的 user 或自己所在群的 group）。");
    }

    // ================================================================ 读写小工具

    private static JsonObject find(Store s, String scope, String id, String key) {
        JsonObject f = new JsonObject();
        f.addProperty("scope", scope);
        f.addProperty("scope_id", id);
        f.addProperty("key", key);
        List<JsonObject> rows = s.list(LIB, f, 1, null);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    // ================================================================ 重要度（三段定式：按身份）

    /**
     * 身份 → 档名（三段定式）：{@code MASTER}（主人）/ {@code SYSTEM}（她自己的自主行为）/
     * {@code USER}（= {@code ALLUSER}：群 + 普通个人）。
     *
     * <p>判定顺序：{@link #master}（主人标志，含本地控制台）→
     * {@code kind() == }{@link Caller.Kind#SYSTEM}（<b>与 {@link #restricted} 同一判据</b>）→
     * 其余一律 {@code USER}。<b>取不到 caller ⇒ 按 {@code USER}（最低档）</b>：与 {@link #restricted}
     * 的 fail-closed 一致 —— 身份不明时绝不给高优先级的档。</p>
     *
     * <p>若一个调用者同时带 master 与 system 两个标志，这里算<b>主人</b>（{@code MASTER}=MAX）：
     * 档位顺序是"主人 &gt; 她"，不是 {@link Caller#kind()} 的"显式 SYSTEM 优先"。</p>
     */
    private static String tierOf(Host h) {
        if (master(h)) return T_MASTER;
        try {
            Caller c = h == null ? null : h.caller();
            if (c != null && c.kind() == Caller.Kind.SYSTEM) return T_SYSTEM;
        } catch (Throwable ignored) {
        }
        return T_USER;
    }

    /**
     * 三段定式的重要度：{@code 重要度-MASTER}（MAX）/ {@code 重要度-SYSTEM}（HIGH）/
     * {@code 重要度-USER}（LOW），<b>三个数值只存在于 prompt.md</b> —— Java 里没有任何档位默认值。
     *
     * <p><b>SYSTEM 也有自己的一档</b>：她的自主行为（定时维护、钩子、她自己主动记下的东西）
     * 不是"某个普通用户"写的 —— 优先级高于任何群与个人，但低于主人。</p>
     *
     * <p><b>缺键 / 坏值 = 明确拒绝这一笔写入</b>（点名缺的键，不猜、不兜底）：配置不全时宁可这一条
     * 先不写，也不给一个"看起来差不多"的档值 —— 那会让主人设定的口径被无声压掉。
     * 只影响写：读 / 列既有数据不看这张表。</p>
     *
     * @param out 出参：成功时把 0..10 的重要度写进 {@code out[0]}
     * @return {@code null} = 成功；否则返回拒绝文案（缺键 / 坏值）
     */
    private static String importanceOf(Host h, Cfg c, int[] out) {
        String tierKey = "重要度-" + tierOf(h);
        String raw = c.kv.get(tierKey);
        if (Str.blank(raw)) return MISSING_KEY + tierKey;
        long v = num(raw, Long.MIN_VALUE);
        if (v == Long.MIN_VALUE) return BAD_VALUE + tierKey + " = " + Str.trim(raw);
        out[0] = clamp((int) v, 0, 10);   // 0..10 是结构约束（importance 列的口径），不是档位默认值
        return null;
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

    /**
     * 归属阻断文案。<b>前缀由代码保证</b>（{@link #DENY}）：这是安全判定的输出，不走 prompt.md。
     */
    private static String blocked(String why) {
        return DENY + why;
    }

    /**
     * 受限主体判定（ACL 之后的新口径）：受限的只有 {@code ALLUSER}（普通用户）。
     * <p>{@code MASTER} / {@code SYSTEM}（她自己的自主行为）照常放行；身份取不到时按受限处理（fail-closed）。</p>
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
     * 资源判定（ACL）：碰 {@code pref} 库（C 类）—— 读判 {@code R}、写判 {@code W}。
     *
     * <p>旧的 op 档位（{@code pref.set.global} / {@code pref.get.user.other} …）不再是权限概念：
     * 权限只看"能不能碰 pref 这个库的读位/写位"。<b>它不表达"越权到别人/全局"</b> ——
     * C 类对非主人只有 {@code R} ⇒ 非主人写一律被位拦下；"写/读的是谁的作用域"仍由 {@link #guardWrite} / {@link #restricted} 里的归属硬判裁定。</p>
     *
     * @param bit {@code 'R'} 读 / {@code 'W'} 写
     */
    private static String check(Host h, char bit) {
        if (h == null) return null;
        return h.needDb(LIB, bit);
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

    private static long qq(Host h) {
        try {
            return h == null || h.caller() == null ? 0L : h.caller().qq();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 这个调用者是不是主人（拿不到身份时按"不是"处理：绝不给非主人按主人档算重要度）。 */
    private static boolean master(Host h) {
        try {
            return h != null && h.caller() != null && h.caller().master();
        } catch (Throwable t) {
            return false;
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
