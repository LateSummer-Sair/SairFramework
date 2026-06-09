package sair;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import sair.sys.CJDK;
import sair.sys.SairCons;

class CMDTool {
	static Process executeCommand(String command, File file) {
		BufferedReader reader = null;
		InputStreamReader inputStreamReader = null;
		Process p = null;
		try {
			String[] comds;
			if (CJDK.isWindows())
				comds = new String[] { "cmd", "/c", command };
			else
				comds = new String[] { "/bin/sh", "-c", command };
			if (file != null)
				p = Runtime.getRuntime().exec(comds, null, file);
			else
				p = Runtime.getRuntime().exec(comds);

			new RunThread(p.getInputStream(), "INFO:").start();
			new RunThread(p.getErrorStream(), "ERROR:").start();
			// p.waitFor();
			return p;
		} catch (Exception e) {
			return null;
		} finally {
			try {
				if (reader != null)
					reader.close();
			} catch (IOException e) {
				SairCons.println(Color.RED, "reader 关闭！");
			}
			try {
				if (inputStreamReader != null)
					inputStreamReader.close();
			} catch (IOException e) {
				SairCons.println(Color.RED, "inputStreamReader 关闭！");
			}
		}
	}
}

/**
 * 监听线程
 */
class RunThread extends Thread {
	InputStream iis;
	String printType;

	RunThread(InputStream iis, String printType) {
		this.iis = iis;
		this.printType = printType;
	}

	public void run() {
		try {
			InputStreamReader isr = new InputStreamReader(iis, "GBK");
			BufferedReader br = new BufferedReader(isr);
			String line = null;
			while ((line = br.readLine()) != null) {
				if ("ERROR:".equals(this.printType))
					SairCons.println(FCM.Error_Color, this.printType + line);
				else
					SairCons.println(this.printType + line);
			}
		} catch (IOException ioe) {
			SairCons.println(Color.RED, "CMD Reader 结束!");
		}
	}
}
