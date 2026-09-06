package sair.sys.tools;

import java.io.File;
import java.io.IOException;
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

/**
 * 框架工具集:路径定位、目录遍历、MF 清单读取、命令解析器(SPI)安装/卸载、
 * 参数重组等公共函数集合。
 * <p>
 * 架构角色:命令解释与插件装载的辅助层——findSplited 是 SairCons 解析命令的必经之路
 * (未安装 SPI 时回退 SystemSpliter),setSpliter/SpliterChkUninstall 管理全局
 * SairCons.SpliterSpiManager 的生命周期,getPath 为 plugins/data 目录定位提供基准。
 * <p>
 * 线程安全说明:本类无实例状态(localPath 为惰性缓存,重复计算幂等,引用写入原子);
 * findSplited/setSpliter 对 SairCons.SpliterSpiManager(volatile)的读写无锁,
 * 卸载命令匹配存在"先判后摘"窗口,框架按单命令线程约束使用。
 * <p>
 * 二进制兼容约束:全部方法为 public static(部分 final),签名与返回类型
 * (含 getVmap 返回 HashMap&lt;String,String&gt; 内部引用)不可变更;
 * getVmap 返回的是 SystemSpliter.vmap 本体,调用方可直接增删变量,属既定公开行为。
 */
public final class ToolPack {

    /**
     * getPath 的惰性缓存(null 表示未计算)。
     */
    private static String localPath;

    /**
     * 双引号包裹路径的提取模式("..."),pathRepack 使用。
     */
    private static final Pattern PATH_PATTERN = Pattern.compile("\"(.*?)\"");

    /**
     * 非数字字符剥离模式,IntegerValOfString 使用。
     */
    private static final Pattern NUM_PATTERN = Pattern.compile("[^0-9]");

    /**
     * 获取当前 jar/class 的完整路径(兼容 Linux,但不适用于安卓),结果惰性缓存。
     * <p>定位策略:优先取本类 codeSource 位置(agent 加载等场景为 null 时回退工作目录);
     * 对路径做完整 URL 解码(仅解 %20 会导致中文目录 %XX 不解码,plugins/data 写错位置);
     * 位于 jar 内时截取到所在目录;任何异常同样回退工作目录。
     *
     * @return 绝对目录路径(以系统分隔符结尾)
     */
    public final static String getPath() {
        if (localPath != null)
            return localPath;

        String filePath = null;
        try {
            java.security.CodeSource cs = ToolPack.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                // 修复:agent加载等场景下codeSource为null,回退到工作目录
                filePath = System.getProperty("user.dir");
            } else {
                // 修复:完整URL解码(仅%20会导致中文目录%XX不解码,plugins/data写错位置)
                filePath = java.net.URLDecoder.decode(cs.getLocation().getPath(), "UTF-8");
            }
        } catch (Exception e) {
            filePath = System.getProperty("user.dir");
        }

        if (filePath.endsWith(".jar"))
            filePath = filePath.substring(0, filePath.lastIndexOf("/") + 1);

