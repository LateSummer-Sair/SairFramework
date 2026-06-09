package sair.sys.gui.swing.control;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;

import javax.swing.JButton;

/**
 * 透明按钮
 * <p>
 * 可通过设置字体颜色的方式来设置边框颜色
 *
 * @version 1.0
 */
public class SButton extends JButton {
    /**
     *
     */
    private static final long serialVersionUID = -1964136659486118084L;
    Shape shape;

    public SButton(String label) {
        super(label);
        Dimension size = getPreferredSize();
        size.width = size.height = Math.max(size.width, size.height);
        setPreferredSize(size);
        setContentAreaFilled(false);
        setBackground(new Color(0, 0, 0, 0));
        setOpaque(false);
    }

    public SButton() {
        this("");
    }

    protected void paintComponent(Graphics g) {
        if (getModel().isArmed())
            g.setColor(Color.lightGray);
        else
            g.setColor(getBackground());
        g.fillRoundRect(0, 0, getSize().width - 1, getSize().height - 1, 8, 8);
        super.paintComponent(g);
    }

    protected void paintBorder(Graphics g) {
        g.setColor(getForeground());
        g.drawRoundRect(0, 0, getSize().width - 1, getSize().height - 1, 8, 8);
    }

    public boolean contains(int x, int y) {
        if (shape == null || !shape.getBounds().equals(getBounds()))
            shape = new Ellipse2D.Float(0, 0, getWidth(), getHeight());
        return shape.contains(x, y);
    }
}
