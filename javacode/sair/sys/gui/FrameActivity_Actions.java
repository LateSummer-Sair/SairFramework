package sair.sys.gui;

import java.awt.Color;
import java.io.File;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.swing.ImageIcon;
import javax.swing.JLabel;

import sair.FCM;
import sair.Pathes;
import sair.sys.IRRunnable;
import sair.sys.Libraries;
import sair.sys.SairCons;
import sair.sys.acticity.Exection;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

class FrameActivity_Actions {
	private String[] filelastNames = new String[] { ".PNG", ".BMP", ".JPGE", ".JPG", ".GIF", ".ICO" };

	boolean renameActi(String args) {
		String[] names = args.split(" ");
		if (names.length < 2)
			return false;
		Activity localActi = Libraries.activities.remove(names[0]);
		if (localActi == null) {
			SairCons.println(FCM.Error_Color, "组件 " + names[0] + " 不存在！");
			return false;
		}
		try {
			localActi.setName(names[1]);
		} catch (Exception e) {
			SairCons.println(FCM.Error_Color, "rename fail ！！！" + e.getMessage());
			Libraries.activities.put(names[0], localActi);
		}
		Libraries.activities.put(names[1], localActi);
		SairCons.println(names[0] + " -> " + names[1]);
		return true;
	}

	boolean deprint(String args) {
		String[] args_arr = args.split(" ");
		Integer offs = null, len = null;
		if (args_arr.length >= 1)
			offs = getNumber(args_arr[0]);
		if (args_arr.length >= 2)
			len = getNumber(args_arr[1]);
		SairCons.dePrint(offs, len);

		return true;
	}

	Integer getNumber(String args) {
		int r;
		if ("max".equals(args))
			return null;
		else
			try {
				r = ToolPack.IntegerValOfString(args);
			} catch (Exception e) {
				return null;
			}
		return r;
	}

	boolean setBG(String args) {
		String[] pathes = ToolPack.pathRepack(args);
		if (pathes.length > 0) {
			String path = pathes[0];
			ConsFrame.setImageBackground(path);
			return true;
		} else
			return false;
	}

	boolean setColor(boolean isFontColor, String args) {
		Color N_Color = getColor(args);
		if (N_Color != null)
			if (isFontColor)
				ConsFrame.setFontColor(N_Color);
			else
				ConsFrame.setBackgroundColor(N_Color);
		else {
			SairCons.println("Color args Error!");
			return false;
		}
		return true;
	}

	boolean irContinue = true;

	void ir(String fileName) throws Exception {
		if ("".equals(fileName) || null == fileName) {
			Set<String> list = IRRunnable.irpool.keySet();
			SairCons.println(Pathes.printSplit);
			SairCons.println("显示正在运行的ir脚本列表:");
			SairCons.println(Pathes.printSplit);
			for (String name : list)
				SairCons.println(name);
			SairCons.println(Pathes.printSplit);
			return;
		}
		fileName = ToolPack.pathRepack(fileName)[0];
		List<String> allLines = Files.readAllLines(Paths.get(fileName), StandardCharsets.UTF_8);
		IRRunnable irr = new IRRunnable(allLines, fileName);
		Thread th = new Thread(irr);
		irr.setMyThread(th);
		th.start();
	}

	public void iri(String fileName) throws Exception {
		fileName = ToolPack.pathRepack(fileName)[0];
		List<String> allLines = Files.readAllLines(Paths.get(fileName), StandardCharsets.UTF_8);
		addVar("irp " + new File(fileName).getParentFile().getPath());
		new IRRunnable(allLines, fileName).run();
	}

	public Object irstop(String fileName) {
		fileName = ToolPack.pathRepack(fileName)[0];
		IRRunnable irth = IRRunnable.irpool.get(fileName);
		if (irth != null) {
			irth.stopIR();
			return true;
		} else {
			String info = "没有找到正在执行的ir文件:[" + fileName + "]";
			SairCons.println(FCM.Error_Color, info);
			return info;
		}
	}

	void irstart() {
		irContinue = true;
	}

	boolean print(boolean isColorSet, boolean isLineJump, String info) {

		if (!isColorSet) {
			if (isLineJump)
				info = "\r\n" + info;
			SairCons.print(info);
			return true;
		} else {
			Color c;
			if ((c = getColor(info)) == null) {
				SairCons.println("Color args Error!");
				return false;
			}
			String[] infomations = info.split(" ");
			for (int i = 3; i < infomations.length; i++) {
				if (isLineJump && i == 3)
					infomations[i] = "\r\n" + infomations[i];
				SairCons.print(c, infomations[i]);
				if (i < infomations.length - 1)
					SairCons.print(c, " ");
			}
			return true;
		}
	}

	boolean printf(String args) {
		String[] paths = ToolPack.pathRepack(args);
		for (String path : paths) {
			File file = new File(path);
			if (!file.exists()) {
				SairCons.println(FCM.Error_Color, "not found file: " + path);
				return true;
			}
			if (!chkFileImage(path)) {
				SairCons.println(FCM.Error_Color, "can`t open file : " + path);
				return true;
			}
			try {
				ImageIcon ii = new ImageIcon(file.toURI().toURL());
				JLabel lb = new JLabel(ii);
				ConsFrame.printComponent(lb);
				SairCons.println("");
			} catch (MalformedURLException e) {
				SairCons.println(FCM.Error_Color, "can`t open file : " + path);
				return true;
			}
		}

		return true;
	}

