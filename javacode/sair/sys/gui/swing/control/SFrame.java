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
 * SFrame自由视图窗体:框架所有窗口的基类——无边框、可拖动、可半透明、可高斯模糊背景。
 * <p>
 * 架构角色:继承 {@link ClicksJFrame}(拖动支持);中心面板 {@link #centerPanel} 为A_JPanel图片画布,
 * 边框按钮由 BorderButton.setDefaultBorderButtons 生成;ConsFrame 直接继承本类。
 * 此版本可在WindowBuilder内显示并编辑。
 * <p>
 * 线程安全与线程模型(已彻底弃用EDT调度):
 * <ul>
 * <li>{@link #setVisible(boolean)} 的淡入动画由后台守护线程(sfw-fade)驱动——从可见下限0.3f起步,每5ms+0.02递增到目标值;隐藏时立即interrupt该线程并复位running(不再用EDT Timer);</li>
 * <li>{@link #selectBgimg()} 用Robot截屏做3x3卷积高斯模糊:窗体临时隐藏、finally恢复显示(异常不再残留隐藏),
 * 截屏失败(无合成器/Wayland/无权限)仅打印一次性警告(robotWarned);</li>
 * <li>{@link #setFloat(float)} 记录旧值后经 Backgrounds.BG_TOOLS 设置窗体不透明度,调用线程直接执行。</li>
 * </ul>
 * <p>
 * 二进制兼容约束:公开/受保护方法签名不可改——getCenter/set/selectBgimg/setcenterNULL/getFloat/
 * setFloat/getUpFloted/isSetingFloated/setSetingFloated/isSettingFloated/setSettingFloated/
 * isOpenSetting/setOpenSetting/getBorders/setVisible/isVisibleRunning。其中 isSetingFloated/
 * setSetingFloated 为历史拼写错误,正确拼写别名 isSettingFloated/setSettingFloated 与之并存,
 * 旧插件与FrameMouseMotionAdapter仍依赖旧名,必须保留。
 *
 * @author _Sair
 * @version SFrame1.6
 **/
public class SFrame extends ClicksJFrame {
	private static final long serialVersionUID = 6784222247951450980L;
	/** 框架logo静态图标(类加载时解码一次,解码失败为null) */
	private static final Image ICON = getIcon();
	/**
	 * 默认的中心空间（JPanel）
	 */
	protected JPanel centerPanel = new A_JPanel();
	/** 状态位:isSetting=拖动中临时透明标记(别名方法读取),isOpenSetting=高斯模糊背景开关 */
	private boolean isSetting = false, isOpenSetting = false;
	/** 不透明度:Floated=目标值(默认0.9),upFloted=拖动前的旧值(拖动结束恢复) */
	private float Floated = 0.9f, upFloted;
	/** 边框按钮集(set(w,h)时由BorderButton.setDefaultBorderButtons生成) */
	private BorderButton[] borders;
	/** 淡入动画进行中标记(任意线程读写,volatile保证可见) */
	private volatile boolean running = false;
	/** 淡入动画线程引用:隐藏时用于interrupt终止动画并复位状态 */
	private Thread fadeThread;
	/** 高斯模糊截屏失败一次性警告开关(进程内只提示一次) */
	private static boolean robotWarned = false;

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

	/** 加载框架logo图标(失败返回null;ICON静态初始化时调用) */
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
		// 第1步:按参数定窗体尺寸并居中到屏幕
		setBounds(0, 0, w, h);
		setLocationRelativeTo(null);
		// 第2步:安装logo图标(缺失时跳过)
		if (ICON != null)
			setIconImage(ICON);
		// 第3步:去掉系统边框(自绘风格)
		setUndecorated(true);
		// 第4步:生成边框按钮集(最小化/关闭等,由BorderButton工具创建)
		borders = BorderButton.setDefaultBorderButtons(this);

	}

	/**
	 * 高斯模糊背景:临时隐藏窗体→Robot截屏→3x3卷积核模糊→写入中心A_JPanel→finally恢复显示。
	 * 仅在高斯模糊开关(isOpenSetting)打开且中心面板为A_JPanel时生效;截屏失败一次性警告(robotWarned)。
	 * 由 setOpenSetting(true) 调用,调用线程直接执行。
	 **/
	public void selectBgimg() {
		if (this.getCenter() instanceof A_JPanel && this.isOpenSetting) {
			try {
				// 第1步:记录窗体位置(截屏区域以此定位)
				Point p = this.getLocation();
				// 第2步:临时隐藏窗体,避免截到自身(截到的即窗后的桌面内容)
				super.setVisible(false);
				try {
					// 第3步:Robot截取窗体区域屏幕内容
					Robot rbt = new Robot();
					BufferedImage background = rbt.createScreenCapture(new Rectangle((int) p.getX() + 1,
							(int) p.getY() + 1, this.getWidth() - 2, this.getHeight() - 2));

					// 第4步:3x3卷积核(近似高斯)模糊
					float[] data = { 0.0625f, 0.125f, 0.0625f, 0.125f, 0.125f, 0.125f, 0.0625f, 0.125f, 0.0625f, };
					Kernel kernel = new Kernel(3, 3, data);
					ConvolveOp co = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
					BufferedImage background2 = co.filter(background, null);
					// 第5步:写入中心A_JPanel并重绘("毛玻璃"背景)
					ImageIcon bg = new ImageIcon(background2);
					((A_JPanel) this.getCenter()).setImg(bg.getImage());
					((A_JPanel) this.getCenter()).repaint();
				} finally {
					// 第6步:finally恢复显示——模糊处理异常时窗体不再残留隐藏
					super.setVisible(true);
				}
			} catch (Exception ex) {
				// 修复:截屏失败打印一次性提示(Wayland/无权限环境无声失效排查难)
				if (!robotWarned) {
					robotWarned = true;
					System.err.println("[SFrame] 高斯模糊截屏失败(无合成器/Wayland/无权限): " + ex);
				}
			}
		}
	}

	/**
	 * 清除中心面板背景图并重绘(拖动窗体时临时清图用);由FrameMouseMotionAdapter拖动回调调用(系统在EDT派发的鼠标事件)。
	 **/
	public void setcenterNULL() {
		if (this.getCenter() instanceof A_JPanel && this.isOpenSetting) {
			((A_JPanel) this.getCenter()).setImg(null);
			((A_JPanel) this.getCenter()).repaint();
		}
	}

	/**
	 * 获取目标不透明度(淡入动画的终点值;淡入线程与拖动流程读取)。
	 *
	 * @return 当前目标不透明度
	 **/
	public float getFloat() {
		return Floated;
	}

	/**
	 * 设置窗体不透明度:记录旧值到upFloted后经Backgrounds.BG_TOOLS应用新值;调用线程直接执行。
	 *
	 * @param f 新不透明度
	 **/
	public void setFloat(float f) {
		upFloted = Floated;
		Floated = f;
		Backgrounds.BG_TOOLS.setNewFrameToTransparent(f, this);
	}

	/**
	 * 获取设置新透明度前的旧值(拖动结束恢复用;FrameMouseAdapter释放回调经此恢复——系统在EDT派发的鼠标事件)。
	 **/
	public float getUpFloted() {
		return upFloted;
	}

	/**
	 * 是否处于拖动临时透明状态(历史拼写错误的方法名,签名不可改)。
	 *
	 * @return isSetting
	 **/
	public boolean isSetingFloated() {
		return isSetting;
	}

	/**
	 * 拼写正确的别名(原方法名拼写错误,保留旧方法以兼容旧插件)
	 */
	public boolean isSettingFloated() {
		return isSetting;
	}

	/**
	 * 设置拖动临时透明状态(历史拼写错误的方法名,签名不可改)。
	 *
	 * @param isfloted isSetting新值
	 **/
	public void setSetingFloated(boolean isfloted) {
		this.isSetting = isfloted;
	}

	/**
	 * 拼写正确的别名(原方法名拼写错误,保留旧方法以兼容旧插件)
	 */
	public void setSettingFloated(boolean isfloted) {
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
			// 打开:立即抓屏做高斯模糊背景
			selectBgimg();
		else if (this.getCenter() instanceof A_JPanel) {
			// 关闭:清空中心面板背景图并重绘
			((A_JPanel) this.getCenter()).setImg(null);
			((A_JPanel) this.getCenter()).repaint();
		}
		// 返回自身供链式调用(泛型强转兼容旧调用方)
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

	/**
	 * 覆写setVisible(已彻底弃用EDT:恢复旧版后台线程淡入方式,并保留防隐藏保护):
	 * <p>
	 * <b>显示(b=true)</b>——先super显示,再启动守护后台线程淡入(每5ms+0.02递增不透明度至目标,
	 * 旧版线程模型)。淡入从<b>可见下限0.3f</b>开始,保证任何中断/异常都不会留下
	 * "几乎透明看不见"的窗口;若淡入已在途(running)或被拖动临时透明禁用,
	 * 则直接恢复目标不透明度,窗口必然可见。
	 * </p>
	 * <p>
	 * <b>隐藏(b=false)</b>——super隐藏后<b>立即interrupt淡入线程并复位running</b>,
	 * 防止/hide与/show快速交替时,残留的淡入把刚显示的窗口重新压回低透明度。
	 * 修复目标:双击run.bat启动时autorun.ir中的/hide与淡入竞态导致的"窗口不可见且无法打开"。
	 * </p>
	 */
	public void setVisible(final boolean b) {
		super.setVisible(b);
		if (!b) {
			// 隐藏:终止淡入线程,复位动画状态(低透明度不会被带入下一次显示)
			if (fadeThread != null) {
				fadeThread.interrupt();
				fadeThread = null;
			}
			running = false;
			return;
		}
		if (isSetingFloated() || running) {
			// 淡入被禁用或已在途:直接恢复目标不透明度,保证窗口可见(不再跳过导致不可见)
			Backgrounds.BG_TOOLS.setNewFrameToTransparent(getFloat(), this);
			return;
		}
		running = true;
		// 旧版方式:后台线程驱动淡入(不再使用EDT Timer)
		final Thread th = new Thread(new Runnable() {
			public void run() {
				try {
					// 从可见下限起步:即便首个循环即被打断,窗口也处于可见状态
					float f = 0.3f;
					while (f < getFloat() && isVisible()) {
						Backgrounds.BG_TOOLS.setNewFrameToTransparent(f, SFrame.this);
						f += 0.02f;
						Thread.sleep(5L);
					}
					if (isVisible())
						Backgrounds.BG_TOOLS.setNewFrameToTransparent(getFloat(), SFrame.this);
				} catch (InterruptedException e) {
					// 隐藏中断:不恢复透明度(窗口已隐藏)
				} finally {
					fadeThread = null;
					running = false;
				}
			}
		}, "sfw-fade");
		th.setDaemon(true);
		fadeThread = th;
		th.start();
	}

	/** 淡入动画是否进行中(任意线程只读) */
	public boolean isVisibleRunning() {
		return running;
	}

}
