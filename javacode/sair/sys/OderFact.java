package sair.sys;

import sair.FCM;
import sair.sys.acticity.Exection;
import sair.sys.tools.Spliter;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

/**
 * 内置命令分发器(函数别名映射):SairCons 解析出组件与函数名后,由本类完成内置命令
 * (help/exit/info/ofunc/close/open/oset/orem/uninstall)与默认组件方法调用的分流。
 * <p>
 * 架构角色:命令执行链中"命令 → 动作"的映射环节——run 方法先做函数别名还原
 * (containsOrderName/getOldOrderName),再按函数名分发;oset/orem 维护 Activity 的
 * 函数别名表(functionOrderMap),让用户用自定义名称替代插件原始函数名。
 * <p>
 * 线程安全说明:本类为无状态单例(仅一个静态 OF 实例),方法不持有可变共享状态,
 * 线程安全完全依赖 Activity 内部映射与 Libraries 注册表的同步策略;dispatch 本身可重入。
 * <p>
 * 二进制兼容约束:本类包私有,不构成对外 API;但静态入口 runner(Activity, Spliter)
 * 的参数顺序与分发语义被 SairCons/框架依赖,不可调整。
 */
class OderFact {

    private final static OderFact OF = new OderFact();

    /**
     * 分发入口(分三步):
     * <ol>
     * <li>取解析出的函数名与参数段;</li>
     * <li>函数名已登记别名时还原为插件原始函数名(containsOrderName/getOldOrderName);</li>
     * <li>按内置命令表分发(help/exit/info/ofunc/close/open/oset/orem/uninstall),
     *     未命中内置命令时作为默认组件函数调用(Activity.main)。</li>
     * </ol>
     *
     * @param localActivity 目标组件
     * @param sp            解析结果(函数名/参数/内嵌对象)
     * @return 命令执行结果(可为 null)
     */
    public static Object runner(Activity localActivity, Spliter sp) {
        // 步骤1:取函数名,别名还原为插件原始函数名
        String funcName = sp.getExecFunc();
        if (localActivity.containsOrderName(funcName))
            funcName = localActivity.getOldOrderName(funcName);
        String localArgs = sp.getArgs();
        // 步骤2:按内置命令表分发,未命中走默认组件函数调用
        switch (funcName) {
            case "help":
                return OF.help(localActivity);
            case "exit":
                return OF.exit(localActivity);
            case "info":
                return OF.info(localActivity);
            case "ofunc":
                return OF.ofunc(localActivity, sp);
            case "close":
                return OF.close(localActivity);
            case "open":
                return OF.open(localActivity);
            case "oset":
                return OF.oset(localActivity, localArgs);
            case "orem":
                return OF.orem(localActivity, localArgs);
            case "uninstall":
                return OF.uninstall(localActivity);
            default:
                return OF.defaultFunc(localActivity, funcName, localArgs);
        }
    }

    /**
     * help 命令:组件未开放输入时拒绝;否则打印帮助文本(分隔线+逐行内容)。
     */
    private boolean help(Activity localActivity) {
        // 步骤1:未开放输入直接拒绝
        if (!localActivity.isOpen())
            return false;
        // 步骤2:打印帮助文本
        SairCons.printHelp(localActivity);
        return true;
    }

    /**
     * exit 命令:插件<b>自定义退出的前置钩子</b>(UserRunnable.exit(),抽象方法,
     * 插件<b>必须实现</b>)——框架仅把调用转发给组件的 exit(),<b>零资源动作</b>:
     * 不捕获异常、不校验返回值、不做任何资源释放/调度/清理,是否真的退出由插件自己决定。
     * 不受 isOpen 门禁(与 close/open 同级):插件通道关闭后钩子仍可达。
     */
    private boolean exit(Activity localActivity) {
        // 步骤1:直接转发调用组件exit()(框架零资源动作)
        localActivity.exit();
        return true;
    }

