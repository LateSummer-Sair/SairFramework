import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Map;

import sair.aiagent.core.ConfirmationGate;
import sair.aiagent.core.MemoryManager;
import sair.aiagent.util.FormatUtil;
import sair.aiagent.util.NetGuard;

/**
 * 文件下载（工具名 {@code download}）—— 从 {@code AgentActionHandler.executeDownload()} 剥离而来。
 *
 * <p>整段逻辑（协议校验 / 逐跳内网拦截 / 手动跟随 5 次重定向 / 重名避让 / 100MB 上限 /
 * 完成后补记忆 / 确认闸门）与原实现一一对应。两处与"插件实例"解耦：</p>
 * <ul>
 *   <li>数据目录从只读上下文 {@code ctx.data_dir} 取（原来是 {@code selfActivity.getDataDir()}）；</li>
 *   <li>记忆写入走 {@code MemoryManager.getInstance()}（原来是实例字段）。</li>
 * </ul>
 */
public class AirunSkill {

    /** 单文件体积上限（与内置实现一致）。 */
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    /** 最大重定向跳数（含首跳）。 */
    private static final int MAX_HOPS = 5;

    /** 声明了 context: true → 第二个入参是只读上下文（这里要用 data_dir）。 */
    public String airun(Map<String, Object> args, Map<String, Object> ctx) {
        String url = (args.get("url") == null) ? null : String.valueOf(args.get("url")).trim();
        String dataDir = (ctx == null) ? null : String.valueOf(ctx.get("data_dir"));
        return download(url, dataDir);
    }

    private String download(String url, String dataDir) {
        if (url == null || url.isEmpty()) return "下载地址为空。";
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "下载地址无效: " + url;
        if (!ConfirmationGate.confirm("download", "下载文件: " + url)) return "下载被拒绝。";

        File downloadDir = new File(dataDir == null || dataDir.isEmpty() ? "." : dataDir, "downloads");
        downloadDir.mkdirs();

        String fileName = FormatUtil.extractFileName(url);
        File targetFile = new File(downloadDir, fileName);
        if (targetFile.exists()) {
            String base = fileName, ext = "";
            int dotIdx = base.lastIndexOf('.');
            if (dotIdx > 0) { ext = base.substring(dotIdx); base = base.substring(0, dotIdx); }
            for (int i = 1; i <= 99; i++) {
                targetFile = new File(downloadDir, base + "_" + i + ext);
                if (!targetFile.exists()) { fileName = base + "_" + i + ext; break; }
            }
        }

        String current = url;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            // ★ 每一跳都重新做内网检查（防 302 绕到内网）；判定逻辑在 Java 的 NetGuard
            try {
                URI uri = new URI(current);
                String host = uri.getHost();
                if (NetGuard.isInternalHost(host)) {
                    return "下载被拒绝: 禁止访问内网地址 (" + host + ")";
                }
            } catch (Exception e) {
                return "下载 [" + current + "] 错误: URL格式无效 - " + e.getMessage();
            }

            System.out.println("[download] " + current + " -> " + fileName);
            HttpURLConnection conn = null;
            BufferedInputStream bis = null;
            FileOutputStream fos = null;
            try {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(120_000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; AiAgent-SFW/1.4)");
                conn.setInstanceFollowRedirects(false);
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.trim().isEmpty()) {
                        current = new URL(new URL(current), loc.trim()).toString();
                        continue;   // 下一跳（会重新做内网检查）
                    }
                    return "下载 [" + current + "] 失败: HTTP " + code + " 缺少 Location";
                }
                if (code < 200 || code >= 300) {
                    return "下载 [" + current + "] 失败: HTTP " + code;
                }

                bis = new BufferedInputStream(conn.getInputStream());
                fos = new FileOutputStream(targetFile);
                byte[] buf = new byte[8192];
                long downloaded = 0;
                int n;
                while ((n = bis.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    downloaded += n;
                    if (downloaded > MAX_BYTES) {
                        fos.flush(); bis.close(); fos.close(); conn.disconnect();
                        targetFile.delete();
                        return "下载 [" + current + "] 失败: 文件超过大小上限 ("
                                + FormatUtil.formatSize(MAX_BYTES) + ")";
                    }
                }
                fos.flush(); bis.close(); fos.close(); conn.disconnect();

                String sizeStr = FormatUtil.formatSize(downloaded);
                String relPath = "downloads/" + fileName;
                MemoryManager mm = MemoryManager.getInstance();
                if (mm != null) {
                    mm.add("下载文件: " + relPath + " | 来源: " + current + " | 大小: " + sizeStr);
                }
                return "下载完成: " + relPath + " (" + sizeStr + ")\n绝对路径: " + targetFile.getAbsolutePath();
            } catch (java.io.FileNotFoundException e) {
                return "下载 [" + current + "] 失败: 文件不存在 (404)";
            } catch (Exception e) {
                return "下载 [" + current + "] 错误: " + e.toString();
            } finally {
                try { if (bis != null) bis.close(); } catch (Exception ignored) {}
                try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }
        return "下载 [" + url + "] 失败: 重定向次数超限";
    }
}
