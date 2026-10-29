import com.google.gson.JsonObject;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import sair.v4.auth.Caller;
import sair.v4.auth.OpList;
import sair.v4.kit.Fs;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 出图（工具 {@code image}）：程序化生成图片并直接发到当前会话。
 *
 * <p><b>零新依赖</b>：全部用 JDK 自带的 Java2D 画（{@link BufferedImage} + {@link Graphics2D}），
 * 二维码用框架 {@code plugins/lib} 里现成的 zxing，HTML 排版走 Edge 无头截图，滤镜走 ffmpeg。
 * 不接任何文生图模型 —— 那是另一条路（要 API key）。</p>
 *
 * <h3>能力边界（写在最前面，免得她乱用）</h3>
 * <p>能做：文字卡片/海报、数据图表、二维码、程序化图案背景、图片加工（裁剪/缩放/圆角/灰度/水印）、
 * HTML 排版截图。<b>不能做</b>写实人像/动物/风景 —— 那些要扩散模型。</p>
 */
public class ImageSkill implements Skill {

    static final String TOOL = "image";
    static final String NAME = "出图";
    static final int MAX_W = 2000;
    static final int MAX_H = 2000;

    @Override
    public void declareOps(OpList ops) {
        ops.add(TOOL, "card", "文字卡片/海报（标题+正文+署名，多种主题）");
        ops.add(TOOL, "chart", "数据图表（柱状/折线/饼图）");
        ops.add(TOOL, "qr", "二维码（可带说明文字）");
        ops.add(TOOL, "pattern", "程序化图案背景（渐变/星点/条纹/网格/噪点）");
        ops.add(TOOL, "html", "把 HTML 片段或网页截成图（Edge 无头）");
        ops.add(TOOL, "edit", "图片加工（裁剪/缩放/旋转/圆角/灰度/水印）");
    }

    @Override
    public Object call(Args a, Host h) {
        String op = Str.lower(Str.trim(a.str("op", "card")));
        if (op.isEmpty()) op = "card";
        if (h == null) return "[image] ok=0 宿主缺失";
        String deny = h.need("image." + op);
        if (deny != null) return deny;
        Cfg c = Cfg.load(h);
        if (!spend(c)) return "[image] ok=0 出图太频繁了（每分钟 " + c.perMin + " 张），缓一缓";

        File dir = outDir(h);
        if (dir == null) return "[image] ok=0 读不到数据目录";
        String stamp = new java.text.SimpleDateFormat("HHmmss-SSS").format(new java.util.Date());
        File out = new File(dir, "img-" + stamp + ".png");

        String err = null;
        int w = 0;
        int hh = 0;
        try {
            if ("card".equals(op)) {
                w = clamp(a.i("w", 0) > 0 ? a.i("w", 0) : 900);
                hh = clamp(a.i("h", 0) > 0 ? a.i("h", 0) : 520);
                drawCard(a, c, out, w, hh);
            } else if ("chart".equals(op)) {
                w = clamp(a.i("w", 0) > 0 ? a.i("w", 0) : 880);
                hh = clamp(a.i("h", 0) > 0 ? a.i("h", 0) : 520);
                drawChart(a, out, w, hh);
            } else if ("qr".equals(op)) {
                w = drawQr(a, out, clamp(a.i("size", 0) > 0 ? a.i("size", 0) : 560));
                hh = w;
            } else if ("pattern".equals(op)) {
                w = clamp(a.i("w", 0) > 0 ? a.i("w", 0) : 1200);
                hh = clamp(a.i("h", 0) > 0 ? a.i("h", 0) : 700);
                drawPattern(a, out, w, hh);
            } else if ("html".equals(op)) {
                w = a.i("w", 0) > 0 ? a.i("w", 0) : 900;
                hh = a.i("h", 0) > 0 ? a.i("h", 0) : 1200;
                htmlShot(a, c, h, dir, out, w, hh);
            } else if ("edit".equals(op)) {
                edit(a, c, h, out);
            } else {
                return "[image] ok=0 不支持的 op：" + op + "（只有 card/chart/qr/pattern/html/edit）";
            }
        } catch (Throwable t) {
            err = String.valueOf(t);
        }
        if (err != null) return "[image] ok=0 出图失败：" + Str.cut(err, 300);
        if (out == null || !out.isFile() || out.length() < 100L) return "[image] ok=0 没生成出图（或文件为空）";

        String sent = send(h, out);
        String head = "[image] ok=1 op=" + op + " file=" + out.getAbsolutePath()
                + " bytes=" + out.length() + (w > 0 ? (" w=" + w + " h=" + hh) : "") + " sent=" + sent;
        return head;
    }

