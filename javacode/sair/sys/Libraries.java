package sair.sys;

import java.io.File;
import java.util.HashMap;

import sair.Safe;
import sair.sys.acticity.Exection;
import sair.sys.acticity.Mod;
import sair.user.Activity;

/**
 * 全局注册表(插件注册中心):统一维护"组件名 → Activity"、"Activity → Exection"
 * 与"jar 路径 → Mod"三张登记表,以及组件名去重计数器。
 * <p>
 * 架构角色:命令解释与插件生命周期的枢纽——SairCons 按解析出的组件名在
 * {@link #activities} 中查找 Activity;Exection 装载时向三张表登记、卸载时摘除。
 * <p>
 * 线程安全说明:三张公开表均为 Safe.map() 方法级同步表(单操作安全,复合操作
 * 需调用方自行同步,如 Exection.unLoadJar 在 synchronized(Libraries.activities)
 * 内按实例值遍历移除);{@link #actiNameRulMana} 仅在 setActivityName 内部的
 * synchronized 块中读写,保证计数与探测的原子性。装载管线本身在主线程串行
 * 实例化,同名装载不会并发发生。
 * <p>
 * 二进制兼容约束:三个公开字段为 public final static HashMap,其声明类型
 * (HashMap&lt;Activity, Exection&gt;/HashMap&lt;String, Mod&gt;/HashMap&lt;String, Activity&gt;)
 * 已被插件字节码引用,不可更换为其他 Map 实现或改变泛型参数。
 */
public final class Libraries {

    /**
     * Activity → 其所属 Exection 的映射(公开登记表,线程安全单操作);
     * info/uninstall 命令据此找到组件的装载器。
     */
    public final static HashMap<Activity, Exection> exections = Safe.map();

    /**
     * jar 路径 → Mod 库模块的映射(公开登记表,线程安全单操作)。
     */
    public final static HashMap<String, Mod> mods = Safe.map();

    /**
     * 组件名 → Activity 的映射(公开登记表,线程安全单操作);命令按组件名在此查找。
     */
    public final static HashMap<String, Activity> activities = Safe.map();

    /**
     * 组件名分配计数器:记录每个 jar 基名已分配的后缀序号(-1 表示尚未重名),
     * 仅在 setActivityName 的 synchronized 块内读写。
     */
    private final static HashMap<String, Integer> actiNameRulMana = Safe.map();

    /**
     * 为组件分配唯一名称:取 jar 文件名(去扩展名)为基名。
     * <ul>
     * <li>首次使用直接返回基名;</li>
     * <li>名称已被释放(热卸载后的重载)且 activities 中不再占用时复用原名,避免无意义的后缀累计;</li>
     * <li>被占用时从计数器开始逐个探测(自愈式防碰撞),跳过实际被占用的名称,
     *     返回第一个空闲的 "基名+序号"。</li>
     * </ul>
     * 计数与探测在 synchronized(actiNameRulMana) 内原子完成;最终的 activities.put
     * 由调用方(Exection)执行,框架装载管线保证同名装载串行。
     *
     * @param actiPath jar 完整路径
     * @return 分配到的唯一组件名
     */
    public static String setActivityName(String actiPath) {
        final String name = new File(actiPath).getName().split("\\.")[0];

        synchronized (actiNameRulMana) {
            Integer actiRul_ID = actiNameRulMana.get(name);

            if (actiRul_ID == null) {
                actiNameRulMana.put(name, -1);
                return name;
            }

            // 修复:名称已被释放(热卸载后的重载)时直接复用原名,避免无意义的后缀累计
            if (actiRul_ID.intValue() < 0 && !Libraries.activities.containsKey(name))
                return name;

            // 自愈式防碰撞:从计数器开始逐个探测,跳过实际被占用的名称
            while (true) {
                actiRul_ID++;
                actiNameRulMana.put(name, actiRul_ID);
                String cand = name + actiRul_ID;
                if (!Libraries.activities.containsKey(cand))
                    return cand;
            }
        }
    }
}
