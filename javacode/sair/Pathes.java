package sair;

import java.io.File;

import sair.sys.tools.ToolPack;

public class Pathes {

	//public static final String staticfilesDir = ToolPack.getPath() + File.separator + "staticfiles" + File.separator;

	public static final String pluginsDir = ToolPack.getPath() + File.separator + "plugins" + File.separator;
	public static final String printSplit = "--------------------------------------------------";
	public static final String logoPath = "/staticfiles/Sair.png";
	public static final String fontPath = "/staticfiles/Sair.ttf";
	public static final String bootDir = pluginsDir + "bootlib" + File.separator;
	public static final String modDir = pluginsDir + "modlib" + File.separator;
	public static final String execDir = pluginsDir + "exection" + File.separator;
	public static final String dataResDir = ToolPack.getPath() + File.separator + "data" + File.separator;
	public static final String pack_repack_space = "//";

	static {

/*		File sfd = new File(staticfilesDir);
		if (!sfd.exists())
			sfd.mkdirs();*/

		File pfd = new File(pluginsDir);
		if (!pfd.exists())
			pfd.mkdirs();

	}

}