    static int clamp(int v) { return Math.max(64, Math.min(MAX_H, Math.min(MAX_W, v))); }

    // ---------------------------------------------------------------- 发到会话

    static String send(Host h, File f) {
        try {
            Caller me = h.caller();
            if (me != null && !me.isConsole()) {
                boolean group = me.isGroup();
                long target = group ? me.groupId() : me.qq();
                JsonObject r = h.sayMedia(group, target, "image", f.getAbsolutePath());
                if (r != null && sair.v4.qq.Api.ok(r)) return "image";
            }
            JsonObject q = new JsonObject();
            q.addProperty("path", f.getAbsolutePath());
            String rr = h.call("sendimage", q);
            if (rr != null && rr.contains("ok=1")) return "sendimage";
            if (rr != null && !rr.isEmpty()) return "none（" + Str.cut(Str.oneLine(rr), 120) + "）";
        } catch (Throwable ignored) {
        }
        return "none";
    }

    // ---------------------------------------------------------------- 基础画布

    static Graphics2D canvas(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    static Font font(int size, boolean bold) {
        return new Font("Microsoft YaHei", bold ? Font.BOLD : Font.PLAIN, size);
    }

    /** 按宽度折中文/英文混排文本（逐字符量，中文字宽不一是关键）。 */
    static List<String> wrap(Graphics2D g, String text, int maxWidth) {
        List<String> out = new ArrayList<String>();
        String t = Str.nz(text).replace("\\n", "\n");
        for (String para : t.split("\n")) {
            FontMetrics fm = g.getFontMetrics();
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < para.length(); i++) {
                char ch = para.charAt(i);
                if (fm.stringWidth(line.toString() + ch) > maxWidth && line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                line.append(ch);
            }
            if (line.length() > 0) out.add(line.toString());
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    /** 主题背景：夜/晨/纸/纯色/极光。 */
    static void paintBg(Graphics2D g, int w, int h, String theme, String colorHex) {
        String t = Str.lower(Str.trim(theme));
        if ("纯色".equals(t) || "solid".equals(t)) {
            g.setColor(color(colorHex, new Color(240, 240, 245)));
            g.fillRect(0, 0, w, h);
        } else if ("晨".equals(t) || "dawn".equals(t)) {
            g.setPaint(new GradientPaint(0, 0, new Color(255, 236, 210), 0, h, new Color(255, 205, 190)));
            g.fillRect(0, 0, w, h);
        } else if ("纸".equals(t) || "paper".equals(t)) {
            g.setColor(new Color(248, 245, 236));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(0, 0, 0, 8));
            java.util.Random r = new java.util.Random(3);
            for (int i = 0; i < w * h / 900; i++) g.fillRect(r.nextInt(w), r.nextInt(h), 1, 1);
        } else if ("极光".equals(t) || "aurora".equals(t)) {
            g.setPaint(new GradientPaint(0, 0, new Color(10, 30, 60), w, h, new Color(20, 90, 110)));
            g.fillRect(0, 0, w, h);
            g.setPaint(new GradientPaint(0, h / 3, new Color(90, 240, 200, 70), w, h, new Color(120, 120, 255, 40)));
            g.fillRect(0, 0, w, h);
        } else {
            g.setPaint(new GradientPaint(0, 0, new Color(18, 24, 58), 0, h, new Color(52, 26, 74)));
            g.fillRect(0, 0, w, h);
            java.util.Random r = new java.util.Random(7);
            for (int i = 0; i < 140; i++) {
                g.setColor(new Color(255, 255, 255, 60 + r.nextInt(160)));
                int s = r.nextInt(3) == 0 ? 2 : 1;
                g.fillOval(r.nextInt(w), r.nextInt((int) (h * 0.62)), s, s);
            }
        }
    }

    static Color color(String hex, Color def) {
        String s = Str.trim(hex);
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 6) {
            try {
                return new Color(Integer.parseInt(s, 16));
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    static int textColorFor(String theme) {
        String t = Str.lower(Str.trim(theme));
        if ("晨".equals(t) || "dawn".equals(t) || "纸".equals(t) || "paper".equals(t)) return 0;
        return 1;
    }

    // ---------------------------------------------------------------- card

    static void drawCard(Args a, Cfg c, File out, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(img);
        String theme = Str.trim(a.str("theme", c.theme));
        paintBg(g, w, h, theme, a.str("color", ""));
        boolean dark = textColorFor(theme) == 1;
        Color main = dark ? new Color(255, 250, 235) : new Color(40, 36, 48);
        Color sub = dark ? new Color(226, 218, 240) : new Color(96, 88, 104);

        String title = Str.trim(a.str("title", ""));
        String body = Str.trim(a.str("text", ""));
        String sign = Str.trim(a.str("sign", ""));
        if (title.isEmpty() && body.isEmpty()) throw new IllegalArgumentException("card 至少要 title 或 text");

        int x = Math.max(48, w / 12);
        int y = Math.max(80, h / 4);
        if (!title.isEmpty()) {
            g.setFont(font(Math.max(28, Math.min(64, w / 15)), true));
            g.setColor(main);
            for (String line : wrap(g, title, w - x * 2)) {
                g.drawString(line, x, y);
                y += g.getFontMetrics().getHeight() + 6;
            }
            y += 10;
        }
        if (!body.isEmpty()) {
            g.setFont(font(Math.max(16, Math.min(30, w / 32)), false));
            g.setColor(sub);
            for (String line : wrap(g, body, w - x * 2)) {
                g.drawString(line, x, y);
                y += g.getFontMetrics().getHeight() + 8;
            }
        }
        if (!sign.isEmpty()) {
            int sy = Math.min(h - 40, y + 28);
            g.setColor(dark ? new Color(255, 246, 214, 150) : new Color(120, 110, 130, 120));
            g.fillRect(x, sy - 26, Math.min(220, w / 4), 3);
            g.setFont(font(Math.max(14, Math.min(22, w / 40)), false));
            g.setColor(sub);
            g.drawString(sign, x, sy);
        }
        g.setColor(new Color(255, 255, 255, dark ? 40 : 60));
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(14, 14, w - 29, h - 29, 26, 26));
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    // ---------------------------------------------------------------- chart

    static void drawChart(Args a, File out, int w, int h) throws Exception {
        String kind = Str.lower(Str.trim(a.str("kind", "bar")));
        String data = Str.trim(a.str("data", ""));
        if (data.isEmpty()) throw new IllegalArgumentException("chart 需要 data，形如 一月=120,二月=95");
        List<String> labels = new ArrayList<String>();
        List<Double> vals = new ArrayList<Double>();
        for (String part : data.split("[,，;；]")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int i = p.lastIndexOf('=');
            if (i < 0) i = p.lastIndexOf(':');
            if (i <= 0) continue;
            try {
                labels.add(p.substring(0, i).trim());
                vals.add(Double.valueOf(p.substring(i + 1).trim()));
            } catch (Throwable ignored) {
            }
        }
        if (labels.isEmpty()) throw new IllegalArgumentException("data 解析不出任何 标签=数值");
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(img);
        boolean dark = true;
        g.setPaint(new GradientPaint(0, 0, new Color(24, 26, 46), 0, h, new Color(36, 30, 58)));
        g.fillRect(0, 0, w, h);
        String title = Str.trim(a.str("title", ""));
        int top = 70;
        if (!title.isEmpty()) {
            g.setFont(font(30, true));
            g.setColor(new Color(255, 250, 235));
            g.drawString(title, 48, 62);
            top = 96;
        }
        Color accent = color(a.str("color", ""), new Color(120, 200, 255));
        if ("pie".equals(kind) || "饼".equals(kind)) {
            double sum = 0;
            for (Double v : vals) sum += v;
            if (sum <= 0) throw new IllegalArgumentException("饼图的数据和必须 > 0");
            int cx = w / 2;
            int cy = top + (h - top - 40) / 2;
            int r = Math.min(w, h - top) / 2 - 60;
            double ang = 0;
            Color[] pal = { accent, new Color(255, 190, 120), new Color(160, 235, 170), new Color(240, 160, 210), new Color(180, 170, 255) };
            for (int i = 0; i < vals.size(); i++) {
                double sweep = 360.0 * vals.get(i) / sum;
                g.setColor(pal[i % pal.length]);
                g.fillArc(cx - r, cy - r, r * 2, r * 2, (int) Math.round(ang), (int) Math.round(sweep));
                ang += sweep;
            }
            g.setColor(new Color(24, 26, 46));
            g.fillOval(cx - r / 2, cy - r / 2, r, r);
            int ly = top + 20;
            g.setFont(font(18, false));
            for (int i = 0; i < labels.size(); i++) {
                g.setColor(pal[i % pal.length]);
                g.fillRect(w - 260, ly - 14, 14, 14);
                g.setColor(new Color(230, 226, 240));
                g.drawString(labels.get(i) + "  " + fmt(vals.get(i)), w - 238, ly);
                ly += 26;
            }
        } else if ("line".equals(kind) || "折线".equals(kind)) {
            int x0 = 70, y0 = top + 20, x1 = w - 60, y1 = h - 70;
            double max = 0;
            for (Double v : vals) max = Math.max(max, v);
            if (max <= 0) max = 1;
            g.setColor(new Color(255, 255, 255, 60));
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i <= 4; i++) {
                int yy = y0 + (y1 - y0) * i / 4;
                g.drawLine(x0, yy, x1, yy);
            }
            g.setColor(accent);
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int px = 0, py = 0;
            for (int i = 0; i < vals.size(); i++) {
                int xx = vals.size() == 1 ? (x0 + x1) / 2 : x0 + (x1 - x0) * i / (vals.size() - 1);
                int yy = (int) (y1 - (y1 - y0) * (vals.get(i) / max));
                if (i > 0) g.drawLine(px, py, xx, yy);
                px = xx;
                py = yy;
            }
            g.setFont(font(16, false));
            for (int i = 0; i < vals.size(); i++) {
                int xx = vals.size() == 1 ? (x0 + x1) / 2 : x0 + (x1 - x0) * i / (vals.size() - 1);
                int yy = (int) (y1 - (y1 - y0) * (vals.get(i) / max));
                g.setColor(new Color(255, 250, 235));
                g.fillOval(xx - 5, yy - 5, 10, 10);
                g.setColor(new Color(220, 216, 235));
                String lab = labels.get(i);
                g.drawString(lab, xx - g.getFontMetrics().stringWidth(lab) / 2, y1 + 30);
            }
        } else {
            int x0 = 70, y0 = top + 20, x1 = w - 60, y1 = h - 80;
            double max = 0;
            for (Double v : vals) max = Math.max(max, v);
            if (max <= 0) max = 1;
            int n = vals.size();
            int gap = 26;
            int bw = Math.max(10, (x1 - x0 - gap * (n - 1)) / Math.max(1, n));
            for (int i = 0; i < n; i++) {
                int bx = x0 + i * (bw + gap);
                int bh = (int) ((y1 - y0) * (vals.get(i) / max));
                g.setPaint(new GradientPaint(0, y1 - bh, accent.brighter(), 0, y1, accent.darker()));
                g.fill(new RoundRectangle2D.Double(bx, y1 - bh, bw, bh, 10, 10));
                g.setFont(font(15, false));
                g.setColor(new Color(240, 236, 250));
                String v = fmt(vals.get(i));
                g.drawString(v, bx + bw / 2 - g.getFontMetrics().stringWidth(v) / 2, y1 - bh - 8);
                String lab = labels.get(i);
                g.drawString(lab, bx + bw / 2 - g.getFontMetrics().stringWidth(lab) / 2, y1 + 28);
            }
        }
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.0001) return String.valueOf((long) Math.rint(v));
        return String.format("%.1f", v);
    }

    // ---------------------------------------------------------------- qr

    static int drawQr(Args a, File out, int size) throws Exception {
        String text = Str.trim(a.str("text", ""));
        if (text.isEmpty()) throw new IllegalArgumentException("qr 需要 text");
        int m = Math.max(120, size);
        int px = 48;
        int qr = m - px * 2;
        com.google.zxing.common.BitMatrix bm = new com.google.zxing.qrcode.QRCodeWriter()
                .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, qr, qr);
        BufferedImage q = new BufferedImage(bm.getWidth(), bm.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int yy = 0; yy < bm.getHeight(); yy++) {
            for (int xx = 0; xx < bm.getWidth(); xx++) {
                q.setRGB(xx, yy, bm.get(xx, yy) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        String caption = Str.trim(a.str("caption", ""));
        int capH = caption.isEmpty() ? 0 : 56;
        BufferedImage img = new BufferedImage(m, m + capH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(img);
        g.setColor(new Color(255, 255, 255));
        g.fillRect(0, 0, m, m + capH);
        g.drawImage(q, px, px, null);
        if (capH > 0) {
            g.setColor(new Color(40, 40, 48));
            g.setFont(font(Math.max(16, m / 26), false));
            g.drawString(caption, px, m + 38);
        }
        g.dispose();
        ImageIO.write(img, "png", out);
        return m;
    }

    // ---------------------------------------------------------------- pattern

    static void drawPattern(Args a, File out, int w, int h) throws Exception {
        String kind = Str.lower(Str.trim(a.str("kind", "gradient")));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(img);
        Color c1 = color(a.str("color1", ""), new Color(18, 24, 58));
        Color c2 = color(a.str("color2", ""), new Color(52, 26, 74));
        g.setPaint(new GradientPaint(0, 0, c1, w, h, c2));
        g.fillRect(0, 0, w, h);
        java.util.Random r = new java.util.Random(w * 31L + h);
        if ("stars".equals(kind) || "星点".equals(kind)) {
            for (int i = 0; i < w * h / 1200; i++) {
                g.setColor(new Color(255, 255, 255, 60 + r.nextInt(180)));
                int s = r.nextInt(4) == 0 ? 2 : 1;
                g.fillOval(r.nextInt(w), r.nextInt(h), s, s);
            }
        } else if ("stripes".equals(kind) || "条纹".equals(kind)) {
            g.setColor(new Color(255, 255, 255, 26));
            for (int x = -h; x < w; x += 42) g.fillPolygon(new int[] { x, x + 18, x + 18 + h, x + h }, new int[] { 0, 0, h, h }, 4);
        } else if ("grid".equals(kind) || "网格".equals(kind)) {
            g.setColor(new Color(255, 255, 255, 30));
            for (int x = 0; x < w; x += 40) g.drawLine(x, 0, x, h);
            for (int y = 0; y < h; y += 40) g.drawLine(0, y, w, y);
        } else if ("noise".equals(kind) || "噪点".equals(kind)) {
            for (int i = 0; i < w * h / 60; i++) {
                g.setColor(new Color(r.nextInt(255), r.nextInt(255), r.nextInt(255), 26));
                g.fillRect(r.nextInt(w), r.nextInt(h), 2, 2);
            }
        } else if ("blobs".equals(kind) || "光斑".equals(kind)) {
            for (int i = 0; i < 14; i++) {
                int rr = 60 + r.nextInt(Math.max(60, w / 4));
                g.setColor(new Color(r.nextInt(255), r.nextInt(255), r.nextInt(255), 22));
                g.fillOval(r.nextInt(w) - rr, r.nextInt(h) - rr, rr * 2, rr * 2);
            }
        }
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    // ---------------------------------------------------------------- html（Edge 无头截图）

    /** 把 HTML 片段或 URL 用 Edge 无头截成 PNG（每张一个临时 profile，不跟正在跑的 Edge 抢锁）。 */
    static void htmlShot(Args a, Cfg c, Host h, File dir, File out, int w, int hh) throws Exception {
        String html = a.str("html", "");
        String url = Str.trim(a.str("url", ""));
        File page = null;
        if (!Str.has(url)) {
            if (!Str.has(html)) throw new IllegalArgumentException("html 需要 html 片段或 url");
            page = new File(dir, "page-" + System.currentTimeMillis() + ".html");
            String doc = html.trim().toLowerCase().startsWith("<!doctype") || html.trim().toLowerCase().startsWith("<html")
                    ? html : ("<!doctype html><meta charset=utf-8><body style='margin:0;font-family:\"Microsoft YaHei\";overflow:hidden'>" + html + "</body>");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(page);
            try { fo.write(doc.getBytes("UTF-8")); } finally { fo.close(); }
            url = page.toURI().toString();
        } else {
            String low = url.toLowerCase();
            if (!low.startsWith("http://") && !low.startsWith("https://")) {
                throw new IllegalArgumentException("url 只接受 http/https（本地排版请用 html 片段）");
            }
        }
        String edge = Str.blank(c.edge) ? "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe" : c.edge;
        if (!new File(edge).isFile()) throw new IllegalStateException("找不到 Edge：" + edge);
        File profile = new File(dir, "prof-" + System.currentTimeMillis());
        List<String> cmd = new ArrayList<String>();
        cmd.add(edge);
        cmd.add("--headless=new");
        cmd.add("--disable-gpu");
        cmd.add("--hide-scrollbars");
        cmd.add("--no-first-run");
        cmd.add("--disable-extensions");
        cmd.add("--disable-features=Translate,MediaRouter");
        cmd.add("--user-data-dir=" + profile.getAbsolutePath());
        cmd.add("--window-size=" + w + "," + hh);
        cmd.add("--screenshot=" + out.getAbsolutePath());
        cmd.add(url);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        java.io.InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        long end = System.currentTimeMillis() + 60000L;
        while (p.isAlive() && System.currentTimeMillis() < end) {
            try { while (in.available() > 0 && in.read(buf) > 0) { } } catch (Throwable ignored) { }
            Thread.sleep(100L);
        }
        if (p.isAlive()) p.destroyForcibly();
        try { p.waitFor(10L, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) { }
        del(profile);
        if (page != null) del(page);          // 截完就删临时 HTML，别在 files/out 里留垃圾
        if (!out.isFile() || out.length() < 100L) throw new IllegalStateException("Edge 没截出图（检查 HTML 是否有内容/尺寸是否合理）");
    }

    static void del(File f) {
        try {
            if (f.isDirectory()) { File[] cs = f.listFiles(); if (cs != null) for (File x : cs) del(x); }
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- edit（图片加工）

    static void edit(Args a, Cfg c, Host h, File out) throws Exception {
        String src = Str.trim(a.str("src", ""));
        if (src.isEmpty()) throw new IllegalArgumentException("edit 需要 src（本地图片路径）");
        File f = new File(src);
        if (!f.isAbsolute() && h.conf() != null && h.conf().root() != null) f = new File(h.conf().root(), src);
        f = f.getCanonicalFile();
        String fd = h.localFileDeny(h.subject(), f.getPath());
        if (fd != null) throw new SecurityException(fd);
        if (!f.isFile()) throw new IllegalArgumentException("读不到这张图：" + f);
        BufferedImage in = ImageIO.read(f);
        if (in == null) throw new IllegalArgumentException("不是能识别的图片格式");
        String act = Str.lower(Str.trim(a.str("action", "scale")));
        BufferedImage img = in;
        if ("crop".equals(act)) {
            int x = Math.max(0, a.i("x", 0)), y = Math.max(0, a.i("y", 0));
            int w = Math.min(in.getWidth() - x, a.i("w", in.getWidth()));
            int hh = Math.min(in.getHeight() - y, a.i("h", in.getHeight()));
            if (w <= 0 || hh <= 0) throw new IllegalArgumentException("crop 区域为空");
            img = in.getSubimage(x, y, w, hh);
        } else if ("scale".equals(act)) {
            int w = a.i("w", 0), hh = a.i("h", 0);
            if (w <= 0 && hh <= 0) w = 800;
            if (w <= 0) w = (int) (in.getWidth() * (hh / (double) in.getHeight()));
            if (hh <= 0) hh = (int) (in.getHeight() * (w / (double) in.getWidth()));
            w = Math.max(1, Math.min(MAX_W, w));
            hh = Math.max(1, Math.min(MAX_H, hh));
            BufferedImage o = new BufferedImage(w, hh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas(o);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(in, 0, 0, w, hh, null);
            g.dispose();
            img = o;
        } else if ("rotate".equals(act)) {
            int deg = a.i("deg", 90) % 360;
            double rad = Math.toRadians(deg);
            int w = in.getWidth(), hh = in.getHeight();
            int nw = (int) Math.abs(w * Math.cos(rad)) + (int) Math.abs(hh * Math.sin(rad));
            int nh = (int) Math.abs(w * Math.sin(rad)) + (int) Math.abs(hh * Math.cos(rad));
            BufferedImage o = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas(o);
            g.translate(nw / 2.0, nh / 2.0);
            g.rotate(rad);
            g.drawImage(in, -w / 2, -hh / 2, null);
            g.dispose();
            img = o;
        } else if ("round".equals(act)) {
            int r = Math.max(2, a.i("radius", Math.min(in.getWidth(), in.getHeight()) / 8));
            BufferedImage o = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas(o);
            g.setClip(new RoundRectangle2D.Double(0, 0, in.getWidth(), in.getHeight(), r * 2, r * 2));
            g.drawImage(in, 0, 0, null);
            g.dispose();
            img = o;
        } else if ("gray".equals(act)) {
            BufferedImage o = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < in.getHeight(); y++) {
                for (int x = 0; x < in.getWidth(); x++) {
                    int rgb = in.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    int al = (rgb >>> 24) & 0xFF;
                    int v = (int) (0.299 * r + 0.587 * gg + 0.114 * b);
                    o.setRGB(x, y, (al << 24) | (v << 16) | (v << 8) | v);
                }
            }
            img = o;
        } else if ("wm".equals(act) || "watermark".equals(act)) {
            String text = Str.trim(a.str("text", ""));
            if (text.isEmpty()) throw new IllegalArgumentException("wm 需要 text");
            BufferedImage o = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas(o);
            g.drawImage(in, 0, 0, null);
            int size = Math.max(14, Math.min(56, in.getWidth() / 18));
            g.setFont(font(size, true));
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(text), th = fm.getHeight();
            int x = in.getWidth() - tw - 20, y = in.getHeight() - 20;
            g.setColor(new Color(0, 0, 0, 90));
            g.drawString(text, x + 1, y + 1);
            g.setColor(new Color(255, 255, 255, 200));
            g.drawString(text, x, y);
            g.dispose();
            img = o;
        } else {
            throw new IllegalArgumentException("不支持的 edit action：" + act + "（crop/scale/rotate/round/gray/wm）");
        }
        ImageIO.write(img, "png", out);
    }

    // ---------------------------------------------------------------- 配置与预算

    static File outDir(Host h) {
        try {
            if (h == null || h.conf() == null || h.conf().filesDir() == null) return null;
            File d = new File(h.conf().filesDir(), "out");
            if (!d.isDirectory() && !d.mkdirs()) return null;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final Object LOCK = new Object();
    private static long windowStart = 0L;
    private static int windowCount = 0;

    static boolean spend(Cfg c) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - windowStart > 60000L) {
                windowStart = now;
                windowCount = 0;
            }
            if (windowCount >= c.perMin) return false;
            windowCount++;
            return true;
        }
    }

    /** 技能自己的配置：{@code skills/出图/prompt.md}，一行「键: 值」。 */
    static final class Cfg {
        String theme = "夜";
        String edge = "";
        int perMin = 8;

        static Cfg load(Host h) {
            Cfg c = new Cfg();
            try {
                if (h == null || h.conf() == null) return c;
                File dir = h.skillDir();
                if (dir == null) dir = new File(h.conf().skillsDir(), NAME);
                File f = new File(dir, "prompt.md");
                if (!f.isFile()) return c;
                for (String raw : Fs.read(f, "").split("\\r?\\n")) {
                    String line = raw == null ? "" : raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf(':');
                    if (i <= 0) continue;
                    String k = line.substring(0, i).trim();
                    String v = line.substring(i + 1).trim();
                    if ("默认主题".equals(k) && !v.isEmpty()) c.theme = v;
                    else if ("edge".equals(k) && !v.isEmpty()) c.edge = v;
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
