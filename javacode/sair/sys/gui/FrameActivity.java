package sair.sys.gui;

import java.awt.Color;

import sair.FCM;
import sair.Main;
import sair.Pathes;
import sair.sys.SairCons;
import sair.user.Activity;

public class FrameActivity extends Activity {

	public final String version = "version:" + Main.Version;
	private final FrameActivity_Actions actions = new FrameActivity_Actions();

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

		// 组件展示命令
		case "list":
			return actions.showList(false);
		case "list-s":
			return actions.showList(true);

		// 打印命令
		case "deprint":
			return actions.deprint(args);
		case "clear": {
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
			ConsFrame.flushPoint();
			return true;
		}

	}

	@Override
	public String[] help() {
		return new String[] { //
				Pathes.printSplit, //
				version, //
				"(双引号是路径关键对，单引号是命令执行返回关键对，请避免常规命令使用这两对组)", //
				"{/help （通用命令）查看帮助}	"//
						+ "{/exit （通用命令）结束}	", //
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

	@Override
	public void exit() {
		ConsFrame.close();
	}

	@Override
	protected String dataDir() {
		return "framework";
	}

	public Object o_funcMain(Object o) {
		if (o == null)
			return null;
		SairCons.print(FCM.Error_Color, "\r\nName:" + o.getClass().getName());
		SairCons.println(Color.WHITE, "V:" + String.valueOf(o));
		return o;
	}

}
