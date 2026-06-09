package sair.sys.gui.swing.tools;

import java.awt.Font;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import sair.LoaderManager;
import sair.Pathes;
import sair.SairLoader;
import sair.sys.Libraries;
import sair.sys.acticity.Exection;
import sair.user.Activity;

/**
 * Fonts自由字体生成器
 * <p>
 *
 * @author _Sair
 * @version Fonts1.1
 **/
public class Fonts {
	/**
	 * 生成器
	 **/
	public final static Fonts FONTS_TOOLS = new Fonts();

	private Fonts() {
	}

	public Font getFont(String pathUrl, Integer fontStyle, Float fontSize) {
		return getFont(pathUrl, fontStyle, fontSize, null);
	}

	public Font getFont(String pathUrl, Integer fontStyle, Float fontSize, Activity activity) {
		if (pathUrl == null)
			pathUrl = Pathes.fontPath;
		if (fontStyle == null)
			fontStyle = Font.PLAIN;
		if (fontSize == null)
			fontSize = 13.0F;
		Font ft = null;
		InputStream is = null;
		File file = new File(pathUrl);
		try {
			if (file.exists())
				is = new FileInputStream(pathUrl);
			else {
				is = LoaderManager.systemLoader
						.getResourceAsStream(new StringBuilder(pathUrl).deleteCharAt(0).toString());
				if (is == null) {
					is = LoaderManager.getModResStream(pathUrl);
					if (is == null && activity != null) {
						Exection ect = Libraries.exections.get(activity);
						if (ect != null) {
							SairLoader loader = LoaderManager.ExecLoaders.get(ect.getURL());
							if (loader != null)
								is = loader.getResourceAsStream(pathUrl);
						}
					}
				}
			}
			if (is != null) {
				is = new BufferedInputStream(is, 524288);
				ft = Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(fontStyle, fontSize);
			}
		} catch (Exception e) {
			ft = null;
		}
		return ft;
	}
}
