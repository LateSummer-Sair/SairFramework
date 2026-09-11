package sair.sys.acticity;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * 组件装载基类(Exection 与 Mod 的公共抽象):缓存 jar 的 path/exists/url 元数据,
 * 并把"如何卸载"抽象为 {@link #unLoadJar}。
 * <p>
 * 架构角色:插件生命周期环节的公共底座——构造时统一解析路径、存在性与 URL,
 * 卸载协议由子类按各自资源形态实现(Exection 释放专属 loader,Mod 从全局 loader 摘除)。
 * <p>
 * 线程安全说明:path/exists/url 构造后不可变(无 setter),跨线程只读安全;
 * 子类状态(如 Exection.loadError)的并发契约由子类定义。
 * <p>
 * 二进制兼容约束:本类包私有,不构成对外 API;但 Exection/Mod 的公开构造器依赖
 * 本类受保护构造器的路径解析行为,其语义不可改变。
 */
abstract class Acti {

    /**
     * jar 文件路径(构造时固定)。
     */
    protected String path;

    /**
     * jar 文件是否存在(构造时探测;不存在时跳过装载但实例仍可创建)。
     */
    protected boolean exists;

    /**
     * jar 文件的 URL(LoaderManager 按 URL 缓存类加载器)。
     */
    protected URL url;

    /**
     * 解析路径元数据:记录 path/exists/url。
     *
     * @param path jar 文件路径
     * @throws MalformedURLException 路径无法转换为 URL
     */
    protected Acti(String path) throws MalformedURLException {
        // 步骤1:记录jar文件路径
        this.path = path;
        // 步骤2:探测jar文件是否存在(不存在时跳过装载但实例仍可创建)
        File file = new File(this.path);
        this.exists = file.exists();
        // 步骤3:转换为URL(LoaderManager按URL缓存类加载器)
        // 修复:URL统一用规范化文件——与LoaderManager.loadExecJar的URL登记键一致,
        // 同一jar的任何路径写法(大小写/斜杠/相对路径)都能命中同一键,卸载不会漏清
        File canon = file;
        try {
            canon = file.getCanonicalFile();
        } catch (IOException e) {
            // 规范化失败(罕见):回退原始路径(与旧版行为一致)
        }
        this.url = canon.toURI().toURL();
    }

    /**
     * 读取 jar 路径。
     */
    public String getPath() {
        return path;
    }

    /**
     * 读取 jar 的 URL。
     */
    public URL getURL() {
        return this.url;
    }

    /**
     * 查询 jar 文件是否存在。
     */
    public boolean exists() {
        return exists;
    }

    /**
     * 卸载契约:释放子类所持资源(类加载器、注册表项、缓存);
     * 实现应保证幂等,或由调用方保证仅调用一次。
     *
     * @throws Exception 资源释放异常
     */
    protected abstract void unLoadJar() throws Exception;
}
