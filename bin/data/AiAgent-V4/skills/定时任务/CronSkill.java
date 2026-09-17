import com.google.gson.JsonObject;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import sair.v4.skill.Hooked;
import sair.v4.skill.Skill;
import sair.v4.store.Store;

/**
 * 定时任务（工具 {@code cron} + {@code on_timer} 钩子）：cron 表达式（分 时 日 月 周）的任务表，
 * op {@code add / list / remove}，任务行存在 kv（前缀 {@code cron:t:}，序号 {@code cron:seq}）。
 *
 * <p><b>到点不自己跑模型</b>：钩子里只做"到点判定 + {@code h.spawn(任务, 工具, true)}"，
 * 把活交给主 Agent（异步任务票），技能自己立刻返回，不占钩子线程、不跑长回合。</p>
 *
 * <p><b>防重复触发</b>：任务行里记 {@code last_fired}（按分钟取整）。tick 一次可能来好几下，
 * 同一分钟内只触发一次；标记在派发之前写回 kv，所以即使派发被基板挡下也不会在同一分钟反复触发。</p>
 *
 * <p><b>归属隔离</b>：任务行记 {@code owner}（会话）；非主人只能看到自己建的（建/删走 kv 写位：非主人被位拦下，
 * 要特例放行；主人看全部），与基板闹钟 {@code alarm} 的 owner 语义一致。</p>
 *
 * <p><b>到点说回原处（落点）</b>：任务行还记 {@code session}（建任务时的会话键：{@code group:<群号>} /
 * {@code qq:<QQ>} / {@code console}），到点由宿主按它解析出真正的落点（{@code Host.spawnAs} 的那条口
 * / 基板 {@code Boot.sinkForDeliver}）——"到点提醒"因此说回创建它的那个群 / 私聊，而不是本机控制台。
 * 老数据行没有这个字段、或会话认不出来 → 基板回落<b>今天那个落点</b>（控制台）：不报错、不丢任务。</p>
 *
 * <p><b>到点派发的主体 = 任务创建者</b>（{@code owner}）：钩子本身的主体是 {@code SYSTEM}（她自己的自主行为），
 * 但"这件事是谁托付的"是 {@code owner} —— 所以派子 Agent 时用 {@link Host#spawnAs} 把主体换成创建者
 * （主人建的任务 = MASTER 语义，照旧全权；普通人建的任务按他自己的位判）。任务行里没有 {@code owner}
 * （老数据）或 {@code owner.qq == 0} 时回落到 {@code Caller.systemActor(...)}，<b>绝不回落到 MASTER</b>。</p>
 *
 * <p><b>零硬编码文案</b>：阈值与全部文本读自同目录 {@code prompt.md}（每次调用重读）；Java 里只有
 * op 名、kv 键名、工具名、日志前缀与 ASCII 兜底。</p>
 */
public class CronSkill implements Skill, Hooked {

    private static final String TOOL = "cron";
    private static final String NAME = "定时任务";
    private static final String OPS = "add / list / remove";
    private static final String PREFIX = "cron:t:";
    private static final String SEQ = "cron:seq";
    private static final int KV_SCAN = 500;
    /** 定时任务表落在通用 kv 表（C 类运行时数据）—— 资源判定用的库名。 */
    private static final String LIB_KV = "kv";

    // ==================================================================== 工具

    @Override
    public Object call(Args a, Host h) {
        Cfg c = Cfg.load(h);
        if (c.bad != null) return c.bad;
        if (h == null || h.store() == null) return "[" + TOOL + "] store unavailable";
        String op = Str.trim(a.str("op")).toLowerCase();
        if (op.isEmpty()) op = "list";
        try {
            if ("add".equals(op)) return add(a, h, c);
            if ("list".equals(op)) return list(a, h, c);
            if ("remove".equals(op)) return remove(a, h, c);
            return c.t("不支持op", "op", op, "ops", OPS);
        } catch (Throwable t) {
            log(h, "op=" + op + " 异常: " + t);
            return c.t("异常", "op", op, "error", String.valueOf(t));
        }
    }

    // ---------------------------------------------------------------- add

