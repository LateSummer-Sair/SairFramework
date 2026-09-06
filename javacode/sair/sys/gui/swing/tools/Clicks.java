package sair.sys.gui.swing.tools;

import java.awt.Component;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;

import javax.swing.JComponent;
import javax.swing.KeyStroke;

import sair.sys.gui.swing.control.corpuscle.ClicksI;

/**
 * <p>
 * 点击事件编辑器（工具类）：为 {@link ClicksI} 对应的窗体批量安装整窗拖动监听
 * （{@code FrameMouseAdapter}/{@code FrameMouseMotionAdapter}），为任意组件绑定
 * “获得焦点时按键触发”的键盘动作，或为其安装文件拖放支持。
 * </p>
 * <p>
 * <b>架构角色：</b>tools 层门面，全局唯一实例 {@link #CLICKS_TOOLS}；
 * 实际监听器由包私有工厂 {@code MouseCklicksFactory} 生成、拖放由 {@code DragAc} 安装，
 * 上层（如 SFrame 初始化）只面对本类三个公开方法。
 * </p>
 * <p>
 * <b>线程安全 / EDT 说明：</b>所有方法都向 Swing 组件挂监听器，
 * <b>必须在事件分发线程（EDT）调用</b>。
 * </p>
 * <p>
 * <b>二进制兼容约束：</b>{@link #CLICKS_TOOLS}、{@link #setClicks(ClicksI)}、
 * {@link #enterPressesWhenFocused(JComponent, ActionListener, Integer)}、
 * {@link #drag(Component)} 的签名不可修改；
 * {@link #enterPressesWhenFocused} 底层使用 JDK1.3 时代即弃用的
 * {@link JComponent#registerKeyboardAction(ActionListener, KeyStroke, int)}，
 * 为保持行为兼容刻意保留，勿改用 InputMap/ActionMap 重写。
 * </p>
 *
 * @author _Sair
 * @version Clicks1.2
 **/
public class Clicks {
	/** 全局唯一工具单例。 */
	public final static Clicks CLICKS_TOOLS = new Clicks();

	/** 私有构造：仅通过单例 {@link #CLICKS_TOOLS} 使用。 */
	private Clicks() {
	}

	/**
	 * 为 {@link ClicksI} 所描述的窗体安装整窗拖动支持：
	 * 挂接 {@code MouseCklicksFactory#getFrameMouseAdapter}（按下记组件相对坐标、释放收尾）与
	 * {@code MouseCklicksFactory#getFrameMouseMotionAdapter}（拖动按原版绝对定位语义移动窗体）。
	 * <p><b>EDT：</b>必须。</p>
	 *
	 * @param clicks 窗体交互载体（null 静默返回）
	 **/
	public void setClicks(ClicksI clicks) {
		if (clicks == null)
			return;
		clicks.getJFrame().addMouseListener(MouseCklicksFactory.getFrameMouseAdapter(clicks));
		clicks.getJFrame().addMouseMotionListener(MouseCklicksFactory.getFrameMouseMotionAdapter(clicks));
	}

	/**
	 * <p>
	 * 为组件绑定“获得焦点时按下指定键”的触发动作：key 为 null 时取回车键
	 * （{@link KeyEvent#VK_ENTER}），组件持有焦点时按下该键即触发（{@code WHEN_FOCUSED} 条件）。
	 * </p>
	 * <p>
	 * 底层调用 JDK1.3 起弃用的
	 * {@link JComponent#registerKeyboardAction(ActionListener, KeyStroke, int)}
	 * —— 保留兼容，勿改签名/语义。
	 * </p>
	 * <p><b>EDT：</b>必须。</p>
	 *
	 * @param component 绑定目标组件（null 静默返回）
	 * @param actionListener 触发动作（null 静默返回）
	 * @param key 键码（null 取回车键）
	 **/
	public void enterPressesWhenFocused(JComponent component, ActionListener actionListener, Integer key) {
		if (component == null || actionListener == null)
			return;
		if (key == null)
			key = KeyEvent.VK_ENTER;
		component.registerKeyboardAction(actionListener, KeyStroke.getKeyStroke(key, 0, true), JComponent.WHEN_FOCUSED);
	}

	/**
	 * 为组件安装文件拖放（{@code DragAc#toDrag}：创建/复用 DropTarget，
	 * 拖入的文件列表经 {@code DragAcAdapter} 处理）。
	 * <p><b>EDT：</b>建议在 EDT 安装（DropTarget 注册）。</p>
	 *
	 * @param component 接收拖放的目标组件（null 静默返回）
	 **/
	public void drag(Component component) {
		if (component == null)
			return;
		DragAc.toDrag(component);
	}

}
