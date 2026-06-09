package sair.sys.gui.swing.tools;

import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

import sair.sys.gui.swing.control.SFrame;
import sair.sys.gui.swing.control.corpuscle.ClicksI;

class FrameMouseMotionAdapter extends MouseMotionAdapter {

	private ClicksI c;

	MouseMotionAdapter setC(ClicksI c) {
		this.c = c;
		return this;
	}

	@Override
	public void mouseDragged(MouseEvent e) {
		if ((c.getJFrame() instanceof SFrame) && !((SFrame) (c.getJFrame())).isSetingFloated()) {
			SFrame sf = (SFrame) (c.getJFrame());
			if (sf.isOpenSetting()) {
				sf.setcenterNULL();
				sf.setFloat(0.8f);
				sf.setSetingFloated(true);
			}
		}
		int xOnScreen = e.getXOnScreen(), yOnScreen = e.getYOnScreen(), xx = xOnScreen - c.getOldX(),
				yy = yOnScreen - c.getOldY();
		c.getJFrame().setLocation(xx, yy);
	}
}
