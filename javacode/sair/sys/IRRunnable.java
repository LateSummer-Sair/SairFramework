package sair.sys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import sair.FCM;
import sair.Safe;

/**
 * IR 脚本解释器(Runnable):把 IR 文本文件解析为"主序列 + 命名标签块",逐行投递
 * {@link SairCons#runner} 执行,支持 /TO: 标签跳转与 stopIR 外部终止。
 * <p>
 * 架构角色:命令解释环节的批量执行器——脚本行复用与手工输入相同的命令解析链路,
 * 是 SFW 的"批处理"形式;每个实例绑定一个脚本名,同一时间仅允许一个同名实例运行。
 * <p>
 * 线程安全说明:
 * <ul>
 * <li>{@link #irpool} 为 Safe.map() 同步表,构造器用 putIfAbsent 原子登记,消除同名并发登记的
 *     check-then-act 竞态(后登记的不运行);</li>
 * <li>{@link #irContinue} 为 volatile 停止标志,stopIR 对其置位;</li>
 * <li>{@link #irlabels} 仅在构造阶段(toMakeLabel)单线程写入,运行阶段只读;</li>
 * <li>run 以 try/finally 保证任何异常路径都反注册 irpool,避免外部等待永久挂起。</li>
 * </ul>
 * 二进制兼容约束:{@link #irpool} 为 public static final HashMap&lt;String, IRRunnable&gt;,
 * 公开方法 run/stopIR/setLabel/addLabel/getLabel/getMyThread/setMyThread 的签名不可变更;
 * 标签语法("{...}" 块、"/TO:" 跳转)为 IR 文件格式契约,不可改变。
 */
public class IRRunnable implements Runnable {
	/**
	 * 全局运行中脚本登记表(线程安全):键为脚本名;外部(如 Main.waitIrFinish)据此等待脚本结束。
	 */
	public static final HashMap<String, IRRunnable> irpool = Safe.map();

	/**
	 * UTF-8 BOM 字符(U+FEFF):用于识别/剥离 Windows 记事本保存的 BOM 头。
	 */
	static final String utfCode = new String(new char[] { 65279 });

	/**
	 * 标签名 → 标签块映射:仅在构造阶段(toMakeLabel)单线程写入,运行阶段只读。
	 */
	private HashMap<String, IRLabel> irlabels = new HashMap<String, IRLabel>();

	/**
	 * 运行/停止标志(volatile):stopIR 置 false 后,主循环与标签跳转都会尽快退出。
	 */
	private volatile boolean irContinue = true;

	/**
	 * 主执行序列(非标签行的命令集合;标签行已被解析消费并置为空串)。
	 */
	private List<String> allLines;

	/**
	 * 脚本名(登记/反登记 irpool 与日志展示用)。
	 */
	private String name;

	/**
	 * 承载本脚本的执行线程(由启动方通过 setMyThread 注入,stopIR 时 join 用)。
	 */
	private Thread myThread;

	/**
	 * 是否已成功登记 irpool(用于 run 的 finally 反注册判断)。
	 */
	private boolean registered = false;

	/**
	 * 脚本主循环:按行执行主序列;BOM-only 行视为空行;
	 * {@link #irContinue} 被置位或发生异常时退出;finally 中完成反注册——
	 * 正常执行完毕走 stopIR(join 清理线程),被外部终止则直接从 irpool 移除。
	 */
	public void run() {
		// 修复:try/finally保证任何异常路径都反注册irpool,避免Main.waitIrFinish永久挂起
		try {
			for (String cmd : allLines) {
				if (isNULL(cmd))
					continue;
				if (irContinue)
					labelRunner(cmd);
				else
					break;
			}
		} catch (Throwable e) {
			SairCons.println(FCM.Error_Color, this.name + " -> IR执行异常:" + e);
		} finally {
			if (registered) {
				if (irContinue)
					stopIR();
				else
					irpool.remove(this.name);
			}
		}
	}

	/**
	 * 标签跳转嵌套深度上限(可配置,私有常量):防止 /TO: 循环跳转无限递归耗尽栈(安全加固)。
	 * 同时被标签预解析(toMakeLabel_0)复用为 "{" 嵌套上限。
	 */
	private static final int MAX_LABEL_DEPTH = 128;

	/**
	 * 当前线程标签跳转深度计数器:随 labelRunner 进出增减,与调用栈同生命周期。
	 */
	private int labelDepth = 0;

	/**
	 * 标签跳转入口:深度守卫 + 递归分发。
	 * <p>/TO: 目标块中的每一行同样可能再次 /TO:,因此递归执行;
	 * 深度达到 {@link #MAX_LABEL_DEPTH} 时打印告警并中止本次跳转,
	 * finally 保证深度计数在异常路径同样恢复。
	 */
	private void labelRunner(String cmd) {
		if (cmd == null)
			return;
		if (labelDepth >= MAX_LABEL_DEPTH) {
			SairCons.println(FCM.Error_Color, "标签嵌套过深(超过MAX_LABEL_DEPTH),已中止跳转");
			return;
		}
		labelDepth++;
		try {
			labelRunner0(cmd);
		} finally {
			labelDepth--;
		}
	}

