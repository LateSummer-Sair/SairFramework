import com.google.gson.JsonObject;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.MouseInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import sair.v4.ai.Msg;
import sair.v4.ai.Res;
import sair.v4.auth.OpList;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 截屏技能：抓本机屏幕 + 把截图交给会看图的模型说清内容。
 *
 * <p>只用 JDK 自带的 {@link java.awt.Robot}，<b>不加任何依赖</b>。</p>
 *
 * <h3>为什么还要判"画面可用不可用"</h3>
 * <p>远程桌面断开 / 锁屏时，{@code Robot.createScreenCapture} 会返回一张<b>纯色</b>图（典型全黑），
 * 而进程<b>不会报错</b> —— 光看"抓成功了"看不出来。所以每次抓完都算三个内容指标：采样颜色数
 * （纯色 ⇒ 1）、8×8 灰度指纹（回答"画面和上次一样吗"）、MouseInfo 是否合理（读数变成垃圾值
 * 通常意味着当前可见桌面不是本进程所在的那个）。</p>
 *
 * <p>抓完把 {@code {w,h,hash,uniform,ts}} 写进 {@code kv} 的 {@code screen:last} ——
 * <b>那是给 输入 技能看的</b>（两个技能在不同目录 = 不同编译单元，不能互相引用，只能走这个键约定）。</p>
 */
public class ScreenSkill implements Skill {

    /** 截图目录名（落在 {@code <files>/screen/}，files 在部署跳过清单里 = 线上专属）。 */
    static final String DIR = "screen";

    /** 单张上限：与基板 mediaAttachMaxBytes 的出厂值逐字一致（4 MiB）。 */
    static final long MAX_BYTES = 4L * 1024 * 1024L;

    /** 上一次截图的记录（与 输入 技能共用的 kv 键）。 */
    static final String KEY_LAST = "screen:last";

    @Override
    public void declareOps(OpList ops) {
        ops.add("screen", "capture", "截取本机屏幕并存到 files/screen/");
        ops.add("screen", "see", "让会看图的模型说清一张屏幕截图里有什么");
    }

    @Override
    public Object call(Args a, Host h) {
        String op = Str.lower(Str.trim(a.str("op", "capture")));
        if ("capture".equals(op)) return capture(a, h);
        if ("see".equals(op)) return see(a, h);
        return "[screen] ok=0 不支持的 op：" + op + "（只有 capture / see）";
    }

    // ---------------------------------------------------------------- 内容判据

    /** 采样算"画面有多少种颜色"。纯色 ⇒ 1（抓到的是一张空画面）。失败返回 0。 */
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

    /** 8×8 灰度指纹（十六进制）。回答"画面和上一次一样吗"。失败返回空串。 */
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

    /** MouseInfo 读数是否合理。垃圾值 = 当前可见桌面多半不是本进程所在的那个（锁屏/安全桌面/断开会话）。 */
    static boolean mouseSane(Rectangle r) {
        try {
            PointerInfo pi = MouseInfo.getPointerInfo();
            if (pi == null) return false;
            Point p = pi.getLocation();
            if (p == null) return false;
            return p.x >= 0 && p.y >= 0 && p.x < r.width + 200 && p.y < r.height + 200;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 画面不可用时那句话（不猜环境，只说事实 + 两条可执行的路）。 */
    static String blindHint() {
        return "画面是纯色（抓到的是一张空画面）：多半是远程桌面已断开、或屏幕已锁 / 息屏。"
                + "要让 computer use 可用：连上远程桌面，或让物理桌面保持可见且解锁。";
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

    // ---------------------------------------------------------------- capture

    static Object capture(Args a, Host h) {
        String deny = h.need("screen.capture");
        if (deny != null) return deny;
        File dir = dirOf(h);
        if (dir == null) return "[screen] ok=0 读不到数据目录（conf=null）";
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] ds = ge.getScreenDevices();
            if (ds.length == 0) return "[screen] ok=0 这台机器没有可截的屏幕";
            int which = a.i("screen", 0);
            if (which < 0 || which >= ds.length) which = 0;
            GraphicsConfiguration gc = ds[which].getDefaultConfiguration();
            Rectangle rect = gc.getBounds();
            BufferedImage img = new Robot().createScreenCapture(rect);
            int colors = distinctColors(img);
            boolean uniform = colors <= 1;
            String hash = fingerprint(img);
            boolean mouse = mouseSane(rect);
            remember(h, img.getWidth(), img.getHeight(), hash, uniform);

            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new java.util.Date());
            File png = new File(dir, "screen-" + stamp + ".png");
            ImageIO.write(img, "png", png);
            long bytes = png.length();
            if (bytes > MAX_BYTES) {
                File jpg = new File(dir, "screen-" + stamp + ".jpg");
                // 质量 0.9：0.7 会把小字糊掉（实测能看出来）。只在无损 PNG 超 4 MiB 时才会走到这里。
                if (writeJpg(img, jpg, 0.9f)) { png.delete(); png = jpg; bytes = jpg.length(); }
            }

            String head = "[screen] capture ok=1 file=" + png.getAbsolutePath()
                    + " screen=" + which + " w=" + img.getWidth() + " h=" + img.getHeight()
                    + " bytes=" + bytes + " colors=" + colors + " uniform=" + (uniform ? 1 : 0)
                    + " hash=" + hash + " mouse=" + (mouse ? "sane" : "odd")
                    + " desktop=" + (uniform ? "unavailable" : "ok");
            return uniform ? (head + " hint=" + blindHint()) : head;
        } catch (Throwable e) {
            return "[screen] ok=0 截屏失败：" + e;
        }
    }

