package sair.sys.gui.swing.tools;

import java.awt.Color;
import java.awt.Component;
import java.awt.Window;
import java.lang.reflect.Method;
import java.util.HashSet;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeCellRenderer;

import sair.sys.gui.swing.control.A_JPanel;
import sair.sys.gui.swing.control.SBorder;
import sair.sys.gui.swing.control.SButton;
import sair.sys.gui.swing.control.SFrame;
import sair.sys.gui.swing.control.SairScrollBarUI;

/**
 * <p>
 * Swing 背景与窗口透明度工具类：负责把 {@link SFrame}、{@link A_JPanel} 等控件树整体透明化，
 * 并对窗口设置全透明/半透明效果——框架的<b>透明度/背景核心</b>，全局唯一实例 {@link #BG_TOOLS}。
 * </p>
 * <p>
 * <b>架构角色：</b>位于 <code>sair.sys.gui.swing.tools</code> 工具层，供
 * <code>sair.sys.gui.swing.control</code> 层的 {@link SFrame}、{@link A_JPanel} 等复用。
 * 窗口透明度刻意保留原版对 <code>com.sun.awt.AWTUtilities</code> 的<b>反射调用风格</b>：
 * {@link #getOpacityMethod()} 缓存 <code>setWindowOpacity(Window, float)</code>，
 * {@link #getOpaqueMethod()} 已修正为 <code>setWindowOpaque(Window, boolean)</code>，
 * 不 import 该 JDK 内部类，避免编译期绑定内部 API。
 * </p>
 * <p>
 * <b>线程安全 / EDT 说明：</b>所有公开方法都直接操作 Swing 组件（setOpaque、setBorder、
 * setSize、repaint、setUndecorated、setOpacity 等），<b>必须在事件分发线程（EDT）调用</b>；
 * 反射 Method 缓存字段（{@code cachedOpacityMethod} 等）无同步修饰，仅是单线程高频调用下的
 * 微优化（淡入动画），并发调用只会造成重复查找，不影响正确性。
 * </p>
 * <p>
 * <b>二进制兼容约束：</b>本类全部公开成员——{@link #BG_TOOLS}、两个 {@code setAllOpaque}
 * 重载、{@link #createJPanel(String)}、{@link #setGraphicsForWidthHeight}、
 * {@link #setNewImageToJPanel}、{@link #setNewFrameToTransparent}、
 * {@link #setNewFrameToOpera}——签名均不可修改。JDK 10+ 已整体删除
 * {@code com.sun.awt.AWTUtilities}，反射查找失败时自动回退公开 API
 * {@link Window#setOpacity(float)}（见各方法说明），任何修改都必须保留这条回退链。
 * </p>
 *
 * @author _Sair
 * @version Backgrounds1.2
 **/
public class Backgrounds {
	/**
	 * <p>
	 * 全局唯一生成器单例：透明度/背景透明化操作统一入口。
	 * </p>
	 * <p>
	 * <b>EDT：</b>其方法操作 Swing 组件，须在 EDT 调用；单例本身无公开可变状态
	 * （反射缓存字段见类级说明），线程安全。
	 * </p>
	 **/
	public final static Backgrounds BG_TOOLS = new Backgrounds();

	/**
	 * <p>
	 * 暴力将指定的控件内所有子控件变成透明（包括自身），一点都不优雅：以该控件为根
	 * 递归遍历整棵子控件树并全部透明化。
	 * </p>
	 * <ul>
	 * <li>{@code addborder == null}：不改动任何边框（保持现状）；</li>
	 * <li>{@code addborder == true}：给组件套上 {@link SBorder}（{@link SButton} 除外，
	 * 避免覆盖按钮自身绘制边框）；</li>
	 * <li>{@code addborder == false}：清空边框（{@code setBorder(null)}）。</li>
	 * </ul>
	 * <p>
	 * 内部细节：先全局设置 {@code UIManager.put("TabbedPane.contentAreaColor", 透明色)}，
	 * 再交给带 visited 集合的私有重载递归处理：对 {@link JList} 只处理
	 * {@code instanceof JComponent} 的渲染器（instanceof 防护，避免强转非组件渲染器失败），
	 * 对 {@link JTree} 仅当用户未自定义渲染器时才替换为透明 {@link DefaultTreeCellRenderer}
	 * （避免覆盖插件自己的渲染器），对 {@link JScrollPane}/{@link JScrollBar} 统一换装
	 * {@link SairScrollBarUI}。
	 * </p>
	 * <p>
	 * <b>EDT：</b>必须在 EDT 调用（操作 UIManager 全局状态与 Swing 组件）。
	 * </p>
	 *
	 * @param component
	 *            任意 JComponent 控件（透明化递归的根，null 会静默返回）
	 * @param addborder
	 *            是否加边框（null 就是不变动）
	 **/
	public final static void setAllOpaque(JComponent component, Boolean addborder) {
		UIManager.put("TabbedPane.contentAreaColor", new Color(0, 0, 0, 0));
		setAllOpaque(component, new HashSet<JComponent>(), addborder);
	}

