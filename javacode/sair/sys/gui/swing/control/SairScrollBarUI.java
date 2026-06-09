package sair.sys.gui.swing.control;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * 自定义Scroll样式
 * <p>
 *
 * @version 1.0
 */
public class SairScrollBarUI extends BasicScrollBarUI {

    private static Color NUL = new Color(0, 0, 0, 0);

    private Color up_left, center, down_right;

    /**
     * 默认构造器
     * <p>
     * 构造出来的拉条结构为默认灰黑样式
     */
    public SairScrollBarUI() {
        this(null, null, null);
    }

    /**
     * 默认构造器
     * <p>
     *
     * @param up_left    上方或者左方按钮的边框颜色
     * @param center     中间拉条的颜色
     * @param down_right 下方或者右方按钮边框的颜色
     */
    public SairScrollBarUI(Color up_left, Color center, Color down_right) {
        if (up_left == null)
            up_left = Color.DARK_GRAY;
        if (center == null)
            center = Color.BLACK;
        if (down_right == null)
            down_right = Color.DARK_GRAY;
        this.up_left = up_left;
        this.center = center;
        this.down_right = down_right;
    }

    @Override
    protected void configureScrollBarColors() {
        trackColor = NUL;
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
        super.paintTrack(g, c, trackBounds);
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
        g.translate(thumbBounds.x, thumbBounds.y);
        g.setColor(Color.BLACK);
        g.drawRoundRect(0, 0, thumbBounds.width - 1, thumbBounds.height - 1, 8, 8);
        Graphics2D g2 = (Graphics2D) g;
        RenderingHints rh = new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.addRenderingHints(rh);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        g2.setPaint(new GradientPaint(c.getWidth() / 2, 1, center, c.getWidth() / 2, c.getHeight(), Color.DARK_GRAY));
        g2.fillRoundRect(0, 0, thumbBounds.width - 1, thumbBounds.height - 1, 8, 8);
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        SButton button = new SButton();
        button.setForeground(up_left);
        return button;
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        SButton button = new SButton();
        button.setForeground(down_right);
        return button;
    }

}