        return (localPath = new File(filePath).getAbsolutePath());
    }

    /**
     * 收集指定路径下全部文件路径(含起始路径本身;b=false 时不递归子目录)。
     *
     * @param local 起始文件/目录
     * @param b     是否递归收集子目录
     * @return 路径列表(深度优先顺序)
     */
    public static final ArrayList<String> getAllFilesPath(File local, boolean b) {
        return getAllFilesPath(local, b, 0);
    }

    /**
     * 收集文件路径的带偏移重载:offset 为递归深度计数,起始层(offset=0)恒被展开,
     * 更深层按 isAll 决定是否继续下钻。
     *
     * @param file   起始文件/目录
     * @param isAll  是否递归收集子目录
     * @param offset 初始递归深度(通常传 0)
     * @return 路径列表(深度优先顺序)
     */
    public static final ArrayList<String> getAllFilesPath(File file, boolean isAll, int offset) {
        ArrayList<String> result = new ArrayList<String>();
        getAllFilesPath0(result, file, isAll, offset);
        return result;
    }

    /**
     * 递归收集实现:offset&gt;0 时把当前文件路径加入结果;
     * 目录按 isAll/offset==0 规则继续下钻(listFiles 返回 null 时跳过,如无权限目录)。
     */
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

    /**
     * 路径拆包:提取参数字符串中所有双引号包裹的路径段;
     * 以 '.' 开头的相对路径前缀替换为 {@link #getPath()};
     * 未匹配到任何引号段时原样返回整个字符串(单元素数组)。
     */
    public static final String[] pathRepack(String path) {
        ArrayList<String> result = new ArrayList<String>();

        Pattern p = PATH_PATTERN;
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
     * 获得此 jar 的 MF 文件中的 act 条目值(声明插件 Activity 主类)</br>
     * 没有 act 一行则会返回 null
     *
     * @param path jar所在的路径
     * @return act 条目值(可能为 null)
     **/
    public static String getExeMain(String path) {
        return getAttributes("act", path);
    }

    /**
     * 获得此 jar 的 MF 文件中的指定条目值(条目名大小写不敏感,内部转大写)</br>
     * 没有该条目一行则会返回 null
     *
     * @param path jar所在的路径
     * @param name 条目名称
     * @return 条目值(可能为 null)
     **/
    public static String getAttributes(String name, String path) {
        Attributes ab = getAttributes(path);
        if (ab == null)
            return null;
        return ab.getValue(name.toUpperCase());
    }

    /**
     * 打开 jar 读取 MF 主属性:打开/读取异常仅打印日志并返回 null;
     * finally 中关闭 JarFile 释放句柄(Windows 下必须关闭,否则 jar 被占用)。
     */
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

    /**
     * 命令解析器查找:全局 SPI 管理器未安装(null)时回退默认 SystemSpliter;
     * 已安装时把命令交给 SPI 的自定义解析器。
     *
     * @param cmd 完整命令字符串(调用方已做前导空白剥离)
     * @return 解析结果(SairCons 据此取组件名/函数名/参数)
     */
    public static Spliter findSplited(String cmd) {
        if (SairCons.SpliterSpiManager == null)
            return new SystemSpliter(cmd);
        else
            return SairCons.SpliterSpiManager.getSpliter(cmd);
    }

    /**
     * 安装自定义命令解析器 SPI:用全局 LoaderManager.loader 加载指定类
     * (Java17 兼容写法,替代已废弃的 Class.newInstance()),chkToInstall 校验
     * 通过后写入 SairCons.SpliterSpiManager(volatile)。
     *
     * @param args 实现类全限定名
     * @return 恒为 true(校验不通过时也不抛异常,只是不安装)
     * @throws Exception 类加载/实例化失败
     */
    public static boolean setSpliter(String args) throws Exception {
        boolean result = false;

        Class<?> clazz = Class.forName(args, false, LoaderManager.loader);
        // Java17兼容写法:替代已废弃的Class.newInstance()
        Object o = clazz.getDeclaredConstructor().newInstance();
        SpliterSPI spi = (SpliterSPI) o;

        if (spi.chkToInstall())
            SairCons.SpliterSpiManager = (SpliterSPI) o;

        result = true;
        return result;
    }

    /**
     * 从字符串抽取数字:剥离全部非数字字符后解析为 Integer;
     * 剥离后无数字内容时抛 NumberFormatException。
     *
     * @param string 含数字的字符串
     * @return 解析出的整数
     * @throws Exception 剥离后无数字内容
     */
    public static Integer IntegerValOfString(String string) throws Exception {
        try {
            return Integer.valueOf(NUM_PATTERN.matcher(string).replaceAll("").trim());
        } catch (NumberFormatException e) {
            throw e;
        }
    }

    /**
     * SPI 卸载命令检查:命令与当前 SPI 声明的卸载命令(getUninstallCMD)完全相等时
     * 执行 unInstall 并摘除全局管理器,返回 true 表示本条命令已被消费。
     * <p>注意:unInstall 默认实现已把 SpliterSpiManager 置 null,随后这里的
     * 二次判空置 null 是防御性写法;SPI 覆写 unInstall 后由本方法兜底摘除。
     */
    public static boolean SpliterChkUninstall(String cmd) {
        if (SairCons.SpliterSpiManager != null && cmd.equals(SairCons.SpliterSpiManager.getUninstallCMD())) {
            SairCons.SpliterSpiManager.unInstall();
            if (SairCons.SpliterSpiManager != null)
                SairCons.SpliterSpiManager = null;
            return true;
        } else
            return false;

    }

    /**
     * 获取系统变量表(公开可写):返回 {@link SystemSpliter#vmap} 本体引用,
     * 调用方增删变量会直接影响后续 %var% 展开;这是既定公开行为,不可改为副本。
     */
    public static HashMap<String, String> getVmap() {
        return SystemSpliter.vmap;
    }

    /**
     * 参数重组:从拆分后的参数数组中剔除 its 指定的下标,其余按原顺序用空格拼接
     * (供已消费部分参数的函数重建剩余参数字符串)。
     */
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

    /**
     * %var% 展开便捷入口:等价于 {@link SystemSpliter#chk_replace(String)}。
     */
    public static String chkArgsV(String arg) {
        return SystemSpliter.chk_replace(arg);
    }

    /**
     * ofunc 参数对象提取:仅当解析器是 SystemSpliter 时才可能携带内嵌执行
     * ('...')结果对象 o,交给 Activity.o_funcMain;SPI 解析器无此能力,返回 null。
     */
    public static Object toSystemSpliter_ofunc(Spliter sp, Activity localActivity) {
        if (sp instanceof SystemSpliter)
            return localActivity.o_funcMain(((SystemSpliter) sp).getO());
        else
            return null;
    }
}
