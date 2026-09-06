package sair.sys;

import java.awt.Color;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import sair.FCM;
import sair.LoaderManager;
import sair.Pathes;
import sair.Safe;
import sair.SairLoader;
import sair.sys.gui.ConsFrame;
import sair.sys.tools.Spliter;
import sair.sys.tools.ToolPack;
import sair.user.Activity;
import sair.user.PrintRunnable;
import sair.user.SpliterSPI;

/**
 * 命令总入口(命令解释器):接收整条命令字符串,经 Spliter 解析出目标组件与函数后分发执行。
 * <p>
 * 架构角色:SFW 命令解释链路的总闸门——IRRunnable 的脚本行、控制台输入、打印代理嵌套命令、
 * '...' 内嵌命令最终都汇聚到 {@link #runner(boolean, String)};解析结果交给
 * {@link OderFact#runner(Activity, Spliter)} 完成内置命令分发或组件方法调用。
 * <p>
 * 线程安全说明:
 * <ul>
 * <li>{@link #printAgos} 为 ConcurrentHashMap,注册/移除/遍历均线程安全;</li>
 * <li>{@link #SpliterSpiManager} 与 {@link #localRunnerHistory_Index} 为 volatile 字段;</li>
 * <li>{@link #runnerDepth} 与 {@link #printAgoDepth} 为 ThreadLocal,嵌套深度按线程独立统计;</li>
 * <li>{@link #localRunnerHistory} 为 Safe.list() 同步列表,超 10000 条的裁剪与追加在同一
 * synchronized(localRunnerHistory) 块内完成,避免与历史上下键读取的 check-then-act 竞态;</li>
 * <li>直印模式(已彻底弃用EDT调度):打印/列表/标题等 Swing 操作均由调用线程直接执行,
 * 控制台文档变更在 ConsFrame 内部锁(printLock)串行化。</li>
 * </ul>
 * 二进制兼容约束:以下公开字段/方法签名已被插件与旧字节码依赖,禁止改名/改类型/改签名——
 * {@link #localRunnerHistory}(ArrayList&lt;String&gt;)、{@link #localRunnerHistory_Index}(int)、
 * {@link #SpliterSpiManager}(SpliterSPI)、{@link #MAX_RUNNER_DEPTH}/{@link #MAX_PRINT_AGO_DEPTH}(int)、
 * {@link #runner(boolean, String)}、{@link #insertPrinto(Integer, Color, String)} 及各 print/println 重载。
 */
public class SairCons {

	/**
	 * 命令历史(线程安全列表,Safe.list() 方法级同步):公开字段,控制台/IR/历史翻阅方共享;
	 * 裁剪与追加必须在同一 synchronized 块内完成(见 runner0)。
	 */
	public final static ArrayList<String> localRunnerHistory = Safe.list();

	/**
	 * 打印代理注册表(线程安全):键为代理标识,值为代理实现;putIfAbsent 保证标识全局唯一。
	 */
	private final static ConcurrentHashMap<String, PrintRunnable> printAgos = new ConcurrentHashMap<String, PrintRunnable>();

	/**
	 * 全局自定义命令解析器(volatile):null 表示使用默认 SystemSpliter;
	 * 由 ToolPack.setSpliter/ToolPack.SpliterChkUninstall 安装/卸载,公开字段供插件直接读取。
	 */
	public static volatile SpliterSPI SpliterSpiManager;

	/**
	 * 历史读取游标(volatile):供上下键翻阅历史;历史被裁剪清空时随 clear 同步归零。
	 */
	public static volatile int localRunnerHistory_Index = 0;

	/**
	 * 注册打印代理:以 pr_id 为键原子登记(putIfAbsent),并同步控制台右侧的代理列表(直印模式:调用线程直接执行)。
	 * <p>并发安全:重复 id 或空参数直接拒绝,不会覆盖已注册代理。
	 *
	 * @param pr_id 代理唯一标识(展示在控制台列表中)
	 * @param pr    打印代理实现
	 * @return 登记成功返回 true;id/实现为 null 或 id 已存在返回 false
	 */
	public final static boolean addPrintRunnable(String pr_id, PrintRunnable pr) {
		if (pr_id == null || pr == null)
			return false;
		if (printAgos.putIfAbsent(pr_id, pr) != null)
			return false;
		ConsFrame.cf.listModel.addElement(pr_id);
		return true;
	}

