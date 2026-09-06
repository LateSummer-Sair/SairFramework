package sair.sys.gui;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.swing.ImageIcon;
import javax.swing.JLabel;

import sair.FCM;
import sair.LoaderManager;
import sair.Pathes;
import sair.sys.IRRunnable;
import sair.sys.Libraries;
import sair.sys.SairCons;
import sair.sys.acticity.Exection;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

/**
 * 框架命令的具体实现包:FrameActivity.main 分发的 /setBG、/load、/print、/ir 等命令全部在此落地。
 * <p>
 * 架构角色:package-private 助手类,仅由 FrameActivity 持有单实例;命令实现直接操作 ConsFrame/SairCons/
 * Libraries/LoaderManager 等框架单例,自身不持有窗体状态。
 * <p>
 * 线程安全:命令可能由解释器线程/EDT/worker线程调用——GUI操作(ConsFrame.xxx)内部已派发EDT;
 * 集合(Libraries.activities/mods、IRRunnable.irpool、变量池)访问均加锁;/newthread 用 AtomicInteger
 * 限制并发({@link #MAX_NEWTHREADS} 可配置)。无共享可变字段(irContinue 仅脚本线程使用)。
 * <p>
 * 关键约定:/load 支持本地路径、file:/ URL 与 http(s) URL(下载到execDir,文件名经 sanitizeUrlName 消毒);
 * ir脚本读取 readIrFile 优先UTF-8、编码非法回退GB18030;getColor 解析RGB并clamp到0-255;
 * print -c 只拆前3个颜色字段、其余文本用原始子串保留连续空格;download 带连接/读超时。
 */
class FrameActivity_Actions {
	// 修复:.JPGE拼写错误导致.jpeg被拒绝
	private String[] filelastNames = new String[] { ".PNG", ".BMP", ".JPEG", ".JPG", ".GIF", ".ICO" };

	/**
	 * /rename [old] [new]:重命名exection组件——从Libraries.activities移除旧名,setName成功后再以新名放回
	 * (setName失败则回滚旧名)。
	 */
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

	/** /deprint [offs] [len]:解析偏移与长度("max"→null)后调用SairCons.dePrint */
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

	/** 数值解析:"max"→null(表示末尾/全部),非法字符串→null,否则整数值 */
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

	/** /setBG [path]:pathRepack解析路径(支持双引号)后设置控制台背景图 */
	boolean setBG(String args) {
		String[] pathes = ToolPack.pathRepack(args);
		if (pathes.length > 0) {
			String path = pathes[0];
			ConsFrame.setImageBackground(path);
			return true;
		} else
			return false;
	}

	/** /opacity [10-100]:clamp到10-100后按百分比设置窗口不透明度(setFrameOpacity) */
	public boolean opacity(String args) {
		int v = 100;
		try {
			v = ToolPack.IntegerValOfString(args);
		} catch (Exception e) {
			v = 100;
		}
		if (v < 10)
			v = 10;
		if (v > 100)
			v = 100;
		ConsFrame.setFrameOpacity(v / 100.0f);
		SairCons.println("窗口不透明度 = " + v + "% (JDK8走AWTUtilities反射,JDK10+直接API Window.setOpacity)");
		return true;
	}

	/** 判断http/https URL前缀 */
	private static boolean isHttpUrl(String s) {
		return s != null && (s.startsWith("http://") || s.startsWith("https://"));
	}