	/**
	 * 私有递归实现：以 visited 集合（{@code hash}）防循环处理（共享渲染器等场景），
	 * 并按控件类型差异化透明化，各分支说明见公开重载 {@link #setAllOpaque(JComponent, Boolean)}。
	 * <p>（原版方法级 rawtypes 抑制保留，泛型集合仅内部使用。）</p>
	 **/
	@SuppressWarnings("rawtypes")
	private final static void setAllOpaque(JComponent component, HashSet<JComponent> hash, Boolean addborder) {
		if (component == null)
			return;
		if (hash.contains(component))
			return;
		component.setBackground(null);
		component.setOpaque(false);
		if (addborder == null) {
		} else if (addborder.equals(true) && !(component instanceof SButton))
			try {
				component.setBorder(new SBorder(component.getForeground()));
			} catch (IllegalArgumentException e) {

			}
		else if (addborder.equals(false))
			try {
				component.setBorder(null);
			} catch (IllegalArgumentException e) {

			}
		hash.add(component);
		Component[] components = component.getComponents();
		for (Component c : components) {

			if (c instanceof JList) {
				// 修复:渲染器可能不是JComponent,instanceof防护后只处理可处理的
				Object renderer = ((JList) c).getCellRenderer();
				if (renderer instanceof JComponent)
					setAllOpaque((JComponent) renderer, hash, addborder);
			} else if (c instanceof JScrollPane) {
				JScrollPane jsc = (JScrollPane) c;
				JScrollBar vbar = jsc.getVerticalScrollBar();
				JScrollBar hbar = jsc.getHorizontalScrollBar();
				if (vbar != null) {
					vbar.setUI(new SairScrollBarUI());
				}
				if (hbar != null) {
					hbar.setUI(new SairScrollBarUI());
				}
				setAllOpaque(jsc.getViewport(), hash, addborder);
			} else if (c instanceof JScrollBar) {
				((JScrollBar) c).setUI(new SairScrollBarUI());
			} else if (c instanceof JTabbedPane) {
				JTabbedPane jt = ((JTabbedPane) c);
				setAllOpaque(jt.getRootPane(), hash, addborder);
				int len = jt.getTabCount();
				for (int i = 0; i < len; i++) {
					JComponent tabc = (JComponent) jt.getTabComponentAt(i);
					setAllOpaque(tabc, hash, addborder);
					JComponent tabjc = (JComponent) jt.getComponentAt(i);
					setAllOpaque(tabjc, hash, addborder);
				}
				// jt.setUI(ui);
			} else if (c instanceof JRadioButton) {
				setAllOpaque((JRadioButton) c, hash, addborder);
			} else if (c instanceof JTree) {
				// 修复:仅当用户未自定义渲染器时才替换,避免覆盖插件自己的渲染器
				if (((JTree) c).getCellRenderer() == null) {
					TreeCellRenderer r = new DefaultTreeCellRenderer() {
					/**
					 * 
					 */
					private static final long serialVersionUID = -2685398360323195318L;

					{
						updateUI();
					}

					@Override
					public void updateUI() {
						Object old = UIManager.get("Tree.rendererFillBackground");
						try {
							UIManager.put("Tree.rendererFillBackground", false);
							super.updateUI();
						} finally {
							UIManager.put("Tree.rendererFillBackground", old);
						}
					}
				};
				((JTree) c).setCellRenderer(r);
				}
			}
			if (c instanceof JComponent)
				setAllOpaque((JComponent) c, hash, addborder);
		}
	}

	/**
	 * <p>
	 * 暴力将指定窗体（{@link SFrame}）的中心内容区域整体透明化（包括其所有子控件）：
	 * 委托 {@link #setAllOpaque(JComponent, Boolean)} 处理 {@code frame.getCenter()}。
	 * </p>
	 * <p><b>EDT：</b>必须在 EDT 调用。</p>
	 *
	 * @param frame
	 *            任意 SFrame 窗体（null 静默返回）
	 * @param addborder
	 *            是否加边框（null 就是不变动）
	 **/
	public final static void setAllOpaque(SFrame frame, Boolean addborder) {
		if (frame == null)
			return;
		setAllOpaque(frame.getCenter(), addborder);
	}

