package sair.sys.file.pngcompress;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.imageio.ImageIO;

/**
 * PNG/图片等比压缩工具:按比例缩放图片(高质量渲染提示)并写出为目标格式。
 * <p>
 * 架构角色:文件处理基础设施——插件可调用 toCompress 压缩截图/资源图,
 * 纯 JDK ImageIO 实现,不依赖 Swing 界面。
 * <p>
 * 线程安全说明:纯静态工具、无共享可变状态,天然线程安全;
 * 文件流使用 try-with-resources 保证任何路径都关闭。
 * <p>
 * 二进制兼容约束:三个 toCompress 重载的签名与 ratio 语义((0,1])不可变更。
 */
public class ImageTool {
	/**
	 * 按比例压缩文件:目标格式取源文件扩展名(无扩展名时回退 png);
	 * ratio 限制在 (0,1],越界抛 IOException;流经 try-with-resources 自动关闭。
	 *
	 * @param input  源图片文件
	 * @param output 目标文件
	 * @param ratio  缩放比例,取值范围 (0,1]
	 * @throws IOException 比例非法或读写失败
	 */
	public static void toCompress(File input, File output, float ratio) throws IOException {
		String fileName = input.getName();
		// 修复:无扩展名时回退png;ratio限制(0,1]避免非正尺寸
		String fileLastName = "png";
		int dot = fileName.lastIndexOf(".");
		if (dot > 0 && dot < fileName.length() - 1)
			fileLastName = fileName.substring(dot + 1);
		if (ratio <= 0f || ratio > 1f)
			throw new IOException("ratio must be in (0,1]: " + ratio);
		try (InputStream in = new FileInputStream(input); OutputStream out = new FileOutputStream(output)) {
			toCompress(fileLastName, in, out, ratio);
		}
	}

	/**
	 * 流式压缩核心:解码 → 校验(解码失败抛明确错误而非 NPE)→ 按比例计算新尺寸 →
	 * 高质量渲染缩放(ARGB 画布 + 四类渲染提示)→ 以 fileLastName 格式写出。
	 *
	 * @param fileLastName 输出格式名(如 png/jpg)
	 * @param input        源图片流(调用方负责关闭)
	 * @param output       目标流(调用方负责关闭)
	 * @param ratio        缩放比例,取值范围 (0,1]
	 * @throws IOException 解码失败或写出失败
	 */
	public static void toCompress(String fileLastName, InputStream input, OutputStream output, float ratio)
			throws IOException {
		BufferedImage image = ImageIO.read(input);
		// 修复:解码失败给出明确错误而非NPE
		if (image == null)
			throw new IOException("无法解码图片(格式不支持或文件损坏)");
		int newWidth = (int) (image.getWidth() * ratio);
		int newHeight = (int) (image.getHeight() * ratio);
		BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = resizedImage.createGraphics();
		g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g2d.drawImage(image, 0, 0, newWidth, newHeight, null);
		g2d.dispose();
		ImageIO.write(resizedImage, fileLastName, output);

	}

	/**
	 * 路径便捷重载:等价于 {@link #toCompress(File, File, float)}。
	 *
	 * @param input  源图片路径
	 * @param output 目标图片路径
	 * @param ratio  缩放比例 (0,1]
	 * @throws IOException 比例非法或读写失败
	 */
	public static void toCompress(String input, String output, float ratio) throws IOException {
		toCompress(new File(input), new File(output), ratio);
	}
}
