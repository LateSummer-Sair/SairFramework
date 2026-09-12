import java.util.List;
import java.util.Map;
import sair.aiagent.core.PersistenceManager;

public class AirunSkill {
    public String airun(Map<String, Object> args) {
        Object q = (args == null) ? null : args.get("query");
        String query = (q == null) ? "" : String.valueOf(q).trim();
        if (query.isEmpty()) return "[searchnote] 缺少检索关键词 query";
        PersistenceManager pm = PersistenceManager.getInstance();
        if (pm == null) return "[searchnote] 持久化层未初始化";
        // 显式检索命中的笔记计一次「被查阅」（自动注入不算）
        List<String[]> hits = pm.searchNotes(query, 10);
        if (hits == null || hits.isEmpty()) return "[note] no results for: " + query;
        pm.recordNoteHit(hits);
        StringBuilder sb = new StringBuilder("[note] search results (" + hits.size() + "):");
        for (String[] r : hits) {
            sb.append("\n  #").append(r[0]).append(" ").append(r[1]);
            if (r.length > 2 && r[2] != null && !r[2].isEmpty()) sb.append(" - ").append(r[2]);
        }
        return sb.toString();
    }
}
