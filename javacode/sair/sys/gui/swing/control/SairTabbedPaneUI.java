package sair.sys.gui.swing.control;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;

import javax.swing.plaf.basic.BasicTabbedPaneUI;

/**
 * <p>
 * 扁平化选项卡 UI（控件隔离展示区专用）：为 {@link javax.swing.JTabbedPane} 提供与 SFW 窗体
 * 整体一致的暗色扁平外观——圆角药丸标签、主题色联动、无 LAF 立体边框/高光/阴影。
 * </p>
 * <p>
 * <b>架构角色：</b>自绘控件家族的一员（与 {@link SButton}/{@link SBorder}/{@link SairScrollBarUI}
 * 同风格）；由 ConsFrame 在构建控件隔离区（tabsPane）时一次性安装
 * （{@code tabsPane.setUI(new SairTabbedPaneUI())}）。
 * </p>
 * <p>
 * <b>配色约定（主题联动）：</b>所有颜色在<b>绘制时</b>从目标 JTabbedPane 实时读取——
 * 选中标签填充色 = <code>getForeground()</code>（主色 otC），选中文字色 =
 * <code>getBackground()</code>（背景色 bgC，由 ConsFrame 换色流程写入），未选中标签 =
 * 主色 30/255 半透明填充 + 主色文字；顶部另画一条 2px 主色分隔线与主窗体描边呼应。
 * 因此换色（/setFC /setBC）只需更新属性后 repaint，无需重建 UI。
 * </p>
 * <p>
 * <b>线程安全 / EDT 说明：</b>与所有 Swing UI 一致，仅 EDT 使用；无内部可变状态。
 * </p>
 * <p>
 * <b>二进制兼容约束：</b>独立新类，无历史符号约束；构造器无参。
 * </p>
 */
public class SairTabbedPaneUI extends BasicTabbedPaneUI {

	/** 未选中标签填充的透明度（0-255，主色叠加） */
	private static final int UNSELECTED_ALPHA = 30;

	/** 标签区顶部细分隔线高度（像素） */
	private static final int SEP_HEIGHT = 2;

	/** 标签圆角半径（像素，与 SButton/SBorder 的 8 相近） */
	private static final int ARC = 10;

	/**
	 * 安装默认值：清空 LAF 自带的立体高光/阴影/边框色，改用纯平几何布局。
	 * <p>
	 * 说明：{@code lightHighlight/highlight/shadow/darkShadow} 置 null 后父类不再绘制
	 * 立体边缘；{@code contentBorderInsets} 置零去掉内容区与标签区之间的粗边框；
	 * {@code tabInsets} 控制药丸内边距，{@code tabAreaInsets} 顶部预留分隔线空间。
	 * </p>
	 */
	@Override
	protected void installDefaults() {
		super.installDefaults();
		lightHighlight = highlight = shadow = darkShadow = null;
		focus = Color.BLACK;
		contentBorderInsets = new Insets(0, 0, 0, 0);
		tabAreaInsets = new Insets(SEP_HEIGHT + 2, 4, 0, 4);
		tabInsets = new Insets(2, 10, 2, 10);
		selectedTabPadInsets = new Insets(2, 2, 2, 2);
	}

	/**
	 * 标签区整体绘制：父类画完标签后在标签区顶部补一条主色细分隔线。
	 * <p>
	 * 注意：父类会把 Graphics 裁剪到标签区矩形内，分隔线必须按当前裁剪区顶部绘制
	 * （直接写死 y=1 会被裁剪掉）。
	 * </p>
	 */
	@Override
	protected void paintTabArea(Graphics g, int tabPlacement, int selectedIndex) {
		super.paintTabArea(g, tabPlacement, selectedIndex);
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			Color fg = tabPane.getForeground();
			g2.setColor(fg == null ? Color.GREEN : fg);
			g2.setStroke(new BasicStroke(1.2f));
			Rectangle clip = g.getClipBounds();
			if (clip != null)
				g2.drawLine(clip.x, clip.y, clip.x + clip.width - 1, clip.y);
		} finally {
			g2.dispose();
		}
	}

	/**
	 * 标签背景：选中 = 主色实心圆角药丸；未选中 = 主色半透明圆角药丸。
	 * 颜色绘制时从 tabPane 属性实时读取（主题联动）。
	 */
	@Override
	protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h,
			boolean isSelected) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Color fg = tabPane.getForeground();
			Color base = fg == null ? Color.GREEN : fg;
			if (isSelected) {
				g2.setColor(base);
			} else {
				g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), UNSELECTED_ALPHA));
			}
			g2.fillRoundRect(x, y, w, h, ARC, ARC);
		} finally {
			g2.dispose();
		}
	}

	/**
	 * 标签边框：不绘制（形状完全由背景色块体现，保持纯平）。
	 */
	@Override
	protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h,
			boolean isSelected) {
	}

	/**
	 * 标签文字：选中 = 背景色（深底浅字对比），未选中 = 主色；水平居中绘制。
	 */
	@Override
	protected void paintText(Graphics g, int tabPlacement, Font font, FontMetrics metrics, int tabIndex,
			String title, Rectangle textRect, boolean isSelected) {
		g.setFont(font);
		Color c;
		if (isSelected) {
			Color bg = tabPane.getBackground();
			c = (bg == null || bg.getAlpha() == 0) ? Color.DARK_GRAY : bg;
		} else {
			c = tabPane.getForeground();
		}
		g.setColor(c == null ? Color.GREEN : c);
		int tx = textRect.x + (textRect.width - metrics.stringWidth(title)) / 2;
		g.drawString(title, tx, textRect.y + metrics.getAscent());
	}

	/**
	 * 焦点虚线框：不绘制（纯平风格，键盘可访问性由默认选中管理）。
	 */
	@Override
	protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects, int tabIndex,
			Rectangle iconRect, Rectangle textRect, boolean isSelected) {
	}

	/**
	 * 内容区边框与背景填充：<b>整体不绘制</b>。
	 * <p>
	 * 关键：JDK8 父类实现在此处有
	 * {@code if (tabCount > 0 && (contentOpaque || tabPane.isOpaque()))}
	 * 的内容区整块填充（contentOpaque 在 Metal 下默认 true，颜色为
	 * {@code TabbedPane.contentAreaColor} 的浅色 200,221,242），
	 * 会把透明内容面板整块刷成 LAF 浅色，破坏暗色主题。
	 * 覆写为空后内容区完全透明，由窗体背景透出（与主界面"全部 Opaque=false"一致）。
	 * </p>
	 */
	@Override
	protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
	}
}
