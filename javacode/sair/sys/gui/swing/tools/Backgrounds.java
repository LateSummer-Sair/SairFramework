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
 * Backgrounds自由背景JPanel生成器
 * <p>
 *
 * @author _Sair
 * @version Backgrounds1.2
 **/
public class Backgrounds {
	/**
	 * 生成器
	 **/
	public final static Backgrounds BG_TOOLS = new Backgrounds();

	/**
	 * 暴力将指定的控件内所有子控件变成透明（包括自身）,一点都不优雅
	 * 
	 * @param component
	 *            任意JComponent控件
	 * @param addborder
	 *            是否加边框(null就是不变动)
	 **/
	public final static void setAllOpaque(JComponent component, Boolean addborder) {
		UIManager.put("TabbedPane.contentAreaColor", new Color(0, 0, 0, 0));
		setAllOpaque(component, new HashSet<JComponent>(), addborder);
	}

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
				setAllOpaque(((JComponent) (((JList) c).getCellRenderer())), hash, addborder);
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
			if (c instanceof JComponent)
				setAllOpaque((JComponent) c, hash, addborder);
		}
	}

	/**
	 * 暴力将指定的控件内所有子控件变成透明（包括自身）,一点都不优雅
	 * 
	 * @param frame
	 *            任意frame
	 * @param addborder
	 *            是否加边框(null就是不变动)
	 **/
	public final static void setAllOpaque(SFrame frame, Boolean addborder) {
		if (frame == null)
			return;
		setAllOpaque(frame.getCenter(), addborder);
	}

	private Backgrounds() {
	}

	private Object localObject;

	public A_JPanel createJPanel(String pathUrl) {
		return new A_JPanel(pathUrl);
	}

	public void setGraphicsForWidthHeight(int width, int height, JPanel jp) {
		if (jp == null)
			return;
		A_JPanel ajp = null;
		try {
			ajp = (A_JPanel) jp;
		} catch (Exception e) {
			return;
		}
		if (ajp == null || ajp.getG() == null)
			return;
		ajp.getG().drawImage(ajp.getImg(), 0, 0, width, height, ajp);
		ajp.repaint();
	}

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

	public void setNewFrameToTransparent(float f, JFrame... jf) {
		if (jf == null)
			return;
		for (JFrame frame : jf) {
			if (frame != null)
				try {
					frame.setUndecorated(true);
				} catch (Exception e1) {

				}
			try {
				Class<?> clazz = Class.forName("com.sun.awt.AWTUtilities");
				if (localObject == null)
					localObject = clazz.newInstance();
				Method m = clazz.getMethod("setWindowOpacity", Window.class, float.class);
				m.setAccessible(true);
				m.invoke(localObject, frame, f);
				// com.sun.awt.AWTUtilities.setWindowOpacity(frame, f);
			} catch (Exception e) {
				if (frame != null) {
					try {
						frame.setOpacity(f);
					} catch (Exception ee) {

					}
				}
			}
		}
	}

	public void setNewFrameToOpera(boolean b, JFrame... jf) {
		if (jf == null)
			return;
		for (JFrame frame : jf) {
			if (frame != null)
				try {
					frame.setUndecorated(true);
				} catch (Exception e1) {

				}
			try {
				Class<?> clazz = Class.forName("com.sun.awt.AWTUtilities");
				if (localObject == null)
					localObject = clazz.newInstance();
				Method m = clazz.getMethod("setWindowOpacity", Window.class, boolean.class);
				m.setAccessible(true);
				m.invoke(localObject, frame, b);
				// com.sun.jna.platform.WindowUtils.a();
				// com.sun.awt.AWTUtilities.setWindowOpaque(frame, b);
				// jf.setOpacity(0.3f);
			} catch (Exception e) {
				if (frame != null) {
					try {
						frame.setOpacity(0.1f);
					} catch (Exception ee) {

					}
				}
			}
		}
	}

}
