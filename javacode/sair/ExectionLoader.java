package sair;

/**
 * 每插件独立的类加载器。
 * <p>
 * 职责:加载单个插件 jar 中的类与资源,使同名类在插件间互不冲突,
 * 并在插件卸载时随 Exection.unLoadJar 一起 dispose,释放 jar 句柄。
 * <p>
 * 架构角色:插件类加载器父子链的末端——父为 LoaderManager.loader(全局
 * SairLoader),因此插件代码可以直接引用 plugins/lib 中的依赖库类;反之
 * 全局 loader 看不到插件类(单向可见,实现插件隔离)。
 * <p>
 * 线程安全:实例由 LoaderManager 在主线程(Phase A)创建;Phase B 线程池
 * 并发调用其 loadClass/findClass,基类已 registerAsParallelCapable 且对
 * jars 映射与单个 JarFile 加锁(见 SairBaseLoader),并行加载安全;
 * 卸载由主线程在加载失败路径上执行。
 * <p>
 * 二进制兼容约束(不可改):类名与父类(公开可 instanceof)不可改;构造器为
 * 包级私有且父加载器硬编码为 LoaderManager.loader,外部/插件只能通过
 * LoaderManager 的登记表获得实例,不得改为 public 或改父加载器语义。
 */
public class ExectionLoader extends SairLoader {
    /**
     * 包内构造:父加载器固定为全局 SairLoader(LoaderManager.loader)。
     */
    ExectionLoader() {
        super(LoaderManager.loader);
    }
}
