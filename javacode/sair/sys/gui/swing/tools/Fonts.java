package sair.sys.gui.swing.tools;

import java.awt.Font;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;

import sair.LoaderManager;
import sair.Pathes;
import sair.SairLoader;
import sair.sys.Libraries;
import sair.sys.acticity.Exection;
import sair.user.Activity;

/**
 * <p>
 * 字体生成器工具类：按路径加载 TrueType 字体文件并生成指定样式/字号的 {@link Font}
 * （全局唯一实例 {@link #FONTS_TOOLS}）。
 * </p>
 * <p>
 * <b>架构角色：</b>tools 层工具；字体资源按三级顺序查找——
 * ① 文件系统路径（{@link File#exists()} 成立则直接读文件）；
 * ② 系统类路径资源（{@link LoaderManager#systemLoader}）；
 * ③ 插件/Activity 专属加载器（{@link LoaderManager#getModResStream} 与
 * {@link sair.sys.acticity.Exection} 对应的 {@link SairLoader}），
 * 支撑 SairFramework 插件化体系下的字体加载与热替换。
 * </p>
 * <p>
 * <b>线程安全 / EDT 说明：</b>{@link #baseFontCache} 的读写全部以 synchronized 块保护，
 * 任意线程调用安全；返回的 {@link Font} 是不可变对象，可跨线程共享。
 * 但 {@link Font#createFont} 涉及 IO 与解析、耗时较高，禁止在 EDT 上高频重复调用——
 * 命中 base 字体缓存后仅做廉价的 {@code deriveFont}。
 * </p>
 * <p>
 * <b>二进制兼容约束：</b>两个公开 {@code getFont} 重载的签名不可修改；
 * 缺省值约定（pathUrl=null→{@link Pathes#fontPath}，fontStyle=null→{@link Font#PLAIN}，
 * fontSize=null→13.0F）属于行为约定，同样不可改。
 * </p>
 *
 * @author _Sair
 * @version Fonts1.1
 **/
public class Fonts {
	/**
	 * <p>
	 * 全局唯一生成器单例。
	 * </p>
	 * <p>
	 * <b>EDT：</b>方法本身线程安全（缓存已同步），但把返回的 {@link Font} 应用到
	 * Swing 组件时须在 EDT 进行。
	 * </p>
	 **/
	public final static Fonts FONTS_TOOLS = new Fonts();

	/** 私有构造：仅允许通过单例 {@link #FONTS_TOOLS} 使用。 */
	private Fonts() {
	}

	/**
	 * base 字体缓存（性能优化）：以路径为键缓存<b>未派生样式/字号</b>的原始 base Font，
	 * 字体文件只解析一次，后续 {@code deriveFont} 直接派生。
	 * 上限 64 条，超出整体 clear（简易防无界增长）。全部访问经 synchronized，线程安全；
	 * 值是不可变 {@link Font}，可安全共享。
	 */
	private static final HashMap<String, Font> baseFontCache = new HashMap<String, Font>();

	/** 私有：从缓存取 base 字体（synchronized），未命中返回 null。 */
	private static Font getBaseFont(String path) {
		synchronized (baseFontCache) {
			return baseFontCache.get(path);
		}
	}

	/** 私有：写入缓存（synchronized）；超过 64 条先整体清空再放入。 */
	private static void putBaseFont(String path, Font base) {
		synchronized (baseFontCache) {
			if (baseFontCache.size() >= 64)
				baseFontCache.clear();
			baseFontCache.put(path, base);
		}
	}

	/**
	 * 便捷重载：等价于 {@link #getFont(String, Integer, Float, Activity)} 的 activity 传 null
	 * （仅走文件 → 系统类路径 → 插件资源三级查找，不查 Activity 专属加载器）。
	 *
	 * @param pathUrl 字体路径（可为 null，取缺省 {@link Pathes#fontPath}）
	 * @param fontStyle 样式（可为 null，取 {@link Font#PLAIN}）
	 * @param fontSize 字号（可为 null，取 13.0F）
	 * @return 派生后的 Font；全部加载途径失败时返回 null
	 **/
	public Font getFont(String pathUrl, Integer fontStyle, Float fontSize) {
		return getFont(pathUrl, fontStyle, fontSize, null);
	}

