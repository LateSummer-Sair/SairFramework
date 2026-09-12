import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

public class AirunSkill {
    /** 无参入口：本工具不需要任何参数（runner 按 airun(Map) → airun(String) → airun() 顺序尝试）。 */
    public String airun() {
        return "当前时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE").format(new Date());
    }
}
