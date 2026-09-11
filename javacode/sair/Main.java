package sair;

import java.io.File;
import java.io.IOException;

import sair.sys.IRRunnable;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

/**
 * SairFramework 主入口类(进程引导器)。
 * <p>
 * 职责:编排框架启动流程——安装全局线程上下文类加载器(SPI 兼容)、初始化控制台窗口、
 * 加载依赖库(plugins/lib)与插件(plugins/exection)、执行 autorun.ir 与外置 ir 脚本。
 * <p>
 * 架构角色:插件生命周期的起点。本类由系统类加载器加载,负责在任何 SPI 机制
 * 首次触碰之前完成线程上下文类加载器安装,并把后续依赖库/插件加载交给
 * LoaderManager 中的全局 SairLoader 与每插件独立的 ExectionLoader
 * (框架已彻底弃用 EDT 调度,启动流程不依赖 EDT)。
 * <p>
 * 线程安全:启动流程全部在 main 线程执行(框架已彻底弃用 EDT 调度,启动流程不依赖
 * EDT);静态字段 {@link #isLoaded} 只在主线程读写,首次启动流程走完后置为 true
 * (启动中途异常退出时保持 false,二次入口将重试初始化),保证 {@link #toTest} 等
 * 二次入口不会重复初始化。真正的并发发生在 LoaderManager 的插件加载线程池中
 * (见 LoaderManager 的线程安全说明)。
 * <p>
 * 二进制兼容约束(旧 0.5.3 插件/外部脚本依赖,不可改):
 * <ul>
 * <li>{@link #Version} 字段名、类型(String)、final 修饰均不可改,脚本/插件以此判断框架版本;</li>
 * <li>{@link #main(String[])} 与 {@link #toTest(Activity, String, String)} 方法签名不可改;</li>
 * <li>类名 Main、包名 sair 不可改。</li>
 * </ul>
 */
public final class Main {

	/**
	 * 框架版本号(公开常量,外部脚本/插件依赖;字段名/类型/final 均不可改)
	 */
	public static final String Version = "0.6.1";

	/**
	 * 首次加载完成标记:main 线程独占读写,true 表示启动流程已执行过(无论成败),
	 * 避免 toTest 等二次入口重复初始化
	 */
	private static boolean isLoaded = false;

	/**
	 * JVM 入口:直接委托 {@link #main0(String[])}(保留两层结构以便二次入口复用启动逻辑)。
	 *
	 * @param args 命令行参数,原样透传给 {@link #autoRun(String[])} 用于拼接外置 ir 脚本路径
	 */
	public static void main(String[] args) {
		main0(args);
	}

	/**
	 * 一次性启动委托:仅首次调用时执行 {@link #firstLoad(String[])},后续调用为空操作。
	 * <p>
	 * 捕获一切 Throwable(含 Error):任何启动期错误都不能让框架静默死亡,
	 * 错误以红色打印并输出堆栈到 stderr 便于排查。
	 *
	 * @param args 命令行参数(透传给 firstLoad/autoRun)
	 */
	private static void main0(String[] args) {
		if (!isLoaded)
			try {
				firstLoad(args);
			} catch (Throwable e) {
				// 修复:任何启动期错误都不能让框架静默死亡(包括Error);堆栈打印到stderr便于排查
				SairCons.println(FCM.Error_Color, String.valueOf(e));
				e.printStackTrace();
			}
	}

