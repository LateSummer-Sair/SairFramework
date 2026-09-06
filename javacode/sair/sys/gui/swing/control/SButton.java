package sair.sys.gui.swing.control;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;

import javax.swing.JButton;

/**
 * 透明按钮:自绘圆角矩形(圆角8px),边框色=前景色,填充色=背景色(按下为浅灰)。
 * 可通过设置字体颜色的方式来设置边框颜色。
 * <p>
 * 架构角色:框架全UI的基础按钮——ListCellRenderer继承本类,SairScrollBarUI的增减按钮也用本类。
 * 构造时把PreferredSize归一为正方形(取宽高较大边),保证圆角对称。
 * <p>
 * 线程安全:重绘/命中方法仅EDT调用;{@link #shape} 椭圆命中形状按宽高校验后缓存
 * (原getBounds()恒不等导致每次mousemove重建Ellipse2D,现按实际宽高变化才重建)。
 * <p>
 * 二进制兼容:公开构造器签名SButton(String)/SButton()与自绘行为保持不变;包可见字段shape保留。
 *
 * @version 1.0
 */
public class SButton extends JButton {
    /**
     *
     */
    private static final long serialVersionUID = -1964136659486118084L;
    /** 椭圆命中形状缓存(按宽高变化重建);仅EDT读写 */
    Shape shape;

    /** 构造:PreferredSize归一为正方形,内容区不填充、背景全透明 */
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

    /** 自绘填充:按下(armed)画浅灰,否则画背景色;圆角矩形8px;之后交给父类画文字 */
    protected void paintComponent(Graphics g) {
        if (getModel().isArmed())
            g.setColor(Color.lightGray);
        else
            g.setColor(getBackground());
        g.fillRoundRect(0, 0, getSize().width - 1, getSize().height - 1, 8, 8);
        super.paintComponent(g);
    }

    /** 自绘边框:前景色圆角矩形描边(圆角8px) */
    protected void paintBorder(Graphics g) {
        g.setColor(getForeground());
        g.drawRoundRect(0, 0, getSize().width - 1, getSize().height - 1, 8, 8);
    }

    /** 椭圆命中判定:形状按宽高校验后缓存,点是否落在椭圆内(仅EDT调用) */
    public boolean contains(int x, int y) {
        // 修复:形状缓存按宽高校验(原getBounds()恒不等,每次mousemove都重建Ellipse2D)
        if (shape == null || shape.getBounds().width != getWidth() || shape.getBounds().height != getHeight())
            shape = new Ellipse2D.Float(0, 0, getWidth(), getHeight());
        return shape.contains(x, y);
    }
}
