import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import sair.v4.kit.Fs;
import sair.v4.kit.Http;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 网页抓取（工具 {@code web}）：抓 URL 内容 —— JSON 自动美化、HTML 去标签成文本、超长截断。
 *
 * <p>纯只读：不写六库、不落盘、不改配置。任何失败都转成一句可读说明。</p>
 *
 * <p><b>安全闸门</b>：抓取走 {@link Http#getGuarded}（基板 {@code kit/NetGuard}）——
 * 环回/私网/链路本地/云元数据地址一律拒绝，且<b>每一跳重定向都重新判定</b>。
 * 被拒时技能只用本技能 {@code prompt.md} 里的文案回话（缺键就明确报缺键，没有兜底文案）。</p>
 *
 * <p><b>编码</b>：响应体按基板 {@code kit/Text} 的口径解码（BOM → Content-Type charset →
 * meta charset → 严格 UTF-8 → GBK → GB18030 → UTF-8 兜底），并把实际用的编码写进结果头。</p>
 */
public class WebSkill implements Skill {

    private static final String TOOL = "web";
    private static final String NAME = "网页抓取";

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private static final int DEF_MAX = 4000;
    private static final int CAP_MAX = 20000;

    @Override
    public Object call(Args a, Host h) {
        Cfg c = Cfg.load(h);
        if (c.bad != null) return c.bad;

        // 出网抓取 = 执行一把工具（工具的运行时形态是 T 类资源）→ 判这个工具的 X 位。
        // ALLUSER 默认 T=无 ⇒ 非主人用不了这把工具，要特例放行；她自己（SYSTEM）与主人同样照常。
        String deny = h == null ? null : h.need(sair.v4.auth.Res.tool("web"), 'X');
        if (deny != null) return deny;

        String url = Str.trim(a.str("url"));
        if (url.isEmpty()) return a.missing("url");
        String low = Str.lower(url);
        if (!low.startsWith("http://") && !low.startsWith("https://")) {
            if (low.contains("://")) {
                return c.t("拒绝协议", "url", Str.cut(url, 200));
            }
            url = "http://" + url;
        }

        int max = a.i("max", DEF_MAX);
        if (max <= 0) max = DEF_MAX;
        if (max > CAP_MAX) max = CAP_MAX;
        boolean raw = a.b("raw", false);

        Map<String, String> hdrs = Http.headers();
        hdrs.put("User-Agent", UA);
        hdrs.put("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
        hdrs.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6");

        Http.Res r = Http.getGuarded(url, hdrs, 15000);
        if (r.denied()) {
            // 闸门拒绝：文案在 prompt.md（缺键 = 明确报缺键，不用兜底话术糊过去）
            return c.t("拒绝内网", "url", Str.cut(r.finalUrl.isEmpty() ? url : r.finalUrl, 200),
                    "host", r.blockedHost, "reason", r.blockedReason, "code", r.blocked);
        }
        if (r.code == 0 && Str.has(r.error) && !r.redirectBroken()) {
            return c.t("抓取失败", "error", why(r), "url", Str.cut(r.finalUrl.isEmpty() ? url : r.finalUrl, 200));
        }
        if (r.redirectBroken()) {
            return c.t("重定向异常", "url", Str.cut(r.finalUrl.isEmpty() ? url : r.finalUrl, 200), "reason", r.redirect);
        }
        if (!r.ok()) return c.t("抓取失败", "error", why(r), "url", Str.cut(r.finalUrl.isEmpty() ? url : r.finalUrl, 200));

        String body = r.body;
        String text;
        if (raw) {
            text = body;
        } else if (looksJson(body)) {
            Object parsed = J.el(body.trim());
            text = parsed == null ? body : J.pretty(parsed);
        } else {
            text = htmlToText(body);
        }
        if (text == null) text = "";
        text = text.trim();
        if (text.isEmpty()) {
            return c.t("空正文", "bytes", String.valueOf(body.length()));
        }
        String head = c.t("结果头", "url", r.finalUrl.isEmpty() ? url : r.finalUrl,
                "code", String.valueOf(r.code), "bytes", String.valueOf(body.length()),
                "charset", r.charset.isEmpty() ? "?" : r.charset) + "\n";
        if (text.length() > max) {
            return head + text.substring(0, max) + c.t("截断尾巴", "total", String.valueOf(text.length()));
        }
        return head + text;
    }

    // ---------------------------------------------------------------- 处理

    private static boolean looksJson(String body) {
        String s = body == null ? "" : body.trim();
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        if (c != '{' && c != '[') return false;
        return J.el(s) != null;
    }

    /** HTML → 纯文本：去脚本样式、块级转行、去标签、解实体、压空行。 */
    private static String htmlToText(String html) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?is)<!--[\\s\\S]*?-->", " ");
        s = s.replaceAll("(?is)<(script|style|noscript|svg|head)\\b[^>]*>[\\s\\S]*?</\\1>", " ");
        s = s.replaceAll("(?is)<(br|hr)\\s*/?>", "\n");
        s = s.replaceAll("(?is)</(p|div|li|tr|h[1-6]|section|article|header|footer|blockquote|pre)>", "\n");
        s = s.replaceAll("(?is)<[^>]*>", "");
        s = unescape(s);
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String line : s.split("\n", -1)) {
            String t = line.replaceAll("[ \\t\\u00a0]+", " ").trim();
            if (t.isEmpty()) {
                blank++;
                if (blank > 1) continue;
            } else {
                blank = 0;
            }
            sb.append(t).append('\n');
        }
        return sb.toString().trim();
    }

    private static String unescape(String s) {
        String t = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&ensp;", " ").replace("&emsp;", " ")
                .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep = m.group(0);
            try {
                int cp = m.group(1).isEmpty()
                        ? Integer.parseInt(m.group(2))
                        : Integer.parseInt(m.group(2), 16);
                rep = new String(Character.toChars(cp));
            } catch (Exception ignored) {
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String why(Http.Res r) {
        if (r == null) return "no_response";
        if (Str.has(r.error)) return r.error;
        return "HTTP " + r.code;
    }

    // ---------------------------------------------------------------- prompt.md

    /** {@code prompt.md} 是本技能唯一的文案源：读不到 / 缺键 = 明确报错，不用兜底文案。 */
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
