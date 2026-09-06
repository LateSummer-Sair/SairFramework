package sair.sys.gui;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.LinearGradientPaint;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;

import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import javax.swing.JLayeredPane;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Document;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

import sair.FCM;
import sair.sys.SairCons;
import sair.sys.gui.swing.control.A_JPanel;
import sair.sys.gui.swing.control.SBorder;
import sair.sys.gui.swing.control.SButton;
import sair.sys.gui.swing.control.SFrame;
import sair.sys.gui.swing.control.SairScrollBarUI;
import sair.sys.gui.swing.control.SairTabbedPaneUI;
import sair.sys.gui.swing.tools.Backgrounds;
import sair.sys.gui.swing.tools.BorderButton;
import sair.sys.gui.swing.tools.BufferedImageTool;
import sair.sys.gui.swing.tools.Clicks;
import sair.sys.gui.swing.tools.Fonts;

import javax.swing.JTextField;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.AbstractButton;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;

/**
 * 控制台主窗体(框架GUI主体):以无边框SFrame承载"文本控制台+插件列表+画布选项卡隔离区"三大区域。
 * <p>
 * <b>架构角色</b>
 * <ul>
 * <li>单例:公开字段 {@link #cf} 即唯一实例(构造器私有,类加载时静态初始化),窗口显隐不销毁; 插件/IR脚本一律通过 ConsFrame
 * 的静态方法操作控制台。</li>
 * <li>文本控制台:{@link #infoPane}(JTextPane)+{@link #centerScorllPane};所有打印经
 * {@link #printo(Integer, Color, String)} <b>调用线程直接插入</b>(直印模式,文档变更由
 * {@link #printLock} 串行化)。</li>
 * <li>组件隔离区:{@link #tabsPane} 选项卡——printComponent 识别到画布/内部窗口/顶层窗口时入选项卡,
 * 与文本流完全隔离;其余子控件仍走 insertComponent 入文本流。</li>
 * <li>插件列表:{@link #listModel}/{@link #list}/{@link #listP_JSP},标题栏Sair按钮切换显隐,
 * 单击/双击由 PackageClasses 的监听器处理。</li>
 * </ul>
 * <p>
 * <b>线程模型(已彻底弃用EDT调度)</b>
 * <ul>
 * <li>所有公开静态入口(printo/dePrinto/printComponent/clearComponents/换色/背景/标题/显隐等)
 * 均由<b>调用线程直接执行</b>(恢复旧版方式),不再 invokeLater/EDT Timer 调度;
 * {@link #runOnEDT(Runnable)} 保留仅为二进制兼容,框架内部不再使用,新代码请勿调用。</li>
 * <li>文档变更(insertString/remove/setText)统一在 {@link #printLock} 内执行,多线程打印不破坏
 * StyledDocument;段计数 {@code consoleSegments} 同锁保护。</li>
 * <li>滚动经 {@link #flushPoint()} 调用线程直接执行(不再合并/派发)。</li>
 * <li>{@link #bgC}/{@link #otC} 为 volatile,任意线程写后立即可见。</li>
 * </ul>
 * <p>
 * <b>二进制兼容约束</b>(旧插件依赖,签名不可改)
 * <ul>
 * <li>公开字段 {@link #cf}、{@link #listModel}、{@link #list} 保持原样;可配置字段
 * {@link #MAX_CONSOLE_TEXT}、{@link #MAX_SINGLE_TEXT}、{@link #MAX_BATCH_PRINTS}、
 * {@link #MAX_CONSOLE_MEMORY} 为 public static,插件可运行时调参。</li>
 * <li>静态方法
 * printo/dePrinto/printComponent/getAllText/getPaneSize/close/setFontColor/
 * setBackgroundColor/setImageBackground/setFrameOpacity/showFrame/hideFrame/setTitleInfo/playScanline/
 * flushPoint/runOnEDT/getTextPane/getFontColor/clearComponents
 * 是插件打印/换肤入口,签名与语义保持稳定。</li>
 * </ul>
 * <p>
 * <b>资源纪律</b>:背景图按"路径+修改时间+大小"缓存复用;颜色属性缓存 {@link #attrCache} 为256上限LRU; 控制台按
 * {@link #MAX_CONSOLE_MEMORY} 内存预算裁剪最旧一半(防长跑OOM);滚动条UI实例复用
 * ({@link #scrollUIs}:[列表垂直,列表水平,主区垂直,主区水平],换色仅更新配色)。
 */
public class ConsFrame extends SFrame {

