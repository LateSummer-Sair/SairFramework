package sair.sys.gui.swing.tools;

import java.awt.Image;
import java.awt.Toolkit;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

import sair.LoaderManager;
import sair.SairLoader;
import sair.sys.Libraries;
import sair.sys.acticity.Exection;
import sair.user.Activity;

public class BufferedImageTool {

	public static final BufferedImageTool DefaultImageTool = new BufferedImageTool();
	private HashMap<String, Image> imageCache = new HashMap<String, Image>();
	private static Toolkit toolkit = Toolkit.getDefaultToolkit();

	public Image readImage_byBuffer(String filePathOrPackagePath) throws IOException {
		if (filePathOrPackagePath == null)
			return null;
		if (imageCache.containsKey(filePathOrPackagePath))
			return imageCache.get(filePathOrPackagePath);
		else
			return updateImage_byBuffer(filePathOrPackagePath);
	}

	public Image updateImage_byBuffer(String filePathOrPackagePath) throws IOException {
		if (filePathOrPackagePath == null)
			return null;
		else {
			Image flag = null;
			File file = new File(filePathOrPackagePath);
			if (file.exists())
				flag = readFile(file);
			else if (!file.exists())
				flag = readPackage(filePathOrPackagePath);
			if (flag != null) {
				imageCache.put(filePathOrPackagePath, flag);
				return flag;
			} else
				return null;
		}
	}

	public Image readImage_byBuffer(String filePathOrPackagePath, Activity activity) throws IOException {
		if (filePathOrPackagePath == null)
			return null;
		if (imageCache.containsKey(filePathOrPackagePath))
			return imageCache.get(filePathOrPackagePath);
		else
			return updateImage_byBuffer(filePathOrPackagePath, activity);
	}

	public Image updateImage_byBuffer(String filePathOrPackagePath, Activity activity) throws IOException {
		if (filePathOrPackagePath == null)
			return null;
		else {
			Image flag = null;
			File file = new File(filePathOrPackagePath);
			if (file.exists())
				flag = readFile(file);
			else if (!file.exists() && activity != null)
				flag = readPackage(filePathOrPackagePath, activity);
			if (flag != null) {
				imageCache.put(filePathOrPackagePath, flag);
				return flag;
			} else
				return null;
		}
	}

	public void clear() {
		HashMap<String, Image> local = removeAll();
		local.clear();
		local = null;
	}

	public HashMap<String, Image> removeAll() {
		HashMap<String, Image> local = imageCache;
		imageCache = null;
		imageCache = new HashMap<String, Image>();
		return local;
	}

	public static Image readFile(File file) throws IOException {
		return streamRead(new FileInputStream(file));
	}

	public static Image readPackage(String packagePath) throws IOException {
		return readPackage(packagePath, null);
	}

	public static Image readPackage(String packagePath, Activity activity) throws IOException {
		InputStream input = LoaderManager.systemLoader
				.getResourceAsStream(new StringBuilder(packagePath).deleteCharAt(0).toString());
		if (input == null) {
			input = LoaderManager.getModResStream(packagePath);
			if (input == null && activity != null) {
				Exection ect = Libraries.exections.get(activity);
				if (ect != null) {
					SairLoader loader = LoaderManager.ExecLoaders.get(ect.getURL());
					if (loader != null)
						input = loader.getResourceAsStream(packagePath);
				}
			}
		}
		if (input != null)
			return streamRead(input);
		else
			return null;
	}

	public static Image streamRead(InputStream input) throws IOException {
		if (input == null)
			return null;
		input = new BufferedInputStream(input, 262144);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		Image image = null;
		try {
			byte[] buffer = new byte[262144];
			int len = -1;
			while ((len = input.read(buffer)) >= 0)
				bos.write(buffer, 0, len);
			byte[] result = bos.toByteArray();
			image = toolkit.createImage(result);
		} catch (Exception e) {

		} finally {
			try {
				bos.close();
				input.close();
			} catch (Exception e) {
				throw new IOException();
			}
		}
		return image;
	}
}
