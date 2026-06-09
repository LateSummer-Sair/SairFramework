package sair.sys.gui.swing.tools;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.SystemColor;

import javax.swing.JPanel;

import sair.sys.gui.swing.control.SButton;
import sair.sys.gui.swing.control.SFrame;

/**
 * 自由大小窗体的BorderLayout中BorderButton工厂
 * <p>
 *
 * @author _Sair
 * @version BorderButtons1.0
 **/
public class BorderButton extends SButton {

    private static final long serialVersionUID = 6357429865819576304L;

    private static Dimension Dsize = new Dimension(4, 4);
    int fx, fy, nx, ny;
    private int minHeight, minWidth;

    private BorderButton(SFrame frame, String border) {
        setBackground(SystemColor.control);
        setOpaque(false);
        setPreferredSize(Dsize);
        this.minHeight = frame.getHeight();
        this.minWidth = frame.getWidth();
        this.addMouseMotionListener(BorderButton_Clicks.newBorderButton_Click(frame, this, border));
        this.addMouseListener(BorderButton_Clicks.newBorderButton_Click(frame, this, border));
    }

    /**
     * 工厂的主要方法
     * <p>
     * 一键设置BorderLayout<br>
     *
     * @param frame  需要被动态大小的窗体对象
     * @param center 中心显示区域
     * @param size   若此参数为空，将默认尺寸new Dimension(3,3);
     **/
    public static BorderButton[] setDefaultBorderButtons(SFrame frame) {
        if (frame == null)
            return null;

        BorderButton NORTH = new BorderButton(frame, BorderLayout.NORTH);
        BorderButton SOUTH = new BorderButton(frame, BorderLayout.SOUTH);
        BorderButton WEST = new BorderButton(frame, BorderLayout.WEST);
        BorderButton EAST = new BorderButton(frame, BorderLayout.EAST);

        JPanel jp_new = new JPanel();
        Component jp_back = frame.getCenter();

        jp_new.setLayout(new BorderLayout());
        jp_new.add(NORTH, BorderLayout.NORTH);
        jp_new.add(SOUTH, BorderLayout.SOUTH);
        jp_new.add(WEST, BorderLayout.WEST);
        jp_new.add(EAST, BorderLayout.EAST);
        jp_new.add(jp_back, BorderLayout.CENTER);
        frame.setContentPane(jp_new);

        return new BorderButton[]{NORTH, SOUTH, WEST, EAST};
    }

    public int getMinHeight() {
        return minHeight;
    }

    public void setMinHeight(int minHeight) {
        this.minHeight = minHeight;
    }

    public int getMinWidth() {
        return minWidth;
    }

    public void setMinWidth(int minWidth) {
        this.minWidth = minWidth;
    }
}
