package sair.sys.gui;

import java.awt.Color;

import sair.FCM;
import sair.Main;
import sair.Pathes;
import sair.sys.SairCons;
import sair.user.Activity;

/**
 * 框架命令分发器:把解释器下发的 funcName 分发到 FrameActivity_Actions 的具体实现,并维护 /help 帮助文本。
 * <p>
 * 架构角色:sair.user.Activity 子类,实例即 {@link ConsFrame#fa}(注册为框架组件)。main 按 funcName switch:
 * GUI定制命令(setBG/setFC/…)、组件展示(list/list-s)、打印(print/println/deprint/clear/print-f/…)、
 * 窗体自身(hide/show/resize)、脚本(ir/ir-x/ir-i)、解释器/变量/线程等;未知命令输出一行错误提示
 * (提示输入/help查看帮助,原实现静默返回)。
 * <p>
 * 线程模型(已彻底弃用EDT调度):main 可能被解释器在工作线程或AWT事件回调中调用,
 * 本类无共享可变状态(actions 每次分发现调),命令由调用线程直接执行(恢复旧版方式)。
 * /clear 只清空控制台文本,不影响画布选项卡隔离区(隔离区由右键菜单/clearComponents管理)。
 * <p>
 * 二进制兼容:公开字段 {@link #version} 与重写方法签名(main/help/exit/dataDir/o_funcMain)保持稳定;
 * 命令名称(case字符串)即插件对外接口,不可改名。
 */
public class FrameActivity extends Activity {

	/** 版本显示串(help头部使用) */
	public final String version = "version:" + Main.Version;
	private final FrameActivity_Actions actions = new FrameActivity_Actions();

	/**
	 * 命令分发入口:按 funcName 分发到 actions 的实现;所有分支返回非null结果。
	 * 未知命令走default:先flushPoint(滚动贴底)再打印一行错误提示。
	 *
	 * @param funcName 命令名(如"print"/"load")
	 * @param args     命令参数原文
	 * @return 各命令的执行结果(多数为Boolean)
	 */
	@Override
	public Object main(String funcName, String args) {

		switch (funcName) {

		// GUI 自定义命令
		case "setBG":
			return actions.setBG(args);
		case "setFC":
			return actions.setColor(true, args);
		case "setBC":
			return actions.setColor(false, args);
		case "setFCM":
			return actions.setFCMColor(args);
		case "opacity":
			return actions.opacity(args);
		case "load":
			return actions.load(args);

		// 组件展示命令
		case "list":
			return actions.showList(false);
		case "list-s":
			return actions.showList(true);

		// 打印命令
		case "deprint":
			return actions.deprint(args);
		case "clear": {
			// 只清控制台文本,不影响右侧画布选项卡隔离区(隔离区由右键菜单管理)
			SairCons.clear();
			return true;
		}
		case "print":
			return actions.print(false, false, args);
		case "print-c":
			return actions.print(true, false, args);
		case "println":
			return actions.print(false, true, args);
		case "println-c":
			return actions.print(true, true, args);
		case "print-f":
		case "println-f":
			return actions.printf(args);
		case "print-ti":
			return actions.printti();
		case "print-cpr":
			return actions.printcpr(args);
		// 窗体自身命令
		case "hide": {
			ConsFrame.hideFrame();
			return true;
		}
		case "show": {
			ConsFrame.showFrame();
			return true;
		}
		case "resize": {
			try {
				return actions.resize(args);
			} catch (Exception e) {
				SairCons.println(FCM.Error_Color, " size ERR !! ");
			}
			return true;
		}

		// 脚本读取命令
		case "ir": {
			try {
				actions.ir(args);
			} catch (Exception e) {
				SairCons.println(FCM.Error_Color, args + " : irFile Error!");
			}
			return true;
		}

		// 脚本停止命令
		case "ir-x": {
			actions.irstop(args);
			return true;
		}

		// 脚本阻塞式命令
		case "ir-i": {
			try {
				actions.iri(args);
			} catch (Exception e) {
				SairCons.println(FCM.Error_Color, args + " : irFile Error!");
			}
			return true;
		}

		// 解释器设置
		case "setspliter":
			return actions.setSpliter(args);

		// 组件重命名
		case "rename":
			return actions.renameActi(args);

		// 变量池操作：
		case "var-add":
			return actions.addVar(args);
		case "var-del":
			return actions.delVar(args);
		case "var-list":
			return actions.listVar();

		// 线程操作：
		case "sleep":
			return actions.sleep(args);
		case "newthread":
			return actions.newThread(args);

		default:
			// 修复:未知命令给一行提示(原实现静默返回,用户无法感知输入无效)
			SairCons.println(FCM.Error_Color, "未知命令: " + (funcName == null ? "" : funcName.trim()) + " (输入/help查看帮助)");
			return true;
		}

	}

