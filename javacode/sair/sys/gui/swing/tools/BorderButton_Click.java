package sair.sys.gui.swing.tools;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import sair.sys.gui.swing.control.SFrame;

/**
 * <p>
 * BorderButton 的鼠标适配器工厂：为四边调整按钮提供
 * “按下记屏幕坐标 → 拖动算增量 → 释放按增量改写 {@link SFrame} 边界”的
 * 拖拽调整尺寸行为（文件名 BorderButton_Click，类名 BorderButton_Clicks）。
 * </p>
 * <p>
 * <b>架构角色：</b>包私有（不对外暴露）实现类，仅被 {@link BorderButton} 构造使用；
 * {@link #newBorderButton_Click} 按 BorderLayout 方位分发到 UP/DN/LE/RI 四个内部适配器。
 * </p>
 * <p>
 * <b>线程安全 / EDT 说明：</b>所有方法都是 Swing 鼠标回调，天然在 EDT 执行；
 * 内部使用屏幕绝对坐标（{@code getXOnScreen/getYOnScreen}），
 * 多屏/高 DPI 环境下增量计算稳定。
 * </p>
 * <p>
 * <b>二进制兼容约束：</b>类与成员均为包私有，但 {@link BorderButton} 以编译期调用
 * 依赖（构造中挂接监听器），方法签名不可随意修改；游标常量
 * {@code c_def/c_mov} 为静态共享，不可外部改写。
 * </p>
 */
class BorderButton_Clicks {

    /** 静态共享游标：默认箭头（移出按钮时还原）与移动十字（悬停提示可拖动）。 */
    private final static Cursor c_def = new Cursor(Cursor.DEFAULT_CURSOR);
    private final static Cursor c_mov = new Cursor(Cursor.MOVE_CURSOR);

    /**
     * 工厂：按边框方位返回对应的鼠标适配器（NORTH→UP、SOUTH→DN、WEST→LE、EAST→RI），
     * 未知方位返回 null（调用方需自行判空）。
     *
     * @param frame 所属窗体
     * @param borderButton 挂接的按钮（用于记录 fx/fy/nx/ny 增量）
     * @param border BorderLayout 方位常量
     * @return 对应方位的 MouseAdapter；未知方位返回 null
     **/
    final static MouseAdapter newBorderButton_Click(final SFrame frame, final BorderButton borderButton,
                                                    String border) {
        switch (border) {
            case BorderLayout.NORTH:
                return UP(frame, borderButton);
            case BorderLayout.WEST:
                return LE(frame, borderButton);
            case BorderLayout.EAST:
                return RI(frame, borderButton);
            case BorderLayout.SOUTH:
                return DN(frame, borderButton);
            default:
                return null;
        }
    }

    /**
     * 私有：SOUTH（下边）适配器——按下记 fy，拖动累加 ny，释放时
     * 窗体 height += ny 并做最小高度下限保护（屏幕坐标增量语义）。
     **/
    private static MouseAdapter DN(final SFrame frame, final BorderButton borderButton) {
        return new MouseAdapter() {

            // 下压
            @Override
            public void mousePressed(MouseEvent e) {
                borderButton.fy = e.getYOnScreen();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                borderButton.ny = e.getYOnScreen() - borderButton.fy;
            }

            // 释放
            @Override
            public void mouseReleased(MouseEvent e) {
                int local = frame.getHeight() + borderButton.ny;
                Rectangle o_b = frame.getBounds();
                if (local < borderButton.getMinHeight())
                    local = borderButton.getMinHeight();
                o_b.height = local;
                frame.setBounds(o_b);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                frame.setCursor(c_mov);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                frame.setCursor(c_def);
            }
        };
    }

    /**
     * 私有：EAST（右边）适配器——按下记 fx，拖动累加 nx，释放时
     * 窗体 width += nx 并做最小宽度下限保护。
     **/
    private static MouseAdapter RI(final SFrame frame, final BorderButton borderButton) {
        return new MouseAdapter() {

            // 下压
            @Override
            public void mousePressed(MouseEvent e) {
                borderButton.fx = e.getXOnScreen();

            } // 释放

            @Override
            public void mouseDragged(MouseEvent e) {
                borderButton.nx = e.getXOnScreen() - borderButton.fx;
            }

            // 释放
            @Override
            public void mouseReleased(MouseEvent e) {
                int local = frame.getWidth() + borderButton.nx;
                Rectangle o_b = frame.getBounds();
                if (local < borderButton.getMinWidth())
                    local = borderButton.getMinWidth();
                o_b.width = local;
                frame.setBounds(o_b);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                frame.setCursor(c_mov);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                frame.setCursor(c_def);
            }
        };
    }

    /**
     * 私有：WEST（左边）适配器——按下记 fx，向左拖为正向增量，释放时
     * x 取释放点屏幕坐标、width += nx 联动（左边拖宽时窗体整体左移）。
     **/
    private static MouseAdapter LE(final SFrame frame, final BorderButton borderButton) {
        return new MouseAdapter() {

            // 下压
            @Override
            public void mousePressed(MouseEvent e) {
                borderButton.fx = e.getXOnScreen();
            }

            // 释放
            @Override
            public void mouseDragged(MouseEvent e) {
                borderButton.nx = borderButton.fx - e.getXOnScreen();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int local = frame.getWidth() + borderButton.nx;
                Rectangle o_b = frame.getBounds();
                if (local < borderButton.getMinWidth())
                    local = borderButton.getMinWidth();
                o_b.x = e.getXOnScreen();
                o_b.width = local;
                frame.setBounds(o_b);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                frame.setCursor(c_mov);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                frame.setCursor(c_def);
            }
        };
    }

    /**
     * 私有：NORTH（上边）适配器——按下记 fy，向上拖为正向增量，释放时
     * y 取释放点屏幕坐标、height += ny 联动（上边拖高时窗体整体上移）。
     **/
    private static MouseAdapter UP(final SFrame frame, final BorderButton borderButton) {
        return new MouseAdapter() {

            // 下压
            @Override
            public void mousePressed(MouseEvent e) {
                borderButton.fy = e.getYOnScreen();
            }

            // 释放
            @Override
            public void mouseDragged(MouseEvent e) {
                borderButton.ny = borderButton.fy - e.getYOnScreen();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int local = frame.getHeight() + borderButton.ny;
                Rectangle o_b = frame.getBounds();
                if (local < borderButton.getMinHeight())
                    local = borderButton.getMinHeight();
                o_b.y = e.getYOnScreen();
                o_b.height = local;
                frame.setBounds(o_b);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                frame.setCursor(c_mov);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                frame.setCursor(c_def);
            }
        };
    }

}
