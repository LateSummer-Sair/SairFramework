import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;
import sair.v4.store.Store;

/**
 * 消息标记（工具 {@code mark}）：给某条消息（或任意一个键）打一个 <b>AI 内部 Mark</b> ——
 * "这条要跟进""这条是结论""别答这条"之类，纯给自己的笔记本用，不发给任何人。
 *
 * <p>落在基板运行表 {@code kv}：键 = 前缀（默认 {@code mark:}）+ 目标键，
 * 值 = 一段 JSON（mark / note / ts / by / session）。列表用 {@code kvList(前缀)}、
 * 清除用 {@code kvDelete(键)} —— 不自己写 SQL。</p>
 *
 * <p>四个 op：{@code set}（打标记；mark 与 note 都空 = 删掉这个标记）、{@code get}（读一条）、
 * {@code list}（列出所有标记，可按关键词过滤）、{@code migrate}（把旧键迁到新口径）。
 * 文案/阈值/键前缀全在 {@code prompt.md}。</p>
 */
public class MarkSkill implements Skill {

    /** 技能名（= 文件夹名 = md 名），也是日志前缀。 */
    private static final String NAME = "消息标记";
    /** kv 键前缀的结构性兜底（真正生效的值在 prompt.md 的「键前缀」）。 */
    private static final String PREFIX_DEF = "mark:";

    // ================================================================ 工具入口

