import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

import sair.v4.ai.Msg;
import sair.v4.ai.Res;
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

    /**
     * 声明本技能唯一的 op：<b>单动作工具</b> ⇒ op 就是工具名 {@code web}（{@code action} 传空串）。
     */
    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        ops.add(TOOL, "", "抓网页正文（JSON 自动美化 / HTML 去标签，超长截断）");
        ops.add(TOOL, "dom", "无头 Edge 取执行完 JS 的 DOM 并转成文本");
        ops.add(TOOL, "shot", "无头 Edge 给网页截图存到 files/web/（只要文件）");
        ops.add(TOOL, "look", "无头 Edge 截图 + 直接让会看图的模型说清这个网页长什么样");
        ops.add(TOOL, "nav", "让 Web 容器里的页面跳到某个网址（保持会话）");
        ops.add(TOOL, "act", "在 Web 容器当前页面里执行一段 JS（点击/填表/读状态）");
        ops.add(TOOL, "close", "关掉 Web 容器（无头 Edge）");
        ops.add(TOOL, "fetch", "用 DeepSeek 官方 Fetch 接口抓网页（返回 Markdown 正文）");
    }

    @Override
    public Object call(Args a, Host h) {
        Cfg c = Cfg.load(h);
        if (c.bad != null) return c.bad;

        // op 判定：本工具是单动作工具 ⇒ op 就是工具名 `web`（技能管控表按它放行/封禁）。
        // 没授权（账本里那行没给身份）= 不可用；她自己（SYSTEM）与主人不受表影响，恒全权。
        String deny = h == null ? null : h.need("web");
        if (deny != null) return deny;

        String url = Str.trim(a.str("url"));
        String opNow = Str.lower(Str.trim(a.str("op", "text")));
        if (opNow.isEmpty()) opNow = "text";
        boolean needUrl = "text".equals(opNow) || "nav".equals(opNow);
        if (url.isEmpty() && needUrl) return a.missing("url");
        if (!url.isEmpty()) {
            String low = Str.lower(url);
            if (!low.startsWith("http://") && !low.startsWith("https://")) {
                if (low.contains("://")) {
                    return c.t("拒绝协议", "url", Str.cut(url, 200));
                }
                url = "http://" + url;
            }
        }

        // ★ 阶段一：op 分发。text（默认）= 原来的纯 HTTP 抓取；dom / shot 走无头 Edge。
        String op = Str.lower(Str.trim(a.str("op", "text")));
        if (op.isEmpty()) op = "text";
        if ("dom".equals(op)) return dom(a, h, url);
        if ("shot".equals(op)) return shot(a, h, url);
        if ("look".equals(op)) return look(a, h, url);
        if ("close".equals(op)) return close(h);
        if ("nav".equals(op)) return nav(a, h, url);
        if ("act".equals(op)) return act(a, h);
        if ("fetch".equals(op)) return dsFetch(a, h, url);
        if (!"text".equals(op)) return "[web] ok=0 不支持的 op：" + op + "（只有 text / dom / shot / look / nav / act / close）";

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
    // ---------------------------------------------------------------- 无头浏览器（op=dom / op=shot）

    /** Windows 上 Edge 的两个标准安装位置。 */
    static final String[] WIN_BROWSERS = {
        "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
        "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
    };

    /**
     * 找出浏览器可执行文件。优先级：<b>调用参数 {@code browser}</b> &gt; 本技能 {@code prompt.md} 的
     * 「浏览器:」一行 &gt; Windows 标准安装位置。
     *
     * <p>为什么必须先看参数/配置：<b>Linux 不自带 Edge</b>，各发行版的 Chromium 路径又五花八门
     * （{@code /usr/bin/chromium}、{@code /usr/bin/chromium-browser}、{@code snap} 里的、
     * {@code google-chrome} …）。猜路径是错的，让调用者指明才对。</p>
     */
    static String edgePath(Host h, Args a) {
        String given = Str.trim(a.str("browser", ""));
        if (!Str.blank(given)) {
            File f = new File(given);
            return f.isFile() ? f.getAbsolutePath() : "!" + given;
        }
        String cfg = "";
        try {
            String v = Cfg.load(h).raw("浏览器");
            if (v != null) cfg = Str.trim(v);
        } catch (Throwable ignored) { }
        if (!Str.blank(cfg)) {
            File f = new File(cfg);
            return f.isFile() ? f.getAbsolutePath() : "!" + cfg;
        }
        for (String p : WIN_BROWSERS) if (new File(p).isFile()) return p;
        return "";
    }

    /** 找不到浏览器时回给调用者的那句话：<b>说清自己在哪个系统、以及去哪指明路径</b>。 */
    static String noBrowser(Host h, String bad) {
        String os = System.getProperty("os.name", "?");
        String lowOs = Str.lower(os);
        StringBuilder sb = new StringBuilder();
        sb.append("[web] ok=0 ");
        if (!Str.blank(bad)) {
            sb.append("你指的浏览器不存在：").append(Str.cut(bad, 200)).append("。\n");
        } else if (lowOs.contains("linux")) {
            sb.append("这台是 Linux，而 Linux 不自带 Edge —— 我找不到任何可用的浏览器可执行文件。\n");
        } else if (lowOs.contains("mac")) {
            sb.append("这台是 macOS，我没找到 Chromium 内核的浏览器可执行文件。\n");
        } else {
            sb.append("这台机器上没找到 Edge/Chromium 的可执行文件。\n");
        }
        sb.append("请指明「AI 浏览器」在哪，两种方式：\n");
        sb.append("  1) 调用时带上参数：browser=\"/usr/bin/chromium\"（或你机器上的真实路径）\n");
        sb.append("  2) 在 skills/网页抓取/prompt.md 里写一行「浏览器: /usr/bin/chromium」\n");
        sb.append("（当前 os.name=").append(os).append("；op=text 不需要浏览器，仍可用）");
        return sb.toString();
    }

    /** 本技能的落盘目录 {@code <files>/web/}（截图、DOM 快照、浏览器 profile 都在这里）。 */
    static File webDir(Host h) {
        if (h == null || h.conf() == null || h.conf().filesDir() == null) return null;
        File d = new File(h.conf().filesDir(), "web");
        if (!d.isDirectory() && !d.mkdirs()) return null;
        return d;
    }

    static String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new java.util.Date());
    }

    /**
     * 开浏览器前用基板的 {@link NetGuard} 判一次地址（<b>只判、不发请求</b>）。
     *
     * <p>这条是必需的：浏览器自己解析域名、自己跟重定向，{@code Http.getGuarded} 那道闸门管不到它 ——
     * 不在这里拦，{@code op=shot} 就成了打穿内网的通道。</p>
     */
    static String netDeny(String url) {
        try {
            sair.v4.kit.NetGuard.Verdict v = sair.v4.kit.NetGuard.check(url);
            if (v != null && !v.ok()) return "[web] ok=0 这个地址落在内部/私有网段，拒绝：" + v;
        } catch (Throwable e) {
            return "[web] ok=0 URL 校验异常：" + e;
        }
        return null;
    }

    static final class Run {
        int code;
        String out = "";
        String err = "";
    }

    static Thread drain(final java.io.InputStream in, final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.InputStreamReader r = new java.io.InputStreamReader(in, "UTF-8");
                    char[] buf = new char[8192];
                    int n;
                    while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                } catch (Throwable ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 跑一次 Edge。<b>不走 shell</b>（{@link ProcessBuilder} + 参数数组）：
     * URL 是模型给的、可能含 {@code &} {@code '} {@code |}，走 {@code cmd /c} 就是命令注入洞。
     */
    static Run runEdge(List<String> args, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder(args).start();
            final StringBuilder o = new StringBuilder();
            final StringBuilder e = new StringBuilder();
            Thread to = drain(p.getInputStream(), o);
            Thread te = drain(p.getErrorStream(), e);
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            to.join(5000L);
            te.join(5000L);
            Run r = new Run();
            r.code = p.exitValue();
            r.out = o.toString();
            r.err = e.toString();
            return r;
        } catch (Throwable t) {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Throwable ignored) { }
            }
            return null;
        }
    }

    static List<String> baseArgs(String edge, Host h) {
        List<String> a = new ArrayList<String>();
        a.add(edge);
        a.add("--headless=new");
        a.add("--disable-gpu");
        a.add("--no-first-run");
        a.add("--no-default-browser-check");
        a.add("--disable-sync");
        a.add("--disable-extensions");
        a.add("--mute-audio");
        a.add("--user-data-dir=" + new File(webDir(h), "profile").getAbsolutePath());
        a.add("--window-size=1280,900");
        return a;
    }

    /** op=dom：执行完 JS 的 DOM → 文本（并留一份原始 HTML 快照）。 */
    static Object dom(Args a, Host h, String url) {
        String deny = h == null ? null : h.need("web.dom");
        if (deny != null) return deny;
        if (Str.blank(url)) return domLive(a, h);
        String bad = netDeny(url);
        if (bad != null) return bad;
        String edge = edgePath(h, a);
        if (edge.isEmpty() || edge.startsWith("!")) {
            return noBrowser(h, edge.startsWith("!") ? edge.substring(1) : "");
        }
        File dir = webDir(h);
        if (dir == null) return "[web] ok=0 读不到/建不了数据目录";
        List<String> args = baseArgs(edge, h);
        args.add("--virtual-time-budget=8000");
        args.add("--dump-dom");
        args.add(url);
        Run r = runEdge(args, 75000L);
        if (r == null) return "[web] ok=0 启动浏览器失败（超时或异常）";
        String html = r.out == null ? "" : r.out;
        if (r.code != 0 && html.trim().isEmpty()) {
            return "[web] ok=0 dump-dom 失败 exit=" + r.code + " " + Str.cut(Str.oneLine(r.err), 300);
        }
        int max = a.i("max", DEF_MAX);
        if (max <= 0) max = DEF_MAX;
        if (max > CAP_MAX) max = CAP_MAX;
        String text = a.b("raw", false) ? html : htmlToText(html);
        if (text == null) text = "";
        text = text.trim();
        File keep = new File(dir, "dom-" + stamp() + ".html");
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(keep);
            try { fo.write(html.getBytes("UTF-8")); } finally { fo.close(); }
        } catch (Throwable ignored) {
        }
        String head = "[web] ok=1 op=dom url=" + Str.cut(url, 200)
                + " html_chars=" + html.length() + " text_chars=" + text.length()
                + " file=" + keep.getAbsolutePath() + " exit=" + r.code;
        if (text.isEmpty()) {
            return head + "\n（DOM 里没有可读文本 —— 页面可能全靠画布/图片。改用 op=shot 截图再 screen(op=see)）";
        }
        if (text.length() > max) {
            return head + "\n" + text.substring(0, max) + "\n…（已截断，原文 " + text.length() + " 字符）";
        }
        return head + "\n" + text;
    }

    /** op=shot：给网页截图落盘，交给 screen 技能的 see 去看。 */
    static Object shot(Args a, Host h, String url) {
        String deny = h == null ? null : h.need("web.shot");
        if (deny != null) return deny;
        if (Str.blank(url)) return shotLive(a, h);
        String bad = netDeny(url);
        if (bad != null) return bad;
        String edge = edgePath(h, a);
        if (edge.isEmpty() || edge.startsWith("!")) {
            return noBrowser(h, edge.startsWith("!") ? edge.substring(1) : "");
        }
        File dir = webDir(h);
        if (dir == null) return "[web] ok=0 读不到/建不了数据目录";
        File png = new File(dir, "shot-" + stamp() + ".png");
        List<String> args = baseArgs(edge, h);
        args.add("--virtual-time-budget=8000");
        args.add("--hide-scrollbars");
        args.add("--screenshot=" + png.getAbsolutePath());
        args.add(url);
        Run r = runEdge(args, 75000L);
        if (r == null) return "[web] ok=0 启动浏览器失败（超时或异常）";
        if (!png.isFile() || png.length() <= 0L) {
            return "[web] ok=0 截图没落盘 exit=" + r.code + " " + Str.cut(Str.oneLine(r.err), 300);
        }
        return "[web] ok=1 op=shot url=" + Str.cut(url, 200)
                + " file=" + png.getAbsolutePath() + " bytes=" + png.length()
                + " exit=" + r.code
                + "\n下一步：screen(op=see, file=\"" + png.getAbsolutePath() + "\") 就能看懂这张网页";
    }

    /**
     * op=look：截图 + <b>直接识图</b>。
     *
     * <p>刻意<b>不依赖 {@code screen} 技能</b>：网页识图用的是 Edge 自己的渲染产物（一张 PNG），
     * 跟"抓屏幕"那条路完全无关 —— 屏幕那条链在远程桌面断开时会抓到纯色，而这条链不受影响。</p>
     */
    static Object look(Args a, Host h, String url) {
        String deny = h == null ? null : h.need("web.look");
        if (deny != null) return deny;
        String mv = Str.lower(Str.trim(h.conf() == null ? "auto" : h.conf().get("mediaVision", "auto")));
        if ("no".equals(mv) || "off".equals(mv) || "false".equals(mv)) {
            return "[web] ok=0 图片能力被关（mediaVision=" + mv + "），改用 op=shot 拿文件";
        }
        String fact = String.valueOf(shot(a, h, url));
        if (!fact.startsWith("[web] ok=1")) return fact;
        String path = "";
        int i = fact.indexOf(" file=");
        if (i >= 0) {
            int j = fact.indexOf(" bytes=", i);
            if (j > i) path = fact.substring(i + 6, j).trim();
        }
        if (path.isEmpty()) return fact;
        File f = new File(path);
        if (!f.isFile()) return fact;
        String ask = Str.trim(a.str("ask", ""));
        if (ask.isEmpty()) {
            ask = "这是一个网页的截图。说清楚这个页面是什么、主要区域有什么内容、能读到的文字（标题/按钮/关键信息原样转写）。看不到内容就直说。";
        }
        try {
            byte[] raw = readAll(f);
            if (raw == null) return fact + "\n[web] ok=0 读不到截图文件";
            String dataUrl = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(raw);
            List<String> imgs = new ArrayList<String>();
            imgs.add(dataUrl);
            com.google.gson.JsonArray msgs = new com.google.gson.JsonArray();
            msgs.add(Msg.system("你是主 Agent 的眼睛：只描述这张网页截图里真实可见的东西，不推测意图。"));
            msgs.add(Msg.userWithImages(ask, imgs));
            Res r = h.ai().vision(msgs, new JsonObject());
            if (r == null || !r.ok()) return fact + "\n[web] ok=0 看图模型没看成：" + (r == null ? "null" : r.error);
            String body = Str.trim(r.text());
            if (body.isEmpty()) return fact + "\n[web] ok=0 看图模型没给出内容";
            return fact + "\n--- 看见了 ---\n" + body;
        } catch (Throwable e) {
            return fact + "\n[web] ok=0 识图异常：" + e;
        }
    }

    static byte[] readAll(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Web 容器（持久浏览器 + CDP）

    static final int PORT_MIN = 9222;
    static final int PORT_MAX = 9232;

    static File portFile(Host h) { return new File(webDir(h), "browser.port"); }
    static File pidFile(Host h) { return new File(webDir(h), "browser.pid"); }

    static void writeText(File f, String s) {
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            try { fo.write(s.getBytes("UTF-8")); } finally { fo.close(); }
        } catch (Throwable ignored) { }
    }

    static long pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return ((Number) f.get(p)).longValue();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 确保有一个能用的 Web 容器（无头 Edge + 调试口），返回调试口端口；失败 0。
     *
     * <p><b>跨调用复用</b>：端口写 {@code files/web/browser.port}、进程号写 {@code browser.pid}。
     * 端口还活着就直接用 —— 登录 cookie、页面状态、滚动位置都能留住。</p>
     */
    static int ensure(Host h, String edge) {
        File pf = portFile(h);
        int p = 0;
        try { if (pf.isFile()) p = Integer.parseInt(Fs.read(pf, "0").trim()); } catch (Throwable ignored) { }
        if (p > 0 && Str.has(Cdp.version(p))) return p;
        for (int q = PORT_MIN; q <= PORT_MAX; q++) {
            if (Str.has(Cdp.version(q))) continue;
            Process pr = null;
            try {
                List<String> args = baseArgs(edge, h);
                args.add("--remote-debugging-port=" + q);
                args.add("--remote-allow-origins=*");
                args.add("about:blank");
                pr = new ProcessBuilder(args).start();
            } catch (Throwable t) {
                return 0;
            }
            long dl = System.currentTimeMillis() + 25000L;
            while (System.currentTimeMillis() < dl) {
                if (Str.has(Cdp.version(q))) {
                    writeText(pf, String.valueOf(q));
                    writeText(pidFile(h), String.valueOf(pidOf(pr)));
                    return q;
                }
                try { Thread.sleep(400L); } catch (Throwable ignored) { }
            }
            try { pr.destroyForcibly(); } catch (Throwable ignored) { }
        }
        return 0;
    }

    /** 一个页面会话（用完必须 close）。 */
    static final class Session {
        int port;
        Cdp.Ws sock;
        int seq = 1;

        JsonObject cmd(String method, JsonObject params, int timeout) throws java.io.IOException {
            return Cdp.call(sock, seq++, method, params, timeout);
        }

        void close() {
            if (sock != null) sock.close();
        }
    }

    /** 打开（或复用）容器并接上第一个页面；url 非空就先导航。失败返回 null，原因写进 lastErr。 */
    static Session openSession(Host h, Args a, String url, StringBuilder lastErr) {
        String edge = edgePath(h, a);
        if (edge.isEmpty() || edge.startsWith("!")) {
            lastErr.append(noBrowser(h, edge.startsWith("!") ? edge.substring(1) : ""));
            return null;
        }
        int port = ensure(h, edge);
        if (port <= 0) {
            lastErr.append("[web] ok=0 Web 容器起不来（调试口 " + PORT_MIN + "~" + PORT_MAX + " 都被占，或浏览器启动超时）");
            return null;
        }
        String ws = Cdp.firstPageWs(port);
        if (!Str.has(ws)) {
            lastErr.append("[web] ok=0 容器起来了但拿不到页面（/json/list 里没有 page）");
            return null;
        }
        Session s = null;
        try {
            s = new Session();
            s.port = port;
            s.sock = Cdp.Ws.open(ws, 15000);
            s.cmd("Page.enable", null, 8000);
            if (Str.has(url)) {
                JsonObject nav = new JsonObject();
                nav.addProperty("url", url);
                s.cmd("Page.navigate", nav, 20000);
                waitReady(s, 20000);
            }
            return s;
        } catch (Throwable t) {
            if (s != null) s.close();
            lastErr.append("[web] ok=0 连不上调试口：" + t);
            return null;
        }
    }

    static void waitReady(Session s, int timeoutMs) {
        long dl = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < dl) {
            try {
                JsonObject p = new JsonObject();
                p.addProperty("expression", "document.readyState");
                p.addProperty("returnByValue", true);
                if ("complete".equals(Cdp.evalResult(s.cmd("Runtime.evaluate", p, 5000)))) return;
            } catch (Throwable ignored) { }
            try { Thread.sleep(300L); } catch (Throwable ignored) { }
        }
    }

    /** 当前页面的地址与标题，压成一行。 */
    static String where(Session s) {
        try {
            JsonObject p = new JsonObject();
            p.addProperty("expression", "location.href + ' | ' + document.title");
            p.addProperty("returnByValue", true);
            String v = Cdp.evalResult(s.cmd("Runtime.evaluate", p, 8000));
            return v.replace("\r", " ").replace("\n", " ").trim();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** op=nav：跳转（保持容器与登录态）。 */
    static Object nav(Args a, Host h, String url) {
        String deny = h == null ? null : h.need("web.nav");
        if (deny != null) return deny;
        String bad = netDeny(url);
        if (bad != null) return bad;
        StringBuilder err = new StringBuilder();
        Session s = openSession(h, a, url, err);
        if (s == null) return err.toString();
        try {
            return "[web] ok=1 op=nav url=" + Str.cut(url, 200) + " 页面：" + Str.cut(where(s), 200);
        } finally {
            s.close();
        }
    }

    /** op=act：在当前页面里执行一段 JS（点击 / 填表 / 读状态）。 */
    static Object act(Args a, Host h) {
        String deny = h == null ? null : h.need("web.act");
        if (deny != null) return deny;
        String js = a.str("js", "");
        if (Str.trim(js).isEmpty()) return "[web] ok=0 act 要给 js（一段在页面里执行的 JS）";
        if (js.length() > 20000) return "[web] ok=0 js 太长（上限 20000 字符）";
        StringBuilder err = new StringBuilder();
        Session s = openSession(h, a, "", err);
        if (s == null) return err.toString();
        try {
            JsonObject p = new JsonObject();
            p.addProperty("expression", js);
            p.addProperty("returnByValue", true);
            p.addProperty("awaitPromise", true);
            p.addProperty("userGesture", true);
            JsonObject r = s.cmd("Runtime.evaluate", p, 30000);
            String e = Cdp.err(r);
            if (Str.has(e)) return "[web] ok=0 CDP 报错：" + e;
            return "[web] ok=1 op=act 页面：" + Str.cut(where(s), 160) + "\n结果：" + Str.cut(Cdp.evalResult(r), 2000);
        } catch (Throwable t) {
            return "[web] ok=0 act 失败：" + t;
        } finally {
            s.close();
        }
    }

    /** op=close：关掉容器（按 pid 杀，不走 shell）。 */
    static Object close(Host h) {
        String deny = h == null ? null : h.need("web.close");
        if (deny != null) return deny;
        File pf = portFile(h);
        File pidf = pidFile(h);
        long pid = 0L;
        int port = 0;
        try { if (pidf.isFile()) pid = Long.parseLong(Fs.read(pidf, "0").trim()); } catch (Throwable ignored) { }
        try { if (pf.isFile()) port = Integer.parseInt(Fs.read(pf, "0").trim()); } catch (Throwable ignored) { }
        boolean alive = port > 0 && Str.has(Cdp.version(port));
        String killed = "";
        // 首选：给【浏览器级】调试口发一条 Browser.close（不需要 pid，也不需要 shell）
        if (alive) {
            try {
                String v = Cdp.http("http://127.0.0.1:" + port + "/json/version", 2000);
                JsonObject vo = J.obj(v);
                String bws = vo == null ? "" : J.s(vo, "webSocketDebuggerUrl", "");
                if (Str.has(bws)) {
                    Cdp.Ws w = Cdp.Ws.open(bws, 5000);
                    JsonObject req = new JsonObject();
                    req.addProperty("id", 1);
                    req.addProperty("method", "Browser.close");
                    req.add("params", new JsonObject());
                    w.sendText(req.toString());
                    try { Thread.sleep(800L); } catch (Throwable ignored) { }
                    w.close();
                    killed = "Browser.close";
                }
            } catch (Throwable ignored) { }
        }
        if (!Str.has(killed) && pid > 0L) {
            try {
                String os = Str.lower(System.getProperty("os.name", ""));
                List<String> cmd = new ArrayList<String>();
                if (os.contains("win")) {
                    cmd.add("taskkill");
                    cmd.add("/PID");
                    cmd.add(String.valueOf(pid));
                    cmd.add("/T");
                    cmd.add("/F");
                } else {
                    cmd.add("kill");
                    cmd.add("-9");
                    cmd.add(String.valueOf(pid));
                }
                Process pr = new ProcessBuilder(cmd).start();
                pr.waitFor();
                killed = "pid=" + pid;
            } catch (Throwable t) {
                killed = "kill 失败：" + t;
            }
        }
        try { pidf.delete(); } catch (Throwable ignored) { }
        try { pf.delete(); } catch (Throwable ignored) { }
        return "[web] ok=1 op=close 容器之前" + (alive ? "是活的" : "已不在")
                + (Str.has(killed) ? ("，已终止 " + killed) : "，没有记录到 pid");
    }

    /** op=shot 不给 url：截容器里【当前】那个页面（用 CDP，不重启浏览器）。 */
    static Object shotLive(Args a, Host h) {
        StringBuilder err = new StringBuilder();
        Session s = openSession(h, a, "", err);
        if (s == null) return err.toString();
        try {
            JsonObject p = new JsonObject();
            p.addProperty("format", "png");
            JsonObject r = s.cmd("Page.captureScreenshot", p, 30000);
            String e = Cdp.err(r);
            if (Str.has(e)) return "[web] ok=0 截图失败：" + e;
            JsonObject result = r.has("result") ? J.obj(r.get("result").toString()) : null;
            String b64 = result == null ? "" : J.s(result, "data", "");
            if (!Str.has(b64)) return "[web] ok=0 截图返回里没有图片数据";
            File png = new File(webDir(h), "shot-" + stamp() + ".png");
            if (!Cdp.writeB64(b64, png)) return "[web] ok=0 截图写不进文件：" + png;
            return "[web] ok=1 op=shot url=(容器当前页) " + Str.cut(where(s), 160)
                    + " file=" + png.getAbsolutePath() + " bytes=" + png.length()
                    + "\n下一步：screen(op=see, file=\"" + png.getAbsolutePath() + "\") 或 op=look";
        } catch (Throwable t) {
            return "[web] ok=0 截图失败：" + t;
        } finally {
            s.close();
        }
    }

    /** op=dom 不给 url：取容器里【当前】那个页面的 DOM。 */
    static Object domLive(Args a, Host h) {
        StringBuilder err = new StringBuilder();
        Session s = openSession(h, a, "", err);
        if (s == null) return err.toString();
        try {
            JsonObject p = new JsonObject();
            p.addProperty("expression", "document.documentElement.outerHTML");
            p.addProperty("returnByValue", true);
            JsonObject r = s.cmd("Runtime.evaluate", p, 30000);
            String e = Cdp.err(r);
            if (Str.has(e)) return "[web] ok=0 CDP 报错：" + e;
            String html = Cdp.evalResult(r);
            if (html == null) html = "";
            int max = a.i("max", DEF_MAX);
            if (max <= 0) max = DEF_MAX;
            if (max > CAP_MAX) max = CAP_MAX;
            String text = a.b("raw", false) ? html : htmlToText(html);
            if (text == null) text = "";
            text = text.trim();
            File keep = new File(webDir(h), "dom-" + stamp() + ".html");
            try {
                java.io.FileOutputStream fo = new java.io.FileOutputStream(keep);
                try { fo.write(html.getBytes("UTF-8")); } finally { fo.close(); }
            } catch (Throwable ignored) { }
            String head = "[web] ok=1 op=dom url=(容器当前页) " + Str.cut(where(s), 160)
                    + " html_chars=" + html.length() + " text_chars=" + text.length()
                    + " file=" + keep.getAbsolutePath();
            if (text.isEmpty()) return head + "\n（当前页 DOM 里没有可读文本，改用 op=shot / op=look）";
            if (text.length() > max) return head + "\n" + text.substring(0, max) + "\n…（已截断，原文 " + text.length() + " 字符）";
            return head + "\n" + text;
        } catch (Throwable t) {
            return "[web] ok=0 取 DOM 失败：" + t;
        } finally {
            s.close();
        }
    }

    // ---------------------------------------------------------------- DeepSeek 官方 Fetch

    /** DeepSeek 官方接口的 Base URL。 */
    static final String DS_BASE = "https://api.deepseek.com";

    /** 取 API Key（走基板配置，不落盘、不打印）。 */
    static String apiKey(Host h) {
        try {
            if (h == null || h.conf() == null) return "";
            String k = h.conf().get("apiKey", "");
            return k == null ? "" : Str.trim(k);
        } catch (Throwable t) {
            return "";
        }
    }

    /** POST 一个 JSON 到 DeepSeek，返回响应体；失败返回以 {@code !} 开头的错误说明。 */
    static String dsPost(String path, JsonObject body, String key, int timeoutMs) {
        java.util.Map<String, String> hdrs = Http.headers();
        hdrs.put("Authorization", "Bearer " + key);
        hdrs.put("Content-Type", "application/json");
        hdrs.put("Accept", "application/json");
        Http.Res r = Http.postJson(DS_BASE + path, body.toString(), hdrs, timeoutMs);
        if (r == null) return "!null response";
        if (r.code == 0) return "!" + (Str.has(r.error) ? r.error : "no response");
        String b = r.body == null ? "" : r.body;
        if (r.code != 200) {
            String msg = b;
            JsonObject o = J.obj(b);
            if (o != null && o.has("error")) {
                JsonObject e = J.obj(o.get("error").toString());
                if (e != null) msg = J.s(e, "message", b);
            }
            return "!HTTP " + r.code + " " + Str.cut(Str.oneLine(msg), 300);
        }
        return b;
    }

    /**
     * op=fetch：DeepSeek 官方 Fetch（{@code POST /v1/tools/fetch}），返回页面标题 + <b>Markdown 正文</b>。
     *
     * <p>比 {@code op=text}（自己抓 HTML 再去标签）强的地方：正文是**结构化 Markdown**（标题层级、
     * 列表、表格、代码块都保留），而且不用管反爬、编码、SPA 空壳这些事。</p>
     *
     * <p>计费：官方按**成功请求次数**计（约 5 元/1000 次），所以这个 op 单独判权、默认只给主人与她本人。</p>
     */
    static Object dsFetch(Args a, Host h, String url) {
        String deny = h == null ? null : h.need("web.fetch");
        if (deny != null) return deny;
        if (Str.blank(url)) return a.missing("url");
        String bad = netDeny(url);
        if (bad != null) return bad;
        String key = apiKey(h);
        if (key.isEmpty()) return "[web] ok=0 配置里没有 apiKey，DeepSeek 官方接口用不了（op=text 仍然可用）";
        JsonObject body = new JsonObject();
        body.addProperty("url", url);
        String resp = dsPost("/v1/tools/fetch", body, key, 60000);
        if (resp.startsWith("!")) {
            return "[web] ok=0 DeepSeek Fetch 失败：" + resp.substring(1) + "（可退回 op=text 自己抓）";
        }
        JsonObject o = J.obj(resp);
        if (o == null) return "[web] ok=0 DeepSeek Fetch 回了不可解析的内容：" + Str.cut(resp, 300);
        String title = J.s(o, "title", "");
        String md = J.s(o, "content", "");
        if (md == null) md = "";
        int max = a.i("max", DEF_MAX);
        if (max <= 0) max = DEF_MAX;
        if (max > CAP_MAX) max = CAP_MAX;
        String head = "[web] ok=1 op=fetch via=deepseek url=" + Str.cut(url, 200)
                + " title=" + Str.cut(title, 120) + " chars=" + md.length();
        if (md.trim().isEmpty()) return head + "\n（接口回来了但没有正文：可能该页没有可提取的 Markdown 正文）";
        if (md.length() > max) return head + "\n" + md.substring(0, max) + "\n…（已截断，原文 " + md.length() + " 字符）";
        return head + "\n" + md;
    }

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

        /** 取一个原始配置值（不套模板）；没有就回 null。 */
        String raw(String k) { return v.get(k); }

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
