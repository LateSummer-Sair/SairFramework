import java.util.Map;

import sair.aiagent.core.ConfirmationGate;
import sair.aiagent.util.FileUtils;

/** 文件查找（工具名 {@code findfile}）—— 从 {@code AgentActionHandler.executeFindFile()} 剥离而来。 */
public class AirunSkill {

    public String airun(Map<String, Object> args, Map<String, Object> ctx) {
        String path = str(args.get("path"));
        String keyword = str(args.get("keyword"));
        if (keyword == null) keyword = "";
        String p = resolveExecqPath(ctx, path);
        if (p == null) return "[findfile] 无权限：execq 通道缺少数据目录";
        if (!ConfirmationGate.confirm("findfile", "查找文件: " + p)) return "查找文件被拒绝。";
        System.out.println("[findfile] 查找: " + p + (keyword.isEmpty() ? "" : " (关键词:" + keyword + ")"));
        return FileUtils.findFiles(p, keyword);
    }

    /** 复刻原 {@code resolveQqPath(ctx, path, true)}：execq 下空路径回落到数据目录。 */
    private static String resolveExecqPath(Map<String, Object> ctx, String path) {
        String channel = (ctx == null) ? null : String.valueOf(ctx.get("channel"));
        if (!"execq".equals(channel)) return path;
        String p = (path == null) ? "" : path.trim();
        if (p.isEmpty()) {
            Object dd = ctx.get("data_dir");
            p = (dd == null) ? "" : String.valueOf(dd);
        }
        return p.isEmpty() ? null : p;
    }

    public String airun(String argsJson) {
        String path = extract(argsJson, "path");
        String keyword = extract(argsJson, "keyword");
        if (keyword == null) keyword = "";
        if (!ConfirmationGate.confirm("findfile", "查找文件: " + path)) return "查找文件被拒绝。";
        return FileUtils.findFiles(path, keyword);
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    private static String extract(String json, String key) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