    /**
     * info 命令:打印组件数据目录,若组件有关联 Exection 则追加打印 jar 路径。
     */
    private boolean info(Activity localActivity) {
        // 步骤1:未开放输入直接拒绝
        if (!localActivity.isOpen())
            return false;
        // 步骤2:取组件名(null防御分支按framework展示)
        String name = localActivity.getName();
        if (name == null)
            name = "framework";
        // 步骤3:打印组件数据目录
        Exection exec = Libraries.exections.get(localActivity);
        SairCons.println(FCM.EXECTION_pathInfo_Color, name + " --> " + localActivity.getDataDir());
        // 步骤4:有关联Exection时追加打印jar路径
        if (exec == null)
            return true;
        SairCons.println(FCM.EXECTION_pathInfo_Color, name + " --> " + exec.getPath());
        return true;
    }

    /**
     * ofunc 命令:把解析结果中的内嵌执行对象透传给组件的 o_funcMain
     * (仅 SystemSpliter 能携带内嵌对象,SPI 解析器时为 null)。
     */
    private Object ofunc(Activity localActivity, Spliter sp) {
        // 步骤1:未开放输入直接拒绝
        if (!localActivity.isOpen())
            return false;
        // 步骤2:透传内嵌执行对象(SPI解析器时为null)
        return ToolPack.toSystemSpliter_ofunc(sp, localActivity);
    }

    /**
     * close 命令:关闭组件命令输入(不受 isOpen 门禁,允许组件已关闭时重复调用)。
     */
    private boolean close(Activity localActivity) {
        // 步骤1:调用组件close()
        localActivity.close();
        return true;
    }

    /**
     * 默认分发:未命中内置命令时调用组件 main;组件未开放输入时拒绝执行。
     */
    private Object defaultFunc(Activity localActivity, String funcName, String localArgs) {
        // 步骤1:未开放输入直接拒绝
        if (!localActivity.isOpen())
            return false;
        // 步骤2:调用组件main(内部处理null/Boolean.FALSE返回值约定)
        return SairCons.toActiRun(localActivity, funcName, localArgs);
    }

    /**
     * orem 命令:按别名移除函数映射(原始函数名不受影响)。
     */
    private boolean orem(Activity localActivity, String localArgs) {
        // 步骤1:按别名移除映射
        localActivity.removeOrderName(localArgs);
        return true;
    }

    /**
     * 通用命令 uninstall:委托 {@link Activity#uninstall()}(final,框架独占实现)执行
     * 框架级卸载与资源释放调度——这是唯一入口,内部自动调用一次组件 exit() 与 close()。
     */
    private boolean uninstall(Activity localActivity) {
        // 步骤1:委托Activity.uninstall()(final,唯一释放调度入口)
        return localActivity.uninstall();
    }

    /**
     * open 命令:重新开放组件命令输入(不受 isOpen 门禁)。
     */
    private boolean open(Activity localActivity) {
        // 步骤1:调用组件open()
        localActivity.open();
        return true;
    }

    /**
     * oset 命令:登记函数别名(分四步)。参数格式为 "oset &lt;新名&gt; &lt;原名&gt;"
     * (argsSplit[0]=新名,argsSplit[1]=原名);段数不足两段或两段任一为空时打印提示并返回。
     */
    private boolean oset(Activity localActivity, String localArgs) {
        // 步骤1:按空格拆分为[新名,原名]
        String[] argsSplit = localArgs.split(" ");
        // 步骤2:不足两段打印提示并返回
        if (argsSplit.length < 2) {
            SairCons.println("args ERROR!");
            return true;
        }
        String oldName = argsSplit[1], newName = argsSplit[0];
        // 步骤3:任一段为空打印提示并返回
        if (oldName.trim().equals("") || newName.trim().equals("")) {
            SairCons.println("args has null!");
            return true;
        }
        // 步骤4:登记别名映射(新名→原名)
        localActivity.putOrderName(newName, oldName);
        return true;
    }

}
