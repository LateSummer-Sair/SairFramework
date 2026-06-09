package sair.sys.gui;

import java.awt.Component;
import java.awt.event.MouseEvent;

import javax.swing.JList;

import sair.FCM;
import sair.sys.SairCons;
import sair.sys.gui.swing.control.SButton;

class MouseAdapter extends java.awt.event.MouseAdapter {

	@Override
	public void mouseClicked(MouseEvent e) {
		if (e.getClickCount() == 1)
			ConsFrame.printComponent("当前选中:" + String.valueOf(ConsFrame.cf.list.getSelectedValue()));
		else if (e.getClickCount() == 2)
			SairCons.runner(false, "/print-cpr " + String.valueOf(ConsFrame.cf.list.getSelectedValue()));
	}

}

class ListCellRenderer extends SButton implements javax.swing.ListCellRenderer<String> {

	/**
	 *
	 */
	private static final long serialVersionUID = -5523679986019468529L;

	public String value;

	@Override
	public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
			boolean isSelected, boolean cellHasFocus) {
		this.value = value;
		this.setFont(ConsFrame.cf.infoPane.getFont());
		this.setText(value);
		if (isSelected)
			this.setForeground(ConsFrame.cf.otC);
		else
			this.setForeground(FCM.loadExection_Color);
		return this;
	}

}
