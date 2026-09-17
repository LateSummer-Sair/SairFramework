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
import sair.v4.qq.Ev;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;
import sair.v4.skill.Skill;
import sair.v4.store.Store;

/**
 * 触发词（工具 {@code trigger} + {@code on_message} 钩子）：一份"说到这些词就接话"的词表。
 *
 * <p>op {@code set / list / remove}，词表存在 kv（词条 {@code trigger:w:<词>}，冷却 {@code trigger:c:<会话>:<词>}）。</p>
 *
 * <p><b>写词表是主人的事（归属硬判，不是权限位）</b>：词表是<b>全局共享</b>的一份表
 * （键 {@code trigger:w:<词>} 里没有身份，任何人的 {@code list} 都看得见所有人的词），
 * 所以"这是不是你的数据"不能靠权限位回答 —— C 类（kv）对非主人只有 {@code R}（{@code RWX} 只有 {@code SYSTEM} 有），
 * {@code h.needDb("kv", 'W')} 对非主人一律拦下（非主人写一律被位拦下），它只剩"库在不在、能不能写"这一层意思。
 * 于是 {@code set}/{@code remove} 在它之外<b>显式判身份</b>：{@code MASTER} / {@code SYSTEM} 照常放行，
 * {@code ALLUSER} 一律拒绝（拒绝文案以 {@code [权限阻断]} 开头）。
 * 命中时的冷却与用量回写、{@code list} 读取都不受这条影响。</p>
 *
 * <p><b>命中不自己发消息</b>：钩子里只 {@code h.spawn(接话说明, 工具, true)} 把活交给主 Agent，
 * 并把 {@code payload._handled} 置 true —— 这样基板不会在同一轮里再自动回一次（不会双重回复）。</p>
 *
 * <p><b>让位与冷却</b>：被 @ / 私聊 / 主人说话这些"基板本来就会回"的消息默认让位；
 * 同一会话同一词有冷却，冷却期内不接管（消息照旧交回基板判定）。</p>
 *
 * <p><b>零硬编码文案</b>：阈值与全部文本读自同目录 {@code prompt.md}（每次调用重读）；Java 里只有
 * op 名、kv 键名、工具名、日志前缀与 ASCII 兜底。</p>
 */
public class TriggerSkill implements Skill, Hooked {