	/**
	 * 移除指定打印代理:从 ConcurrentHashMap 摘除并同步移除列表项(直印模式)。
	 *
	 * @param pr_id 代理唯一标识
	 * @return 被移除的代理;id 不存在时返回 null
	 */
	public final static PrintRunnable removePrintRunnable(String pr_id) {
		PrintRunnable old = printAgos.remove(pr_id);
		if (old != null)
			ConsFrame.cf.listModel.removeElement(pr_id);
		return old;
	}

	/**
	 * 清空全部打印代理(原子 clear + 列表清空,直印模式)。
	 */
	public final static void removeAllPrintRunnable() {
		printAgos.clear();
		ConsFrame.cf.listModel.removeAllElements();
	}

	/**
	 * 读取控制台默认字体颜色(经 ConsFrame 读取 Swing 配置)。
	 */
	public final static Color getDefaultColor() {
		return ConsFrame.getFontColor();
	}

	/**
	 * 设置控制台默认字体颜色(直印模式:调用线程直接执行)。
	 */
	public final static void setDefaultColor(Color c) {
		ConsFrame.setFontColor(c);
	}

	/**
	 * 删除控制台文本区间:offs 为起始偏移(0 表示从头),len 为长度(null 表示删到末尾)。
	 */
	public final static void dePrint(Integer offs, Integer len) {
		ConsFrame.dePrinto(offs, len);
	}

	/**
	 * 控制台输出统一入口:向 index 处插入 info(颜色 c)。
	 * <p>双路径策略(直印模式,无EDT调度):
	 * <ul>
	 * <li>存在打印代理时,代理在调用线程同步执行({@link #runAgo},保留原有实时语义),
	 *     随后置标题并滚动;</li>
	 * <li>无代理时走 ConsFrame.printo 直接插入(调用线程执行),滚动保持一致。</li>
	 * </ul>
	 *
	 * @param index 插入位置,可为 null(追加)
	 * @param c     文本颜色,可为 null(默认色)
	 * @param info  文本内容
	 */
	public final static void insertPrinto(final Integer index, final Color c, final String info) {
		final boolean hasAgo = printAgos.size() != 0;
		if (hasAgo) {
			// 打印代理在调用线程同步执行(保留原有实时语义)
			runAgo(index, c, info);
			ConsFrame.setTitleInfo("SFW的其他输出模式");
		} else {
			// 打印走批处理(内部攒批后在EDT统一flush,性能优化),滚动合并保持不变
			ConsFrame.printo(index, c, info);
		}
	}

	/**
	 * 打印代理重入深度(线程本地):runAgo 调用期间代理内部再触发 print/println 时深度+1,
	 * 超过 {@link #MAX_PRINT_AGO_DEPTH} 自动跳过,防止无限递归。
	 */
	private static final ThreadLocal<Integer> printAgoDepth = new ThreadLocal<Integer>() {
		@Override
		protected Integer initialValue() {
			return 0;
		}
	};

	/**
	 * 打印代理重入上限(可配置,安全加固):代理内部再调用 print/println 时自动跳过,防止无限递归。
	 * 公开字段:插件可读取/调整该值,但其二进制类型(int)不可改变。
	 */
	public static int MAX_PRINT_AGO_DEPTH = 8;

	/**
	 * 同步遍历全部打印代理并分发本次输出(保留插件对实时语义的依赖)。
	 * <p>防重入:ThreadLocal 深度计数,达到 {@link #MAX_PRINT_AGO_DEPTH} 时打印告警并跳过;
	 * try/finally 保证任何异常路径都恢复深度计数,避免误判后续输出。
	 */
	private static void runAgo(Integer index, Color c, String info) {
		Integer d = printAgoDepth.get();
		if (d >= MAX_PRINT_AGO_DEPTH) {
			System.err.println("[SairCons] 打印代理重入过深,已跳过本次输出");
			return;
		}
		printAgoDepth.set(d + 1);
		try {
			for (String pr_id : printAgos.keySet()) {
				PrintRunnable pr = printAgos.get(pr_id);
				if (pr != null)
					pr.run(index, c, info);
			}
		} finally {
			printAgoDepth.set(printAgoDepth.get() - 1);
		}
	}

