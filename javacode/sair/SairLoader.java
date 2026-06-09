package sair;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;

public class SairLoader extends SairBaseLoader {

	static final HashMap<String, String> mainMap = new HashMap<String, String>();

	protected SairLoader(ClassLoader p) {
		super(p);
	}

	protected SairLoader() {
		super(ClassLoader.getSystemClassLoader());
	}

	public static String getMOD_MEAT_INFFILE(String name) {
		return mainMap.get(name);
	}

	public final void addJarFiles(File... paths) throws IOException {
		if (paths == null || paths.length == 0)
			return;

		for (File p : paths)
			if (p != null)
				super.addJarFile(p);

	}

	public final void removeJarFiles(File... urls) {
		if (urls == null || urls.length == 0)
			return;
		for (File u : urls)
			if (u != null)
				super.removeJarURL(u);
	}

	public final Collection<File> getAllJarFile() {
		return jars.keySet();
	}

	public final void dispose() {
		Set<File> set = jars.keySet();
		if (set.size() > 0)
			removeJarFiles(set.toArray(new File[set.size()]));
	}

}
