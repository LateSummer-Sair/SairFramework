import java.util.Map;

import sair.aiagent.core.PersistenceManager;

/** 纠错记录（工具名 {@code correct}）—— 从 {@code ToolDispatcher.executeCorrect()} 剥离而来。 */
public class AirunSkill {

    /** 声明了 context: true → 第二参数是只读上下文（取 user_id 作为来源 QQ）。 */
    public String airun(Map<String, Object> args, Map<String, Object> ctx) {
        String topic = str(args.get("topic"));
        String content = str(args.get("content"));
        String viewpoint = str(args.get("viewpoint"));

        if (content == null || content.trim().isEmpty()) {
            return "[correct] 错误：content（纠正内容）不能为空";
        }
        if (topic == null || topic.trim().isEmpty()) topic = "general";

        PersistenceManager pm = PersistenceManager.getInstance();
        if (pm == null) return "[correct] 错误：持久化层未初始化";

        long qq = senderQq(ctx);
        int id = pm.addCorrection(topic.trim(), content.trim(),
                viewpoint != null ? viewpoint.trim() : "", "ai", qq);
        return id >= 0
                ? "[correct] 已记录纠正 #" + id + "（主题: " + topic + "）"
                : "[correct] 记录失败";
    }

    /** 从只读上下文取发送者 QQ（替代原来的 ctx.senderQQ）。 */
    private static long senderQq(Map<String, Object> ctx) {
        if (ctx == null) return 0L;
        Object v = ctx.get("user_id");
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return (v == null) ? 0L : Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
}
