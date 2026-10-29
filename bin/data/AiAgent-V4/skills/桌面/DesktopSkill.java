import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import sair.v4.auth.Caller;
import sair.v4.auth.OpList;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 桌面（工具 {@code desktop}）：用 UI Automation 看窗口里的元素、按【名字】操作。
 *
 * <p>为什么不用看图猜坐标：真机上 UIA 能直接给出元素名、控件类型、可用性、支持的 Pattern
 * （Invoke / Value / Select …）⇒ 「点保存按钮」变成「找名字含保存的 Button 并 Invoke」：
 * 不用猜坐标、不怕窗口挪动，读回来还是结构化文本（token 远小于一张图）。</p>
 *
 * <p>引擎是 {@code data/tools/uia.ps1}（PowerShell + .NET 自带 UIAutomation，<b>零新依赖</b>）。
 * JSON 走文件交换、不经过命令行：中文参数过 cmd 会被 GBK 破坏（真机实测：最大化 变成 鏈€澶у寲）。</p>
 *
 * <p><b>已知边界</b>：浏览器页面内容与 conhost 文本 UIA 拿不到（实测 Chrome 只有 13 个窗框元素）。
 * 浏览器请用 {@code web} 技能（CDP）；控制台正文请用 {@code exec} 直接跑命令，或 op=shot 截图再看。</p>
 */
public class DesktopSkill implements Skill {

    static final String TOOL = "desktop";
    static final String NAME = "桌面";
    static final String HALT_KEY = "desktop:halt";

    @Override
    public void declareOps(OpList ops) {
        ops.add(TOOL, "windows", "列出所有顶层窗口（pid / 类名 / 标题 / 位置 / kind）");
        ops.add(TOOL, "tree", "取一个窗口的元素树（名字 / 类型 / 坐标 / 可用 / 支持的 Pattern）");
        ops.add(TOOL, "act", "按【名字】操作元素：click(Invoke/Select) / set(写值) / focus");
        ops.add(TOOL, "read", "读窗口里的文本（元素名 + Value 值）");
        ops.add(TOOL, "shot", "只截某个窗口（比整屏省 token）");
        ops.add(TOOL, "halt", "一键停：立刻冻结全部桌面操作");
        ops.add(TOOL, "resume", "解除一键停");
    }

    @Override
    public Object call(Args a, Host h) {
        String op = Str.lower(Str.trim(a.str("op", "windows")));
        if (op.isEmpty()) op = "windows";
        String deny = h.need("desktop." + op);
        if (deny != null) return deny;
        Caller c = h.caller();
        if (c == null || (!c.master() && !c.system())) {
            return "[desktop] ok=0 桌面控制只给主人与她本人（当前身份：" + (c == null ? "?" : c.kind()) + "）";
        }
        if ("halt".equals(op)) {
            try { h.store().kvSet(HALT_KEY, "1"); } catch (Throwable ignored) { }
            store(h, "halt", "", "");
            return "[desktop] ok=1 已冻结：鼠标键盘与桌面操作全部拒绝，直到有人 resume";
        }
        if ("resume".equals(op)) {
            try { h.store().kvSet(HALT_KEY, ""); } catch (Throwable ignored) { }
            store(h, "resume", "", "");
            return "[desktop] ok=1 已解除冻结";
        }
        if (isHalted(h)) return "[desktop] ok=0 已冻结（有人说停）。要恢复得先 desktop op=resume";
        Cfg cfg = Cfg.load(h);
        if (!cfg.on) return "[desktop] ok=0 总闸没开：skills/" + NAME + "/prompt.md 里写一行 总闸: on";
        if (!budget(cfg)) return "[desktop] ok=0 桌面操作太频繁（每分钟 " + cfg.perMin + " 次），缓一缓";
        try {
            if ("windows".equals(op)) return windows(h, cfg);
            if ("tree".equals(op)) return tree(h, cfg, a);
            if ("act".equals(op)) return act(h, cfg, a);
            if ("read".equals(op)) return read(h, cfg, a);
            if ("shot".equals(op)) return shot(h, cfg, a);
            return "[desktop] ok=0 不支持的 op：" + op + "（windows/tree/act/read/shot/halt/resume）";
        } catch (Throwable t) {
            return "[desktop] ok=0 异常：" + Str.cut(String.valueOf(t), 240);
        }
    }
    // ---------------------------------------------------------------- 各 op

