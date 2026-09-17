import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sair.v4.auth.Caller;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Host;
import sair.v4.store.Store;

/**
 * 群审核的配置面：<b>{@code prompt.md} 全局默认 + {@code kv} 里的按群覆盖</b>。
 *
 * <h3>键与口径</h3>
 * <ul>
 *   <li>kv 键：{@code modcfg:<群号>}（与 V3 {@code GroupModeratorAgent} 落的 {@code app_state} 键<b>同名对齐</b>），
 *       值是 JSON；一个群一条，读不到就是"这个群没有按群配置"。</li>
 *   <li>全局默认：{@code prompt.md} 的 {@code 启用 / 违规词 / 禁言秒数 / 跳过群 / 豁免角色 / 警告文案}
 *       （V4 原有口径，一个都没改）。</li>
 *   <li>按群覆盖：同一批键名，另有 {@code 豁免QQ}（V3 的 {@code whitelistUsers}）与 {@code 规则说明}
 *       （V3 的 {@code ruleDescription}）。<b>给了哪个键就覆盖哪个键</b>，没给的继续用全局默认。</li>
 *   <li>词表/时长/豁免/文案<b>仍然全部来自文本配置</b>（{@code prompt.md} 或这条 JSON）：
 *       Java 里没有任何一条写死的违规词或话术。</li>
 * </ul>
 *
 * <h3>V3 → V4 字段映射（新口径，见技能 md）</h3>
 * <pre>
 *   autoMonitorEnabled        → on        （这个群审不审）
 *   customViolationKeywords   → words     （违规词，大小写不敏感）
 *   violationMuteDuration     → mute      （禁言秒数，< 60 抬到 60）
 *   whitelistUsers            → users     （豁免 QQ，命中不处理）
 *   ruleDescription           → note      （群规说明，给模型看，不参与判定）
 *   （V3 的 monitorIntervalSeconds / messagesPerCheck / spamCheckEnabled / adCheckEnabled
 *     在 V4 没有对应机制：V4 是逐条 on_message 判定，没有轮询取样，也没有刷屏/广告检测器）
 * </pre>
 *
 * <p>本类<b>不产面向用户/模型的文案</b>：需要回话时统一走 {@link #text} 读 {@code prompt.md}，
 * 缺键就明确报缺键（没有兜底话术）。</p>
 */
final class ModCfg {

    private ModCfg() {}

    /** 技能名（= 文件夹名 = md 文件名）。 */
    static final String NAME = "群审核";
    /** 按群配置的 kv 键前缀（V3 对齐）。 */
    static final String PREFIX = "modcfg:";
    /** 本条 JSON 的 schema 版本（将来改口径时用来判别）。 */
    static final int SCHEMA = 2;

    // ================================================================ 写配置的归属 / 角色硬判

