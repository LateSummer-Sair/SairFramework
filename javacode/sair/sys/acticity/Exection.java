package sair.sys.acticity;

import java.io.IOException;
import java.util.ArrayList;

import sair.FCM;
import sair.LoaderManager;
import sair.Pathes;
import sair.SairLoader;
import sair.sys.Libraries;
import sair.sys.SairCons;
import sair.user.Activity;

/**
 * 插件执行器(Activity 所在 jar 的生命周期管理者):装载 jar → 实例化并注册
 * Activity → 卸载时清理注册并释放类加载器。
 * <p>
 * 架构角色:插件生命周期环节——与 Mod(库模块)同属 Acti 体系,但 Exection 的产物
 * 是命令组件(Activity),由 Libraries 三张表共同登记;SairCons 按组件名找到
 * Activity 后经 OderFact 执行。并行加载管线(preRegister/parallelLoadClasses/
 * serialInstantiate)把"开 jar、loadClass、实例化"拆成三个阶段以支持并发装载。
 * <p>
 * 线程安全说明:常规装载路径(公开构造器)在主线程串行完成;并行加载管线中
 * Phase A(仅开 jar/登记 loader)与 Phase B(仅 loadClass,纯内存、无构造器副作用)
 * 可并行,Phase C 必须主线程串行实例化/注册/日志,保证构造器副作用顺序与旧版一致;
 * {@link #loadError} 只由管线阶段写入、查询阶段读取。卸载(unLoadJar)对
 * Libraries.activities 的别名清理在 synchronized 块内按实例值遍历完成。
 * <p>
 * 二进制兼容约束:公开构造器 {@link #Exection(String[], String)} 与公开方法
 * preRegister/parallelLoadClasses/serialInstantiate/hasLoadError/getLoadError/
 * getClassNames/unLoadJar 的签名不可变更;classNames/loader 等私有字段可自由调整。
 */
public class Exection extends Acti {

	/**
	 * jar 中声明的 Activity 主类名列表(来自 MF 清单 act 条目)。
	 */
	private String[] classNames;

	/**
	 * 本 Exection 已实例化的 Activity 集合(卸载时遍历清理)。
	 */
	private ArrayList<Activity> actiList = new ArrayList<Activity>();

	/**
	 * 插件专属类加载器(loadJar 时按本 jar 的 URL 从 LoaderManager 取得)。
	 */
	private SairLoader loader;

	/**
	 * 并行加载管线 Phase B 预加载的 Class 对象缓存。
	 */
	private Class<?>[] loadedClasses;

	/**
	 * 装载/实例化阶段捕获的首个异常(管线状态查询用)。
	 */
	private Throwable loadError;

