import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.util.Map;

import sair.v4.kit.Http;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Skill;

/**
 * 天气查询（工具 {@code weather}）：wttr.in 的 {@code format=j1} JSON 接口，返回温度 / 天气 / 湿度 / 风。
 * <p>失败（没网、超时、城市不存在、返回非 JSON）一律给一句明确说明，不抛异常。</p>
 * <p>权限与同族的「联网搜索」同形：出网查询判 {@code Res.tool("weather")} 的 X 位（T 类工具的运行时形态）；
 * ALLUSER 默认 {@code T=无} ⇒ 非主人用不了这把工具，要特例放行（可被账本例外放开的位判定）。</p>
 */
public class WeatherSkill implements Skill {

    @Override
    public Object call(Args a, Host h) {
        // 出网查询 = 执行一把工具（工具的运行时形态是 T 类资源）→ 判这个工具的 X 位，
        // 与同族的「联网搜索」（SearchSkill）同一个资源名、同一个位，两族保持同形。
        // ALLUSER 默认 T=无 ⇒ 非主人用不了这把工具，要特例放行（可被账本例外放开的位判定）；
        // 主人与她自己的自主行为（SYSTEM）照常。
        if (h != null) {
            String deny = h.need(sair.v4.auth.Res.tool("weather"), 'X');
            if (deny != null) return deny;
        }
        String city = Str.trim(a.str("city"));
        String q = city.isEmpty() ? "" : enc(city);
        String[] urls = new String[] {
                "https://wttr.in/" + q + "?format=j1",
                "http://wttr.in/" + q + "?format=j1"
        };
        Map<String, String> hdrs = Http.headers();
        hdrs.put("User-Agent", "curl/8.0.1");
        hdrs.put("Accept", "application/json");

        String last = "";
        for (int i = 0; i < urls.length; i++) {
            Http.Res r = Http.get(urls[i], hdrs, 12000);
            if (!r.ok()) {
                last = why(r) + "（GET " + urls[i] + "）";
                continue;
            }
            JsonObject o = J.obj(r.body);
            if (o == null) {
                last = "返回的不是 JSON：" + Str.cut(Str.oneLine(r.body), 120);
                continue;
            }
            return render(city, o);
        }
        return "[weather] 查询失败：" + last;
    }

    // ---------------------------------------------------------------- 解析

    private static String render(String city, JsonObject o) {
        JsonObject cur = first(J.list(o, "current_condition"));
        if (cur == null) return "[weather] 返回里没有 current_condition，取不到观测数据。";

        JsonObject area = first(J.list(o, "nearest_area"));
        String name = inner(area, "areaName");
        String country = inner(area, "country");
        if (Str.has(city) && !Str.has(name)) return "[weather] 没找到这个城市：" + city;
        String place;
        if (Str.has(name)) place = name + (Str.has(country) ? "（" + country + "）" : "");
        else place = Str.has(city) ? city : "当前位置";
        if (!Str.has(city)) place = place + "（按 IP 定位）";

        String desc = descOf(cur);
        StringBuilder sb = new StringBuilder();
        sb.append(place).append(" · 观测 ").append(J.s(cur, "observation_time", "?")).append("\n");
        sb.append("温度 ").append(J.s(cur, "temp_C", "?")).append("°C");
        if (Str.has(J.s(cur, "FeelsLikeC", ""))) sb.append("（体感 ").append(J.s(cur, "FeelsLikeC", "")).append("°C）");
        if (Str.has(desc)) sb.append("｜").append(desc);
        if (Str.has(J.s(cur, "humidity", ""))) sb.append("｜湿度 ").append(J.s(cur, "humidity", "")).append("%");
        String wind = J.s(cur, "windspeedKmph", "");
        if (Str.has(wind)) {
            sb.append("｜风 ").append(J.s(cur, "winddir16Point", "")).append(" ").append(wind).append(" km/h");
        }
        if (Str.has(J.s(cur, "visibility", ""))) sb.append("｜能见度 ").append(J.s(cur, "visibility", "")).append(" km");
        if (Str.has(J.s(cur, "pressure", ""))) sb.append("｜气压 ").append(J.s(cur, "pressure", "")).append(" hPa");
        return sb.toString();
    }

    private static String descOf(JsonObject cur) {
        JsonArray d = J.list(cur, "weatherDesc");
        if (d == null || d.size() == 0) return "";
        JsonObject o = d.get(0) == null || !d.get(0).isJsonObject() ? null : d.get(0).getAsJsonObject();
        return o == null ? "" : J.s(o, "value", "");
    }

    private static String inner(JsonObject area, String key) {
        if (area == null) return "";
        JsonArray a = J.list(area, key);
        if (a == null || a.size() == 0) return "";
        JsonObject o = a.get(0) == null || !a.get(0).isJsonObject() ? null : a.get(0).getAsJsonObject();
        return o == null ? "" : J.s(o, "value", "");
    }

    private static JsonObject first(JsonArray a) {
        if (a == null || a.size() == 0) return null;
        return a.get(0) != null && a.get(0).isJsonObject() ? a.get(0).getAsJsonObject() : null;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String why(Http.Res r) {
        if (r == null) return "没有响应";
        if (Str.has(r.error)) return r.error;
        return "HTTP " + r.code;
    }
}
