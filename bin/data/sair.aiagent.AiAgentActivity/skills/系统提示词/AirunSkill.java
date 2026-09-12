import java.util.Map;

import sair.aiagent.core.AiConfig;

/** 系统提示词（工具名 {@code editprompt}）—— 从 {@code AgentActionHandler.executeEditPrompt()} 剥离而来。 */
public class AirunSkill {

    public String airun(Map<String, Object> args) {
        String raw = (args.get("content") == null) ? null : String.valueOf(args.get("content"));
        if (raw == null || raw.trim().isEmpty()) return "提示词内容为空，未修改。";
        String prompt = raw.trim();
        if (prompt.length() < 30) {
            return "提示词太短（" + prompt.length() + " 字符），需要至少 30 字符。";
        }
        System.out.println("[editprompt] 长度: " + prompt.length() + " 字符");
        try {
            AiConfig.getInstance().setSystemPrompt(prompt);   // 内部已写 systemPrompt.md
            return "系统提示词已更新（" + prompt.length() + " 字符）。新个性已生效。";
        } catch (Exception e) {
            return "提示词更新失败: " + e.getMessage();
        }
    }
}
