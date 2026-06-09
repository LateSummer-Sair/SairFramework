package sair.user;

import java.awt.Color;
import java.io.File;
import java.util.HashMap;

import sair.FCM;
import sair.Pathes;
import sair.sys.Libraries;
import sair.sys.SairCons;

public abstract class Activity implements UserRunnable {
    private String dataPath;
    private String name;
    private boolean isOpen = true;
    private HashMap<String, String> functionOrderMap = new HashMap<String, String>();

    public final boolean containsOrderName(String name) {
        return functionOrderMap.containsKey(name);
    }

    public final void putOrderName(String newName, String funcName) {
        functionOrderMap.put(newName, funcName);
        SairCons.println(Color.RED, getName() + " funcName : [" + funcName + "] --> [" + newName + "]");
    }

    public final String getOldOrderName(String newOrderName) {
        return functionOrderMap.get(newOrderName);
    }

    public final void removeOrderName(String name) {
        name = functionOrderMap.remove(name);
        if (null != name)
            SairCons.println(Color.RED, name + " is removed");
    }

    public final void close() {
        isOpen = false;
        if (name == null)
            name = "SFW";
        SairCons.println(Color.RED, name + " is closed input cmd");
    }

    public final void open() {
        isOpen = true;
        if (name == null)
            name = "SFW";
        SairCons.println(Color.GREEN, name + " is opened input cmd");
    }

    public final boolean isOpen() {
        return isOpen;
    }

    public final String getName() {
        if (this.name == null)
            return "test";
        return this.name;
    }

    public final void setName(String name) throws Exception {
        boolean isHasName = Libraries.activities.containsKey(name);
        if (isHasName)
            throw new Exception("Early has:" + name + " in Libraries");
        else
            this.name = name;
    }

    public final String getDataDir() {

        if (dataPath != null)
            return dataPath;

        String path = dataDir();

        if (path == null) {
            path = this.getClass().getName();
        }

        dataPath = Pathes.dataResDir + path + File.separator;
        File file = new File(dataPath);
        if (!file.exists())
            file.mkdirs();

        return dataPath;
    }

    // #--UserRunnable
    public Object o_funcMain(Object o) {
        SairCons.println(FCM.Error_Color, name + " is not implements ofunc");
        return o;
    }

    // #--UserRunnable
    protected String dataDir() {
        return null;
    }
}
