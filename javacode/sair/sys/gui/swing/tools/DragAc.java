package sair.sys.gui.swing.tools;

import java.awt.Component;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;

class DragAc {
	static DropTarget toDrag(Component component) {
		return new DropTarget(component, DnDConstants.ACTION_COPY_OR_MOVE,
				new DragAcAdapter().setComponents(component));
	}
}