    private static final String TOOL = "trigger";
    private static final String NAME = "触发词";
    private static final String OPS = "set / list / remove";
    private static final String PREFIX = "trigger:w:";
    private static final String COOL = "trigger:c:";
    private static final String LAST = "trigger:last";
    private static final int KV_SCAN = 500;
    /** 触发词表落在通用 kv 表（C 类运行时数据）—— 资源判定用的库名。 */
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
            if ("set".equals(op)) return set(a, h, c);
            if ("list".equals(op)) return list(a, h, c);
            if ("remove".equals(op)) return remove(a, h, c);
            return c.t("不支持op", "op", op, "ops", OPS);
        } catch (Throwable t) {
            log(h, "op=" + op + " 异常: " + t);
            return c.t("异常", "op", op, "error", String.valueOf(t));
        }
    }

    // ==================================================================== 归属硬判

    /**
     * 写词表的<b>归属硬判</b>：{@code MASTER}（主人）与 {@code SYSTEM}（她自己的自主行为、定时维护）放行，
     * {@code ALLUSER}（普通用户）一律拒。
     *
     * <p>为什么必须是硬判而不是权限位：词表键 {@code trigger:w:<词>} <b>不含任何身份</b>，
     * 一份表全局共享 —— 一个人 {@code set} 的词会出现在任何人的 {@code list} 里。
     * 而 C 类（kv）对非主人只有 {@code R}（{@code RWX} 只有 {@code SYSTEM} 有），{@code h.needDb("kv", 'W')} 对非主人一律拦下，
     * 它不能回答"这是不是你的数据"（位这一层只说"库能不能写"：C 类对非主人只有 {@code R} ⇒ 非主人写一律被位拦下）。</p>
     *
     * <p>拒绝文案以 {@code [权限阻断]} 开头（结构前缀，不是 prompt.md 文案）：
     * 这是归属判定，不是可配置话术，改文案不该改得了它。</p>
     *
     * @return 放行返回 {@code null}，否则返回可直接回给模型的拒绝原文
     */
    private static String ownerDeny(Host h) {
        Caller c = h == null ? null : h.caller();
        if (c == null) return "[权限阻断] 触发词是主人的事：拿不到调用者身份，按拒绝处理。";
        Caller.Kind k = c.kind();
        if (k == Caller.Kind.MASTER || k == Caller.Kind.SYSTEM) return null;
        // 文案如实点名"谁能做"：判定的放行面是 MASTER + SYSTEM 两个，所以两个都要写出来
        // （只写"仅主人"就是文案比行为窄）。保留"仅主人"这四个字是既有探针断言的要求
        // （ProbeSkillsC 的 trigger.set 非主人被拦下时点名"仅主人"），故写成"仅 X 与 Y 能…"。
        return "[权限阻断] 触发词是主人的事：仅主人（MASTER）与她自己的自主行为（SYSTEM）能设置或删除触发词。";
    }

    // ---------------------------------------------------------------- set

    private static String set(Args a, Host h, Cfg c) {
        // 归属硬判（ACL 新口径）：写触发词表 = 改一份全局共享的表，只有主人与她自己的自主行为能改。
        // 这一判与下面的资源判定是两件事：那个判"库能不能写"，这个判"这是不是你能碰的数据"。
        String owner = ownerDeny(h);
        if (owner != null) return owner;
        // 资源判定（ACL）：写触发词表 = 写通用 kv 表（C:W；C 类对非主人只有 R（RWX 只有 SYSTEM 有）⇒ 非主人写一律被位拦下）
        String deny = h.needDb(LIB_KV, 'W');
        if (deny != null) return deny;

        List<String> words = split(a.str("word"));
        if (words.isEmpty()) return a.missing("word");
        long maxLen = c.n("触发词长度上限");
        long max = c.n("最多触发词");
        if (maxLen <= 0) return c.miss("触发词长度上限");
        if (max <= 0) return c.miss("最多触发词");

        Store st = h.store();
        List<JsonObject> have = all(st);
        String prompt = Str.trim(a.str("prompt"));
        String tools = Str.trim(a.str("tools"));
        String by = h.caller() == null ? "" : Str.nz(h.caller().session());
        StringBuilder note = new StringBuilder();
        String first = null;
        for (String w : words) {
            if (w.length() > maxLen) {
                note.append("\n").append(c.t("触发词太长", "word", Str.cut(w, 60), "size", w.length(), "limit", maxLen));
                continue;
            }
            JsonObject old = byWord(have, w);
            if (old == null && have.size() >= max) {
                note.append("\n").append(c.t("触发词太多", "limit", max));
                break;
            }
            JsonObject row = new JsonObject();
            row.addProperty("word", w);
            row.addProperty("prompt", prompt);
            row.addProperty("tools", tools);
            row.addProperty("ts", System.currentTimeMillis());
            row.addProperty("by", by);
            row.addProperty("uses", old == null ? 0L : J.l(old, "uses", 0L));
            st.kvSet(key(w), J.json(row));
            if (old == null) have.add(row);
            if (first == null) first = w;
        }
        String body = note.length() == 0 ? "" : note.toString().trim() + " ";
        if (first == null) {
            // 一个词都没真的落库（全超长 / 一到就撞上「最多触发词」）：回执**不能说"已设置"** ——
            // 那句话会把没落库的词说成落库了。改回 prompt.md 的「没有设置」+ 逐条原因（文案不进 Java）。
            return c.t("没有设置", "note", body, "count", all(st).size());
        }
        return c.t("已设置", "word", first, "note", body, "count", all(st).size());
    }

    // ---------------------------------------------------------------- list

    private static String list(Args a, Host h, Cfg c) {
        String deny = h.needDb(LIB_KV, 'R');           // 列触发词表 = 读通用 kv 表（C:R，默认位就够读）
        if (deny != null) return deny;
        List<JsonObject> rows = all(h.store());
        if (rows.isEmpty()) return c.t("列表空");
        long cap = c.n("列表上限");
        if (cap <= 0) return c.miss("列表上限");
        StringBuilder sb = new StringBuilder();
        sb.append(c.t("列表头", "count", rows.size())).append('\n');
        int i = 0;
        for (JsonObject r : rows) {
            if (i >= cap) break;
            i++;
            long last = J.l(r, "last_used", 0L);
            String lastTxt = J.l(r, "uses", 0L) > 0 && last > 0
                    ? c.t("最后使用", "time", stamp(last, c))
                    : "";
            sb.append(c.t("列表项",
                    "i", i,
                    "word", J.s(r, "word", ""),
                    "uses", J.l(r, "uses", 0L),
                    "last", lastTxt,
                    "prompt", Str.cut(Str.oneLine(Str.blank(J.s(r, "prompt", ""))
                            ? c.t("触发_说明默认") : J.s(r, "prompt", "")), 160),
                    "tools", Str.blank(J.s(r, "tools", "")) ? c.t("全部工具") : J.s(r, "tools", ""))).append('\n');
        }
        if (rows.size() > i) sb.append(c.t("列表还有", "rest", rows.size() - i));
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- remove

    private static String remove(Args a, Host h, Cfg c) {
        // 归属硬判（ACL 新口径）：删触发词同 set —— 改的是全局共享的表，非主人一律拒。
        String owner = ownerDeny(h);
        if (owner != null) return owner;
        // 资源判定（ACL）：删触发词 = 写通用 kv 表（C:W）
        String deny = h.needDb(LIB_KV, 'W');
        if (deny != null) return deny;
        Store st = h.store();
        if (a.b("all", false)) {
            int n = 0;
            for (JsonObject r : all(st)) if (st.kvDelete(key(J.s(r, "word", "")), false) > 0) n++;
            return c.t("已清空", "count", n);
        }
        List<String> words = split(a.str("word"));
        if (words.isEmpty()) return a.missing("word");
        String w = words.get(0);
        if (byWord(all(st), w) == null) return c.t("没有这个触发词", "word", w);
        st.kvDelete(key(w), false);
        return c.t("已删除", "word", w);
    }

    // ==================================================================== 命中（on_message）

    /**
     * 投"这条别自动回"的一票（基板认这个键，见 {@code QqGateway}）。
     * <p>为什么由技能投：词表与冷却都是这个技能的配置，基板不该替它做判断；
     * 而这一票投下去，基板就<b>不会建上下文、不会调模型</b> —— 省 token 就靠它。</p>
     */
    private static void decline(JsonObject payload, String why) {
        if (payload == null) return;
        payload.addProperty("_reply", false);
        payload.addProperty("_reply_reason", why);
    }

    @Override
    public void on(String hook, JsonObject payload, Host h) {
        if (!"on_message".equals(hook) || payload == null || h == null || h.store() == null) return;
        try {
            Cfg c = Cfg.load(h);
            if (c.bad != null) return;
            if (J.b(payload, "_handled", false)) return;                 // 已被别的技能接管

            String yieldAt = c.s("被艾特时让位");
            String yieldPm = c.s("私聊时让位");
            long cd = c.n0("冷却秒");
            if (yieldAt == null || yieldPm == null || cd < 0) {
                log(h, c.t("缺配置", "key", yieldAt == null ? "被艾特时让位"
                        : (yieldPm == null ? "私聊时让位" : "冷却秒")));
                return;
            }

            Ev e = Ev.parse(payload);
            if (e.userId() <= 0L || e.userId() == e.selfId()) return;
            String text = e.plainText();
            if (Str.blank(text)) return;

            // 让位规则（出厂：@ 与私聊都让位 → 交给基板正常回）。
            // 注意这里**不再有"主人说话就让位"**：主人跟别人一样，只有 @ 或命中触发词才回，
            // 否则投 _reply=false（省 token）—— 主人要在 QQ 里跟它说话，@ 它、私聊它、或用一个触发词即可。
            if (e.atSelf() && truthy(yieldAt)) {
                log(h, c.t("让位"));
                return;
            }
            if (e.isPrivate() && truthy(yieldPm)) {
                log(h, c.t("让位"));
                return;
            }

            JsonObject hit = null;
            String word = null;
            for (JsonObject r : all(h.store())) {
                String w = J.s(r, "word", "");
                if (Str.blank(w)) continue;
                if (text.toLowerCase().indexOf(w.toLowerCase()) >= 0) {
                    hit = r;
                    word = w;
                    break;
                }
            }
            if (hit == null) {
                // 既没让位、也没命中触发词 → 明确"这条别自动回"：基板据此直接结束，
                // 不建上下文、不调模型（省 token 的关键一步）。见触发词.md 的"三种情形"。
                decline(payload, "no_trigger");
                return;
            }

            long now = System.currentTimeMillis();
            // 自主钩子（on_message）：主体是 SYSTEM（C:RWX）→ 她自己的触发词接话要读写 kv，
            // 这一判只兜"库不可写"；C 类对非主人只有 R（RWX 只有 SYSTEM 有），普通用户命中时写位会拦下他 ——
            // 真正决定"谁能写词表"的是上面的归属硬判（set / remove），钩子路径不写词表结构、只回写 uses/冷却。
            String denyKv = h.needDb(LIB_KV, 'W');
            if (denyKv != null) {
                log(h, "触发词命中但写不了 kv：" + denyKv);
                return;
            }
            String ck = COOL + Str.nz(h.session()) + ":" + Str.lower(word);
            if (cd > 0) {
                long last = num(h.store().kv(ck, "0"), 0L);
                if (now - last < cd * 1000L) {
                    log(h, c.t("冷却中", "word", word, "left", (cd - (now - last) / 1000L)));
                    decline(payload, "trigger_cooldown");     // 冷却期内也不回（既省 token，也不重复接话）
                    return;
                }
            }

            String tpl = Str.has(J.s(hit, "prompt", "")) ? J.s(hit, "prompt", "") : c.t("触发_说明默认");
            String task = fill(tpl,
                    "word", word,
                    "name", Str.nz(e.senderName()),
                    "qq", e.userId(),
                    "group", e.groupId(),
                    "text", Str.cut(Str.oneLine(text), 160),
                    "time", stamp(now, c));
            // reply=true：这一单就是"替机器人说一句话" —— 子 Agent 写成什么由宿主发出去（它自己不会再发一遍）
            JsonObject ticket = h.spawn(task, split(J.s(hit, "tools", "")), true, null, null, true);
            payload.addProperty("_handled", true);                        // 我接管了：基板不再自动回复
            // 这条被技能接管后，基板**不会**再为它建上下文、也不会落 user 行 ——
            // 于是它的回复在聊天记录里"凭空多一条"，看着像重复回答。这里补一行 user，
            // 让历史读起来是「谁说的 → 回了什么」，下一轮主 Agent 拿到的上下文也自洽。
            try {
                h.store().appendDialog(h.session(), "user", text, 0);
            } catch (Throwable t) {
                log(h, "补记 user 行失败（不影响接话）：" + t);
            }

            hit.addProperty("uses", J.l(hit, "uses", 0L) + 1L);
            hit.addProperty("last_used", now);
            h.store().kvSet(key(word), J.json(hit));
            h.store().kvSet(ck, String.valueOf(now));
            JsonObject rec = new JsonObject();
            rec.addProperty("ts", now);
            rec.addProperty("word", word);
            rec.addProperty("group", e.groupId());
            rec.addProperty("qq", e.userId());
            rec.addProperty("name", Str.nz(e.senderName()));
            rec.addProperty("text", Str.cut(Str.oneLine(text), 160));
            rec.addProperty("spawn", J.s(ticket == null ? new JsonObject() : ticket, "status", "?"));
            h.store().kvSet(LAST, J.json(rec));
            log(h, c.t("已接管", "word", word, "status", J.s(ticket == null ? new JsonObject() : ticket, "status", "?")));
        } catch (Throwable t) {
            log(h, "on_message 处理异常：" + t);
        }
    }

    // ==================================================================== kv 词表

    private static String key(String word) { return PREFIX + Str.lower(Str.trim(word)); }

    private static List<JsonObject> all(Store st) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (st == null) return out;
        for (JsonObject kv : st.kvList(PREFIX, KV_SCAN)) {
            JsonObject row = J.obj(J.s(kv, "v", ""));
            if (row == null || Str.blank(J.s(row, "word", ""))) continue;
            out.add(row);
        }
        Collections.sort(out, new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject x, JsonObject y) {
                return J.s(x, "word", "").compareToIgnoreCase(J.s(y, "word", ""));
            }
        });
        return out;
    }

    private static JsonObject byWord(List<JsonObject> rows, String word) {
        if (Str.blank(word)) return null;
        String k = Str.lower(word.trim());
        for (JsonObject r : rows) if (Str.lower(J.s(r, "word", "")).equals(k)) return r;
        return null;
    }

    private static List<String> split(String v) {
        List<String> out = new ArrayList<String>();
        if (Str.blank(v)) return out;
        for (String p : v.split("[,，]+")) {
            String t = p.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
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

    private static String fill(String tpl, Object... kv) {
        if (tpl == null) return "";
        String s = tpl;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]));
        }
        return s;
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
            long n = n0(k);
            return n > 0 ? n : -1L;
        }

        /** 允许 0 的数值（0 = 明确关闭该项）；缺键或非数字返回 -1。 */
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