    static String windows(Host h, Cfg cfg) throws Exception {
        JsonObject o = run(h, cfg, "windows", null, null, 30000L);
        if (o == null || !J.b(o, "ok", false)) return "[desktop] ok=0 取窗口失败：" + (o == null ? "没有输出" : J.s(o, "error", "?"));
        JsonArray arr = o.getAsJsonArray("windows");
        int n = arr == null ? 0 : arr.size();
        StringBuilder sb = new StringBuilder("[desktop] ok=1 共 " + n + " 个窗口（用 pid 或 win=标题片段选中）");
        for (int i = 0; arr != null && i < n; i++) {
            JsonObject w = arr.get(i).getAsJsonObject();
            String title = J.s(w, "title", "");
            sb.append('\n').append(i + 1).append(". [pid=").append(J.i(w, "pid", 0)).append("] ")
                    .append(J.s(w, "class", ""))
                    .append("  ").append(title.isEmpty() ? "(无标题)" : Str.cut(title, 60))
                    .append("  kind=").append(J.s(w, "kind", "window"))
                    .append("  ").append(J.i(w, "x", 0)).append(',').append(J.i(w, "y", 0))
                    .append(' ').append(J.i(w, "w", 0)).append('x').append(J.i(w, "h", 0))
                    .append(J.b(w, "focused", false) ? "  ←前台" : "");
        }
        store(h, "windows", "", "count=" + n);
        return sb.toString();
    }

    static String tree(Host h, Cfg cfg, Args a) throws Exception {
        int pid = a.i("pid", 0);
        int max = a.i("max", 0) > 0 ? a.i("max", 0) : cfg.maxNodes;
        String title = Str.trim(a.str("win", ""));
        File wf = null;
        if (pid <= 0) {
            if (title.isEmpty()) return "[desktop] ok=0 要给 pid 或 win=（先用 op=windows 看有哪些窗口）";
            wf = utf8(h, "win", title);
        }
        StringBuilder cmd = new StringBuilder("powershell -NoProfile -ExecutionPolicy Bypass -File " + q(scriptFile(h, cfg)));
        cmd.append(" -Cmd tree");
        if (pid > 0) cmd.append(" -WinPid ").append(pid);
        if (wf != null) cmd.append(" -MatchFile ").append(q(wf.getAbsolutePath()));
        cmd.append(" -Max ").append(max);
        JsonObject o = runCmd(h, cmd.toString(), 45000L);
        if (o == null || !J.b(o, "ok", false)) return "[desktop] ok=0 取元素树失败：" + (o == null ? "没有输出" : J.s(o, "error", "?"));
        JsonObject w = o.getAsJsonObject("window");
        JsonArray nodes = o.getAsJsonArray("nodes");
        int n = nodes == null ? 0 : nodes.size();
        StringBuilder sb = new StringBuilder("[desktop] ok=1 窗口「" + Str.cut(J.s(w, "title", ""), 60) + "」 kind="
                + J.s(w, "kind", "") + " pid=" + J.i(w, "pid", 0) + "，元素 " + n + " 个");
        for (int i = 0; nodes != null && i < n; i++) {
            JsonObject x = nodes.get(i).getAsJsonObject();
            sb.append('\n').append(' ').append(J.i(x, "i", i)).append(". ")
                    .append(Str.cut(J.s(x, "type", ""), 18))
                    .append(" | ").append(Str.cut(J.s(x, "name", ""), 48));
            String pats = J.s(x, "patterns", "");
            if (Str.has(pats)) sb.append("  [").append(pats).append(']');
            if (!J.b(x, "enabled", true)) sb.append("  (禁用)");
        }
        sb.append("\n（要动手用 op=act target=名字 或 target=#序号；要看文字用 op=read）");
        store(h, "tree", J.s(w, "title", ""), "nodes=" + n);
        return sb.toString();
    }

