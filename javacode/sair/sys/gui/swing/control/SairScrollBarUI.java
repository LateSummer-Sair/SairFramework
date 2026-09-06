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
 * 自定义Scroll样式:自绘透明轨道+半透明渐变拉条+圆角增减按钮的BasicScrollBarUI。
 * <p>
 * 架构角色:由ConsFrame.setScrollBarUI安装到列表区/主区滚动条;UI实例复用——换主题色时仅调用
 * {@link #setColors(Color, Color, Color)} 更新配色并重绘,不重建UI(已创建的incrButton/decrButton
 * 同步更新前景色,否则按钮停留在旧配色)。
 * <p>
 * 线程安全:仅EDT创建与绘制;paintTrack/paintThumb用create/dispose隔离Graphics状态,
 * composite/translate不再泄漏到后续绘制;拉条渐变端点改用thumb自身区域,边框色跟随配色。
 * <p>
 * 二进制兼容:两个公开构造器签名不变;setColors为扩展方法,主题换色依赖它。
 *
 * @version 1.0
 */
public class SairScrollBarUI extends BasicScrollBarUI {

    /** 全透明色:轨道底色(静态共享,不可变) */
    private static Color NUL = new Color(0, 0, 0, 0);

    /** 三色配色:上/左按钮色、中间拉条色、下/右按钮色 */
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
        setColors(up_left, center, down_right);
    }

    /**
     * 更新三色配色(微优化:换色时复用UI实例,不再重建)。
     * 注意:必须同步更新已创建的增减按钮前景色,否则按钮颜色会停留在旧配色。
     */
    public void setColors(Color up_left, Color center, Color down_right) {
        if (up_left == null)
            up_left = Color.DARK_GRAY;
        if (center == null)
            center = Color.BLACK;
        if (down_right == null)
            down_right = Color.DARK_GRAY;
        this.up_left = up_left;
        this.center = center;
        this.down_right = down_right;
        if (incrButton != null) {
            incrButton.setForeground(up_left);
            incrButton.repaint();
        }
        if (decrButton != null) {
            decrButton.setForeground(down_right);
            decrButton.repaint();
        }
    }

    @Override
    protected void configureScrollBarColors() {
        trackColor = NUL;
    }

    /** 绘制轨道:create/dispose隔离Graphics状态,30%透明叠加后交给父类绘制 */
    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
        // 修复:create/dispose隔离Graphics状态,composite不再泄漏到后续绘制
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
            super.paintTrack(g2, c, trackBounds);
        } finally {
            g2.dispose();
        }
    }

    /** 绘制拉条:create/dispose隔离translate与composite;按thumb自身区域渐变+圆角描边 */
    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
        // 修复:create/dispose隔离translate与composite;渐变端点改用thumb自身区域;边框色跟随配色
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.translate(thumbBounds.x, thumbBounds.y);
            g2.setColor(down_right);
            g2.drawRoundRect(0, 0, thumbBounds.width - 1, thumbBounds.height - 1, 8, 8);
            RenderingHints rh = new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.addRenderingHints(rh);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            g2.setPaint(new GradientPaint(0, 0, center, 0, thumbBounds.height, Color.DARK_GRAY));
            g2.fillRoundRect(0, 0, thumbBounds.width - 1, thumbBounds.height - 1, 8, 8);
        } finally {
            g2.dispose();
        }
    }

    /** 创建上/左增减按钮(SButton,前景色=up_left) */
    @Override
    protected JButton createIncreaseButton(int orientation) {
        SButton button = new SButton();
        button.setForeground(up_left);
        return button;
    }

    /** 创建下/右增减按钮(SButton,前景色=down_right) */
    @Override
    protected JButton createDecreaseButton(int orientation) {
        SButton button = new SButton();
        button.setForeground(down_right);
        return button;
    }

}