    static File dirOf(Host h) {
        try {
            if (h == null || h.conf() == null || h.conf().filesDir() == null) return null;
            File d = new File(h.conf().filesDir(), DIR);
            if (!d.isDirectory() && !d.mkdirs()) return null;
            return d;
        } catch (Throwable e) {
            return null;
        }
    }

    static boolean writeJpg(BufferedImage img, File f, float q) {
        ImageWriter w = null;
        try {
            w = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(q);
            ImageOutputStream os = ImageIO.createImageOutputStream(new FileOutputStream(f));
            w.setOutput(os);
            w.write(null, new IIOImage(img, null, null), p);
            os.close();
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            if (w != null) w.dispose();
        }
    }

    // ---------------------------------------------------------------- see

    static Object see(Args a, Host h) {
        String deny = h.need("screen.see");
        if (deny != null) return deny;
        String mv = Str.lower(Str.trim(h.conf() == null ? "auto" : h.conf().get("mediaVision", "auto")));
        if ("no".equals(mv) || "off".equals(mv) || "false".equals(mv)) {
            return "[screen] ok=0 图片能力被关（mediaVision=" + mv + "）";
        }
        File f = pick(a, h);
        if (f == null || !f.isFile()) return "[screen] ok=0 没有可看的截图（先 op=capture）";
        String ask = Str.trim(a.str("ask", ""));
        if (ask.isEmpty()) ask = "这是本机当前屏幕的截图。说清楚屏幕上有什么：开着的窗口/程序、主要区域的内容、能读到的文字（原样转写），以及任何明显可见的状态。别客套，直接说内容。如果这张图是纯色（全黑/全白），就直接说看不到任何内容，并说明整张图是什么颜色。";
        try {
            byte[] raw = readAll(f);
            if (raw == null) return "[screen] ok=0 读不到截图文件：" + f.getAbsolutePath();
            String mime = f.getName().toLowerCase().endsWith(".jpg") ? "image/jpeg" : "image/png";
            String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(raw);
            List<String> imgs = new ArrayList<String>();
            imgs.add(dataUrl);
            com.google.gson.JsonArray msgs = new com.google.gson.JsonArray();
            msgs.add(Msg.system("你是主 Agent 的眼睛：只描述屏幕上真实可见的东西，不推测意图、不给行动建议；看不到就说看不到。"));
            msgs.add(Msg.userWithImages(ask, imgs));
            Res r = h.ai().vision(msgs, new JsonObject());
            if (r == null || !r.ok()) return "[screen] ok=0 看图模型没看成：" + (r == null ? "null" : r.error);
            String body = Str.trim(r.text());
            if (body.isEmpty()) return "[screen] ok=0 看图模型没给出内容";
            return body;
        } catch (Throwable e) {
            return "[screen] ok=0 看图异常：" + e;
        }
    }

    static File pick(Args a, Host h) {
        String want = Str.trim(a.str("file", ""));
        if (Str.has(want)) {
            File f = new File(want);
            if (f.isAbsolute()) return f;
            File d = dirOf(h);
            return d == null ? f : new File(d, want);
        }
        File d = dirOf(h);
        if (d == null) return null;
        File[] all = d.listFiles();
        if (all == null || all.length == 0) return null;
        File best = null;
        for (File f : all) {
            if (!f.isFile()) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    static byte[] readAll(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
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
}