    static String act(Host h, Cfg cfg, Args a) throws Exception {
        int pid = a.i("pid", 0);
        String title = Str.trim(a.str("win", ""));
        String target = Str.trim(a.str("target", ""));
        String action = Str.lower(Str.trim(a.str("action", "click")));
        String value = a.str("value", "");
        if (target.isEmpty()) return "[desktop] ok=0 要给 target=元素名字（或用 op=tree 看 #序号）";
        if (pid <= 0 && title.isEmpty()) return "[desktop] ok=0 要给 pid 或 win=";
        if (!"click".equals(action) && !"set".equals(action) && !"focus".equals(action)) return "[desktop] ok=0 action 只有 click / set / focus";
        File tf = utf8(h, "target", target);
        File vf = "set".equals(action) ? utf8(h, "value", value) : null;
        File wf = pid <= 0 ? utf8(h, "win", title) : null;
        StringBuilder cmd = new StringBuilder("powershell -NoProfile -ExecutionPolicy Bypass -File " + q(scriptFile(h, cfg)));
        cmd.append(" -Cmd act -Action ").append(action);
        if (pid > 0) cmd.append(" -WinPid ").append(pid);
        if (wf != null) cmd.append(" -MatchFile ").append(q(wf.getAbsolutePath()));
        cmd.append(" -TargetFile ").append(q(tf.getAbsolutePath()));
        if (vf != null) cmd.append(" -ValueFile ").append(q(vf.getAbsolutePath()));
        JsonObject o = runCmd(h, cmd.toString(), 30000L);
        if (o == null) return "[desktop] ok=0 没有输出（脚本没跑起来？确认 data/tools/uia.ps1 在）";
        if (!J.b(o, "ok", false)) return "[desktop] ok=0 操作失败：" + J.s(o, "error", "?") + " target=" + Str.cut(target, 40);
        JsonObject t = o.getAsJsonObject("target");
        JsonObject w = o.getAsJsonObject("window");
        String done = J.s(o, "done", "");
        store(h, action, target, "done=" + done + " pid=" + J.i(w, "pid", 0));
        return "[desktop] ok=1 action=" + action + " done=" + done
                + " target=「" + Str.cut(J.s(t, "name", ""), 40) + "」(" + J.s(t, "type", "") + ")"
                + " window=「" + Str.cut(J.s(w, "title", ""), 40) + "」";
    }

    static String read(Host h, Cfg cfg, Args a) throws Exception {
        int pid = a.i("pid", 0);
        int max = a.i("max", 0) > 0 ? a.i("max", 0) : cfg.maxNodes;
        String title = Str.trim(a.str("win", ""));
        if (pid <= 0 && title.isEmpty()) return "[desktop] ok=0 要给 pid 或 win=";
        File wf = pid <= 0 ? utf8(h, "win", title) : null;
        StringBuilder cmd = new StringBuilder("powershell -NoProfile -ExecutionPolicy Bypass -File " + q(scriptFile(h, cfg)));
        cmd.append(" -Cmd read");
        if (pid > 0) cmd.append(" -WinPid ").append(pid);
        if (wf != null) cmd.append(" -MatchFile ").append(q(wf.getAbsolutePath()));
        cmd.append(" -Max ").append(max);
        JsonObject o = runCmd(h, cmd.toString(), 45000L);
        if (o == null || !J.b(o, "ok", false)) return "[desktop] ok=0 读失败：" + (o == null ? "没有输出" : J.s(o, "error", "?"));
        JsonObject w = o.getAsJsonObject("window");
        String text = Str.trim(J.s(o, "text", ""));
        String kind = J.s(w, "kind", "window");
        store(h, "read", J.s(w, "title", ""), "chars=" + text.length());
        StringBuilder sb = new StringBuilder("[desktop] ok=1 读「" + Str.cut(J.s(w, "title", ""), 50) + "」 kind=" + kind + "，文本 " + text.length() + " 字符");
        if ("console".equals(kind) || "canvas".equals(kind)) {
            sb.append("\n（这种窗口 UIA 读不到正文：控制台用 exec 直接跑命令；要读屏幕上看到的字，用 op=shot 截图再去看图）");
        }
        sb.append('\n').append(Str.cut(text, 4000));
        return sb.toString();
    }