    /**
     * 写按群配置的<b>归属 + 角色硬判</b>（这是隔离手段，不是权限位）。
     *
     * <p>规则（主人拍板的口径）：</p>
     * <ul>
     *   <li>{@code MASTER}（主人）与 {@code SYSTEM}（她自己的自主行为 / 自主维护全局策略）照常放行；</li>
     *   <li>{@code ALLUSER}：只允许写<b>自己所在的那个群</b>，且自己在该群里是<b>群主 / 管理员</b>；
     *       跨群写入一律拒（给 group 参数写别的群 = 改别人的群规，不能靠"能看见工具"就放行）；</li>
     *   <li>没有调用者身份 = 拒（fail-closed）。</li>
     * </ul>
     *
     * <p>为什么必须是硬判：C 类（kv）对非主人只有 {@code R}（写被位拦下）⇒ {@code h.needDb("kv",'W')}
     * 拦得住普通用户的写 —— "非主人写一律被位拦下"这条防线回来了（归属硬判仍然保留）。
     * 而群号又是<b>调用方给的参数</b>（{@code moderation} 的 {@code group}），位只回答"能不能写库"、
     * 回答不了"这是不是你的群"，跨群改别人群规这一层仍要靠这一判挡住。</p>
     *
     * <p>拒绝文案以 {@code [权限阻断]} 开头（结构前缀，不是 prompt.md 文案）：归属判定不该被改文案改掉。</p>
     *
     * @param c     调用者（{@code h.caller()}）
     * @param group 目标群号（已经做过"缺省回落到调用者自己的群"）
     * @return 放行返回 {@code null}，否则返回可直接回给模型的拒绝原文
     */
    static String writeDeny(Caller c, long group) {
        // 文案如实点名"谁能做"：放行面 = MASTER + SYSTEM + （ALLUSER 里）本群群主/管理员。
        // 每一句都把这三类写全 —— 少写一类就是"文案比行为窄"（她本人明明做得到，文案却说只有主人能）。
        if (c == null) {
            return "[权限阻断] 群审核配置只能由主人、她本人（SYSTEM）或本群群主/管理员改："
                    + "拿不到调用者身份，按拒绝处理。";
        }
        Caller.Kind k = c.kind();
        if (k == Caller.Kind.MASTER || k == Caller.Kind.SYSTEM) return null;
        if (group <= 0L) {
            return "[权限阻断] 只能改自己所在群的群审核配置：没给群号，也没有当前群"
                    + "（不带群号的写只有主人或她本人（SYSTEM）能做）。";
        }
        if (c.groupId() != group) {
            return "[权限阻断] 只能改自己所在群的群审核配置：目标群 " + group
                    + " 不是你说话的群（跨群写入一律拒，只有主人或她本人（SYSTEM）能跨群）。";
        }
        if (!isGroupAdminRole(c.groupRole())) {
            return "[权限阻断] 只能改自己所在群的群审核配置：你在群 " + group
                    + " 里不是群主/管理员（只有主人、她本人（SYSTEM）或本群群主/管理员能改）。";
        }
        return null;
    }

    /**
     * 这个角色值是不是"群主 / 管理员"。
     *
     * <p>取值口径与基板自己那套<b>完全一致</b>（{@code Boot.selfIsGroupAdmin} 判的也是这两个词）：
     * {@code owner} / {@code admin}（大小写不敏感，其余如 {@code member} = 普通成员）。
     * 调用者身上的角色来自网关（{@code QqGateway.callerOf} → {@code Ev.senderRole()}，
     * 即 NapCat 事件的 {@code sender.role}），不是这里自己发明的一套角色字符串。</p>
     */
    static boolean isGroupAdminRole(String role) {
        String r = Str.trim(role);
        return "owner".equalsIgnoreCase(r) || "admin".equalsIgnoreCase(r);
    }

    // ================================================================ 配置

    /** 一份生效配置（全局默认，或全局 + 按群覆盖）。 */
    static final class Cfg {
        long groupId = 0L;
        boolean enabled = false;
        int mute = 600;
        List<String> words = new ArrayList<String>();
        Set<Long> skipGroups = new HashSet<Long>();
        List<String> skipRoles = new ArrayList<String>();
        Set<Long> users = new HashSet<Long>();
        String warn = "";
        String note = "";
        /** 本次生效配置的来源：{@code prompt.md} 或 {@code modcfg:<群号>}。 */
        String source = "";
        /** 是否叠加了按群覆盖。 */
        boolean perGroup = false;

        Cfg copy() {
            Cfg c = new Cfg();
            c.groupId = groupId;
            c.enabled = enabled;
            c.mute = mute;
            c.words = new ArrayList<String>(words);
            c.skipGroups = new HashSet<Long>(skipGroups);
            c.skipRoles = new ArrayList<String>(skipRoles);
            c.users = new HashSet<Long>(users);
            c.warn = warn;
            c.note = note;
            c.source = source;
            c.perGroup = perGroup;
            return c;
        }
    }

