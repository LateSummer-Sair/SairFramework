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
 * 「@提问者」的配置面：本插件目录下 {@code prompt.md} 的 {@code 键: 值} 表。
 *
 * <p>这一段原来长在 {@code AtAskerStage.Cfg} 里（私有静态嵌套类）。现在插件要读同一份配置的地方
 * 有三个（出站 stage / {@code on_message} 记账钩子 / 生命周期钩子的钉住），所以把它<b>原样搬成顶层类</b>，
 * 三处共用一份缓存 —— 读法、失效口径、兜底默认值一个字都没改：</p>
 * <ul>
 *   <li>惰性缓存 + 按「文件 + mtime」失效：每条消息不读盘，改完保存下一条生效；</li>
 *   <li>读不到文件 / {@code Host} 为 null（探针直接注册的合成件就是 null）→ 空表，
 *       调用方逐项落到兜底默认值，绝不抛异常。</li>
 * </ul>
 */
final class AtAskerCfg {

    static final Map<String, List<String>> EMPTY = Collections.emptyMap();

    private static File file;
    private static long stamp = -1L;
    private static Map<String, List<String>> kv = new LinkedHashMap<String, List<String>>();

    private AtAskerCfg() {}

    static synchronized Map<String, List<String>> of(Host h) {
        if (h == null) return EMPTY;
        try {
            File dir = h.skillDir();
            if (dir == null) return EMPTY;
            File f = new File(dir, "prompt.md");
            boolean exists = f.isFile();
            long m = exists ? f.lastModified() : 0L;
            if (file != null && file.equals(f) && m == stamp) return kv;
            Map<String, List<String>> next = exists ? parse(f) : new LinkedHashMap<String, List<String>>();
            file = f;
            stamp = m;
            kv = next;
            return next;
        } catch (Throwable t) {
            return EMPTY;
        }
    }

    /** {@code 键: 值} 一行一条；{@code #} 开头是注释；行内第一个（半角/全角）冒号是分隔符。 */
    private static Map<String, List<String>> parse(File f) {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) == '\ufeff') line = line.substring(1);
                String s = line.trim();
                if (s.isEmpty() || s.charAt(0) == '#') continue;
                int a = s.indexOf(':');
                int b = s.indexOf('：');
                int k = (a < 0) ? b : (b < 0 ? a : Math.min(a, b));
                if (k <= 0) continue;
                String key = s.substring(0, k).trim();
                String val = s.substring(k + 1).trim();
                if (key.isEmpty()) continue;
                List<String> one = m.get(key);
                if (one == null) {
                    one = new ArrayList<String>();
                    m.put(key, one);
                }
                one.add(val);
            }
        } catch (Throwable ignored) {
            // 读失败 = 空表 = 全用兜底值
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return m;
    }

    static String one(Map<String, List<String>> c, String key, String def) {
        if (c == null) return def;
        List<String> l = c.get(key);
        if (l == null || l.isEmpty()) return def;
        String v = l.get(0);
        return v == null ? def : v;
    }

    static boolean flag(Map<String, List<String>> c, String key, boolean def) {
        String v = one(c, key, null);
        if (v == null) return def;
        String s = v.trim().toLowerCase();
        if (s.isEmpty()) return def;
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")
                || s.equals("开") || s.equals("是")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")
                || s.equals("关") || s.equals("否")) return false;
        return def;
    }

    static long num(Map<String, List<String>> c, String key, long def) {
        String v = one(c, key, null);
        if (v == null) return def;
        try {
            return Long.parseLong(Str.trim(v));
        } catch (Throwable t) {
            return def;
        }
    }

    /** 逗号/空白分隔的清单（例如「重要标记: at,quote」）。空值 = 空表。 */
    static List<String> list(Map<String, List<String>> c, String key) {
        List<String> out = new ArrayList<String>();
        String v = one(c, key, null);
        if (Str.blank(v)) return out;
        for (String p : v.split("[,\\s]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
