package sair.sys.gui.swing.control;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;

import javax.swing.border.AbstractBorder;

/**
 * 圆角边框:单色圆角矩形描边(圆角8px),框架文本框/滚动容器/列表的统一边框风格。
 * <p>
 * 架构角色:AbstractBorder子类(无内边距),ConsFrame.reinit_Color 中为 JTextField/JScrollPane/JList 安装。
 * <p>
 * 线程安全:仅EDT绘制;color为null时跳过绘制(防NPE);绘制使用paintBorder入参矩形(x,y,width,height),
 * 带inset的组件不再错位。
 * <p>
 * 二进制兼容:构造签名SBorder(Color)不变。
 */
public class SBorder extends AbstractBorder {
    private static final long serialVersionUID = -224795799907226856L;
    /** 边框颜色(null=不绘制) */
    private Color color;

    /** 构造:记录边框颜色 */
    public SBorder(Color color) {
        this.color = color;
    }

    /**
     * 绘制圆角矩形边框:使用入参矩形(带inset的组件不再错位),color为null时直接跳过(防NPE)。
     * 仅EDT调用。
     */
    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        // 修复:使用入参矩形(带inset的组件不再错位),color为null时跳过避免NPE
        if (color == null)
            return;
        g.setColor(color);
        g.drawRoundRect(x, y, width - 1, height - 1, 8, 8);
    }

}
