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
import sair.v4.store.Store;

/**
 * 修正记录（工具 {@code correct}）：把"AI 说错了 → 正确的是什么 → 为什么"记成一条<b>纠错条目</b>，
 * 落在六库的 {@code note} 库（{@code source=修正记录}、{@code tags} 含纠错标签）。
 *
 * <p>两个 op：<br>
 * ① {@code add}：记一条纠正。原话 / 纠正后 必填，原因可选；同一个"原话 + 纠正后"只留一条
 * （命中就累加 {@code extra.hits}，绝不重复堆）。<br>
 * ② {@code list}：查最近 / 按关键词查。</p>
 *
 * <p>零硬编码文案：条目正文模板、标签、条数上限、标题字数、查重最短长度、全部文案都在
 * 技能自己的 {@code prompt.md}（{@code ## 配置} / {@code ## 内容模板} / {@code ## 文案}）里，
 * 运行期读取、改了即生效；Java 里只有结构性的 ASCII 兜底。</p>
 *
 * <p><b>归属口径（ACL 之后的新口径 —— 与权限位无关）</b>：{@code note} 表是<b>她的共享知识库</b>
 * （行里没有"这是谁的"这一列，{@code source} 只标是哪支技能写的），所以 {@code add}（含命中重复时的
 * {@code update} 追加正文）对非主人（{@code ALLUSER}）<b>一律拒</b>；{@code MASTER} 与她自己的自主行为
 * （{@code SYSTEM}）照常。判的是调用者身份，不是 {@code h.needDb("note",'W')} ——
 * 后者是 C 类资源，C 类对非主人只有 {@code R} ⇒ 把非主人写一律拦下；归属硬判仍在。</p>
 */
public class CorrectSkill implements Skill {

    /** 技能名（= 文件夹名 = md 名），同时是 note 行 source 列的值。 */
    private static final String NAME = "修正记录";
    /** 六库里的知识笔记库（有 title/content/tags/source/extra）。 */
    private static final String LIB = "note";
    /** 纠错标签的结构性兜底（真正生效的值在 prompt.md 的「纠错标签」，默认是「纠错」）。 */
    private static final String TAG_DEF = "correction";
    /**
     * 归属阻断的固定前缀（探针按它断言）。<b>必须由代码保证</b>，不走 prompt.md。
     */
    private static final String DENY = "[权限阻断] ";

