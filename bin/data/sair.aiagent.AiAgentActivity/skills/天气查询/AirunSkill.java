import java.util.Map;

import sair.aiagent.util.WeatherTool;

/** 天气查询（工具名 {@code weather}）—— 从 {@code AgentActionHandler.executeWeather()} 剥离而来。 */
public class AirunSkill {

    public String airun(Map<String, Object> args) {
        String city = (args.get("city") == null) ? null : String.valueOf(args.get("city"));
        if (city == null || city.trim().isEmpty()) return WeatherTool.queryWeather("");
        return WeatherTool.queryWeather(city.trim());
    }

    public String airun(String city) {
        if (city == null || city.trim().isEmpty()) return WeatherTool.queryWeather("");
        return WeatherTool.queryWeather(city.trim());
    }
}
