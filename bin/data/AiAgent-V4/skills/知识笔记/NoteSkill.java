import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.auth.Caller;
import sair.v4.kit.Fingerprint;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;
import sair.v4.store.LibSpec;
import sair.v4.store.Libs;
import sair.v4.store.Store;

/**
 * 知识笔记（工具 {@code note}）：add / search / recent / delete，落在六库的 {@code note} 库。
 *
 * <p>只用 {@link Host#store()} 的通用接口（put/list/search/delete），不自己写 SQL：
 * 建表、检索回退（FTS5 → LIKE）、字段白名单都是基板的职责。</p>
 *
 * <p><b>写入查重（两道）</b>：① 标题完全相同 → 更新（原口径）；
 * ② 标题<b>近似</b> → 更新（基板 {@link Fingerprint} 的中文 2-gram Jaccard，阈值在 {@code prompt.md}）。
 * 第二道是 V3 {@code TextFingerprint} 的等价物：同一主题被反复沉淀成"略有差异的标题"时，
 * 只有精确同名判定会让重复条目持续堆积。判定只用<b>标题</b>（标题即身份），
 * 正文是模板化结果、不参与身份判定（V3 实测：用正文会把"同主题不同问题"并成一条）。</p>
 *
 * <p><b>文案与阈值全在 {@code prompt.md}</b>（每次调用重读，改即生效）：缺键就明确报缺键，
 * Java 里没有写死的用户文案，也没有隐藏默认阈值。</p>
 *
 * <p><b>两个口径，别混</b>：① <b>op 授权</b>（技能管控表）—— 每个动作（{@code note.add} /
 * {@code note.search} / {@code note.recent} / {@code note.delete}）在分支开头各判自己的 op，
 * 没授权的身份一律拒；② <b>归属硬判</b>（{@link #noteWriteGuard}）—— {@code note} 是<b>她的共享知识库</b>
 * （行里只有 {@code source}，没有"这是谁的"这一列），所以 {@code add}（含同名/近似名命中的覆盖更新）
 * 与 {@code delete} 对非主人（{@code ALLUSER}）<b>一律拒</b>；{@code MASTER} 与她自己的自主行为
 * （{@code SYSTEM}）照常。"是不是写别人的"只有归属硬判回答得了（共享库没有归属列）。</p>
 */
public class NoteSkill implements Skill {

    private static final String TOOL = "note";
    private static final String LIB = "note";
    private static final String SRC = "skill:note";
    /** 近似重复命中次数的计数键（V3 的 bumpNoteDupHit 等价物）。 */
    private static final String KV_DUP = "note:dup_hit";
    /** 归属阻断的固定前缀（探针按它断言）。<b>必须由代码保证</b>，不走 prompt.md。 */
    private static final String DENY = "[权限阻断] ";

    // ================================================================ 库声明（装载期）

    /**
     * 声明 {@code note} 表：<b>谁写入、谁召回，谁声明</b>（表结构不再写在基板里）。
     *
     * <p>列/类型/默认值/FTS 列/索引名/中文别名逐字对应现网 {@code v4.db} —— 线上已经有真实数据，
     * 改结构 = 迁移 = 不允许。基板 {@code Db} 只负责"缺表即建、缺列即补"（幂等）。</p>
     */
    @Override
    public void declare(Libs libs) {
        libs.register(new LibSpec(LIB, "知识笔记", "知识笔记")
                .ts()
                .col("title", "TEXT", "''")
                .col("content", "TEXT", "''")
                .col("tags", "TEXT", "''")
                .col("source", "TEXT", "''")
                .col("extra", "TEXT", "''")
                .fts("title", "content", "tags")
                .alias("知识", "笔记", "知识笔记", "notes")
                .idx("idx_note_ts", "ts")
                .idx("idx_note_title", "title"));
    }

