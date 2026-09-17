import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Host;

/**
 * 「@提问者」的<b>状态层</b>：入站记账（环）、本轮触发（钉住）、过期清理。
 *
 * <h3>为什么要有这一层</h3>
 * <p>出站 stage 里<b>拿不到入站时刻与触发消息号</b>：{@code Outbound} 的字段只有
 * {@code text/session/group/target/caller/echo/index/total/host}（{@code ext\Outbound.java:30-118}），
 * 而 {@code BuildContext.state(...)} 在出站路径上 {@code turn == null}、恒返回兜底值。
 * 所以"时差"只能在<b>能看见入站事件的地方</b>记账，再由 stage 读回来算：</p>
 * <pre>
 *   on_message（看见原始事件）        → 入站环   atasker:in:&#60;群号&#62;
 *   onTurnStart（本轮真正的触发时刻） → 钉住     atasker:t:&#60;群号&#62;   ← stage 只读这一个
 *   OutboundStage.finish（发出前）    → 只读，按 dt 决定 @ 与引用
 * </pre>
 *
 * <h3>为什么不直接在 stage 里读"最近的入站"</h3>
 * <p>因为群是活的：A @ 了她之后、她开口之前，群里可能又来了几条别人的消息。stage 里
 * "最近一条入站"很可能<b>不是这一轮的触发消息</b>（那会算错时差，还会引用错人 —— V3 当年
 * 放弃引用回复就是怕这个）。所以在回合开始那一刻按"本轮输入"把它<b>钉住</b>，
 * stage 读的是钉住的那一条。</p>
 *
 * <h3>持久化与键名（不许靠 static：热重载会换类加载器，static 全丢）</h3>
 * <ul>
 *   <li>{@code atasker:in:<群号>} —— 本群最近 {@code 保留条数}（默认 5）条入站，JSON 数组，新的在后；</li>
 *   <li>{@code atasker:t:<群号>} —— 本轮钉住的触发（JSON 对象），每个群一条，覆盖写；</li>
 *   <li>{@code atasker:sweep} —— 上次清理的时刻（毫秒），只为限频；</li>
 *   <li>TTL：记录有效期 {@code 记录有效期秒}（默认 600）—— 读出时超期 = 当作"没有记录"；
 *       清理：{@code on_message} 里按 {@code 清理间隔秒}（默认 3600）限频扫一次
 *       {@code atasker:} 前缀，把"值里最新的 ts"早于 2×TTL（且至少 120 秒前）的行删掉。
 *       每群两个键 ⇒ 键数 = O(群数)，清理再兜住"曾经来过、再也不来的群"。</li>
 *   <li>权限：一律走 {@code Host.selfDb("kv", …)}（自主入口，判定主体钉死 SYSTEM）。出站 stage 里
 *       判定主体是<b>当轮调用者</b>（{@code ExtRegistry.java:780-793}），普通群友对 C 类库几乎
 *       全是空位，走 {@code h.needDb} 会被当场拒掉；读不到 = 当作"没有记录"，绝不猜。</li>
 * </ul>
 */
final class AtAskerMemo {

    /** 通用 kv 表（C 类运行时数据）—— ACL 判定用的库名。 */
    static final String LIB = "kv";
    /** 入站环的键前缀。 */
    static final String P_IN = "atasker:in:";
    /** 本轮钉住的触发的键前缀。 */
    static final String P_PIN = "atasker:t:";
    /** 清理限频键。 */
    static final String K_SWEEP = "atasker:sweep";
    /** 一次清理最多看多少行（防呆上限，不是设计容量）。 */
    static final int SWEEP_MAX = 200;

    private AtAskerMemo() {}

    /** 一条入站记录：判定需要的四个量 + 两段给人看的正文。 */
    static final class Rec {
        long ts;            // 真入站时刻（毫秒，钩子里取 System.currentTimeMillis()）
        long id;            // message_id（引用要用它）
        boolean at;         // 触发消息 @ 了她
        boolean master;     // 触发者是主人
        long qq;            // 触发者 QQ
        String text = "";    // 纯文本（截 40 字，只给"匹配本轮输入"和排障看）
        String via = "";     // 钉住时用的是哪条判据（match/addr/last，只给人看）

        Rec copy() {
            Rec r = new Rec();
            r.ts = ts;
            r.id = id;
            r.at = at;
            r.master = master;
            r.qq = qq;
            r.text = text;
            r.via = via;
            return r;
        }
    }

    // ==================== 读写（一律带自主入口判定） ====================

