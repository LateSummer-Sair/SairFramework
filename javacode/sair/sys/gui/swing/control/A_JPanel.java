package sair.sys.gui.swing.control;

import java.awt.Graphics;
import java.awt.Image;
import java.io.File;
import java.io.IOException;

import javax.swing.JPanel;

import sair.sys.gui.swing.tools.BufferedImageTool;
import sair.user.Activity;

public class A_JPanel extends JPanel {
	private static final long serialVersionUID = -2784605853404141634L;
	private Graphics G;
	private Image img;

	public A_JPanel() {
	}

	public A_JPanel(String Url) {
		setNewImageToJPanel(Url);
	}

	public Image getImg() {
		return img;
	}

	public void setImg(Image img) {
		this.img = img;
	}

	public void setNewImageToJPanel(String pathUrl) {
		setNewImageToJPanel(pathUrl, null);
	}

	public void setNewImageToJPanel(String pathUrl, Activity activity) {
		Image image = null;
		File file = new File(pathUrl);
		try {
			if (file.exists())
				image = BufferedImageTool.readFile(file);
			else
				image = BufferedImageTool.readPackage(pathUrl, activity);
		} catch (IOException e) {
			return;
		}
		this.img = image;
	}

	@Override
	public void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (this.img != null)
			g.drawImage(img, 0, 0, getWidth(), getHeight(), this);
		setG(g);
	}

	public Graphics getG() {
		return G;
	}

	public void setG(Graphics g) {
		G = g;
	}
}
