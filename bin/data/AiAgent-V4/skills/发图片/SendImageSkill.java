import com.google.gson.JsonObject;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import sair.v4.auth.Caller;
import sair.v4.kit.Fs;
import sair.v4.kit.Str;
import sair.v4.qq.Api;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 发图片（工具 {@code sendimage}）：把图片（URL / 本地绝对路径）或一段渲染成 PNG 的文字发到当前会话。
 *
 * <p>落点由 {@link Host#caller()} 决定（群 → 群，私聊 → 私聊），技能不改落点、不改判定。
 * NapCat 未连接时按基板口径直接回「{@link Api#NOT_CONNECTED}」。</p>
 *
 * <p>权限：单动作工具，op 就是工具名 {@code sendimage}（技能管控表 {@code skillctl.json} 里那一行）——
 * 没放行 = 未授权 = 不可用（`ALLUSER` 按表判；{@code MASTER} 与她本人 {@code SYSTEM} 恒全权）。
 * 发图本身是对外动作，平台侧还有基板给 NapCat 装的那道闸门兜着。</p>
 *
 * <p>文字渲染走 Java2D 离屏绘制（headless 可用），字体按可用性挑选，避免中文变方块。</p>
 */
public class SendImageSkill implements Skill {

    /** 候选字体（按优先级）：先中文常用字体，最后退回逻辑字体。 */
    private static final String[] FONTS = {
            "Microsoft YaHei", "微软雅黑", "SimHei", "黑体", "SimSun", "宋体",
            "Noto Sans CJK SC", "Source Han Sans SC", "PingFang SC", "SansSerif"
    };

    private static final int FONT_SIZE = 28;
    private static final int LINE_H = 44;
    private static final int PAD = 28;
    private static final int MAX_WIDTH = 1600;
    private static final int UNITS_PER_LINE = 44;      // 半角字符宽度单位
    private static final int MAX_LINES = 120;

    @Override
    public void declareOps(sair.v4.auth.OpList ops) {
        // 单动作工具：op = 工具名
        ops.add("sendimage", "", "往当前会话发一张图（或把文字渲染成图再发）");
    }

    @Override
    public Object call(Args a, Host h) {
        // op 判定（动作分支开头）：本工具只有一个动作，op 就是工具名 sendimage
        String deny = h.need("sendimage");
        if (deny != null) return deny;
        if (!h.napcatConnected()) return Api.NOT_CONNECTED;

        Caller c = h.caller();
        if (c == null) return "[sendimage] 没有调用者身份，不知道发到哪里。";
        if (c.isConsole()) return "[sendimage] 本地控制台没有可发送的 QQ 会话，请在群或私聊里用。";

        String image = Str.trim(a.str("image"));
        String text = a.str("text");
        if (image.isEmpty() && Str.blank(text)) return a.missing("image（或 text）");

        boolean group = c.toGroup();
        long id = group ? c.groupId() : c.qq();
        List<String> done = new ArrayList<String>();
        List<String> bad = new ArrayList<String>();

        if (!image.isEmpty()) {
            JsonObject r = group ? h.napcat().sendGroupImage(id, image) : h.napcat().sendPrivateImage(id, image);
            if (Api.ok(r)) done.add("图片 " + Str.cut(image, 80));
            else bad.add("图片发送失败：" + err(r));
        }
        if (!Str.blank(text)) {
            File png = render(h, text);
            if (png == null) {
                bad.add("文字渲染成图片失败（Java2D 或磁盘不可用）");
            } else {
                JsonObject r = group
                        ? h.napcat().sendGroupImage(id, png.getAbsolutePath())
                        : h.napcat().sendPrivateImage(id, png.getAbsolutePath());
                if (Api.ok(r)) done.add("文字图 " + png.getName() + "（" + png.length() + " 字节）");
                else bad.add("文字图发送失败：" + err(r));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(group ? "发到群 " + id : "私聊发给 " + id);
        sb.append(done.isEmpty() ? "：" : "：成功 " + join(done, "、"));
        if (!bad.isEmpty()) sb.append(done.isEmpty() ? "" : "；").append(join(bad, "；"));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 渲染

    private static File render(Host h, String text) {
        try {
            File dir = h.conf().filesDir();
            Fs.mkdirs(dir);
            List<String> lines = wrap(text);
            Font font = pick(FONT_SIZE);

            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            Graphics2D pg = probe.createGraphics();
            pg.setFont(font);
            FontMetrics fm = pg.getFontMetrics();
            int widest = 0;
            for (String l : lines) widest = Math.max(widest, fm.stringWidth(l));
            pg.dispose();

            int w = Math.max(240, Math.min(widest + PAD * 2, MAX_WIDTH));
            int hgt = Math.max(LINE_H + PAD * 2, lines.size() * LINE_H + PAD * 2);
            BufferedImage img = new BufferedImage(w, hgt, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(250, 250, 248));
            g.fillRect(0, 0, w, hgt);
            g.setColor(new Color(206, 206, 202));
            g.drawRect(0, 0, w - 1, hgt - 1);
            g.setFont(font);
            g.setColor(new Color(30, 30, 34));
            int y = PAD + fm.getAscent();
            for (String l : lines) {
                g.drawString(l, PAD, y);
                y += LINE_H;
            }
            g.dispose();

            File out = new File(dir, "text_" + System.currentTimeMillis() + ".png");
            return ImageIO.write(img, "png", out) ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 先按 \n 切行，再按宽度单位折行（中文按 2 个宽度算），并限制总行数。 */
    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<String>();
        String t = Str.nz(text).replace("\r\n", "\n").replace('\r', '\n');
        for (String raw : t.split("\n", -1)) {
            String line = raw.replace("\t", "    ");
            StringBuilder cur = new StringBuilder();
            int units = 0;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                int u = ch < 128 ? 1 : 2;
                if (units + u > UNITS_PER_LINE && cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    units = 0;
                    if (out.size() >= MAX_LINES) return tail(out);
                }
                cur.append(ch);
                units += u;
            }
            out.add(cur.toString());
            if (out.size() >= MAX_LINES) return tail(out);
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    private static List<String> tail(List<String> lines) {
        if (lines.size() >= MAX_LINES) lines.set(lines.size() - 1, lines.get(lines.size() - 1) + " …");
        return lines;
    }

    private static Font pick(int size) {
        try {
            Set<String> avail = new HashSet<String>();
            String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            for (String n : names) avail.add(n);
            for (String f : FONTS) {
                if (avail.contains(f)) return new Font(f, Font.PLAIN, size);
            }
        } catch (Throwable ignored) {
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, size);
    }

    // ---------------------------------------------------------------- 小工具

    private static String err(JsonObject r) {
        String e = Api.error(r);
        return Str.has(e) ? e : "Napcat 没有返回可识别结果";
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }
}
