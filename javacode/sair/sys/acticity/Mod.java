package sair.sys.acticity;

import java.io.File;
import java.io.IOException;

import sair.FCM;
import sair.LoaderManager;
import sair.Pathes;
import sair.SairLoader;
import sair.sys.Libraries;
import sair.sys.SairCons;

/**
 * 库模块(lib jar 的装载/卸载管理者):把纯库 jar 挂到全局 SairLoader,
 * 供插件与其宿主共享类。
 * <p>
 * 架构角色:插件生命周期环节——与 Exection(命令组件)不同,Mod 不产生 Activity,
 * 只登记 {@link Libraries#mods} 并让全局加载器可见其类。
 * <p>
 * 线程安全说明:装载(构造器)与卸载(unLoadJar)均假定由框架装载管线串行调用;
 * Libraries.mods 为 Safe.map 方法级同步表,单操作安全。
 * <p>
 * 卸载风险提示:unLoadJar0 会从全局 LoaderManager.loader 中移除本 jar 文件——
 * 全局共享加载器上的移除会立即影响所有引用方;若同一 jar 被多个插件/Mod 共享,
 * 卸载其一将破坏其余引用方的类可见性,调用方须自行保证引用已全部释放。
 * <p>
 * 二进制兼容约束:公开构造器 Mod(String) 与 unLoadJar() 的签名不可变更。
 */
public class Mod extends Acti {

    /**
     * 装载 lib jar:打开并挂到全局加载器,随后登记 Libraries.mods。
     *
     * @param path jar 路径
     * @throws IOException jar 打开失败
     */
    public Mod(String path) throws IOException {
        super(path);
        this.loadJar();
        this.initMod();
    }

    /**
     * 打开 lib jar:jar 存在时经 LoaderManager 挂到全局 SairLoader
     * (所有插件的类可共享其内容)。
     */
    private void loadJar() throws IOException {
        if (this.exists == true)
            LoaderManager.loadLibJar(this.path);
    }

    /**
     * 登记 Libraries.mods 并输出装载日志。
     */
    private void initMod() {
        Libraries.mods.put(this.getPath(), this);
        SairCons.println(FCM.loadMod_Color, "loaded MODS : " + this.getPath());
    }

    /**
     * 卸载本库模块:摘除 mods 登记后释放全局加载器中的本 jar 文件。
     * <p>注意共享 loader 风险:全局加载器上的移除会影响所有共享方,见类注释。
     *
     * @throws Exception 资源释放异常
     */
    public void unLoadJar() throws Exception {
        Libraries.mods.remove(this.getPath());
        SairCons.println(FCM.Error_Color, Pathes.printSplit);
        SairCons.println(FCM.Error_Color, "unload MODS : " + this.getPath());
        this.unLoadJar0();
    }

    /**
     * 释放资源:从全局 LoaderManager.loader 移除本 jar 文件并清理 libJarPathSet
     * 缓存(释放文件占用,便于后续删除/覆盖)。
     */
    private void unLoadJar0() throws Exception {
        File file = new File(path);
        SairLoader l = ((SairLoader) LoaderManager.loader);
		l .removeJarFiles(file);
		LoaderManager.libJarPathSet.remove(this.path);
    }

}