	/**
	 * 打印一行(不换行):便捷入口,等价于 {@link #insertPrinto(null, c, info)}。
	 */
	public final static void print(Color c, String info) {
		insertPrinto(null, c, info);
	}

	/**
	 * 打印一行(带换行):在 info 前追加 "\r\n" 后走统一输出入口。
	 */
	public final static void println(Color c, String info) {
		print(c, "\r\n" + info);
	}

	/**
	 * 默认颜色打印(不换行)。
	 */
	public final static void print(String info) {
		print(null, info);
	}

	/**
	 * 默认颜色打印(带换行)。
	 */
	public final static void println(String info) {
		println(null, info);
	}

	/**
	 * 清空控制台:删除全部文本并清空命令历史(localRunnerHistory)。
	 */
	public final static void clear() {
		dePrint(0, null);
		localRunnerHistory.clear();
	}

	/**
	 * 读取控制台当前全部文本(供插件/脚本查询)。
	 */
	public final static String getConsoleText() {
		return ConsFrame.getAllText();
	}

	/**
	 * 读取控制台当前规模:文本长度 + 标签页/组件数量,供容量评估与截断策略参考。
	 */
	public final static int getConsoleSize() {
		return ConsFrame.getPaneSize();
	}

	/**
	 * 空命令判定:null、全空白或纯 "//"(注释行)均视为空,直接跳过执行。
	 */
	private static boolean chkCmdIsNul(String cmd) {
		if (cmd == null)
			return true;
		cmd = cmd.trim();
		return cmd.length() == 0 || "//".equals(cmd);
	}

	/**
	 * 去除前导空白字符(性能优化:手写循环替代正则 replaceAll,命令热路径调用)。
	 */
	private static String ltrim(String s) {
		int i = 0, n = s.length();
		while (i < n && Character.isWhitespace(s.charAt(i)))
			i++;
		return s.substring(i);
	}

	/**
	 * 命令嵌套深度(线程本地):runner 递归调用期间按线程统计;
	 * 达到 {@link #MAX_RUNNER_DEPTH} 时中止递归。
	 */
	private static final ThreadLocal<Integer> runnerDepth = new ThreadLocal<Integer>() {
		@Override
		protected Integer initialValue() {
			return 0;
		}
	};

	/**
	 * 命令嵌套深度上限(可配置):防止 ofunc 等嵌入命令无限递归耗尽栈(安全加固)。
	 * 公开字段:插件可读取/调整该值,但其二进制类型(int)不可改变。
	 */
	public static int MAX_RUNNER_DEPTH = 128;

	/**
	 * 命令执行总入口(可重入):ThreadLocal 统计嵌套深度,超过 {@link #MAX_RUNNER_DEPTH} 中止递归。
	 * <p>IR 脚本行、打印代理内嵌命令、'...' 内嵌执行、ofunc 嵌入命令都会递归经过本方法;
	 * try/finally 保证异常路径也恢复深度计数。
	 *
	 * @param isMark true 时把本条命令记入历史(localRunnerHistory);IR 内部投递传 false
	 * @param cmd    完整命令字符串
	 * @return 命令执行结果(可为 null)
	 */
	public final static Object runner(boolean isMark, String cmd) {
		Integer depth = runnerDepth.get();
		if (depth >= MAX_RUNNER_DEPTH) {
			SairCons.println(FCM.Error_Color, "命令嵌套过深(超过MAX_RUNNER_DEPTH),已中止递归");
			return null;
		}
		runnerDepth.set(depth + 1);
		try {
			return runner0(isMark, cmd);
		} finally {
			runnerDepth.set(runnerDepth.get() - 1);
		}
	}

