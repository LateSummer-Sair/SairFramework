package sair.sys.gui.swing.tools;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import sair.sys.gui.swing.control.SFrame;
import sair.sys.gui.swing.control.corpuscle.ClicksI;

/**
 * <p>
 * SFrame 的鼠标按下/释放适配器（包私有）：按下时记录<b>组件相对坐标</b>供拖动计算，
 * 释放时若窗体处于“设置面板展开”状态则执行收尾（选择背景图、还原浮动透明度等）。
 * </p>
 * <p>
 * <b>架构角色：</b>tools 层内部实现，由 {@code MouseCklicksFactory#getFrameMouseAdapter(ClicksI)}
 * 创建、{@link Clicks#setClicks(ClicksI)} 挂到窗体；与
 * {@code FrameMouseMotionAdapter} 成对工作（后者消费 OldX/OldY 计算新位置）。
 * </p>
 * <p>
 * <b>线程安全 / EDT 说明：</b>鼠标事件回调天然在 EDT；{@link #c} 仅在构造后
 * 通过 {@link #setC} 设置一次，之后只读，无需同步。
 * </p>
 * <p>
 * <b>二进制兼容约束：</b>类与成员均为包私有，但 {@code MouseCklicksFactory}、
 * {@link Clicks} 依赖其构造与 {@link #setC(ClicksI)} 链式调用，签名不可随意修改；
 * 按下记录的必须保持组件相对坐标（原版语义，勿改：屏幕绝对坐标在 HiDPI
 * 缩放下与窗口逻辑坐标不一致，会导致拖动位移量被放大、窗体无法拖到指定位置）。
 * </p>
 */
class FrameMouseAdapter extends MouseAdapter {
	/** 窗体交互载体（按下坐标记录与窗体引用）。 */
	private ClicksI c;

	/**
	 * 注入交互载体 {@link ClicksI}（链式返回 this，供工厂直接返回监听器）。
	 *
	 * @param c 窗体交互状态载体
	 * @return this
	 **/
	MouseAdapter setC(ClicksI c) {
		this.c = c;
		return this;
	}

	/**
	 * 按下回调：把当前<b>组件相对坐标</b>记入 {@link ClicksI#setOldX}/{@link ClicksI#setOldY}
	 * （原版语义，勿改：屏幕绝对坐标在 HiDPI 缩放下与窗口逻辑坐标不一致，
	 * 会导致拖动位移量被放大）。
	 **/
	@Override
	public void mousePressed(MouseEvent e) {
		c.setOldX(e.getX());
		c.setOldY(e.getY());
	}

	/**
	 * 释放回调：仅当窗体为 {@link SFrame} 且处于“设置面板展开”状态
	 * （{@code isOpenSetting()}）时，依次执行：选择背景图、恢复用户此前浮动透明度、
	 * 复位设置悬浮标记（isSetingFloated=false）。
	 **/
	@Override
	public void mouseReleased(MouseEvent evt) {
		if (c.getJFrame() instanceof SFrame) {
			SFrame sf = (SFrame) (c.getJFrame());
			if (sf.isOpenSetting()) {
				sf.selectBgimg();
				sf.setFloat(((SFrame) (c.getJFrame())).getUpFloted());
				sf.setSetingFloated(false);
			}
		}
	}
}
