package sair.sys.gui.swing.tools;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.SystemColor;

import javax.swing.JPanel;

import sair.sys.gui.swing.control.SButton;
import sair.sys.gui.swing.control.SFrame;

/**
 * <p>
 * 自由缩放窗体的四边（上/下/左/右）调整按钮：放在窗体 {@link BorderLayout} 的四周，
 * 按住拖动即可改变 {@link SFrame} 的尺寸（最小尺寸受 minWidth/minHeight 约束）。
 * </p>
 * <p>
 * <b>架构角色：</b>继承 {@link SButton} 的 tools 层控件；构造时挂接
 * {@code BorderButton_Clicks} 的鼠标/鼠标移动监听实现拖拽调尺寸逻辑
 * （按下记屏幕坐标 → 拖动算增量 → 释放写回 {@code SFrame#setBounds}）。
 * 工厂入口 {@link #setDefaultBorderButtons(SFrame)} 一键把窗体内容面板改造成
 * “四边按钮 + 原内容居中”的 BorderLayout。
 * </p>
 * <p>
 * <b>线程安全 / EDT 说明：</b>所有构造与使用都必须在 EDT（Swing 组件）；
 * 实例字段 fx/fy/nx/ny 由鼠标事件线程（即 EDT）读写，无需额外同步，
 * 但<b>不要跨线程共享同一实例</b>。
 * </p>
 * <p>
 * <b>二进制兼容约束：</b>公开成员（{@link #setDefaultBorderButtons(SFrame)}、
 * {@link #getMinHeight()}/{@link #setMinHeight(int)}、
 * {@link #getMinWidth()}/{@link #setMinWidth(int)}）签名不可修改；
 * {@code setDefaultBorderButtons} 返回值 {@code BorderButton[4]} 顺序固定为
 * {NORTH, SOUTH, WEST, EAST}，调用方可能按下标取用，不可调整。
 * </p>
 * <p>
 * <b>共享状态注意：</b>{@link #Dsize} 是所有实例共享的 Dimension 对象，
 * 构造中 {@code setPreferredSize(Dsize)} 传入的是同一引用——外部勿修改其
 * width/height，否则四个方向按钮尺寸会同时改变。
 * </p>
 *
 * @author _Sair
 * @version BorderButtons1.0
 **/
public class BorderButton extends SButton {

    private static final long serialVersionUID = 6357429865819576304L;

    /**
     * 所有 BorderButton 共享的默认首选尺寸（4x4 像素）：
     * 构造中 {@code setPreferredSize(Dsize)} 直接引用该实例，
     * 外部禁止修改其 width/height（共享状态注意）。
     **/
    private static Dimension Dsize = new Dimension(4, 4);
    /** 拖拽增量暂存：fx/fy 为按下点屏幕坐标，nx/ny 为本轮拖动增量（仅 EDT 读写）。 */
    int fx, fy, nx, ny;
    /** 最小高/宽（拖动下限，构造时取 frame 初始尺寸，可通过 setter 调整）。 */
    private int minHeight, minWidth;

    /**
     * 私有构造：由 {@link #setDefaultBorderButtons} 创建；
     * 记录窗体初始宽高作为最小尺寸，并挂接 {@code BorderButton_Clicks} 生成的
     * 鼠标监听与鼠标移动监听（两个监听共用同一适配器实例）。
     *
     * @param frame 所属窗体（取其初始宽高作为最小宽高）
     * @param border 所在方位（BorderLayout.NORTH/SOUTH/WEST/EAST）
     **/
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
     * <p>
     * 工厂的主要方法：一键为窗体安装四边调整按钮。
     * </p>
     * <p>
     * 把 {@code frame.getCenter()} 原内容面板包进新的 BorderLayout 面板：
     * NORTH/SOUTH/WEST/EAST 各放一个 BorderButton（默认首选尺寸 4x4），
     * 原内容居中，随后 {@code frame.setContentPane(jp_new)} 整体替换。
     * </p>
     * <p><b>EDT：</b>必须。</p>
     *
     * @param frame 需要支持动态调整大小的窗体（null 返回 null）
     * @return 按 {NORTH, SOUTH, WEST, EAST} 顺序排列的四个按钮；frame 为 null 时返回 null
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

    /**
     * 最小高度（拖小下限，释放时与目标高度比较）。
     *
     * @return 最小高度（像素）
     **/
    public int getMinHeight() {
        return minHeight;
    }

    /**
     * 设置最小高度（拖动下限；调整尺寸时低于它会被钳制）。
     *
     * @param minHeight 最小高度（像素）
     **/
    public void setMinHeight(int minHeight) {
        this.minHeight = minHeight;
    }

    /**
     * 最小宽度（拖小下限，释放时与目标宽度比较）。
     *
     * @return 最小宽度（像素）
     **/
    public int getMinWidth() {
        return minWidth;
    }

    /**
     * 设置最小宽度（拖动下限；调整尺寸时低于它会被钳制）。
     *
     * @param minWidth 最小宽度（像素）
     **/
    public void setMinWidth(int minWidth) {
        this.minWidth = minWidth;
    }
}
