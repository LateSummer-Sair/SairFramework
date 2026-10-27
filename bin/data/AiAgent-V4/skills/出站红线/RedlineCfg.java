import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.kit.Str;
import sair.v4.skill.Host;

/**
 * 「出站红线」自己的配置读取器：读**本插件目录**下的 {@code prompt.md}。
 *
 * <p><b>为什么另写一份、不复用 {@code EmotionCfg}</b>（第十二轮 GM 令）：
 * 这是两个**互相独立**的技能 —— 技能之间不许有依赖（情绪删掉/换掉时红线必须照样活，
 * 反过来也一样）。代价是这几十行解析代码有一份重复，收益是两边互不牵连。</p>
 *
 * <p>缓存口径与 {@code EmotionCfg} 同一套路：<b>按 prompt.md 文件各留一份 + mtime + 字节数失效</b>
 * （改完保存，下一条消息生效）。<b>为什么必须按文件分份</b>：{@code ExtRegistry} 会给每个 stage
 * 绑"它自己插件的宿主"，多插件共存时"进程级一份"会被互相顶掉
 * （表现是某个插件突然读到一份空配置 ⇒ 整段静默不干活）。</p>
 *
 * <p>只认两种形态：<b>标量键</b>（{@code 开关: true}，同名取第一次出现）与
 * <b>逗号清单键</b>（{@code 某键: a,b,c} —— 多行同名会全部取回）。这里没有条件/档位那一套。</p>
 *
 * <p><b>★★ 2026-09-22「归一与可观测批」（W2）：本类现在只服务一个键（{@code 开关}）</b>★★
 * 词表与判据都在基板（{@code sair.v4.term.Redline} + {@code data/prompts/redline.md}）；
 * 本类<b>不认识任何红线类目、一个红线词都不持有</b>。详见下面两处删除记录。</p>
 */
public final class RedlineCfg {

    /** 技能文件夹名（与 md 的 {@code name:} 一致；{@code ExtRegistry} 的 owner 也是它）。 */
    public static final String NAME = "出站红线";

    // ★★ 2026-09-22「归一与可观测批」（W2）★★
    //   这里原来还有两个**兜底词表常量**（`DEF_ALWAYS` = 常开族四个类目名逐字，
    //   `DEF_NEVER_REPLACE` = "绝不替换"那两个类目名逐字；**具体词值本文不再复述** ——
    //   复述一次就等于把第二份词表又写回技能源码，本批的目的正是消灭它）。
    //   复核已证：它们在 `alwaysCats()` / `neverReplaceCats()` 里被读（旧 RedlineCfg.java:111/120），
    //   而本目录新的 `prompt.md` 已经把 `禁词常开` / `禁词不替换` 两个键删掉 ⇒ `raw.isEmpty()`
    //   **恒真** ⇒ 这两个"兜底"从安全网变成了**常规路径**，红线词字面量也因此
    //   随本技能的 `.class` 一起出货（全树除 `data/prompts/redline.md` 外**唯一**一份词值副本）。
    //   ⇒ **整段删除**（两个常量 + 它们的两个读法 `alwaysCats()` / `neverReplaceCats()`
    //     + 判据转发器 `always()` / `neverReplace()` / `replaceable()` + 类目识别 `categories()`；
    //     复核与本次改动都确认这些成员在 `src\` / `data\skills\` / `probe\` 里**零调用者**）。
    //   ⇒ 本文件**不再持有任何红线词**：判据与词表都只住基板
    //     `sair.v4.term.Redline` + `data/prompts/redline.md`（"全树只有一处判据"这句话
    //     从本批起对**技能源码**也成立）。
    //   ⇒ 本类剩下的职责只有一个：读本目录 `prompt.md` 的 `开关` 键（见 `RedlineStage`）。

    private final boolean loaded;
    private final Map<String, String> kv = new LinkedHashMap<String, String>();
    private final Map<String, List<String>> many = new LinkedHashMap<String, List<String>>();

    private RedlineCfg(boolean loaded) { this.loaded = loaded; }

    public boolean loaded() { return loaded; }

    /** 原始键值表（诊断用；只读）。 */
    public Map<String, String> all() { return Collections.unmodifiableMap(kv); }

    // ---------------- 标量 ----------------

    public String s(String key, String def) {
        String v = kv.get(key);
        return Str.has(v) ? v.trim() : def;
    }