	/** 私有构造：只允许通过单例 {@link #BG_TOOLS} 使用，禁止外部实例化。 */
	private Backgrounds() {
	}

	/** 反射宿主实例：调用 {@code com.sun.awt.AWTUtilities} 方法时的接收者（保留原版反射风格）。 */
	private Object localObject;
	/**
	 * 反射 Method 缓存（微优化）：淡入动画高频调用，避免每次 getMethod + setAccessible（行为与原版一致）。
	 * 缓存 <code>com.sun.awt.AWTUtilities.setWindowOpacity(Window, float)</code>；
	 * JDK10+ 该内部类已被删除，查找失败时保持 null 并走 {@link Window#setOpacity(float)} 回退。
	 */
	private Method cachedOpacityMethod;
	/** 是否已完成透明度方法反射查找（防重复反射；失败也置 true，缓存结果保持 null）。 */
	private boolean opacityLookupDone = false;
	/**
	 * 反射 Method 缓存：已修正为正确签名
	 * <code>com.sun.awt.AWTUtilities.setWindowOpaque(Window, boolean)</code>
	 * （原版误写的 setWindowOpacity(Window, boolean) 并不存在）；查找失败保持 null 走回退。
	 */
	private Method cachedOpaqueMethod;
	/** 是否已完成不透明方法反射查找（防重复反射；失败也置 true）。 */
	private boolean opaqueLookupDone = false;

	/**
	 * 私有：惰性反射查找并缓存 {@code com.sun.awt.AWTUtilities.setWindowOpacity(Window,float)}
	 * （仅查找一次，结果缓存）。查找失败（如 JDK10+ 该内部类已被删除）返回 null，
	 * 由调用方回退 {@link Window#setOpacity(float)}。
	 *
	 * @return 缓存的反射 Method；不可用时 null
	 **/
	private Method getOpacityMethod() {
		if (!opacityLookupDone) {
			opacityLookupDone = true;
			try {
				Class<?> clazz = Class.forName("com.sun.awt.AWTUtilities");
				if (localObject == null)
					localObject = clazz.newInstance();
				Method m = clazz.getMethod("setWindowOpacity", Window.class, float.class);
				m.setAccessible(true);
				cachedOpacityMethod = m;
			} catch (Exception e) {
			}
		}
		return cachedOpacityMethod;
	}

	/**
	 * 私有：惰性反射查找并缓存 {@code com.sun.awt.AWTUtilities.setWindowOpaque(Window,boolean)}
	 * （正确签名，原版误写的签名已修正）。查找失败返回 null，
	 * 由调用方回退 {@link Window#setOpacity(float)}。
	 *
	 * @return 缓存的反射 Method；不可用时 null
	 **/
	private Method getOpaqueMethod() {
		if (!opaqueLookupDone) {
			opaqueLookupDone = true;
			try {
				Class<?> clazz = Class.forName("com.sun.awt.AWTUtilities");
				if (localObject == null)
					localObject = clazz.newInstance();
				// 修复:正确API是setWindowOpaque(Window,boolean),原签名setWindowOpacity(Window,boolean)不存在
				Method m = clazz.getMethod("setWindowOpaque", Window.class, boolean.class);
				m.setAccessible(true);
				cachedOpaqueMethod = m;
			} catch (Exception e) {
			}
		}
		return cachedOpaqueMethod;
	}

	/**
	 * 工厂方法：按背景图路径创建 {@link A_JPanel}（背景图由 A_JPanel 内部加载）。
	 * <p><b>EDT：</b>创建 Swing 组件须在 EDT 调用。</p>
	 *
	 * @param pathUrl 背景图路径（文件路径或类路径资源）
	 * @return 新建的 A_JPanel
	 **/
	public A_JPanel createJPanel(String pathUrl) {
		return new A_JPanel(pathUrl);
	}

	/**
	 * <p>
	 * 设置 {@link A_JPanel} 的目标绘制尺寸：直接调整组件 size 并触发重绘。
	 * </p>
	 * <p>
	 * 修复版语义：不再跨 paint 周期借用临时 {@code Graphics}（那是无效用法），
	 * 改为 resize + repaint，由 {@code paintComponent} 按组件新尺寸自行绘制。
	 * 面板不是 {@link A_JPanel} 时静默忽略。
	 * </p>
	 * <p><b>EDT：</b>必须（setSize/repaint）。</p>
	 *
	 * @param width 目标宽度（像素）
	 * @param height 目标高度（像素）
	 * @param jp 目标面板（仅 A_JPanel 生效，其余忽略）
	 **/
	public void setGraphicsForWidthHeight(int width, int height, JPanel jp) {
		if (jp == null)
			return;
		A_JPanel ajp = jp instanceof A_JPanel ? (A_JPanel) jp : null;
		if (ajp == null)
			return;
		// 修复:不再跨paint周期使用临时Graphics;改为调整尺寸并重绘,由paintComponent按组件尺寸绘制
		ajp.setSize(width, height);
		ajp.repaint();
	}

