import sair.aiagent.core.DeepSeekClient;

/** 余额查询（工具名 {@code balance}）—— 从 {@code AgentActionHandler.executeBalance()} 剥离而来。 */
public class AirunSkill {

    /** 无参入口（本工具不需要参数）。 */
    public String airun() {
        DeepSeekClient client = DeepSeekClient.getInstance();
        if (client == null) return "[balance] DeepSeekClient未初始化";
        try {
            return client.queryBalance();
        } catch (Exception e) {
            return "[balance] 查询失败: " + e.toString();
        }
    }
}
