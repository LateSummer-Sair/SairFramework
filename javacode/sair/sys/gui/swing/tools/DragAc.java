package sair.sys.gui.swing.tools;

import java.awt.Component;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;

/**
 * <p>
 * 文件拖放安装工具（包私有）：为指定组件创建并注册 {@link DropTarget}，
 * 使其接收系统拖入的文件列表（经 {@code DragAcAdapter} 处理）。
 * </p>
 * <p>
 * <b>架构角色：</b>tools 层内部实现，由 {@link Clicks#drag(Component)} 调用；
 * 始终新建 DropTarget 并挂接本工具的文件处理适配器。
 * </p>
 * <p>
 * <b>修复记录（重要）：</b>此前曾改为"复用组件已存在的 DropTarget"——
 * 但 JTextField/JTextArea 等 Swing 文本组件构造时自带基于 TransferHandler 的
 * 默认 DropTarget（getDropTarget() 恒非 null），复用逻辑会把默认目标当成本工具的
 * 目标直接返回，导致 {@code DragAcAdapter} 根本未挂接、<b>拖文件到输入框获取
 * 路径的功能完全失效</b>。因此必须始终用本工具的适配器新建 DropTarget 并
 * setDropTarget 覆盖默认目标，不得复用。
 * </p>
 * <p>
 * <b>线程安全 / EDT 说明：</b>建议在 EDT 调用（AWT 组件/事件注册）；DropTarget 的
 * 回调（{@code DragAcAdapter#drop}）由 DnD 事件派发线程执行，与 EDT 分离。
 * </p>
 * <p>
 * <b>二进制兼容约束：</b>类与方法均为包私有，但 {@link Clicks} 以编译期调用依赖，
 * 签名不可随意修改。
 * </p>
 */
class DragAc {
	/**
	 * 为组件安装文件拖放目标：始终新建 ACTION_COPY_OR_MOVE 的 DropTarget
	 * 并挂接 {@code DragAcAdapter}，经 {@code setDropTarget} 覆盖组件原有目标
	 * （文本组件自带的默认 DnD 目标不支持我们的文件路径填入逻辑，必须替换）。
	 *
	 * @param component 目标组件
	 * @return 新建的 DropTarget（已注册到组件）
	 **/
	static DropTarget toDrag(Component component) {
		DropTarget dt = new DropTarget(component, DnDConstants.ACTION_COPY_OR_MOVE,
				new DragAcAdapter().setComponents(component));
		component.setDropTarget(dt);
		return dt;
	}
}
