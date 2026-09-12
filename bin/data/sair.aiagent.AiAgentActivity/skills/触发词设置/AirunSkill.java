import java.util.List;
import java.util.Map;

import sair.aiagent.core.AiConfig;

/** 触发词设置（工具名 {@code settrigger}）—— 从 {@code ToolDispatcher.executeSetTrigger()} 剥离而来。 */
public class AirunSkill {

    /** 声明了 context: true → 第二参数是只读上下文（这里要用 is_master）。 */
    public String airun(Map<String, Object> args, Map<String, Object> ctx) {
        // 仅主人可修改触发词（权限矩阵里 settrigger=MASTER 是更外层的一道门）
        if (ctx == null || !Boolean.TRUE.equals(ctx.get("is_master"))) {
            return "[settrigger] 无权限：仅主人可修改触发词";
        }
        String words = (args.get("words") == null) ? null : String.valueOf(args.get("words"));
        AiConfig cfg = AiConfig.getInstance();
        if (words == null || words.trim().isEmpty()
                || "list".equalsIgnoreCase(words.trim()) || "查看".equals(words.trim())) {
            List<String> current = cfg.getTriggerWords();
            if (current.isEmpty()) return "[settrigger] 当前未设置触发词（@机器人 始终有效）";
            return "[settrigger] 当前触发词: " + String.join("; ", current);
        }
        cfg.setBotName(words);   // setBotName 内部按 ; 拆分去重
        cfg.save();
        List<String> list = cfg.getTriggerWords();
        return "[settrigger] 触发词已设置: " + String.join("; ", list);
    }
}