	/**
	 * 首次启动主流程(仅调用一次,由 main0 保证)。
	 * <p>
	 * 顺序:①安装全局线程上下文类加载器(必须在任何 SPI 首次触碰之前,见方法内注释);
	 * ②显示控制台窗口;③检查 sairjar: 协议可用性(不可用则红字降级警告);
	 * ④打印框架信息;⑤加载依赖库与插件——lib 串行 + exection 三阶段管线(A/B/C),
	 * load 失败不阻断后续 autorun;⑥播放一次渐变扫光特效;⑦短暂等待(10ms)后执行
	 * autorun.ir 与外置 ir 脚本;⑧置 {@link #isLoaded} 为 true。
	 *
	 * @param args 命令行参数
	 * @throws Exception 内部失败均被捕获打印,不向外抛;保留签名以便调用方统一处理
	 */
	private static void firstLoad(String[] args) throws Exception {
		// SPI兼容修复:将全局SairLoader设为默认线程上下文类加载器(持久生效,子线程自动继承)。
		// 旧版bootlib由系统类加载器加载,JDK的SPI机制(如AudioSystem的JavaSound服务发现、
		// JLayer的AudioDeviceFactory注册表)首次触碰时会以当时的线程上下文扫描
		// META-INF/services并缓存结果;现在依赖库都在lib(自定义加载器)中,必须在任何
		// SPI首次触碰之前把线程上下文指向全局SairLoader,否则
		// layer.jar(mp3spi)等解码器永远无法被发现。
		// ① 安装全局线程上下文类加载器(见上方 SPI 兼容修复说明)
		SairLoader.setThreadContextLoader(LoaderManager.loader);
		// ② 显示控制台窗口
		ConsFrame.showFrame();
		// ③ 检查 sairjar: 协议可用性(不可用时仅红字降级警告,见下方修复注释)
		if (!SairJarHandler.active()) {
			// 修复:sairjar协议不可用(URL工厂被第三方占用)时红字提示,jar句柄释放特性降级为jar:
			SairCons.println(FCM.Error_Color, "警告: sairjar协议注册失败(URLStreamHandlerFactory已被占用),插件热卸载句柄释放特性已降级");
		}
		// ④ 打印框架信息(版本/Java环境)
		SairCons.printTiInfos();
		// ⑤ 加载依赖库与插件:lib 串行 + exection 三阶段管线(A/B/C)
		try {
			load();
		} catch (Throwable e) {
			// 修复:插件/依赖库加载失败不能阻断autorun执行
			SairCons.println(FCM.Error_Color, "load fail : " + e);
		}
		// ⑥ 载入特效:渐变扫光(每进程一次)
		ConsFrame.playScanline();

		try {
			// ⑦ 短暂等待后执行自动运行脚本(autorun.ir优先,外置ir随后)
			Thread.sleep(10L);
			autoRun(args);
		} catch (IOException e) {
			SairCons.println(FCM.Error_Color, "createNewIrFile Error!");
		}
		// ⑧ 标记首次启动已执行(此前异常上抛时不会走到此处,isLoaded保持false,二次入口将重试)
		isLoaded = true;
	}

	/**
	 * 依赖库与插件的两级加载:先串行加载 plugins/lib(全局共享父链),再走
	 * 三阶段管线加载 plugins/exection(并行 loadClass、串行实例化注册)。
	 * 失败只红字提示,不抛出(调用方捕获后继续 autorun)。
	 */
	private static void load() {
		// 第一步:串行加载 plugins/lib 依赖库(全局共享父链,单个失败不中断)
		LoaderManager.loadLib();
		// 第二步:三阶段管线(A/B/C)加载 plugins/exection 插件,失败插件卸载
		LoaderManager.loadExec();
	}

