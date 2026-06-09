package sair.sys.gui.swing.control;

import javax.swing.JFrame;

import sair.sys.gui.swing.control.corpuscle.ClicksI;
import sair.sys.gui.swing.tools.Clicks;

public class ClicksJFrame extends JFrame implements ClicksI {

    private static final long serialVersionUID = 541564616456487L;
    private int X, Y;

    public ClicksJFrame() {
        Clicks.CLICKS_TOOLS.setClicks(this);
    }

    @Override
    public JFrame getJFrame() {
        return this;
    }

    @Override
    public int getOldX() {
        return X;
    }

    @Override
    public void setOldX(int x) {
        X = x;
    }

    @Override
    public int getOldY() {
        return Y;
    }

    @Override
    public void setOldY(int y) {
        Y = y;
    }
}
