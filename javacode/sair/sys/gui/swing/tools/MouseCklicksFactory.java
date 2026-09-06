package sair.sys.gui.swing.tools;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseMotionAdapter;

import sair.sys.gui.swing.control.corpuscle.ClicksI;

/**
 * <p>
 * 鼠标监听器工厂（包私有）：为 {@link ClicksI} 批量产出 {@code FrameMouseAdapter}
 * （按下/释放）与 {@code FrameMouseMotionAdapter}（拖动）实例。
 * </p>
 * <p>
 * <b>架构角色：</b>tools 层内部实现，仅供 {@link Clicks#setClicks(ClicksI)} 调用，
 * 把监听器创建与注入逻辑从门面类中分离。
 * </p>
 * <p>
 * <b>线程安全 / EDT 说明：</b>纯工厂，无共享可变状态，任意线程可调用；
 * 产出的监听器须在 EDT 挂到 Swing 组件。
 * </p>
 * <p>
 * <b>二进制兼容约束：</b>类与两个静态方法均为包私有，但 {@link Clicks} 依赖其签名，
 * 不可随意修改。
 * </p>
 */
class MouseCklicksFactory {
    /**
     * 创建按下/释放适配器并注入 clicks（内部 {@code setC} 链式返回）。
     *
     * @param clicks 窗体交互载体
     * @return 已注入 clicks 的 FrameMouseAdapter
     **/
    static MouseAdapter getFrameMouseAdapter(ClicksI clicks) {
        return new FrameMouseAdapter().setC(clicks);
    }

    /**
     * 创建拖动适配器并注入 clicks（内部 {@code setC} 链式返回）。
     *
     * @param clicks 窗体交互载体
     * @return 已注入 clicks 的 FrameMouseMotionAdapter
     **/
    static MouseMotionAdapter getFrameMouseMotionAdapter(ClicksI clicks) {
        return new FrameMouseMotionAdapter().setC(clicks);
    }


}