    /** 开关：{@code true/1/yes/on/开/是} 真，{@code false/0/no/off/关/否} 假，其余用默认。 */
    public boolean flag(String key, boolean def) {
        String v = kv.get(key);
        if (!Str.has(v)) return def;
        String s = v.trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)
                || "开".equals(s) || "是".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s)
                || "关".equals(s) || "否".equals(s)) return false;
        return def;
    }

    /** 逗号清单（同名多行时全部取回、按文件顺序；每行的值也按逗号切）。 */
    public List<String> tags(String key) {
        List<String> out = new ArrayList<String>();
        if (!Str.has(key)) return out;
        List<String> vals = many.get(key);
        if (vals == null) {
            String one = kv.get(key);
            if (!Str.has(one)) return out;
            vals = new ArrayList<String>();
            vals.add(one);
        }
        for (String v : vals) for (String x : split(v)) if (!out.contains(x)) out.add(x);
        return out;
    }

    public static List<String> split(String v) {
        List<String> out = new ArrayList<String>();
        if (v == null) return out;
        for (String p : v.split("[,，]")) {
            String t = Str.trim(p);
            if (Str.has(t)) out.add(t);
        }
        return out;
    }

    // ---------------- 类目 ----------------
    //
    // ★★ 2026-09-22「归一与可观测批」（W2）：这一段**整段删除** ★★
    //
    // 这里原来有：`categories()`（认 `禁词-<类目>` 键）、`alwaysCats()`（读 `禁词常开`）、
    // `neverReplaceCats()`（读 `禁词不替换`）、`neverReplace(cat)`、`replaceable(cat)`、
    // `always(cat)` —— 一整套"红线类目识别"的**第二份实现**。
    //
    // 为什么删（两条都成立才删）：
    //   ① **没有调用者**：`RedlineStage` 的判定早就整体转发基板（`Redline.veto` / `Redline.replace`），
    //      复核 + 本次改动都在 `src\` / `data\skills\` / `probe\` 里 grep 过
    //      `alwaysCats|neverReplaceCats|neverReplace(|replaceable(|categories(` —— 零命中；
    //   ② **它是词值副本的载体**：`alwaysCats()` / `neverReplaceCats()` 读的那两个兜底常量
    //      （已随上一段一起删除）是技能 `.class` 里仅存的红线词字面量。
    //
    // 删掉之后，本类**不认识任何红线类目、不持有任何红线词**；
    // 唯一的判据与唯一的词表 = 基板 `sair.v4.term.Redline` + `data/prompts/redline.md`。

    // ---------------- 读取（按文件惰性缓存 + mtime + 字节数失效） ----------------

    private static final class Cache {
        final RedlineCfg cfg;
        final long stamp;
        final long len;

        Cache(RedlineCfg cfg, long stamp, long len) {
            this.cfg = cfg;
            this.stamp = stamp;
            this.len = len;
        }
    }

    private static final Map<String, Cache> CACHE = new java.util.concurrent.ConcurrentHashMap<String, Cache>();
    private static final int CACHE_MAX = 64;

    /** 取配置（{@code Host} 为 null/拿不到目录时：给一份已有缓存，一份都没有就给空配置）。 */
    public static RedlineCfg of(Host h) {
        try {
            File f = promptFile(h);
            if (f == null) {
                for (Cache c : CACHE.values()) return c.cfg;
                return new RedlineCfg(false);
            }
            long stamp = f.lastModified();
            long len = f.length();
            String key = f.getAbsolutePath();
            Cache c = CACHE.get(key);
            if (c != null && c.stamp == stamp && c.len == len) return c.cfg;
            RedlineCfg fresh = parse(f);
            if (CACHE.size() > CACHE_MAX) CACHE.clear();
            CACHE.put(key, new Cache(fresh, stamp, len));
            return fresh;
        } catch (Throwable t) {
            for (Cache c : CACHE.values()) return c.cfg;
            return new RedlineCfg(false);
        }
    }

    /** 清掉缓存（探针/诊断用；正常路径靠 mtime 失效）。 */
    public static synchronized void forget() {
        CACHE.clear();
    }

    /** 本插件目录下的 prompt.md（拿不到目录返回 null）。 */
    public static File promptFile(Host h) {
        try {
            if (h == null) return null;
            File dir = h.skillDir();
            if (dir == null && h.conf() != null) dir = new File(h.conf().skillsDir(), NAME);
            return dir == null ? null : new File(dir, "prompt.md");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析一份 prompt.md：`#` 开头跳过；`键: 值` 取**第一次出现**的值；同名多行进 {@code many}。 */
    private static RedlineCfg parse(File f) {
        RedlineCfg c = new RedlineCfg(true);
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) == '\ufeff') line = line.substring(1);
                String s = line.trim();
                if (s.isEmpty() || s.charAt(0) == '#') continue;
                int k = colon(s);
                if (k <= 0) continue;
                String key = s.substring(0, k).trim();
                String val = unesc(s.substring(k + 1).trim());
                if (key.isEmpty()) continue;
                List<String> one = c.many.get(key);
                if (one == null) {
                    one = new ArrayList<String>();
                    c.many.put(key, one);
                }
                one.add(val);
                if (!c.kv.containsKey(key)) c.kv.put(key, val);
            }
        } catch (Throwable t) {
            // 读不出来 = 这份配置不完整（调用方按"少做点事"处理），绝不抛
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
        return c;
    }

    /** 半角或全角冒号的下标（没有返回 -1）。 */
    private static int colon(String s) {
        int a = s.indexOf(':');
        int b = s.indexOf('\uFF1A');
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private static String unesc(String s) {
        return s == null ? "" : s.replace("\\n", "\n").replace("\\t", "\t");
    }
}
