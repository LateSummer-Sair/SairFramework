package sair.sys.gui;

import java.awt.Component;
import java.awt.event.MouseEvent;

import javax.swing.JList;

import sair.FCM;
import sair.sys.SairCons;
import sair.sys.gui.swing.control.SButton;

/**
 * 插件列表的鼠标适配:单击把"当前选中:xxx"打印到控制台,双击执行 /print-cpr xxx
 * (清除该组件的第三方输出模式)。
 * <p>
 * 线程安全:仅鼠标事件回调触发(系统在EDT派发);无共享状态,单实例注册于ConsFrame.list。
 */
class MouseAdapter extends java.awt.event.MouseAdapter {

	@Override
	public void mouseClicked(MouseEvent e) {
		// 单击:把选中项提示打印到控制台
		if (e.getClickCount() == 1)
			ConsFrame.printo(null, null, "当前选中:" + String.valueOf(ConsFrame.cf.list.getSelectedValue()));
		// 双击:执行/print-cpr清除该组件的第三方输出模式
		else if (e.getClickCount() == 2)
			SairCons.runner(false, "/print-cpr " + String.valueOf(ConsFrame.cf.list.getSelectedValue()));
	}

}

/**
 * 插件列表单元格渲染器:复用同一个SButton实例绘制每行——选中=当前默认前景色,未选中=exection色,
 * 字体沿用控制台字体。
 * <p>
 * 架构角色:由ConsFrame.initComp注册到ConsFrame.list;渲染回调由系统在EDT派发(Swing渲染管线)。
 * 公开字段 {@link #value} 保留(渲染时写入当前单元格文本,供外部读取)。
 * <p>
 * 二进制兼容:继承SButton+实现ListCellRenderer&lt;String&gt;的结构、公开字段value保持不变。
 */
class ListCellRenderer extends SButton implements javax.swing.ListCellRenderer<String> {

	/**
	 *
	 */
	private static final long serialVersionUID = -5523679986019468529L;

	/** 最近一次渲染的单元格文本(渲染回调内写入,任意线程可读) */
	public String value;

	@Override
	public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
			boolean isSelected, boolean cellHasFocus) {
		// 记录当前单元格文本(公开字段,供外部读取)
		this.value = value;
		// 字体与控制台文本窗格同步
		this.setFont(ConsFrame.cf.infoPane.getFont());
		this.setText(value);
		// 选中=当前默认前景色,未选中=exection色
		if (isSelected)
			this.setForeground(ConsFrame.cf.otC);
		else
			this.setForeground(FCM.loadExection_Color);
		// 复用同一SButton实例绘制本行
		return this;
	}

}
