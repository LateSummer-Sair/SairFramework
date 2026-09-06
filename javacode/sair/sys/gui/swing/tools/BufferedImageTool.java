package sair.sys.gui.swing.tools;

import java.awt.Image;
import java.awt.Toolkit;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import sair.LoaderManager;
import sair.SairLoader;
import sair.sys.Libraries;
import sair.sys.acticity.Exection;
import sair.user.Activity;

/**
 * <p>
 * 图片加载工具类：从文件/类路径/插件资源读取图片为 {@link Image}，
 * 内置软引用 LRU 缓存（上限 {@link #IMAGE_CACHE_MAX}，默认 256）。
 * </p>
 * <p>
 * <b>架构角色：</b>tools 层工具；静态方法（{@link #readFile}、{@link #readPackage}、
 * {@link #streamRead}）供任意代码复用，实例方法以路径为键做缓存管理，
 * 全局唯一默认实例 {@link #DefaultImageTool} 供框架内共享；
 * 插件资源查找链与 {@link Fonts} 一致（本地文件 → 系统类路径 → 插件 →
 * Activity 专属加载器）。
 * </p>
 * <p>
 * <b>线程安全 / EDT 说明：</b>缓存 Map 是 {@code synchronizedMap} 包装的 LRU
 * {@link LinkedHashMap}，{@link #removeAll()} 再用 synchronized 块做整体快照，
 * 任意线程可安全调用；静态 {@code toolkit}（{@link Toolkit#getDefaultToolkit()}）
 * 只读使用，其 {@code createImage(byte[])} 线程安全。但返回的 {@link Image}
 * 解码是异步的，显示/绘制须在 EDT，绘图前可用 {@link java.awt.MediaTracker}
 * 或 {@code ImageIcon} 等待解码完成。
 * </p>
 * <p>
 * <b>二进制兼容约束：</b>公开成员（{@link #DefaultImageTool}、{@link #IMAGE_CACHE_MAX}、
 * 各 read/update 重载、{@link #clear()}、{@link #removeAll()}、静态 read 系列）
 * 签名不可修改；{@link #IMAGE_CACHE_MAX} 允许运行时调整（LRU 淘汰在每次插入时实时读取），
 * 属于受支持的配置点，不可删除。
 * </p>
 */
public class BufferedImageTool {

