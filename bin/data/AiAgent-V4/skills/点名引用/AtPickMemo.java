import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Host;

/**
 * 「点名引用」的状态面：<b>本群最近几条入站</b>（谁、几号、说了什么、什么时候）。
 *
 * <p>为什么必须自己记：她的聊天窗口里一行是 {@code [时间] 谁: 说了什么} ——
 * <b>没有 message_id，也没有 QQ</b>（见 {@code ctx\ChatWindow.java}）。要让她自己决定
 * "@ 谁 / 引用哪条"，就得把这个清单交到她眼前；清单只能从入站事件那里记下来。</p>
 *
 * <ul>
 *   <li>键 {@code pick:in:<群号>}：JSON 数组，新的在后，最多 {@code 清单条数} 条；</li>
 *   <li>TTL：超过 {@code 记录有效期秒} 的条目在读出时就丢掉（不返回）；</li>
 *   <li>键 {@code pick:sweep}：上次清理时刻，只为限频。</li>
 * </ul>
 *
 * <p><b>不许靠 static</b>：热重载 = 新类加载器 + 新静态字段，static 里的东西会成孤儿，
 * 所以状态只写库。{@code Host} / 库不可用时全部吞异常返回 false —— 记不上最多是清单里少几条，
 * <b>绝不能拖垮一条入站</b>。</p>
 */
final class AtPickMemo {

    private AtPickMemo() {}

    /** 入站环的键前缀。 */
    static final String P_IN = "pick:in:";
    /** 清理记账的键。 */
    static final String K_SWEEP = "pick:sweep";
    /** 一次清理最多看多少行（清理只是"别让库无限长"，不是任务）。 */
    private static final int SWEEP_MAX = 200;

    /** 一条入站记录。 */
    static final class Rec {
        long ts;
        long id;
        long qq;
        String name = "";
        String text = "";
    }

    // ==================== 读写 ====================

    static String read(Host h, String key) {
        try {
            if (h == null || h.store() == null) return null;
            return h.store().kv(key, null);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean write(Host h, String key, String val) {
        try {
            if (h == null || h.store() == null) return false;
            h.store().kvSet(key, val);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读出本群入站环（新的在后，已按 TTL 丢掉过期条目）；没有/读不出 ⇒ 空表。 */
    static List<Rec> ring(Host h, long gid, long now, long ttlMs) {
        List<Rec> out = new ArrayList<Rec>();
        if (gid <= 0L) return out;
        String v = read(h, P_IN + gid);
        if (Str.blank(v)) return out;
        try {
            JsonElement e = J.el(v);
            if (e == null || !e.isJsonArray()) return out;
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                JsonElement x = a.get(i);
                if (x == null || !x.isJsonObject()) continue;
                Rec r = of(x.getAsJsonObject());
                if (r == null || r.ts <= 0L) continue;
                if (ttlMs > 0L && now - r.ts > ttlMs) continue;
                out.add(r);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 追加一条入站（同 {@code message_id} 先删旧的：重连重放会重复入库），
     * 并裁到 {@code keep} 条。
     */
    static boolean push(Host h, long gid, Rec r, int keep, long now, long ttlMs) {
        if (h == null || gid <= 0L || r == null) return false;
        int max = keep < 1 ? 1 : (keep > 30 ? 30 : keep);
        List<Rec> list = ring(h, gid, now, ttlMs);
        if (r.id > 0L) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Rec x = list.get(i);
                if (x != null && x.id == r.id) list.remove(i);
            }
        }
        list.add(r);
        while (list.size() > max) list.remove(0);
        JsonArray a = new JsonArray();
        for (int i = 0; i < list.size(); i++) a.add(json(list.get(i)));
        return write(h, P_IN + gid, a.toString());
    }

    // ==================== 清理（限频） ====================

    /**
     * 把 {@code pick:} 前缀下<b>内容已经很旧</b>的行删掉。
     * 判据是那行值里最新的 {@code ts}（"这条记录说的是多久以前的事"），不是 {@code updated}。
     * 宽限 = {@code max(2×TTL, 120 秒)}。
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
            List<JsonObject> rows = h.store().kvList("pick:", SWEEP_MAX);
            long grace = Math.max(ttlMs > 0L ? ttlMs * 2L : 1200000L, 120000L);
            if (rows != null) {
                for (int i = 0; i < rows.size(); i++) {
                    JsonObject row = rows.get(i);
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

    /** 一行值里"最新的那个 ts"（入站环取最后一条）；认不出来返回 0。 */
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
        o.addProperty("qq", r.qq);
        o.addProperty("name", Str.nz(r.name));
        o.addProperty("tx", Str.nz(r.text));
        return o;
    }

    static Rec of(JsonObject o) {
        if (o == null) return null;
        Rec r = new Rec();
        r.ts = J.l(o, "ts", 0L);
        r.id = J.l(o, "id", 0L);
        r.qq = J.l(o, "qq", 0L);
        r.name = J.s(o, "name", "");
        r.text = J.s(o, "tx", "");
        return r;
    }
}
