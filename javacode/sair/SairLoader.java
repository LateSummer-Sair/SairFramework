package sair;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;

/**
 * 全局依赖库类加载器。
 * <p>
 * 职责:加载 plugins/lib 目录下全部依赖库 jar,为全框架(含所有插件)提供
 * 共享的第三方库类与资源。
 * <p>
 * 架构角色:类加载器父子链的中间层——父为系统类加载器,自身又是每个
 * ExectionLoader(插件加载器)的父加载器;同时被 Main 安装为全局线程上下文
 * 类加载器,使 ServiceLoader/DriverManager/AudioSystem 等 SPI 机制在
 * 线程上下文扫描时能发现 lib 中 META-INF/services 的实现。
 * <p>
 * 线程安全:
 * <ul>
 * <li>{@link #mainMap} 由 Safe.map() 创建,基础单操作同步(LoaderManager.toMod
 *     在启动主线程写入,插件线程可能通过 {@link #getMOD_MEAT_INFFILE} 读取);</li>
 * <li>挂载的 jars 映射继承自 SairBaseLoader(见其线程安全说明);</li>
 * <li>{@link #dispose()} 先置 volatile dead 标记再关闭全部 jar,与并行加载线程安全配合。</li>
 * </ul>
 * <p>
 * 二进制兼容约束(不可改):
 * <ul>
 * <li>{@link #mainMap}(包内 static final HashMap&lt;String,String&gt;)不可改字段名/类型;</li>
 * <li>{@link #addJarFiles}/{@link #removeJarFiles}/{@link #getAllJarFile}/{@link #dispose}
 *     为 final 公开方法,签名不可改;</li>
 * <li>{@link #getMOD_MEAT_INFFILE} 的旧拼写(MEAT)是历史遗留公开 API,不可改名;</li>
 * <li>{@link #setThreadContextLoader}/{@link #runWithThreadContext} 为 SPI 基础设施,签名不可改。</li>
 * </ul>
 */
public class SairLoader extends SairBaseLoader {

	/**
	 * 依赖库登记表:jar 的 Manifest NAME -> DIR(包内共享:LoaderManager.toMod 写入,
	 * getMOD_MEAT_INFFILE 读取)。Safe.map() 保证基础单操作同步;不可改字段名/类型
	 */
	static final HashMap<String, String> mainMap = Safe.map();

	/**
	 * 构造器:显式指定父加载器(当前框架未使用,保留以兼容未来扩展)。
	 *
	 * @param p 父类加载器
	 */
	protected SairLoader(ClassLoader p) {
		super(p);
	}

	/**
	 * 构造器:父默认为系统类加载器(LoaderManager.loader 即以此创建)。
	 */
	protected SairLoader() {
		super(ClassLoader.getSystemClassLoader());
	}

	/**
	 * 查询依赖库 jar 的登记目录(Manifest 的 DIR)。
	 * <p>
	 * 注意:方法名中的 "MEAT" 为历史拼写错误,属公开 API,不可改名。
	 *
	 * @param name jar 的 Manifest NAME 值
	 * @return 对应的 DIR 登记值;未登记返回 null
	 */
	public static String getMOD_MEAT_INFFILE(String name) {
		return mainMap.get(name);
	}

	/**
	 * 批量挂载 jar(逐个调用基类 addJarFile;null 元素与空数组安全忽略)。
	 *
	 * @param paths 目标 jar 文件列表
	 * @throws IOException 任一文件存在但非 .jar 时抛出
	 */
	public final void addJarFiles(File... paths) throws IOException {
		if (paths == null || paths.length == 0)
			return;

		for (File p : paths)
			if (p != null)
				super.addJarFile(p);

	}

	/**
	 * 批量卸载 jar:逐个从映射移除并关闭 JarFile,释放文件句柄(热卸载核心)。
	 *
	 * @param urls 要卸载的 jar 文件列表;null 元素与空数组安全忽略
	 */
	public final void removeJarFiles(File... urls) {
		if (urls == null || urls.length == 0)
			return;
		for (File u : urls)
			if (u != null)
				super.removeJarURL(u);
	}

	/**
	 * 获取当前挂载 jar 的完整列表(快照副本,遍历期间不受并发卸载影响)。
	 *
	 * @return 已挂载 jar 文件集合(ArrayList 快照)
	 */
	public final Collection<File> getAllJarFile() {
		return new ArrayList<File>(snapshotJarFiles());
	}

	/**
	 * 卸载本加载器:先置 dead 标记(运行中线程惰性加载类得到明确"已卸载"错误
	 * 而非未定义行为),再快照并关闭全部 jar,释放所有文件句柄。
	 * <p>
	 * 卸载后本实例不可继续使用;final 公开方法,签名不可改(热卸载契约)。
	 */
	public final void dispose() {
		// 修复:先置死亡标记,卸载后运行中线程惰性加载类得到明确"已卸载"错误而非未定义行为
		dead = true;
		Set<File> set = snapshotJarFiles();
		if (set.size() > 0)
			removeJarFiles(set.toArray(new File[set.size()]));
	}

	/**
	 * 将当前线程的上下文类加载器设置为指定加载器,并返回旧值。
	 * <p>
	 * ServiceLoader、DriverManager、AudioSystem 等 SPI 机制通过线程上下文
	 * 类加载器查找 META-INF/services 配置,设置后即可从 plugins/lib 中的
	 * JAR 里发现 SQL 驱动/编解码器等实现。Main.firstLoad 会在 EDT 启动前
	 * 把全局 loader 安装为默认上下文(子线程自动继承)。
	 *
	 * @param loader 要安装的类加载器(可为 null,表示引导类加载器)
	 * @return 设置前的旧上下文类加载器(用于事后恢复)
	 */
	public static ClassLoader setThreadContextLoader(ClassLoader loader) {
		Thread t = Thread.currentThread();
		ClassLoader old = t.getContextClassLoader();
		t.setContextClassLoader(loader);
		return old;
	}

	/**
	 * 在指定加载器的线程上下文中执行任务,执行完毕(含异常)自动恢复旧上下文。
	 * <p>
	 * 典型用途:插件命令在工作线程中运行时,临时把上下文切到该插件的
	 * ExectionLoader,使 SPI/资源查找优先命中插件自身与 lib。
	 *
	 * @param loader 目标类加载器
	 * @param task   要执行的任务
	 */
	public static void runWithThreadContext(SairLoader loader, Runnable task) {
		ClassLoader old = setThreadContextLoader(loader);
		try {
			task.run();
		} finally {
			setThreadContextLoader(old);
		}
	}

}