	/** 全局默认工具实例（框架内共享的图片缓存入口）。 */
	public static final BufferedImageTool DefaultImageTool = new BufferedImageTool();
	/**
	 * 图片缓存条目上限（可配置，LRU 淘汰）：防止无界缓存耗尽内存（安全加固）。
	 * 每次插入条目时由匿名 {@link LinkedHashMap#removeEldestEntry} 检查
	 * {@code size() > IMAGE_CACHE_MAX} 并淘汰最久未访问者；
	 * 可在启动时一次性调整，勿在运行时频繁修改。
	 */
	public static int IMAGE_CACHE_MAX = 256;
	/**
	 * 软引用 LRU 缓存：{@code synchronizedMap} 保证并发读写安全；
	 * {@code LinkedHashMap(32, 0.75f, true)} 按访问序维护，超出 {@link #IMAGE_CACHE_MAX}
	 * 时淘汰最久未访问条目；值用 {@link SoftReference} 包裹——
	 * 内存紧张时允许 GC 回收图片本体，下次访问自动重新加载。
	 */
	private final Map<String, SoftReference<Image>> imageCache = Collections
			.synchronizedMap(new LinkedHashMap<String, SoftReference<Image>>(32, 0.75f, true) {
				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<String, SoftReference<Image>> eldest) {
					return size() > IMAGE_CACHE_MAX;
				}
			});
	/** 全局共享 Toolkit（只读使用；createImage(byte[]) 线程安全，解码异步完成）。 */
	private static Toolkit toolkit = Toolkit.getDefaultToolkit();

	/**
	 * 读取图片（缓存优先）：命中缓存直接返回；未命中（或软引用已被 GC 回收）则
	 * 走 {@link #updateImage_byBuffer(String)} 重新加载并写回缓存。
	 * <p><b>线程安全：</b>缓存已同步，任意线程可调用。</p>
	 *
	 * @param filePathOrPackagePath 文件路径或包（类路径）资源路径（null 返回 null）
	 * @return Image；找不到资源时返回 null
	 * @throws IOException 读取过程中的 IO 错误
	 **/
	public Image readImage_byBuffer(String filePathOrPackagePath) throws IOException {
		if (filePathOrPackagePath == null)
			return null;
		SoftReference<Image> ref = imageCache.get(filePathOrPackagePath);
		if (ref != null) {
			Image cached = ref.get();
			if (cached != null)
				return cached;
		}
		return updateImage_byBuffer(filePathOrPackagePath);
	}

	/**
	 * 强制（重新）加载图片并更新缓存：本地文件存在优先 {@link #readFile(File)}，
	 * 否则按类路径资源 {@link #readPackage(String)} 查找。
	 * 加载成功写缓存；资源不存在返回 null。
	 *
	 * @param filePathOrPackagePath 文件路径或包资源路径（null 返回 null）
	 * @return Image；找不到资源时 null
	 * @throws IOException 读取过程中的 IO 错误
	 **/
	public Image updateImage_byBuffer(String filePathOrPackagePath) throws IOException {
		if (filePathOrPackagePath == null)
			return null;
		else {
			Image flag = null;
			File file = new File(filePathOrPackagePath);
			if (file.exists())
				flag = readFile(file);
			else if (!file.exists())
				flag = readPackage(filePathOrPackagePath);
			if (flag != null) {
				imageCache.put(filePathOrPackagePath, new SoftReference<Image>(flag));
				return flag;
			} else
				return null;
		}
	}

	/**
	 * 带 Activity 的缓存优先读取：未命中时走
	 * {@link #updateImage_byBuffer(String, Activity)}（文件 → 插件资源 →
	 * Activity 专属加载器）并写回缓存。
	 *
	 * @param filePathOrPackagePath 文件路径或包资源路径（null 返回 null）
	 * @param activity 所属 Activity（插件资源查找入口，可为 null）
	 * @return Image；找不到资源时 null
	 * @throws IOException 读取过程中的 IO 错误
	 **/
	public Image readImage_byBuffer(String filePathOrPackagePath, Activity activity) throws IOException {
		if (filePathOrPackagePath == null)
			return null;
		SoftReference<Image> ref = imageCache.get(filePathOrPackagePath);
		if (ref != null) {
			Image cached = ref.get();
			if (cached != null)
				return cached;
		}
		return updateImage_byBuffer(filePathOrPackagePath, activity);
	}

	/**
	 * 带 Activity 的强制加载：本地文件存在优先；否则通过插件/Activity 加载器
	 * 查找包资源（见 {@link #readPackage(String, Activity)}）。加载成功写缓存。
	 *
	 * @param filePathOrPackagePath 文件路径或包资源路径（null 返回 null）
	 * @param activity 所属 Activity（null 时仅查系统类路径与插件资源）
	 * @return Image；找不到资源时 null
	 * @throws IOException 读取过程中的 IO 错误
	 **/
	public Image updateImage_byBuffer(String filePathOrPackagePath, Activity activity) throws IOException {
		if (filePathOrPackagePath == null)
			return null;
		else {
			Image flag = null;
			File file = new File(filePathOrPackagePath);
			if (file.exists())
				flag = readFile(file);
			else if (!file.exists() && activity != null)
				flag = readPackage(filePathOrPackagePath, activity);
			if (flag != null) {
				imageCache.put(filePathOrPackagePath, new SoftReference<Image>(flag));
				return flag;
			} else
				return null;
		}
	}

	/**
	 * 清空全部缓存条目（图片本体不主动释放，交由 GC 按软引用回收）。
	 * 线程安全（synchronizedMap）。
	 **/
	public void clear() {
		imageCache.clear();
	}

	/**
	 * 清空缓存并把仍存活的图片（软引用未被 GC 回收者）打包返回。
	 * 用于框架卸载/模块重载时回收图片资源；synchronized 块保证
	 * “快照 + 清空”的原子性。
	 *
	 * @return 仍存活的 路径→Image 快照（不含已被回收的条目）
	 **/
	public HashMap<String, Image> removeAll() {
		HashMap<String, Image> alive = new HashMap<String, Image>();
		synchronized (imageCache) {
			for (Map.Entry<String, SoftReference<Image>> e : imageCache.entrySet()) {
				Image img = e.getValue().get();
				if (img != null)
					alive.put(e.getKey(), img);
			}
			imageCache.clear();
		}
		return alive;
	}

	/**
	 * 静态：从本地文件读取图片（委托 {@link #streamRead} 一次读入字节数组交给
	 * {@link Toolkit#createImage}）。
	 *
	 * @param file 图片文件
	 * @return Image
	 * @throws IOException 文件读取失败
	 **/
	public static Image readFile(File file) throws IOException {
		return streamRead(new FileInputStream(file));
	}

	/**
	 * 静态：按包（类路径）资源路径读取图片，等价于 activity 传 null 的重载。
	 *
	 * @param packagePath 类路径资源路径（允许带前导 /，内部自动去除）
	 * @return Image；资源不存在返回 null
	 * @throws IOException 读取 IO 失败
	 **/
	public static Image readPackage(String packagePath) throws IOException {
		return readPackage(packagePath, null);
	}

	/**
	 * 静态：按包资源读取图片，查找链——
	 * ① 系统类路径资源（去掉前导 {@code /} 的 resPath，修复版不再误删无前导斜杠路径的首字符）；
	 * ② {@link LoaderManager#getModResStream} 插件资源；
	 * ③ activity 对应的 {@link Exection} → {@link SairLoader} 专属资源。
	 * 全部未命中返回 null（不抛异常）。
	 *
	 * @param packagePath 类路径资源路径（null 时按不命中处理）
	 * @param activity 所属 Activity（可为 null）
	 * @return Image；资源不存在返回 null
	 * @throws IOException 读取 IO 失败
	 **/
	public static Image readPackage(String packagePath, Activity activity) throws IOException {
		// 修复:无前导/的路径不再误删首字符
		String resPath = packagePath;
		if (resPath != null && resPath.startsWith("/"))
			resPath = resPath.substring(1);
		InputStream input = LoaderManager.systemLoader.getResourceAsStream(resPath);
		if (input == null) {
			input = LoaderManager.getModResStream(packagePath);
			if (input == null && activity != null) {
				Exection ect = Libraries.exections.get(activity);
				if (ect != null) {
					SairLoader loader = LoaderManager.ExecLoaders.get(ect.getURL());
					if (loader != null)
						input = loader.getResourceAsStream(resPath);
				}
			}
		}
		if (input != null)
			return streamRead(input);
		else
			return null;
	}

	/**
	 * <p>
	 * 静态：从任意 InputStream 完整读入字节并解码为 {@link Image}
	 * （字节流 → 字节数组 → {@link Toolkit#createImage}，不依赖 javax.imageio 同步解码）。
	 * </p>
	 * <p>
	 * 修复版语义：关闭失败不再抛裸 IOException 掩盖真实读取错误——
	 * 读取阶段捕获的原始 IOException 保存后原样重抛；{@code bos}/{@code input}
	 * 的 close 异常静默忽略。返回的 Image 尚未完成异步解码，
	 * 绘图前需自行等待（如 {@link java.awt.MediaTracker}）。
	 * </p>
	 *
	 * @param input 输入流（null 直接返回 null）
	 * @return Image；流中读不到内容时为 null
	 * @throws IOException 读取过程中发生的 IO 错误（原样重抛，不被 close 异常掩盖）
	 **/
	public static Image streamRead(InputStream input) throws IOException {
		if (input == null)
			return null;
		input = new BufferedInputStream(input, 262144);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		Image image = null;
		IOException readError = null;
		try {
			byte[] buffer = new byte[262144];
			int len = -1;
			while ((len = input.read(buffer)) >= 0)
				bos.write(buffer, 0, len);
			byte[] result = bos.toByteArray();
			image = toolkit.createImage(result);
		} catch (IOException e) {
			readError = e;
		} finally {
			// 修复:关闭失败不再抛裸IOException掩盖真实读取错误
			try {
				bos.close();
			} catch (Exception e) {
			}
			try {
				input.close();
			} catch (Exception e) {
			}
		}
		if (readError != null)
			throw readError;
		return image;
	}
}