    /** 全局默认（只读 {@code prompt.md}）。 */
    static Cfg global(Host h) {
        Cfg c = new Cfg();
        c.source = "prompt.md";
        String text = promptText(h);
        if (Str.blank(text)) return c;
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int i = line.indexOf(':');
            if (i < 0) i = line.indexOf('：');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim();
            String v = line.substring(i + 1).trim();
            applyKey(c, k, v);
        }
        return c;
    }

    /** 生效配置：全局默认 + {@code modcfg:<群号>} 的覆盖。 */
    static Cfg effective(Host h, long groupId) {
        Cfg c = global(h);
        if (groupId <= 0L) return c;
        c.groupId = groupId;
        JsonObject o = group(h == null ? null : h.store(), groupId);
        if (o == null) return c;
        applyJson(c, o);
        c.perGroup = true;
        c.source = PREFIX + groupId;
        return c;
    }

    /** 把一条按群 JSON 叠到配置上（只覆盖 JSON 里给了的键）。 */
    static void applyJson(Cfg c, JsonObject o) {
        if (o == null) return;
        if (J.get(o, "on") != null) c.enabled = J.b(o, "on", c.enabled);
        if (J.get(o, "words") != null) c.words = strings(o, "words");
        if (J.get(o, "mute") != null) c.mute = Math.max(60, J.i(o, "mute", c.mute));
        if (J.get(o, "roles") != null) c.skipRoles = strings(o, "roles");
        if (J.get(o, "users") != null) c.users = longs(o, "users");
        if (J.get(o, "warn") != null) c.warn = J.s(o, "warn", "");
        if (J.get(o, "note") != null) c.note = J.s(o, "note", "");
    }

    /** 单个键 → 配置项（全局 {@code prompt.md} 与按群 JSON 共用同一套键名）。 */
    static void applyKey(Cfg c, String k, String v) {
        if (k == null) return;
        if ("启用".equals(k) || "on".equals(k)) c.enabled = truthy(v);
        else if ("违规词".equals(k) || "words".equals(k)) c.words = split(v);
        else if ("禁言秒数".equals(k) || "mute".equals(k)) c.mute = Math.max(60, num(v, c.mute));
        else if ("跳过群".equals(k)) c.skipGroups = longsFromSplit(v);
        else if ("豁免角色".equals(k) || "roles".equals(k)) c.skipRoles = split(v);
        else if ("豁免QQ".equals(k) || "users".equals(k)) c.users = longsFromSplit(v);
        else if ("警告文案".equals(k) || "warn".equals(k)) c.warn = v;
        else if ("规则说明".equals(k) || "note".equals(k)) c.note = v;
    }

    // ================================================================ kv 读写

    /** 读某个群的按群配置（没有/坏数据 → null）。 */
    static JsonObject group(Store s, long groupId) {
        if (s == null || groupId <= 0L) return null;
        try {
            String raw = s.kv(PREFIX + groupId, "");
            if (Str.blank(raw)) return null;
            JsonElement e = J.el(raw);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 所有按群配置（诊断/列举用）：[{group, cfg, updated}]。 */
    static List<JsonObject> all(Store s, int limit) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (s == null) return out;
        try {
            for (JsonObject row : s.kvList(PREFIX, limit > 0 ? limit : 200)) {
                String k = J.s(row, "k", "");
                long gid = parseLong(k.substring(PREFIX.length()), 0L);
                JsonObject o = new JsonObject();
                o.addProperty("group", gid);
                o.addProperty("key", k);
                JsonElement e = J.el(J.s(row, "v", ""));
                o.add("cfg", e == null ? new JsonObject() : e);
                out.add(o);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 合并写某个群的按群配置（只覆盖 patch 里给了的键）；返回 false = 写不进去。 */
    static boolean write(Store s, long groupId, JsonObject patch) {
        if (s == null || groupId <= 0L) return false;
        try {
            JsonObject o = group(s, groupId);
            if (o == null) {
                o = new JsonObject();
                o.addProperty("v", SCHEMA);
            }
            if (patch != null) {
                for (Map.Entry<String, JsonElement> e : patch.entrySet()) o.add(e.getKey(), e.getValue());
            }
            o.addProperty("updated", System.currentTimeMillis());
            s.kvSet(PREFIX + groupId, J.json(o));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 记一下"谁改的"（结构字段，不参与判定）。 */
    static void stamp(Store s, long groupId, long by) {
        if (s == null || groupId <= 0L) return;
        JsonObject o = group(s, groupId);
        if (o == null) return;
        o.addProperty("by", by);
        s.kvSet(PREFIX + groupId, J.json(o));
    }

    /** 删掉某个群的按群配置（回到全局默认）；返回删除条数。 */
    static int clear(Store s, long groupId) {
        if (s == null || groupId <= 0L) return 0;
        try {
            return s.kvDelete(PREFIX + groupId, false);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ================================================================ 文本

    /** {@code prompt.md} 的原文。 */
    static String promptText(Host h) {
        try {
            File dir = h == null ? null : h.skillDir();
            if (dir == null || !dir.isDirectory()) {
                if (h != null && h.conf() != null) dir = new File(h.conf().skillsDir(), NAME);
            }
            return Fs.read(new File(dir, "prompt.md"), "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 文案：{@code prompt.md} 里的同名键；缺键 = 明确报缺键（不用兜底话术）。 */
    static String text(Host h, String key, Object... kv) {
        String tpl = texts(h).get(key);
        if (tpl == null) return "[" + NAME + "] prompt.md missing text: " + key;
        String s = tpl;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]));
        }
        return s;
    }

    /** 文案表（每次重读，改 prompt.md 即时生效）。 */
    static Map<String, String> texts(Host h) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        String text = promptText(h);
        if (Str.blank(text)) return m;
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int i = line.indexOf(':');
            if (i < 0) i = line.indexOf('：');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim();
            if (!k.isEmpty()) m.put(k, unesc(line.substring(i + 1).trim()));
        }
        return m;
    }

    // ================================================================ 小工具

    static List<String> split(String v) {
        List<String> out = new ArrayList<String>();
        if (Str.blank(v)) return out;
        for (String p : v.split("[,，\\s]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static Set<Long> longsFromSplit(String v) {
        Set<Long> out = new LinkedHashSet<Long>();
        for (String s : split(v)) {
            long n = parseLong(s, -1L);
            if (n > 0L) out.add(Long.valueOf(n));
        }
        return out;
    }

    static boolean contains(List<String> list, String v) {
        if (Str.blank(v) || list == null) return false;
        for (String s : list) if (s.equalsIgnoreCase(v.trim())) return true;
        return false;
    }

    static String join(Set<Long> set) {
        List<String> l = new ArrayList<String>();
        for (Long v : set) l.add(String.valueOf(v));
        return Str.join(l, ",");
    }

    static String joinList(List<String> l) { return Str.join(l == null ? new ArrayList<String>() : l, ","); }

    static long parseLong(String s, long def) {
        try {
            return Long.parseLong(Str.trim(s));
        } catch (Exception e) {
            return def;
        }
    }

    static int num(String v, int def) {
        try {
            return Integer.parseInt(Str.trim(v));
        } catch (Exception e) {
            return def;
        }
    }

    static boolean truthy(String v) {
        String s = Str.lower(Str.trim(v));
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "是".equals(s);
    }

    private static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<String>();
        JsonElement e = J.get(o, k);
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                String s = a.get(i) == null || a.get(i).isJsonNull() ? "" : a.get(i).getAsString().trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        return split(e.getAsString());
    }

    private static Set<Long> longs(JsonObject o, String k) {
        Set<Long> out = new LinkedHashSet<Long>();
        JsonElement e = J.get(o, k);
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                JsonElement x = a.get(i);
                if (x == null || x.isJsonNull()) continue;
                long n = parseLong(x.getAsString(), -1L);
                if (n > 0L) out.add(Long.valueOf(n));
            }
            return out;
        }
        return longsFromSplit(e.getAsString());
    }

    private static String unesc(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t");
    }
}