	/**
	 * 运行时更换 {@link A_JPanel} 的背景图并立即重绘；
	 * 参数非法（null、或面板不是 A_JPanel）时静默返回。
	 * <p><b>EDT：</b>必须。</p>
	 *
	 * @param pathUrl 新背景图路径
	 * @param jp 目标面板
	 **/
	public void setNewImageToJPanel(String pathUrl, JPanel jp) {
		if (jp == null)
			return;
		A_JPanel ajp = null;
		try {
			ajp = (A_JPanel) jp;
		} catch (Exception e) {
			return;
		}
		ajp.setNewImageToJPanel(pathUrl);
		ajp.repaint();
	}

	/**
	 * <p>
	 * 将若干 {@link JFrame} 设置为指定透明度（0.0f 全透明 ~ 1.0f 不透明）。
	 * </p>
	 * <p>
	 * 实现顺序（保留原版 AWTUtilities 反射风格）：
	 * </p>
	 * <ol>
	 * <li>尝试 {@code setUndecorated(true)} 去除系统装饰（失败静默忽略）；</li>
	 * <li>通过 {@link #getOpacityMethod()} 缓存的反射 {@code setWindowOpacity(Window,float)}
	 * 设置透明度；</li>
	 * <li>反射失败（JDK10+ 已删除 {@code com.sun.awt.AWTUtilities}）时回退公开 API
	 * {@link Window#setOpacity(float)}。</li>
	 * </ol>
	 * <p>所有异常静默吞掉，绝不抛出；null 帧跳过。</p>
	 * <p><b>EDT：</b>必须。</p>
	 *
	 * @param f 目标透明度 0.0f~1.0f
	 * @param jf 变长参数：一个或多个 JFrame（null 元素跳过）
	 **/
	public void setNewFrameToTransparent(float f, JFrame... jf) {
		if (jf == null)
			return;
		for (JFrame frame : jf) {
			if (frame != null)
				try {
					frame.setUndecorated(true);
				} catch (Exception e1) {

				}
			boolean done = false;
			Method m = getOpacityMethod();
			if (m != null) {
				try {
					m.invoke(localObject, frame, f);
					done = true;
					// com.sun.awt.AWTUtilities.setWindowOpacity(frame, f);
				} catch (Exception e) {
				}
			}
			if (!done && frame != null) {
				try {
					frame.setOpacity(f);
				} catch (Exception ee) {

				}
			}
		}
	}

	/**
	 * <p>
	 * 设置窗口“实心/半透明”状态（方法名 Opera 即 Opacity 变体）：
	 * {@code b == true} 恢复不透明（全实）；{@code b == false} 置为 0.1f 半透明。
	 * </p>
	 * <p>
	 * 修复版语义：参数真正生效（原实现回退分支硬编码 0.1f，导致 b=true 也被置为半透明）。
	 * 实现顺序与 {@link #setNewFrameToTransparent} 一致：先去系统装饰，再反射调用已修正签名的
	 * {@code setWindowOpaque(Window,boolean)}（见 {@link #getOpaqueMethod()}）；
	 * 反射失败回退 {@link Window#setOpacity(b ? 1.0f : 0.1f)}；异常静默，null 帧跳过。
	 * </p>
	 * <p><b>EDT：</b>必须。</p>
	 *
	 * @param b true=不透明；false=半透明（0.1f）
	 * @param jf 变长参数：一个或多个 JFrame（null 元素跳过）
	 **/
	public void setNewFrameToOpera(boolean b, JFrame... jf) {
		if (jf == null)
			return;
		for (JFrame frame : jf) {
			if (frame != null)
				try {
					frame.setUndecorated(true);
				} catch (Exception e1) {

				}
			boolean done = false;
			Method m = getOpaqueMethod();
			if (m != null) {
				try {
					m.invoke(localObject, frame, b);
					done = true;
					// com.sun.jna.platform.WindowUtils.a();
					// com.sun.awt.AWTUtilities.setWindowOpaque(frame, b);
					// jf.setOpacity(0.3f);
				} catch (Exception e) {
				}
			}
			if (!done && frame != null) {
				try {
					// 修复:参数语义b=true→不透明(全实),b=false→半透明;原实现硬编码0.1f导致参数失效
					frame.setOpacity(b ? 1.0f : 0.1f);
				} catch (Exception ee) {

				}
			}
		}
	}

}
