package sair;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import sair.sys.Libraries;
import sair.sys.SairCons;
import sair.sys.acticity.Exection;
import sair.sys.acticity.Mod;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

/**
 * 依赖库与插件的加载管理器(启动管线与运行时 /load 命令共用)。
 * <p>
 * 职责:串行加载 plugins/lib 依赖库到全局 SairLoader;对 plugins/exection 插件执行
 * 三阶段加载管线(Phase A 预登记、Phase B 并行 loadClass、Phase C 串行实例化注册),
 * 并负责失败插件的卸载(unloadFailed)与各类登记表的维护。
 * <p>
 * 架构角色:插件生命周期的中枢。类加载器父子链为:
 * 系统类加载器 &lt;- 全局 SairLoader({@link #loader},加载 lib) &lt;- 每插件独立
 * ExectionLoader(父为全局 loader,加载 exection)。SPI 机制通过 Main 安装的
 * 线程上下文类加载器(全局 loader)发现 lib 中的实现。
 * <p>
 * 线程安全:
 * <ul>
 * <li>公开静态集合 {@link #ExecLoaders}/{@link #libJarPathSet}/{@link #execJarPathSet}
 *     由 Safe 工厂创建(基础单操作同步);{@link #loadExecJar}/{@link #loadLibJar}
 *     在对应集合监视器上把"查重+建loader+登记"整体原子化,并发/load 同一 jar
 *     不会产生双 loader 或共享 loader 被误卸载;</li>
 * <li>Phase A 预登记与 Phase C 串行实例化在主线程执行(保证目录顺序确定);</li>
 * <li>Phase B 由固定线程池({@link #MAX_LOAD_THREADS} 上限)并行 loadClass:
 *     类加载器已 registerAsParallelCapable,findClass 内部对 jars 映射与单个
 *     JarFile 加锁,且 loadClass 纯内存、无构造器副作用,可安全并行;</li>
 * <li>{@link #getModResStream} 与 SairBaseLoader 的加载路径对同一组锁同步。</li>
 * </ul>
 * <p>
 * 二进制兼容约束(旧 0.5.3 插件依赖,不可改):
 * <ul>
 * <li>公开静态字段 {@link #systemLoader}、{@link #loader} 的字段名/类型/final 不可改;</li>
 * <li>公开静态集合 {@link #ExecLoaders}(HashMap&lt;URL,SairLoader&gt;)、
 *     {@link #libJarPathSet}/{@link #execJarPathSet}(HashSet&lt;String&gt;) 字段名与类型不可改;</li>
 * <li>{@link #MAX_LOAD_THREADS} 为可配置 int,仅允许修改值,不允许改字段名/类型;</li>
 * <li>公开方法 {@link #loadMain}/{@link #loadOneExecJar}/{@link #loadExecJar}/
 *     {@link #loadLibJar}/{@link #getExecLoader}/{@link #getModResStream} 签名不可改。</li>
 * </ul>
 */
public class LoaderManager {



	/**
	 * 系统类加载器与全局依赖库加载器(公开常量,不可改字段名/类型/final):
	 * loader 加载 plugins/lib,并作为每个 ExectionLoader 的父加载器,构成插件类加载父子链
	 */
	public final static ClassLoader systemLoader = ClassLoader.getSystemClassLoader(), loader = new SairLoader();

	/**
	 * 插件 URL -> 独立 ExectionLoader 的登记表(公开集合,不可改字段名/类型;
	 * 基础单操作同步,复合操作由调用方自行同步)
	 */
	public final static HashMap<URL, SairLoader> ExecLoaders = Safe.map();

	/**
	 * 已登记的依赖库 jar 路径集合(公开集合,不可改字段名/类型;
	 * loadLibJar 在监视器上原子完成查重+加载+登记)
	 */
	public final static HashSet<String> libJarPathSet = Safe.set();

	/**
	 * 已登记的插件 jar 路径集合(公开集合,不可改字段名/类型;
	 * loadExecJar 在监视器上原子完成查重+建loader+登记)
	 */
	public final static HashSet<String> execJarPathSet = Safe.set();

