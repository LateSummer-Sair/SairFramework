package sair.sys.gui.swing.tools;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseMotionAdapter;

import sair.sys.gui.swing.control.corpuscle.ClicksI;

class MouseCklicksFactory {
    static MouseAdapter getFrameMouseAdapter(ClicksI clicks) {
        return new FrameMouseAdapter().setC(clicks);
    }

    static MouseMotionAdapter getFrameMouseMotionAdapter(ClicksI clicks) {
        return new FrameMouseMotionAdapter().setC(clicks);
    }


}
