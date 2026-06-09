package sair;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureClassLoader;
import java.util.HashMap;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class SairBaseLoader extends SecureClassLoader {

	protected HashMap<File, JarFile> jars = new HashMap<File, JarFile>();

	protected SairBaseLoader(ClassLoader p) {
		super(p);
	}

	protected SairBaseLoader() {
		super(ClassLoader.getSystemClassLoader());
	}

	protected void addJarFile(File file) throws IOException {
		if (file != null) {
			if (file.exists() && file.getAbsolutePath().toLowerCase().endsWith(".jar")) {
				// URL url = file.toURI().toURL();
				jars.put(file, new JarFile(file));
			} else
				throw new IOException("is not JAR File!!!");
		}
	}

	protected JarFile removeJarURL(File file) {
		/*
		 * if (inputs != null) return linkedMap.remove(url);
		 */
		try {
			JarFile jar = jars.remove(file);
			if (jar != null)
				jar.close();
			return jar;
		} catch (Exception e) {
		}
		return null;
	}

	protected Class<?> findClass(String name) throws ClassNotFoundException {
		String classPath = name.replace(".", "/").concat(".class");
		ByteArrayOutputStream fos = new ByteArrayOutputStream();
		byte[] b;
		int code;
		Set<File> set = jars.keySet();
		try {
			for (File file : set) {
				InputStream fis = null;
				try {
					// URL urlc = new URL("jar:" + url + "!/" + classPath);
					JarFile jf = jars.get(file);
					if (jf == null)
						continue;
					ZipEntry ze = jf.getEntry(classPath);
					if (ze == null)
						continue;
					fis = new BufferedInputStream(jf.getInputStream(ze), 81920);
				} catch (IOException e) {
					continue;
				}
				byte[] cb = new byte[81920];
				while ((code = fis.read(cb)) >= 0)
					fos.write(cb, 0, code);
				fis.close();
				b = fos.toByteArray();
				fos.close();
				return defineClass(name, b, 0, b.length);
			}
		} catch (IOException e) {
			throw new ClassNotFoundException("URL read fail !!!");
		}
		return null;
	}
}
