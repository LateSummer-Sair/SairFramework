package sair;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import sair.sys.SairCons;
import sair.sys.acticity.Exection;
import sair.sys.acticity.Mod;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

public class LoaderManager {

	public final static ClassLoader systemLoader = ClassLoader.getSystemClassLoader(), loader = new SairLoader();

	public final static HashMap<URL, SairLoader> ExecLoaders = new HashMap<URL, SairLoader>();

	public final static HashSet<String> libJarPathSet = new HashSet<String>();

	public final static HashSet<String> execJarPathSet = new HashSet<String>();

	final static boolean loadBootJar() throws NoSuchMethodException, SecurityException, ClassNotFoundException,
			IllegalAccessException, IllegalArgumentException, InvocationTargetException, MalformedURLException {
		File dirFilePath = new File(Pathes.bootDir);
		if (!dirFilePath.exists())
			dirFilePath.mkdirs();
		Method m = Class.forName("java.net.URLClassLoader").getDeclaredMethod("addURL", new Class[] { URL.class });
		m.setAccessible(true);
		ArrayList<String> bootJars = ToolPack.getAllFilesPath(dirFilePath, true);
		for (String name : bootJars)
			if (name.endsWith(".jar")) {
				m.invoke(systemLoader, new File(name).toURI().toURL());
				SairCons.println(FCM.loadBoot_Color, "bootReaded : " + name);
			}
		return true;
	}

	public static boolean loadExecJar(String filePath) throws IOException {

		if (!execJarPathSet.contains(filePath)) {

			File file = new File(filePath);
			URL url = file.toURI().toURL();
			ExectionLoader exel = new ExectionLoader();
			exel.addJarFiles(file);

			ExecLoaders.put(url, exel);
			execJarPathSet.add(filePath);

			return true;

		}

		return false;

	}

	public final static boolean loadLibJar(String filePath) throws IOException {

		if (!libJarPathSet.contains(filePath)) {

			File file = new File(filePath);

			((SairLoader) loader).addJarFiles(file);

			libJarPathSet.add(filePath);

			return true;

		}

		return false;
	}

	public static Activity loadMain(String className, ClassLoader loader)
			throws ClassNotFoundException, InstantiationException, IllegalAccessException, NoClassDefFoundError {

		Class<?> clazz = loader.loadClass(className);

		if (Activity.class.isAssignableFrom(clazz)) {

			Activity acti = (Activity) (clazz.newInstance());

			return acti;

		} else
			throw new ClassNotFoundException("Class File Error£¡is not Activity£¡");

	}

	static String[] getJarMainInfoMation(String jarPath) {
		String infomations = ToolPack.getExeMain(jarPath);
		if (infomations == null)
			return null;
		String[] sp_ed = infomations.split(";");
		ArrayList<String> localList = new ArrayList<String>();
		if (sp_ed.length > 0) {
			for (String clazzName : sp_ed)
				if (clazzName != null && !"".equals(clazzName) && clazzName.length() > 0)
					localList.add(clazzName);
		} else
			return null;
		return localList.toArray(new String[localList.size()]);
	}

	static void loadExec() {
		load(Exection.class, Pathes.execDir);
	}

	static void loadMod() {
		load(Mod.class, Pathes.modDir);
	}

	private static void load(Class<?> clazz, String path) {

		File path_File = new File(path);

		if (!path_File.exists())
			path_File.mkdirs();

		ArrayList<String> paths = ToolPack.getAllFilesPath(path_File, true);

		for (String p : paths) {
			if (p.endsWith(".jar")) {
				if (Exection.class == clazz)
					toExec(p);
				else
					toMod(p);
			}
		}
	}

	private static void toExec(String path) {
		String[] classNames = LoaderManager.getJarMainInfoMation(path);
		if (classNames == null) {
			SairCons.println(FCM.Error_Color, path + " -> ACT_infomations notFound!");
			return;
		}
		try {
			new Exection(classNames, path);
		} catch (Exception e) {
			SairCons.println(FCM.Error_Color, path + " -> ACT_infomations or ClassInfo is Error!");
		}

	}

	private static void toMod(String path) {
		String dirs = null;
		String name = null;
		try {
			name = ToolPack.getAttributes("name".toUpperCase(), path);
			dirs = ToolPack.getAttributes("dir".toUpperCase(), path);
		} catch (Exception e) {
		}
		if (null != dirs && null != name)
			SairLoader.mainMap.put(name, dirs);
		try {
			new Mod(path);
		} catch (IOException e) {
			SairCons.println(FCM.Error_Color, e.getMessage());
		}

	}

	public static InputStream getModResStream(String classPathInJar, SairLoader loader) {
		InputStream resURL = null;

		Collection<File> ul = null;
		classPathInJar = new StringBuilder(classPathInJar).deleteCharAt(0).toString();
		try {
			ul = loader.getAllJarFile();
		} catch (SecurityException | IllegalArgumentException e) {
			SairCons.print(FCM.Error_Color, "CLASSLOASDER IS ERROR!!!");
			return null;
		}
		for (File file : ul) {
			try {
				JarFile jf = loader.jars.get(file);
				ZipEntry ze = jf.getEntry(classPathInJar);
				if (ze != null)
					resURL = jf.getInputStream(ze);
				if (resURL != null)
					return resURL;
			} catch (IOException e) {
			}
		}
		return null;
	}

	public static InputStream getModResStream(String classPathInJar) {
		return getModResStream(classPathInJar, (SairLoader) loader);
	}

}
