import java.util.Map;

import sair.aiagent.core.CronScheduler;

/** 定时任务（工具名 {@code schedule}）—— 从 {@code TagExecutor.executeSchedule()} 逐字剥离而来。 */
public class AirunSkill {

    public String airun(Map<String, Object> args) {
        return run(args.get("content") == null ? null : String.valueOf(args.get("content")));
    }

    public String airun(String content) {
        return run(content);
    }

    private String run(String content) {
        CronScheduler cronScheduler = CronScheduler.getInstance();
        if (cronScheduler == null) return "[schedule] CronScheduler not initialized";
        if (content == null || content.trim().isEmpty())
            return "[schedule] usage: add \"cron\" \"command\" | list | remove id | enable id | disable id";

        String c = content.trim();
        String[] parts = c.split("\\s+", 2);
        String subCmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";
        switch (subCmd) {
            case "add": {
                String cronExpr = extractQuoted(args, 0);
                String command = extractQuoted(args, 1);
                String desc = extractQuoted(args, 2);
                if (cronExpr.isEmpty() || command.isEmpty())
                    return "[schedule] add needs cron_expr and command in quotes";
                return cronScheduler.addTask(cronExpr, command, desc);
            }
            case "list": return cronScheduler.listTasks();
            case "remove":
                try { return cronScheduler.removeTask(Integer.parseInt(args)); }
                catch (NumberFormatException e) { return "[schedule] remove needs task ID"; }
            case "enable":
                try { return cronScheduler.enableTask(Integer.parseInt(args)); }
                catch (NumberFormatException e) { return "[schedule] enable needs task ID"; }
            case "disable":
                try { return cronScheduler.disableTask(Integer.parseInt(args)); }
                catch (NumberFormatException e) { return "[schedule] disable needs task ID"; }
            default: return "[schedule] unknown: " + subCmd + ". try: add/list/remove/enable/disable";
        }
    }

    /** 取第 index 个双引号参数（与 TagHandlers.extractQuoted 同实现）。 */
    private static String extractQuoted(String args, int index) {
        if (args == null) return "";
        int count = 0, i = 0;
        while (i < args.length() && count <= index) {
            if (args.charAt(i) == '"') {
                int end = args.indexOf('"', i + 1);
                if (end < 0) break;
                if (count == index) return args.substring(i + 1, end);
                i = end + 1; count++;
            } else { i++; }
        }
        return "";
    }
}
