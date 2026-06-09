package sair.sys;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;

import sair.FCM;
import sair.Pathes;
import sair.sys.gui.ConsFrame;
import sair.sys.tools.Spliter;
import sair.sys.tools.ToolPack;
import sair.user.Activity;
import sair.user.PrintRunnable;
import sair.user.SpliterSPI;

public class SairCons {

	public final static ArrayList<String> localRunnerHistory = new ArrayList<String>();
	private final static HashMap<String, PrintRunnable> printAgos = new HashMap<String, PrintRunnable>() {
		/**
		 * 
		 */
		private static final long serialVersionUID = -6312415774187064675L;

		public HashMap<String, PrintRunnable> init() {
			return this;
		}

		public PrintRunnable put(String name, PrintRunnable act) {
			if (!this.containsKey(name))
				ConsFrame.cf.listModel.addElement(name);
			return super.put(name, act);
		}

		public PrintRunnable remove(Object name) {
			ConsFrame.cf.listModel.removeElement(name);
			return super.remove(name);
		}

		public void clear() {
			super.clear();
			ConsFrame.cf.listModel.removeAllElements();
		}
	}.init();

	public static SpliterSPI SpliterSpiManager;
	public static int localRunnerHistory_Index = 0;

	public final static boolean addPrintRunnable(String pr_id, PrintRunnable pr) {
		if (printAgos.containsKey(pr_id))
			return false;
		printAgos.put(pr_id, pr);
		return true;
	}

	public final static PrintRunnable removePrintRunnable(String pr_id) {
		return printAgos.remove(pr_id);
	}

	public final static void removeAllPrintRunnable() {
		printAgos.clear();
	}

	public final static Color getDefaultColor() {
		return ConsFrame.getFontColor();
	}

	public final static void setDefaultColor(Color c) {
		ConsFrame.setFontColor(c);
	}

	public final static void dePrint(Integer offs, Integer len) {
		ConsFrame.dePrinto(offs, len);
	}

	public final static void insertPrinto(Integer index, Color c, String info) {
		if (printAgos.size() != 0) {
			ConsFrame.setTitleInfo("SFW的其他输出模式");
			runAgo(index, c, info);
		} else {
			ConsFrame.setTitleInfo(ConsFrame.title_str);
			ConsFrame.printo(index, c, info);
		}
		ConsFrame.flushPoint();
	}

	private static void runAgo(Integer index, Color c, String info) {
		for (String pr_id : printAgos.keySet()) {
			PrintRunnable pr = printAgos.get(pr_id);
			if (pr != null)
				pr.run(index, c, info);
		}
	}

	public final static void print(Color c, String info) {
		insertPrinto(null, c, info);
	}

	public final static void println(Color c, String info) {
		print(c, "\r\n" + info);
	}

	public final static void print(String info) {
		print(null, info);
	}

	public final static void println(String info) {
		println(null, info);
	}

	public final static void clear() {
		dePrint(0, null);
		localRunnerHistory.clear();
	}

	public final static String getConsoleText() {
		return ConsFrame.getAllText();
	}

	public final static int getConsoleSize() {
		return ConsFrame.getPaneSize();
	}

	private static boolean chkCmdIsNul(String cmd) {
		if (cmd == null)
			return true;
		if (cmd.trim().equals("") || "//".equals(cmd.replaceAll("^\\s+", "")))
			return true;

		return false;
	}

	public final static Object runner(boolean isMark, String cmd) {
		/*
		 * if ("jj/at 1+/100".equals(cmd)) System.out.println();
		 */

		if (chkCmdIsNul(cmd))
			return null;

		if (ToolPack.SpliterChkUninstall(cmd))
			return true;

		if (isMark) {
			if (SairCons.localRunnerHistory.size() > 10000) {
				SairCons.localRunnerHistory.clear();
				SairCons.localRunnerHistory_Index = 0;
			}
			SairCons.localRunnerHistory.add(cmd);
		}

		Spliter sp = ToolPack.findSplited(cmd.replaceAll("^\\s+", ""));
		if (sp == null) {
			SairCons.SpliterSpiManager = null;
			SairCons.println(FCM.Error_Color, "Spliter错误！已切换默认解释器！");
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

		if (localActivity != null)
			return OderFact.runner(localActivity, sp);
		else {
			SairCons.println(FCM.Error_Color, "\"" + localName + "\" is not found");
			return null;
		}
	}

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

	static void printHelp(Activity localActivity) {
		String[] helpArgs = localActivity.help();
		SairCons.println(FCM.split_Color, Pathes.printSplit);
		for (String info : helpArgs)
			SairCons.println(FCM.EXECTION_help_Color, info);
		SairCons.println(FCM.split_Color, Pathes.printSplit);
	}

	public static void printTiInfos() {
		SairCons.println("loaded FrameActi : " + ConsFrame.fa.version);
		SairCons.println(Pathes.printSplit);
		SairCons.println("Java Version : " + CJDK.version);
		SairCons.println("JavaHome Path : " + CJDK.javaPath);
		SairCons.println("System : " + CJDK.sysName);
		SairCons.println(Pathes.printSplit);
	}

}
