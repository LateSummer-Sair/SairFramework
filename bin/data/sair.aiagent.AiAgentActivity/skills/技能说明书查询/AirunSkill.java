import java.util.Map;
import sair.aiagent.core.SkillBank;

public class AirunSkill {
    public String airun(Map<String, Object> args) {
        Object n = (args == null) ? null : args.get("name");
        String name = (n == null) ? "" : String.valueOf(n).trim();
        if (name.isEmpty()) return "[skillinfo] 用法: 传 name 参数指定技能名或工具名";
        SkillBank bank = SkillBank.getInstance();
        if (bank == null) return "[skillinfo] 技能库未初始化";
        String detail = bank.getTagDetail(name);
        if (detail == null) return "[skillinfo] 未找到技能或工具: " + name + "（可尝试其它名称）";
        bank.recordSkillLookup(name);
        return detail;
    }
}