    /**
     * 声明本技能的四个 op（技能管控表就是按这些名字放行或封禁的）。
     */
    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        ops.add("mark", "set", "打一个内部标记");
        ops.add("mark", "get", "读一条标记");
        ops.add("mark", "list", "列出标记");
        ops.add("mark", "migrate", "把旧键迁到新口径");
    }

    @Override
    public Object call(Args a, Host h) {
        Cfg c = load(h);
        Store s = h == null ? null : h.store();
        if (s == null) return msg(c, "文案-无库", "store unavailable");
        String op = Str.trim(a.str("op")).toLowerCase();
        if (op.isEmpty()) op = "get";
        try {
            if ("set".equals(op)) return set(a, h, s, c);
            if ("get".equals(op)) return get(a, h, s, c);
            if ("list".equals(op)) return list(a, h, s, c);
            if ("migrate".equals(op)) return migrate(s, h, c);
            return msg(c, "文案-未知op", "unsupported op={op}", "{op}", op);
        } catch (Throwable t) {
            log(h, "op=" + op + " error: " + t);
            return msg(c, "文案-执行异常", "error: {error}", "{error}", String.valueOf(t));
        }
    }

    // ================================================================ set

    /**
     * 打标记；mark 与 note 都空 = 把这个标记删掉（一条 KV 一条标记，不做列表塞一个值）。
     *
     * <p><b>写者唯一</b>：一个会话的标记只由该会话的<b>主回合</b>写。子 Agent 看不到会话消息流，
     * 因此它在 {@code subagent()} 为真时<b>只返回提议</b>（建议的 msg_id + 理由），由主 Agent 决定是否落库。
     * 键 = {@code <前缀><session>/<msg_id>}：{@code kv} 的主键天然 upsert，同 (session,msg_id) 重复写只留一行。</p>
     */
    private static String set(Args a, Host h, Store s, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("mark.set");
        if (deny != null) return deny;
        String target = target(a);
        if (target.isEmpty()) return a.missing("target");
        String mark = cut(Str.trim(a.str("mark")), atLeast(P(c, "标记长度上限", "60"), 4, 500));
        String note = cut(Str.trim(a.str("note")), atLeast(P(c, "备注长度上限", "200"), 4, 2000));
        String msgId = msgIdOf(a, target);
        String key = key(c, target, h);
        if (key.length() > atLeast(P(c, "键长度上限", "160"), 8, 2000)) {
            return msg(c, "文案-键太长", "key too long limit={limit}",
                    "{limit}", P(c, "键长度上限", "160"));
        }
        // 子 Agent：只提议，不落库（写者唯一）
        if (h != null && h.subagent()) {
            return msg(c, "文案-子Agent提议", "propose msg_id={msg_id} mark={mark}",
                    "{msg_id}", msgId.isEmpty() ? target : msgId,
                    "{mark}", mark, "{note}", note, "{from}", Str.nz(h.agentLabel()));
        }
        if (mark.isEmpty() && note.isEmpty()) {
            int n = s.kvDelete(key, false);
            return n > 0
                    ? msg(c, "文案-已清除", "cleared {target}", "{target}", target)
                    : msg(c, "文案-本来就没有", "no_mark {target}", "{target}", target);
        }

        JsonObject v = new JsonObject();
        v.addProperty("mark", mark);
        v.addProperty("note", note);
        v.addProperty("ts", System.currentTimeMillis());
        v.addProperty("by", qq(h));                                   // 谁标的（QQ）
        v.addProperty("by_agent", agentOf(h));                        // main / sub-<n>
        if (!msgId.isEmpty()) v.addProperty("msg_id", msgId);          // 消息稳定身份（grouplog 行的 msg_id）
        v.addProperty("target", target);                               // 兼容：原始目标串
        if (!msgId.equals(target)) v.addProperty("legacy", true);      // 目标串不是 msg_id → 标成 legacy，不静默丢
        String who = name(h);
        if (Str.has(who)) v.addProperty("by_name", who);
        String session = session(h);
        if (Str.has(session)) v.addProperty("session", session);
        s.kvSet(key, J.json(v));
        String back = s.kv(key, "");
        if (!Str.has(back)) return msg(c, "文案-写入失败", "kv write failed");
        return msg(c, "文案-已标记", "marked {target}",
                "{target}", target, "{mark}", mark, "{note}", note,
                "{time}", new SimpleDateFormat(P(c, "时间格式", "yyyy-MM-dd HH:mm")).format(new Date()));
    }

    // ================================================================ migrate

    /**
     * 旧键迁移：把 {@code mark:<自己会话>/<目标串>} 里的旧标记转成新口径。
     *
     * <p><b>只迁自己会话</b>（{@code scopePrefix} 前缀扫描）：标记本来就是按会话隔离的，
     * 迁移也不能例外 —— 扫全库前缀会把所有会话的行都回写一遍，那是跨会话写别人的数据。</p>
     *
     * <p>规则：目标串本身就是数字（= msg_id）→ 补上 {@code msg_id}/{@code by_agent} 字段，键不变；
     * 对不上 msg_id 的（文字目标、或该 msg_id 已不在 {@code grouplog} 里）→ <b>保留并标 {@code legacy:true}</b>，
     * 绝不静默丢。返回 "改了几条 / 保留 legacy 几条"。</p>
     */
    private static String migrate(Store s, Host h, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）—— migrate 既读也写 kv、
        // 还要按 grouplog 判 msg_id 是否存在，但它们是同一个动作 {@code mark.migrate} 的内部步骤，
        // 不再拆成第二个 op。
        String deny = h.need("mark.migrate");
        if (deny != null) return deny;
        String prefix = P(c, "键前缀", PREFIX_DEF);
        int limit = atLeast(P(c, "迁移扫描条数", "1000"), 1, 5000);
        // 只扫<b>自己会话</b>的前缀（键 = <前缀><session>/<target>，与 set/get/list 同一把尺子）。
        // 旧写法扫的是全库前缀 mark: —— 那是"把所有会话的行都回写一遍"，等于一次调用就跨会话写别人的数据。
        // 收窄成会话前缀后：① 迁移只补自己会话的行，别人的数据一个字节都不会被碰；② 谁都能安全地迁移自己那一摊。
        List<JsonObject> rows = s.kvList(scopePrefix(c, h), limit);
        if (rows == null) rows = new ArrayList<JsonObject>();
        int upgraded = 0, legacy = 0, kept = 0;
        for (JsonObject row : rows) {
            String k = J.s(row, "k", "");
            if (Str.blank(k)) continue;
            JsonObject v = parse(J.s(row, "v", ""));
            if (v == null) v = new JsonObject();
            String target = k.startsWith(prefix) ? k.substring(prefix.length()) : k;
            int slash = target.lastIndexOf('/');
            if (slash >= 0) target = target.substring(slash + 1);
            boolean numeric = target.matches("\\d+");
            boolean exists = numeric && s.db() != null
                    && s.db().count("grouplog", "msg_id = ?", Long.parseLong(target)) > 0;
            boolean changed = false;
            if (numeric && exists) {
                if (!J.s(v, "msg_id", "").equals(target)) {
                    v.addProperty("msg_id", target);
                    changed = true;
                }
                if (v.has("legacy")) {
                    v.remove("legacy");
                    changed = true;
                }
                if (!v.has("by_agent")) {
                    v.addProperty("by_agent", "main");
                    changed = true;
                }
                upgraded++;
            } else {
                if (!J.b(v, "legacy", false)) {
                    v.addProperty("legacy", true);
                    changed = true;
                }
                legacy++;
            }
            if (changed) s.kvSet(k, J.json(v));
            else kept++;
        }
        return msg(c, "文案-迁移完成", "migrate scanned={n} upgraded={up} legacy={lg} unchanged={kept}",
                "{n}", String.valueOf(rows.size()), "{up}", String.valueOf(upgraded),
                "{lg}", String.valueOf(legacy), "{kept}", String.valueOf(kept));
    }

    /** {@code by_agent}：主回合 = {@code main}，子 Agent = 它的标签。 */
    private static String agentOf(Host h) {
        try {
            if (h == null) return "main";
            return h.subagent() ? Str.nz(h.agentLabel()) : "main";
        } catch (Throwable t) {
            return "main";
        }
    }

    /** 这条标记认的 msg_id：显式 {@code msg_id} 参数优先；目标串本身是数字也算。 */
    private static String msgIdOf(Args a, String target) {
        String m = Str.trim(a.str("msg_id"));
        if (m.isEmpty() && target != null && target.matches("\\d+")) m = target;
        return m.replaceAll("[\\r\\n\\t]", "").trim();
    }

    // ================================================================ get

    /** 读一条标记。 */
    private static String get(Args a, Host h, Store s, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("mark.get");
        if (deny != null) return deny;
        String target = target(a);
        if (target.isEmpty()) return a.missing("target");
        JsonObject v = read(s, key(c, target, h));
        if (v == null) return msg(c, "文案-无标记", "no_mark {target}", "{target}", target);
        return msg(c, "文案-一条标记", "{target} → {mark}",
                "{target}", target, "{mark}", J.s(v, "mark", ""), "{note}", J.s(v, "note", ""),
                "{time}", time(c, J.l(v, "ts", 0L)), "{by}", J.s(v, "by", ""),
                "{session}", J.s(v, "session", ""));
    }

    // ================================================================ list

    /** 列出所有标记（kvList 按前缀取），可给 query 在 Java 侧过滤 mark/note/键。 */
    private static String list(Args a, Host h, Store s, Cfg c) {
        // 这个动作分支的 op 判定（技能管控表：op 放行才动手）
        String deny = h.need("mark.list");
        if (deny != null) return deny;
        int limit = a.i("limit", atLeast(P(c, "列表条数", "20"), 1, 500));
        if (limit <= 0) limit = atLeast(P(c, "列表条数", "20"), 1, 500);
        String q = Str.trim(a.str("query")).toLowerCase();
        List<JsonObject> rows = s.kvList(scopePrefix(c, h), limit);
        if (rows == null) rows = new ArrayList<JsonObject>();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JsonObject row : rows) {
            String k = J.s(row, "k", "");
            String t = targetOf(c, k, h);
            JsonObject v = parse(J.s(row, "v", ""));
            String mark = v == null ? "" : J.s(v, "mark", "");
            String note = v == null ? "" : J.s(v, "note", "");
            if (!q.isEmpty()
                    && t.toLowerCase().indexOf(q) < 0
                    && mark.toLowerCase().indexOf(q) < 0
                    && note.toLowerCase().indexOf(q) < 0) continue;
            n++;
            sb.append(msg(c, "文案-条目行", "{i}. {target} → {mark}",
                    "{i}", String.valueOf(n), "{target}", t, "{mark}", mark,
                    "{note}", note,
                    "{time}", v == null ? "" : time(c, J.l(v, "ts", 0L)),
                    "{by}", v == null ? "" : J.s(v, "by", ""),
                    "{legacy}", v != null && J.b(v, "legacy", false) ? " [legacy]" : "",
                    "{msg_id}", v == null ? "" : J.s(v, "msg_id", ""))).append('\n');
        }
        if (n == 0) {
            return q.isEmpty()
                    ? msg(c, "文案-无条目", "no_marks")
                    : msg(c, "文案-没有匹配", "no_match query={query}", "{query}", q);
        }
        String head = q.isEmpty()
                ? msg(c, "文案-条目头", "marks={n}", "{n}", String.valueOf(n))
                : msg(c, "文案-条目头查", "marks={n} query={query}",
                        "{n}", String.valueOf(n), "{query}", q);
        return (head + "\n" + sb.toString()).trim();
    }

    // ================================================================ KV 读写

    /** 会话内的键（标记按会话隔离：同一个 target 在不同群里互不可见）。 */
    private static String key(Cfg c, String target, Host h) {
        return scopePrefix(c, h) + target;
    }

    /** 会话前缀：<键前缀><session>/ */
    private static String scopePrefix(Cfg c, Host h) {
        String session = h == null || h.session() == null ? "unknown" : h.session();
        return P(c, "键前缀", PREFIX_DEF) + Str.safeName(session) + "/";
    }

    private static String targetOf(Cfg c, String key, Host h) {
        String p = scopePrefix(c, h);
        return key != null && key.startsWith(p) ? key.substring(p.length()) : Str.nz(key);
    }

    private static JsonObject read(Store s, String key) {
        return parse(s.kv(key, ""));
    }

    private static JsonObject parse(String json) {
        if (Str.blank(json)) return null;
        JsonObject o = J.obj(json);
        if (o != null) return o;
        JsonObject back = new JsonObject();      // 不是 JSON（手写的旧值）也照样能读
        back.addProperty("mark", Str.trim(json));
        return back;
    }

    /** target 参数：主名 target，兼容 key / msg_id 两个常见写法。 */
    private static String target(Args a) {
        String t = Str.trim(a.str("target"));
        if (t.isEmpty()) t = Str.trim(a.str("key"));
        if (t.isEmpty()) t = Str.trim(a.str("msg_id"));
        return t.replaceAll("[\\r\\n\\t]", " ").trim();
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
        return Str.trim(s);
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
     * 标记的隔离靠<b>键</b>：键 = {@code <前缀><session>/<target>}，读（{@code list}）与写
     * （{@code set}）都按会话前缀走，迁移（{@code migrate}）同样只扫自己会话的前缀 ——
     * 一个人碰不到别人的会话（这是技能自己的归属设计，不是权限判定）。
     */
    private static String time(Cfg c, long ts) {
        if (ts <= 0L) return "";
        return new SimpleDateFormat(P(c, "时间格式", "yyyy-MM-dd HH:mm")).format(new Date(ts));
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

    private static String session(Host h) {
        try {
            return h == null ? "" : Str.nz(h.session());
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
