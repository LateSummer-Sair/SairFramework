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
 * <p>
 * <b>架构角色：</b>SFW 自绘控件家族成员——标题栏 Sair/Exit 按钮、滚动条增减按钮、
 * 插件列表渲染器等均使用本类；paintComponent 画填充、paintBorder 画描边，均为圆角矩形。
 * <p>
 * <b>透明化方式（原版约定，勿改）：</b>setContentAreaFilled(false) + setBackground(new Color(0,0,0,0))
 * + setOpaque(false) 三者并用——原版即如此，全透明背景色属有意为之，不要改为仅 setOpaque(false)。
 * <p>
 * <b>命中区域：</b>contains 按内切椭圆判定（原版语义），形状实例按 bounds 缓存。
 * <p>
 * <b>二进制兼容：</b>两个构造器、contains(int,int)、paintComponent/paintBorder 的签名
 * 与命中语义为原版契约，不可修改；shape 为包可见字段（原版如此）。
 *
 * @version 1.0
 */
public class SButton extends JButton {
    /**
     * 序列化版本UID(历史值保留)
     */
    private static final long serialVersionUID = -1964136659486118084L;
    /** 椭圆命中形状缓存（原版字段）：按 bounds 变化时重建 */
    Shape shape;

    /**
     * 带标签构造：把首选尺寸归一为正方形（宽高取较大值），并完成透明化三件套。
     *
     * @param label 按钮文本
     */
    public SButton(String label) {
        super(label);
        Dimension size = getPreferredSize();
        size.width = size.height = Math.max(size.width, size.height);
        setPreferredSize(size);
        setContentAreaFilled(false);
        setBackground(new Color(0, 0, 0, 0));
        setOpaque(false);
    }

    /** 空标签构造 */
    public SButton() {
        this("");
    }

    /**
     * 绘制按钮底色：按下（armed）时用浅灰，否则用背景色（透明），圆角矩形填充后交给父类绘制文本。
     */
    protected void paintComponent(Graphics g) {
        if (getModel().isArmed())
            g.setColor(Color.lightGray);
        else
            g.setColor(getBackground());
        g.fillRoundRect(0, 0, getSize().width - 1, getSize().height - 1, 8, 8);
        super.paintComponent(g);
    }

    /**
     * 绘制边框：以前景色画圆角矩形描边（因此"设置字体颜色=设置边框颜色"，见类注释）。
     */
    protected void paintBorder(Graphics g) {
        g.setColor(getForeground());
        g.drawRoundRect(0, 0, getSize().width - 1, getSize().height - 1, 8, 8);
    }

    /**
     * 命中判定（原版语义）：内切椭圆——椭圆外四角不可点击；shape 仅在 bounds 变化时重建。
     */
    public boolean contains(int x, int y) {
        if (shape == null || !shape.getBounds().equals(getBounds()))
            shape = new Ellipse2D.Float(0, 0, getWidth(), getHeight());
        return shape.contains(x, y);
    }
}