    static String shot(Host h, Cfg cfg, Args a) throws Exception {
        int pid = a.i("pid", 0);
        int x = a.i("x", -1), y = a.i("y", -1), w = a.i("w", 0), hh = a.i("h", 0);
        if (w <= 0 || hh <= 0) {
            if (pid <= 0) return "[desktop] ok=0 要给 pid（截窗口）或 x/y/w/h（截区域）";
            JsonObject o = run(h, cfg, "windows", null, null, 30000L);
            JsonArray arr = o == null ? null : o.getAsJsonArray("windows");
            for (int i = 0; arr != null && i < arr.size(); i++) {
                JsonObject wn = arr.get(i).getAsJsonObject();
                if (J.i(wn, "pid", 0) == pid) {
                    x = J.i(wn, "x", 0); y = J.i(wn, "y", 0); w = J.i(wn, "w", 0); hh = J.i(wn, "h", 0);
                    break;
                }
            }
            if (w <= 0 || hh <= 0) return "[desktop] ok=0 没找到 pid=" + pid + " 的窗口（用 op=windows 确认）";
        }
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        BufferedImage img = new Robot().createScreenCapture(new Rectangle(x, y, w, hh));
        File dir = new File(h.conf().filesDir(), "screen");
        if (!dir.isDirectory() && !dir.mkdirs()) return "[desktop] ok=0 建不了目录";
        File out = new File(dir, "desk-" + System.currentTimeMillis() + ".png");
        ImageIO.write(img, "png", out);
        store(h, "shot", "pid=" + pid, "size=" + w + "x" + hh);
        return "[desktop] ok=1 file=" + out.getAbsolutePath() + " size=" + w + "x" + hh + " bytes=" + out.length()
                + "\n（要看内容：用看图工具，或 screen op=see）";
    }

    // ---------------------------------------------------------------- 引擎与配置

    static String q(String s) { return "\"" + Str.nz(s) + "\""; }

    /** uia.ps1 的真实路径（配置里可写绝对路径；默认相对数据根 tools/uia.ps1）。 */
    static String scriptFile(Host h, Cfg c) {
        String p = Str.trim(c.script);
        if (p.isEmpty()) p = "tools/uia.ps1";
        File f = new File(p);
        if (!f.isAbsolute() && h != null && h.conf() != null) f = new File(h.conf().root(), p);
        return f.getAbsolutePath();
    }

    /** 组装并执行一条 uia.ps1 命令（-OutFile 走临时 JSON），返回解析后的 JsonObject。 */
    static JsonObject runCmd(Host h, String baseCmd, long timeoutMs) {
        try {
            File dirT = h.conf().tmpDir();
            if (dirT != null && !dirT.isDirectory()) dirT.mkdirs();   // 真机踩过：目录不在 ⇒ PowerShell 写 JSON 抛异常 ⇒ 表现为「没有输出」
            File out = new File(dirT, "desk-" + System.currentTimeMillis() + ".json");
            String cmd = baseCmd + " -OutFile " + q(out.getAbsolutePath());
            JsonObject args = new JsonObject();
            args.addProperty("kind", "cmd");
            args.addProperty("cmd", cmd);
            args.addProperty("timeout", timeoutMs);
            h.call("exec", args);
            if (!out.isFile()) return null;
            String txt = Fs.read(out, "");
            try { out.delete(); } catch (Throwable ignored) { }
            return J.obj(txt);
        } catch (Throwable x) {
            return null;
        }
    }

