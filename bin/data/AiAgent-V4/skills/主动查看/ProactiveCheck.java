import com.google.gson.JsonObject;

import java.io.File;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;
import sair.v4.store.Store;

/**
 * 主动查看（<b>纯 on_timer 钩子</b>，不提供工具）：按 {@code prompt.md} 的
 * {@code 监控群 / 间隔分钟 / 最少新消息 / 静默时段 / 冷却分钟} 判定"要不要主动看一眼某个群"。
 *
 * <p>数据全部来自基板六库的 {@code grouplog}（用 {@code h.store()} 查，不碰 NapCat）；
 * 游标与冷却记在 kv（{@code check:cursor:<群>} / {@code check:sweep:<群>} / {@code check:spoke:<群>}）。</p>
 *
 * <p><b>默认保守</b>：出厂 {@code 启用: false}、{@code 监控群} 为空 —— 一个字都不说；
 * 只有"新消息够多、没在静默、没过冷却、不在节流期"时才 {@code h.spawn(查看说明, 工具, true)}
 * 把"要不要接一句"交给主 Agent。**本技能从不直接发消息**。</p>
 *
 * <p><b>游标语义</b>：只有"看完了但没什么可说"才推进游标；被静默 / 冷却 / 节流挡住时不推进，
 * 消息攒着，等能说话的时候一起看，不会因为静默一小时就把这一小时的消息丢掉。</p>
 *
 * <p><b>零硬编码文案</b>：阈值与全部文本读自同目录 {@code prompt.md}（每次心跳重读）；Java 里只有
 * kv 键名、库名、日志前缀与 ASCII 兜底。</p>
 *
 * <h3>★ 她自己的标记行（P1-selfsee）：巡检统计不吃标记行</h3>
 * <p>基板 P0-1 之后 {@code grouplog} 里会多出她<b>自己发出去</b>的那一行「标记行」
 * （{@code content} 形如 {@code [我发的 图片 msg_id=<id>]}，{@code extra.self=true}）。
 * 它是<b>机器写的标记行 —— 不是群友发言</b>，所以本技能这样处置（判据只有 {@link #isSelfMark} 一处）：</p>
 * <ol>
 *   <li>{@code fresh} 活动统计（条数够不够 {@code 最少新消息}、{@code 关键词} 命中、以及
 *       {@code 查看说明} 里"最后发言的人"）：<b>① 整行跳过</b> —— 「这个群刚才动了一下」
 *       不许由她自己的标记行凑数（否则会出现"自己看自己说的"那种自激），
 *       也不许把 {@code [我发的 …]} 当成某人的话写进派工说明；</li>
 *   <li>{@link #maxId} 游标：<b>③ 照旧</b> —— 它只读 {@code id} 列当<b>记录水位线</b>
 *       （不读 {@code content}/{@code user_id}）；不推进游标会让她每个 tick 重扫同一批标记行。</li>
 * </ol>
 */
public class ProactiveCheck implements Hooked {

    private static final String NAME = "主动查看";
    private static final String LIB = "grouplog";
    private static final String K_CURSOR = "check:cursor:";
    private static final String K_SWEEP = "check:sweep:";
    private static final String K_SPOKE = "check:spoke:";
    private static final String K_LAST = "check:last:";
    /** 游标 / 节流 / 观测记录都落在通用 kv 表（C 类运行时数据）—— 资源判定用的库名。 */
    private static final String LIB_KV = "kv";
    private static final int MAX_FETCH = 200;

    // ==================================================================== ★ 她自己的标记行（唯一一处判据）

    /** 标记行在 {@code content} 上的<b>逐字</b>前缀（尾部的空格也算，判据不许过宽）。 */
    private static final String SELF_MARK_PREFIX = "[我发的 ";

