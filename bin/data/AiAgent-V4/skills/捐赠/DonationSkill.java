import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import sair.v4.store.Store;

/**
 * 捐赠（工具 {@code donation}）：一本小小的捐赠台账 —— 谁捐了多少、一共多少。
 *
 * <p>落在六库的 {@code pref} 库（key/value 形态）：<br>
 * ① 每人一行：{@code key = donation:<qq>}，{@code value} = 累计金额，{@code importance} = 笔数；<br>
 * ② 一行总计：{@code key = donation:total}。</p>
 *
 * <p>三个 op：{@code add}（记一笔，累加到人 + 累加到总计）、{@code list}（榜 + 总计）、
 * {@code reset}（清空台账）。金额排序在 Java 侧按数值做
 * （{@code value} 是文本列，直接 ORDER BY 会按字典序排）。文案/键名/阈值全在 {@code prompt.md}。</p>
 *
 * <p><b>归属口径（与授权无关）</b>：{@code MASTER}/{@code SYSTEM} 照常；
 * 受限的只有 {@code ALLUSER} —— ① <b>给别人记账</b>一律拒（见 {@link #add}）；
 * ② <b>清空台账</b>一律拒（见 {@link #reset}）。给自己记账不受影响。
 * 这两个是<b>业务归属硬判</b>，与技能管控表（op）各管一段：op 管"这个身份能不能用这个动作"，
 * 归属硬判管"这个动作能不能碰这个人的数据"。</p>
 */
public class DonationSkill implements Skill {

    /** 技能名（= 文件夹名 = md 名），也是日志前缀。 */
    private static final String NAME = "捐赠";
    /** 六库里的偏好设定库（key/value 形态，正好放台账）。 */
    private static final String LIB = "pref";
    /** 作用域：捐赠是全机器人一本账。 */
    private static final String SCOPE = "global";
    /** 键前缀 / 总计键的结构性兜底（真正生效的值在 prompt.md）。 */
    private static final String PREFIX_DEF = "donation:";
    private static final String TOTAL_DEF = "donation:total";
    /**
     * 归属阻断的固定前缀。<b>必须由代码保证</b>（探针按这个前缀断言），不能走 prompt.md。
     */
    private static final String DENY = "[权限阻断] ";

    // ================================================================ 工具入口

