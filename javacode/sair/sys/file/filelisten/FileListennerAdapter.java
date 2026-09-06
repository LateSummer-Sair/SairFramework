package sair.sys.file.filelisten;

import java.io.File;

/**
 * 文件监听回调适配器:所有回调默认返回 true(继续监听),子类只需覆写关心的事件。
 * <p>
 * 架构角色:文件监听环节的样板代码消除——为 {@link FileListennerRunnable} 提供
 * 空实现,避免调用方必须实现全部四个方法。
 * <p>
 * 线程安全说明:回调在监听线程内串行执行;子类覆写时自行保证跨线程可见性。
 * <p>
 * 二进制兼容约束:本类可被插件继承,四个覆写方法的签名不可变更。
 */
public class FileListennerAdapter implements FileListennerRunnable{

	/**
	 * 文件创建/重命名事件:默认继续监听。
	 */
	@Override
	public boolean create_rename(File filePath) {
		// TODO Auto-generated method stub
		return true;
	}

	/**
	 * 文件修改事件:默认继续监听。
	 */
	@Override
	public boolean modify(File filePath) {
		// TODO Auto-generated method stub
		return true;
	}

	/**
	 * 文件删除事件:默认继续监听。
	 */
	@Override
	public boolean delete(File filePath) {
		// TODO Auto-generated method stub
		return true;
	}

	/**
	 * 事件溢出(事件丢失)事件:默认继续监听。
	 */
	@Override
	public boolean overflow(File filePath) {
		// TODO Auto-generated method stub
		return true;
	}

}
