import java.util.Map;

import sair.aiagent.core.ConfirmationGate;
import sair.aiagent.util.FileUtils;

/** 目录列举（工具名 {@code readdir}）—— 从 {@code AgentActionHandler.executeReadDir()} 剥离而来。 */
public class AirunSkill {

    public String airun(Map<String, Object> args, Map<String, Object> ctx) {
        String path = (args.get("path") == null) ? "" : String.valueOf(args.get("path")).trim();
        String p = resolveExecqPath(ctx, path, true);
        if (p == null) return "[readdir] 无权限：execq 通道缺少数据目录";
        if (!ConfirmationGate.confirm("readdir", "列出目录: " + p)) return "列出目录被拒绝。";
        System.out.println("[readdir] 列出目录: " + p);
        return "目录 [" + p + "]:\n" + FileUtils.readDir(p);
    }

    /** 复刻原 {@code resolveQqPath(ctx, path, true)}：execq 下空路径回落到数据目录。 */
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

    public String airun(String argsJson) {
        String path = extract(argsJson, "path");
        if (path == null) path = "";
        if (!ConfirmationGate.confirm("readdir", "列出目录: " + path)) return "列出目录被拒绝。";
        return "目录 [" + path + "]:\n" + FileUtils.readDir(path);
    }

    private static String extract(String json, String key) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
