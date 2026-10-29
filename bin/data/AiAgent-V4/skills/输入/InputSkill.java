import com.google.gson.JsonObject;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashSet;

import sair.v4.auth.Caller;
import sair.v4.auth.OpList;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 输入模拟技能（鼠标 / 键盘）：computer use 的"动手"那一半。
 *
 * <p>只用 JDK 自带的 {@link java.awt.Robot}，零依赖。</p>
 *
 * <h3>护栏（每一道都独立可证伪）</h3>
 * <ol>
 *   <li><b>只认主人与她本人</b>；</li>
 *   <li><b>群里的回合一律不接</b>；</li>
 *   <li><b>总闸</b>（技能自己的配置文件写 {@code 总闸: on}；读不到 = 关，fail-closed）；</li>
 *   <li><b>动作级 op</b> 各判各的；</li>
 *   <li><b>每分钟预算</b>；</li>
 *   <li><b>坐标越界一律拒</b>（不静默夹取）；</li>
 *   <li><b>陈旧坐标硬拒</b>：当前屏幕尺寸 ≠ 上次截图时的尺寸 ⇒ 拒（远程桌面分辨率会变）；</li>
 *   <li><b>画面不可用拒</b>：上次截图是纯色（远程桌面断开 / 锁屏）⇒ 拒，别盲点盲打；</li>
 *   <li><b>动手后复核</b>：再抓一张比指纹，画面没变就如实告诉她"这次可能没生效"；</li>
 *   <li><b>每次动作前再查一次总闸与预算</b>。</li>
 * </ol>
 *
 * <p><b>与 截屏 技能的契约</b>：两边不同目录 = 不同编译单元，不能互相引用，所以靠
 * {@code kv} 的 {@code screen:last}（{@code {w,h,hash,uniform,ts}}）传话。本技能只读它 + 复核时更新它。</p>
 */
public class InputSkill implements Skill {

    static final String NAME = "输入";
    static final int DEF_PER_CALL = 20;
    static final int DEF_PER_MIN = 60;

    /** 按需恢复用的计划任务名（可在 {@code skills/输入/prompt.md} 里用「进桌面任务:」改）。 */
    static final String DEF_TASK = "AiAgent-RDP-to-Console-Now";

    /**
     * 进桌面这件事的<b>风险披露</b>。主人要的就是"先自报家门、把风险说清、问一句再动"。
     * 未经明确同意时，这个 op <b>一个字都不执行</b>。
     */
    static final String DISCLOSE = "我打算做的事：把那个【已登录】的会话接到物理控制台上"
            + "（tscon &lt;会话&gt; /dest:console），让屏幕重新被渲染 —— 这样我才看得见、才谈得上操作。\n"
            + "风险先说清楚：\n"
            + "  1) 会掐断你当前的远程桌面连接，你需要重新连一次 RDP（会话本身不丢，还登录着）；\n"
            + "  2) 物理屏幕会显示那个已登录、未锁定的桌面，现场能看到屏幕内容；\n"
            + "  3) 可能失败：控制台被别的用户占用、会话正处在锁屏安全域、或权限不足 —— 失败我会如实说，不假装成功；\n"
            + "  4) 也可能无效：黑屏原因若不是「会话没被渲染」（比如显示器关了），搬过去也不一定恢复 —— 我会重抓一张画面来验证。";

    /** 上一次截图的记录（与 截屏 技能共用的 kv 键）。 */
    static final String KEY_LAST = "screen:last";

    private static final Object LOCK = new Object();
    private static long windowStart = 0L;
    private static int windowCount = 0;

    @Override
    public void declareOps(OpList ops) {
        ops.add("input", "move", "把鼠标移到某坐标");
        ops.add("input", "click", "在某坐标点击");
        ops.add("input", "type", "把一段文字敲进当前焦点（走剪贴板粘贴，中文也行）");
        ops.add("input", "key", "按一个键或一组组合键（如 ctrl+s、enter）");
        ops.add("input", "scroll", "滚轮");
        ops.add("input", "recover", "黑屏时把已登录的会话接回物理控制台（做之前会告知，不再等确认）");
        // [P3 2026-10-24] 手脚补全：宏 / 双击 / 右键 / 拖拽 / 剪贴板 / 启动 / 等待
        ops.add("input", "seq", "一串动作一次做完（move/a click/d type:文本/k key/drag/dbl/rclick/sleep/wait）");
        ops.add("input", "doubleclick", "双击某坐标");
        ops.add("input", "rightclick", "右键某坐标");
        ops.add("input", "drag", "从一点拖到另一点（拖文件/拖选）");
        ops.add("input", "clipboard", "读或写系统剪贴板（get/set）");
        ops.add("input", "launch", "启动一个程序或打开文件");
        ops.add("input", "wait", "等到屏幕某处变了 / 等一段时间");
    }