	/**
	 * 执行自动运行脚本:args 为 null 时直接返回;否则 autorun.ir 永远优先执行——
	 * 无外置 ir 时行为与旧版一致(后台异步执行,不等待);有外置 ir 时 autorun
	 * 先执行并等待其完整结束,外置 ir 在其后执行。
	 *
	 * @param args 命令行参数:为 null 时直接返回(不执行任何脚本);为空数组时
	 *             仅执行 autorun.ir;非空时全部参数被拼接为外置 ir 脚本路径
	 * @throws IOException autorun.ir 创建失败时抛出(调用方打印 createNewIrFile Error!)
	 */
	private static void autoRun(String[] args) throws IOException {
		// 无命令行参数:不执行任何脚本
		if (args == null)
			return;
		// autorun.ir永远优先执行(无外置ir时行为与旧版一致:后台执行)
		// 构造 autorun.ir 路径(控制台数据目录下);不存在则创建空文件
		String autorunPath = ConsFrame.fa.getDataDir() + "autorun.ir";
		File autorunFile = new File(autorunPath);
		if (!autorunFile.exists())
			autorunFile.createNewFile();
		// 路径拆包(相对路径补全为绝对路径,并取出引号内路径段)
		autorunPath = ToolPack.pathRepack(autorunPath)[0];
		// 无外置 ir:仅后台执行 autorun.ir 后返回
		if (args.length <= 0) {
			SairCons.runner(false, "/ir \"" + autorunPath + "\"");
			return;
		}
		// 有外置ir:autorun先执行并等待其完整结束,外置ir在其后执行
		SairCons.runner(false, "/ir \"" + autorunPath + "\"");
		// 等待 autorun.ir 结束(双超时保护),保证先序关系
		waitIrFinish(autorunPath);
		// 全部命令行参数拼接为一个路径(空格分隔)作为外置 ir 脚本路径
		String path = argsFactory(args);
		path = ToolPack.pathRepack(path)[0];
		SairCons.runner(false, "/ir \"" + path + "\"");
	}

	/**
	 * 等待指定 ir 脚本执行完成:先等其注册进 irpool(命令投递/登记存在窗口期,最多 10s),
	 * 再等其从 irpool 移除(执行结束,最多 30s)。
	 * <p>
	 * 两个循环均有超时保护:IR 线程异常退出/永不结束时不再永久挂起启动流程;
	 * 第二循环 30s 超时后红字提示并继续启动。
	 *
	 * @param path ir 脚本路径(irpool 的键)
	 */
	private static void waitIrFinish(String path) {
		long t0 = System.currentTimeMillis();
		// 第一循环:等待脚本登记进 irpool(最多10s);期间线程被中断则放弃等待
		while (!IRRunnable.irpool.containsKey(path) && System.currentTimeMillis() - t0 < 10000) {
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				return;
			}
		}
		// 修复:第二循环加总超时,IR线程异常退出时不再永久挂起启动流程
		long t1 = System.currentTimeMillis();
		// 第二循环:等待脚本从 irpool 移除(执行结束);30s 总超时后红字提示并继续启动
		while (IRRunnable.irpool.containsKey(path)) {
			if (System.currentTimeMillis() - t1 > 30000) {
				SairCons.println(FCM.Error_Color, "等待IR脚本[" + path + "]结束超时(30s),继续启动");
				return;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	/**
	 * 将命令行参数拼接为一个以空格分隔的路径字符串(局部拼接用 StringBuilder,
	 * 单线程无同步需求)。
	 *
	 * @param args 非空参数数组(调用方已保证 length > 0)
	 * @return 各参数以单个空格连接后的字符串(尾部空格已移除)
	 */
	private static String argsFactory(String[] args) {
		// 修复:局部拼接用StringBuilder(单线程无同步需求)
		StringBuilder sbf = new StringBuilder();
		for (String str : args)
			sbf.append(str).append(' ');
		sbf.deleteCharAt(sbf.length() - 1);
		return sbf.toString();
	}

	/**
	 * 测试/插件二次入口:确保框架已初始化(未初始化则补跑一次启动流程),随后
	 * 调用指定 Activity 的指定方法。
	 *
	 * @param testActivity 目标 Activity 实例
	 * @param funcName     要调用的方法名
	 * @param args         方法参数字符串
	 * @return 方法执行结果(由 SairCons.toActiRun 返回,类型取决于被调方法)
	 */
	public static Object toTest(Activity testActivity, String funcName, String args) {
		main0(null);
		return SairCons.toActiRun(testActivity, funcName, args);
	}

}