	/**
	 * 插件加载并行度(可配置):lib 串行加载完成后,exection 类加载阶段(Phase B)使用的线程数。
	 * 默认取 CPU 核数并夹在 [2,8] 区间,任务数不足时按任务数取小。
	 * 二进制兼容:只可修改值,不可改字段名/类型
	 */
	public static int MAX_LOAD_THREADS = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));

	/**
	 * 运行时加载单个插件 jar(仅登记 ExectionLoader 与路径,不加载类,后续阶段由调用方决定)。
	 * <p>
	 * 在 execJarPathSet 监视器上把"查重 + 建 loader + 登记"整体原子化:
	 * 并发或重复 /load 同一 jar 不会产生双 loader,也不会让共享 loader 被误卸载。
	 *
	 * @param filePath 插件 jar 的本地路径
	 * @return true=本次新建并登记成功;false=该路径已登记过
	 * @throws IOException 文件不存在/非法时由底层(new File/toURI)抛出
	 */
	public static boolean loadExecJar(String filePath) throws IOException {
		// 修复:查重+建loader+登记整体原子,并发/load同一jar不会产生双loader或共享loader被误卸载
		synchronized (execJarPathSet) {
			if (!execJarPathSet.contains(filePath)) {
				File file = new File(filePath);
				URL url = file.toURI().toURL();
				ExectionLoader exel = new ExectionLoader();
				exel.addJarFiles(file);

				ExecLoaders.put(url, exel);
				execJarPathSet.add(filePath);

				return true;
			}
			return false;
		}
	}

	/**
	 * 运行时向全局 SairLoader 追加一个依赖库 jar(与 loadExecJar 一致:
	 * 查重+加载+登记整体原子化,并发 /load 同一 jar 不会重复登记)。
	 *
	 * @param filePath 依赖库 jar 的本地路径
	 * @return true=本次新增成功;false=该路径已登记过
	 * @throws IOException 文件非法或打开失败
	 */
	public final static boolean loadLibJar(String filePath) throws IOException {
		// 修复:与loadExecJar一致,查重+加载+登记原子化
		synchronized (libJarPathSet) {
			if (!libJarPathSet.contains(filePath)) {
				File file = new File(filePath);
				((SairLoader) loader).addJarFiles(file);
				libJarPathSet.add(filePath);
				return true;
			}
			return false;
		}
	}

	/**
	 * 从指定类加载器加载插件主类(Activity 子类)并实例化。
	 * <p>
	 * 使用 getDeclaredConstructor().newInstance() 兼容 Java17(替代已废弃的
	 * Class.newInstance()),并保留旧版"构造器异常原样抛出"语义:无参构造器缺失
	 * 转 InstantiationException,构造器抛出的 RuntimeException/Error 原样上抛,
	 * 其余异常包装为带 cause 链的 InstantiationException 以便 errorInfo 定位真实类型。
	 *
	 * @param className 插件主类全限定名
	 * @param loader    用于加载该类的类加载器(通常为插件自己的 ExectionLoader)
	 * @return 实例化完成的 Activity
	 * @throws ClassNotFoundException     类不存在,或存在但不是 Activity 子类
	 * @throws InstantiationException     无无参构造器或构造器执行失败
	 * @throws IllegalAccessException     保留旧版签名(当前实现不抛出,调用方仍须处理)
	 * @throws NoClassDefFoundError       加载期依赖缺失;构造器抛出的 Error 原样上抛
	 */
	public static Activity loadMain(String className, ClassLoader loader)
			throws ClassNotFoundException, InstantiationException, IllegalAccessException, NoClassDefFoundError {

		Class<?> clazz = loader.loadClass(className);

		if (Activity.class.isAssignableFrom(clazz)) {

			// Java17兼容写法:替代已废弃的Class.newInstance(),并保留旧版"构造器异常原样抛出"语义
			Activity acti;
			try {
				acti = (Activity) clazz.getDeclaredConstructor().newInstance();
			} catch (NoSuchMethodException e) {
				throw new InstantiationException("no no-arg constructor: " + className);
			} catch (InvocationTargetException e) {
				Throwable cause = e.getTargetException();
				if (cause instanceof RuntimeException)
					throw (RuntimeException) cause;
				if (cause instanceof Error)
					throw (Error) cause;
				// 修复:保留cause链,errorInfo能定位真实异常类型
				InstantiationException ie = new InstantiationException("constructor failed: " + cause);
				ie.initCause(cause);
				throw ie;
			}

			return acti;

		} else
			throw new ClassNotFoundException("Class File Error！is not Activity！");

	}

	/**
	 * 运行时加载单个插件 jar(本地路径;/load 命令使用)。
	 * 与启动管线一致:预登记 → 并行加载类 → 串行实例化注册;任一阶段失败即
	 * 卸载该插件并返回 false(红字打印原因),绝不让半初始化插件残留。
	 *
	 * @param path 插件 jar 本地路径
	 * @return true=加载并注册成功;false=已加载过或任一阶段失败
	 */
	public static boolean loadOneExecJar(String path) {
		try {
			if (execJarPathSet.contains(path)) {
				SairCons.println(FCM.Error_Color, "插件已加载: " + path);
				return false;
			}
			Exection ex = preRegisterExec(path);
			if (ex == null)
				return false;
			ex.parallelLoadClasses();
			if (ex.hasLoadError()) {
				try {
					ex.unLoadJar();
				} catch (Exception e2) {
				}
				SairCons.println(FCM.Error_Color, path + " -> load fail : " + errorInfo(ex.getLoadError()));
				return false;
			}
			ex.serialInstantiate();
			if (ex.hasLoadError()) {
				try {
					ex.unLoadJar();
				} catch (Exception e2) {
				}
				SairCons.println(FCM.Error_Color, path + " -> load fail : " + errorInfo(ex.getLoadError()));
				return false;
			}
			return true;
		} catch (Throwable e) {
			SairCons.println(FCM.Error_Color, path + " -> load fail : " + errorInfo(e));
			return false;
		}
	}

	/**
	 * 启动管线主流程:加载 plugins/exection 目录下全部插件 jar(三阶段安全并行管线,
	 * lib 已在此之前串行加载完成):
	 * <p>
	 * Phase A 主线程顺序预登记:读 ACT 清单 + 建独立 ExectionLoader,不加载类,
	 * 保证目录顺序确定;Phase B 线程池并行 loadClass(纯内存、无构造器副作用,
	 * 可安全并行);Phase C 主线程串行实例化 + 注册 + 日志,构造器副作用顺序与旧版完全一致。
	 * <p>
	 * 失败任务先按目录顺序单线程重试一轮(复原旧版"先到先得"的跨插件依赖语义);
	 * 仍失败的插件执行 unloadFailed,释放 jar 句柄与类加载器(避免 Windows 文件锁死)。
	 */
	static void loadExec() {
		// 三阶段安全并行管线(lib已串行加载完成):
		// A) 主线程顺序预登记:读ACT/打开ExectionLoader,不加载类(保证目录顺序确定)
		// B) 线程池并行loadClass:纯内存、无构造器副作用,可安全并行
		// C) 主线程串行实例化+注册+日志:构造器副作用顺序与旧版完全一致
		File dir = new File(Pathes.execDir);
		if (!dir.exists())
			dir.mkdirs();
		ArrayList<String> paths = ToolPack.getAllFilesPath(dir, true);
		ArrayList<Exection> tasks = new ArrayList<Exection>();
		for (String p : paths) {
			// 修复:扩展名大小写不敏感(Locale.ROOT),Linux上.JAR插件不再被忽略
			if (p.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
				Exection ex = preRegisterExec(p);
				if (ex != null)
					tasks.add(ex);
			}
		}

		if (tasks.size() > 1 && MAX_LOAD_THREADS > 1) {
			ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_LOAD_THREADS, tasks.size()));
			try {
				ArrayList<Future<?>> futures = new ArrayList<Future<?>>();
				for (final Exection ex : tasks)
					futures.add(pool.submit(new Runnable() {
						public void run() {
							ex.parallelLoadClasses();
						}
					}));
				for (Future<?> f : futures)
					try {
						f.get();
					} catch (Exception e) {
					}
			} finally {
				pool.shutdown();
			}
		} else {
			for (Exection ex : tasks)
				ex.parallelLoadClasses();
		}

		// 失败任务单线程重试一轮:复原旧版"目录顺序先到先得"的跨插件依赖语义
		for (Exection ex : tasks)
			if (ex.hasLoadError())
				ex.parallelLoadClasses();

		// Phase C: 串行实例化+注册+日志(顺序与旧版一致)
		for (Exection ex : tasks) {
			if (ex.hasLoadError()) {
				SairCons.println(FCM.Error_Color, ex.getPath() + " -> load fail : " + errorInfo(ex.getLoadError()));
				// 修复:加载失败的插件同样卸载,释放jar句柄与类加载器(旧行为残留导致Windows文件锁死)
				unloadFailed(ex);
				continue;
			}
			ex.serialInstantiate();
			if (ex.hasLoadError()) {
				SairCons.println(FCM.Error_Color, ex.getPath() + " -> load fail : " + errorInfo(ex.getLoadError()));
				unloadFailed(ex);
			}
		}
	}

	/**
	 * 卸载加载失败的插件(幂等):dispose 类加载器并清理登记,卸载失败仅记日志不抛出。
	 *
	 * @param ex 加载失败的 Exection(内部携带 jar 路径与登记信息)
	 */
	private static void unloadFailed(Exection ex) {
		try {
			ex.unLoadJar();
		} catch (Exception un) {
			SairCons.println(FCM.Error_Color, ex.getPath() + " -> unload fail : " + un);
		}
	}

	/**
	 * Phase A 预登记:读 jar 的 ACT 清单(每个 jar 只打开一次)+ 建独立
	 * ExectionLoader,返回未实例化的 Exection;清单缺失/为空/解析失败均
	 * 红字提示并返回 null(该插件被从任务列表中剔除)。
	 *
	 * @param path 插件 jar 本地路径
	 * @return 预登记成功的 Exection(尚未加载类);失败返回 null
	 */
	private static Exection preRegisterExec(String path) {
		JarFile jar = null;
		try {
			jar = new JarFile(path);
			Manifest mf = jar.getManifest();
			Attributes ab = mf == null ? null : mf.getMainAttributes();
			String infomations = ab == null ? null : ab.getValue("ACT");
			if (infomations == null) {
				SairCons.println(FCM.Error_Color, path + " -> ACT_infomations notFound!");
				return null;
			}
			String[] sp_ed = infomations.split(";");
			ArrayList<String> localList = new ArrayList<String>();
			for (String clazzName : sp_ed)
				if (clazzName != null && !"".equals(clazzName) && clazzName.length() > 0)
					localList.add(clazzName);
			if (localList.size() == 0) {
				SairCons.println(FCM.Error_Color, path + " -> ACT_infomations notFound!");
				return null;
			}
			String[] classNames = localList.toArray(new String[localList.size()]);
			return Exection.preRegister(classNames, path);
		} catch (Exception e) {
			SairCons.println(FCM.Error_Color, path + " -> ACT_infomations or ClassInfo is Error!");
			return null;
		} finally {
			if (jar != null)
				try {
					jar.close();
				} catch (IOException e) {
				}
		}
	}

	/**
	 * 串行加载 plugins/lib 下全部依赖库 jar(全局共享加载器,是插件父链,必须串行)。
	 * 单个 jar 失败不中断(红字提示),最后给出旧 bootlib/modlib 目录的迁移提示。
	 */
	static void loadLib() {
		// lib必须串行加载(全局共享加载器,是插件父链)
		File path_File = new File(Pathes.libDir);
		if (!path_File.exists())
			path_File.mkdirs();
		ArrayList<String> paths = ToolPack.getAllFilesPath(path_File, true);
		for (String p : paths) {
			if (p.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
				toMod(p);
		}
		// 迁移检测:旧目录还有JAR时给出红字提示,防止lib为空导致插件批量加载失败
		printLibMigrateHint();
	}

	/**
	 * 迁移检测提示:旧目录 plugins/bootlib 与 plugins/modlib 仍存有 JAR 时,
	 * 红字提示用户移入 plugins/lib(防止 lib 为空导致插件批量加载失败);无遗留则静默。
	 */
	private static void printLibMigrateHint() {
		File bootOld = new File(Pathes.pluginsDir + "bootlib" + File.separator);
		File modOld = new File(Pathes.pluginsDir + "modlib" + File.separator);
		int bootCnt = countJars(bootOld);
		int modCnt = countJars(modOld);
		if (bootCnt + modCnt <= 0)
			return;
		SairCons.println(FCM.Error_Color, Pathes.printSplit);
		SairCons.println(FCM.Error_Color, "警告: 检测到旧依赖目录仍存有JAR(bootlib/modlib 已合并为 lib):");
		SairCons.println(FCM.Error_Color, "  plugins/bootlib : " + bootCnt + " 个JAR");
		SairCons.println(FCM.Error_Color, "  plugins/modlib  : " + modCnt + " 个JAR");
		SairCons.println(FCM.Error_Color, "请将这两个目录内的全部JAR移入 plugins/lib 后重启(旧目录随后可删除)");
		SairCons.println(FCM.Error_Color, Pathes.printSplit);
	}

	/**
	 * 统计目录(非递归)下的 jar 文件个数(扩展名大小写不敏感)。
	 *
	 * @param dir 目标目录;为 null 或不存在时返回 0
	 * @return jar 文件个数
	 */
	private static int countJars(File dir) {
		if (dir == null || !dir.exists())
			return 0;
		File[] fs = dir.listFiles();
		int n = 0;
		if (fs != null)
			for (File f : fs)
				if (f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
					n++;
		return n;
	}

	/**
	 * 拼接异常链信息为 "类名: 消息 &lt;-- 类名: 消息 ..." 形式,最多 3 层,
	 * 便于控制台红字输出直接定位真实异常类型与消息。
	 *
	 * @param e 起始异常(可为 null,返回空串)
	 * @return 人类可读的异常链摘要
	 */
	private static String errorInfo(Throwable e) {
		StringBuilder sb = new StringBuilder();
		int depth = 0;
		for (Throwable t = e; t != null && depth < 3; t = t.getCause(), depth++) {
			if (sb.length() > 0)
				sb.append(" <-- ");
			sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
		}
		return sb.toString();
	}

	/**
	 * 加载单个依赖库 jar:先读 Manifest 的 NAME/DIR 登记到 SairLoader.mainMap
	 * (启动期每个 jar 只打开一次,兼顾性能),再交给 Mod 构造器完成实际挂载;
	 * 单个依赖库失败只红字提示,不中断框架启动/autorun。
	 *
	 * @param path 依赖库 jar 本地路径
	 */
	private static void toMod(String path) {
		String dirs = null;
		String name = null;
		JarFile jar = null;
		try {
			// 性能优化:启动期每个jar只打开一次(原实现读NAME/DIR各开一次、加载器再开一次)
			jar = new JarFile(path);
			Manifest mf = jar.getManifest();
			if (mf != null) {
				Attributes ab = mf.getMainAttributes();
				name = ab.getValue("NAME");
				dirs = ab.getValue("DIR");
			}
		} catch (Exception e) {
		} finally {
			if (jar != null)
				try {
					jar.close();
				} catch (IOException e) {
				}
		}
		if (null != dirs && null != name)
			SairLoader.mainMap.put(name, dirs);
		try {
			new Mod(path);
		} catch (Throwable e) {
			// 修复:单个依赖库加载失败不能中断框架启动/autorun
			SairCons.println(FCM.Error_Color, path + " -> load fail : " + errorInfo(e));
		}

	}

	/**
	 * 获取指定 Activity 所属插件的独立类加载器(ExectionLoader)。
	 * <p>
	 * 配合 SairLoader.setThreadContextLoader 可为插件命令自动安装线程上下文,
	 * 使 ServiceLoader/DriverManager 等 SPI 机制发现 plugins/lib 中的实现。
	 *
	 * @param acti 插件 Activity 实例;为 null 或未登记时返回 null
	 * @return 该插件对应的 SairLoader(实际为 ExectionLoader);找不到返回 null
	 */
	public static SairLoader getExecLoader(Activity acti) {
		if (acti == null)
			return null;
		Exection exec = Libraries.exections.get(acti);
		if (exec == null)
			return null;
		return ExecLoaders.get(exec.getURL());
	}

	/**
	 * 从指定加载器挂载的 jar 中读取资源流:按 jar 挂载顺序逐个查找,返回第一个命中。
	 * <p>
	 * 资源名兼容以 "/" 开头(自动去斜杠);对 loader.jars 映射与单个 JarFile 分别
	 * 加锁,与 SairBaseLoader 的加载路径使用同一套锁,并发安全。
	 *
	 * @param classPathInJar jar 内条目路径(如 "META-INF/services/xxx")
	 * @param loader         目标类加载器
	 * @return 命中条目的输入流(调用方负责关闭);未找到或加载器异常时返回 null
	 */
	public static InputStream getModResStream(String classPathInJar, SairLoader loader) {
		InputStream resURL = null;

		Collection<File> ul = null;
		if (classPathInJar == null)
			return null;
		if (classPathInJar.startsWith("/"))
			classPathInJar = classPathInJar.substring(1);
		try {
			ul = loader.getAllJarFile();
		} catch (SecurityException | IllegalArgumentException e) {
			SairCons.print(FCM.Error_Color, "CLASSLOASDER IS ERROR!!!");
			return null;
		}
		for (File file : ul) {
			try {
				JarFile jf;
				synchronized (loader.jars) {
					jf = loader.jars.get(file);
				}
				if (jf == null)
					continue;
				ZipEntry ze;
				synchronized (jf) {
					ze = jf.getEntry(classPathInJar);
				}
				if (ze != null)
					resURL = jf.getInputStream(ze);
				if (resURL != null)
					return resURL;
			} catch (IOException e) {
			}
		}
		return null;
	}

	/**
	 * 全局 lib 版本:从全局 SairLoader({@link #loader})挂载的 jar 中读取资源流。
	 *
	 * @param classPathInJar jar 内条目路径
	 * @return 命中条目的输入流;未找到返回 null
	 */
	public static InputStream getModResStream(String classPathInJar) {
		return getModResStream(classPathInJar, (SairLoader) loader);
	}

}
