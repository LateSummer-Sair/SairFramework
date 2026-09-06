package sair.user;

import java.awt.Color;

/**
 * 打印代理 SPI:注册到 SairCons.addPrintRunnable 后,所有控制台输出
 * (insertPrinto)会在代理上同步回调,实现"控制台输出旁路"。
 * <p>
 * 架构角色:命令输出环节的扩展点——代理在命令线程同步执行(保留实时语义),
 * 代理内再触发输出受 SairCons 重入上限(默认 8)保护。
 * <p>
 * 线程安全说明:回调发生在任意命令/IR 线程,实现需自行保证线程安全;
 * 注册表本身为 ConcurrentHashMap。
 * <p>
 * 二进制兼容约束:run(Integer, Color, String) 的签名不可变更。
 */
public interface PrintRunnable {

	/**
	 * 输出回调。
	 *
	 * @param index 插入位置(可为 null 表示追加)
	 * @param c     文本颜色(可为 null 表示默认色)
	 * @param info  文本内容
	 */
	void run(Integer index, Color c, String info);

}