	/**
	 * 帮助文本:解释器在输入/help时逐行打印;内容即全部框架GUI命令的用法说明。
	 *
	 * @return 帮助文本行数组
	 */
	@Override
	public String[] help() {
		return new String[] { //
				Pathes.printSplit, //
				version, //
				"(双引号是路径关键对，单引号是命令执行返回关键对，请避免常规命令使用这两对组)", //
				"{/help （通用命令）查看帮助}	"//
						+ "{/exit （通用命令）结束}	", //
						"{/uninstall （通用命令）卸载并移除该插件(释放jar文件占用)}	", //
				"{/info （通用命令）查看组件data和jar文件来源}	", //
				"{/ofunc '[cmd]' （通用命令） 优先执行单括号内的命令返回到重写的o_funcMain方法}	", //
				"{/oset [newFucName] [oldFuncName]（通用命令） 新建一个组件内指令对应旧指令（旧指令任然可以使用）}	", //
				"{/orem [newFucName]（通用命令） 移除掉新增的指令代替}	", //
				"{/close | open （通用命令）关闭或者打开命令传入到指定组件}", //
				Pathes.printSplit, //
				"/clear 清屏命令", //
				"/println | print | deprint打印命令，可加-c进行颜色打印,-f输出图片文件(无法识别只会输出路径)", //
				"/print-ti 则是打印消息头,可以在清屏后使用", "\t例举： /print-c 255 0 0 [args]", //
				"\t例举：/print [args]", //
				"\t例举：/deprint 0 256 | /deprint max max", //
				"print-cpr [name] 清除第三方输出模式,如果name留空,那么将会清除所有输出模式方案",
				"/setBG [path] 设置背景图片，path一定需要双引号否则无法识别路径！", //
				"/opacity [10-100] 设置窗口不透明度(100=完全不透明,JDK8反射/JDK10+直接API)", //
				"/load [path|url] 从其他位置加载插件jar(支持本地路径与http/https/file URL)", //
				"/setBC [RGB] | setFC [RGB]设置背景颜色与设置默认打印字体颜色（包括边框颜色和窗体元素颜色）", //
				"/setFCM [target] [RGB] 设置FCM中的各种参数颜色，需要组件引用此颜色实现统一！", //
				"\ttarget: 可设置参数有 ui-error，ex-help，ex-info，ex，mod", //
				"\t分别是: 错误消息的显示颜色，帮助消息的显示颜色，", //
				"\t\t组件info命令触发的显示颜色，组件名或者mod名显示颜色", //
				"/list 查看已经加载的所有plugin，可以加-s进行查看exection的详细信息", //
				"\t举例：/list-s", //
				"/ir [path] 脚本执行", //
				"/ir-x [path] 脚本停止", //
				"/ir-i [path] 使用当前线程执行ir(任务过长会导致当前线程阻塞)", //
				"/hide | show 隐藏和显示前台控制台", //
				"/setspliter [className] 切换控制台解释器到指定解释器", //
				"\t注释：spliter管理器需要继承自抽象类sair.user.SpliterSPI", //
				"/rename [old_name] [new_name] 重命名exection组件", //
				"/var-add [name] [string] 设置环境变量值", //
				"/var-del [name] 删除环境变量值", //
				"\t添加后用法只存在系统解释器中：比如添加名字为var的变量，那么直接在命令输入框输入含", //
				"\t%var%的字符串就能被解释器自动代替成[string]的值", //
				"\t重复var-add相同变量名会强制更改已有的值", //
				"/var-list 查看所有已经设置的变量", //
				"/sleep [time] 线程睡眠:time为毫秒数", //
				"/newthread [cmd]以独立的新线程形式跑命令(不使用线程池)", //
				"/resize [w] [h] 重新设置窗体长宽，如果resize无参数则就是默认大小", //

		};
	}

	/** 退出框架:/exit与托盘exit菜单最终走这里 → ConsFrame.close() → System.exit(0) */
	@Override
	public void exit() {
		ConsFrame.close();
	}

	/** 组件数据目录名("framework"):与Pathes.dataResDir拼出本组件的持久化目录 */
	@Override
	protected String dataDir() {
		return "framework";
	}

	/**
	 * ofunc回调入口(/ofunc '[cmd]' 触发):打印对象类名与值并原样返回。
	 *
	 * @param o 回调传入的对象(可null)
	 * @return 原对象
	 */
	public Object o_funcMain(Object o) {
		if (o == null)
			return null;
		SairCons.print(FCM.Error_Color, "\r\nName:" + o.getClass().getName());
		SairCons.println(Color.WHITE, "V:" + String.valueOf(o));
		return o;
	}

}
