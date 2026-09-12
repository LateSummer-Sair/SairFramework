import java.util.Collections;
import java.util.List;
import java.util.Map;

import sair.aiagent.core.PersistenceManager;

/** 知识笔记（工具名 {@code note}）—— 从 {@code TagExecutor.executeNote()} 逐字剥离而来。 */
public class AirunSkill {

    public String airun(Map<String, Object> args) {
        return run(args.get("content") == null ? null : String.valueOf(args.get("content")));
    }

    public String airun(String content) {
        return run(content);
    }

    private String run(String content) {
        if (content == null || content.trim().isEmpty())
            return "[note] usage: add title|content|tags | search query | list | get id | delete id | update id title|content|tags";
        String c = content.trim();
        PersistenceManager pm = PersistenceManager.getInstance();

        if (c.startsWith("add ")) {
            String rest = c.substring(4).trim();
            String[] parts = rest.split("\\|", 3);
            if (parts.length < 2) return "[note] add needs title|content format";
            String title = parts[0].trim();
            String body = parts[1].trim();
            String tags = parts.length > 2 ? parts[2].trim() : "";
            if (pm == null) return "[note] PersistenceManager not available";
            int id = pm.addNote(title, body, tags);
            return id > 0 ? "[note] saved #" + id + ": " + title : "[note] save failed";

        } else if (c.startsWith("search ")) {
            String query = c.substring(7).trim();
            if (pm == null) return "[note] PersistenceManager not available";
            List<String[]> results = pm.searchNotes(query, 10);
            if (results.isEmpty()) return "[note] no results for: " + query;
            // 使用回写：显式检索命中的笔记记一次「被查阅」（自动注入不算）
            pm.recordNoteHit(results);
            StringBuilder sb = new StringBuilder("[note] search results (" + results.size() + "):");
            for (String[] r : results) {
                sb.append("\n  #").append(r[0]).append(" ").append(r[1]);
                if (r[2] != null && !r[2].isEmpty()) sb.append(" - ").append(r[2]);
            }
            return sb.toString();

        } else if (c.startsWith("list")) {
            if (pm == null) return "[note] PersistenceManager not available";
            List<String[]> notes = pm.listNotes(20);
            if (notes.isEmpty()) return "[note] no notes yet";
            StringBuilder sb = new StringBuilder("[note] recent notes (" + notes.size() + "):");
            for (String[] n : notes) {
                sb.append("\n  #").append(n[0]).append(" ").append(n[1]);
                if (n[3] != null && !n[3].isEmpty()) sb.append(" [").append(n[3]).append("]");
            }
            return sb.toString();

        } else if (c.startsWith("get ")) {
            try {
                int id = Integer.parseInt(c.substring(4).trim());
                if (pm == null) return "[note] PersistenceManager not available";
                String[] note = pm.getNote(id);
                if (note == null) return "[note] #" + id + " not found";
                pm.recordNoteHit(Collections.singletonList(new String[]{String.valueOf(id)}));
                return "[note] #" + id + " " + note[0] + "\n" + note[1]
                        + (note[2] != null && !note[2].isEmpty() ? "\nTags: " + note[2] : "");
            } catch (NumberFormatException e) {
                return "[note] get needs numeric id";
            }

        } else if (c.startsWith("delete ")) {
            try {
                int id = Integer.parseInt(c.substring(7).trim());
                if (pm == null) return "[note] PersistenceManager not available";
                return pm.removeNote(id) ? "[note] #" + id + " deleted" : "[note] #" + id + " not found";
            } catch (NumberFormatException e) {
                return "[note] delete needs numeric id";
            }

        } else if (c.startsWith("update ")) {
            String rest = c.substring(7).trim();
            String[] parts2 = rest.split(" ", 2);
            try {
                int id = Integer.parseInt(parts2[0]);
                String updateArgs = parts2.length > 1 ? parts2[1].trim() : "";
                String[] fields = updateArgs.split("\\|", 3);
                if (fields.length < 2) return "[note] update needs: id title|content|tags";
                if (pm == null) return "[note] PersistenceManager not available";
                String title = fields[0].trim();
                String body = fields[1].trim();
                String tags = fields.length > 2 ? fields[2].trim() : "";
                return pm.updateNote(id, title, body, tags)
                        ? "[note] #" + id + " updated" : "[note] #" + id + " not found";
            } catch (NumberFormatException e) {
                return "[note] update needs: numeric_id title|content|tags";
            }
        }
        return "[note] unknown command. try: add/search/list/get/delete/update";
    }
}