    // ================================================================ 工具入口

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
            return msg(c, "文案-未知op", "unsupported op={op}", "{op}", op);
        } catch (Throwable t) {
            log(h, "op=" + op + " error: " + t);
            return msg(c, "文案-执行异常", "error: {error}", "{error}", String.valueOf(t));
        }
    }

    // ================================================================ add

    /** 记一条纠正：先查重（同一「原话 + 纠正后」只留一条），再落 note 库。 */
    private static String add(Args a, Host h, Store s, Cfg c) {
        // 归属硬判（唯一覆盖 put 与 update 两道写的地方）：note 是她的共享知识库、行里没有归属列
        // ⇒ 非主人（ALLUSER）一律拒。命中重复时走的 bump() → s.update(LIB, id, …) 同样被这一条挡住。
        String own = noteWriteGuard(h);
        if (own != null) return own;
        String deny = check(h, 'W');
        if (deny != null) return deny;
        String original = Str.trim(a.str("original"));
        if (original.isEmpty()) return a.missing("original");
        String corrected = Str.trim(a.str("corrected"));
        if (corrected.isEmpty()) return a.missing("corrected");
        String reason = Str.trim(a.str("reason"));
        String title = Str.trim(a.str("title"));
        if (title.isEmpty()) title = Str.cut(Str.oneLine(corrected), atLeast(P(c, "标题字数", "24"), 4, 120));
        String tags = tags(c, a.str("tags"));

        JsonObject same = findDup(s, c, original, corrected);
        if (same != null) {
            long id = J.l(same, "id", 0L);
            int hits = bump(s, same, original, corrected, reason);
            return msg(c, "文案-重复", "dup #{id} hits={hits}",
                    "{id}", String.valueOf(id), "{hits}", String.valueOf(hits));
        }

        JsonObject row = new JsonObject();
        row.addProperty("title", title);
        row.addProperty("content", body(c, h, original, corrected, reason));
        row.addProperty("tags", tags);
        row.addProperty("source", NAME);
        row.add("extra", extra(original, corrected, reason, 1, qq(h), name(h)));
        long id = s.put(LIB, row);
        if (id <= 0L) return msg(c, "文案-写入失败", "store put failed");
        return msg(c, "文案-已记录", "recorded #{id}",
                "{id}", String.valueOf(id), "{title}", title,
                "{original}", Str.cut(Str.oneLine(original), 60),
                "{corrected}", Str.cut(Str.oneLine(corrected), 60),
                "{tags}", tags);
    }

    /** 查重：同一原话，或同一纠正后（归一化后相等 / 短的一边被长的一边包含且够长）。 */
    private static JsonObject findDup(Store s, Cfg c, String original, String corrected) {
        int min = atLeast(P(c, "查重最短长度", "6"), 2, 200);
        String o = norm(original);
        String r = norm(corrected);
        List<JsonObject> rows = rows(s, NAME, atLeast(P(c, "查重取样条数", "200"), 10, 1000));
        for (JsonObject row : rows) {
            JsonObject ex = J.sub(row, "extra");
            String ro = norm(ex == null ? "" : J.s(ex, "original", ""));
            String rr = norm(ex == null ? "" : J.s(ex, "corrected", ""));
            if (!o.isEmpty() && o.equals(ro)) return row;
            if (!r.isEmpty() && r.equals(rr)) return row;
            if (near(o, ro, min) || near(r, rr, min)) return row;
        }
        return null;
    }

    /** 命中已有条目：hits+1；原来没写原因的补上原因（正文一起刷新）。 */
    private static int bump(Store s, JsonObject row, String original, String corrected, String reason) {
        long id = J.l(row, "id", 0L);
        JsonObject ex = J.sub(row, "extra");
        if (ex == null) ex = new JsonObject();
        int hits = J.i(ex, "hits", 0) + 1;
        ex.addProperty("hits", hits);
        ex.addProperty("original", original);
        ex.addProperty("corrected", corrected);
        boolean hasReason = Str.has(J.s(ex, "reason", ""));
        if (Str.has(reason) && !hasReason) ex.addProperty("reason", reason);
        JsonObject patch = new JsonObject();
        patch.add("extra", ex);
        if (Str.has(reason) && !hasReason) {
            row.addProperty("content", Str.nz(J.s(row, "content", "")) + "\n" + reasonLine(null, reason));
            patch.addProperty("content", J.s(row, "content", ""));
        }
        s.update(LIB, id, patch);
        return hits;
    }

    // ================================================================ list

    /** 查纠错条目：不给关键词就按时间倒序，给了就走六库检索（再在 Java 侧按 source 过滤）。 */
    private static String list(Args a, Host h, Store s, Cfg c) {
        String deny = check(h, 'R');
        if (deny != null) return deny;
        int limit = a.i("limit", atLeast(P(c, "列表条数", "10"), 1, 200));
        if (limit <= 0) limit = atLeast(P(c, "列表条数", "10"), 1, 200);
        String q = Str.trim(a.str("query"));
        List<JsonObject> rows;
        if (q.isEmpty()) {
            rows = rows(s, NAME, limit);
        } else {
            rows = pick(s.search(LIB, q, limit * 4), limit);
        }
        if (rows.isEmpty()) {
            return q.isEmpty()
                    ? msg(c, "文案-无条目", "no_corrections")
                    : msg(c, "文案-没有匹配", "no_match query={query}", "{query}", q);
        }
        SimpleDateFormat df = new SimpleDateFormat(P(c, "时间格式", "yyyy-MM-dd HH:mm"));
        StringBuilder sb = new StringBuilder();
        sb.append(q.isEmpty()
                ? msg(c, "文案-条目头", "corrections={n}", "{n}", String.valueOf(rows.size()))
                : msg(c, "文案-条目头查", "corrections={n} query={query}",
                        "{n}", String.valueOf(rows.size()), "{query}", q)).append('\n');
        int i = 0;
        for (JsonObject row : rows) {
            i++;
            sb.append(line(c, df, row, i)).append('\n');
        }
        return sb.toString().trim();
    }

    /** 条目渲染：一行一条，缺哪段就少哪行（行与行之间由 Java 拼，形不成形由 prompt.md 定）。 */
    private static String line(Cfg c, SimpleDateFormat df, JsonObject row, int i) {
        JsonObject ex = J.sub(row, "extra");
        String original = ex == null ? "" : J.s(ex, "original", "");
        String corrected = ex == null ? "" : J.s(ex, "corrected", "");
        String reason = ex == null ? "" : J.s(ex, "reason", "");
        String title = J.s(row, "title", "");
        long ts = J.l(row, "ts", 0L);
        List<String> parts = new ArrayList<String>();
        parts.add(msg(c, "文案-条目行", "{i}. #{id} {title}",
                "{i}", String.valueOf(i), "{id}", String.valueOf(J.l(row, "id", 0L)), "{title}", title));
        if (Str.has(original)) {
            parts.add(msg(c, "文案-原话行", "original: {original}", "{original}", original));
        }
        if (Str.has(corrected)) {
            parts.add(msg(c, "文案-纠正行", "corrected: {corrected}", "{corrected}", corrected));
        }
        if (Str.has(reason)) parts.add(reasonLine(c, reason));
        String tail = msg(c, "文案-条尾行", "{time} tags={tags}", "{time}",
                ts > 0 ? df.format(new Date(ts)) : "", "{tags}", tagsOf(row));
        if (Str.has(tail)) parts.add(tail);
        return Str.join(parts, "\n");
    }

    private static String reasonLine(Cfg c, String reason) {
        return msg(c, "文案-原因行", "reason: {reason}", "{reason}", reason);
    }

    // ================================================================ 组装

    /**
     * 条目正文：按 prompt.md 的 {@code ## 内容模板} 逐行填占位符，填完为空的行丢掉。
     * 占位符：{original} {corrected} {reason_line} {caller} {time}。
     */
    private static String body(Cfg c, Host h, String original, String corrected, String reason) {
        String tpl = c.rawTpl;
        if (Str.blank(tpl)) {
            tpl = DEFAULT_BODY_TPL;
        }
        String filled = fill(tpl,
                "{original}", original,
                "{corrected}", corrected,
                "{reason_line}", Str.has(reason) ? reasonLine(c, reason) : "",
                "{caller}", name(h),
                "{time}", new SimpleDateFormat(P(c, "时间格式", "yyyy-MM-dd HH:mm")).format(new Date()));
        StringBuilder sb = new StringBuilder();
        for (String raw : filled.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            sb.append(t).append('\n');
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? (original + "\n" + corrected) : out;
    }

    /** 内容模板缺失时的结构性兜底（prompt.md 正常时用不到）。 */
    private static final String DEFAULT_BODY_TPL = "original: {original}\ncorrected: {corrected}\n{reason_line}";

    /** extra 行：结构化字段 + 命中次数（note 没有独立列，按基板约定塞进 extra）。 */
    private static JsonObject extra(String original, String corrected, String reason, int hits, long qq, String by) {
        JsonObject o = new JsonObject();
        o.addProperty("original", original);
        o.addProperty("corrected", corrected);
        if (Str.has(reason)) o.addProperty("reason", reason);
        o.addProperty("hits", hits);
        if (qq > 0L) o.addProperty("by", qq);
        if (Str.has(by)) o.addProperty("by_name", by);
        return o;
    }

    /** 标签：纠错标签 + 调用方给的标签（去重、逗号分隔）。 */
    private static String tags(Cfg c, String raw) {
        List<String> out = new ArrayList<String>();
        String tag = P(c, "纠错标签", TAG_DEF);
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

    // ================================================================ 查询小工具

    /** 本技能自己写的行（source=技能名），新 → 老。 */
    private static List<JsonObject> rows(Store s, String source, int limit) {
        List<JsonObject> l = s.list(LIB, J.obj("source", source), limit, "ts DESC");
        return l == null ? new ArrayList<JsonObject>() : l;
    }

    /** 六库 search 没有 filter（只有 text + limit）：先多取，再在 Java 侧按 source 过滤。 */
    private static List<JsonObject> pick(List<JsonObject> found, int limit) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (found == null) return out;
        for (JsonObject row : found) {
            if (!NAME.equals(J.s(row, "source", ""))) continue;
            out.add(row);
            if (out.size() >= limit) break;
        }
        return out;
    }

    // ================================================================ 配置 / 文案（prompt.md）

    /** {@code prompt.md} 是唯一配置源：读不到 = 全部走结构性兜底。 */
    private static Cfg load(Host h) {
        Cfg c = new Cfg();
        String text = promptText(h);
        if (Str.blank(text)) return c;
        c.kv = pairs(section(text, "配置"));
        c.tx = pairs(section(text, "文案"));
        c.rawTpl = section(text, "内容模板");
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
        String rawTpl = "";
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

    /** 取 {@code ## 文案} 里的模板渲染；模板缺失时用调用方给的结构性兜底。 */
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

    /** 取 md 里 {@code ## 名} 到下一个 {@code ## } 之间的正文。 */
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

    /** {@code 键: 值} 一行一条 → map（跳过注释与没有冒号的行）。 */
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
     * 资源判定（ACL）：纠错记录全落在 {@code note} 库（C 类）—— 读判 {@code R}、写判 {@code W}。
     * 放行返回 null，否则返回可直接回给模型的拒绝原文。
     *
     * <p><b>它不表达"这是不是别人的知识"</b>：{@code note} 是 C 类资源，C 类对非主人只有 {@code R}
     * ⇒ 非主人写一律被位拦下。归属仍由 {@link #noteWriteGuard} 硬判。</p>
     *
     * @param bit {@code 'R'} 读 / {@code 'W'} 写
     */
    private static String check(Host h, char bit) {
        if (h == null) return null;
        return h.needDb(LIB, bit);
    }

    /**
     * note 表的<b>归属硬判</b>：{@code note} 是她的共享知识库（行里没有 per-user 归属列），
     * 所以非主人（{@code ALLUSER}）对它的<b>写/改/删一律拒</b>；{@code MASTER} 与 {@code SYSTEM}
     * （她自己的蒸馏/记录）照常放行。
     *
     * <p><b>为什么不按调用者给行打标记再判归属</b>：那要动 {@code note} 的 schema（加列/换语义），
     * 属于另一批的事 —— 这里先按"共享库 = 只有主人和她自己能写"的窄口径。</p>
     */
    private static String noteWriteGuard(Host h) {
        if (!restricted(h)) return null;
        return DENY + "知识库（note）是她的共享知识库，没有按人归属的条目 —— 只有主人和她自己能写。";
    }

    /**
     * 受限主体判定（ACL 之后的新口径）：受限的只有 {@code ALLUSER}（普通用户）。
     * <p>{@code MASTER} / {@code SYSTEM} 照常放行；身份取不到时按受限处理（fail-closed）。</p>
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

    private static String norm(String s) {
        return Str.nz(s).toLowerCase().replaceAll("[^0-9a-z\\u4e00-\\u9fff]+", "");
    }

    /** 短的一边够长且被长的一边包含 → 视为同一条。 */
    private static boolean near(String a, String b, int min) {
        if (a.isEmpty() || b.isEmpty()) return false;
        String sh = a.length() <= b.length() ? a : b;
        String lo = a.length() <= b.length() ? b : a;
        return sh.length() >= min && lo.contains(sh);
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