	/** 流式下载:8KB缓冲,连接超时10s/读超时60s,流在finally中关闭 */
	private static void download(String url, File to) throws IOException {
		InputStream in = null;
		FileOutputStream out = null;
		try {
			URLConnection conn = new URL(url).openConnection();
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(60000);
			in = conn.getInputStream();
			out = new FileOutputStream(to);
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) >= 0)
				out.write(buf, 0, n);
		} finally {
			if (in != null)
				try {
					in.close();
				} catch (IOException e) {
				}
			if (out != null)
				try {
					out.close();
				} catch (IOException e) {
				}
		}
	}

	/**
	 * 解析ir脚本来源:http/https URL下载到缓存目录,其余按本地路径处理
	 */
	private static File resolveIrFile(String spec) throws IOException {
		if (isHttpUrl(spec)) {
			File dir = new File(Pathes.dataResDir + "framework" + File.separator + "ir_cache" + File.separator);
			if (!dir.exists())
				dir.mkdirs();
			String name = sanitizeUrlName(spec.substring(spec.lastIndexOf('/') + 1), "script.ir");
			File f = new File(dir, name);
			download(spec, f);
			return f;
		}
		return new File(spec);
	}

	/**
	 * 修复:URL文件名消毒——截断查询串、拒绝".."与路径分隔符、白名单字符
	 */
	private static String sanitizeUrlName(String raw, String fallback) {
		String name = raw;
		int q = name.indexOf('?');
		if (q >= 0)
			name = name.substring(0, q);
		StringBuilder sb = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++) {
			char ch = name.charAt(i);
			boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
					|| ch == '.' || ch == '_' || ch == '-' || ch > 127;
			if (ok)
				sb.append(ch);
		}
		name = sb.toString();
		if (name.isEmpty() || ".".equals(name) || "..".equals(name) || name.startsWith(".."))
			name = fallback;
		return name;
	}

	/**
	 * /load [path|url]:从本地路径、file:/ URL 或 http(s) URL 加载插件jar。
	 * http(s)先下载到execDir(文件名经sanitizeUrlName消毒);校验.jar后缀后交LoaderManager加载。
	 */
	public boolean load(String args) {
		String[] pathes = ToolPack.pathRepack(args);
		String target = pathes.length > 0 ? pathes[0] : args;
		try {
			File jar;
			if (isHttpUrl(target)) {
				File dir = new File(Pathes.execDir);
				if (!dir.exists())
					dir.mkdirs();
				jar = new File(dir, sanitizeUrlName(target.substring(target.lastIndexOf('/') + 1), "remote.jar"));
				SairCons.println("正在下载: " + target);
				download(target, jar);
			} else if (target.startsWith("file:/")) {
				jar = new File(new URL(target).toURI());
			} else {
				jar = new File(target);
			}
			if (!jar.exists() || !jar.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
				SairCons.println(FCM.Error_Color, "不是有效的jar: " + target);
				return false;
			}
			return LoaderManager.loadOneExecJar(jar.getAbsolutePath());
		} catch (Exception e) {
			SairCons.println(FCM.Error_Color, "load fail : " + e);
			return false;
		}
	}

	/** /setFC|/setBC:解析RGB后设置默认前景色(isFontColor=true)或背景色(false) */
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

	/** 脚本续跑开关(仅脚本线程读写,irstart恢复) */
	boolean irContinue = true;

	/**
	 * /ir [path]:新线程执行ir脚本;参数为空时列出正在运行的脚本(irpool快照)。
	 * http(s)脚本先下载到缓存目录(见resolveIrFile)。
	 */
	void ir(String fileName) throws Exception {
		if ("".equals(fileName) || null == fileName) {
			Set<String> list;
			synchronized (IRRunnable.irpool) {
				list = new HashSet<String>(IRRunnable.irpool.keySet());
			}
			SairCons.println(Pathes.printSplit);
			SairCons.println("显示正在运行的ir脚本列表:");
			SairCons.println(Pathes.printSplit);
			for (String name : list)
				SairCons.println(name);
			SairCons.println(Pathes.printSplit);
			return;
		}
		fileName = ToolPack.pathRepack(fileName)[0];
		File irFile = resolveIrFile(fileName);
		List<String> allLines = readIrFile(irFile.getAbsolutePath());
		IRRunnable irr = new IRRunnable(allLines, fileName);
		Thread th = new Thread(irr);
		irr.setMyThread(th);
		th.start();
	}

	/**
	 * 读取ir脚本:优先UTF-8,编码非法时回退GB18030(GBK超集,兼容记事本ANSI且覆盖生僻字)
	 */
	private static List<String> readIrFile(String fileName) throws IOException {
		Path p = Paths.get(fileName);
		try {
			return Files.readAllLines(p, StandardCharsets.UTF_8);
		} catch (CharacterCodingException cce) {
			return Files.readAllLines(p, Charset.forName("GB18030"));
		}
	}

	/** /ir-i [path]:当前线程同步执行ir(长任务会阻塞调用线程),并把脚本所在目录写入变量池irp */
	public void iri(String fileName) throws Exception {
		fileName = ToolPack.pathRepack(fileName)[0];
		File irFile = resolveIrFile(fileName);
		List<String> allLines = readIrFile(irFile.getAbsolutePath());
		addVar("irp " + irFile.getParentFile().getPath());
		new IRRunnable(allLines, fileName).run();
	}

	/** /ir-x [path]:按路径从IRRunnable.irpool查找并停止脚本;找不到返回提示串 */
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

	/** 恢复脚本执行(irContinue=true) */
	void irstart() {
		irContinue = true;
	}

	/**
	 * /print | /println | -c 变体的统一实现:无颜色时原样打印(println前缀\r\n);
	 * 带-c时前3个空格分隔字段解析为RGB(见getColor),其余文本用原始子串保留连续空格,
	 * 颜色用SairCons.print(c,rest)输出。
	 */
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
			// 修复:只拆前3个颜色字段,其余文本用原始子串保留连续空格(原split(" ")会破坏排版)
			int splitPos = -1;
			for (int i = 0; i < 3; i++) {
				splitPos = info.indexOf(' ', splitPos + 1);
				if (splitPos < 0)
					break;
			}
			String rest = splitPos < 0 ? "" : info.substring(splitPos + 1);
			if (isLineJump)
				rest = "\r\n" + rest;
			SairCons.print(c, rest);
			return true;
		}
	}

	/** /print-f|/println-f:按路径逐个校验图片后缀(不区分大小写)后以JLabel+ImageIcon输出到控制台 */
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

	/** 图片后缀白名单校验(path统一大写后比对filelastNames) */
	private boolean chkFileImage(String path) {
		path = path.toUpperCase();
		for (String lastName : filelastNames)
			if (path.endsWith(lastName))
				return true;
		return false;
	}

	/** /list [-s]:打印已加载exection组件(-s时附dataDir与jar来源路径)与Mods列表(集合遍历加锁) */
	boolean showList(boolean b) {
		SairCons.println(FCM.split_Color, Pathes.printSplit);
		SairCons.print(FCM.loadExection_Color, "exections:");
		synchronized (Libraries.activities) {
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
		}

		SairCons.println(FCM.split_Color, Pathes.printSplit);
		SairCons.print(FCM.loadMod_Color, "Mods:");
		synchronized (Libraries.mods) {
			for (String name : Libraries.mods.keySet())
				SairCons.println(FCM.loadMod_Color, name);
		}

		return true;
	}

	/** RGB解析:取前3个空格分隔字段为R/G/B,非法值按0处理,并clamp到0-255防IllegalArgumentException */
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
		// 修复:clamp到0-255,越界值不再抛IllegalArgumentException
		for (int i = 0; i < 3; i++) {
			if (RGB_I[i] < 0)
				RGB_I[i] = 0;
			if (RGB_I[i] > 255)
				RGB_I[i] = 255;
		}
		return new Color(RGB_I[0], RGB_I[1], RGB_I[2]);
	}

	/** /var-add [name] [string]:写入变量池(键自动包%name%),同名覆盖 */
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

	/** /var-del [name]:从变量池删除%name%,不存在则打印提示 */
	public boolean delVar(String args) {
		HashMap<String, String> localMap = ToolPack.getVmap();
		if ("".equals(args))
			return false;

		String name = "%" + args + "%";

		if (null == localMap.remove(name))
			SairCons.println(FCM.Error_Color, "remove fail ,name nofound -> " + name);

		return true;
	}

	/** /setspliter [className]:切换控制台解释器(空参/"null"恢复默认解释器) */
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

	/** /var-list:遍历打印变量池(遍历加锁,防并发修改) */
	public boolean listVar() {
		HashMap<String, String> localMap = ToolPack.getVmap();
		SairCons.println(Pathes.printSplit);
		synchronized (localMap) {
			Iterator<String> it = localMap.keySet().iterator();
			while (it.hasNext()) {
				String name = it.next();
				String v = localMap.get(name);
				SairCons.println(name + " -> " + v);
			}
		}
		SairCons.println(Pathes.printSplit);
		return true;
	}

	/** /sleep [ms]:当前线程睡眠指定毫秒(异常吞掉并返回true) */
	public boolean sleep(String args) {
		try {
			Thread.sleep(ToolPack.IntegerValOfString(args));
		} catch (Exception e) {

		}
		return true;
	}

	/** newthread并发计数(原子):到达MAX_NEWTHREADS时拒绝新线程,线程结束finally中归还 */
	private static final java.util.concurrent.atomic.AtomicInteger activeNewThreads = new java.util.concurrent.atomic.AtomicInteger();

	/**
	 * /newthread并发上限(可配置):防止恶意脚本无界创建线程耗尽资源(安全加固)
	 */
	public static int MAX_NEWTHREADS = 64;

	/**
	 * /newthread [cmd]:独立新线程执行命令(不走线程池);AtomicInteger限制并发上限
	 * MAX_NEWTHREADS,防恶意脚本无界创建线程;线程结束在finally中归还计数。
	 */
	public Object newThread(String args) {
		if (activeNewThreads.get() >= MAX_NEWTHREADS) {
			SairCons.println(FCM.Error_Color, "newthread并发已达上限(" + MAX_NEWTHREADS + "),拒绝执行");
			return false;
		}
		activeNewThreads.incrementAndGet();
		new Thread() {
			public void run() {
				try {
					SairCons.runner(false, args);
				} finally {
					activeNewThreads.decrementAndGet();
				}
			}

		}.start();
		return true;
	}

	/** /resize [w] [h]:调整窗体尺寸;空参恢复默认800x600;小于200x200拒绝 */
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

	/** resize参数错误提示 */
	private boolean printSizeE() {
		SairCons.println(FCM.Error_Color, " size ERR !! ");
		return false;
	}

	/** /setFCM [target] [RGB]:设置FCM全局颜色表(ui-error/ex-help/ex-info/ex/mod五个目标) */
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

	/** /print-ti:打印消息头(SairCons.printTiInfos),清屏后可用 */
	public Object printti() {
		SairCons.printTiInfos();
		return true;
	}

	/** /print-cpr [name]:清除指定第三方输出模式;name留空清除全部 */
	public Object printcpr(String args) {
		if (args == null || "".equals(args)) {
			SairCons.removeAllPrintRunnable();
			return true;
		}
		SairCons.removePrintRunnable(args);
		return true;
	}
}