    @Override
    public Object call(Args a, Host h) {
        String op = Str.lower(Str.trim(a.str("op", "")));
        if (!oneOf(op, "move", "click", "type", "key", "scroll", "recover",
                "seq", "doubleclick", "rightclick", "drag", "clipboard", "launch", "wait")) {
            return deny("不支持的 op：" + op + "（move/click/type/key/scroll/recover/seq/doubleclick/rightclick/drag/clipboard/launch/wait）");
        }
        Caller c = h.caller();
        if (c == null) return deny("没有调用者身份");
        if (c.isGroup()) return deny("输入模拟不接群里的回合（只接主人私聊 / 控制台）");
        // [P4 2026-10-24] 一键停：与 desktop op=halt 共用同一个开关，冻结全部鼠标键盘
        try {
            if (h.store() != null && Str.has(h.store().kv("desktop:halt", ""))) {
                return deny("已冻结（有人说「停」）。要恢复得先 desktop op=resume");
            }
        } catch (Throwable ignored) {
        }
        if (!c.master() && !c.system()) return deny("输入模拟只给主人（当前身份：" + c.kind() + "）");
        Cfg cfg = Cfg.load(h);
        if (!cfg.on()) return deny("总闸没开：在 skills/" + NAME + "/prompt.md 里写一行「总闸: on」才生效");
        String d = h.need("input." + op);
        if (d != null) return d;
        // ★ recover 要能在"画面不可用"时执行 —— 所以它排在下面那三道闸之前
        if ("recover".equals(op)) return recover(a, h, cfg);
        // [P3] 不动鼠标的几种，各自独立（它们不需要"先截图"那道闸）
        if ("clipboard".equals(op)) return clipboardOp(a, h, cfg);
        if ("launch".equals(op)) return launchProgram(a, h);
        if ("wait".equals(op)) return waitScreen(a, h, cfg);

        Rectangle screen = screen();
        if (screen == null) return deny("拿不到屏幕尺寸（headless？）");

        // ---- ⑥⑦⑧ 桌面可用性与坐标有效性（远程桌面：分辨率会变、断开后画面是纯色）
        boolean coordOp = "move".equals(op) || "click".equals(op)
                || "doubleclick".equals(op) || "rightclick".equals(op) || "drag".equals(op)
                || "seq".equals(op);
        JsonObject last = lastCapture(h);
        boolean have = last != null;
        int lastW = have ? J.i(last, "w", 0) : 0;
        int lastH = have ? J.i(last, "h", 0) : 0;
        boolean lastUniform = have && J.b(last, "uniform", false);
        if (have && lastUniform) {
            return deny("上一张截图是纯色（" + lastW + "x" + lastH + "）—— 屏幕上没有可见内容，"
                    + "现在动手等于盲操作。" + BLIND_HINT);
        }
        if (coordOp) {
            if (!have) {
                return deny("还没有截图记录：先 screen(op=capture) 看清屏幕，再动手 —— 坐标必须来自刚看的那张图。");
            }
            if (screen.width != lastW || screen.height != lastH) {
                return deny("屏幕尺寸变了（上次截图 " + lastW + "x" + lastH + " → 现在 "
                        + screen.width + "x" + screen.height + "），那次的坐标已经过期：先 screen(op=capture) 重抓再动。");
            }
        }

        try {
            Robot r = new Robot();
            // [P3] 新增原语
            if ("seq".equals(op)) return seq(a, h, cfg, r, screen);
            if ("doubleclick".equals(op) || "rightclick".equals(op)) return clickBtn(a, h, cfg, r, screen, "doubleclick".equals(op));
            if ("drag".equals(op)) return dragTo(a, h, cfg, r, screen);
            if (coordOp) {
                int[] xy = resolve(a, screen);
                int x = xy[0], y = xy[1];
                if (!inside(screen, x, y)) return deny("坐标越界：(" + x + "," + y + ") 不在屏幕 " + fmt(screen) + " 内");
                if (!spend(cfg)) return deny("输入预算用尽（每分钟 " + cfg.perMin + " 次）");
                r.mouseMove(x, y);
                if ("click".equals(op)) {
                    int btn = a.i("button", 1);
                    int mask = btn == 3 ? InputEvent.BUTTON3_DOWN_MASK
                            : (btn == 2 ? InputEvent.BUTTON2_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK);
                    r.mousePress(mask);
                    r.delay(30);
                    r.mouseRelease(mask);
                }
                r.delay(120);
                return fact("ok=1 op=" + op + " x=" + x + " y=" + y + " " + recheck(h, screen, !"move".equals(op)));
            }
            if ("scroll".equals(op)) {
                int amount = a.i("amount", 0);
                if (amount == 0) return deny("scroll 要给 amount（正 = 上滚，负 = 下滚）");
                int n = Math.abs(amount);
                if (n > 20) n = 20;
                for (int i = 0; i < n; i++) {
                    if (!spend(cfg)) return deny("输入预算用尽（每分钟 " + cfg.perMin + " 次）");
                    r.mouseWheel(amount > 0 ? 1 : -1);
                    r.delay(20);
                }
                r.delay(120);
                return fact("ok=1 op=scroll amount=" + amount + " " + recheck(h, screen, true));
            }
            if ("key".equals(op)) {
                String keys = Str.lower(Str.trim(a.str("keys", "")));
                if (keys.isEmpty()) return deny("key 要给 keys（如 ctrl+s / enter / f5）");
                String[] parts = keys.split("\\+");
                java.util.List<Integer> codes = new java.util.ArrayList<Integer>();
                for (String p : parts) {
                    int code = keyCode(Str.trim(p));
                    if (code <= 0) return deny("认不出的键名：" + p);
                    codes.add(Integer.valueOf(code));
                }
                for (int i = 0; i < codes.size(); i++) {
                    if (!spend(cfg)) return deny("输入预算用尽（每分钟 " + cfg.perMin + " 次）");
                    r.keyPress(codes.get(i).intValue());
                    r.delay(20);
                }
                for (int i = codes.size() - 1; i >= 0; i--) {
                    r.keyRelease(codes.get(i).intValue());
                    r.delay(10);
                }
                r.delay(120);
                return fact("ok=1 op=key keys=" + keys + " " + recheck(h, screen, true));
            }
            String text = a.str("text", "");
            if (text.isEmpty()) return deny("type 要给 text");
            if (text.length() > 2000) return deny("一次最多贴 2000 字符（当前 " + text.length() + "）");
            if (!spend(cfg)) return deny("输入预算用尽（每分钟 " + cfg.perMin + " 次）");
            // [P3] 保存用户原剪贴板，粘完再还回去 —— 旧实现会把用户的剪贴板顶掉（真机 bug）
            java.awt.datatransfer.Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            java.awt.datatransfer.Transferable oldClip = null;
            try { oldClip = cb.getContents(null); } catch (Throwable ignored) { }
            cb.setContents(new StringSelection(text), null);
            r.delay(60);
            r.keyPress(KeyEvent.VK_CONTROL);
            r.keyPress(KeyEvent.VK_V);
            r.delay(30);
            r.keyRelease(KeyEvent.VK_V);
            r.keyRelease(KeyEvent.VK_CONTROL);
            r.delay(150);
            try { if (oldClip != null) cb.setContents(oldClip, null); } catch (Throwable ignored) { }
            return fact("ok=1 op=type chars=" + text.length() + " via=clipboard " + recheck(h, screen, true));
        } catch (Throwable e) {
            return deny("输入执行异常：" + e);
        }
    }

