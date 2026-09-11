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
 * <b>架构角色：</b>SFW 自绘控件家族成员——控制台/列表所有 JScrollPane 的滚动条 UI；
 * ConsFrame.reinit_Color 每次换色时以 new SairScrollBarUI(otC,otC,otC) 重建（原版方式，无实例复用）。
 * <p>
 * <b>原版视觉约定（勿改）：</b>轨道底色为全透明色 NUL(new Color(0,0,0,0))，绘制时叠加 30% 透明合成；
 * 滑块以 50% 透明合成 + 垂直渐变色（center→DARK_GRAY）填充，先以 Color.BLACK 描圆角边框。
 * <p>
 * <b>注意：</b>原版实现中 paintTrack/paintThumb 直接修改传入 Graphics 的状态（composite/translate），
 * 属有意为之的历史写法，恢复原版时勿改为 create/dispose 隔离写法。
 * <p>
 * <b>二进制兼容：</b>两个公开构造器签名不变；本类无公开方法可被插件扩展（按原版）。
 *
 * @version 1.0
 */
public class SairScrollBarUI extends BasicScrollBarUI {

    /** 全透明色：轨道底色（原版静态字段，勿改——0.5.3 即如此） */
    private static Color NUL = new Color(0, 0, 0, 0);

    /** 三色配色：up_left/center/down_right 分别用于增加按钮边框、中间拉条、减少按钮边框（原版字段名与实际按钮方位相反：up_left 实际给增加按钮、down_right 实际给减少按钮，勿改字段名） */
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
     * 三色构造器
     * <p>
     *
     * @param up_left    增加按钮的边框颜色（null=深灰；原版字段名与实际按钮方位相反，勿改）
     * @param center     中间拉条的颜色（null=黑）
     * @param down_right 减少按钮边框的颜色（null=深灰；原版字段名与实际按钮方位相反，勿改）
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

    /**
     * 安装配色：轨道底色置为全透明 NUL（配合 paintTrack 的 30% 合成实现"无轨道"视觉）。
     */
    @Override
    protected void configureScrollBarColors() {
        trackColor = NUL;
    }

    /**
     * 绘制轨道：30% 透明合成后交给父类绘制（父类以 trackColor 填充）。
     */
    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
        super.paintTrack(g, c, trackBounds);
    }

    /**
     * 绘制滑块：平移到滑块位置 → 黑色圆角描边 → 50% 透明合成 + 抗锯齿 +
     * 垂直渐变(center→DARK_GRAY)填充圆角矩形。
     */
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

    /**
     * 增加按钮（垂直滚动条在下端/水平滚动条在右端）：SButton，边框色取 up_left。
     */
    @Override
    protected JButton createIncreaseButton(int orientation) {
        SButton button = new SButton();
        button.setForeground(up_left);
        return button;
    }

    /**
     * 减少按钮（垂直滚动条在上端/水平滚动条在左端）：SButton，边框色取 down_right。
     */
    @Override
    protected JButton createDecreaseButton(int orientation) {
        SButton button = new SButton();
        button.setForeground(down_right);
        return button;
    }

}
