package sair.sys.gui.swing.tools;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import sair.sys.gui.swing.control.SFrame;
import sair.sys.gui.swing.control.corpuscle.ClicksI;

class FrameMouseAdapter extends MouseAdapter {
	private ClicksI c;

	MouseAdapter setC(ClicksI c) {
		this.c = c;
		return this;
	}

	@Override
	public void mousePressed(MouseEvent e) {
		c.setOldX(e.getX());
		c.setOldY(e.getY());
	}

	@Override
	public void mouseReleased(MouseEvent evt) {
		if (c.getJFrame() instanceof SFrame) {
			SFrame sf = (SFrame) (c.getJFrame());
			if (sf.isOpenSetting()) {
				sf.selectBgimg();
				sf.setFloat(((SFrame) (c.getJFrame())).getUpFloted());
				sf.setSetingFloated(false);
			}
		}
	}
}
