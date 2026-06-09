package sair.sys;

import java.io.File;
import java.util.ArrayList;

import sair.Main;
import sair.Pathes;
import sair.sys.tools.ToolPack;

public class CJDK {

	public static final String version = System.getProperty("java.version");
	public static final String javaPath = System.getProperty("java.home");
	public static final String sysName = System.getProperty("os.name");

	private static Boolean flag = null;

	public final static boolean isJava8() {
		if (flag != null)
			return flag;
		else {
			String lv = version;
			flag = false;
			if (lv == null)
				return flag;
			if (lv.contains("_"))
				lv = lv.split("_")[0];
			if (lv.contains("."))
				lv = String.format("%.1f", (Double.parseDouble(mod(lv))));
			if ("1.8".equals(lv))
				flag = true;
			else
				flag = false;
		}
		return flag;
	}

	private static String mod(String lv) {
		boolean isFindPoint = false;
		StringBuilder sb = new StringBuilder();
		char[] cs = lv.toCharArray();
		for (char c : cs) {
			if (!isFindPoint || c != '.')
				sb.append(c);
			if (c == '.')
				isFindPoint = true;
		}
		return sb.toString();
	}

	public static String toCMDreStart(String[] args) {
		StringBuilder arg = new StringBuilder(makeJavaPath());
		arg.append("java" + makeExe());
		arg.append(" -Xbootclasspath/a:");
		ArrayList<String> bootJars = ToolPack.getAllFilesPath(new File(Pathes.bootDir), true);
		for (String name : bootJars)
			if (name.endsWith(".jar")) {
				arg.append(makeName(false, name)).append(File.pathSeparator);
			}

		arg.append(". ");
		arg.append("-jar ");
		arg.append(myPath());
		arg.append(" unboot ");
		if (args != null) {
			for (String s : args)
				arg.append(s).append(' ');
			arg.deleteCharAt(arg.length() - 1);
		}
		String oldArgs = arg.toString();
		return oldArgs;
	}

	private static String makeExe() {
		if (isWindows())
			return "w.exe";
		else
			return "";
	}

	private static String makeJavaPath() {
		return new StringBuilder(javaPath).append(File.separator).append("bin").append(File.separator).toString();
	}

	private static String makeName(boolean isSFW, String name) {
		StringBuilder sb = new StringBuilder();
		char[] cs = name.toCharArray();
		for (int i = cs.length - 1; i >= 0; i--) {
			sb.insert(0, cs[i]);
			if ('\\' == cs[i] || '/' == cs[i])
				break;
		}
		if (isSFW) {
			if (sb.length() > 0)
				sb.deleteCharAt(0);
			if (sb.length() == 0)
				sb.append("SFW.jar");
			return sb.toString();
		}
		sb.insert(0, "bootlib");
		sb.insert(0, File.separatorChar);
		sb.insert(0, "plugins");
		sb.insert(0, File.separatorChar);
		sb.insert(0, '.');
		return sb.toString();
	}

	private static String myPath() {
		return makeName(true, Main.class.getProtectionDomain().getCodeSource().getLocation().getFile());
	}

	public static boolean isWindows() {
		return sysName.toString().toUpperCase().indexOf("WINDOWS") >= 0;
	}
}
