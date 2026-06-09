package sair.sys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import sair.FCM;

public class IRRunnable implements Runnable {
	public static final HashMap<String, IRRunnable> irpool = new HashMap<String, IRRunnable>();

	static final String utfCode = new String(new char[] { 65279 });

	private HashMap<String, IRLabel> irlabels = new HashMap<String, IRLabel>();
	private boolean irContinue = true;
	private List<String> allLines;
	private String name;
	private Thread myThread;

	public void run() {
		for (String cmd : allLines) {
			if (isNULL(cmd))
				continue;
			if (irContinue)
				labelRunner(cmd);
			else
				break;
		}
		if (irContinue == true)
			stopIR();
	}

	private void labelRunner(String cmd) {
		if (cmd == null)
			return;
		cmd = cmd.replaceAll("^\\s+", "");
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

	private static boolean isNULL(String cmd) {
		if (cmd == null)
			return true;
		return utfCode.equals(cmd.trim());
	}

	public void stopIR() {
		irContinue = false;
		irpool.remove(this.name);
		if (myThread == null)
			return;

		if (!myThread.isInterrupted()) {
			try {
				myThread.join(3000);
			} catch (InterruptedException e) {
				SairCons.println(FCM.Error_Color, this.name + " -> 当前操作的IR线程错误!");
			}
			if (myThread.isAlive()) {
				myThread.interrupt();
				// SairCons.println(FCM.Error_Color, this.name + " ->
				// 当前操作的IR线程超时!");
			} else
				SairCons.println(this.name + " -> 完成!");
		}
	}

	public boolean setLabel(String lb_name, IRLabel label) {
		boolean flag = irlabels.containsKey(lb_name);
		if (flag) {
			irlabels.put(lb_name, label);
			return true;
		} else
			return false;
	}

	public boolean addLabel(String lb_name, IRLabel label) {
		boolean flag = irlabels.containsKey(lb_name);
		if (!flag) {
			irlabels.put(lb_name, label);
			return true;
		} else
			return false;
	}

	public IRLabel getLabel(String lb_name) {
		return irlabels.get(lb_name);
	}

	public IRRunnable(List<String> allLines, String name) {
		this.name = name;
		this.setMyThread(myThread);
		if (name != null && irpool.containsKey(name)) {
			SairCons.println(FCM.Error_Color, "已经运行了一个名为:[" + name + "]的ir脚本");
			irContinue = false;
		} else {
			this.allLines = toMakeLabel(this, allLines);
			irpool.put(name, this);
		}
	}

	// private final static String MAIN_NAME = "main";

	private static final String label_head_flag = "{";
	private static final String label_end_flag = "}";

	private static final String label_to = "/TO:";

	private static List<String> toMakeLabel(IRRunnable irRunnable, List<String> allLines) {
		ArrayList<String> mainLabel = new ArrayList<String>();
		toMakeLabel_0(irRunnable, allLines, -1, mainLabel);
		return mainLabel;
	}

	private static void toMakeLabel_0(IRRunnable irRunnable, List<String> allLines, int index,
			ArrayList<String> labelLine) {
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
				toMakeLabel_0(irRunnable, allLines, i, labelList);
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

	public Thread getMyThread() {
		return myThread;
	}

	public void setMyThread(Thread myThread) {
		this.myThread = myThread;
	}
}
