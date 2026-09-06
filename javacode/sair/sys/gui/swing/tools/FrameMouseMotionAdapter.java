package sair.sys.gui.swing.tools;

import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

import sair.sys.gui.swing.control.SFrame;
import sair.sys.gui.swing.control.corpuscle.ClicksI;

/**
 * <p>
 * SFrame 的鼠标拖动适配器（包私有）：拖动时按<b>原版绝对定位语义</b>移动窗体
 * （新位置 = 当前屏幕坐标 − 按下时记录的组件相对坐标），
 * 并在拖动开始瞬间对处于设置面板展开状态的窗体施加一次性半透明（0.8f）悬浮效果。
 * </p>
 * <p>
 * <b>架构角色：</b>tools 层内部实现，由
 * {@code MouseCklicksFactory#getFrameMouseMotionAdapter(ClicksI)} 创建；
 * 与 {@code FrameMouseAdapter} 成对工作（消费其按下时记录的 OldX/OldY）。
 * </p>
 * <p>
 * <b>线程安全 / EDT 说明：</b>鼠标移动事件回调天然在 EDT；{@link #c} 构造后仅设置一次。
 * </p>
 * <p>
 * <b>二进制兼容约束：</b>类与成员均为包私有，但被 {@code MouseCklicksFactory}、
 * {@link Clicks} 依赖；位置计算保持原版语义（勿改：屏幕坐标增量模型在 HiDPI
 * 缩放下与窗口逻辑坐标不一致，会导致拖动位移量被放大、窗体无法拖到指定位置）。
 * </p>
 */
class FrameMouseMotionAdapter extends MouseMotionAdapter {

	/** 窗体交互载体（按下坐标记录与窗体引用）。 */
	private ClicksI c;

	/**
	 * 注入交互载体 {@link ClicksI}（链式返回 this，供工厂直接返回监听器）。
	 *
	 * @param c 窗体交互状态载体
	 * @return this
	 **/
	MouseMotionAdapter setC(ClicksI c) {
		this.c = c;
		return this;
	}

	/**
	 * <p>
	 * 拖动回调：
	 * </p>
	 * <ol>
	 * <li>若窗体为 {@link SFrame}、尚未进入设置悬浮态且设置面板展开，
	 * 则收起中心内容（setcenterNULL）、置 0.8f 半透明（setFloat）并打上悬浮标记
	 * （setSetingFloated(true)，一次性）；</li>
	 * <li>按原版绝对定位语义移动窗体：新位置 = 当前屏幕坐标 − 按下时记录的组件相对坐标。</li>
	 * </ol>
	 **/
	@Override
	public void mouseDragged(MouseEvent e) {
		if ((c.getJFrame() instanceof SFrame) && !((SFrame) (c.getJFrame())).isSetingFloated()) {
			SFrame sf = (SFrame) (c.getJFrame());
			if (sf.isOpenSetting()) {
				sf.setcenterNULL();
				sf.setFloat(0.8f);
				sf.setSetingFloated(true);
			}
		}
		// 原版语义:新位置 = 屏幕坐标 - 按下时组件相对坐标(勿改,HiDPI下屏幕增量模型会放大位移)
		int xOnScreen = e.getXOnScreen(), yOnScreen = e.getYOnScreen(), xx = xOnScreen - c.getOldX(),
				yy = yOnScreen - c.getOldY();
		c.getJFrame().setLocation(xx, yy);
	}
}
