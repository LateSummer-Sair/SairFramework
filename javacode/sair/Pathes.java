package sair;

import java.io.File;

import sair.sys.tools.ToolPack;

/**
 * 全局目录与资源路径常量。
 * <p>
 * 职责:集中定义框架工作目录结构(plugins 根目录、合并后的 lib 目录、
 * 插件目录 exection、数据目录 data)与内嵌资源路径(logo/字体)、控制台分割线文本。
 * <p>
 * 架构角色:启动期被 LoaderManager/ConsFrame 等读取来定位目录;
 * 静态块在类初始化时负责创建 plugins 根目录(失败打印 stderr)。
 * <p>
 * 线程安全:全部为 static final String 常量,类初始化后只读,天然线程安全
 * (不可变 String,无需同步)。
 * <p>
 * 二进制兼容约束(不可改):
 * <ul>
 * <li>全部字段名不可改({@link #pluginsDir}、{@link #execDir}、
 *     {@link #dataResDir}、{@link #libDir} 被框架与旧插件广泛引用);</li>
 * <li>{@link #bootDir}/{@link #modDir} 已 @deprecated 合并至 {@link #libDir},
 *     但字段必须保留以兼容旧插件引用;</li>
 * <li>{@link #pack_repack_space}("//")是路径打包/还原协议的一部分,值不可改,
 *     否则 autorun 路径还原会错乱;</li>
 * <li>已注释掉的 staticfilesDir 不得恢复为生效代码(该目录已废弃)。</li>
 * </ul>
 */
public class Pathes {

	// 已废弃目录:staticfiles 资源已内嵌进 jar(/staticfiles/...),字段保留注释以防误恢复
	//public static final String staticfilesDir = ToolPack.getPath() + File.separator + "staticfiles" + File.separator;

	/**
	 * plugins 根目录:框架所有可加载内容的挂载点(不可改字段名)
	 */
	public static final String pluginsDir = ToolPack.getPath() + File.separator + "plugins" + File.separator;
	/**
	 * 控制台分割线文本(错误/警告块分隔用;值不可改)
	 */
	public static final String printSplit = "--------------------------------------------------";
	/**
	 * 内嵌 logo 资源路径(classpath 形式,jar 内 /staticfiles/Sair.png)
	 */
	public static final String logoPath = "/staticfiles/Sair.png";
	/**
	 * 内嵌字体资源路径(classpath 形式,jar 内 /staticfiles/Sair.ttf)
	 */
	public static final String fontPath = "/staticfiles/Sair.ttf";
	/**
	 * 统一依赖库目录:原 bootlib 与 modlib 已合并至此(不可改字段名)。
	 * 由全局 SairLoader 加载,支持 SPI(ServiceLoader/DriverManager 等)与热卸载。
	 */
	public static final String libDir = pluginsDir + "lib" + File.separator;
	/**
	 * 旧 bootlib 目录别名,值已等于 {@link #libDir}。
	 *
	 * @deprecated 已合并至 libDir,保留以兼容旧插件引用;字段不可删除
	 */
	@Deprecated
	public static final String bootDir = libDir;
	/**
	 * 旧 modlib 目录别名,值已等于 {@link #libDir}。
	 *
	 * @deprecated 已合并至 libDir,保留以兼容旧插件引用;字段不可删除
	 */
	@Deprecated
	public static final String modDir = libDir;
	/**
	 * 插件目录:LoaderManager.loadExec 从这里扫描全部插件 jar(不可改字段名)
	 */
	public static final String execDir = pluginsDir + "exection" + File.separator;
	/**
	 * 数据目录:autorun.ir、用户数据等运行时数据存放处(不可改字段名)
	 */
	public static final String dataResDir = ToolPack.getPath() + File.separator + "data" + File.separator;
	/**
	 * 路径打包/还原协议的转义占位符("//"):ToolPack.pathRepack 用它替换真实
	 * 路径分隔符,值不可改
	 */
	public static final String pack_repack_space = "//";

	/**
	 * 类初始化:确保 plugins 根目录存在(创建失败不再静默,打印 stderr);
	 * staticfiles 目录已废弃(资源内嵌进 jar,相关代码保留注释)。
	 */
	static {

/*		File sfd = new File(staticfilesDir);
		if (!sfd.exists())
			sfd.mkdirs();*/

		File pfd = new File(pluginsDir);
		if (!pfd.exists() && !pfd.mkdirs()) {
			// 修复:建目录失败不再静默(错误延迟到运行时更难排查)
			System.err.println("[Pathes] 创建plugins目录失败: " + pfd.getAbsolutePath());
		}

	}

}
