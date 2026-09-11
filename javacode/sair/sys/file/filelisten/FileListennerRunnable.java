package sair.sys.file.filelisten;

import java.io.File;

/**
 * 文件监听事件回调接口:由 {@link FileListenner} 在监听线程中回调。
 * <p>
 * 架构角色:文件监听环节的回调契约——每种事件对应一个方法,返回 false 表示
 * "处理完后停止监听";实现类可直接实现本接口,或继承 {@link FileListennerAdapter}
 * 只覆写关心的事件。
 * <p>
 * 线程安全说明:回调在监听线程内串行执行;实现需自行保证回调线程与其他业务线程
 * 之间的可见性与线程安全。
 * <p>
 * 二进制兼容约束:四个方法的签名不可变更。
 */
public interface FileListennerRunnable {
	/**
	 * 文件创建事件(含文件重命名移入目录)。
	 * 
	 * @param filePath
	 *            文件路径
	 * @return true 继续监听 false 处理完后停止监听
	 **/
	boolean create_rename(File filePath);

	/**
	 * 文件修改事件。
	 * 
	 * @param filePath
	 *            文件路径
	 * @return true 继续监听 false 处理完后停止监听
	 **/
	boolean modify(File filePath);

	/**
	 * 文件删除事件。
	 * 
	 * @param filePath
	 *            文件路径
	 * @return true 继续监听 false 处理完后停止监听
	 **/
	boolean delete(File filePath);

	/**
	 * 事件溢出事件(事件过多导致部分事件丢失;此时回调的文件对象为监听目录本身)。
	 * 
	 * @param filePath
	 *            文件路径(溢出事件时为监听目录本身)
	 * @return true 继续监听 false 处理完后停止监听
	 **/
	boolean overflow(File filePath);

}