    /**
     * ★ P1「她自己的标记行」判据 —— <b>本技能唯一一处</b>（不许在业务逻辑里散写
     * {@code if (content.startsWith(...))}）。
     *
     * <p>基板 P0-1 落地后，她<b>自己发出去</b>的消息/图会在 {@code grouplog} 里补一行标记行：
     * {@code content} = {@code [我发的 图片 msg_id=<id>]} 或 {@code [我发的 msg_id=<id>] <正文>}，
     * 并带 {@code extra.self=true} + {@code extra.action} + {@code extra.sent_id}。
     * <b>它是机器写的标记行 —— 不是群友发言</b>（本技能拿它当"这个群有没有人说话"的统计口径）。</p>
     *
     * <p>两条判据<b>都要</b>（只读消费面可能把 {@code extra} 丢掉）：</p>
     * <ol>
     *   <li>{@code extra.self == true}：{@code extra} 是 TEXT 列的 JSON 串（出参可能已被
     *       {@code Db.decode} 解析成对象；解析失败/缺失就当<b>没有它</b>）；</li>
     *   <li>兜底：{@code content} <b>逐字</b>以 {@link #SELF_MARK_PREFIX} 开头（含尾部空格）。</li>
     * </ol>
     *
     * <p><b>判据不许走样</b>：① 只能用 {@code startsWith} —— 不许 {@code contains("[我发的")}，
     * 也不许退化成「以 {@code [} 开头」：{@code [戳一戳]}/{@code [图片 …]}/{@code [撤回]} 那些是
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
     * 去掉标记行（{@link #isSelfMark}）。
     * <p>一个标记行都没有时<b>原样返回入参</b>（同一引用、同一顺序）—— 这就是「没有标记行 ⇒ 与改动前逐字等价」。</p>
     */
    static List<JsonObject> withoutSelfMarks(List<JsonObject> rows) {
        if (rows == null || rows.isEmpty()) return rows;
        boolean has = false;
        for (int i = 0; i < rows.size(); i++) {
            if (isSelfMark(rows.get(i))) {
                has = true;
                break;
            }
        }
        if (!has) return rows;
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            if (!isSelfMark(r)) out.add(r);
        }
        return out;
    }

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        if (!"on_timer".equals(hook) || h == null || h.store() == null) return;
        try {
            Cfg c = Cfg.load(h);
            if (c.bad != null) return;

            String enabled = c.s("启用");
            String groups = c.s("监控群");
            if (enabled == null || groups == null) {
                log(h, c.t("缺配置", "key", enabled == null ? "启用" : "监控群"));
                return;
            }
            List<Long> gids = longs(groups);
            if (gids.isEmpty()) return;                                  // 没点名任何群：彻底静默（出厂默认）
            if (!truthy(enabled)) {
                log(h, c.t("未启用", "enabled", Str.nz(enabled)));
                return;
            }

            long gapMin = c.n0("间隔分钟");
            long need = c.n("最少新消息");
            long cdMin = c.n0("冷却分钟");
            if (gapMin < 0 || need <= 0 || cdMin < 0) {
                log(h, c.t("缺配置", "key", need <= 0 ? "最少新消息" : (gapMin < 0 ? "间隔分钟" : "冷却分钟")));
                return;
            }

            long now = System.currentTimeMillis();
            String win = Str.trim(Str.nz(c.s("静默时段")));
            if (!win.isEmpty()) {
                Boolean silent = silent(win, now);
                if (silent == null) {
                    log(h, c.t("静默时段非法", "window", win));
                    return;
                }
                if (silent.booleanValue()) {
                    log(h, c.t("静默中", "window", win));
                    return;
                }
            }

            Set<String> keys = words(Str.nz(c.s("关键词")));
            List<String> tools = split(Str.nz(c.s("工具白名单")));
            Store st = h.store();

            // 资源判定（ACL）：自主钩子（on_timer）的主体是 SYSTEM（A:R B:RW C:RWX E:RWX）——
            // 读 grouplog（别人的聊天记录，C:R）+ 写 kv 游标（C:W）都放行：她自己的巡检不被默认位误伤。
            String denyRead = h.needDb(LIB, 'R');
            if (denyRead != null) {
                log(h, "grouplog 读不了：" + denyRead);
                return;
            }
            String denyWrite = h.needDb(LIB_KV, 'W');
            if (denyWrite != null) {
                log(h, "游标写不了：" + denyWrite);
                return;
            }

            for (Long gid : gids) {
                long g = gid.longValue();
                long spoke = num(st.kv(K_SPOKE + g, "0"), 0L);
                if (cdMin > 0 && spoke > 0 && now - spoke < cdMin * 60000L) {
                    log(h, c.t("冷却中", "group", g, "left", (cdMin * 60000L - (now - spoke)) / 60000L));
                    continue;
                }
                long sweep = num(st.kv(K_SWEEP + g, "0"), 0L);
                if (gapMin > 0 && sweep > 0 && now - sweep < gapMin * 60000L) {
                    log(h, c.t("间隔未到", "group", g, "gap", gapMin));
                    continue;
                }

                long maxId = maxId(st, g);
                if (maxId <= 0L) continue;                                  // 这个群没有任何记录
                long cursor = num(st.kv(K_CURSOR + g, "0"), 0L);
                if (cursor <= 0L) {
                    // 第一次看到这个群：只建游标，绝不插话（否则一上线就对着历史消息开口）
                    st.kvSet(K_CURSOR + g, String.valueOf(maxId));
                    st.kvSet(K_SWEEP + g, String.valueOf(now));
                    log(h, c.t("首次游标", "group", g, "cursor", maxId));
                    continue;
                }
                if (maxId <= cursor) {
                    st.kvSet(K_SWEEP + g, String.valueOf(now));
                    log(h, c.t("没有新消息", "group", g));
                    continue;
                }

                List<JsonObject> fresh = st.list(LIB, J.obj("group_id", g,
                        "_gte", J.obj("id", cursor + 1L)), MAX_FETCH, "id ASC");
                // ★ P1-selfsee：她自己的标记行整行跳过 —— 条数/关键词/最后发言人都只认真群友（① 整行跳过）
                fresh = withoutSelfMarks(fresh);
                int count = fresh.size();
                if (!keys.isEmpty()) {
                    List<JsonObject> hit = new ArrayList<JsonObject>();
                    for (JsonObject r : fresh) {
                        if (hitAny(keys, J.s(r, "content", "") + " " + J.s(r, "nickname", ""))) hit.add(r);
                    }
                    if (hit.isEmpty()) {
                        st.kvSet(K_CURSOR + g, String.valueOf(maxId));
                        st.kvSet(K_SWEEP + g, String.valueOf(now));
                        log(h, c.t("关键词未命中", "group", g, "cursor", maxId));
                        continue;
                    }
                    fresh = hit;
                    count = hit.size();
                }
                if (count < need) {
                    st.kvSet(K_CURSOR + g, String.valueOf(maxId));
                    st.kvSet(K_SWEEP + g, String.valueOf(now));
                    log(h, c.t("不达标", "group", g, "count", count, "need", need, "cursor", maxId));
                    continue;
                }

                JsonObject last = fresh.get(fresh.size() - 1);
                String task = fill(c.t("查看说明"),
                        "group", g,
                        "count", count,
                        "name", Str.nz(J.s(last, "nickname", "")),
                        "qq", J.l(last, "user_id", 0L),
                        "text", Str.cut(Str.oneLine(J.s(last, "content", "")), 160),
                        "time", stamp(now, c));
                // reply=true：想接就由宿主把这句话发出去；不想说（回 <silent>）就一个字都不发
        JsonObject ticket = h.spawn(task, tools, true, null, null, true);

                st.kvSet(K_CURSOR + g, String.valueOf(maxId));
                st.kvSet(K_SWEEP + g, String.valueOf(now));
                st.kvSet(K_SPOKE + g, String.valueOf(now));
                JsonObject rec = new JsonObject();
                rec.addProperty("ts", now);
                rec.addProperty("group", g);
                rec.addProperty("count", count);
                rec.addProperty("cursor", maxId);
                rec.addProperty("last", Str.cut(Str.oneLine(J.s(last, "content", "")), 160));
                rec.addProperty("spawn", J.s(ticket == null ? new JsonObject() : ticket, "status", "?"));
                st.kvSet(K_LAST + g, J.json(rec));
                log(h, c.t("决定开口", "group", g, "count", count,
                        "name", Str.nz(J.s(last, "nickname", "")),
                        "text", Str.cut(Str.oneLine(J.s(last, "content", "")), 60),
                        "status", J.s(ticket == null ? new JsonObject() : ticket, "status", "?")));
            }
        } catch (Throwable t) {
            log(h, "on_timer 处理异常：" + t);
        }
    }

    // ==================================================================== 辅助

    private static long maxId(Store st, long gid) {
        // ★ P1-selfsee：这里**不过**标记行谓词（③ 照旧）—— 它只读 id 列当记录水位线（不读 content），
        // 不推进游标会让她每个 tick 重扫同一批标记行；"谁在说话"的判断在 fresh 那一步做。
        List<JsonObject> rows = st.list(LIB, J.obj("group_id", gid), 1, "id DESC");
        return rows.isEmpty() ? 0L : J.l(rows.get(0), "id", 0L);
    }

    private static boolean hitAny(Set<String> keys, String text) {
        String low = Str.lower(text);
        for (String k : keys) if (low.indexOf(Str.lower(k)) >= 0) return true;
        return false;
    }

    /** 是否落在静默时段内；window 看不懂返回 null。 */
    private static Boolean silent(String window, long nowMs) {
        int dash = window.indexOf('-');
        if (dash <= 0) return null;
        Integer a = minutes(window.substring(0, dash).trim());
        Integer b = minutes(window.substring(dash + 1).trim());
        if (a == null || b == null) return null;
        LocalTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZoneId.systemDefault()).toLocalTime();
        int now = t.getHour() * 60 + t.getMinute();
        if (a.intValue() == b.intValue()) return Boolean.FALSE;
        if (a.intValue() < b.intValue()) return Boolean.valueOf(now >= a && now < b);
        return Boolean.valueOf(now >= a || now < b);                       // 跨零点
    }

    private static Integer minutes(String hhmm) {
        int c = hhmm.indexOf(':');
        if (c <= 0) return null;
        try {
            int h = Integer.parseInt(hhmm.substring(0, c).trim());
            int m = Integer.parseInt(hhmm.substring(c + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return Integer.valueOf(h * 60 + m);
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> words(String v) {
        Set<String> out = new HashSet<String>();
        if (Str.blank(v)) return out;
        for (String p : v.split("[,，\\s]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static List<Long> longs(String v) {
        List<Long> out = new ArrayList<Long>();
        for (String s : words(v)) {
            try {
                long n = Long.parseLong(s);
                if (n > 0L) out.add(Long.valueOf(n));
            } catch (Exception ignored) {
            }
        }
        return out;
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

    private static boolean truthy(String v) {
        String s = Str.trim(v).toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "是".equals(s);
    }

    private static String stamp(long ms, Cfg c) {
        String pat = Str.trim(c.s("时间格式"));
        if (pat.isEmpty()) pat = "yyyy-MM-dd HH:mm";
        try {
            return DateTimeFormatter.ofPattern(pat)
                    .format(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()));
        } catch (Throwable bad) {
            return String.valueOf(ms);
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
                c.bad = "[" + NAME + "] prompt.md unreadable under "
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
            long n = n0(k);
            return n > 0 ? n : -1L;
        }

        /** 允许 0 的数值（0 = 明确关掉该项）；缺键或非法返回 -1。 */
        long n0(String k) {
            String s = v.get(k);
            if (s == null) return -1L;
            try {
                long n = Long.parseLong(s.trim());
                return n >= 0 ? n : -1L;
            } catch (Exception e) {
                return -1L;
            }
        }

        String t(String k, Object... kv) {
            String tpl = v.get(k);
            if (tpl == null) return "[" + NAME + "] prompt.md missing text: " + k;
            return fill(tpl, kv);
        }

        private static String unesc(String s) {
            return s.replace("\\n", "\n").replace("\\t", "\t");
        }
    }
}
