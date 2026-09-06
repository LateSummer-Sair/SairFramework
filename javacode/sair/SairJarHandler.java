package sair;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * 自定义 sairjar: 协议处理器(零反射,Java8/17 通用)。
 * <p>
 * 目的:替代 jar:file:...!/ URL。JDK 的 JarURLConnection 会通过 JarFileFactory
 * 全局缓存 JarFile 句柄,导致插件热卸载后 jar 文件仍被占用(Windows 无法删除/覆盖)。
 * 本处理器每次 openStream 独立打开并随流关闭 JarFile,无任何全局缓存,
 * 卸载(dispose)后文件句柄立即释放。
 * <p>
 * 架构角色:资源读取链路的最后一段——SairBaseLoader.findResource 生成
 * sairjar: URL,ServiceLoader 等通过 URL.openStream 进入本处理器取流。
 * <p>
 * 线程安全:注册在静态块中完成,{@code registered} 仅在类初始化时写入一次;
 * 每次 openConnection 创建独立的 SairJarConnection 与 JarFile,实例无共享
 * 可变状态,天然线程安全。
 * <p>
 * 二进制兼容与协议契约(不可改):
 * <ul>
 * <li>URLStreamHandlerFactory 每 JVM 只能设置一次:注册失败时静默降级为
 *     jar: 协议({@link #active()} 返回 false,Main 红字提示),不得改变该回退语义;</li>
 * <li>sairjar: URL 形如 sairjar:file:///...jar!/条目名,条目名百分号编码由
 *     SairBaseLoader.encodeEntry 生成、本处理器 decodeEntry 对称解码,两边不可单方面修改;</li>
 * <li>{@link #active()} 为公开静态方法,签名不可改。</li>
 * </ul>
 */
public final class SairJarHandler extends URLStreamHandler {

	/**
	 * 工厂注册结果(仅静态块写入一次,active() 读取):true=sairjar: 协议可用
	 */
	private static boolean registered = false;

	static {
		try {
			URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory() {
				public URLStreamHandler createURLStreamHandler(String protocol) {
					if ("sairjar".equals(protocol))
						return new SairJarHandler();
					return null; // 其他协议走默认处理器
				}
			});
			registered = true;
		} catch (Throwable e) {
			// 工厂已被占用(每JVM只能设置一次):回退到jar:协议
			registered = false;
		}
	}

	/**
	 * 自定义 sairjar: 协议是否可用(注册成功为 true)。
	 * <p>
	 * 不可用意味着 URLStreamHandlerFactory 已被第三方占用,框架降级为 jar: 协议。
	 *
	 * @return true=每次 openStream 独立 JarFile 的句柄释放特性生效
	 */
	public static boolean active() {
		return registered;
	}

	/**
	 * 为 sairjar: URL 建立连接(实际取流发生在 SairJarConnection.getInputStream)。
	 *
	 * @param u sairjar: URL
	 * @return 对应的 SairJarConnection
	 * @throws IOException 保留签名(本实现不抛出)
	 */
	@Override
	protected URLConnection openConnection(URL u) throws IOException {
		return new SairJarConnection(u);
	}

	/**
	 * sairjar: 连接实现:按 "!/" 分隔 jar 文件路径与条目名,解码条目名后独立打开
	 * JarFile 取流;返回的流 close 时同步关闭 JarFile,无任何全局句柄缓存(热卸载契约)。
	 */
	static final class SairJarConnection extends URLConnection {

		/**
		 * @param url 关联的 sairjar: URL
		 */
		SairJarConnection(URL url) {
			super(url);
		}

		/**
		 * 无连接过程:所有工作延迟到 getInputStream。
		 */
		@Override
		public void connect() {
			// 无连接过程
		}

		/**
		 * 解析 URL 并返回条目内容流:以 "!/" 切分 jar 文件路径与条目名,条目名
		 * 百分号解码(与 SairBaseLoader.encodeEntry 对称),独立打开 JarFile
		 * (不经过 JDK 全局 JarFileFactory 缓存),返回包装流——close 时先关底层流
		 * 再关 JarFile,句柄必然释放。
		 *
		 * @return 条目内容输入流(调用方负责关闭)
		 * @throws IOException           URL 格式非法或 jar 打开失败
		 * @throws FileNotFoundException 条目不存在或 URL 缺少 "!/" 分隔符
		 */
		@Override
		public InputStream getInputStream() throws IOException {
			String spec = url.getFile();
			int sep = spec.lastIndexOf("!/");
			if (sep < 0)
				throw new FileNotFoundException("bad sairjar url: " + spec);
			String filePart = spec.substring(0, sep);
			// 修复:条目名百分号解码,与SairBaseLoader.jarEntryURL的编码对称(空格/#/?/%)
			String entry = decodeEntry(spec.substring(sep + 2));
			File jarFile;
			try {
				jarFile = new File(new URI(filePart));
			} catch (Exception e) {
				throw new IOException("bad sairjar file part: " + filePart);
			}
			final JarFile jf = new JarFile(jarFile);
			try {
				ZipEntry ze = jf.getEntry(entry);
				if (ze == null) {
					throw new FileNotFoundException(entry);
				}
				final InputStream raw = jf.getInputStream(ze);
				// 流关闭即关闭JarFile,无全局缓存,句柄必然释放
				return new FilterInputStream(raw) {
					@Override
					public void close() throws IOException {
						try {
							super.close();
						} finally {
							jf.close();
						}
					}
				};
			} catch (RuntimeException | IOException e) {
				// 修复:取条目失败时关闭刚打开的JarFile,避免句柄泄漏
				try {
					jf.close();
				} catch (IOException ce) {
				}
				throw e;
			}
		}

		/**
		 * 百分号解码(字节级,支持 UTF-8 多字节;不解码 "+" 避免与表单编码混淆),
		 * 与 SairBaseLoader.encodeEntry 的编码完全对称。
		 *
		 * @param s 编码后的条目名
		 * @return 解码还原的条目名;解码异常时原样返回输入
		 */
		private static String decodeEntry(String s) {
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(s.length());
			for (int i = 0; i < s.length(); i++) {
				char ch = s.charAt(i);
				if (ch == '%' && i + 2 < s.length()) {
					int hi = Character.digit(s.charAt(i + 1), 16);
					int lo = Character.digit(s.charAt(i + 2), 16);
					if (hi >= 0 && lo >= 0) {
						bos.write((hi << 4) | lo);
						i += 2;
						continue;
					}
				}
				byte[] bs = String.valueOf(ch).getBytes(java.nio.charset.StandardCharsets.UTF_8);
				try {
					bos.write(bs);
				} catch (IOException e) {
				}
			}
			try {
				return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
			} catch (Exception e) {
				return s;
			}
		}
	}
}
