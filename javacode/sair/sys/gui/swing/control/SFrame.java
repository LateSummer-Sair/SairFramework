package sair.sys.gui.swing.control;

import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.IOException;

import javax.swing.ImageIcon;
import javax.swing.JPanel;

import sair.Pathes;
import sair.sys.gui.swing.tools.Backgrounds;
import sair.sys.gui.swing.tools.BorderButton;
import sair.sys.gui.swing.tools.BufferedImageTool;

/**
 * SFrame自由视图窗体
 * <p>
 * 此版本可在WindowBuilder内显示并编辑
 *
 * @author _Sair
 * @version SFrame1.6
 **/
public class SFrame extends ClicksJFrame {
	private static final long serialVersionUID = 6784222247951450980L;
	private static final Image ICON = getIcon();
	/**
	 * 默认的中心空间（JPanel）
	 */
	protected JPanel centerPanel = new A_JPanel();
	private boolean isSetting = false, isOpenSetting = false;
	private float Floated = 0.9f, upFloted;
	private BorderButton[] borders;
	private boolean running = false;

	/**
	 * 空的构造方法搭配本类中的set(w,h)方法使用可以更加灵活控制centerPanel
	 * <p>
	 **/
	protected SFrame() {
	}

	/**
	 * 带参数构造方法
	 * <p>
	 * 此方法将从子类设置的参数中来设置窗体大小，从而直接生成一个空的可控窗体<br>
	 *
	 * @param w
	 *            窗体横向长度
	 * @param h
	 *            窗体纵向高度
	 **/
	public SFrame(int w, int h) {
		set(w, h);
	}

	public static final Image getIcon() {
		Image im = null;
		try {
			String path = Pathes.logoPath;
			im = BufferedImageTool.DefaultImageTool.readImage_byBuffer(path);
			return im;
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * 用于反射获取centerPanel预留的方法
	 */
	public JPanel getCenter() {
		return centerPanel;
	}

	/**
	 * 此方法可搭配空的构造方法使用
	 * <p>
	 * 此方法将从子类设置的参数中来设置窗体大小，从而直接生成一个空的可控窗体<br>
	 *
	 * @param w
	 *            窗体横向长度
	 * @param h
	 *            窗体纵向高度
	 **/
	protected void set(int w, int h) {
		setBounds(0, 0, w, h);
		setLocationRelativeTo(null);
		if (ICON != null)
			setIconImage(ICON);
		setUndecorated(true);
		borders = BorderButton.setDefaultBorderButtons(this);

	}

	/**
	 * SFrame内部方法
	 **/
	public void selectBgimg() {
		if (this.getCenter() instanceof A_JPanel && this.isOpenSetting) {
			try {
				Point p = this.getLocation();
				super.setVisible(false);
				Robot rbt = new Robot();
				BufferedImage background = rbt.createScreenCapture(new Rectangle((int) p.getX() + 1, (int) p.getY() + 1,
						this.getWidth() - 2, this.getHeight() - 2));
				background = rbt.createScreenCapture(new Rectangle((int) p.getX() + 1, (int) p.getY() + 1,
						this.getWidth() - 2, this.getHeight() - 2));
				background = rbt.createScreenCapture(new Rectangle((int) p.getX() + 1, (int) p.getY() + 1,
						this.getWidth() - 2, this.getHeight() - 2));
				background = rbt.createScreenCapture(new Rectangle((int) p.getX() + 1, (int) p.getY() + 1,
						this.getWidth() - 2, this.getHeight() - 2));

				float[] data = { 0.0625f, 0.125f, 0.0625f, 0.125f, 0.125f, 0.125f, 0.0625f, 0.125f, 0.0625f, };
				Kernel kernel = new Kernel(4, 2, data);
				ConvolveOp co = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
				BufferedImage background2 = null;
				background2 = co.filter(background, background2);
				super.setVisible(true);
				ImageIcon bg = new ImageIcon(background2);
				try {
					((A_JPanel) this.getCenter()).setImg(bg.getImage());
					((A_JPanel) this.getCenter()).repaint();
				} catch (Exception e) {
				}
			} catch (Exception ex) {
			}
		}
	}

	/**
	 * SFrame内部方法
	 **/
	public void setcenterNULL() {
		if (this.getCenter() instanceof A_JPanel && this.isOpenSetting) {
			((A_JPanel) this.getCenter()).setImg(null);
			((A_JPanel) this.getCenter()).repaint();
		}
	}

	/**
	 * SFrame内部方法
	 **/
	public float getFloat() {
		return Floated;
	}

	/**
	 * SFrame内部方法
	 **/
	public void setFloat(float f) {
		upFloted = Floated;
		Floated = f;
		Backgrounds.BG_TOOLS.setNewFrameToTransparent(f, this);
	}

	/**
	 * SFrame内部方法
	 **/
	public float getUpFloted() {
		return upFloted;
	}

	/**
	 * SFrame内部方法
	 **/
	public boolean isSetingFloated() {
		return isSetting;
	}

	/**
	 * SFrame内部方法
	 **/
	public void setSetingFloated(boolean isfloted) {
		this.isSetting = isfloted;
	}

	/**
	 * 获取高斯模糊打开状态
	 * <p>
	 * <br>
	 *
	 * @return boolean类型
	 **/
	public boolean isOpenSetting() {
		return isOpenSetting;
	}

	/**
	 * 设置高斯模糊打开状态
	 * <p>
	 * <br>
	 *
	 * @param isOpenSetting
	 *            设置的布尔值
	 * @return SFrame(this)
	 **/
	@SuppressWarnings("unchecked")
	public <T> T setOpenSetting(boolean isOpenSetting) {
		this.isOpenSetting = isOpenSetting;
		if (isOpenSetting)
			selectBgimg();
		else if (this.getCenter() instanceof A_JPanel) {
			((A_JPanel) this.getCenter()).setImg(null);
			((A_JPanel) this.getCenter()).repaint();
		}
		return (T) this;
	}

	/**
	 * 获取边框按钮
	 * <p>
	 * <br>
	 *
	 * @return BorderButtons
	 **/

	public BorderButton[] getBorders() {
		return borders;
	}

	public void setVisible(final boolean b) {
		super.setVisible(b);
		if ((!isSetingFloated()) && b && !running) {
			Thread th = new Thread() {
				public void run() {
					thisVisible(b);
				}
			};
			try {
				Thread.sleep(10L);
			} catch (InterruptedException e) {
			}
			th.start();
		}
	}

	private void thisVisible(boolean b) {
		setRunning(true);
		for (float f = 0.0f; f < getFloat() && super.isVisible(); f += 0.01f) {
			Backgrounds.BG_TOOLS.setNewFrameToTransparent(f, this);
			try {
				Thread.sleep(5L);
			} catch (InterruptedException e) {
			}
		}
		setRunning(false);
	}

	public boolean isVisibleRunning() {
		return running;
	}

	private void setRunning(boolean running) {
		this.running = running;
	}

}
