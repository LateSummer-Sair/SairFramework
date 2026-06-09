package sair.sys.acticity;

import java.io.IOException;
import java.util.ArrayList;

import sair.FCM;
import sair.LoaderManager;
import sair.Pathes;
import sair.SairLoader;
import sair.sys.Libraries;
import sair.sys.SairCons;
import sair.user.Activity;

public class Exection extends Acti {

	private String[] classNames;
	private ArrayList<Activity> actiList = new ArrayList<Activity>();
	private SairLoader loader;

	public Exection(String[] classNames, String path)
			throws ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
		super(path);
		this.loadJar();
		this.classNames = classNames;
		this.initExec();
	}

	private void loadJar() throws IOException {
		if (this.exists == true) {
			LoaderManager.loadExecJar(this.path);
			loader = LoaderManager.ExecLoaders.get(this.getURL());
		}
	}

	private void initExec() throws ClassNotFoundException, InstantiationException, IllegalAccessException {

		if (classNames != null) {

			for (String className : classNames) {
				Activity result = LoaderManager.loadMain(className, loader);
				String name = Libraries.setActivityName(this.getPath());
				try {
					result.setName(name);
				} catch (Exception e) {
					SairCons.println(FCM.Error_Color, e.getMessage());
				}
				Libraries.activities.put(name, result);
				Libraries.exections.put(result, this);
				actiList.add(result);
				SairCons.println(FCM.loadExection_Color, "loaded EXECTIONS : " + this.getPath() + " --> " + className);
			}

		}
	}

	public String[] getClassNames() {
		return classNames;
	}

	public void unLoadJar() throws Exception {
		SairCons.println(FCM.Error_Color, Pathes.printSplit);
		for (Activity acti : actiList) {
			String name = acti.getName();
			acti = Libraries.activities.remove(name);
			Exection exec = Libraries.exections.remove(acti);
			if (acti != null) {
				SairCons.println(FCM.Error_Color, "unload EXECTIONS-ACTI : " + name);
				acti.exit();
				acti.close();
			}
			if (exec != null)
				SairCons.println(FCM.Error_Color, "unload EXECTIONS-EXEC : " + name);
		}
		this.unLoadJar0();
	}

	private void unLoadJar0() throws Exception {
		loader.dispose();
		LoaderManager.execJarPathSet.remove(this.path);
	}
}
