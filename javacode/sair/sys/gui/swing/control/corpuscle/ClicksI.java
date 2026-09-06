package sair.sys.gui.swing.control.corpuscle;

import javax.swing.JFrame;

/**
 * 拖动坐标接口:为窗体拖动提供"旧坐标"存取与JFrame引用,由Clicks工具类的监听适配器使用。
 * <p>
 * 架构角色:corpuscle(小部件)包内的契约接口;ClicksJFrame实现它——FrameMouseAdapter按下时写入
 * <b>组件相对坐标</b>(setOldX/setOldY),FrameMouseMotionAdapter拖动时按原版绝对定位语义移动
 * (新位置=getXOnScreen()-getOldX());
 * FrameMouseAdapter释放时还经getJFrame做SFrame特殊处理(高斯模糊开关联动)。
 * <p>
 * 线程安全:全部方法仅EDT(鼠标事件线程)调用。
 * <p>
 * 二进制兼容:接口方法签名(getJFrame/getOldX/setOldX/getOldY/setOldY)不可增删改。
 */
public interface ClicksI {
    /** 取接口所属JFrame */
    public JFrame getJFrame();

    /** 旧X(按下时的组件相对坐标,原版语义) */
    public int getOldX();

    /** 记录旧X */
    public void setOldX(int x);

    /** 旧Y(按下时的组件相对坐标,原版语义) */
    public int getOldY();

    /** 记录旧Y */
    public void setOldY(int y);
}
