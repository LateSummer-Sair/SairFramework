package sair;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

import sair.sys.CJDK;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

public final class Main {

	public static final String Version = "0.5.3";

	private static boolean isLoaded = false;

	private static void restart(String flag) {
		String path = ToolPack.getPath();
		File file = new File(path);
		Process p = CMDTool.executeCommand(flag, file);
		if (p == null)
			SairCons.println(Color.RED, "执行命令出现异常!请手动执行!");
		else
			System.exit(0);
	}

	public static void main(String[] args) {
		//System.out.println(ToolPack.getPath());
		if (!CJDK.isJava8()) {
			if ((args == null) || (args != null && args.length == 0)
					|| (args != null && args.length > 0 && !"unboot".equals(args[0]))) {
				String flag = CJDK.toCMDreStart(args);
				ConsFrame.showFrame();
				SairCons.printTiInfos();
				SairCons.println(Color.RED, "你使用的是比Java8更高的版本运行,即将使用以下命令重启SFW:");
				SairCons.println(Pathes.printSplit);
				SairCons.println(Color.RED, flag);
				SairCons.println(Pathes.printSplit);
				restart(flag);
			} else
				main0(args);
		} else
			main0(args);
	}

	private static void main0(String[] args) {
		if (!isLoaded)
			try {
				firstLoad(args);
			} catch (Exception e) {
				SairCons.println(FCM.Error_Color, e.getMessage());
			}
	}

	private static void firstLoad(String[] args) throws Exception {
		ConsFrame.showFrame();
		SairCons.printTiInfos();
		args = load(args);

		try {
			Thread.sleep(10L);
			autoRun(args);
		} catch (IOException e) {
			SairCons.println(FCM.Error_Color, "createNewIrFile Error!");
		}
		isLoaded = true;
	}

	private static String[] load(String[] args) {
		if (args != null && args.length > 0 && "unboot".equals(args[0])) {
			List<String> list = new ArrayList<String>();
			for (String a : args)
				list.add(a);
			list.remove(0);
			if (list.size() > 0)
				args = list.toArray(new String[list.size()]);
			else
				args = new String[] {};

		} else {
			try {
				LoaderManager.loadBootJar();
			} catch (NoSuchMethodException | SecurityException | ClassNotFoundException | IllegalAccessException
					| IllegalArgumentException | InvocationTargetException | MalformedURLException e1) {
				SairCons.println(FCM.Error_Color, "BootJarFile read fail !!!");
			}
		}

		LoaderManager.loadMod();
		LoaderManager.loadExec();

		return args;
	}

	private static void autoRun(String[] args) throws IOException {
		if (args == null)
			return;
		String path = null;
		if (args.length <= 0) {
			path = ConsFrame.fa.getDataDir() + "autorun.ir";
			File file = new File(path);
			if (!file.exists())
				file.createNewFile();
		} else {
			path = argsFactory(args);
		}
		path = ToolPack.pathRepack(path)[0];
		path = "\"" + path + "\"";
		SairCons.runner(false, "/ir " + path);
	}

	private static String argsFactory(String[] args) {
		StringBuffer sbf = new StringBuffer();
		for (String str : args)
			sbf.append(str).append(' ');
		sbf.deleteCharAt(sbf.length() - 1);
		return sbf.toString();
	}

	public static Object toTest(Activity testActivity, String funcName, String args) {
		main0(null);
		return SairCons.toActiRun(testActivity, funcName, args);
	}

}
