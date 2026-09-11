package sair;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
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
 * <li>资源 URL 形如 sairjar:file:...!/条目名(协议不可用时为 jar:file:...!/条目名),
 *     条目名经百分号编码,与 SairJarHandler 的解码对称,两边格式不可单方面修改。</li>
 * </ul>
 */
public class SairBaseLoader extends SecureClassLoader {

	// 修复:注册并行能力,共享lib加载器被多线程loadClass时不再全部串行排队
	static {
		ClassLoader.registerAsParallelCapable();
	}

	/**
	 * 多版本JAR(MR-JAR)支持开关(公开可配置,默认开;二进制兼容:可改值不可改字段名/类型):
	 * 关闭后所有JVM(含JDK9+)都只读取base条目,恢复旧版行为;JDK8上本开关无效果
	 * (JDK8没有多版本概念,永远读base条目,与旧版一致)。
	 */
	public static boolean ENABLE_MULTIRELEASE = true;

	/**
	 * JDK9+ 的 JarFile(File, boolean, int, Runtime.Version) 多版本构造器(反射缓存;
	 * JDK8上Runtime$Version类不存在,此值为null)。
	 * <p>源码不能出现任何JDK9+类型(本工程以JDK8 javac编译),故用Class.forName全反射取得,
	 * 在JDK9+上以Runtime.version()(当前运行时版本)打开多版本jar,getEntry自动映射
	 * META-INF/versions/N/ 下的类与资源(含META-INF/services的SPI配置)。
	 */
	private static final Constructor<JarFile> MR_CTOR;

	/**
	 * JDK9+ 的 Runtime.version() 返回值(静态块内反射调用一次并缓存;JDK8上为null)。
	 */
	private static final Object MR_VERSION;

	static {
		Constructor<JarFile> c = null;
		Object v = null;
		try {
			// 全反射:JDK8上Class.forName抛ClassNotFoundException→两值都为null→自动降级普通打开
			Class<?> vc = Class.forName("java.lang.Runtime$Version");
			c = JarFile.class.getConstructor(File.class, boolean.class, int.class, vc);
			v = Runtime.class.getMethod("version").invoke(null);
		} catch (Throwable t) {
			// 反射不可用(JDK8或特殊JRE):保持null,openJar自动降级普通打开(与旧版行为一致)
			c = null;
			v = null;
		}
		MR_CTOR = c;
		MR_VERSION = v;
	}