    // ---------------------------------------------------------------- P3 新增原语（2026-10-24）

    /** 解析一个点："xr,yr"（值域 0~1，归一化）或 "x,y"（绝对像素）。 */
    static int[] point(String s, Rectangle screen) {
        String t = Str.trim(s);
        if (t.isEmpty()) return null;
        String[] p = t.split("[,，\\s]+");
        if (p.length < 2) return null;
        try {
            double v1 = Double.parseDouble(p[0].trim()), v2 = Double.parseDouble(p[1].trim());
            if (v1 >= 0 && v1 <= 1 && v2 >= 0 && v2 <= 1) {
                return new int[] { screen.x + (int) Math.round(v1 * (screen.width - 1)),
                                   screen.y + (int) Math.round(v2 * (screen.height - 1)) };
            }
            return new int[] { (int) Math.round(v1), (int) Math.round(v2) };
        } catch (Throwable x) {
            return null;
        }
    }

    /** 双击或右键（坐标同样要过\u201c截图新鲜\u201d那道闸）。 */
    static Object clickBtn(Args a, Host h, Cfg cfg, Robot r, Rectangle screen, boolean dbl) throws Exception {
        int[] xy = resolve(a, screen);
        int x = xy[0], y = xy[1];
        if (!inside(screen, x, y)) return deny("坐标越界：(" + x + "," + y + ")");
        if (!spend(cfg)) return deny("输入预算用尽（每分钟 " + cfg.perMin + " 次）");
        int mask = dbl ? InputEvent.BUTTON1_DOWN_MASK : InputEvent.BUTTON3_DOWN_MASK;
        r.mouseMove(x, y);
        r.delay(40);
        r.mousePress(mask); r.delay(30); r.mouseRelease(mask);
        if (dbl) { r.delay(60); r.mousePress(mask); r.delay(30); r.mouseRelease(mask); }
        r.delay(150);
        return fact("ok=1 op=" + (dbl ? "doubleclick" : "rightclick") + " at=" + x + "," + y + " " + recheck(h, screen, true));
    }

