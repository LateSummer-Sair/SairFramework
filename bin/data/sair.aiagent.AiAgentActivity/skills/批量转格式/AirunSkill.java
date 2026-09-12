import java.io.File;
import java.util.Map;

/** 批量转格式（工具名 {@code batchconvert}）—— 从 {@code TagHandlers.executeBatchConvert()} 剥离而来。 */
public class AirunSkill {

    public String airun(Map<String, Object> args) {
        String dir = str(args.get("dir"));
        String from = str(args.get("from"));
        String to = str(args.get("to"));
        if (dir == null) dir = "";
        if (from == null) from = "";
        if (to == null) to = "";

        if (dir.isEmpty() || from.isEmpty() || to.isEmpty())
            return "[batchconvert] needs dir, from, to params";
        File d = new File(dir);
        if (!d.exists() || !d.isDirectory()) return "[batchconvert] dir not found: " + dir;
        File[] files = d.listFiles();
        if (files == null || files.length == 0) return "[batchconvert] no files in: " + dir;

        StringBuilder sb = new StringBuilder("[batchconvert]");
        int count = 0;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (!f.isFile() || !name.endsWith("." + from.toLowerCase())) continue;
            String base = f.getName().substring(0, f.getName().length() - from.length() - 1);
            File dest = new File(d, base + "." + to);
            try {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
                if (img == null) {
                    sb.append("\n  FAIL: ").append(f.getName()).append(" (not readable)");
                    continue;
                }
                if (!javax.imageio.ImageIO.write(img, to, dest)) {
                    sb.append("\n  FAIL: ").append(f.getName()).append(" (no writer for ").append(to).append(")");
                    continue;
                }
                sb.append("\n  ").append(f.getName()).append(" -> ").append(dest.getName());
                count++;
            } catch (Exception e) {
                sb.append("\n  ERROR: ").append(f.getName()).append(" - ").append(e.getMessage());
            }
        }
        sb.append("\n  Converted: ").append(count).append(" files");
        return sb.toString();
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
}