	/**
	 * 统一开jar入口(框架全部 new JarFile 的唯一出口,公开静态):
	 * JDK9+且开关打开时以多版本模式打开(版本化类与资源自动映射);
	 * JDK8/反射失败/开关关闭一律降级普通打开(与旧版行为完全一致)。
	 *
	 * @param file 目标jar文件
	 * @return 打开完成的JarFile
	 * @throws IOException jar无法打开
	 */
	public static JarFile openJar(File file) throws IOException {
		if (ENABLE_MULTIRELEASE && MR_CTOR != null) {
			try {
				// mode=1即ZipFile.OPEN_READ(public static final int,硬编码避免源码引用JDK9+符号)
				return MR_CTOR.newInstance(file, Boolean.TRUE, Integer.valueOf(1), MR_VERSION);
			} catch (Throwable t) {
				// 反射调用失败:降级普通打开(行为与旧版一致)
			}
		}
		return new JarFile(file);
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
	 * @throws IOException 文件不存在或不是 .jar 文件
	 */
	protected void addJarFile(File file) throws IOException {
		if (file != null) {
			// 修复:大小写不敏感匹配(Locale.ROOT),Linux上.JAR插件不再被忽略
			if (file.exists() && file.getAbsolutePath().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
				// 锁外打开(磁盘I/O不占映射锁)
				JarFile jf = openJar(file);
				synchronized (jars) {
					// 修复:重复挂载时旧JarFile必须关闭,否则Windows句柄泄漏(文件锁死)
					JarFile old = jars.put(file, jf);
					if (old != null) {
						try {
							old.close();
						} catch (IOException e) {
							System.err.println("[SairBaseLoader] close replaced jar failed: " + file + " -> " + e);
						}
					}
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
				// 修复:关闭在jar自身监视器上执行——与findClass/findResource/getModResStream的
				// 整段"取条目+读流"互斥:进行中的读取完整结束后才关闭,消除读流中途句柄被关的竞态
				synchronized (jar) {
					jar.close();
				}
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
		// 已卸载:直接给出"插件已卸载"错误(替代难以定位的ClassNotFoundException)
		if (dead)
			throw new ClassNotFoundException(name + " (插件已卸载)");
		// 类全限定名换算为.class条目路径(二进制名→路径)
		String classPath = name.replace(".", "/").concat(".class");
		// 按挂载顺序在全部jar中查找(遍历快照,不持有映射锁)
		for (File file : snapshotJarFiles()) {
			JarFile jf;
			// 锁jars映射取JarFile(与removeJarURL互斥)
			synchronized (jars) {
				jf = jars.get(file);
			}
			if (jf == null)
				continue;
			ZipEntry ze;
			byte[] b;
			// 锁单个JarFile完成"取条目+读字节"整段:与removeJarURL的关闭互斥,
			// 读流期间句柄不会被并发关闭(消除读到一半抛IllegalStateException的竞态)
			synchronized (jf) {
				try {
					ze = jf.getEntry(classPath);
					if (ze == null)
						continue;
					// 按MAX_CLASS_BYTES上限读取类字节
					b = readEntry(jf, ze);
				} catch (IllegalStateException ise) {
					// 兜底:jar刚被并发卸载关闭(关闭先于本线程进锁),视同该jar未命中,继续查下一个
					continue;
				} catch (IOException e) {
					throw new ClassNotFoundException("URL read fail !!! [" + name + "]", e);
				}
			}
			// 补定义Package后defineClass
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
	 * 声明大小已知(>0 且可装入数组)时按精确长度读入,读不满按实际长度截断;
	 * 大小未知(-1)或为 0 时走流式缓冲并逐段校验上限。
	 *
	 * @param jf 已打开的 JarFile
	 * @param ze 目标条目
	 * @return 条目完整字节
	 * @throws IOException 声明大小/实际读取超过 {@link #MAX_CLASS_BYTES} 或读取失败
	 */
	private static byte[] readEntry(JarFile jf, ZipEntry ze) throws IOException {
		long size = ze.getSize();
		// 声明大小直接超限:立即拒绝,不分配内存
		if (size > MAX_CLASS_BYTES)
			throw new IOException("entry too large (" + size + " bytes): " + ze.getName());
		InputStream fis = new BufferedInputStream(jf.getInputStream(ze), 81920);
		try {
			// 大小已知:按精确长度一次性读入,读不满按实际长度截断
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
			// 大小未知/为0:流式缓冲读取,每段写入前校验上限
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
		// 归一化资源名(去开头"/")
		String entryName = normEntry(name);
		// 按挂载顺序在全部jar中查找首个命中条目
		for (File file : snapshotJarFiles()) {
			JarFile jf;
			synchronized (jars) {
				jf = jars.get(file);
			}
			if (jf == null)
				continue;
			ZipEntry ze;
			synchronized (jf) {
				try {
					ze = jf.getEntry(entryName);
				} catch (IllegalStateException ise) {
					// jar刚被并发卸载关闭:视同未命中,继续查下一个jar
					continue;
				}
			}
			if (ze == null)
				continue;
			try {
				// 生成sairjar:/jar:资源URL(条目名已百分号编码,连接侧对称解码)
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
		// 收集所有jar中的同名条目(ServiceLoader聚合多jar服务配置依赖此行为)
		for (File file : snapshotJarFiles()) {
			JarFile jf;
			synchronized (jars) {
				jf = jars.get(file);
			}
			if (jf == null)
				continue;
			ZipEntry ze;
			synchronized (jf) {
				try {
					ze = jf.getEntry(entryName);
				} catch (IllegalStateException ise) {
					// jar刚被并发卸载关闭:视同未命中,继续查下一个jar
					continue;
				}
			}
			if (ze == null)
				continue;
			try {
				urls.add(jarEntryURL(file, entryName));
			} catch (MalformedURLException e) {
				// URL构造失败:跳过该命中,继续收集其余jar
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
	 * 条目名百分号编码(仅空格/#/?/% 四个特殊字符,编码为 %XX 大写十六进制;
	 * 其余字符含非 ASCII 原样透传,由 SairJarHandler.SairJarConnection.decodeEntry
	 * 按 UTF-8 字节解码还原,两侧对称)。
	 *
	 * @param name 原始条目名
	 * @return 编码后的条目名
	 */
	private static String encodeEntry(String name) {
		StringBuilder sb = new StringBuilder(name.length() + 8);
		for (int i = 0; i < name.length(); i++) {
			char ch = name.charAt(i);
			// 仅编码四个URL特殊字符(%XX大写十六进制),其余字符(含非ASCII)原样透传
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
