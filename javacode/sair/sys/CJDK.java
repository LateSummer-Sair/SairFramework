package sair.sys;

/**
 * 平台/运行环境探测:缓存 java.version/java.home/os.name 三个系统属性,并提供
 * Java 8 与 Windows 的可靠判定。
 * <p>
 * 架构角色:兼容性基础设施——框架与插件按运行环境选择实现(如 Java 8 与新版
 * 反射/启动器差异);SairCons.printTiInfos 也依赖本类输出环境信息。
 * <p>
 * 线程安全说明:{@link #version}/{@link #javaPath}/{@link #sysName} 为不可变
 * final 字符串;{@link #flag} 为普通字段的惰性缓存——并发首次调用可能重复计算,
 * 但结果幂等且 Boolean 引用写入原子,不会出现撕裂状态。
 * <p>
 * 二进制兼容约束:三个公开常量与 isJava8()/isWindows() 的签名已被插件引用,不可变更。
 */
public class CJDK {

	/**
	 * Java 版本号(java.version 系统属性,如 "1.8.0_202" 或 "17.0.9")。
	 */
	public static final String version = System.getProperty("java.version");

	/**
	 * Java 安装目录(java.home 系统属性)。
	 */
	public static final String javaPath = System.getProperty("java.home");

	/**
	 * 操作系统名(os.name 系统属性,如 "Windows 11")。
	 */
	public static final String sysName = System.getProperty("os.name");

	/**
	 * isJava8 判定结果的惰性缓存(null 表示未计算)。
	 */
	private static Boolean flag = null;

	/**
	 * 判断当前 JVM 是否为 Java 8:版本号先截去 "_" 后缀(如 1.8.0_202 → 1.8.0),
	 * 再做 "1.8" 前缀判断——前缀比较不受系统区域设置影响
	 * (修复 String.format 区域敏感导致的误判问题);结果惰性缓存。
	 *
	 * @return true 表示 Java 8
	 */
	public final static boolean isJava8() {
		if (flag != null)
			return flag;
		String lv = version;
		boolean is8 = false;
		if (lv != null) {
			if (lv.contains("_"))
				lv = lv.split("_")[0];
			// 前缀判断:不受系统区域设置影响(修复String.format区域敏感问题)
			is8 = lv.startsWith("1.8");
		}
		flag = is8;
		return is8;
	}

	/**
	 * 判断当前系统是否为 Windows:显式指定 Locale.ROOT 大写比较,
	 * 土耳其语等区域设置下不再误判(i 的本地化大小写问题)。
	 *
	 * @return true 表示 Windows 系列系统
	 */
	public static boolean isWindows() {
		// 修复:指定Locale.ROOT,土耳其语等区域设置下不再误判
		return sysName.toUpperCase(java.util.Locale.ROOT).indexOf("WINDOWS") >= 0;
	}
}