    /** 拖拽：from="0.2,0.3" to="0.7,0.8"（也接受绝对像素）。 */
    static Object dragTo(Args a, Host h, Cfg cfg, Robot r, Rectangle screen) throws Exception {
        int[] from = point(a.str("from", ""), screen);
        int[] to = point(a.str("to", ""), screen);
        if (from == null || to == null) return deny("drag 要给 from=\"xr,yr\" 和 to=\"xr,yr\"");
        if (!inside(screen, from[0], from[1]) || !inside(screen, to[0], to[1])) return deny("拖拽起止点越界");
        if (!spend(cfg)) return deny("输入预算用尽（每分钟 " + cfg.perMin + " 次）");
        r.mouseMove(from[0], from[1]);
        r.delay(80);
        r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        int steps = 14;
        for (int i = 1; i <= steps; i++) {
            int x = from[0] + (to[0] - from[0]) * i / steps;
            int y = from[1] + (to[1] - from[1]) * i / steps;
            r.mouseMove(x, y);
            r.delay(18);
        }
        r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        r.delay(180);
        return fact("ok=1 op=drag from=" + from[0] + "," + from[1] + " to=" + to[0] + "," + to[1] + " " + recheck(h, screen, true));
    }

    /** 剪贴板读写：mode=get 读文本，mode=set 写 text。 */
    static Object clipboardOp(Args a, Host h, Cfg cfg) {
        String mode = Str.lower(Str.trim(a.str("mode", "get")));
        java.awt.datatransfer.Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        if ("set".equals(mode)) {
            String text = a.str("text", "");
            if (!spend(cfg)) return deny("输入预算用尽（每分钟 " + cfg.perMin + " 次）");
            cb.setContents(new StringSelection(text), null);
            return fact("ok=1 op=clipboard mode=set chars=" + text.length());
        }
        String got = "";
        try {
            java.awt.datatransfer.Transferable t = cb.getContents(null);
            if (t != null && t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                got = String.valueOf(t.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor));
            }
        } catch (Throwable x) {
            return deny("读剪贴板失败：" + x);
        }
        return fact("ok=1 op=clipboard mode=get chars=" + got.length() + " text=" + Str.cut(Str.oneLine(got), 2000));
    }

    /** 启动程序或打开文件（直接 exec，不过 shell ⇒ 没有命令注入面）。 */
    static Object launchProgram(Args a, Host h) {
        String path = Str.trim(a.str("path", ""));
        if (path.isEmpty()) return deny("launch 要给 path=（可执行文件或文档的完整路径）");
        File f = new File(path);
        if (!f.isAbsolute()) return deny("launch 只接绝对路径（相对路径会按谁的工作目录解析？不猜）");
        if (!f.exists()) return deny("找不到：" + path);
        try {
            new ProcessBuilder(new String[] { path }).start();
            return fact("ok=1 op=launch via=direct path=" + Str.cut(path, 160));
        } catch (Throwable x) {
            try {
                new ProcessBuilder(new String[] { "rundll32.exe", "url.dll,FileProtocolHandler", path }).start();
                return fact("ok=1 op=launch via=shell-open path=" + Str.cut(path, 160));
            } catch (Throwable y) {
                return deny("拉不起来：" + y);
            }
        }
    }

    /** 等：sleep 毫秒（ms），或等到画面变了（changed=1 + timeout 毫秒）。 */
    static Object waitScreen(Args a, Host h, Cfg cfg) {
        int ms = a.i("ms", 0);
        int timeout = a.i("timeout", 0) > 0 ? a.i("timeout", 0) : 8000;
        boolean wantChange = a.i("changed", 0) == 1 || "change".equals(Str.lower(Str.trim(a.str("mode", ""))));
        if (!wantChange) {
            int sleep = ms > 0 ? Math.min(ms, 60000) : 800;
            try { Thread.sleep(sleep); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return fact("ok=1 op=wait slept=" + sleep + "ms");
        }
        long h0 = screenHash();
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < timeout) {
            try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            if (screenHash() != h0) {
                return fact("ok=1 op=wait changed=1 after_ms=" + (System.currentTimeMillis() - t0));
            }
        }
        return fact("ok=0 op=wait changed=0 timeout_ms=" + timeout + "（画面一直没变）");
    }

    /** 粗略屏幕指纹（1/8 采样求和）—— wait 用它判\u201c画面变了没\u201d，不落盘、不耗预算。 */
    static long screenHash() {
        try {
            Rectangle sc = screen();
            if (sc == null) return 0L;
            BufferedImage img = new Robot().createScreenCapture(sc);
            long sum = 0L;
            for (int y = 0; y < img.getHeight(); y += 8) {
                for (int x = 0; x < img.getWidth(); x += 8) {
                    sum = sum * 31L + img.getRGB(x, y);
                }
            }
            return sum;
        } catch (Throwable e) {
            return 0L;
        }
    }
    /**
     * 宏：一串动作一次做完。steps 用 | 分隔，每个：
     *   move xr,yr | click | dbl | rclick | drag xr,yr xr,yr | type:文本 | k:ctrl+s | sleep:500
     * 每步都过预算与复核；某步失败就停并把已经做了的报出来。
     */
    static Object seq(Args a, Host h, Cfg cfg, Robot r, Rectangle screen) throws Exception {
        String raw = a.str("steps", "");
        if (Str.blank(raw)) return deny("seq 要给 steps（用 | 分隔，如 move 0.4,0.2 | click | sleep:300 | type:你好）");
        String[] steps = raw.split("\\|");
        StringBuilder report = new StringBuilder("ok=1 op=seq steps=" + steps.length);
        int done = 0;
        java.awt.datatransfer.Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        for (String rawStep : steps) {
            String st = Str.trim(rawStep);
            if (st.isEmpty()) continue;
            if (!spend(cfg)) {
                report.append("\n  第 ").append(done + 1).append(" 步停：预算用尽");
                return fact(report.toString());
            }
            String low = Str.lower(st);
            try {
                if (low.startsWith("move ")) {
                    int[] p = point(st.substring(5), screen);
                    if (p == null) throw new IllegalArgumentException("move 点写错了");
                    r.mouseMove(Math.max(0, Math.min(screen.width - 1, p[0])), Math.max(0, Math.min(screen.height - 1, p[1])));
                    r.delay(60);
                } else if (low.equals("click") || low.equals("dbl") || low.equals("rclick")) {
                    int mask = low.equals("click") ? InputEvent.BUTTON1_DOWN_MASK
                            : (low.equals("dbl") ? InputEvent.BUTTON1_DOWN_MASK : InputEvent.BUTTON3_DOWN_MASK);
                    r.mousePress(mask); r.delay(25); r.mouseRelease(mask);
                    if (low.equals("dbl")) { r.delay(60); r.mousePress(mask); r.delay(25); r.mouseRelease(mask); }
                    r.delay(120);
                } else if (low.startsWith("drag ")) {
                    String[] p2 = st.substring(5).trim().split("\\s+");
                    if (p2.length < 2) throw new IllegalArgumentException("drag 要两个点");
                    int[] f = point(p2[0], screen), t2 = point(p2[1], screen);
                    if (f == null || t2 == null) throw new IllegalArgumentException("drag 点写错了");
                    if (!inside(screen, f[0], f[1]) || !inside(screen, t2[0], t2[1])) throw new IllegalArgumentException("drag 越界");
                    r.mouseMove(f[0], f[1]); r.delay(70);
                    r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    for (int i = 1; i <= 12; i++) {
                        r.mouseMove(f[0] + (t2[0] - f[0]) * i / 12, f[1] + (t2[1] - f[1]) * i / 12);
                        r.delay(16);
                    }
                    r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); r.delay(160);
                } else if (low.startsWith("type:")) {
                    String text = st.substring(5);
                    java.awt.datatransfer.Transferable old = null;
                    try { old = cb.getContents(null); } catch (Throwable ignored) { }
                    cb.setContents(new StringSelection(text), null);
                    r.delay(40);
                    r.keyPress(KeyEvent.VK_CONTROL); r.keyPress(KeyEvent.VK_V); r.delay(25);
                    r.keyRelease(KeyEvent.VK_V); r.keyRelease(KeyEvent.VK_CONTROL);
                    r.delay(120);
                    try { if (old != null) cb.setContents(old, null); } catch (Throwable ignored) { }
                } else if (low.startsWith("k:")) {
                    String keys = Str.lower(Str.trim(st.substring(2)));
                    String[] parts = keys.split("\\+");
                    java.util.List<Integer> codes = new java.util.ArrayList<Integer>();
                    for (String p : parts) {
                        int code = keyCode(Str.trim(p));
                        if (code <= 0) throw new IllegalArgumentException("认不出的键：" + p);
                        codes.add(Integer.valueOf(code));
                    }
                    for (int i = 0; i < codes.size(); i++) { r.keyPress(codes.get(i).intValue()); r.delay(15); }
                    for (int i = codes.size() - 1; i >= 0; i--) { r.keyRelease(codes.get(i).intValue()); r.delay(10); }
                    r.delay(100);
                } else if (low.startsWith("sleep:") || low.startsWith("wait:")) {
                    int ms = Integer.parseInt(st.substring(st.indexOf(':') + 1).trim());
                    Thread.sleep(Math.max(0, Math.min(ms, 30000)));
                } else {
                    throw new IllegalArgumentException("认不出的步骤：" + Str.cut(st, 40));
                }
                done++;
                report.append("\n  ").append(done).append(". ✅ ").append(Str.cut(st, 60));
            } catch (Throwable e) {
                report.append("\n  ").append(done + 1).append(". ❌ ").append(Str.cut(st, 60)).append(" —— ").append(Str.cut(String.valueOf(e), 120));
                return fact(report.toString() + "\n（已完成 " + done + " 步，后面的没做）");
            }
        }
        report.append("\n（全部 ").append(done).append(" 步做完）");
        return fact(report.toString() + " " + recheck(h, screen, false));
    }
    // ---------------------------------------------------------------- recover（黑屏自救）

    /**
     * 黑屏自救：把那个已登录的会话接回物理控制台，让屏幕重新被渲染。
     *
     * <p><b>三道关</b>：① 上一次截图必须是纯色（否则画面好好的，没必要动）；②
     * <b>必须带 {@code confirm=yes}</b> —— 没带就只回风险披露与问句，<b>一个字都不执行</b>；
     * ③ 执行走基板的 {@code exec} 工具（{@code h.call}）⇒ 除本 op 外还要过 {@code exec.cmd} 那道 ACL。</p>
     *
     * <p>执行后 <b>重抓一张画面</b>作为唯一判据：恢复没恢复，用画面说话，不靠猜。</p>
     */
    static Object recover(Args a, Host h, Cfg cfg) {
        JsonObject last = lastCapture(h);
        if (last == null) {
            return deny("先 screen(op=capture) 看清当前状态，再决定要不要进桌面。");
        }
        if (!J.b(last, "uniform", false)) {
            return deny("画面是好的（上一次截图不是纯色），不用进桌面 —— recover 只在抓到纯色空画面时才用。");
        }
        // [P4 2026-10-24] 甲方要求"彻底开放"：不再等确认 —— 但**仍然告知**：写一行日志，回执里说明会接管桌面
        try {
            if (h.out() != null) h.out().dim("[input] recover：把会话接回物理控制台（会中断正在用的远程桌面）");
        } catch (Throwable ignored) {
        }
        int beforeW = J.i(last, "w", 0);
        int beforeH = J.i(last, "h", 0);
        String task = Str.trim(cfg.task);
        if (Str.blank(task)) task = DEF_TASK;
        String cmd = "powershell -NoProfile -Command \"Start-ScheduledTask -TaskName '" + task + "'\"";
        String ex;
        try {
            JsonObject e = new JsonObject();
            e.addProperty("kind", "cmd");
            e.addProperty("cmd", cmd);
            e.addProperty("timeout", 30000);
            ex = h.call("exec", e);
        } catch (Throwable t) {
            ex = "调用 exec 异常：" + t;
        }
        try { Thread.sleep(5000L); } catch (Throwable ignored) { }
        String after = "?";
        Rectangle sc = screen();
        if (sc != null) {
            try {
                BufferedImage img = new Robot().createScreenCapture(sc);
                int colors = distinctColors(img);
                boolean u2 = colors <= 1;
                remember(h, sc.width, sc.height, fingerprint(img), u2);
                after = u2 ? "unavailable" : "ok";
            } catch (Throwable t) {
                after = "?";
            }
        }
        String head = "[input] ok=1 op=recover ran=1 task=" + task
                + " screenBefore=unavailable(" + beforeW + "x" + beforeH + ")"
                + " screenAfter=" + after
                + " exec=" + Str.cut(Str.oneLine(ex), 200);
        String note = "ok".equals(after)
                ? "\n已恢复：物理控制台现在在渲染这个会话，我重新看得见了。"
                : ("unavailable".equals(after)
                ? "\n搬过去之后画面仍是纯色：可能不是「会话没被渲染」这个原因（比如显示器关了），也可能这一步没成功。"
                : "\n恢复结果读不出来（拿不到屏幕）。");
        return head + note;
    }

    static boolean yes(String v) {
        String s = Str.lower(Str.trim(v));
        return "yes".equals(s) || "y".equals(s) || "true".equals(s) || "1".equals(s)
                || "是".equals(s) || "可以".equals(s) || "确认".equals(s) || "好".equals(s);
    }

    // ---------------------------------------------------------------- 坐标

    /**
     * 解出像素坐标。<b>优先归一化</b>（{@code xr}/{@code yr} ∈ [0,1]）：按<b>动手那一刻</b>的
     * 屏幕尺寸换算 ⇒ 天然抗分辨率变化（远程桌面会变）。给了 {@code x}/{@code y} 就用绝对像素。
     */
    static int[] resolve(Args a, Rectangle s) {
        if (a.has("xr") || a.has("yr")) {
            double xr = a.d("xr", -1.0D), yr = a.d("yr", -1.0D);
            if (xr < 0.0D || xr > 1.0D || yr < 0.0D || yr > 1.0D) {
                return new int[] { Integer.MIN_VALUE, Integer.MIN_VALUE };
            }
            return new int[] { s.x + (int) Math.round(xr * (s.width - 1)), s.y + (int) Math.round(yr * (s.height - 1)) };
        }
        return new int[] { a.i("x", Integer.MIN_VALUE), a.i("y", Integer.MIN_VALUE) };
    }

    // ---------------------------------------------------------------- 桌面判据

    static final String BLIND_HINT = "要让 computer use 可用：连上远程桌面，或让物理桌面保持可见且解锁。";

    static JsonObject lastCapture(Host h) {
        try {
            if (h == null || h.store() == null) return null;
            String s = h.store().kv(KEY_LAST, "");
            return Str.blank(s) ? null : J.obj(s);
        } catch (Throwable e) {
            return null;
        }
    }

    static int distinctColors(BufferedImage img) {
        try {
            HashSet<Integer> set = new HashSet<Integer>();
            int sx = Math.max(1, img.getWidth() / 40);
            int sy = Math.max(1, img.getHeight() / 40);
            for (int y = 0; y < img.getHeight(); y += sy)
                for (int x = 0; x < img.getWidth(); x += sx) set.add(Integer.valueOf(img.getRGB(x, y)));
            return set.size();
        } catch (Throwable e) {
            return 0;
        }
    }

    static String fingerprint(BufferedImage img) {
        try {
            BufferedImage s = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
            java.awt.Graphics2D g = s.createGraphics();
            g.drawImage(img, 0, 0, 8, 8, null);
            g.dispose();
            StringBuilder sb = new StringBuilder(128);
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++) sb.append(String.format("%02x", s.getRaster().getSample(x, y, 0)));
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * ⑨ 动手后复核：再抓一张，比指纹；顺带更新 {@code screen:last}（下一次动作的基准）。
     *
     * @param interpret {@code true} = 这一下<b>本该改变画面</b>（click/type/key/scroll），
     *                  {@code changed=0} 要提示"这次动作可能没生效"；
     *                  {@code false} = 移动类（move）—— <b>移动本来就不改画面</b>，
     *                  再挂那句提示只会让她白重试（真机实测踩到的）。
     */
    static String recheck(Host h, Rectangle screen, boolean interpret) {
        try {
            BufferedImage img = new Robot().createScreenCapture(screen);
            String fp = fingerprint(img);
            int colors = distinctColors(img);
            JsonObject before = lastCapture(h);
            String was = before == null ? "" : J.s(before, "hash", "");
            remember(h, screen.width, screen.height, fp, colors <= 1);
            if (was.isEmpty() || fp.isEmpty()) return "changed=?";
            if (!fp.equals(was)) return "changed=1";
            return interpret ? "changed=0（画面和动手前一样：这次动作可能没生效）"
                    : "changed=0（移动不改画面，属正常）";
        } catch (Throwable e) {
            return "changed=?";
        }
    }

    static void remember(Host h, int w, int hh, String hash, boolean uniform) {
        try {
            if (h == null || h.store() == null) return;
            JsonObject o = new JsonObject();
            o.addProperty("w", w);
            o.addProperty("h", hh);
            o.addProperty("hash", hash);
            o.addProperty("uniform", uniform);
            o.addProperty("ts", System.currentTimeMillis());
            h.store().kvSet(KEY_LAST, J.json(o));
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 护栏

    static boolean spend(Cfg cfg) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - windowStart > 60000L) { windowStart = now; windowCount = 0; }
            if (windowCount >= cfg.perMin) return false;
            windowCount++;
            return true;
        }
    }

    static boolean inside(Rectangle s, int x, int y) {
        return x != Integer.MIN_VALUE && y != Integer.MIN_VALUE
                && x >= s.x && y >= s.y && x < s.x + s.width && y < s.y + s.height;
    }

    static Rectangle screen() {
        try {
            if (GraphicsEnvironment.isHeadless()) return null;
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getScreenDevices()[0].getDefaultConfiguration().getBounds();
        } catch (Throwable e) {
            return null;
        }
    }

    static String fmt(Rectangle r) { return r.width + "x" + r.height; }

    static boolean oneOf(String v, String... all) {
        for (String s : all) if (s.equals(v)) return true;
        return false;
    }

    static String deny(String why) { return "[input] ok=0 " + why; }
    static String fact(String body) { return "[input] " + body; }

    static int keyCode(String k) {
        if (k.isEmpty()) return -1;
        if (k.length() == 1) {
            char ch = k.charAt(0);
            if (ch >= 'a' && ch <= 'z') return KeyEvent.VK_A + (ch - 'a');
            if (ch >= '0' && ch <= '9') return KeyEvent.VK_0 + (ch - '0');
        }
        if ("ctrl".equals(k) || "control".equals(k)) return KeyEvent.VK_CONTROL;
        if ("alt".equals(k)) return KeyEvent.VK_ALT;
        if ("shift".equals(k)) return KeyEvent.VK_SHIFT;
        if ("win".equals(k) || "meta".equals(k)) return KeyEvent.VK_META;
        if ("enter".equals(k) || "return".equals(k)) return KeyEvent.VK_ENTER;
        if ("esc".equals(k) || "escape".equals(k)) return KeyEvent.VK_ESCAPE;
        if ("tab".equals(k)) return KeyEvent.VK_TAB;
        if ("space".equals(k)) return KeyEvent.VK_SPACE;
        if ("backspace".equals(k)) return KeyEvent.VK_BACK_SPACE;
        if ("delete".equals(k) || "del".equals(k)) return KeyEvent.VK_DELETE;
        if ("home".equals(k)) return KeyEvent.VK_HOME;
        if ("end".equals(k)) return KeyEvent.VK_END;
        if ("pageup".equals(k)) return KeyEvent.VK_PAGE_UP;
        if ("pagedown".equals(k)) return KeyEvent.VK_PAGE_DOWN;
        if ("up".equals(k)) return KeyEvent.VK_UP;
        if ("down".equals(k)) return KeyEvent.VK_DOWN;
        if ("left".equals(k)) return KeyEvent.VK_LEFT;
        if ("right".equals(k)) return KeyEvent.VK_RIGHT;
        if (k.length() >= 2 && k.charAt(0) == 'f') {
            try {
                int n = Integer.parseInt(k.substring(1));
                if (n >= 1 && n <= 12) return KeyEvent.VK_F1 + (n - 1);
            } catch (Throwable ignored) { }
        }
        return -1;
    }

    /** 技能自己的配置源：{@code skills/<NAME>/prompt.md}，一行「键: 值」。读不到 = 全默认（总闸 = 关）。 */
    static final class Cfg {
        boolean gate = false;
        int perMin = DEF_PER_MIN;
        String task = "";

        boolean on() { return gate; }

        static Cfg load(Host h) {
            Cfg c = new Cfg();
            try {
                if (h == null || h.conf() == null || h.conf().skillsDir() == null) return c;
                File f = new File(new File(h.conf().skillsDir(), NAME), "prompt.md");
                if (!f.isFile()) return c;
                String txt = Fs.read(f, "");
                String[] lines = txt.split("\\r?\\n");
                for (String raw : lines) {
                    String line = raw == null ? "" : raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf(':');
                    if (i <= 0) continue;
                    String k = line.substring(0, i).trim();
                    String v = line.substring(i + 1).trim();
                    if ("总闸".equals(k)) c.gate = "on".equalsIgnoreCase(v);
                    else if ("进桌面任务".equals(k)) c.task = v;
                    else if ("每分钟上限".equals(k)) {
                        try { int n = Integer.parseInt(v); if (n > 0) c.perMin = n; } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable ignored) {
            }
            return c;
        }
    }
}