    /** 读一个键；判定不过 / 抛异常 → {@code null}（= 没有记录）。 */
    static String read(Host h, String key) {
        if (h == null || Str.blank(key)) return null;
        try {
            if (h.selfDb(LIB, 'R') != null) return null;
            if (h.store() == null) return null;
            String v = h.store().kv(key, null);
            return Str.blank(v) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写一个键；判定不过 / 抛异常 → false（调用方不需要知道细节）。 */
    static boolean write(Host h, String key, String val) {
        if (h == null || Str.blank(key)) return false;
        try {
            if (h.selfDb(LIB, 'W') != null) return false;
            if (h.store() == null) return false;
            h.store().kvSet(key, val);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 入站环 ====================

    /** 读本群入站环（新的在后）；超期条目丢掉。读不到返回空表。 */
    static List<Rec> ring(Host h, long gid, long now, long ttlMs) {
        List<Rec> out = new ArrayList<Rec>();
        if (gid <= 0L) return out;
        String v = read(h, P_IN + gid);
        if (v == null) return out;
        try {
            JsonArray a = J.arr(v);
            if (a == null) return out;
            for (int i = 0; i < a.size(); i++) {
                JsonElement e = a.get(i);
                if (e == null || !e.isJsonObject()) continue;
                Rec r = of(e.getAsJsonObject());
                if (r == null) continue;
                if (ttlMs > 0L && now - r.ts > ttlMs) continue;
                out.add(r);
            }
        } catch (Throwable t) {
            return out;
        }
        return out;
    }

    /**
     * 记一条入站：追加到环尾（同一 message_id 不重复追加），只保留最后 {@code keep} 条。
     *
     * @return 写入成功吗（false = 判定不过 / 没有库 / 异常，调用方不必管）
     */
    static boolean push(Host h, long gid, Rec r, int keep, long now, long ttlMs) {
        if (r == null || gid <= 0L) return false;
        int k = keep > 0 ? keep : 5;
        List<Rec> cur = ring(h, gid, now, ttlMs);
        if (!cur.isEmpty()) {
            Rec last = cur.get(cur.size() - 1);
            if (last.id > 0L && last.id == r.id) {
                cur.set(cur.size() - 1, r);            // 同一条重投：就地更新，不加长
            } else {
                cur.add(r);
            }
        } else {
            cur.add(r);
        }
        while (cur.size() > k) cur.remove(0);
        JsonArray a = new JsonArray();
        for (int i = 0; i < cur.size(); i++) a.add(json(cur.get(i)));
        return write(h, P_IN + gid, J.json(a));
    }

    // ==================== 钉住本轮触发 ====================

    /**
     * 在回合开始那一刻，从入站环里挑出"这一轮的触发消息"并钉住。
     *
     * <p>判据（从强到弱，全部只读结构信号）：</p>
     * <ol>
     *   <li><b>match</b>：某条入站的纯文本出现在<b>本轮输入</b>里（基板给模型的输入就是由触发消息
     *       派生出来的：群里被 @ 时是 {@code stripAt(正文)}，触发词命中时就是正文）⇒ 取<b>最新</b>命中的那条；</li>
     *   <li><b>addr</b>：没有命中（例如"只 @ 一下没说话"那一轮，输入是外挂模板，不含原文）⇒
     *       取最新的"被点名"（{@code at}）或主人的那条 —— 这两种消息按基板地址规则<b>必然</b>起一轮；</li>
     *   <li><b>last</b>：连"被点名"都没有（触发词/插件投票起来的那种）⇒ 取最新的一条。</li>
     * </ol>
     * <p>挑不到（环空 / 全超期）就<b>删掉旧钉子</b>，一个都不留 —— stage 那边把"没有钉子"当
     * 「不知道」，按旧口径处理（见 {@code AtAskerStage}）。</p>
     */
    static void pin(Host h, long gid, String prompt, long now, long ttlMs) {
        if (gid <= 0L) return;
        List<Rec> ring = ring(h, gid, now, ttlMs);
        Rec pick = pick(ring, prompt);
        if (pick == null) {
            write(h, P_PIN + gid, "");               // 空值 = 明确的"没有"
            return;
        }
        write(h, P_PIN + gid, J.json(json(pick)));
    }

    /** 纯挑选逻辑（给探针单独钉住用；不碰库）。 */
    static Rec pick(List<Rec> ring, String prompt) {
        if (ring == null || ring.isEmpty()) return null;
        String p = Str.nz(prompt);
        // ① match：最新的、正文出现在本轮输入里的那条
        for (int i = ring.size() - 1; i >= 0; i--) {
            Rec r = ring.get(i);
            if (r == null) continue;
            String t = Str.nz(r.text).trim();
            if (t.length() >= 2 && !p.isEmpty() && p.indexOf(t) >= 0) {
                Rec c = r.copy();
                c.via = "match";
                return c;
            }
        }
        // ② addr：最新的"被点名 / 主人"那条（这两种按地址规则必然起一轮）
        for (int i = ring.size() - 1; i >= 0; i--) {
            Rec r = ring.get(i);
            if (r == null) continue;
            if (r.at || r.master) {
                Rec c = r.copy();
                c.via = "addr";
                return c;
            }
        }
        // ③ last：最新的一条
        Rec r = ring.get(ring.size() - 1);
        if (r == null) return null;
        Rec c = r.copy();
        c.via = "last";
        return c;
    }

    /** 读本轮钉住的触发；没有 / 超期 / 判定不过 → {@code null}（stage 据此走"不知道"那条路）。 */
    static Rec pinned(Host h, long gid, long now, long ttlMs) {
        if (gid <= 0L) return null;
        String v = read(h, P_PIN + gid);
        if (v == null) return null;
        try {
            JsonObject o = J.obj(v);
            Rec r = of(o);
            if (r == null || r.ts <= 0L) return null;
            if (ttlMs > 0L && now - r.ts > ttlMs) return null;
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 清理（限频） ====================

    /**
     * 限频清理：把 {@code atasker:} 前缀下<b>内容已经很旧</b>的行删掉。
     *
     * <p>判据是那行值里<b>最新的 {@code ts}</b>（入站环取最后一条、钉住取它自己）——
     * 也就是"这条记录说的是多久以前的事"，而不是"这行上次被写是什么时候"（{@code updated}）：
     * 前者才是"过期"，后者只说明有人覆盖写过它。值里读不出 ts（坏值/空值）时回落到 {@code updated}。
     * 宽限 = {@code max(2×TTL, 120 秒)}，免得刚写下的钉子在下一轮就被扫掉。
     * 一次最多看 {@link #SWEEP_MAX} 行 —— 清理只是"别让库无限长"，不是任务。</p>
     *
     * @return 这次真扫了吗（false = 没到间隔 / 读不到 / 异常）
     */
    static boolean sweep(Host h, long now, long ttlMs, long intervalMs) {
        if (h == null) return false;
        try {
            long interval = intervalMs > 0L ? intervalMs : 3600000L;
            String last = read(h, K_SWEEP);
            if (last != null) {
                try {
                    if (now - Long.parseLong(Str.trim(last)) < interval) return false;
                } catch (Throwable ignored) {
                }
            }
            if (h.store() == null) return false;
            List<JsonObject> rows = h.store().kvList("atasker:", SWEEP_MAX);
            long grace = Math.max(ttlMs > 0L ? ttlMs * 2L : 1200000L, 120000L);
            if (rows != null) {
                for (JsonObject row : rows) {
                    if (row == null) continue;
                    String k = J.s(row, "k", "");
                    if (Str.blank(k) || K_SWEEP.equals(k)) continue;
                    long ts = newestTs(J.s(row, "v", ""));
                    if (ts <= 0L) ts = J.l(row, "updated", 0L);
                    if (ts > 0L && now - ts > grace) h.store().kvDelete(k, false);
                }
            }
            write(h, K_SWEEP, String.valueOf(now));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一行值里"最新的那个 ts"（入站环取最后一条、钉住取它自己）；认不出来返回 0。 */
    static long newestTs(String val) {
        if (Str.blank(val)) return 0L;
        try {
            JsonElement e = J.el(val);
            if (e == null || e.isJsonNull()) return 0L;
            if (e.isJsonArray()) {
                JsonArray a = e.getAsJsonArray();
                for (int i = a.size() - 1; i >= 0; i--) {
                    JsonElement x = a.get(i);
                    if (x != null && x.isJsonObject()) {
                        long t = J.l(x.getAsJsonObject(), "ts", 0L);
                        if (t > 0L) return t;
                    }
                }
                return 0L;
            }
            if (e.isJsonObject()) return J.l(e.getAsJsonObject(), "ts", 0L);
        } catch (Throwable t) {
            return 0L;
        }
        return 0L;
    }

    // ==================== JSON ====================

    static JsonObject json(Rec r) {
        JsonObject o = new JsonObject();
        if (r == null) return o;
        o.addProperty("ts", r.ts);
        o.addProperty("id", r.id);
        o.addProperty("at", r.at ? 1 : 0);
        o.addProperty("m", r.master ? 1 : 0);
        o.addProperty("qq", r.qq);
        o.addProperty("tx", Str.nz(r.text));
        if (!Str.blank(r.via)) o.addProperty("via", r.via);
        return o;
    }

    static Rec of(JsonObject o) {
        if (o == null) return null;
        Rec r = new Rec();
        r.ts = J.l(o, "ts", 0L);
        r.id = J.l(o, "id", 0L);
        r.at = J.i(o, "at", 0) > 0;
        r.master = J.i(o, "m", 0) > 0;
        r.qq = J.l(o, "qq", 0L);
        r.text = J.s(o, "tx", "");
        r.via = J.s(o, "via", "");
        return r;
    }
}