	/** 窗口默认标题;setTitleInfo 设置 customTitle 后覆盖显示,flush 不再改回 */
	public static final String title_str = "SairFrameWork";
	/** 控制台基准字体(13pt);p_f 随窗口尺寸等比缩放 */
	public static final Font font = Fonts.FONTS_TOOLS.getFont(null, null, 13.0f);
	/** 框架命令分发器(sair.user.Activity):/help、/print、/load 等命令的实现所在 */
	public static final FrameActivity fa = new FrameActivity();
	/** 默认窗口宽(px);/resize 无参数时恢复该尺寸 */
	static final int w = 800;
	/** 默认窗口高(px) */
	static final int h = 600;
	/**
	 * 控制台文档长度上限(字符):超过时自动清空(可配置,安全加固:默认32MB防止恶意输出耗尽内存)
	 */
	public static int MAX_CONSOLE_TEXT = 32 * 1024 * 1024;
	/**
	 * 单次插入文本长度上限(字符):超过时拒绝插入(可配置,安全加固)
	 */
	public static int MAX_SINGLE_TEXT = 32 * 1024 * 1024;
	// 修复:颜色属性缓存改LRU(上限256),随机颜色打印不再无界增长
	private static final java.util.LinkedHashMap<Color, SimpleAttributeSet> attrCache = new java.util.LinkedHashMap<Color, SimpleAttributeSet>(
			16, 0.75f, true) {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<Color, SimpleAttributeSet> eldest) {
			return size() > 256;
		}
	};

	/**
	 * 控制台文档锁:串行化所有文档变更(insertString/remove/setText)与段计数, 多线程直印不破坏
	 * StyledDocument。
	 */
	private static final Object printLock = new Object();
	/** 自定义窗口标题(setTitleInfo设置):非null时框架不再自动恢复默认标题 */
	// private static String customTitle = null;
	/** 背景图解码缓存:换色/resize时直接复用,不重复解码 */
	private static Image cachedBgImage;
	/** 背景图缓存键:路径(或路径|修改时间|大小),用于判定文件是否变化需重新解码 */
	private static String cachedBgKey;
	/** 最近一次解码失败的背景图键:同一失败键只提示一次(失败不写负缓存) */
	private static String lastFailedBgKey;
	/** 扫光特效一次性开关:整个进程只播放一次彩虹扫光 */
	private static boolean scanlinePlayed = false;
	// 滚动条UI复用(微优化):[列表垂直, 列表水平, 主区垂直, 主区水平],换色时仅更新配色
	/** 四个滚动条的UI实例缓存,换色时仅setColors+重绘(EDT读写) */
	private final SairScrollBarUI[] scrollUIs = new SairScrollBarUI[4];
	/** 对应槽位UI是否已创建:首次setUI,之后仅setColors */
	private final boolean[] scrollUIInited = new boolean[4];
	/**
	 * 控制台内存预算(估算字节,可配置):文本按每字符2字节,每段打印另计结构开销。 超预算自动裁剪最旧一半(修复长跑OOM)。
	 */
	public static long MAX_CONSOLE_MEMORY = 64L * 1024L * 1024L;
	/**
	 * 每次insertString在StyledDocument中的固定元素开销估算(字节)
	 */
	private static final int PER_SEGMENT_BYTES = 128;
	/** 段计数(EDT读写):每插入/删除一段同步增减,供内存预算估算使用 */
	private static long consoleSegments = 0;
	/** 序列化版本UID(历史值保留) */
	private static final long serialVersionUID = 3271120553991368988L;
	/** 控制台单例(公开,旧插件直接引用):构造器私有,静态初始化时创建 */
	public static ConsFrame cf = new ConsFrame();
	/** 已加载插件名列表模型(EDT读写) */
	public DefaultListModel<String> listModel = new DefaultListModel<String>();
	/** 已加载插件名列表(公开,旧插件可能直接访问) */
	public JList<String> list = new JList<String>(listModel);
	/** 插件列表的滚动容器;由标题栏Sair按钮控制显隐 */
	JScrollPane listP_JSP = new JScrollPane();
	/** 命令输入框:回车执行,上下键翻历史 */
	JTextField input = new JTextField();
	/** 标题栏中央的窗口标题标签 */
	JLabel title = new JLabel();
	/** 底部输入区容器(左提示右输入框) */
	JPanel inputPanel = new JPanel();
	/** 输入框左侧的提示标签("SFW_>") */
	JLabel sysinfo = new JLabel("SFW_>");
	/** 顶部标题栏容器(Sair按钮|标题|Exit按钮) */
	JPanel titlePanel = new JPanel();
	/** 标题栏左侧按钮:切换插件列表显隐 */
	SButton sair = new SButton("Sair");
	/** 标题栏右侧按钮:触发/exit退出 */
	SButton exit = new SButton("Exit");
	/**
	 * 画布选项卡隔离区:printComponent 识别到画布(JPanel/Panel/Canvas)时自动新增一个选项卡;
	 * 与文本流完全隔离,互不影响布局/复制/清屏。 停靠位置:中心面板右侧(EAST,左侧为Sair按钮切换的输出列表),宽度可由用户拖动左侧手柄调整。
	 */
	JTabbedPane tabsPane = new JTabbedPane();
	/**
	 * 隔离区右停靠容器(BorderLayout:[拖动调宽手柄|选项卡面板]),
	 * 挂在centerPanel的EAST;无选项卡时整体隐藏,有选项卡时自动显示。
	 */
	JPanel eastWrap = new JPanel();
	/**
	 * 拖动调宽手柄(6px竖条):悬浮显示左右箭头光标,按住拖动调整eastWrap宽度; 自绘一条主色竖线与三点把手,与窗体描边风格一致。
	 */
	JPanel tabsGrip = new JPanel() {
		private static final long serialVersionUID = 1L;

		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			g.setColor(getForeground());
			int cx = getWidth() / 2;
			g.drawLine(cx, 10, cx, getHeight() - 10);
			g.fillRect(cx - 1, 14, 3, 3);
			g.fillRect(cx - 1, getHeight() / 2 - 1, 3, 3);
			g.fillRect(cx - 1, getHeight() - 18, 3, 3);
		}
	};
	/** 隔离区当前宽度(用户可拖动调整,跨显隐保持;窗口resize时按70%上限收敛) */
	private int tabsWidth = 240;
	/** 隔离区最小宽度(px) */
	private static final int TABS_MIN_W = 120;
	/** 手柄按下时的屏幕X与宽度快照(拖动增量基准,仅EDT) */
	private int gripStartX, gripStartW;
	/** 标签区右键菜单:关闭此面板 / 清除全部(主题联动在reinit_Color) */
	private JPopupMenu tabPopup;
	/** 右键点击的选项卡索引(弹出菜单前记录;-1=空白处) */
	private int popupTabIndex = -1;
	/** 中部容器:文本控制台(中);隔离区已移至centerPanel右侧(eastWrap) */
	JPanel consoleWrap = new JPanel();
	/** 文本控制台(JTextPane,不可编辑):所有printo输出与insertComponent子控件的落点 */
	JTextPane infoPane = new JTextPane();
	/**
	 * 无名称画布的选项卡标题自增序号
	 */
	private static int panelSeq = 0;
	/** 文本控制台滚动容器(字段名保留历史拼写centerScorllPane) */
	JScrollPane centerScorllPane = new JScrollPane();
	/** 背景色(volatile:任意线程写、EDT读,保证跨线程可见) */
	volatile Color bgC = Color.DARK_GRAY;
	/** 默认前景/字体颜色(volatile:任意线程写、EDT读,保证跨线程可见) */
	volatile Color otC = Color.GREEN;
	/** 系统托盘右键菜单(托盘支持时创建) */
	private PopupMenu popup;
	/** 托盘菜单项:重置GUI尺寸、退出 */
	private MenuItem resetSize_popup, close_popup;
	/** 随窗口尺寸等比缩放的当前字体 */
	private Font p_f = font;
	/** 当前背景图路径(null=无背景);resize/setBounds后按此路径重设背景 */
	private String BGpath;
	/** 系统托盘图标(窗口显示时移除、隐藏时挂回) */
	private TrayIcon trayIcon;

	/** 私有构造:按默认800x600创建无边框窗体,依次初始化托盘、组件、监听与配色(仅静态初始化时调用一次) */
	private ConsFrame() {
		super(w, h);
		this.setTitle(title_str);
		this.setDefaultCloseOperation(SFrame.EXIT_ON_CLOSE);
		this.initTary();

		this.initComp();
		this.initAction();

		this.re_init_styles(w, h);

	}

	/**
	 * 获取当前默认前景色(任意线程可读,volatile保证可见性)
	 *
	 * @return 当前默认打印/字体颜色
	 */
	public static Color getFontColor() {
		return cf.otC;
	}

	/**
	 * 设置默认前景色(直印模式:调用线程直接重刷配色,立即生效); 已设背景图时同时重设背景图以叠加新底色。
	 *
	 * @param c
	 *            新前景色
	 */
	public static final void setFontColor(final Color c) {
		cf.otC = c;
		cf.reinit_Color();
		if (cf.BGpath != null)
			setImageBackground(cf.BGpath);
	}

	/**
	 * 显示控制台窗体(等价/show;显示时从系统托盘移除图标)。
	 * 直印模式:调用线程直接执行(不派发EDT);淡入由SFrame后台线程驱动并带防中断保护。
	 */
	public static final void showFrame() {
		cf.setVisible(true);
	}

	/**
	 * 隐藏控制台窗体(隐藏时挂回系统托盘图标)。
	 * 直印模式:调用线程直接执行;SFrame.setVisible(false)会终止淡入线程,残留低透明度不带入下次显示。
	 */
	public static final void hideFrame() {
		cf.setVisible(false);
	}

	/**
	 * 设置窗口不透明度(0.1-1.0):JDK8经Backgrounds走AWTUtilities反射(用户要求保留原版),
	 * JDK10+回退直接API Window.setOpacity;无合成器的Linux环境可能抛IAE被吞并保持原不透明度
	 */
	public static final void setFrameOpacity(float f) {
		if (f < 0.1f)
			f = 0.1f;
		if (f > 1.0f)
			f = 1.0f;
		cf.setFloat(f);
	}

	// /**
	// * @deprecated 已彻底弃用EDT调度:框架所有打印/配色/显隐/组件操作均改为调用线程直接执行。
	// * 方法保留仅为二进制兼容,框架内部不再调用,新代码请勿使用。
	// */
	// @Deprecated
	// public static final void runOnEDT(Runnable r) {
	// if (SwingUtilities.isEventDispatchThread())
	// r.run();
	// else
	// SwingUtilities.invokeLater(r);
	// }

	/**
	 * 设置控制台背景色(直印模式:调用线程直接重刷配色,含选项卡内桌面区同步)。 已设背景图时同时重设背景图以叠加新底色。
	 *
	 * @param c
	 *            新背景色
	 */
	public static final void setBackgroundColor(final Color c) {
		cf.bgC = c;
		cf.reinit_Color();
		if (cf.BGpath != null)
			setImageBackground(cf.BGpath);
	}

	/**
	 * 设置背景图(直印模式:调用线程直接执行); 传null或"null"清空背景;同一文件只解码一次(缓存键=路径|修改时间|大小)。
	 *
	 * @param path
	 *            图片路径(可null=清空)
	 */
	public static final void setImageBackground(final String path) {
		setImageBackground0(path);
	}

	/** setImageBackground实现:解码缓存复用+负缓存防护(失败同一key只提示一次)+失效兜底 */
	private static void setImageBackground0(String path) {
		if (path != null && !"null".equals(path)) {
			// 性能优化:同一背景图只解码一次,换色/resize时仅重绘;文件变化(时间/大小)自动重新解码
			String key = path;
			File file = new File(path);
			if (file.exists())
				key = path + "|" + file.lastModified() + "|" + file.length();
			if (!key.equals(cachedBgKey)) {
				Image img = null;
				try {
					if (file.exists())
						img = BufferedImageTool.readFile(file);
					else
						img = BufferedImageTool.DefaultImageTool.readImage_byBuffer(path);
				} catch (IOException e) {
				}
				// 修复:解码失败不写入负缓存(文件修复后仍可重试);同一key只提示一次
				if (img != null) {
					cachedBgImage = img;
					cachedBgKey = key;
					lastFailedBgKey = null;
				} else if (!key.equals(lastFailedBgKey)) {
					lastFailedBgKey = key;
					SairCons.println(FCM.Error_Color, "背景图加载失败:" + path);
				}
			}
			if (cachedBgImage != null) {
				((A_JPanel) cf.centerPanel).setImg(cachedBgImage);
				cf.centerPanel.repaint();
			} else {
				// 兜底:沿用旧逻辑
				Backgrounds.BG_TOOLS.setNewImageToJPanel(path, cf.centerPanel);
			}
			cf.BGpath = path;
		} else {
			cachedBgKey = null;
			cachedBgImage = null;
			cf.BGpath = null;
			cf.reinit_Color();
		}
	}

	/**
	 * 启动扫光特效(彩虹渐变光带扫过一次,鼠标穿透)。 直印模式:不再用EDT
	 * Timer,改为后台守护线程驱动repaint(repaint线程安全),特效结束自动移除。
	 */
	public static final void playScanline() {
		if (scanlinePlayed)
			return;
		scanlinePlayed = true;
		Thread th = new Thread(new Runnable() {
			public void run() {
				try {
					Thread.sleep(80L);// 等待窗体完成显示
					JLayeredPane lp = cf.getLayeredPane();
					final ScanlinePanel sp = new ScanlinePanel();
					sp.setBounds(0, 0, cf.getWidth(), cf.getHeight());
					lp.add(sp, JLayeredPane.POPUP_LAYER);
					while (sp.pos <= 1.35f) {
						sp.pos += 0.12f;
						sp.repaint();
						Thread.sleep(35L);
					}
					lp.remove(sp);
					lp.repaint();
				} catch (Throwable e) {
				}
			}
		}, "sfw-scanline");
		th.setDaemon(true);
		th.start();
	}

	/**
	 * 设置自定义窗口标题(直印模式:调用线程直接执行); customTitle非null期间不再自动恢复默认标题title_str。
	 *
	 * @param title
	 *            自定义标题
	 */
	public static final void setTitleInfo(final String title) {
		// customTitle = title;
		cf.setTitle(title);
	}

	/**
	 * 控制台打印入口(插件最常用API,直印模式):
	 * <ul>
	 * <li>index==null → 追加到文本流尾部;</li>
	 * <li>index!=null → 定位插入。</li>
	 * </ul>
	 * 调用线程直接执行(恢复旧版方式,不再攒批/不再派发EDT); 文档变更在printLock内完成,多线程打印不会破坏StyledDocument。
	 * c为null用当前默认前景色;text为null打印"null"。
	 *
	 * @param index
	 *            插入位置(null=尾部追加)
	 * @param c
	 *            颜色(null=默认色)
	 * @param text
	 *            文本(null容错)
	 */
	public static final void printo(final Integer index, final Color c, final String text) {
		printo0(index, c, text);
	}

	/** 颜色→属性集缓存(LRU上限256):随机颜色打印不再无界增长;多线程安全(锁内调用) */
	private static SimpleAttributeSet attrOf(Color c) {
		SimpleAttributeSet s = attrCache.get(c);
		if (s == null) {
			s = new SimpleAttributeSet();
			StyleConstants.setForeground(s, c);
			attrCache.put(c, s);
		}
		return s;
	}

	/** 单段实际插入(printLock内):MAX_CONSOLE_TEXT全清兜底+MAX_SINGLE_TEXT超长拒绝+内存预算裁剪 */
	private static void printo0(Integer index, Color c, String text) {
		if (c == null)
			c = cf.otC;
		if (null == text)
			text = "null";

		Document docs = cf.infoPane.getDocument();
		if (index == null)
			index = docs.getLength();
		// 直印模式:调用线程直接插入;printLock串行化文档变更,多线程打印不破坏StyledDocument
		synchronized (printLock) {
			try {
				if (docs.getLength() >= MAX_CONSOLE_TEXT) {
					// 极端兜底:纯文本超上限才全清(平时由内存预算裁剪接管)
					cf.infoPane.setText("");
					consoleSegments = 0;
				}
				if (text.length() > MAX_SINGLE_TEXT) {
					docs.insertString(index, "text is too long", attrOf(c));
					consoleSegments++;
				} else {
					docs.insertString(index, text, attrOf(c));
					consoleSegments++;
					// 修复OOM:StyledDocument每次插入都有固定元素开销(可放大10-50倍),
					// 按估算内存预算裁剪最旧一半,保留近期历史,防止长跑内存暴增
					trimConsoleIfNeeded(docs);
				}
			} catch (BadLocationException ble) {
			}
		}

		// int point = cf.infoPane.getHeight();
		/* JViewport vp = */
		// cf.centerScorllPane.setVerticalScrollBarPolicy(point);
		/*
		 * if (vp != null) vp.setViewPosition(new Point(0, point));
		 */
	}

	/**
	 * 内存预算裁剪:估算 = 文本长度*2字节 + 段落数*每段结构开销。 超预算时删除最旧一半文本(段计数按比例下调),控制台保留近期输出。
	 */
	private static void trimConsoleIfNeeded(Document docs) {
		long estimate = (long) docs.getLength() * 2L + consoleSegments * PER_SEGMENT_BYTES;
		if (estimate <= MAX_CONSOLE_MEMORY)
			return;
		int trimLen = Math.max(1, docs.getLength() / 2);
		try {
			docs.remove(0, trimLen);
			consoleSegments = Math.max(0, consoleSegments / 2);
		} catch (BadLocationException e) {
		}
	}

	/**
	 * 获取控制台文本窗格(供需要直接操作文档的插件使用;直印模式,无EDT约束)
	 *
	 * @return 控制台JTextPane
	 */
	public static final JTextPane getTextPane() {
		return cf.infoPane;
	}

	/**
	 * 控件输出(含类型识别,直印模式:调用线程直接执行): 画布(JPanel/Panel/Canvas)→选项卡隔离区,每块画布一个选项卡;
	 * 内部窗口(JInternalFrame)→桌面选项卡,保留标题栏/移动/最大化(真·窗口嵌套);
	 * 顶层窗口(JFrame/JDialog等)→框架自动拆卸内容区(含菜单栏)入选项卡,旧插件零改动;
	 * 其余子控件(JLabel/JButton等)→默认控制台,插入文本流(原insertComponent语义);
	 * 传null清空选项卡隔离区(与右键菜单"清除全部"/clearComponents()等价,/clear不联动)。
	 */
	public static final void printComponent(final Component component) {
		printComponent0(component);
	}

	/**
	 * 识别逻辑:只有画布类容器才走选项卡,其余全部走默认控制台
	 */
	private static boolean isCanvas(Component c) {
		return c instanceof JPanel || c instanceof java.awt.Panel || c instanceof java.awt.Canvas;
	}

	/** printComponent的EDT实现:类型识别分派(画布/内部窗口/顶层窗口/子控件/null),细节见方法内注释 */
	private static void printComponent0(Component component) {
		if (component == null) {
			clearComponents();
			return;
		}
		if (isCanvas(component)) {
			// 画布→选项卡隔离区(自动生成标题:组件名或序号)
			addCanvasTab(component);
			return;
		}
		if (component instanceof JInternalFrame) {
			// 内部窗口→桌面选项卡(真·窗口嵌套)
			dockInternalFrame((JInternalFrame) component);
			return;
		}
		if (component instanceof java.awt.Window && component != cf) {
			// 顶层窗口→自动拆卸内容区入选项卡(旧插件零改动)
			addCanvasTab(dockWindowContent((java.awt.Window) component));
			return;
		}
		// 子控件→默认控制台文本流(保持旧printComponent语义)
		// 前置守卫:Window或已有父容器的组件不可内嵌——insertComponent(Window)会先污染
		// 文档属性再抛异常,后续任何文档插入都会在视图更新时重复抛出"adding a window"
		if (component instanceof java.awt.Window || component.getParent() != null) {
			ConsFrame.printo0(null, null, "\r\n[控件不可插入:Window或已有父容器]\r\n");
			return;
		}
		try {
			ConsFrame.printo0(null, null, "\r\n");
			int posi = cf.infoPane.getDocument().getLength();
			if (posi < 0)
				posi = 0;
			cf.infoPane.setCaretPosition(posi);
			cf.infoPane.insertComponent(component);
		} catch (Throwable t) {
			// 修复:插入失败输出一行警告,插件能感知打印未生效(原实现完全静默)
			ConsFrame.printo0(null, null, "\r\n[控件插入失败:" + t.getClass().getSimpleName() + "]\r\n");
		}
	}

	/**
	 * 递归透明化(与主界面"全部Opaque=false"风格统一): 把组件树内全部 JComponent 置为不透明
	 * false,让暗色窗体背景透出, 避免 LAF 默认浅色块破坏整体风格(仅处理 JComponent,AWT 组件无 opaque 语义)。
	 * 插件子组件自身绘制不受影响,只影响背景填充。
	 */
	private static void transparentTree(Component root) {
		if (root == null)
			return;
		if (root instanceof JComponent)
			((JComponent) root).setOpaque(false);
		if (root instanceof java.awt.Container) {
			Component[] cs = ((java.awt.Container) root).getComponents();
			for (Component ch : cs)
				transparentTree(ch);
		}
	}

	/**
	 * 画布入选项卡(标题:组件名或自动序号)
	 */
	private static void addCanvasTab(Component canvas) {
		// 风格统一:画布整棵子树递归透明化,与主界面"全部Opaque=false"一致
		transparentTree(canvas);
		String name = canvas.getName();
		if (name == null || name.length() == 0)
			name = "面板" + (++panelSeq);
		cf.tabsPane.addTab(name, canvas);
		cf.tabsPane.setSelectedIndex(cf.tabsPane.getTabCount() - 1);
		updateCompAreaVisible();
	}

	/**
	 * 内部窗口停靠:放入独立JDesktopPane选项卡,保留窗口标题栏/拖动/最大化等MDI能力
	 */
	private static void dockInternalFrame(JInternalFrame f) {
		String title = f.getTitle();
		if (title == null || title.length() == 0)
			title = f.getName();
		if (title == null || title.length() == 0)
			title = "窗口" + (++panelSeq);
		JDesktopPane desk = new JDesktopPane();
		// 风格统一:Metal桌面UI会无条件绘制浅色渐变背景(200,221,242)破坏暗色主题,
		// 换用基础桌面UI(不画背景渐变),配合opaque=false透出窗体暗色背景
		desk.setUI(new javax.swing.plaf.basic.BasicDesktopPaneUI());
		desk.setBackground(cf.bgC);
		desk.setPreferredSize(new Dimension(Math.max(f.getWidth(), 120), Math.max(f.getHeight(), 90)));
		// 风格统一:桌面区与内部窗口整棵子树递归透明化(标题栏为窗口chrome,保留"嵌套窗口"语义)
		desk.setOpaque(false);
		f.setOpaque(false);
		transparentTree(f.getContentPane());
		desk.add(f);
		f.setVisible(true);
		try {
			f.setSelected(true);
		} catch (Throwable t) {
		}
		cf.tabsPane.addTab(title, desk);
		cf.tabsPane.setSelectedIndex(cf.tabsPane.getTabCount() - 1);
		updateCompAreaVisible();
	}

	/**
	 * 顶层窗口停靠(JFrame/JDialog/Frame/Dialog/Window):
	 * 拆出内容区(含JFrame菜单栏)入选项卡,释放原窗口句柄。
	 * 代价:窗口自身的标题栏/setVisible/setTitle不再可用(内容已被框架接管)。
	 */
	private static Component dockWindowContent(java.awt.Window w) {
		String title = null;
		if (w instanceof java.awt.Frame)
			title = ((java.awt.Frame) w).getTitle();
		else if (w instanceof java.awt.Dialog)
			title = ((java.awt.Dialog) w).getTitle();
		java.awt.Container content;
		if (w instanceof javax.swing.RootPaneContainer) {
			javax.swing.RootPaneContainer rpc = (javax.swing.RootPaneContainer) w;
			content = rpc.getContentPane();
			rpc.setContentPane(new JPanel()); // 解绑旧内容区
			if (w instanceof javax.swing.JFrame) {
				javax.swing.JMenuBar mb = ((javax.swing.JFrame) w).getJMenuBar();
				if (mb != null) {
					JPanel wrap = new JPanel(new BorderLayout(0, 0));
					wrap.setOpaque(false);// 风格统一:停靠容器透明化,避免浅色色块
					wrap.add(mb, BorderLayout.NORTH);
					wrap.add(content, BorderLayout.CENTER);
					content = wrap;
				}
			}
			// 风格统一:拆出的内容区整棵子树递归透明化(插件自身绘制不受影响)
			transparentTree(content);
		} else {
			// 纯AWT窗口:子组件平移到画布容器
			JPanel p = new JPanel();
			p.setOpaque(false);// 风格统一:平移容器透明化
			Component[] cs = w.getComponents();
			for (Component ch : cs) {
				w.remove(ch);
				p.add(ch);
			}
			transparentTree(p);
			content = p;
		}
		if (w.isDisplayable())
			w.dispose();
		if (title == null || title.length() == 0)
			title = w.getName();
		if (title == null || title.length() == 0)
			title = "窗口" + (++panelSeq);
		content.setName(title);
		return content;
	}

	/**
	 * 清空选项卡隔离区(隔离区随之隐藏;直印模式:调用线程直接执行)
	 */
	public static final void clearComponents() {
		cf.tabsPane.removeAll();
		updateCompAreaVisible();
	}

	/** 按选项卡数量同步隔离区(右停靠容器)显隐并重排中心面板(直印模式:调用线程直接执行) */
	private static void updateCompAreaVisible() {
		boolean has = cf.tabsPane.getTabCount() > 0;
		if (cf.eastWrap.isVisible() != has) {
			cf.eastWrap.setVisible(has);
			cf.tabsPane.setVisible(has);
			// 显隐切换后显式重排自身与父链,确保TabbedPaneUI按当前尺寸重新布局标签条
			// (否则隐藏期布局的零宽标签矩形会在显示后残留,药丸不渲染)
			cf.tabsPane.revalidate();
			cf.tabsPane.repaint();
			cf.eastWrap.revalidate();
			cf.centerPanel.revalidate();
			cf.centerPanel.repaint();
		}
	}

	/**
	 * 打印一个文本标签:构造JLabel(颜色c、控制台字体)后走printComponent0,
	 * 即作为子控件插入文本流(保持旧printComponent语义)。直印模式:调用线程直接执行。
	 *
	 * @param c
	 *            标签前景色(null=默认色)
	 * @param labelString
	 *            标签文本
	 */
	public static final void printComponent(final Color c, final String labelString) {
		Color local = c;
		if (local == null)
			local = cf.otC;
		JLabel lab = new JLabel();
		lab.setText(labelString);
		// lab.setEditable(false);
		lab.setFont(cf.p_f);
		lab.setForeground(local);
		lab.setBorder(null);
		// lab.setOpaque(false);
		printComponent0(lab);
	}

	/** printComponent(Color,String)的默认色重载 */
	public static final void printComponent(String labelString) {
		printComponent(null, labelString);
	}

	/**
	 * 删除控制台一段文本(直印模式:调用线程直接执行,printLock串行化):offs/len可null——
	 * 两者皆null删末尾一个字符;offs为null从0开始;len为null或超长删到文档末尾。 段计数按剩余比例同步下调。
	 *
	 * @param offs
	 *            起始偏移(null容错)
	 * @param len
	 *            删除长度(null容错)
	 */
	public static final void dePrinto(final Integer offs, final Integer len) {
		dePrinto0(offs, len);
	}

	/** dePrinto实现:参数归一化后删除(printLock内),并按剩余比例同步段计数保持内存估算准确 */
	private static void dePrinto0(Integer offs, Integer len) {
		synchronized (printLock) {
			Document docs = cf.infoPane.getDocument();
			if (offs == null && len == null) {
				offs = docs.getLength() - 1;
				len = 1;
			} else {
				if (offs == null)
					offs = 0;
				if (len == null || len > docs.getLength())
					len = docs.getLength() - offs;
			}
			try {
				int oldLen = docs.getLength();
				docs.remove(offs, len);
				// 段计数按剩余比例同步下调,保持内存估算准确
				if (oldLen > 0 && docs.getLength() < oldLen)
					consoleSegments = consoleSegments * docs.getLength() / oldLen;
			} catch (Exception ble) {
				// System.out.println("E");
			}
		}

	}

	/** 获取控制台全部文本(插件读取用) */
	public static String getAllText() {
		return cf.infoPane.getText();
	}

	/** 控制台占用估算:文档字符数+选项卡数+嵌入组件数(插件容量查询用) */
	public final static int getPaneSize() {
		int docLen = cf.infoPane.getDocument().getLength();
		int comLen = cf.tabsPane.getTabCount() + cf.infoPane.getComponentCount();
		return docLen + comLen;
	}

	/** 关闭框架并退出进程(System.exit(0);/exit与托盘exit菜单最终走这里) */
	public final static void close() {
		System.exit(0);
	}

	/** 初始化系统托盘(构造期调用):logo缺失时生成16x16占位图,防止new TrayIcon(null)抛异常导致框架无法启动 */
	private void initTary() {
		if (SystemTray.isSupported()) {
			// 修复:logo缺失时生成16x16占位图,避免new TrayIcon(null)抛异常导致框架无法启动
			Image icon = this.getIconImage();
			if (icon == null)
				icon = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			trayIcon = new TrayIcon(icon);
			trayIcon.setImageAutoSize(true);
			trayIcon.setToolTip(this.getTitle());

			popup = new PopupMenu();

			resetSize_popup = new MenuItem("reset GUI");
			resetSize_popup.addActionListener(ClicksAct.clicksActs.clicks_resetGUI);
			resetSize_popup.setFont(font);

			close_popup = new MenuItem("exit");
			close_popup.addActionListener(ClicksAct.clicksActs.clicks_exit);
			close_popup.setFont(font);

			popup.add(resetSize_popup);
			popup.add(close_popup);

			popup.setFont(font);

			trayIcon.addMouseListener(ClicksAct.clicksActs.icoClick);

			trayIcon.setPopupMenu(popup);
		}
	}

	/** 按当前尺寸重算比例/字体/配色(resize与setBounds共用) */
	private void re_init_styles(int w, int h) {

		this.initSize(w, h);
		this.initFont(w, h);
		this.reinit_Color();

	}

	/**
	 * 调整窗体尺寸:先按新尺寸重算样式再调用父类setSize;已设背景图时重设背景(/resize命令入口)。
	 */
	public void setSize(int w, int h) {
		this.re_init_styles(w, h);
		super.setSize(w, h);
		if (BGpath != null)
			setImageBackground(BGpath);
	}

	/** 按宽高计算等比字号((w+h)*0.01)并刷新p_f */
	private void initFont(int w, int h) {
		float wf = w;
		float hf = h;
		float result = (wf * 0.01f) + (hf * 0.01f);
		p_f = Fonts.FONTS_TOOLS.getFont(null, null, result);
	}

	/** 注册交互(构造期调用):Exit/Sair按钮、输入框回车执行与上下键历史、输入框拖动支持 */
	private void initAction() {
		// showList.setModel(dlm);
		infoPane.setEditable(false);

		exit.addActionListener(ClicksAct.clicksActs.clicks_exit);
		sair.addActionListener(ClicksAct.clicksActs.clicks_sair);
		// showList.addMouseListener(listClick);

		Clicks.CLICKS_TOOLS.enterPressesWhenFocused(input, ClicksAct.clicksActs.clicks_enter, null);
		Clicks.CLICKS_TOOLS.enterPressesWhenFocused(input, ClicksAct.clicksActs.clicks_up, KeyEvent.VK_UP);
		Clicks.CLICKS_TOOLS.enterPressesWhenFocused(input, ClicksAct.clicksActs.clicks_down, KeyEvent.VK_DOWN);

		Clicks.CLICKS_TOOLS.drag(input);
	}

	/** 按窗体宽高设置各区域PreferredSize比例(构造/resize共用) */
	private void initSize(int wi, int hi) {
		int h = hi / 20, w = wi / 20;
		Dimension btd = new Dimension((int) (w * 2), (int) (h * 1.7)), ipd = new Dimension((int) (w * 2), h);
		listP_JSP.setPreferredSize(new Dimension((int) (w * 4), h));
		// 隔离区宽度:用户拖动调整值跨resize保持,窗口变小时按70%上限收敛
		tabsWidth = Math.max(TABS_MIN_W, Math.min(tabsWidth, (int) (wi * 0.7f)));
		eastWrap.setPreferredSize(new Dimension(tabsWidth, 0));
		exit.setPreferredSize(btd);
		sair.setPreferredSize(btd);
		sysinfo.setPreferredSize(ipd);
		sysinfo.setHorizontalAlignment(SwingConstants.CENTER);
		sysinfo.setVerticalAlignment(SwingConstants.CENTER);
	}

	/** 滚动条UI复用:首次创建SairScrollBarUI并setUI,之后仅setColors换色+重绘(仅EDT调用) */
	private void setScrollBarUI(JScrollBar bar, int pane, boolean vertical) {
		int idx = pane * 2 + (vertical ? 0 : 1);
		if (!scrollUIInited[idx]) {
			scrollUIInited[idx] = true;
			scrollUIs[idx] = new SairScrollBarUI(otC, otC, otC);
			bar.setUI(scrollUIs[idx]);
		} else {
			scrollUIs[idx].setColors(otC, otC, otC);
			bar.repaint();
		}
	}

	/** 按bgC/otC重刷全部组件配色(EDT调用):含滚动条UI换色、选项卡内桌面区背景同步 */
	private synchronized void reinit_Color() {
		super.centerPanel.setBackground(bgC);
		setOpenSetting(false);
		JComponent[] cts = new JComponent[] { list, listP_JSP, tabsPane, eastWrap, tabsGrip, consoleWrap, input, title,
				inputPanel, sysinfo, titlePanel, sair, exit, infoPane, centerScorllPane, };
		for (JComponent ct : cts) {
			ct.setFont(p_f);
			ct.setOpaque(false);// 透明化
			if (ct instanceof JTextPane) {
				((JTextPane) ct).setSelectedTextColor(cavg(true));
				((JTextPane) ct).setSelectionColor(cavg(false));
			} else {
				ct.setForeground(otC);
				if (ct instanceof AbstractButton)
					((AbstractButton) ct).setContentAreaFilled(false);
				else
					ct.setBorder(null);

				if (ct instanceof JScrollPane) {
					JScrollPane jsp = (JScrollPane) ct;
					jsp.getViewport().setOpaque(false);// 透明化
					int paneIdx = jsp == listP_JSP ? 0 : 1;
					JScrollBar sbv = jsp.getVerticalScrollBar();
					if (sbv != null) {
						// 微优化:UI实例复用,换色时仅更新配色与重绘
						setScrollBarUI(sbv, paneIdx, true);
						sbv.setOpaque(false);// 透明化
					}
					JScrollBar sbh = jsp.getHorizontalScrollBar();
					if (sbh != null) {
						setScrollBarUI(sbh, paneIdx, false);
						sbh.setOpaque(false);// 透明化
					}
					jsp.setBorder(new SBorder(otC));
				}
				if (ct instanceof JTextField) {
					ct.setBorder(new SBorder(otC));
					((JTextField) ct).setSelectedTextColor(cavg(true));
					((JTextField) ct).setSelectionColor(cavg(false));
					((JTextField) ct).setCaretColor(cavg(false));
				}
				if (ct instanceof JList)
					ct.setBorder(new SBorder(FCM.loadExection_Color));
				if (ct instanceof JTabbedPane) {
					// 控件隔离区:背景色与主题联动(SairTabbedPaneUI绘制时实时读取前景/背景色)
					ct.setBackground(bgC);
					ct.repaint();
				}
				for (BorderButton border : getBorders())
					border.setForeground(otC);
			}
		}
		// 选项卡内的桌面区跟随主题换色
		for (int i = 0; i < tabsPane.getTabCount(); i++) {
			Component tc = tabsPane.getComponentAt(i);
			if (tc instanceof JDesktopPane) {
				tc.setBackground(bgC);
				tc.repaint();
			}
		}
		// 标签区右键菜单跟随主题换色
		styleTabPopup();
	}

	/**
	 * 标签区右键菜单配色与主界面统一(背景/前景/字体/边框,仅EDT调用)。 菜单项为框架创建的JMenuItem,直接按主题色套用。
	 */
	private void styleTabPopup() {
		if (tabPopup == null)
			return;
		tabPopup.setBackground(bgC);
		tabPopup.setBorder(new SBorder(otC));
		for (int i = 0; i < tabPopup.getComponentCount(); i++) {
			Component c = tabPopup.getComponent(i);
			if (c instanceof JMenuItem) {
				JMenuItem mi = (JMenuItem) c;
				mi.setFont(p_f);
				mi.setForeground(otC);
				mi.setBackground(bgC);
			}
		}
	}

	/** 组装布局(构造期调用):标题栏/输入区/控制台(含初始隐藏的画布选项卡隔离区)/列表渲染与鼠标适配 */
	private void initComp() {
		input.setColumns(10);

		titlePanel.setLayout(new BorderLayout(0, 0));
		inputPanel.setLayout(new BorderLayout(0, 0));
		centerPanel.setLayout(new BorderLayout(0, 0));

		list.setCellRenderer(new ListCellRenderer());
		list.addMouseListener(new MouseAdapter());
		listP_JSP.setViewportView(list);

		// 画布选项卡隔离区:停靠中心面板右侧(左侧为Sair按钮切换的输出列表),
		// 初始隐藏,printComponent 识别到画布后自动显示;左侧手柄可拖动调整宽度;
		// 安装扁平化UI(圆角药丸标签+主题色联动),与SButton/SBorder自绘风格统一
		tabsPane.setUI(new SairTabbedPaneUI());
		tabsPane.setOpaque(false);
		eastWrap.setLayout(new BorderLayout(0, 0));
		eastWrap.setOpaque(false);
		tabsGrip.setOpaque(false);
		tabsGrip.setPreferredSize(new Dimension(6, 0));
		tabsGrip.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR));
		// 手柄拖动调宽:按下快照屏幕X与宽度,拖动按"向左拖动增宽/向右拖动减宽"换算
		tabsGrip.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mousePressed(java.awt.event.MouseEvent e) {
				gripStartX = e.getXOnScreen();
				gripStartW = tabsWidth;
			}
		});
		tabsGrip.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
			@Override
			public void mouseDragged(java.awt.event.MouseEvent e) {
				int delta = gripStartX - e.getXOnScreen();
				tabsWidth = Math.max(TABS_MIN_W, Math.min((int) (cf.getWidth() * 0.7f), gripStartW + delta));
				eastWrap.setPreferredSize(new Dimension(tabsWidth, 0));
				centerPanel.revalidate();
				centerPanel.repaint();
			}
		});
		eastWrap.add(tabsGrip, BorderLayout.WEST);
		eastWrap.add(tabsPane, BorderLayout.CENTER);
		eastWrap.setVisible(false);
		// 标签区右键菜单:关闭此面板(关闭被右键的选项卡) / 清除全部(清空隔离区)。
		// 注意:/clear 只清控制台文本,隔离区由本菜单管理(用户要求)
		tabPopup = new JPopupMenu();
		final JMenuItem closeTabItem = new JMenuItem("关闭此面板");
		closeTabItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (popupTabIndex >= 0 && popupTabIndex < cf.tabsPane.getTabCount())
					cf.tabsPane.removeTabAt(popupTabIndex);
				updateCompAreaVisible();
			}
		});
		final JMenuItem clearAllItem = new JMenuItem("清除全部");
		clearAllItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				clearComponents();
			}
		});
		tabPopup.add(closeTabItem);
		tabPopup.add(clearAllItem);
		// 右键弹出(按下/释放双触发兼容各平台);空白处右键"关闭此面板"置灰
		tabsPane.addMouseListener(new java.awt.event.MouseAdapter() {
			private void maybeShow(java.awt.event.MouseEvent e) {
				if (!e.isPopupTrigger())
					return;
				int idx = cf.tabsPane.indexAtLocation(e.getX(), e.getY());
				popupTabIndex = idx;
				closeTabItem.setEnabled(idx >= 0);
				styleTabPopup();
				tabPopup.show(cf.tabsPane, e.getX(), e.getY());
			}

			@Override
			public void mousePressed(java.awt.event.MouseEvent e) {
				maybeShow(e);
			}

			@Override
			public void mouseReleased(java.awt.event.MouseEvent e) {
				maybeShow(e);
			}
		});
		consoleWrap.setLayout(new BorderLayout(0, 0));
		consoleWrap.add(centerScorllPane, BorderLayout.CENTER);

		inputPanel.add(input, BorderLayout.CENTER);
		inputPanel.add(sysinfo, BorderLayout.WEST);

		titlePanel.add(sair, BorderLayout.WEST);
		titlePanel.add(exit, BorderLayout.EAST);
		titlePanel.add(title, BorderLayout.CENTER);


		DefaultCaret dc = (DefaultCaret) infoPane.getCaret();
		dc.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
		centerScorllPane.setViewportView(infoPane);

		centerPanel.add(inputPanel, BorderLayout.SOUTH);
		centerPanel.add(titlePanel, BorderLayout.NORTH);
		centerPanel.add(consoleWrap, BorderLayout.CENTER);
		centerPanel.add(eastWrap, BorderLayout.EAST);

	}

	/** 覆写显隐:显示时从系统托盘移除图标,隐藏时挂回托盘(最小化到托盘能力) */
	public void setVisible(boolean b) {
		super.setVisible(b);
		try {
			if (trayIcon != null)
				if (b)
					SystemTray.getSystemTray().remove(trayIcon);
				else
					SystemTray.getSystemTray().add(trayIcon);
		} catch (AWTException e) {

		}
	}

	/** 覆写setBounds:改bounds后按新尺寸重算样式;已设背景图时重设背景 */
	public void setBounds(Rectangle o_b) {
		super.setBounds(o_b);

		this.re_init_styles(o_b.width, o_b.height);
		if (BGpath != null)
			setImageBackground(BGpath);
	}

	/** 辅助配色:ist=true返回背景色(选中文本色),false返回前景色(选区高亮色) */
	private Color cavg(boolean ist) {
		if (!ist)
			return otC;
		else
			return bgC;
	}
}