	/**
	 * 单行分发:以 "/TO:" 开头时按其后标签名取出标签块并逐行递归执行
	 * (标签不存在打印错误);否则作为普通命令经 {@link SairCons#runner(false, cmd)}
	 * 执行(不记历史)。
	 */
	private void labelRunner0(String cmd) {
		cmd = ltrim(cmd);
		if (cmd.startsWith(label_to)) {
			String localName = cmd.substring(label_to.length(), cmd.length());
			if ("".equals(localName))
				return;
			IRLabel irlb = irlabels.get(localName);
			if (irlb == null) {
				SairCons.println(FCM.Error_Color, "没有找到名为:" + localName + "的标签入口!");
				return;
			}
			List<String> lines = irlb.getLines();
			for (String line : lines) {
				if (isNULL(line))
					continue;
				if (irContinue)
					labelRunner(line);
			}
		} else
			SairCons.runner(false, cmd);
	}

	/**
	 * 空行判定:null 或仅含 BOM 字符(trim 后与 BOM 相等)的行视为空。
	 */
	private static boolean isNULL(String cmd) {
		if (cmd == null)
			return true;
		return utfCode.equals(cmd.trim());
	}

	/**
	 * 去除前导空白(性能优化:手写字符扫描替代正则 replaceAll,逐行热路径调用)。
	 */
	private static String ltrim(String s) {
		int i = 0, n = s.length();
		while (i < n && Character.isWhitespace(s.charAt(i)))
			i++;
		return s.substring(i);
	}

	/**
	 * 终止脚本:置停止标志并反注册 irpool;若由外部线程调用,则在独立守护线程
	 * ("ir-joiner")中 join 执行线程最多 3 秒,超时仍存活则 interrupt。
	 * <p>修复点:脚本自身执行完毕时跳过对自身线程的 join/中断;join 移出调用线程(EDT),
	 * 避免 /irstop 从界面触发时冻结 GUI 最长 3 秒。
	 */
	public void stopIR() {
		irContinue = false;
		irpool.remove(this.name);
		if (myThread == null)
			return;
		// 修复:脚本正常执行完毕时自调用,跳过对自身线程的3秒join与自我中断
		if (myThread == Thread.currentThread())
			return;

		if (!myThread.isInterrupted()) {
			// 修复:join移出调用线程(EDT),避免/irstop从界面触发时冻结GUI最长3秒
			final Thread target = myThread;
			final String tname = this.name;
			Thread joiner = new Thread(new Runnable() {
				public void run() {
					try {
						target.join(3000);
					} catch (InterruptedException e) {
						SairCons.println(FCM.Error_Color, tname + " -> 当前操作的IR线程错误!");
					}
					if (target.isAlive()) {
						target.interrupt();
					} else
						SairCons.println(tname + " -> 完成!");
				}
			}, "ir-joiner");
			joiner.setDaemon(true);
			joiner.start();
		}
	}

	/**
	 * 覆盖已存在的标签(仅当同名标签已存在时写入,返回是否发生替换)。
	 *
	 * @param lb_name 标签名
	 * @param label   标签块
	 * @return true 表示同名标签已存在并已替换;false 表示不存在、未写入
	 */
	public boolean setLabel(String lb_name, IRLabel label) {
		boolean flag = irlabels.containsKey(lb_name);
		if (flag) {
			irlabels.put(lb_name, label);
			return true;
		} else
			return false;
	}

	/**
	 * 注册新标签(仅当同名标签不存在时写入,返回是否新增成功)。
	 *
	 * @param lb_name 标签名
	 * @param label   标签块
	 * @return true 表示新增成功;false 表示同名标签已存在、未覆盖
	 */
	public boolean addLabel(String lb_name, IRLabel label) {
		boolean flag = irlabels.containsKey(lb_name);
		if (!flag) {
			irlabels.put(lb_name, label);
			return true;
		} else
			return false;
	}

	/**
	 * 按标签名读取标签块;不存在返回 null。
	 */
	public IRLabel getLabel(String lb_name) {
		return irlabels.get(lb_name);
	}

	/**
	 * 构造并登记:剥离 BOM 后解析标签块得到主序列;putIfAbsent 原子登记 irpool——
	 * 同名脚本已在运行时,本实例不登记且置停止标志(后登记的不运行)。
	 *
	 * @param allLines 脚本原始行集合(会被原地修改:标签行置空、首行剥 BOM)
	 * @param name     脚本名(irpool 键;为 null 时不做重名拦截)
	 */
	public IRRunnable(List<String> allLines, String name) {
		this.name = name;
		// 修复:putIfAbsent原子登记,消除同名并发登记的check-then-act竞态(后登记的不运行)
		this.allLines = toMakeLabel(this, stripBom(allLines));
		if (name == null || irpool.putIfAbsent(name, this) == null) {
			registered = true;
		} else {
			SairCons.println(FCM.Error_Color, "已经运行了一个名为:[" + name + "]的ir脚本");
			irContinue = false;
		}
	}