    private static String add(Args a, Host h, Cfg c) {
        // 资源判定（ACL）：建任务 = 写通用 kv 表（C 类 W 位；C 类对非主人只有 R（RWX 只有 SYSTEM 有），
        // 所以非主人写一律被位拦下 —— 建任务本身不是提权，"到点以谁的身份跑"由 owner 决定，见 on()）
        String deny = h.needDb(LIB_KV, 'W');
        if (deny != null) return deny;

        String raw = Str.trim(a.str("expr"));
        if (raw.isEmpty()) return a.missing("expr");
        String task = Str.trim(a.str("task"));
        if (task.isEmpty()) return a.missing("task");

        Expr e;
        try {
            e = Expr.parse(raw, c);
        } catch (IllegalArgumentException bad) {
            return c.t("表达式非法", "expr", raw, "reason", bad.getMessage());
        }
        long max = c.n("最多任务");
        if (max <= 0) return c.miss("最多任务");

        Store st = h.store();
        List<JsonObject> rows = tasks(st);
        if (rows.size() >= max) return c.t("任务太多", "limit", max);

        long id = num(st.kv(SEQ, "0"), 0L) + 1L;
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("expr", raw);
        row.addProperty("task", task);
        row.addProperty("tools", Str.trim(a.str("tools")));
        row.addProperty("ts", System.currentTimeMillis());
        row.addProperty("last_fired", 0L);
        row.add("owner", owner(h.caller()));
        // 到点这句话该投给谁：**建任务时的会话**（群 = group:<群号>、私聊 = qq:<QQ>、本地 = console）。
        // 取宿主自己的会话键（Host.session()：与 Turn / 分道 / 网关用的同一串），拿不到就退回调用者会话；
        // 两者都拿不到时存空串 = 没有目标 → 到点回落控制台（见 on()）。
        row.addProperty("session", sessionOf(h));
        st.kvSet(SEQ, String.valueOf(id));
        st.kvSet(key(id), J.json(row));
        log(h, "新增 #" + id + " " + raw + " → " + Str.cut(Str.oneLine(task), 80));
        return c.t("已建", "id", id, "expr", raw, "next", next(e, c), "task", Str.cut(Str.oneLine(task), 120));
    }

    // ---------------------------------------------------------------- list