    /** windows 用（无参数）。 */
    static JsonObject run(Host h, Cfg c, String cmd, File targetFile, File valueFile, long timeoutMs) {
        return runCmd(h, "powershell -NoProfile -ExecutionPolicy Bypass -File " + q(scriptFile(h, c)) + " -Cmd " + cmd, timeoutMs);
    }

    /** 中文一律走 UTF-8 临时文件（命令行传中文会被 GBK 破坏，真机实测过）。 */
    static File utf8(Host h, String tag, String text) {
        try {
            File dirT = h.conf().tmpDir();
            if (dirT != null && !dirT.isDirectory()) dirT.mkdirs();
            File f = new File(dirT, "desk-" + tag + "-" + System.currentTimeMillis() + ".txt");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            try { fo.write(Str.nz(text).getBytes("UTF-8")); } finally { fo.close(); }
            return f;
        } catch (Throwable x) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 闸与账

    static boolean isHalted(Host h) {
        try { return Str.has(h.store().kv(HALT_KEY, "")); } catch (Throwable x) { return false; }
    }

    /** 审计：一行日志 + kv 里留最后一条（出问题能复盘）。 */
    static void store(Host h, String action, String target, String note) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("ts", System.currentTimeMillis());
            o.addProperty("action", Str.nz(action));
            o.addProperty("target", Str.cut(Str.nz(target), 60));
            o.addProperty("note", Str.cut(Str.nz(note), 120));
            h.store().kvSet("desktop:last", J.json(o));
        } catch (Throwable ignored) { }
        try {
            if (h.out() != null) h.out().dim("[desktop] " + Str.nz(action) + " " + Str.cut(Str.nz(target), 40) + " " + Str.nz(note));
        } catch (Throwable ignored) { }
    }

    private static final Object LOCK = new Object();
    private static long winStart = 0L;
    private static int winCount = 0;

    static boolean budget(Cfg c) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - winStart > 60000L) { winStart = now; winCount = 0; }
            if (winCount >= c.perMin) return false;
            winCount++;
            return true;
        }
    }

    /** 技能配置：skills/桌面/prompt.md（缺文件 = 总闸关）。 */
    static final class Cfg {
        boolean on = false;
        int perMin = 20;
        int maxNodes = 120;
        String script = "tools/uia.ps1";

        static Cfg load(Host h) {
            Cfg c = new Cfg();
            try {
                File dir = h.skillDir();
                if (dir == null && h.conf() != null) dir = new File(h.conf().skillsDir(), NAME);
                File f = dir == null ? null : new File(dir, "prompt.md");
                String txt = f == null ? null : Fs.read(f, "");
                if (txt == null) return c;
                for (String raw : txt.split("\\r?\\n")) {
                    String line = raw == null ? "" : raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf(':');
                    if (i <= 0) continue;
                    String k = line.substring(0, i).trim();
                    String v = line.substring(i + 1).trim();
                    if ("总闸".equals(k)) c.on = "on".equalsIgnoreCase(v);
                    else if ("每分钟上限".equals(k)) { try { int n = Integer.parseInt(v); if (n > 0) c.perMin = n; } catch (Throwable ignored) { } }
                    else if ("最大节点".equals(k)) { try { int n = Integer.parseInt(v); if (n > 0) c.maxNodes = n; } catch (Throwable ignored) { } }
                    else if ("uia脚本".equals(k) && !v.isEmpty()) c.script = v;
                }
            } catch (Throwable ignored) { }
            return c;
        }
    }
}