/**
 * 扫光特效面板:一条半透明渐变光带扫过窗体,鼠标穿透,只做纯重绘
 */
class ScanlinePanel extends JPanel {
	private static final long serialVersionUID = 1L;
	/** 光带横向位置(0~1.35,超1.35即结束);仅EDT(Timer回调)读写 */
	float pos = -0.35f;

	// 彩虹渐变(静态复用,每帧只有位置变化,零重算)
	private static final float[] RAINBOW_F = { 0f, 0.14f, 0.29f, 0.43f, 0.57f, 0.71f, 0.86f, 1f };
	private static final Color[] RAINBOW_C = { //
			new Color(255, 255, 255, 0), // 透明边缘
			new Color(255, 60, 60, 110), // 红
			new Color(255, 180, 40, 110), // 橙
			new Color(255, 255, 60, 110), // 黄
			new Color(60, 220, 80, 110), // 绿
			new Color(40, 160, 255, 110), // 青
			new Color(160, 80, 255, 110), // 紫
			new Color(255, 255, 255, 0) // 透明边缘
	};

	/** 构造:非透明=false,自身不绘制底色(光带绘制在paintComponent) */
	ScanlinePanel() {
		setOpaque(false);
	}

	@Override
	public boolean contains(int x, int y) {
		return false; // 鼠标穿透,不拦截交互
	}

	/** 绘制:按pos定位的360px宽彩虹渐变光带(LinearGradientPaint静态配色,仅EDT调用) */
	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g;
		int x = (int) (pos * getWidth());
		LinearGradientPaint gp = new LinearGradientPaint(x - 180, 0, x + 180, 0, RAINBOW_F, RAINBOW_C);
		g2.setPaint(gp);
		g2.fillRect(x - 180, 0, 360, getHeight());
	}
}
