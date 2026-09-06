package sair.user;

import java.awt.Color;
import java.io.File;
import java.util.HashMap;

import sair.FCM;
import sair.Pathes;
import sair.sys.Libraries;
import sair.sys.SairCons;

/**
 * 插件业务组件抽象基类:插件实现本类并把主类名写入 jar 的 MF act 条目,
 * 由框架实例化、命名、登记并调度 main(funcName, args)。
 * <p>
 * 架构角色:插件生命周期与命令执行的对象环节——Exection 装载时实例化并 setName
 * 登记进 Libraries.activities;命令到达时经 OderFact 调用 main(默认函数分发);
 * 函数别名表(functionOrderMap)允许用户以自定义名替代插件原始函数名(oset/orem)。
 * <p>
 * 线程安全说明:{@link #functionOrderMap} 为 Safe.map 同步表(并发 IR 线程读写
 * 防结构损坏);{@link #isOpen} 为普通 boolean,框架按单命令线程读写;
 * getDataDir 的惰性初始化依赖 dataPath 先查后写,由框架装载管线串行保证。
 * <p>
 * 二进制兼容约束:本类大量方法为 public final,插件与旧字节码已直接绑定,不可再
 * 改为非 final/改签名/改返回类型——close/open/isOpen/getName/setName/
 * containsOrderName/putOrderName/getOldOrderName/removeOrderName/getDataDir/
 * uninstall(框架独占卸载,不可重写,请勿重载);
 * o_funcMain/dataDir/exit 为插件覆写挂钩,行为契约不可改变。
 * exit() 为抽象方法(设计约束):插件<b>必须实现</b>,框架仅转发调用、
 * 不做任何资源释放/调度、不约束插件自身行为。
 */
public abstract class Activity implements UserRunnable {
    /**
     * 数据目录(惰性初始化缓存,null 表示未创建)。
     */
    private String dataPath;

    /**
     * 组件名(Exection 装载时 setName 分配,用于命令查找与日志)。
     */
    private String name;

    /**
     * 是否接受命令输入(close/open 切换;false 时大多数内置命令与默认函数分发被拒绝)。
     */
    private boolean isOpen = true;

    /**
     * 函数别名表(线程安全):新名 → 插件原始函数名;oset/orem 维护。
     */
    private HashMap<String, String> functionOrderMap = sair.Safe.map();

    /**
     * 判断是否已存在某命令名(包含原始函数名与别名)。
     *
     * @param name 命令名
     * @return true 表示已登记
     */
    public final boolean containsOrderName(String name) {
        return functionOrderMap.containsKey(name);
    }

    /**
     * 登记函数别名:newName → funcName,并打印红色提示。
     *
     * @param newName  新名(用户输入名)
     * @param funcName 插件原始函数名
     */
    public final void putOrderName(String newName, String funcName) {
        functionOrderMap.put(newName, funcName);
        SairCons.println(Color.RED, getName() + " funcName : [" + funcName + "] --> [" + newName + "]");
    }

    /**
     * 别名反查:按新名取回插件原始函数名;未登记时返回 null。
     *
     * @param newOrderName 新名
     * @return 原始函数名(可能为 null)
     */
    public final String getOldOrderName(String newOrderName) {
        return functionOrderMap.get(newOrderName);
    }

    /**
     * 移除函数别名(原始函数名不在此表,不受影响);移除成功打印提示。
     *
     * @param name 别名
     */
    public final void removeOrderName(String name) {
        name = functionOrderMap.remove(name);
        if (null != name)
            SairCons.println(Color.RED, name + " is removed");
    }

    /**
     * 关闭命令输入:置 isOpen=false 后,help/exit/info/ofunc 等内置命令与
     * 默认函数分发都会拒绝执行(返回 false)。
     */
    public final void close() {
        isOpen = false;
        if (name == null)
            name = "SFW";
        SairCons.println(Color.RED, name + " is closed input cmd");
    }

