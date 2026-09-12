import java.util.Map;

import sair.aiagent.core.ConfirmationGate;
import sair.aiagent.core.PersistenceManager;
import sair.aiagent.core.ThreadManager;
import sair.aiagent.util.NetGuard;
import sair.aiagent.util.WebFetcher;

/** 网页抓取（工具名 {@code web}）—— 从 {@code AgentActionHandler.executeWeb()} 剥离而来。 */
public class AirunSkill {

    public String airun(Map<String, Object> args) {
        return fetch(args.get("url") == null ? null : String.valueOf(args.get("url")));
    }

    public String airun(String argsJson) {
        String url = null;
        if (argsJson != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(argsJson);
            if (m.find()) url = m.group(1);
            else if (!argsJson.trim().startsWith("{")) url = argsJson.trim();
        }
        return fetch(url);
    }

    private String fetch(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return "Web GET 错误: URL 为空";
        String url = rawUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;

        // ★ 内网拦截在 Java 的 NetGuard 里（技能只能调用、不能改判定）
        try {
            java.net.URI uri = new java.net.URI(url);
            if (NetGuard.isInternalHost(uri.getHost())) {
                System.out.println("[web] 拒绝内网地址: " + uri.getHost());
                return "Web GET [" + url + "] 被拒绝: 禁止访问内网地址 (" + uri.getHost() + ")";
            }
        } catch (Exception e) {
            return "Web GET [" + url + "] 错误: URL格式无效 - " + e.getMessage();
        }

        if (!ConfirmationGate.confirm("web", "联网获取: " + url)) return "Web 请求被拒绝。";
        System.out.println("[web] GET " + url);

        WebFetcher.FetchResult r = WebFetcher.fetch(url);
        String out;
        if (r.success) {
            String text = (r.text == null || r.text.trim().isEmpty()) ? "(空正文)" : r.text;
            out = "Web GET [" + r.finalUrl + "] (HTTP " + r.status + ", 编码 " + r.charset + "):\n" + text;
        } else {
            out = "Web GET [" + r.finalUrl + "] 失败: " + (r.error != null ? r.error : "HTTP " + r.status);
        }
        autoStore(url, out);
        return out;
    }

    /**
     * 抓取成功才沉淀到知识库（笔记标题前缀 {@code [网页抓取] }，正文截断 3000 字符，异步落库）。
     * <p>这条副作用原先是挂在 ToolDispatcher 的 web 分支上的，剥离工具时随分支一起丢了，
     * 现在由技能自己调用，行为与当初一致。判定：必须是以 {@code Web GET} 开头、且不含
     * 失败/错误/被拒绝、正文不少于 30 字符的成功结果。</p>
     */
    private static void autoStore(String url, String result) {
        final String u = url.trim();
        if (u.isEmpty() || result == null) return;
        String r = result.trim();
        if (r.isEmpty() || !r.startsWith("Web GET")) return;
        if (r.contains("失败:") || r.contains("错误:") || r.contains("被拒绝")) return;
        int httpIdx = r.indexOf("(HTTP ");
        if (httpIdx > 0) {
            int colon = r.indexOf(':', httpIdx);
            String body = colon > 0 ? r.substring(colon + 1).trim() : "";
            if (body.length() < 30) return;
        }
        final String note = r.length() <= 3000 ? r : r.substring(0, 3000) + "\n...(笔记过长已截断)";
        try {
            ThreadManager.getInstance().newNamedCached("AutoNote").submit(() -> {
                PersistenceManager pm = PersistenceManager.getInstance();
                if (pm == null) return;
                try {
                    pm.addNote("[网页抓取] " + u, note, u);
                } catch (Exception ignored) {
                    // 沉淀失败不影响主流程
                }
            });
        } catch (Exception ignored) {
            // 线程池不可用也不影响抓取结果返回
        }
    }
}
