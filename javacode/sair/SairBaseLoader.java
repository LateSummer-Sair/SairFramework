package sair;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.SecureClassLoader;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Sair 框架自定义类加载器基类(SecureClassLoader 子类)。
 * <p>
 * 职责:挂载 jar 并直接以 JarFile 字节流定义类(不走 URLClassLoader),同时重写
 * findResource/findResources,使 META-INF/services 等资源可被 ServiceLoader(SPI)、
 * DriverManager、AudioSystem 等基于线程上下文类加载器的机制发现。
 * <p>
 * 架构角色:类加载器父子链的公共实现——全局 SairLoader(加载 plugins/lib)与
 * 每插件独立 ExectionLoader(父为全局 SairLoader)都继承自本类。
 * <p>
 * 线程安全:
 * <ul>
 * <li>静态块调用 registerAsParallelCapable():本类所有子类实例的 loadClass 可被
 *     多线程并行调用(共享 lib 加载器在插件并行加载时不再全部串行排队);</li>
 * <li>{@link #dead} 为 volatile 布尔:dispose 置位后 findClass 立即给出"已卸载"错误,
 *     运行中线程惰性加载不会得到未定义行为;</li>
 * <li>{@link #jars} 映射的读写(含 findClass/findResource 的查找)全部在
 *     synchronized(jars) 上;单个 JarFile 的 getEntry 在其自身监视器上同步;
 *     removeJarURL 先出映射再关闭 JarFile,避免与进行中的查找竞争句柄;</li>
 * <li>{@link #snapshotJarFiles()} 返回键集合快照,遍历快照不持有映射锁。</li>
 * </ul>
 * <p>
 * 二进制兼容约束(不可改):
 * <ul>
 * <li>{@link #dead}(protected volatile boolean)与 {@link #jars}
 *     (protected HashMap&lt;File,JarFile&gt;)被 SairLoader/ExectionLoader/LoaderManager
 *     直接访问(LoaderManager.getModResStream 直接 synchronized(loader.jars)),字段名不可改;</li>
 * <li>{@link #MAX_CLASS_BYTES}(public static long,默认 64MB)可配置,字段名/类型不可改;</li>
 * <li>findClass/findResource/findResources 为 JDK ClassLoader 约定签名,不可改;</li>
 * <li>资源 URL 形如 sairjar:/jar:file:...!/条目名,条目名经百分号编码,
 *     与 SairJarHandler 的解码对称,两边格式不可单方面修改。</li>
 * </ul>
 */
public class SairBaseLoader extends SecureClassLoader {

	// 修复:注册并行能力,共享lib加载器被多线程loadClass时不再全部串行排队
	static {
		ClassLoader.registerAsParallelCapable();
	}

	/**
	 * 卸载死亡标记(volatile,不可改字段名):dispose 后 findClass 直接报"已卸载",
	 * 替代难以定位的 ClassNotFoundException。写线程为卸载线程,读线程为任意
	 * 进行惰性加载的线程,volatile 保证跨线程可见性
	 */
	protected volatile boolean dead = false;

	/**
	 * 已挂载的 jar 文件映射(File -> 打开的 JarFile;不可改字段名/类型)。
	 * 所有访问必须在 synchronized(jars) 上进行(removeJarURL 先移除再关闭,
	 * 保证关闭动作不与进行中的查找竞争);LoaderManager 亦按此锁约定直接访问
	 */
	protected HashMap<File, JarFile> jars = new HashMap<File, JarFile>();

	/**
	 * 构造器:显式指定父类加载器(ExectionLoader 用它把父指向全局 SairLoader)。
	 *
	 * @param p 父类加载器
	 */
	protected SairBaseLoader(ClassLoader p) {
		super(p);
	}

	/**
	 * 构造器:父默认为系统类加载器(全局 SairLoader 的默认父)。
	 */
	protected SairBaseLoader() {
		super(ClassLoader.getSystemClassLoader());
	}

	/**
	 * 挂载单个 jar:大小写不敏感校验扩展名(.jar,Locale.ROOT)后打开 JarFile
	 * 并登记进 jars 映射。
	 *
	 * @param file 目标 jar 文件;为 null 时静默忽略
	 * @throws IOException 文件存在但不是 .jar 文件
	 */
	protected void addJarFile(File file) throws IOException {
		if (file != null) {
			// 修复:大小写不敏感匹配(Locale.ROOT),Linux上.JAR插件不再被忽略
			if (file.exists() && file.getAbsolutePath().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
				synchronized (jars) {
					jars.put(file, new JarFile(file));
				}
			} else
				throw new IOException("is not JAR File!!!");
		}
	}

	/**
	 * 卸载单个 jar:从映射移除(同步块内)后关闭其 JarFile,释放文件句柄;
	 * 关闭失败打印到 stderr(Windows 下意味着文件仍被锁定)。
	 *
	 * @param file 要卸载的 jar 文件
	 * @return 被移除的 JarFile(已关闭);不存在或关闭异常时返回 null
	 */
	protected JarFile removeJarURL(File file) {
		try {
			JarFile jar;
			synchronized (jars) {
				jar = jars.remove(file);
			}
			if (jar != null)
				jar.close();
			return jar;
		} catch (Exception e) {
			// 修复:关闭失败不再静默(Windows下意味着文件仍被锁定)
			System.err.println("[SairBaseLoader] close jar failed: " + file + " -> " + e);
		}
		return null;
	}

	/**
	 * 获取当前 jar 列表的快照(线程安全):在 jars 锁内拷贝键集合,
	 * 返回后遍历快照不再持有映射锁,卸载线程可并发关闭 jar。
	 *
	 * @return 已挂载 jar 文件集合的快照(可安全遍历)
	 */
	protected Set<File> snapshotJarFiles() {
		synchronized (jars) {
			return new HashSet<File>(jars.keySet());
		}
	}

	/**
	 * 按双亲委派失败后的标准入口查找并定义类:把类名换算为 .class 条目路径,
	 * 按挂载顺序在全部 jar 中查找;已卸载(dead)时直接抛"插件已卸载"。
	 * <p>
	 * 读取上限受 {@link #MAX_CLASS_BYTES} 约束(防恶意超大类 OOM);
	 * 定义前补定义 Package 信息(兼容 sqlite-jdbc 等依赖 Class.getPackage() 的库)。
	 *
	 * @param name 类全限定名(二进制名)
	 * @return 定义完成的 Class
	 * @throws ClassNotFoundException 已卸载、条目不存在或读取失败(读取失败携带 cause)
	 */
	protected Class<?> findClass(String name) throws ClassNotFoundException {
		if (dead)
			throw new ClassNotFoundException(name + " (插件已卸载)");
		String classPath = name.replace(".", "/").concat(".class");
		for (File file : snapshotJarFiles()) {
			JarFile jf;
			synchronized (jars) {
				jf = jars.get(file);
			}
			if (jf == null)
				continue;
			ZipEntry ze;
			synchronized (jf) {
				ze = jf.getEntry(classPath);
			}
			if (ze == null)
				continue;
			byte[] b;
			try {
				b = readEntry(jf, ze);
			} catch (IOException e) {
				throw new ClassNotFoundException("URL read fail !!! [" + name + "]", e);
			}
			return defineClass0(name, b);
		}
		throw new ClassNotFoundException(name);
	}

	/**
	 * 定义类并关联 Package 信息。
	 * <p>
	 * URLClassLoader 同样会调用 definePackage;若缺失,库中
	 * Class.getPackage() 将返回 null(sqlite-jdbc 等加载原生库时依赖它)。
	 * 包已被并发线程定义时 IllegalArgumentException 被忽略。
	 *
	 * @param name 类全限定名
	 * @param b    类文件字节码
	 * @return 定义完成的 Class
	 */
	private Class<?> defineClass0(String name, byte[] b) {
		int dot = name.lastIndexOf('.');
		if (dot >= 0) {
			String pkgName = name.substring(0, dot);
			if (getPackage(pkgName) == null) {
				try {
					definePackage(pkgName, null, null, null, null, null, null, null);
				} catch (IllegalArgumentException e) {
					// 包已被并发线程定义,忽略
				}
			}
		}
		return defineClass(name, b, 0, b.length);
	}

	/**
	 * 类文件读取上限(字节,可配置,字段名/类型不可改):默认 64MB,
	 * 防止恶意 jar 声明超大解压体积导致 OOM(安全加固)
	 */
	public static long MAX_CLASS_BYTES = 64L * 1024L * 1024L;

	/**
	 * 按 ZipEntry 已知大小一次性读取(避免 ByteArrayOutputStream 反复扩容):
	 * 声明大小已知时按精确长度读入;未知大小(-1)时走流式缓冲并逐段校验上限。
	 *
	 * @param jf 已打开的 JarFile
	 * @param ze 目标条目
	 * @return 条目完整字节
	 * @throws IOException 声明大小/实际读取超过 {@link #MAX_CLASS_BYTES} 或读取失败
	 */
	private static byte[] readEntry(JarFile jf, ZipEntry ze) throws IOException {
		long size = ze.getSize();
		if (size > MAX_CLASS_BYTES)
			throw new IOException("entry too large (" + size + " bytes): " + ze.getName());
		InputStream fis = new BufferedInputStream(jf.getInputStream(ze), 81920);
		try {
			if (size > 0 && size <= Integer.MAX_VALUE - 8) {
				byte[] b = new byte[(int) size];
				int off = 0;
				while (off < b.length) {
					int code = fis.read(b, off, b.length - off);
					if (code < 0)
						break;
					off += code;
				}
				if (off == b.length)
					return b;
				return Arrays.copyOf(b, off);
			}
			ByteArrayOutputStream bos = new ByteArrayOutputStream(81920);
			byte[] cb = new byte[81920];
			int code;
			while ((code = fis.read(cb)) >= 0) {
				if (bos.size() + code > MAX_CLASS_BYTES)
					throw new IOException("entry too large: " + ze.getName());
				bos.write(cb, 0, code);
			}
			return bos.toByteArray();
		} finally {
			try {
				fis.close();
			} catch (IOException e) {
			}
		}
	}

	/**
	 * 查找资源:归一化名称后按挂载顺序在全部 jar 中查找首个命中条目,
	 * 返回 sairjar: 或 jar:file:...!/条目 形式的 URL(条目名已百分号编码,
	 * 由 SairJarHandler 侧对称解码),供 getResource/ServiceLoader 直接 openStream。
	 *
	 * @param name 资源名(允许以 "/" 开头)
	 * @return 命中资源的 URL;未找到返回 null
	 */
	@Override
	protected URL findResource(String name) {
		String entryName = normEntry(name);
		for (File file : snapshotJarFiles()) {
			JarFile jf;
			synchronized (jars) {
				jf = jars.get(file);
			}
			if (jf == null)
				continue;
			ZipEntry ze;
			synchronized (jf) {
				ze = jf.getEntry(entryName);
			}
			if (ze == null)
				continue;
			try {
				return jarEntryURL(file, entryName);
			} catch (MalformedURLException e) {
				return null;
			}
		}
		return null;
	}

	/**
	 * 查找全部同名资源:与 {@link #findResource} 相同,但收集所有 jar 中的
	 * 命中条目(ServiceLoader 聚合多 jar 服务配置依赖此行为),返回 Vector 枚举。
	 *
	 * @param name 资源名
	 * @return 全部命中资源 URL 的枚举(可为空)
	 * @throws IOException 签名兼容(本实现不抛出)
	 */
	@Override
	protected Enumeration<URL> findResources(String name) throws IOException {
		String entryName = normEntry(name);
		Vector<URL> urls = new Vector<URL>();
		for (File file : snapshotJarFiles()) {
			JarFile jf;
			synchronized (jars) {
				jf = jars.get(file);
			}
			if (jf == null)
				continue;
			ZipEntry ze;
			synchronized (jf) {
				ze = jf.getEntry(entryName);
			}
			if (ze == null)
				continue;
			try {
				urls.add(jarEntryURL(file, entryName));
			} catch (MalformedURLException e) {
			}
		}
		return urls.elements();
	}

	/**
	 * 资源名归一化:去掉开头 "/"(与 ClassLoader 资源名约定保持一致)。
	 *
	 * @param name 原始资源名,可为 null
	 * @return 归一化后的条目名
	 */
	private static String normEntry(String name) {
		if (name != null && name.startsWith("/"))
			return name.substring(1);
		return name;
	}

	/**
	 * 生成 jar 内资源的 URL(jar:file:...!/entry):条目名先百分号编码(空格/#/?/%),
	 * 再按 SairJarHandler 可用性选择 sairjar: 协议(每次 openStream 独立打开 JarFile,
	 * 卸载后句柄立即释放)或回退 jar: 协议。ServiceLoader 等可直接 openStream。
	 *
	 * @param file      资源所在 jar 文件
	 * @param entryName jar 内条目名(已归一化)
	 * @return 可 openStream 的资源 URL
	 * @throws MalformedURLException URL 构造失败(本实现基本不触发)
	 */
	private static URL jarEntryURL(File file, String entryName) throws MalformedURLException {
		// 修复:条目名完整百分号编码(空格/#/?/%),SairJarConnection侧同步解码
		String target = file.toURI().toURL().toExternalForm() + "!/" + encodeEntry(entryName);
		if (SairJarHandler.active())
			return new URL("sairjar", null, -1, target);
		return new URL("jar:" + target);
	}

	/**
	 * 条目名百分号编码(仅空格/#/?/% 四个特殊字符;非 ASCII 按 UTF-8 原样传递,
	 * 由 SairJarHandler 连接侧 decodeEntry 解码还原)。
	 *
	 * @param name 原始条目名
	 * @return 编码后的条目名
	 */
	private static String encodeEntry(String name) {
		StringBuilder sb = new StringBuilder(name.length() + 8);
		for (int i = 0; i < name.length(); i++) {
			char ch = name.charAt(i);
			if (ch == ' ' || ch == '#' || ch == '?' || ch == '%') {
				sb.append('%');
				String hx = Integer.toHexString(ch).toUpperCase(java.util.Locale.ROOT);
				if (hx.length() < 2)
					sb.append('0');
				sb.append(hx);
			} else
				sb.append(ch);
		}
		return sb.toString();
	}
}