    /**
     * 声明本技能的三个 op（技能管控表就是按这些名字放行或封禁的）。
     */
    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        ops.add("donation", "add", "记一笔捐赠");
        ops.add("donation", "list", "看捐赠榜与总计");
        ops.add("donation", "reset", "清空捐赠台账");
    }

    @Override
    public Object call(Args a, Host h) {
        Cfg c = load(h);
        Store s = h == null ? null : h.store();
        if (s == null) return msg(c, "文案-无库", "store unavailable");
        String op = Str.trim(a.str("op")).toLowerCase();
        if (op.isEmpty()) op = "list";
        try {
            if ("add".equals(op)) return add(a, h, s, c);
            if ("list".equals(op)) return list(a, h, s, c);
            if ("reset".equals(op)) return reset(a, h, s, c);
            return msg(c, "文案-未知op", "unsupported op={op}", "{op}", op);
        } catch (Throwable t) {
            log(h, "op=" + op + " error: " + t);
            return msg(c, "文案-执行异常", "error: {error}", "{error}", String.valueOf(t));
        }
    }

    // ================================================================ add

    /** 记一笔：给某人累加金额与笔数，同时累加总计。 */
    private static String add(Args a, Host h, Store s, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("donation.add");
        if (deny != null) return deny;
        long caller = qq(h);
        long target = a.l("qq", 0L);
        if (target <= 0L) target = caller;
        if (target <= 0L) return a.missing("qq");
        if (target != caller && restricted(h)) {
            // 归属硬判：给别人记账 = 改别人的账目 —— 非主人（ALLUSER）一律拒。
            // 这一条是**业务判断**（改的是不是别人的账），与 op 判定各管一段，照旧保留。
            // 给自己记账不受影响（target == caller 走不到这里）。
            return DENY + "只能给自己记账（目标 " + target + " 不是你自己）。";
        }
        String raw = Str.trim(a.str("amount"));
        if (raw.isEmpty()) return a.missing("amount");
        double amount = dec(raw, -1.0D);
        double min = dec(P(c, "最低金额", "0.01"), 0.01D);
        double max = dec(P(c, "单笔上限", "1000000"), 1000000.0D);
        if (amount <= 0.0D) return msg(c, "文案-金额非法", "bad amount={amount}", "{amount}", raw);
        if (amount < min) {
            return msg(c, "文案-金额太小", "amount too small min={min}",
                    "{min}", fmt(min), "{amount}", fmt(amount));
        }
        if (amount > max) {
            return msg(c, "文案-金额太大", "amount too large max={max}",
                    "{max}", fmt(max), "{amount}", fmt(amount));
        }
        String note = cut(Str.trim(a.str("note")), atLeast(P(c, "备注长度上限", "120"), 4, 1000));
        String nick = cut(Str.trim(a.str("name")), atLeast(P(c, "昵称长度上限", "40"), 2, 200));

        String pkey = P(c, "键前缀", PREFIX_DEF) + target;
        String tkey = P(c, "总计键", TOTAL_DEF);
        long now = System.currentTimeMillis();

        JsonObject person = find(s, pkey);
        double pv = person == null ? 0.0D : dec(J.s(person, "value", "0"), 0.0D);
        int pn = person == null ? 0 : J.i(person, "importance", 0);
        double nv = pv + amount;
        JsonObject pex = person == null ? new JsonObject() : J.sub(person, "extra");
        if (pex == null) pex = new JsonObject();
        pex.addProperty("qq", target);
        if (Str.has(nick)) pex.addProperty("name", nick);
        else if (!Str.has(J.s(pex, "name", "")) && Str.has(name(h))) pex.addProperty("name", name(h));
        pex.addProperty("last", now);
        pex.addProperty("last_amount", fmt(amount));
        if (Str.has(note)) pex.addProperty("last_note", note);

        JsonObject prow = new JsonObject();
        prow.addProperty("scope", SCOPE);
        prow.addProperty("scope_id", "");
        prow.addProperty("key", pkey);
        prow.addProperty("value", fmt(nv));
        prow.addProperty("importance", pn + 1);
        prow.add("extra", pex);
        if (person != null) prow.addProperty("id", J.l(person, "id", 0L));
        long pid = s.put(LIB, prow);
        if (pid <= 0L) return msg(c, "文案-写入失败", "store put failed");

        JsonObject total = find(s, tkey);
        double tv = total == null ? 0.0D : dec(J.s(total, "value", "0"), 0.0D);
        int tn = total == null ? 0 : J.i(total, "importance", 0);
        JsonObject tex = total == null ? new JsonObject() : J.sub(total, "extra");
        if (tex == null) tex = new JsonObject();
        tex.addProperty("last", now);
        JsonObject trow = new JsonObject();
        trow.addProperty("scope", SCOPE);
        trow.addProperty("scope_id", "");
        trow.addProperty("key", tkey);
        trow.addProperty("value", fmt(tv + amount));
        trow.addProperty("importance", tn + 1);
        trow.add("extra", tex);
        if (total != null) trow.addProperty("id", J.l(total, "id", 0L));
        s.put(LIB, trow);

        SimpleDateFormat df = new SimpleDateFormat(P(c, "时间格式", "yyyy-MM-dd HH:mm"));
        return msg(c, "文案-已记账", "donation #{id} qq={qq} amount={amount} sum={sum}",
                "{id}", String.valueOf(pid),
                "{qq}", String.valueOf(target),
                "{amount}", fmt(amount),
                "{sum}", fmt(nv),
                "{total}", fmt(tv + amount),
                "{count}", String.valueOf(pn + 1),
                "{time}", df.format(new Date(now)),
                "{note}", note);
    }

    // ================================================================ list

    /** 榜：按累计金额从高到低（Java 侧按数值排），末尾给一行总计。 */
    private static String list(Args a, Host h, Store s, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("donation.list");
        if (deny != null) return deny;
        int limit = a.i("limit", atLeast(P(c, "榜单条数", "10"), 1, 200));
        if (limit <= 0) limit = atLeast(P(c, "榜单条数", "10"), 1, 200);
        String pkey = P(c, "键前缀", PREFIX_DEF);
        String tkey = P(c, "总计键", TOTAL_DEF);
        List<JsonObject> all = s.list(LIB, J.obj("_like", J.obj("key", pkey)), atLeast(P(c, "取样条数", "500"), 10, 5000), null);
        String total = "0";
        int totalCount = 0;
        List<JsonObject> people = new ArrayList<JsonObject>();
        for (JsonObject row : all) {
            String k = J.s(row, "key", "");
            if (tkey.equals(k)) {
                total = Str.nz(J.s(row, "value", "0"));
                totalCount = J.i(row, "importance", 0);
                continue;
            }
            people.add(row);
        }
        Collections.sort(people, new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject x, JsonObject y) {
                double dx = dec(J.s(x, "value", "0"), 0.0D);
                double dy = dec(J.s(y, "value", "0"), 0.0D);
                if (dx != dy) return dx > dy ? -1 : 1;
                return 0;
            }
        });
        if (people.isEmpty()) return msg(c, "文案-无记录", "no_donations");
        SimpleDateFormat df = new SimpleDateFormat(P(c, "时间格式", "yyyy-MM-dd HH:mm"));
        StringBuilder sb = new StringBuilder();
        sb.append(msg(c, "文案-榜单头", "donations={n} total={total}",
                "{n}", String.valueOf(people.size()), "{total}", total,
                "{count}", String.valueOf(totalCount))).append('\n');
        int i = 0;
        for (JsonObject row : people) {
            i++;
            if (i > limit) break;
            JsonObject ex = J.sub(row, "extra");
            sb.append(msg(c, "文案-榜行", "{i}. {name}({qq}) {value}",
                    "{i}", String.valueOf(i),
                    "{qq}", String.valueOf(J.l(ex, "qq", 0L)),
                    "{name}", Str.nz(J.s(ex, "name", "")),
                    "{value}", Str.nz(J.s(row, "value", "0")),
                    "{count}", String.valueOf(J.i(row, "importance", 0)),
                    "{time}", df.format(new Date(J.l(ex, "last", 0L))))).append('\n');
        }
        if (people.size() > limit) {
            sb.append(msg(c, "文案-还有更多", "more={more}",
                    "{more}", String.valueOf(people.size() - limit))).append('\n');
        }
        sb.append(msg(c, "文案-总计行", "total={total}", "{total}", total,
                "{count}", String.valueOf(totalCount)));
        return sb.toString().trim();
    }

    // ================================================================ reset

    /**
     * 清空台账（含总计）：<b>归属硬判 = 非主人（{@code ALLUSER}）一律拒</b>。
     *
     * <p>为什么必须有这一条：技能管控表管的是"这个身份能不能用 {@code donation.reset} 这个 op"，
     * 它说不了"这本台账是不是你的" —— 台账是全机器人一本账、行里没有归属列，
     * 所以除了 op 判定之外还必须有这道归属硬判，否则任何被放行过 {@code donation.reset}
     * 的 {@code ALLUSER} 都能清空整本捐赠台账。</p>
     *
     * <p>口径与本批其它技能一致：{@code MASTER}（主人）与 {@code SYSTEM}（她自己的自主行为）
     * 照常放行 —— 拿不到身份（{@code caller == null}）时按受限处理（fail-closed）。
     * 若将来要收紧成"连她自己的自主行为也不能清"，把这一行换成 {@code if (!master(h))} 即可。</p>
     *
     * <p><b>拒绝文案必须如实</b>：现在 {@code SYSTEM} 确实也能清，所以文案说的是
     * "主人或她本人（MASTER/SYSTEM）"，<b>不许写"仅主人"</b>（那是假话 —— 探针按这一点断言）。</p>
     */
    private static String reset(Args a, Host h, Store s, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("donation.reset");
        if (deny != null) return deny;
        if (restricted(h)) return DENY + "清空捐赠台账只能由主人或她本人（MASTER/SYSTEM）做。";
        String pkey = P(c, "键前缀", PREFIX_DEF);
        List<JsonObject> all = s.list(LIB, J.obj("_like", J.obj("key", pkey)), atLeast(P(c, "取样条数", "500"), 10, 5000), null);
        int n = 0;
        long sum = 0L;
        double money = 0.0D;
        for (JsonObject row : all) {
            if (s.delete(LIB, J.l(row, "id", 0L))) {
                n++;
                money += dec(J.s(row, "value", "0"), 0.0D);
            }
        }
        sum = (long) money;
        return msg(c, "文案-已重置", "reset rows={rows}",
                "{rows}", String.valueOf(n), "{total}", fmt(money), "{sum}", String.valueOf(sum));
    }

    // ================================================================ 读写小工具

    /** 按 key 精确取一行（pref 库的 key 列可以直接等值过滤）。 */
    private static JsonObject find(Store s, String key) {
        List<JsonObject> rows = s.list(LIB, J.obj("key", key), 1, null);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
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
     * 受限主体判定：受限的只有 {@code ALLUSER}（普通用户）。
     * <p>{@code MASTER} / {@code SYSTEM}（她自己的自主行为）照常放行；身份取不到时按受限处理（fail-closed）。
     * 这是 <b>业务归属硬判</b>（改的是不是别人的账 / 能不能清账），与 op 判定各管一段。</p>
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

    /** 金额显示：整数不带小数点，小数最多两位、不留多余的 0。 */
    private static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 9.0E15D) return String.valueOf((long) v);
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static double dec(String v, double def) {
        try {
            return Double.parseDouble(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static long qq(Host h) {
        try {
            return h == null || h.caller() == null ? 0L : h.caller().qq();
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
