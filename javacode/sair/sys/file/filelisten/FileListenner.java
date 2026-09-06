package sair.sys.file.filelisten;

import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/**
 * 目录文件监听器(WatchService 封装):在独立线程中监听目录的创建/删除/修改/溢出
 * 事件,回调 {@link FileListennerRunnable} 的对应方法。
 * <p>
 * 架构角色:文件监听环节——作为 Runnable 交给线程启动,监听循环消费 WatchService
 * 事件并翻译成四种回调(创建/修改/删除/溢出);stop() 提供线程安全的外部终止。
 * <p>
 * 线程安全说明:{@link #isContinue} 与 {@link #watcher} 均为 volatile,保证 stop()
 * 对监听线程的可见性;stop() 通过关闭 WatchService 唤醒阻塞中的 take(否则监听线程
 * 永不退出);单次回调异常只记日志并继续处理后续事件,不会静默杀死整个监听器;
 * 任何退出路径都在 finally 中关闭 watcher(释放 Linux inotify 句柄)。
 * <p>
 * 二进制兼容约束:公开构造器 FileListenner(FileListennerRunnable, String) 与
 * stop()/run() 的签名不可变更。
 */
public final class FileListenner implements Runnable {

	/**
	 * 构造监听器:仅保存参数,实际监听在 {@link #run()} 中启动。
	 *
	 * @param runnable 事件回调
	 * @param dirPath  被监听目录
	 */
	public FileListenner(FileListennerRunnable runnable, String dirPath) {
		this.dirPath = dirPath;
		this.runnable = runnable;
	}

	/**
	 * 被监听目录路径。
	 */
	private String dirPath;

	/**
	 * 事件回调目标(创建/删除/修改/溢出)。
	 */
	private FileListennerRunnable runnable;

	/**
	 * 停止标志(volatile):stop() 置 false,监听循环尽快退出。
	 */
	private volatile boolean isContinue = true;

	/**
	 * WatchService 引用(volatile):stop() 通过关闭它唤醒阻塞中的 take。
	 */
	private volatile WatchService watcher;

	/**
	 * 监听主循环:注册目录(创建/删除/修改/溢出四类事件)后阻塞消费事件:
	 * <ul>
	 * <li>watcher 被 stop() 关闭而抛出 ClosedWatchServiceException 视为正常退出路径;</li>
	 * <li>OVERFLOW 事件的 context 为 null,退回监听目录本身构造 File;</li>
	 * <li>回调返回 false 表示"处理完后停止监听",退出循环;</li>
	 * <li>单事件回调异常只记录日志,继续处理后续事件;</li>
	 * <li>key.reset() 失败(目录被删除)时退出循环;</li>
	 * <li>finally 无条件关闭 watcher 并置空引用。</li>
	 * </ul>
	 */
	private void startListenFile() throws InterruptedException, IOException {
		Path path = Paths.get(dirPath);
		watcher = FileSystems.getDefault().newWatchService();
		path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
				StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.OVERFLOW);
		try {
			w: while (isContinue) {
				WatchKey key;
				try {
					key = watcher.take();
				} catch (ClosedWatchServiceException ce) {
					// stop()关闭watcher唤醒take:正常退出路径
					break w;
				}
				for (WatchEvent<?> event : key.pollEvents()) {
					if (!isContinue)
						break w;
					// 修复:单次回调异常只记录并继续处理后续事件,不再静默杀死整个监听器
					try {
						Path fileName = (Path) event.context();
						Kind<?> c = event.kind();
						// OVERFLOW事件context为null:退回监听目录本身
						File f = fileName == null ? new File(dirPath)
								: new File(dirPath + File.separator + fileName);
						if (c == StandardWatchEventKinds.OVERFLOW) {
							if (!runnable.overflow(f))
								break w;
						} else if (c == StandardWatchEventKinds.ENTRY_CREATE) {
							if (!runnable.create_rename(f))
								break w;
						} else if (c == StandardWatchEventKinds.ENTRY_DELETE) {
							if (!runnable.delete(f))
								break w;
						} else if (c == StandardWatchEventKinds.ENTRY_MODIFY) {
							if (!runnable.modify(f))
								break w;
						} else
							continue;
					} catch (Exception e) {
						System.err.println("[FileListenner] callback error: " + e);
					}
				}
				if (!key.reset()) {
					break w;
				}
			}
		} finally {
			// 修复:任何退出路径都关闭WatchService,释放Linux inotify句柄
			try {
				watcher.close();
			} catch (IOException ce) {
			}
			watcher = null;
		}
	}

	/**
	 * 请求停止监听:置停止标志并关闭 WatchService 唤醒阻塞中的 take
	 * (否则监听线程永不退出);可被任意线程调用,重复调用安全。
	 */
	public void stop() {
		isContinue = false;
		// 修复:关闭WatchService唤醒阻塞中的take,否则监听线程永不退出
		WatchService w = watcher;
		if (w != null) {
			try {
				w.close();
			} catch (IOException e) {
			}
		}
	}

	/**
	 * Runnable 入口:参数(目录/回调)有效时启动监听主循环;
	 * 启动期异常打印堆栈后线程结束。
	 */
	@Override
	public void run() {
		try {
			if (dirPath != null && runnable != null)
				startListenFile();
		} catch (InterruptedException | IOException e) {
			e.printStackTrace();
		}
	}
}
