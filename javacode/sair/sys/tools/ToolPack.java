package sair.sys.tools;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.FCM;
import sair.LoaderManager;
import sair.sys.SairCons;
import sair.user.Activity;
import sair.user.SpliterSPI;

public final class ToolPack {

    private static String localPath;

    /**
     * 获取当前jar/class的完整路径(兼容Linux但不适用于安卓)
     *
     * @return String 类型
     * @throws UnsupportedEncodingException
     */
    public final static String getPath() {
        if (localPath != null)
            return localPath;

        String filePath = null;

        filePath = ToolPack.class.getProtectionDomain().getCodeSource().getLocation().getPath().replaceAll("%20", " ");

        if (filePath.endsWith(".jar"))
            filePath = filePath.substring(0, filePath.lastIndexOf("/") + 1);

        return (localPath = new File(filePath).getAbsolutePath());
    }

    public static final ArrayList<String> getAllFilesPath(File local, boolean b) {
        return getAllFilesPath(local, b, 0);
    }

    public static final ArrayList<String> getAllFilesPath(File file, boolean isAll, int offset) {
        ArrayList<String> result = new ArrayList<String>();
        getAllFilesPath0(result, file, isAll, offset);
        return result;
    }

    private static final void getAllFilesPath0(ArrayList<String> result, File file, boolean isAll, int offset) {
        if (offset > 0)
            result.add(file.getPath());
        if ((file.isDirectory() && isAll) || (file.isDirectory() && offset == 0)) {
            File[] documentArr = file.listFiles();
            if (documentArr != null)
                for (File document : documentArr)
                    getAllFilesPath0(result, document, isAll, offset + 1);
        }
    }

    public static final String[] pathRepack(String path) {
        ArrayList<String> result = new ArrayList<String>();

        Pattern p = Pattern.compile("\"(.*?)\"");
        Matcher m = p.matcher(path);

        while (m.find()) {
            StringBuffer oe = new StringBuffer(m.group());
            if (oe.length() >= 2) {
                oe.deleteCharAt(oe.length() - 1).deleteCharAt(0);
                if (oe.length() > 0) {
                    if ('.' == oe.charAt(0))
                        oe.deleteCharAt(0).insert(0, getPath());
                    result.add(oe.toString());
                }
            } else
                continue;
        }

        if (result.size() <= 0)
            return new String[]{path};

        return result.toArray(new String[result.size()]);
    }

    /**
     * 获得此jar内的MF文件中的JMD值</br>
     * 没有JMD一行则会返回空
     *
     * @param path jar所在的路径
     * @return String -info返回值
     **/
    public static String getExeMain(String path) {
        return getAttributes("act", path);
    }

    /**
     * 获得此jar内的MF文件中的name值</br>
     * 没有name一行则会返回空
     *
     * @param path jar所在的路径
     * @param name 条目名称
     * @return String -info返回值
     **/
    public static String getAttributes(String name, String path) {
        Attributes ab = getAttributes(path);
        if (ab == null)
            return null;
        return ab.getValue(name.toUpperCase());
    }

    private static Attributes getAttributes(String path) {
        JarFile jar = null;
        try {
            jar = new JarFile(path);
        } catch (IOException e) {
            SairCons.println(FCM.Error_Color, e.getMessage());
        }
        if (jar == null)
            return null;
        //
        Manifest mf = null;
        try {
            mf = jar.getManifest();
        } catch (IOException e) {
            SairCons.println(FCM.Error_Color, e.getMessage());
        } finally {
            if (jar != null)
                try {
                    jar.close();
                } catch (IOException e) {
                    SairCons.println(FCM.Error_Color, e.getMessage());
                }
        }
        if (mf == null)
            return null;
        //
        return mf.getMainAttributes();
    }

    public static Spliter findSplited(String cmd) {
        if (SairCons.SpliterSpiManager == null)
            return new SystemSpliter(cmd);
        else
            return SairCons.SpliterSpiManager.getSpliter(cmd);
    }

    public static boolean setSpliter(String args) throws Exception {
        boolean result = false;

        Class<?> clazz = Class.forName(args, false, LoaderManager.loader);
        Object o = clazz.newInstance();
        SpliterSPI spi = (SpliterSPI) o;

        if (spi.chkToInstall())
            SairCons.SpliterSpiManager = (SpliterSPI) o;

        result = true;
        return result;
    }

    public static Integer IntegerValOfString(String string) throws Exception {
        try {
            return Integer.valueOf(Pattern.compile("[^0-9]").matcher(string).replaceAll("").trim());
        } catch (NumberFormatException e) {
            throw e;
        }
    }

    public static boolean SpliterChkUninstall(String cmd) {
        if (SairCons.SpliterSpiManager != null && cmd.equals(SairCons.SpliterSpiManager.getUninstallCMD())) {
            SairCons.SpliterSpiManager.unInstall();
            if (SairCons.SpliterSpiManager != null)
                SairCons.SpliterSpiManager = null;
            return true;
        } else
            return false;

    }

    public static HashMap<String, String> getVmap() {
        return SystemSpliter.vmap;
    }

    public static String reArg(String[] argSplited, Integer[] its) {
        StringBuffer local = new StringBuffer();
        Set<Integer> itgSet = new HashSet<Integer>();
        for (int i : its)
            itgSet.add(i);

        for (int i = 0; i < argSplited.length; i++) {
            if (itgSet.contains(i))
                continue;
            else {
                local.append(argSplited[i]);
                if (i < argSplited.length - 1)
                    local.append(' ');
            }
        }
        return local.toString();
    }

    public static String chkArgsV(String arg) {
        return SystemSpliter.chk_replace(arg);
    }

    public static Object toSystemSpliter_ofunc(Spliter sp, Activity localActivity) {
        if (sp instanceof SystemSpliter)
            return localActivity.o_funcMain(((SystemSpliter) sp).getO());
        else
            return null;
    }
}
