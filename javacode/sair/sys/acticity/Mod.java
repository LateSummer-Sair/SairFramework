package sair.sys.acticity;

import java.io.File;
import java.io.IOException;

import sair.FCM;
import sair.LoaderManager;
import sair.Pathes;
import sair.SairLoader;
import sair.sys.Libraries;
import sair.sys.SairCons;

public class Mod extends Acti {

    public Mod(String path) throws IOException {
        super(path);
        this.loadJar();
        this.initMod();
    }

    private void loadJar() throws IOException {
        if (this.exists == true)
            LoaderManager.loadLibJar(this.path);
    }

    private void initMod() {
        Libraries.mods.put(this.getPath(), this);
        SairCons.println(FCM.loadMod_Color, "loaded MODS : " + this.getPath());
    }

    public void unLoadJar() throws Exception {
        Libraries.mods.remove(this.getPath());
        SairCons.println(FCM.Error_Color, Pathes.printSplit);
        SairCons.println(FCM.Error_Color, "unload MODS : " + this.getPath());
        this.unLoadJar0();
    }

    private void unLoadJar0() throws Exception {
        File file = new File(path);
        SairLoader l = ((SairLoader) LoaderManager.loader);
		l .removeJarFiles(file);
		LoaderManager.libJarPathSet.remove(this.path);
    }

}