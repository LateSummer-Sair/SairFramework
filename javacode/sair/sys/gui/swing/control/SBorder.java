package sair.sys.gui.swing.control;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;

import javax.swing.border.AbstractBorder;

public class SBorder extends AbstractBorder {
    private static final long serialVersionUID = -224795799907226856L;
    private Color color;

    public SBorder(Color color) {
        this.color = color;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        g.setColor(color);
        g.drawRoundRect(0, 0, c.getSize().width - 1, c.getSize().height - 1, 8, 8);
    }

}