	/**
	 * <p>
	 * 核心方法：加载并派生字体。
	 * </p>
	 * <ol>
	 * <li>参数补缺省（path→{@link Pathes#fontPath}、style→{@link Font#PLAIN}、size→13.0F）；</li>
	 * <li>命中 {@link #baseFontCache} 直接 {@code deriveFont(fontStyle, fontSize)} 返回
	 * （不再重新解析字体文件）；</li>
	 * <li>未命中则三级查找字体流：① 本地文件存在则 {@link FileInputStream}；
	 * ② 系统类路径资源（去掉前导 {@code /} 的 resPath，修复版不再误删无前导斜杠路径的首字符）；
	 * ③ 插件资源 {@link LoaderManager#getModResStream}，再退到 activity 对应
	 * {@link sair.sys.acticity.Exection} 的 {@link SairLoader}；</li>
	 * <li>流非空则 {@link Font#createFont}(TRUETYPE_FONT) 解析 base 字体并写缓存，再派生目标样式/字号；</li>
	 * <li>finally 中关闭字体流（修复版：避免句柄泄漏导致字体文件被锁、无法热替换）。</li>
	 * </ol>
	 * <p>
	 * <b>线程安全：</b>可在任意线程调用（缓存已同步）；但建议预加载/缓存结果，
	 * 避免在 EDT 上重复解析字体文件。
	 * </p>
	 *
	 * @param pathUrl 字体路径（null 取缺省）
	 * @param fontStyle 样式（null 取 PLAIN）
	 * @param fontSize 字号（null 取 13.0F）
	 * @param activity 所属 Activity（可为 null；用于插件专属字体资源查找）
	 * @return Font；失败返回 null（异常被静默吞掉，不抛出）
	 **/
	public Font getFont(String pathUrl, Integer fontStyle, Float fontSize, Activity activity) {
		if (pathUrl == null)
			pathUrl = Pathes.fontPath;
		if (fontStyle == null)
			fontStyle = Font.PLAIN;
		if (fontSize == null)
			fontSize = 13.0F;
		Font ft = null;
		InputStream is = null;
		// 修复:无前导/的路径不再误删首字符
		String resPath = pathUrl;
		if (resPath != null && resPath.startsWith("/"))
			resPath = resPath.substring(1);
		File file = new File(pathUrl);
		try {
			Font base = getBaseFont(pathUrl);
			if (base != null) {
				// 命中缓存:直接派生,不再重新解析字体文件
				ft = base.deriveFont(fontStyle, fontSize);
				return ft;
			}
			if (file.exists())
				is = new FileInputStream(pathUrl);
			else {
				is = LoaderManager.systemLoader.getResourceAsStream(resPath);
				if (is == null) {
					is = LoaderManager.getModResStream(pathUrl);
					if (is == null && activity != null) {
						Exection ect = Libraries.exections.get(activity);
						if (ect != null) {
							SairLoader loader = LoaderManager.ExecLoaders.get(ect.getURL());
							if (loader != null)
								is = loader.getResourceAsStream(resPath);
						}
					}
				}
			}
			if (is != null) {
				BufferedInputStream bis = new BufferedInputStream(is, 524288);
				is = bis;
				Font b = Font.createFont(Font.TRUETYPE_FONT, bis);
				putBaseFont(pathUrl, b);
				ft = b.deriveFont(fontStyle, fontSize);
			}
		} catch (Exception e) {
			ft = null;
		} finally {
			// 修复:字体流关闭,避免句柄泄漏导致字体文件被锁无法热替换
			if (is != null) {
				try {
					is.close();
				} catch (Exception ce) {
				}
			}
		}
		return ft;
	}
}