    /**
     * 声明本技能的全部 op（技能管控表的骨架）：装载期调一次，{@code ai/perm gen} 按它把每个 op
     * 列成一行空（未授权），主人再往要开口的那行补身份。
     * <p>四个动作各一条 —— "允许他查"与"允许他删"因此能在管控表里分开写。</p>
     */
    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        ops.add(TOOL, "add", "记一条笔记（同名/近似标题覆盖更新）");
        ops.add(TOOL, "search", "按关键词检索笔记");
        ops.add(TOOL, "recent", "列最近的几条笔记");
        ops.add(TOOL, "delete", "按 id 删一条笔记");
    }

    @Override
    public Object call(Args a, Host h) {
        if (h == null || h.store() == null) return "[" + TOOL + "] 拿不到六库接口，技能不可用。";
        Cfg c = Cfg.load(h);
        if (c.bad != null) return c.bad;
        Store store = h.store();
        String op = Str.trim(a.str("op")).toLowerCase();
        if (op.isEmpty()) op = "search";
        // op 判定：四个动作各判自己的 op（技能管控表按这些名字放行/封禁），判在动作分支的开头。
        // 判完 op 之后还有"归属硬判"（add / delete 是写她的共享知识库）—— 那是数据归属，不是权限。
        if ("add".equals(op)) {
            String deny = h.need("note.add");
            if (deny != null) return deny;
            String guard = noteWriteGuard(h);
            if (guard != null) return guard;
            return add(a, c, store);
        }
        if ("search".equals(op)) {
            String deny = h.need("note.search");
            if (deny != null) return deny;
            return search(a, c, store);
        }
        if ("recent".equals(op)) {
            String deny = h.need("note.recent");
            if (deny != null) return deny;
            return recent(a, c, store);
        }
        if ("delete".equals(op)) {
            String deny = h.need("note.delete");
            if (deny != null) return deny;
            // 归属硬判（写/删都在这里判一次；delete() 内部还会再判一次，见 noteWriteGuard）：
            // note 是她的共享知识库、行里没有归属列 —— 非主人一律拒。
            String guard = noteWriteGuard(h);
            if (guard != null) return guard;
            return delete(a, c, store, h);
        }
        return c.t("不支持op", "op", op);
    }

    // ---------------------------------------------------------------- add

    /** 并发写闸门：标题查重是"模糊匹配"，所以整库一把锁 —— 查重与写库之间不能有缝。 */
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

    private static String add(Args a, Cfg c, Store store) {
        synchronized (writeLock(LIB)) {
            return addLocked(a, c, store);
        }
    }

    private static String addLocked(Args a, Cfg c, Store store) {
        String content = a.str("content");
        if (Str.blank(content)) return a.missing("content");
        String title = Str.trim(a.str("title"));
        if (title.isEmpty()) title = firstLine(content, 24);

        // ① 标题完全相同 → 更新
        JsonObject same = findSameTitle(store, title);
        if (same != null) return update(c, store, same, title, content, "同名更新", 0.0D);

        // ② 标题近似 → 更新（阈值/取样条数/标题前缀都在 prompt.md）
        double threshold = c.d("相似阈值");
        if (threshold < 0.0D) return c.miss("相似阈值");
        long sample = c.n("相似取样条数");
        if (sample <= 0L) return c.miss("相似取样条数");
        long minTerms = c.n("指纹最短项数");
        if (minTerms <= 0L) return c.miss("指纹最短项数");
        Hit hit = findNearTitle(store, c, title, threshold, (int) sample, (int) minTerms);
        if (hit != null) {
            bump(store);
            return update(c, store, hit.row, title, content, "近似更新", hit.score);
        }

        // ③ 新增
        long limit = c.n("正文长度上限");
        if (limit > 0L && content.length() > limit) return c.t("正文太长", "limit", limit, "len", content.length());
        JsonObject row = new JsonObject();
        row.addProperty("title", title);
        row.addProperty("content", content);
        row.addProperty("tags", tags(a));
        row.addProperty("source", SRC);
        long id = store.put(LIB, row);
        if (id <= 0) return c.t("写入失败");
        String tagText = Str.has(J.s(row, "tags", "")) ? c.t("标签尾巴", "tags", J.s(row, "tags", "")) : "";
        return c.t("已记下", "id", id, "title", title, "len", content.length(), "tags", tagText);
    }

    /** 命中已有笔记 → 覆盖内容（保留原 id 与 ts），返回可读结果。 */
    private static String update(Cfg c, Store store, JsonObject exist, String title, String content,
                                 String key, double score) {
        long id = J.l(exist, "id", 0L);
        if (id <= 0L) return c.t("写入失败");
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("title", title);
        row.addProperty("content", content);
        row.addProperty("source", SRC);
        long rid = store.put(LIB, row);
        if (rid <= 0) return c.t("更新失败", "id", id);
        String scoreText = score > 0.0D ? String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(score)) : "";
        return c.t(key, "id", rid, "title", title, "score", scoreText);
    }

    private static JsonObject findSameTitle(Store store, String title) {
        if (Str.blank(title)) return null;
        try {
            List<JsonObject> rows = store.list(LIB, J.obj("title", title), 1, null);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 近似标题（基板 {@link Fingerprint} 的 2-gram Jaccard）；命中返回最高分那条。 */
    private static Hit findNearTitle(Store store, Cfg c, String title, double threshold,
                                     int sample, int minTerms) {
        if (Str.blank(title)) return null;
        String probe = normTitle(c, title);
        java.util.Set<String> fp = Fingerprint.bigram(probe);
        if (!Fingerprint.enough(fp, minTerms)) return null;      // 标题太短 → 不做近似判定（太容易撞）
        List<JsonObject> rows;
        try {
            rows = store.list(LIB, null, sample, "ts DESC");
        } catch (Throwable t) {
            return null;
        }
        if (rows == null) return null;
        Hit best = null;
        for (JsonObject row : rows) {
            String other = normTitle(c, J.s(row, "title", ""));
            if (other.isEmpty()) continue;
            if (other.equals(probe)) continue;                    // 完全相同的那条已经查过了
            java.util.Set<String> of = Fingerprint.bigram(other);
            if (!Fingerprint.enough(of, minTerms)) continue;
            double s = Fingerprint.jaccard(fp, of);
            if (s < threshold) continue;
            if (best == null || s > best.score) best = new Hit(row, s);
        }
        return best;
    }

    /** 近似判定的比较文本：剥掉本技能配置的标题前缀（如自动沉淀的 {@code [联网搜索] }）后归一化。 */
    private static String normTitle(Cfg c, String title) {
        return Fingerprint.norm(Fingerprint.stripPrefixes(title, c.prefixes));
    }

    /** 近似命中计数（诊断用；坏掉了也不该影响写入）。 */
    private static void bump(Store store) {
        try {
            long n = 0L;
            try {
                n = Long.parseLong(store.kv(KV_DUP, "0").trim());
            } catch (Exception ignored) {
            }
            store.kvSet(KV_DUP, String.valueOf(n + 1L));
        } catch (Throwable ignored) {
        }
    }

    private static final class Hit {
        final JsonObject row;
        final double score;

        Hit(JsonObject row, double score) {
            this.row = row;
            this.score = score;
        }
    }

    // ---------------------------------------------------------------- search

    private static String search(Args a, Cfg c, Store store) {
        String q = Str.trim(a.str("query"));
        if (q.isEmpty()) return a.missing("query");
        int limit = a.i("limit", 5);
        if (limit <= 0) limit = 5;
        List<JsonObject> rows = store.search(LIB, q, limit);
        if (rows == null || rows.isEmpty()) return c.t("检索无果", "query", q);
        StringBuilder sb = new StringBuilder();
        sb.append(c.t("检索头", "n", rows.size(), "query", q)).append('\n');
        sb.append(lines(rows));
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- recent

    private static String recent(Args a, Cfg c, Store store) {
        int limit = a.i("limit", 10);
        if (limit <= 0) limit = 10;
        List<JsonObject> rows = store.list(LIB, null, limit, "ts DESC");
        if (rows == null || rows.isEmpty()) return c.t("最近空");
        StringBuilder sb = new StringBuilder();
        sb.append(c.t("最近头", "n", rows.size())).append('\n');
        sb.append(lines(rows));
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- delete

    private static String delete(Args a, Cfg c, Store store, Host h) {
        // 归属硬判：note 是她的共享知识库（行里没有归属列）—— 非主人删不了任何一条。
        // 原来这里只校验 id 存在（连 source 都不看），归属校验一处都没有。
        String guard = noteWriteGuard(h);
        if (guard != null) return guard;
        long id = a.l("id", 0L);
        if (id <= 0L) return a.missing("id");
        JsonObject row = store.get(LIB, id);
        if (row == null) return c.t("无此id", "id", id);
        boolean ok = store.delete(LIB, id);
        return ok ? c.t("已删除", "id", id, "title", J.s(row, "title", ""))
                : c.t("删除失败", "id", id);
    }

    // ---------------------------------------------------------------- 格式化

    private static String lines(List<JsonObject> rows) {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (JsonObject r : rows) {
            i++;
            String title = J.s(r, "title", "");
            String content = J.s(r, "content", "");
            long ts = J.l(r, "ts", 0L);
            sb.append(i).append(". #").append(J.l(r, "id", 0L));
            if (Str.has(title)) sb.append(" ").append(title);
            sb.append("\n   ").append(Str.cut(Str.oneLine(content), 200)).append("\n");
            List<String> meta = new ArrayList<String>();
            String tags = tagsOf(r);
            if (Str.has(tags)) meta.add("tags: " + tags);
            if (ts > 0) meta.add(df.format(new Date(ts)));
            String hit = J.s(r, "_hit", "");
            if (Str.has(hit)) meta.add("命中: " + hit);
            if (!meta.isEmpty()) sb.append("   ").append(Str.join(meta, " · ")).append("\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 小工具

    /** tags 既收数组也收逗号分隔字符串。 */
    private static String tags(Args a) {
        List<String> arr = a.strs("tags");
        if (arr != null && !arr.isEmpty()) return Str.join(arr, ",");
        String s = Str.trim(a.str("tags"));
        if (s.isEmpty()) return "";
        return Str.join(Str.split(s, ","), ",");
    }

    private static String tagsOf(JsonObject row) {
        com.google.gson.JsonElement e = J.get(row, "tags");
        if (e == null) return "";
        if (e.isJsonArray()) {
            JsonArray arr = e.getAsJsonArray();
            List<String> l = new ArrayList<String>();
            for (int i = 0; i < arr.size(); i++) l.add(arr.get(i).getAsString());
            return Str.join(l, ",");
        }
        return e.getAsString();
    }

    private static String firstLine(String text, int max) {
        String s = Str.oneLine(text);
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ---------------------------------------------------------------- 归属硬判

    /**
     * note 表的<b>归属硬判</b>：{@code note} 是她的共享知识库（行里只有 {@code source}、
     * 没有 per-user 归属列），所以非主人（{@code ALLUSER}）对它的<b>写/改/删一律拒</b>；
     * {@code MASTER} 与她自己的自主行为（{@code SYSTEM}）照常。
     *
     * <p><b>为什么还要归属硬判</b>：op（技能管控表）只回答"这个身份能不能用
     * {@code note.add} / {@code note.delete} 这个动作"，回答不了"这一条是不是他的"
     * （共享库没有归属列）—— 归属问题由这一判回答。</p>
     *
     * <p><b>为什么判定用身份而不是按行打标记</b>：按调用者给行打标记再按标记判归属要动 {@code note}
     * 的 schema（线上已有真实数据）= 迁移，属于另一批；这一批先按"共享库 = 只有主人和她自己能写"的窄口径。</p>
     */
    private static String noteWriteGuard(Host h) {
        if (!restricted(h)) return null;
        return DENY + "知识库（note）是她的共享知识库，没有按人归属的条目 —— 只有主人和她自己能写/改/删。";
    }

    /**
     * 受限主体判定（新口径）：受限的只有 {@code ALLUSER}（普通用户）。
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

    // ---------------------------------------------------------------- prompt.md

    /** {@code prompt.md} 里的阈值与文案（每次调用重读）。 */
    private static final class Cfg {

        private final Map<String, String> v = new LinkedHashMap<String, String>();
        private String bad;
        private List<String> prefixes = new ArrayList<String>();

        static Cfg load(Host h) {
            Cfg c = new Cfg();
            File dir = null;
            try {
                if (h != null) {
                    dir = h.skillDir();
                    if (dir == null && h.conf() != null) dir = new File(h.conf().skillsDir(), "知识笔记");
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
            c.prefixes = Fingerprint.prefixes(c.v.get("标题前缀"));
            return c;
        }

        String s(String k) { return v.get(k); }

        /** 数值配置；缺键或非数返回 -1（调用方明确报缺键，不用隐藏默认值）。 */
        long n(String k) {
            String s = v.get(k);
            if (s == null) return -1L;
            try {
                return Long.parseLong(s.trim());
            } catch (Exception e) {
                return -1L;
            }
        }

        /** 小数配置；缺键或非数返回 -1。 */
        double d(String k) {
            String s = v.get(k);
            if (s == null) return -1.0D;
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception e) {
                return -1.0D;
            }
        }

        String miss(String k) { return "[" + TOOL + "] prompt.md missing config: " + k; }

        String t(String k, Object... kv) {
            String tpl = v.get(k);
            if (tpl == null) return "[" + TOOL + "] prompt.md missing text: " + k;
            return fill(tpl, kv);
        }

        private static String fill(String tpl, Object... kv) {
            String s = tpl;
            for (int i = 0; i + 1 < kv.length; i += 2) {
                s = s.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]));
            }
            return s;
        }

        private static String unesc(String s) {
            return s.replace("\\n", "\n").replace("\\t", "\t");
        }
    }
}
