package sair.sys.gui.swing.control;

import java.awt.Graphics;
import java.awt.Image;
import java.io.File;
import java.io.IOException;

import javax.swing.JPanel;

import sair.sys.gui.swing.tools.BufferedImageTool;
import sair.user.Activity;

/**
 * 图片画布面板:以背景图形式绘制图片并随面板尺寸拉伸,是SFrame中心面板(centerPanel)的默认实现。
 * <p>
 * 架构角色:ConsFrame.setImageBackground 最终把背景图写入本面板;SFrame.selectBgimg 的高斯模糊截屏也写这里。
 * <p>
 * 线程安全:setImg/paintComponent仅EDT调用;图片加载失败时清空旧图并重绘,不再静默保留旧背景。
 * <p>
 * 二进制兼容:公开构造器(A_JPanel()/A_JPanel(String))、getImg/setImg/setNewImageToJPanel两个重载签名保持稳定。
 */
public class A_JPanel extends JPanel {
	private static final long serialVersionUID = -2784605853404141634L;
	/** 当前背景图(null=无背景);仅EDT读写 */
	private Image img;

	/** 空构造:无背景图 */
	public A_JPanel() {
	}

	/** 构造即加载指定路径背景图(路径语义见setNewImageToJPanel) */
	public A_JPanel(String Url) {
		setNewImageToJPanel(Url);
	}

	/** 获取当前背景图 */
	public Image getImg() {
		return img;
	}

	/** 直接设置背景图(调用方负责repaint);仅EDT调用 */
	public void setImg(Image img) {
		this.img = img;
	}

	/** 加载背景图(activity为null的重载):本地文件走readFile,否则按包资源readPackage */
	public void setNewImageToJPanel(String pathUrl) {
		setNewImageToJPanel(pathUrl, null);
	}

	/** 加载背景图:文件存在读本地,否则作为包内资源读取;IO异常时清空旧图并重绘 */
	public void setNewImageToJPanel(String pathUrl, Activity activity) {
		Image image = null;
		File file = new File(pathUrl);
		try {
			if (file.exists())
				image = BufferedImageTool.readFile(file);
			else
				image = BufferedImageTool.readPackage(pathUrl, activity);
		} catch (IOException e) {
			// 修复:加载失败清空旧图并重绘,不再静默保留旧背景
			this.img = null;
			repaint();
			return;
		}
		this.img = image;
	}

	/** 绘制:先画面板底色,再按面板宽高拉伸绘制背景图(仅EDT调用) */
	@Override
	public void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (this.img != null)
			g.drawImage(img, 0, 0, getWidth(), getHeight(), this);
	}
}
