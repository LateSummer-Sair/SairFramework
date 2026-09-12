import java.util.Map;

import sair.aiagent.core.SkillBank;
import sair.aiagent.core.ThirdPartySkillStore;

/** 三方技能库管理（工具名 {@code thirdskill}）—— 从 {@code ToolDispatcher.executeThirdSkill()} 剥离而来。 */
public class AirunSkill {

    public String airun(Map<String, Object> args) {
        return run(args.get("content") == null ? null : String.valueOf(args.get("content")));
    }

    public String airun(String content) {
        return run(content);
    }

    private String run(String content) {
        ThirdPartySkillStore store = SkillBank.getInstance().getThirdPartyStore();
        if (store == null) return "[thirdskill] 三方技能库未初始化";
        if (content == null || content.trim().isEmpty())
            return "[thirdskill] 用法: list | validate | add 技能名|描述|内容 | delete 技能名";

        String cmd = content.trim();
        if ("list".equalsIgnoreCase(cmd)) return store.list();
        if ("validate".equalsIgnoreCase(cmd) || "check".equalsIgnoreCase(cmd)) return store.validate();
        if (cmd.toLowerCase().startsWith("delete ")) return store.delete(cmd.substring(7).trim());
        if (cmd.toLowerCase().startsWith("add ")) {
            String rest = cmd.substring(4).trim();
            String[] parts = rest.split("\\|", 3);
            if (parts.length < 2) return "[thirdskill] add 格式错误: add 技能名|描述|内容";
            return store.add(parts[0].trim(), parts[1].trim(), parts.length > 2 ? parts[2].trim() : "");
        }
        return "[thirdskill] 未知子命令: " + cmd + "（支持 list / validate / add / delete）";
    }
}