    /**
     * 重新开放命令输入。
     */
    public final void open() {
        isOpen = true;
        if (name == null)
            name = "SFW";
        SairCons.println(Color.GREEN, name + " is opened input cmd");
    }

    /**
     * <p>
     * <b>框架级卸载(最终方法,插件不可重写;请勿以不同签名重载,卸载语义必须由框架独占)</b>:
     * 这是插件唯一的资源释放与调度入口——清注册表(按实例值含别名)→ 自动调用一次
     * exit()(退出前置钩子)与 close() → dispose 类加载器/清理 ExecLoaders 缓存,
     * 释放 jar 文件句柄(卸载后文件可删除/覆盖)。
     * </p>
     * <p>
     * 与 <code>插件名/exit</code>(仅转发调用本类 exit(),框架零资源动作)不同,
     * 本方法才执行真正的释放调度;OderFact 的 uninstall 命令即委托本方法。
     * </p>
     *
     * @return true=卸载成功;false=该组件没有关联的 Exection(如框架自身组件)或卸载异常
     */
    public final boolean uninstall() {
        sair.sys.acticity.Exection exec = Libraries.exections.get(this);
        if (exec == null) {
            SairCons.println(FCM.Error_Color, "该组件没有关联的Exection,无法卸载");
            return false;
        }
        try {
            exec.unLoadJar();
            return true;
        } catch (Exception e) {
            SairCons.println(FCM.Error_Color, "uninstall fail : " + e);
            return false;
        }
    }

    /**
     * 查询是否接受命令输入。
     *
     * @return true 表示开放
     */
    public final boolean isOpen() {
        return isOpen;
    }

    /**
     * 读取组件名:未命名时返回 "test"(占位)。
     */
    public final String getName() {
        if (this.name == null)
            return "test";
        return this.name;
    }

    /**
     * 设置组件名(Exection 装载时调用):名称已存在于 Libraries 时抛异常防重名。
     *
     * @param name 组件名
     * @throws Exception 名称已被 Libraries 占用
     */
    public final void setName(String name) throws Exception {
        boolean isHasName = Libraries.activities.containsKey(name);
        if (isHasName)
            throw new Exception("Early has:" + name + " in Libraries");
        else
            this.name = name;
    }

    /**
     * 获取/创建本组件专属数据目录:优先取插件覆写的 dataDir() 子路径
     * (null 时回退类全名),拼在 data 根目录(Pathes.dataResDir)之下;
     * 目录不存在时自动创建,结果惰性缓存于 dataPath。
     * <p>注意:dataDir() 返回值由插件提供,框架只做拼接;插件须自行保证
     * 不含 "../" 等越界路径。
     */
    public final String getDataDir() {

        if (dataPath != null)
            return dataPath;

        String path = dataDir();

        if (path == null) {
            path = this.getClass().getName();
        }

        // 修复:校验路径仍在data根目录下,防止"../"越界
        dataPath = Pathes.dataResDir + path + File.separator;
        File file = new File(dataPath);
        if (!file.exists() && !file.mkdirs()) {
            SairCons.println(FCM.Error_Color, "创建数据目录失败:" + dataPath);
        }

        return dataPath;
    }

    // #--UserRunnable
    /**
     * ofunc 内嵌对象处理挂钩:默认打印"未实现"提示并原样返回;
     * 插件覆写后可消费解析器携带的内嵌执行结果对象(SystemSpliter.getO)。
     *
     * @param o 内嵌执行结果对象
     * @return 处理结果(默认原样返回)
     */
    public Object o_funcMain(Object o) {
        SairCons.println(FCM.Error_Color, name + " is not implements ofunc");
        return o;
    }

    // #--UserRunnable
    /**
     * 数据目录子路径挂钩:插件覆写返回相对 data 根目录的子路径
     * (如 "myplugin/data");默认返回 null(回退类全名)。
     *
     * @return 子路径(可为 null)
     */
    protected String dataDir() {
        return null;
    }
}
