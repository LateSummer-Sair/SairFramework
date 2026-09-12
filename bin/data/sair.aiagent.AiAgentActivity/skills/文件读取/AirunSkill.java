import java.util.Map;

import sair.aiagent.core.ConfirmationGate;
import sair.aiagent.util.FileUtils;

/**
 * 文件读取（工具名 {@code readfile}）—— 从 {@code AgentActionHandler.executeReadFile()} 剥离而来。
 *
 * <p>忠实保留原行为：先过确认闸门 → 调 FileUtils 读（支持 offset/limit 分块）→ 拼回同样的文本格式。
 * 唯一变化是"谁在跑这段代码"：以前是插件里的 Java 方法，现在是这个技能文件夹里的源码，
 * 因此你改完保存即热重载，不用重新编译插件。</p>
 */
public class AirunSkill {

    /** 声明了 params（path/offset/limit）+ context: true（execq 通道要按同样的规则校验路径）。 */
    public String airun(Map<String, Object> args, Map<String, Object> ctx) {
        String path = str(args.get("path"));
        int offset = (int) num(args.get("offset"), 0L);
        int limit = (int) num(args.get("limit"), -1L);
        String p = resolveExecqPath(ctx, path, false);
        if (p == null) return "[readfile] 无权限：execq 通道必须提供明确文件路径";
        return read(p, offset, limit);
    }

    /**
     * 复刻原 {@code ToolDispatcher.resolveQqPath()}：
     * execq（非 execs）通道必须给明确路径，不给就拒绝；其它通道原样透传。
     */
    private static String resolveExecqPath(Map<String, Object> ctx, String path, boolean allowEmptyAsDataDir) {
        String channel = (ctx == null) ? null : String.valueOf(ctx.get("channel"));
        if (!"execq".equals(channel)) return path;
        String p = (path == null) ? "" : path.trim();
        if (p.isEmpty()) {
            if (!allowEmptyAsDataDir) return null;
            Object dd = ctx.get("data_dir");
            p = (dd == null) ? "" : String.valueOf(dd);
        }
        return p.isEmpty() ? null : p;
    }

    /** 兼容旧写法：直接给 JSON 字符串时也能用。 */
    public String airun(String argsJson) {
        return read(extract(argsJson, "path"), 0, -1);
    }

    private String read(String path, int offset, int limit) {
        if (path == null || path.trim().isEmpty()) return "文件路径为空。";

        // ★ 确认闸门留在 Java：技能只能调用，不能绕过
        if (!ConfirmationGate.confirm("readfile", "读取文件: " + path)) return "读取文件被拒绝。";
        System.out.println("[readfile] 读取: " + path
                + ((offset > 0 || limit > 0) ? " (offset=" + offset + ", limit=" + limit + ")" : ""));

        String content = FileUtils.readFile(path, offset, limit);
        String rangeInfo = (offset > 0 || limit > 0)
                ? " [分块读取: offset=" + offset + ", limit=" + (limit > 0 ? limit : "全部") + "]"
                : "";
        return "文件 [" + path + "]" + rangeInfo + ":\n" + content;
    }

    // ==================== 小工具 ====================

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static long num(Object v, long def) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return (v == null) ? def : Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 极简 JSON 取字段（仅供 airun(String) 兼容路径）。 */
    private static String extract(String json, String key) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }
}
