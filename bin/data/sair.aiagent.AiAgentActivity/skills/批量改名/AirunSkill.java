import java.io.File;
import java.util.Map;
import java.util.regex.Pattern;

/** 批量改名（工具名 {@code batchrename}）—— 从 {@code TagHandlers.executeBatchRename()} 剥离而来。 */
public class AirunSkill {

    public String airun(Map<String, Object> args) {
        String dir = str(args.get("dir"));
        String pattern = str(args.get("pattern"));
        String replacement = str(args.get("replacement"));
        boolean preview = bool(args.get("preview"));
        if (dir == null) dir = "";
        if (pattern == null) pattern = "";
        if (replacement == null) replacement = "";

        if (dir.isEmpty() || pattern.isEmpty()) return "[batchrename] needs dir and pattern params";
        File d = new File(dir);
        if (!d.exists() || !d.isDirectory()) return "[batchrename] dir not found: " + dir;
        File[] files = d.listFiles();
        if (files == null || files.length == 0) return "[batchrename] no files in: " + dir;

        Pattern pat;
        try {
            pat = Pattern.compile(pattern);
        } catch (Exception e) {
            return "[batchrename] invalid regex: " + pattern;
        }
        StringBuilder sb = new StringBuilder("[batchrename] ");
        if (preview) sb.append("PREVIEW:\n");
        else sb.append((replacement.isEmpty() ? "matches" : "rename") + ":\n");

        int count = 0;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            java.util.regex.Matcher m = pat.matcher(name);
            if (m.find()) {
                String newName = replacement.isEmpty() ? name : m.replaceAll(replacement);
                sb.append("  ").append(name).append(" -> ").append(newName).append("\n");
                if (!preview && !replacement.isEmpty()) {
                    File dest = new File(d, newName);
                    if (dest.exists()) { sb.append("    SKIP: already exists\n"); continue; }
                    if (f.renameTo(dest)) count++;
                } else if (preview) {
                    count++;
                }
            }
        }
        sb.append("  Total: ").append(count).append(" files");
        return sb.toString();
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    private static boolean bool(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        return v != null && ("true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v)));
    }
}