	/**
	 * 常规装载入口:打开 jar → 登记类加载器 → 立即实例化并注册全部 Activity
	 * (单步完成,与旧版行为一致)。
	 *
	 * @param classNames Activity 主类名数组
	 * @param path       jar 路径
	 * @throws ClassNotFoundException    主类不存在
	 * @throws InstantiationException    实例化失败
	 * @throws IllegalAccessException    无访问权限
	 * @throws IOException               jar 打开失败
	 */
	public Exection(String[] classNames, String path)
			throws ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
		// 步骤1:解析path/exists/url元数据(Acti构造器)
		super(path);
		// 步骤2:打开jar并登记专属类加载器
		this.loadJar();
		// 步骤3:保存主类名并立即实例化注册全部Activity(单步完成,与旧版行为一致)
		this.classNames = classNames;
		this.initExec();
	}

	/**
	 * 并行加载管线 Phase A(框架内部使用):仅打开 jar 与登记 loader,不加载类、不实例化,
	 * 因此多个插件可并行登记;后续再经 {@link #parallelLoadClasses} 与
	 * {@link #serialInstantiate} 完成装载。
	 */
	public static Exection preRegister(String[] classNames, String path) throws IOException {
		Exection ex = new Exection(classNames, path, true);
		return ex;
	}

	/**
	 * 内部构造:skipInit 仅由 {@link #preRegister} 使用(恒为 true),跳过实例化;
	 * 保留该参数是为了避免与公开构造器签名冲突(二进制兼容)。
	 */
	private Exection(String[] classNames, String path, boolean skipInit) throws IOException {
		super(path);
		this.loadJar();
		this.classNames = classNames;
		// skipInit仅由preRegister使用(恒为true);保留参数避免与公开构造器签名冲突
	}

	/**
	 * 打开插件 jar 并登记类加载器:jar 存在时经 LoaderManager 装载,
	 * 并按本 jar 的 URL 取得专属 SairLoader。
	 */
	private void loadJar() throws IOException {
		// 步骤1:jar存在时经LoaderManager装载,并按本jar的URL取得专属SairLoader
		if (this.exists == true) {
			LoaderManager.loadExecJar(this.path);
			loader = LoaderManager.ExecLoaders.get(this.getURL());
			// 修复:登记异常/残留状态导致取不到loader时立即以IOException失败(而不是之后NPE)
			if (loader == null)
				throw new IOException("ExecLoader is null for: " + this.path);
		}
	}

	/**
	 * 串行实例化并注册全部 Activity(常规构造器路径):按类名逐个 loadMain,
	 * 分配唯一组件名(重复名抛异常时仅记录日志)并写入 Libraries 两表
	 * (activities/exections),登记到本实例的 actiList,输出装载日志。
	 */
	private void initExec() throws ClassNotFoundException, InstantiationException, IllegalAccessException {

		// 步骤1:按类名逐个实例化并注册
		if (classNames != null) {

			for (String className : classNames) {
				// 步骤2:经LoaderManager实例化Activity主类
				Activity result = LoaderManager.loadMain(className, loader);
				// 步骤3:分配唯一组件名(重复名抛异常时仅记录日志,不中断装载)
				String name = Libraries.setActivityName(this.getPath());
				try {
					result.setName(name);
				} catch (Exception e) {
					SairCons.println(FCM.Error_Color, e.getMessage());
				}
				// 步骤4:写入Libraries两表(activities/exections)并登记actiList
				Libraries.activities.put(name, result);
				Libraries.exections.put(result, this);
				actiList.add(result);
				// 步骤5:输出装载日志
				SairCons.println(FCM.loadExection_Color, "loaded EXECTIONS : " + this.getPath() + " --> " + className);
			}

		}
	}

	/**
	 * 并行加载管线 Phase B(框架内部使用):仅 loadClass(纯内存、无构造器副作用),
	 * 可安全并行调用;失败时记录到 {@link #loadError} 而非抛出,
	 * 由 {@link #serialInstantiate} 检查后跳过。
	 */
	public void parallelLoadClasses() {
		// 步骤1:分配Class缓存并清空错误状态
		loadedClasses = new Class<?>[classNames.length];
		loadError = null;
		try {
			// 步骤2:仅loadClass(纯内存、无构造器副作用),可安全并行调用
			for (int i = 0; i < classNames.length; i++)
				loadedClasses[i] = loader.loadClass(classNames[i]);
		} catch (Throwable e) {
			// 步骤3:失败记录到loadError而非抛出,由serialInstantiate检查后跳过
			loadError = e;
		}
	}

	/**
	 * 并行加载管线 Phase C(框架内部使用):主线程串行实例化+注册+日志,
	 * 构造器副作用顺序与旧版一致;{@link #loadError} 非空时直接跳过。
	 */
	public void serialInstantiate() {
		// 步骤1:Phase B捕获过异常时直接跳过
		if (loadError != null)
			return;
		try {
			// 步骤2:主线程串行实例化+注册+日志,构造器副作用顺序与旧版一致
			for (int i = 0; i < classNames.length; i++) {
				// 步骤3:实例化主类
				Activity result = LoaderManager.loadMain(classNames[i], loader);
				// 步骤4:分配唯一组件名(重复名抛异常时仅记录日志,不中断装载)
				String name = Libraries.setActivityName(this.getPath());
				try {
					result.setName(name);
				} catch (Exception e) {
					SairCons.println(FCM.Error_Color, e.getMessage());
				}
				// 步骤5:写入Libraries两表(activities/exections)并登记actiList
				Libraries.activities.put(name, result);
				Libraries.exections.put(result, this);
				actiList.add(result);
				SairCons.println(FCM.loadExection_Color, "loaded EXECTIONS : " + this.getPath() + " --> " + classNames[i]);
			}
		} catch (Throwable e) {
			// 步骤6:任何异常记录到loadError,不向上抛出
			loadError = e;
		}
	}

	/**
	 * 并行加载管线状态查询(框架内部使用):装载是否已失败。
	 *
	 * @return true 表示 Phase B/C 捕获过异常
	 */
	public boolean hasLoadError() {
		return loadError != null;
	}

	/**
	 * 读取装载阶段捕获的首个异常(无异常时为 null)。
	 */
	public Throwable getLoadError() {
		return loadError;
	}

	/**
	 * 读取本 jar 声明的 Activity 主类名列表。
	 */
	public String[] getClassNames() {
		return classNames;
	}

	/**
	 * 卸载本插件(框架级资源释放与调度的唯一入口):
	 * 对 actiList 中每个 Activity——在 synchronized(Libraries.activities)
	 * 内按实例值移除全部注册名(含插件自行 put 的别名,旧实现只删主名导致别名残留),
	 * 随后移除 exections 映射,并<b>自动调用一次 exit()(插件退出前置钩子)与 close()</b>;
	 * exit()/close() 抛出的任何异常只红字记录、不中断清理(插件代码无权破坏框架级释放);
	 * 全部完成后 dispose 类加载器、清理 ExecLoaders/execJarPathSet(释放 jar 句柄),
	 * 且该收尾<b>无论上面是否异常都必定执行</b>(避免半卸载导致jar句柄永久泄漏)。
	 *
	 * @throws Exception loader.dispose 或清理过程中的异常
	 */
	public void unLoadJar() throws Exception {
		// 步骤1:打印卸载分隔线
		SairCons.println(FCM.Error_Color, Pathes.printSplit);
		// 步骤2:逐Activity清理注册与执行卸载前置钩子
		for (Activity acti : actiList) {
			String name = acti.getName();
			// 步骤3:在synchronized(Libraries.activities)内按实例值移除全部注册名
			// (含插件自行put的别名,旧实现只删主名导致别名残留)
			synchronized (Libraries.activities) {
				java.util.Iterator<String> it = Libraries.activities.keySet().iterator();
				while (it.hasNext()) {
					String k = it.next();
					if (Libraries.activities.get(k) == acti)
						it.remove();
				}
			}
			// 步骤4:移除exections映射并自动调用一次exit()(插件退出前置钩子)与close()
			Exection exec = Libraries.exections.remove(acti);
			if (acti != null) {
				SairCons.println(FCM.Error_Color, "unload EXECTIONS-ACTI : " + name);
				// 修复:exit()是插件实现的方法,抛异常绝不允许中断框架清理——红字记录后继续
				try {
					acti.exit();
					acti.close();
				} catch (Throwable t) {
					SairCons.println(FCM.Error_Color, "unload EXECTIONS-EXIT-FAIL : " + name + " -> " + t);
				}
			}
			if (exec != null)
				SairCons.println(FCM.Error_Color, "unload EXECTIONS-EXEC : " + name);
		}
		// 步骤5:收尾释放类加载器与LoaderManager缓存
		// 修复:无论上面exit()/close()是否抛异常,该收尾都必须执行(否则半卸载残留+jar句柄泄漏)
		this.unLoadJar0();
	}

	/**
	 * 释放资源:dispose 类加载器(加载失败路径 loader 可能为 null,先判空避免 NPE),
	 * 并清理 LoaderManager 的 ExecLoaders/execJarPathSet 缓存——避免类加载器残留
	 * 导致 jar 文件被占用、无法删除/覆盖。
	 */
	private void unLoadJar0() throws Exception {
		// 步骤1:加载失败路径loader可能为null,先判空避免NPE
		if (loader != null)
			loader.dispose();
		// 步骤2:插件卸载后清理ExecLoaders缓存,避免类加载器残留导致jar文件被占用、无法删除/覆盖
		LoaderManager.ExecLoaders.remove(this.getURL());
		// 修复:按规范化路径移除登记(与loadExecJar的登记键一致,任何路径写法都能清理干净)
		LoaderManager.execJarPathSet.remove(LoaderManager.canonicalPath(this.path));
	}
}