    private static String list(Args a, Host h, Cfg c) {
        String deny = h.needDb(LIB_KV, 'R');           // 列任务 = 读通用 kv 表（C:R）
        if (deny != null) return deny;
        Caller me = h.caller();
        List<JsonObject> all = tasks(h.store());
        List<JsonObject> rows = mine(all, me);
        if (rows.isEmpty()) return c.t("列表空");

        long cap = c.n("列表上限");
        if (cap <= 0) return c.miss("列表上限");
        String scope = me != null && me.master()
                ? c.t("范围_全部")
                : Str.nz(me == null ? "" : me.session());
        StringBuilder sb = new StringBuilder();
        sb.append(c.t("列表头", "count", rows.size(), "scope", scope)).append('\n');
        int n = 0;
        for (JsonObject r : rows) {
            if (n >= cap) break;
            n++;
            long id = J.l(r, "id", 0L);
            String raw = J.s(r, "expr", "");
            Expr e = null;
            try {
                e = Expr.parse(raw, c);
            } catch (Throwable ignored) {
            }
            long last = J.l(r, "last_fired", 0L);
            String lastTxt = last > 0
                    ? c.t("最后触发", "time", stamp(last * 60000L, c))
                    : "";
            sb.append(c.t("列表项",
                    "id", id,
                    "expr", raw,
                    "next", e == null ? c.t("没有下次") : next(e, c),
                    "last", lastTxt,
                    "task", Str.cut(Str.oneLine(J.s(r, "task", "")), 120),
                    "tools", Str.blank(J.s(r, "tools", "")) ? scopeAll(c) : J.s(r, "tools", ""),
                    "owner", ownerText(r))).append('\n');
        }
        if (rows.size() > n) sb.append(c.t("列表还有", "rest", rows.size() - n));
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- remove

    private static String remove(Args a, Host h, Cfg c) {
        // 资源判定（ACL）：删任务 = 写通用 kv 表（C:W）
        String deny = h.needDb(LIB_KV, 'W');
        if (deny != null) return deny;

        Store st = h.store();
        Caller me = h.caller();
        List<JsonObject> all = tasks(st);
        if (a.b("all", false)) {
            int n = 0;
            for (JsonObject r : mine(all, me)) {
                if (st.kvDelete(key(J.l(r, "id", 0L)), false) > 0) n++;
            }
            return c.t("已清空", "count", n);
        }
        long id = a.l("id", 0L);
        if (id <= 0L) return a.missing("id");
        JsonObject row = byId(all, id);
        if (row == null) return c.t("没有这个任务", "id", id);
        if (!owned(row, me)) return c.t("只能删自己的", "id", id);
        if (st.kvDelete(key(id), false) <= 0) return c.t("没有这个任务", "id", id);
        log(h, "删除 #" + id);
        return c.t("已删", "id", id, "expr", J.s(row, "expr", ""));
    }

    // ==================================================================== 到点触发（on_timer）

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        if (!"on_timer".equals(hook) || h == null || h.store() == null) return;
        try {
            Cfg c = Cfg.load(h);
            if (c.bad != null) return;                       // 配置读不到：钩子不动作（工具侧会明确报缺键）
            Store st = h.store();
            List<JsonObject> rows = tasks(st);
            if (rows.isEmpty()) return;

            long now = System.currentTimeMillis();
            long minute = now / 60000L;
            LocalDateTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneId.systemDefault());
            for (JsonObject row : rows) {
                long id = J.l(row, "id", 0L);
                String raw = J.s(row, "expr", "");
                if (id <= 0L || Str.blank(raw)) continue;
                if (J.l(row, "last_fired", 0L) == minute) continue;      // 本分钟已触发过
                Expr e;
                try {
                    e = Expr.parse(raw, c);
                } catch (Throwable bad) {
                    continue;
                }
                if (!e.matches(t)) continue;

                // 先记 last_fired 再派发：tick 抖动/派发被挡都不会在同一分钟重复触发
                // （自主钩子 on_timer：主体 SYSTEM（C:RWX）→ 她自己的定时维护不被默认位误伤）
                String denyKv = h.needDb(LIB_KV, 'W');
                if (denyKv != null) {
                    log(h, "到点但写不了 kv：" + denyKv);
                    continue;
                }
                row.addProperty("last_fired", minute);
                st.kvSet(key(id), J.json(row));

                String text = fill(c.s("派发任务"), "expr", raw, "task", J.s(row, "task", ""));
                List<String> tools = split(J.s(row, "tools", ""));
                // 派发主体 = 任务的创建者（不是钩子主体 SYSTEM）：能力继承 —— 延迟执行不得比创建者权限更高。
                // owner 拿不到（老数据行没写 / qq=0）时回落到 SYSTEM，绝不回落到 MASTER。
                Caller subject = ownerActor(h, row);
                // 到点那句话的**落点** = 任务行里记的目标会话（建任务时那个群 / 私聊）——不记的话，
                // 钩子那轮的落点是控制台，"到点提醒"就说在了本机上（本缺陷）。交下来的只是"投给哪个会话"
                // 这个字符串，把它变成真落点是**基板的事**（Host.spawnAs 那条口 / Boot.sinkForDeliver）：
                // 老数据行没有这个键、或会话认不出来时，基板一律回落当轮落点（控制台），与修之前一致。
                String target = Str.trim(J.s(row, "session", ""));
                // reply=true：到点要说的那句话由宿主发出去（不外发内部汇报）
                JsonObject ticket = h.spawnAs(subject, text, tools, true, null, null, true, target);
                log(h, c.t("到点", "id", id, "expr", raw, "task", Str.cut(Str.oneLine(J.s(row, "task", "")), 80))
                        + " by=" + subject.kind() + " target=" + (Str.blank(target) ? "console" : target) + " → "
                        + J.s(ticket == null ? new JsonObject() : ticket, "status", "?"));
            }
        } catch (Throwable t) {
            log(h, "on_timer 处理异常：" + t);
        }
    }

    // ==================================================================== cron 表达式

    /** 一个已解析的 cron 表达式（分 时 日 月 周）。 */
    static final class Expr {

        private final boolean[] min = new boolean[60];
        private final boolean[] hour = new boolean[24];
        private final boolean[] dom = new boolean[32];
        private final boolean[] month = new boolean[13];
        private final boolean[] dow = new boolean[7];
        private final boolean domRestricted;
        private final boolean dowRestricted;

        private Expr(boolean domRestricted, boolean dowRestricted) {
            this.domRestricted = domRestricted;
            this.dowRestricted = dowRestricted;
        }

        static Expr parse(String raw, Cfg c) {
            String[] label = names(c);
            if (raw == null) throw new IllegalArgumentException(c.t("原因_段数", "n", 0));
            String[] f = raw.trim().split("\\s+");
            if (f.length != 5) throw new IllegalArgumentException(c.t("原因_段数", "n", f.length));
            Expr e = new Expr(!f[2].trim().startsWith("*"), !f[4].trim().startsWith("*"));
            field(f[0], 0, 59, e.min, label[0], c);
            field(f[1], 0, 23, e.hour, label[1], c);
            field(f[2], 1, 31, e.dom, label[2], c);
            field(f[3], 1, 12, e.month, label[3], c);
            // 周：0-7，7 与 0 都是周日
            boolean[] w = new boolean[8];
            field(f[4], 0, 7, w, label[4], c);
            for (int i = 0; i <= 6; i++) e.dow[i] = w[i];
            if (w[7]) e.dow[0] = true;
            return e;
        }

        /** 五个字段的显示名（读自 prompt.md 的 字段名）。 */
        private static String[] names(Cfg c) {
            String[] out = new String[5];
            String raw = c.s("字段名");
            if (raw != null) {
                String[] p = raw.split("[,，]");
                for (int i = 0; i < 5 && i < p.length; i++) out[i] = p[i].trim();
            }
            for (int i = 0; i < 5; i++) {
                if (out[i] == null || out[i].isEmpty()) out[i] = "?";
            }
            return out;
        }

        private static void field(String raw, int lo, int hi, boolean[] set, String cn, Cfg c) {
            String f = raw == null ? "" : raw.trim();
            if (f.isEmpty()) throw new IllegalArgumentException(c.t("原因_空段", "part", cn));
            for (String part0 : f.split(",")) {
                String part = part0.trim();
                if (part.isEmpty()) throw new IllegalArgumentException(c.t("原因_空段", "part", cn));
                int step = 1;
                int slash = part.indexOf('/');
                if (slash >= 0) {
                    String s = part.substring(slash + 1).trim();
                    try {
                        step = Integer.parseInt(s);
                    } catch (Exception bad) {
                        throw new IllegalArgumentException(c.t("原因_步长", "part", part));
                    }
                    if (step <= 0) throw new IllegalArgumentException(c.t("原因_步长", "part", part));
                    part = part.substring(0, slash).trim();
                }
                int a;
                int b;
                if (part.isEmpty() || "*".equals(part)) {
                    a = lo;
                    b = hi;
                } else {
                    int dash = part.indexOf('-');
                    try {
                        if (dash > 0) {
                            a = Integer.parseInt(part.substring(0, dash).trim());
                            b = Integer.parseInt(part.substring(dash + 1).trim());
                        } else {
                            a = Integer.parseInt(part);
                            b = slash >= 0 ? hi : a;
                        }
                    } catch (Exception bad) {
                        throw new IllegalArgumentException(c.t("原因_数字", "field", cn, "part", part));
                    }
                }
                if (a < lo || b > hi || a > b) {
                    throw new IllegalArgumentException(c.t("原因_范围", "field", cn, "part", part, "lo", lo, "hi", hi));
                }
                for (int i = a; i <= b; i += step) set[i] = true;
            }
        }

        boolean matches(LocalDateTime t) {
            if (!min[t.getMinute()]) return false;
            if (!hour[t.getHour()]) return false;
            if (!month[t.getMonthValue()]) return false;
            boolean day = dom[t.getDayOfMonth()];
            boolean week = dow[t.getDayOfWeek().getValue() % 7];
            if (domRestricted && dowRestricted) return day || week;   // cron 惯例：两者都限制时取「或」
            if (domRestricted) return day;
            if (dowRestricted) return week;
            return true;
        }
    }

    /** 下次触发时间（从下一分钟开始扫，扫不到返回文案）。 */
    private static String next(Expr e, Cfg c) {
        long scan = c.n("下次触发最多向前找分钟");
        if (scan <= 0) return c.miss("下次触发最多向前找分钟");
        LocalDateTime t = LocalDateTime.now().withSecond(0).withNano(0).plusMinutes(1);
        for (long i = 0; i < scan; i++) {
            if (e.matches(t)) return stamp(t, c);
            t = t.plusMinutes(1);
        }
        return c.t("没有下次");
    }

    private static String stamp(LocalDateTime t, Cfg c) {
        String pat = Str.trim(c.s("时间格式"));
        if (pat.isEmpty()) pat = "yyyy-MM-dd HH:mm";
        try {
            return DateTimeFormatter.ofPattern(pat).format(t);
        } catch (Throwable bad) {
            return t.toString();
        }
    }

    private static String stamp(long ms, Cfg c) {
        return stamp(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()), c);
    }

    // ==================================================================== kv 任务表

    private static String key(long id) { return PREFIX + id; }

    private static List<JsonObject> tasks(Store st) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (st == null) return out;
        for (JsonObject kv : st.kvList(PREFIX, KV_SCAN)) {
            JsonObject row = J.obj(J.s(kv, "v", ""));
            if (row == null) continue;
            if (J.l(row, "id", 0L) <= 0L) {
                String k = Str.nz(J.s(kv, "k", ""));
                if (!k.startsWith(PREFIX)) continue;
                long id = num(k.substring(PREFIX.length()), 0L);
                if (id <= 0L) continue;
                row.addProperty("id", id);
            }
            out.add(row);
        }
        Collections.sort(out, new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject x, JsonObject y) {
                long a = J.l(x, "id", 0L);
                long b = J.l(y, "id", 0L);
                return a == b ? 0 : (a < b ? -1 : 1);
            }
        });
        return out;
    }

    private static JsonObject byId(List<JsonObject> rows, long id) {
        for (JsonObject r : rows) if (J.l(r, "id", 0L) == id) return r;
        return null;
    }

    /** 该调用者能看到的任务（主人看全部，其余只看自己会话建的）。 */
    private static List<JsonObject> mine(List<JsonObject> all, Caller me) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (JsonObject r : all) if (owned(r, me)) out.add(r);
        return out;
    }

    private static boolean owned(JsonObject row, Caller me) {
        if (me == null) return false;
        if (me.master()) return true;
        String sess = Str.nz(J.s(J.sub(row, "owner"), "session", ""));
        return Str.has(sess) && sess.equals(me.session());
    }

    /**
     * 建任务时记下的<b>投递目标会话</b>（到点那句话该说给谁听）。
     *
     * <p>取的是<b>宿主自己的会话键</b> {@link Host#session()}（{@code console} / {@code qq:123} /
     * {@code group:456}）—— 它和基板 {@code Turn}、回合分道（{@code Lanes}）、网关回复用的
     * <b>是同一串</b>，所以"记下来的会话"与"投递时解析出来的落点"必然对得上（基板侧按同一串解析，
     * 见 {@code Boot.sinkForDeliver}）。宿主没有会话（{@code t == null} 的老宿主 / 探针）时退回
     * {@link Host#caller()} 的会话；两者都没有 → 空串（= 没有目标，到点回落控制台）。</p>
     */
    private static String sessionOf(Host h) {
        if (h == null) return "";
        String s = Str.trim(h.session());
        if (Str.has(s)) return s;
        Caller c = h.caller();
        return c == null ? "" : Str.trim(c.session());
    }

    /** 建任务时记的归属行（会话 + QQ + 群 + 主人标志）。 */
    private static JsonObject owner(Caller c) {
        JsonObject o = new JsonObject();
        if (c == null) return o;
        o.addProperty("session", c.session());
        o.addProperty("qq", c.qq());
        o.addProperty("group", c.groupId());
        o.addProperty("master", c.master());
        o.addProperty("name", c.name());
        return o;
    }

    /**
     * 到点派发时子 Agent 的<b>主体</b> = 任务的创建者（能力继承）。
     *
     * <p>口径：{@code owner.qq == 主人} → 主人语义（{@link Caller#console(long)} —— 他原来能用
     * {@code exec} 就还能用，不造成主人的功能损失）；其余 {@code owner.qq > 0} → {@link Caller#allUser(long)}
     * （到点按<b>他自己的位</b>判：{@code exec} / 写本机文件被拒，技能层的归属校验照旧兜底）；
     * 拿不到（没有 {@code owner} 行 / {@code qq == 0}）→ {@link Caller#systemActor(long)}，
     * <b>绝不回落到 MASTER</b>。</p>
     *
     * <p>会话按 owner 行里的 {@code group} 还原（群任务到点时仍在那个群的上下文里）：
     * <b>非主人</b>任务这一步得到的 {@code session()} 与创建时一致；
     * <b>主人</b>任务的主体是 {@link Caller#console(long)}（{@code entry=CONSOLE}），
     * 而 {@link Caller#session()} 先看 entry（{@link Caller} 里 {@code entry == CONSOLE → "console"}），
     * 所以那种情况下 {@code session()} 是 {@code "console"}、<b>不等于</b>创建时的 {@code group:<群号>}。
     * 这只影响子 Agent 的历史归属（{@code appendDialog} 记在哪个会话），
     * <b>投递落点不受影响</b>：落点取的是任务行顶层的 {@code session}（见 {@code on()}）。</p>
     */
    private static Caller ownerActor(Host h, JsonObject row) {
        long masterQQ = (h == null || h.conf() == null) ? 0L : h.conf().masterQQ();
        JsonObject o = J.sub(row, "owner");
        long qq = o == null ? 0L : J.l(o, "qq", 0L);
        Caller c = null;
        if (qq > 0L) c = (qq == masterQQ) ? Caller.console(masterQQ) : Caller.allUser(qq);
        if (c == null) return Caller.systemActor(masterQQ);          // 老数据行 / 没有归属：SYSTEM，不给 MASTER
        long group = o == null ? 0L : J.l(o, "group", 0L);
        return group > 0L ? c.inGroup(group, "") : c;
    }

    private static String ownerText(JsonObject row) {
        JsonObject o = J.sub(row, "owner");
        if (o == null) return "";
        String s = J.s(o, "session", "");
        String n = J.s(o, "name", "");
        if (Str.has(n) && !n.equals(s)) return s + "(" + n + ")";
        return s;
    }

    private static String scopeAll(Cfg c) {
        String s = c.s("全部工具");
        return s == null ? "" : s;
    }

    private static List<String> split(String v) {
        List<String> out = new ArrayList<String>();
        if (Str.blank(v)) return out;
        for (String p : v.split("[,，\\s]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static long num(String v, long def) {
        try {
            return Long.parseLong(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static String fill(String tpl, Object... kv) {
        if (tpl == null) return "";
        String s = tpl;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]));
        }
        return s;
    }

    private static void log(Host h, String msg) {
        try {
            if (h == null) return;
            Out o = h.out();
            if (o != null) o.dim("[" + NAME + "] " + msg);
        } catch (Throwable ignored) {
        }
    }

    // ==================================================================== prompt.md

    private static final class Cfg {

        private final Map<String, String> v = new LinkedHashMap<String, String>();
        private String bad;

        static Cfg load(Host h) {
            Cfg c = new Cfg();
            File dir = null;
            try {
                if (h != null) {
                    dir = h.skillDir();
                    if (dir == null && h.conf() != null) dir = new File(h.conf().skillsDir(), NAME);
                }
            } catch (Throwable ignored) {
            }
            String text = dir == null ? null : Fs.read(new File(dir, "prompt.md"), null);
            if (text == null) {
                c.bad = "[" + TOOL + "] prompt.md unreadable under "
                        + (dir == null ? "?" : dir.getAbsolutePath());
                return c;
            }
            for (String raw : text.split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int i = line.indexOf(':');
                int j = line.indexOf('：');
                if (i < 0 || (j >= 0 && j < i)) i = j;
                if (i <= 0) continue;
                String k = line.substring(0, i).trim();
                if (!k.isEmpty()) c.v.put(k, unesc(line.substring(i + 1).trim()));
            }
            return c;
        }

        String s(String k) { return v.get(k); }

        long n(String k) {
            String s = v.get(k);
            if (s == null) return -1L;
            try {
                long n = Long.parseLong(s.trim());
                return n > 0 ? n : -1L;
            } catch (Exception e) {
                return -1L;
            }
        }

        String miss(String k) { return "[" + TOOL + "] prompt.md missing config: " + k; }

        String t(String k, Object... kv) {
            String tpl = v.get(k);
            if (tpl == null) return "[" + TOOL + "] prompt.md missing text: " + k;
            return fill(tpl, kv);
        }

        private static String unesc(String s) {
            return s.replace("\\n", "\n").replace("\\t", "\t");
        }
    }
}
