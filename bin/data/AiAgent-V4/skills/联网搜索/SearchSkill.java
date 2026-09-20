import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.v4.kit.Http;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 联网搜索（工具 {@code search}）：抓必应结果页，解析标题 / 链接 / 摘要。
 *
 * <p>jsoup 在 classpath 上就用 jsoup 解析，没有就回退内置正则解析 ——
 * 技能是运行期由基板编译的（{@code -classpath} 由宿主拼），因此不能假定一定能 import jsoup。</p>
 *
 * <p>无网、超时、被反爬、页面改版，一律返回一句可读说明，绝不抛异常。</p>
 */
public class SearchSkill implements Skill {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private static final Pattern BLOCK = Pattern.compile(
            "<li class=\"b_algo\"[\\s\\S]*?(?=<li class=\"b_algo\"|</ol>|</main>|<footer)");
    private static final Pattern LINK = Pattern.compile(
            "<h2[^>]*>[\\s\\S]*?<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>");
    private static final Pattern SNIP = Pattern.compile("<p[^>]*>([\\s\\S]*?)</p>");

    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        ops.add("search", "", "必应联网搜索");
    }

    @Override
    public Object call(Args a, Host h) {
        // 技能管控表：本技能是单动作工具（op = search）→ 入口判一次自己的 op。
        // 空（没写这条，或写了没给身份）= 未授权 = 不可用；放行时返回 null，被拒时把拒绝原文原样回给模型。
        // 她自己（SYSTEM）与主人恒全权，不受管控表影响。
        String deny = h == null ? null : h.need("search");
        if (deny != null) return deny;
        String q = Str.trim(a.str("query"));
        if (q.isEmpty()) return a.missing("query");
        int n = a.i("count", 5);
        if (n <= 0) n = 5;
        if (n > 10) n = 10;

        String url = "https://www.bing.com/search?q=" + enc(q) + "&setlang=zh-CN&ensearch=0";
        Map<String, String> hdrs = Http.headers();
        hdrs.put("User-Agent", UA);
        hdrs.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        hdrs.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6");

        // 带 SSRF 闸门的出网入口（基板 Http.getGuarded：环回/私网/链路本地/云元数据一律拒绝，
        // 且每一跳重定向复检；判定本体在 kit/NetGuard，技能改不动）—— 与同族「网页抓取」同口径。
        Http.Res r = Http.getGuarded(url, hdrs, 15000);
        if (r.denied()) {
            // 拒绝原文由基板给（blocked=结构性代号 / blockedHost / blockedReason），本技能没有 prompt.md，
            // 这句按本文件既有的硬编码口径写（收拢进 prompt.md 属另一笔改动）。
            return "[search] 这个地址指向内网（" + r.blockedHost + " · " + r.blockedReason
                    + "），按安全口径不搜：" + url;
        }
        if (!r.ok()) {
            return "[search] 搜索失败：" + why(r) + "（GET " + url + "）";
        }
        List<String[]> hits = parse(r.body);
        if (hits.isEmpty()) {
            return "[search] 没有解析出结果：必应可能返回了验证页或改了页面结构（拿到 "
                    + r.body.length() + " 字节）。换关键词再试，或改用 web 工具直接抓具体网址。";
        }
        StringBuilder sb = new StringBuilder();
        int show = Math.min(hits.size(), n);
        sb.append("必应搜索 ").append(q).append("：").append(show).append(" 条\n");
        for (int i = 0; i < show; i++) {
            String[] t = hits.get(i);
            sb.append(i + 1).append(". ").append(Str.nz(t[0])).append("\n");
            sb.append("   ").append(Str.nz(t[1])).append("\n");
            if (Str.has(t[2])) sb.append("   ").append(t[2]).append("\n");
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- 解析

    private static List<String[]> parse(String html) {
        List<String[]> out = byJsoup(html);
        if (out != null && !out.isEmpty()) return out;
        return byRegex(html);
    }

    /** 反射调用 jsoup（classpath 上没有就返回 null）。 */
    private static List<String[]> byJsoup(String html) {
        try {
            Class<?> jc = Class.forName("org.jsoup.Jsoup");
            Object doc = jc.getMethod("parse", String.class, String.class)
                    .invoke(null, html, "https://www.bing.com/");
            Class<?> ec = Class.forName("org.jsoup.nodes.Element");
            Object items = ec.getMethod("select", String.class).invoke(doc, "li.b_algo");
            java.lang.reflect.Method size = items.getClass().getMethod("size");
            java.lang.reflect.Method get = items.getClass().getMethod("get", int.class);
            java.lang.reflect.Method text = ec.getMethod("text");
            java.lang.reflect.Method attr = ec.getMethod("attr", String.class);
            java.lang.reflect.Method first = ec.getMethod("selectFirst", String.class);
            int count = ((Integer) size.invoke(items)).intValue();
            List<String[]> out = new ArrayList<String[]>();
            for (int i = 0; i < count && out.size() < 20; i++) {
                Object li = get.invoke(items, Integer.valueOf(i));
                if (li == null) continue;
                Object link = first.invoke(li, "h2 a");
                if (link == null) continue;
                String title = Str.oneLine(String.valueOf(text.invoke(link)));
                String href = Str.trim(String.valueOf(attr.invoke(link, "href")));
                if (title.isEmpty() || href.isEmpty()) continue;
                Object p = first.invoke(li, "div.b_caption p");
                if (p == null) p = first.invoke(li, "p");
                String snip = p == null ? "" : Str.oneLine(String.valueOf(text.invoke(p)));
                out.add(new String[] {title, realUrl(href), snip});
            }
            return out;
        } catch (Throwable t) {
            return null;    // 没有 jsoup / 版本不兼容 → 交给正则
        }
    }

    /** 内置正则解析（不依赖任何第三方库）。 */
    private static List<String[]> byRegex(String html) {
        List<String[]> out = new ArrayList<String[]>();
        Matcher b = BLOCK.matcher(html);
        while (b.find() && out.size() < 20) {
            String block = b.group();
            Matcher l = LINK.matcher(block);
            if (!l.find()) continue;
            String title = text(l.group(2));
            String href = realUrl(unescape(l.group(1)));
            if (title.isEmpty() || href.isEmpty()) continue;
            String snip = "";
            Matcher s = SNIP.matcher(block);
            if (s.find()) snip = text(s.group(1));
            out.add(new String[] {title, href, snip});
        }
        return out;
    }

    // ---------------------------------------------------------------- 小工具

    /** 去标签 + 解实体 + 折成单行。 */
    private static String text(String fragment) {
        if (fragment == null) return "";
        String s = fragment.replaceAll("(?is)<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", " ");
        s = s.replaceAll("<[^>]*>", " ");
        return Str.oneLine(unescape(s));
    }

    private static String unescape(String s) {
        if (s == null) return "";
        String t = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&ensp;", " ").replace("&emsp;", " ");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(t);
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
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 必应跳转链接 → 真实链接。 */
    private static String realUrl(String href) {
        String h = Str.trim(href);
        if (h.isEmpty() || h.indexOf("bing.com/ck/a") < 0) return h;
        int i = h.indexOf("u=a1");
        if (i < 0) return h;
        String b64 = h.substring(i + 4);
        int amp = b64.indexOf('&');
        if (amp >= 0) b64 = b64.substring(0, amp);
        try {
            while (b64.length() % 4 != 0) b64 = b64 + "=";
            String s = new String(java.util.Base64.getUrlDecoder().decode(b64), "UTF-8");
            if (s.startsWith("http")) return s;
        } catch (Exception ignored) {
        }
        return h;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String why(Http.Res r) {
        if (r == null) return "没有响应";
        if (Str.has(r.error)) return r.error;
        return "HTTP " + r.code;
    }
}
