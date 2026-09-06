package sair.sys.gui.swing.control;

import javax.swing.JFrame;

import sair.sys.gui.swing.control.corpuscle.ClicksI;
import sair.sys.gui.swing.tools.Clicks;

/**
 * 可拖动JFrame:实现ClicksI,保存鼠标按下时的<b>组件相对坐标</b>,供FrameMouseMotionAdapter按原版
 * 绝对定位语义计算窗口新位置(新位置=当前屏幕坐标-按下时组件相对坐标)。
 * <p>
 * 架构角色:SFrame的父类;构造时经Clicks.CLICKS_TOOLS.setClicks(this)挂接按下/拖动/释放监听
 * (FrameMouseAdapter/FrameMouseMotionAdapter)。
 * <p>
 * 线程安全:X/Y仅EDT(鼠标事件线程)读写;无其他共享状态。
 * <p>
 * 二进制兼容:无参构造器与ClicksI的坐标方法签名保持稳定。
 */
public class ClicksJFrame extends JFrame implements ClicksI {

    private static final long serialVersionUID = 541564616456487L;
    /** 鼠标按下时的组件相对坐标(拖动定位基准;仅EDT读写) */
    private int X, Y;

    /** 构造:注册按下/拖动/释放监听(经Clicks工具类) */
    public ClicksJFrame() {
        Clicks.CLICKS_TOOLS.setClicks(this);
    }

    /** 返回自身JFrame引用(拖动逻辑入口) */
    @Override
    public JFrame getJFrame() {
        return this;
    }

    /** 旧X(按下时的组件相对坐标) */
    @Override
    public int getOldX() {
        return X;
    }

    /** 记录旧X(按下时由FrameMouseAdapter写入) */
    @Override
    public void setOldX(int x) {
        X = x;
    }

    /** 旧Y(按下时的组件相对坐标) */
    @Override
    public int getOldY() {
        return Y;
    }

    /** 记录旧Y(按下时由FrameMouseAdapter写入) */
    @Override
    public void setOldY(int y) {
        Y = y;
    }
}