	private boolean chkFileImage(String path) {
		path = path.toUpperCase();
		for (String lastName : filelastNames)
			if (path.endsWith(lastName))
				return true;
		return false;
	}

	boolean showList(boolean b) {
		SairCons.println(FCM.split_Color, Pathes.printSplit);
		SairCons.print(FCM.loadExection_Color, "exections:");
		for (String name : Libraries.activities.keySet()) {
			SairCons.println(FCM.loadExection_Color, name);
			if (b) {
				Activity acti = Libraries.activities.get(name);
				Exection exec = Libraries.exections.get(acti);
				SairCons.println(FCM.EXECTION_pathInfo_Color, "  |-->" + acti.getDataDir());
				if (exec == null)
					continue;
				SairCons.println(FCM.EXECTION_pathInfo_Color, "  |-->" + exec.getPath());
				SairCons.println(" ");
			}
		}

		SairCons.println(FCM.split_Color, Pathes.printSplit);
		SairCons.print(FCM.loadMod_Color, "Mods:");
		for (String name : Libraries.mods.keySet())
			SairCons.println(FCM.loadMod_Color, name);

		return true;
	}

	Color getColor(String args) {
		String[] RGB_S = args.split(" ");
		if (RGB_S.length < 3)
			return null;
		int[] RGB_I = new int[3];
		for (int i = 0; i < 3; i++)
			try {
				RGB_I[i] = ToolPack.IntegerValOfString(RGB_S[i]);
			} catch (Exception e) {
				RGB_I[i] = 0;
			}
		return new Color(RGB_I[0], RGB_I[1], RGB_I[2]);
	}

	public boolean addVar(String args) {
		HashMap<String, String> localMap = ToolPack.getVmap();
		String[] argSplited = args.split(" ");
		if (argSplited.length < 2) {
			SairCons.println("请检查参数是否输入正确：");
			SairCons.print(FCM.Error_Color, args);
			return false;
		}

		String name = "%" + argSplited[0] + "%";
		String v = ToolPack.reArg(argSplited, new Integer[] { 0 });

		localMap.put(name, v);
		return true;
	}

	public boolean delVar(String args) {
		HashMap<String, String> localMap = ToolPack.getVmap();
		if ("".equals(args))
			return false;

		String name = "%" + args + "%";

		if (null == localMap.remove(name))
			SairCons.println(FCM.Error_Color, "remove fail ,name nofound -> " + name);

		return true;
	}

	public boolean setSpliter(String args) {
		try {
			if ("null".equals(args) || "".equals(args))
				args = "默认解释器";
			else
				ToolPack.setSpliter(args);
			SairCons.println("已切换到：" + args);
		} catch (Exception e) {
			SairCons.println(FCM.Error_Color, "语言解释器加载失败！");
		}
		return true;
	}

	public boolean listVar() {
		HashMap<String, String> localMap = ToolPack.getVmap();
		Iterator<String> it = localMap.keySet().iterator();
		SairCons.println(Pathes.printSplit);
		while (it.hasNext()) {
			String name = it.next();
			String v = localMap.get(name);
			SairCons.println(name + " -> " + v);
		}
		SairCons.println(Pathes.printSplit);
		return true;
	}

	public boolean sleep(String args) {
		try {
			Thread.sleep(ToolPack.IntegerValOfString(args));
		} catch (Exception e) {

		}
		return true;
	}

	public Object newThread(String args) {
		new Thread() {
			public void run() {
				SairCons.runner(false, args);
			}

		}.start();
		return true;
	}

	public boolean resize(String args) throws Exception {
		int w, h;
		if ("".equals(args.trim())) {
			w = ConsFrame.w;
			h = ConsFrame.h;
		} else {
			String[] sped = args.split(" ");
			if (sped.length < 1)
				return printSizeE();
			w = ToolPack.IntegerValOfString(sped[0]);
			h = ToolPack.IntegerValOfString(sped[1]);
			if (w < 200 || h < 200)
				return printSizeE();
		}
		ConsFrame.cf.setSize(w, h);
		return true;
	}

	private boolean printSizeE() {
		SairCons.println(FCM.Error_Color, " size ERR !! ");
		return false;
	}

	public boolean setFCMColor(String args) {
		if ("".equals(args) || args == null)
			return false;

		String[] argsplited = args.split(" ");
		if (argsplited.length < 2)
			return false;

		String rearg = ToolPack.reArg(argsplited, new Integer[] { 0 });
		Color c = getColor(rearg);
		if (null == c)
			return false;

		switch (argsplited[0]) {
		case "ui-error": {
			FCM.Error_Color = c;
			return true;
		}
		case "ex-help": {
			FCM.EXECTION_help_Color = c;
			return true;
		}
		case "ex-info": {
			FCM.EXECTION_pathInfo_Color = c;
			return true;
		}
		case "ex": {
			FCM.loadExection_Color = c;
			return true;
		}
		case "mod": {
			FCM.loadMod_Color = c;
			return true;
		}
		}

		return false;
	}

	public Object printti() {
		SairCons.printTiInfos();
		return true;
	}

	public Object printcpr(String args) {
		if (args == null || "".equals(args)) {
			SairCons.removeAllPrintRunnable();
			return true;
		}
		SairCons.removePrintRunnable(args);
		return true;
	}
}