	/**
	 * 命令执行核心(由 {@link #runner} 在深度检查之后调用):
	 * <ol>
	 * <li>空命令/注释行直接返回;</li>
	 * <li>SPI 卸载命令({@link ToolPack#SpliterChkUninstall})优先处理;</li>
	 * <li>isMark 时同步完成历史裁剪(超 10000 条清空并重置游标)+追加,避免与上下键读取的竞态;</li>
	 * <li>{@link ToolPack#findSplited} 解析命令;解析失败仅回退本次命令,绝不清空全局 SPI 管理器
	 *     (否则并发下会静默摘除其他插件安装的解释器);</li>
	 * <li>按解析出的组件名查找 Activity(空名指向控制台默认 FrameActivity,未注册则报 not found);</li>
	 * <li>为插件线程临时安装 TCCL(execLoader),使 ServiceLoader/DriverManager/AudioSystem 等机制
	 *     能发现 plugins/lib 中的 META-INF/services 实现;当前线程上下文已是目标加载器时跳过
	 *     set/restore(IR 循环连续调用同一插件时的微优化),finally 中恢复原加载器。</li>
	 * </ol>
	 */
	private static Object runner0(boolean isMark, String cmd) {
		/*
		 * if ("jj/at 1+/100".equals(cmd)) System.out.println();
		 */

		if (chkCmdIsNul(cmd))
			return null;

		if (ToolPack.SpliterChkUninstall(cmd))
			return true;

		if (isMark) {
			// 修复:裁剪+添加整体同步,避免与历史上下键读取的check-then-act竞态
			synchronized (localRunnerHistory) {
				if (SairCons.localRunnerHistory.size() > 10000) {
					SairCons.localRunnerHistory.clear();
					SairCons.localRunnerHistory_Index = 0;
				}
				SairCons.localRunnerHistory.add(cmd);
			}
		}

		Spliter sp = ToolPack.findSplited(ltrim(cmd));
		if (sp == null) {
			// 修复:解析失败不再清空全局SPI管理器(并发下会静默摘除其他插件安装的解释器),仅回退本次命令
			SairCons.println(FCM.Error_Color, "Spliter解析错误！本次命令已跳过");
			return null;
		}
		Activity localActivity = null;
		String localName = sp.getExecName();
		if (localName == null)
			return null;
		if ("".equals(localName))
			localActivity = ConsFrame.fa;
		else
			localActivity = Libraries.activities.get(localName);

		if (localActivity != null) {
			// SPI能力:为插件命令自动安装线程上下文类加载器,使ServiceLoader/DriverManager/
			// AudioSystem等机制能发现plugins/lib中的META-INF/services实现(执行完毕自动恢复)
			// 微优化:当前线程上下文已是目标加载器时跳过set/restore(IR循环连续调用同一插件时生效)
			SairLoader execLoader = LoaderManager.getExecLoader(localActivity);
			ClassLoader oldTCCL = null;
			boolean tccChanged = false;
			if (execLoader != null) {
				Thread t = Thread.currentThread();
				ClassLoader cur = t.getContextClassLoader();
				if (cur != execLoader) {
					oldTCCL = cur;
					t.setContextClassLoader(execLoader);
					tccChanged = true;
				}
			}
			try {
				return OderFact.runner(localActivity, sp);
			} finally {
				if (tccChanged)
					Thread.currentThread().setContextClassLoader(oldTCCL);
			}
		} else {
			SairCons.println(FCM.Error_Color, "\"" + localName + "\" is not found");
			return null;
		}
	}

	/**
	 * 调用组件的 main 入口并处理返回值约定:返回 null 视为已处理(成功);
	 * 返回 Boolean.FALSE 时打印帮助信息并回传 false(命令失败语义)。
	 */
	public static Object toActiRun(Activity localActivity, String funcName, String args) {
		Object result = localActivity.main(funcName, args);
		if (result == null)
			return null;
		if ((result instanceof Boolean) && (Boolean) result == false) {
			printHelp(localActivity);
			return false;
		}
		return result;
	}

	/**
	 * 打印组件帮助信息:分隔线 + help() 的每一行(包私有,供 OderFact 调用)。
	 */
	static void printHelp(Activity localActivity) {
		String[] helpArgs = localActivity.help();
		SairCons.println(FCM.split_Color, Pathes.printSplit);
		for (String info : helpArgs)
			SairCons.println(FCM.EXECTION_help_Color, info);
		SairCons.println(FCM.split_Color, Pathes.printSplit);
	}

	/**
	 * 打印框架运行环境信息:FrameActivity 版本、Java 版本、JavaHome、操作系统。
	 */
	public static void printTiInfos() {
		SairCons.println("loaded FrameActi : " + ConsFrame.fa.version);
		SairCons.println(Pathes.printSplit);
		SairCons.println("Java Version : " + CJDK.version);
		SairCons.println("JavaHome Path : " + CJDK.javaPath);
		SairCons.println("System : " + CJDK.sysName);
		SairCons.println(Pathes.printSplit);
	}

}
