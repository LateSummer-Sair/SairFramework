import java.util.Map;

import sair.aiagent.core.ConfirmationGate;
import sair.aiagent.core.PersistenceManager;
import sair.aiagent.core.ThreadManager;
import sair.aiagent.util.SearchTool;

/**
 * 联网搜索（工具名 {@code search}）—— 从 {@code AgentActionHandler.executeSearch()} 剥离而来。
 *
 * <p>除了搜索本身，这里还保留了原 Java 侧那条<b>「搜索成功自动沉淀到知识库」</b>的副作用：
 * 剥离工具时它一度丢失（原先挂在 ToolDispatcher 的 search 分支上，分支随实现一起删掉了），
 * 现在由技能自己调用 {@code PersistenceManager.addNote}，行为与当初一致
 * （笔记标题前缀 {@code [联网搜索] }，正文截断 3000 字符，异步落库，失败不影响主流程）。</p>
 */
public class AirunSkill {

    public String airun(Map<String, Object> args) {
        String query = (args.get("query") == null) ? null : String.valueOf(args.get("query"));
        return search(query);
    }

    public String airun(String argsJson) {
        String query = null;
        if (argsJson != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"query\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(argsJson);
            if (m.find()) query = m.group(1);
            else if (!argsJson.trim().startsWith("{")) query = argsJson.trim();   // 直接传关键词也接受
        }
        return search(query);
    }

    private String search(String query) {
        if (query == null || query.trim().isEmpty()) return "[search] 请提供搜索关键词";
        if (!ConfirmationGate.confirm("search", "联网搜索: " + query)) return "搜索被拒绝。";
        System.out.println("[search] " + query);
        String result = SearchTool.search(query);
        autoStore(query, result);
        return result;
    }

    /** 搜索成功才沉淀（失败以 [search] 开头）；异步落库，失败静默。 */
    private static void autoStore(String query, String result) {
        final String q = query.trim();
        if (q.isEmpty()) return;
        if (result == null) return;
        String r = result.trim();
        if (r.isEmpty() || r.startsWith("[search]")) return;
        final String note = r.length() <= 3000 ? r : r.substring(0, 3000) + "\n...(笔记过长已截断)";
        try {
            ThreadManager.getInstance().newNamedCached("AutoNote").submit(() -> {
                PersistenceManager pm = PersistenceManager.getInstance();
                if (pm == null) return;
                try {
                    pm.addNote("[联网搜索] " + q, note, q);
                } catch (Exception ignored) {
                    // 沉淀失败不影响主流程
                }
            });
        } catch (Exception ignored) {
            // 线程池不可用也不影响搜索结果返回
        }
    }
}