	/**
	 * 去除首行 UTF-8 BOM:Windows 记事本保存的 UTF-8 文件首行带 BOM,
	 * 会破坏第一条命令的解析,构造时统一去除。
	 */
	private static List<String> stripBom(List<String> allLines) {
		if (allLines != null && allLines.size() > 0) {
			String first = allLines.get(0);
			if (first != null && first.startsWith(utfCode))
				allLines.set(0, first.substring(utfCode.length()));
		}
		return allLines;
	}

	// private final static String MAIN_NAME = "main";

	/**
	 * 标签块开启标志:行尾为 "{" 表示开启一个命名标签块(标签名为其前文本)。
	 */
	private static final String label_head_flag = "{";

	/**
	 * 标签块结束标志:行尾为 "}" 表示结束当前标签块。
	 */
	private static final String label_end_flag = "}";

	/**
	 * 标签跳转命令前缀:行首 "/TO:" 后跟目标标签名。
	 */
	private static final String label_to = "/TO:";

	/**
	 * 标签预解析入口:扫描行集,把 "xxx{" ... "}" 之间的行收进命名标签块,
	 * 其余行构成主序列;{@link #toMakeLabel_0} 递归处理嵌套标签。
	 */
	private static List<String> toMakeLabel(IRRunnable irRunnable, List<String> allLines) {
		ArrayList<String> mainLabel = new ArrayList<String>();
		toMakeLabel_0(irRunnable, allLines, -1, mainLabel, 0);
		return mainLabel;
	}

	/**
	 * 标签预解析递归实现:从 index+1 起扫描,遇行尾 "{" 开启新标签并递归收集其内容,
	 * 遇行尾 "}" 返回上一级,普通行追加到当前收集列表;已消费的行原地置空串(避免重复执行)。
	 * <p>深度超过 {@link #MAX_LABEL_DEPTH} 时截断解析,防止深嵌套 "{" 触发 StackOverflowError。
	 */
	private static void toMakeLabel_0(IRRunnable irRunnable, List<String> allLines, int index,
			ArrayList<String> labelLine, int depth) {
		// 修复:标签解析递归加深度上限,防止深嵌套"{"触发StackOverflowError
		if (depth > MAX_LABEL_DEPTH) {
			SairCons.println(FCM.Error_Color, "IR标签嵌套超过上限[" + MAX_LABEL_DEPTH + "]层,已截断解析");
			return;
		}
		for (int i = index + 1; i < allLines.size(); i++) {
			String line = allLines.get(i);
			if (line == null || "".equals(line))
				continue;
			line = line.trim();
			allLines.set(i, "");
			if (line.endsWith(label_head_flag)) {
				String localName = "";
				if (line.length() > 1)
					localName = line.substring(0, line.length() - 1).trim();
				else
					localName = "noname";
				ArrayList<String> labelList = new ArrayList<String>();
				toMakeLabel_0(irRunnable, allLines, i, labelList, depth + 1);
				IRLabel irl = new IRLabel();
				irl.setName(localName);
				irl.setLines(labelList);
				irRunnable.irlabels.put(localName, irl);
			} else if (line.endsWith(label_end_flag)) {
				return;
			} else {
				labelLine.add(line);
			}
		}
	}

	// /ir ".\data\sair.keyfunc.KeyFuncMain\KeyFuncReco_Rubia.ir"

	/*
	 * private static String[] toMakeLabel(IRRunnable irr, List<String>
	 * allLines) { if (!(allLines != null && allLines.size() != 0)) return new
	 * String[] {}; String[] flag = new String[allLines.size()];
	 * allLines.toArray(flag);
	 * 
	 * List<String> line = null; String localName = null; for (int i = 0; i <
	 * flag.length; i++) { if (flag[i] != null &&
	 * flag[i].trim().startsWith(label_head_flag)) { if (line == null &&
	 * localName == null) { localName =
	 * flag[i].substring(label_head_flag.length(), flag[i].length()); if
	 * ("".equals(localName)) { line = null; localName = null; continue; } line
	 * = new ArrayList<String>(); } else { put(localName, line, irr); line =
	 * null; localName = null; i--; continue; } } else if (line != null &&
	 * !"".equals(flag[i])) { line.add(flag[i].trim()); flag[i] = ""; } } if
	 * (line != null && localName != null) { put(localName, line, irr); line =
	 * null; localName = null; } return flag; }
	 */

	/*
	 * private static void put(String localName, List<String> line, IRRunnable
	 * irr) { IRLabel irlb = new IRLabel(); irlb.setName(localName);
	 * irlb.setLines(line); irr.irlabels.put(localName, irlb);
	 * 
	 * }
	 */

	/**
	 * 读取承载线程(外部 join/诊断用)。
	 */
	public Thread getMyThread() {
		return myThread;
	}

	/**
	 * 注入承载线程(启动方在线程启动前调用,stopIR 据此 join)。
	 */
	public void setMyThread(Thread myThread) {
		this.myThread = myThread;
	}
}
