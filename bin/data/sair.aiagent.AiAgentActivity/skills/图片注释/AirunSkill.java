import java.util.Map;

import sair.aiagent.core.PersistenceManager;

/** 图片注释（工具名 {@code setimageremark}）—— 从 {@code ToolDispatcher.executeSetImageRemark()} 剥离而来。 */
public class AirunSkill {

    public String airun(Map<String, Object> args) {
        String remark = str(args.get("remark"));
        if (remark == null || remark.trim().isEmpty()) {
            return "[setimageremark] 错误：remark 不能为空";
        }

        String md5 = str(args.get("image_md5"));
        if (md5 == null || md5.trim().isEmpty()) {
            String imageUrl = str(args.get("image_url"));
            if (imageUrl == null || imageUrl.trim().isEmpty()) {
                return "[setimageremark] 错误：需提供 image_url 或 image_md5 之一";
            }
            try {
                byte[] bytes = sair.aiagent.onebot.ImageDownloader.downloadImage(imageUrl.trim());
                if (bytes == null || bytes.length == 0) {
                    return "[setimageremark] 错误：图片下载失败，无法计算 MD5";
                }
                md5 = sair.aiagent.onebot.ImageRecognizer.md5(bytes);
            } catch (Exception e) {
                return "[setimageremark] 错误：图片下载异常: " + e.toString();
            }
        }
        md5 = md5.trim().toLowerCase();

        PersistenceManager pm = PersistenceManager.getInstance();
        if (pm == null) return "[setimageremark] 错误：持久化层未初始化";
        pm.setImageRemark(md5, remark.trim(), "ai");
        return "[setimageremark] 已更新图片注释（MD5: " + md5 + "）";
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
}
