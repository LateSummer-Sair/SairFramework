package sair.sys.gui.swing.tools;

import java.awt.Component;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;

import javax.swing.JComponent;
import javax.swing.KeyStroke;

import sair.sys.gui.swing.control.corpuscle.ClicksI;

/**
 * Clicksµã»÷ÊÂ¼þ±à¼­Æ÷
 * <p>
 *
 * @author _Sair
 * @version Clicks1.2
 **/
public class Clicks {
	public final static Clicks CLICKS_TOOLS = new Clicks();

	private Clicks() {
	}

	public void setClicks(ClicksI clicks) {
		if (clicks == null)
			return;
		clicks.getJFrame().addMouseListener(MouseCklicksFactory.getFrameMouseAdapter(clicks));
		clicks.getJFrame().addMouseMotionListener(MouseCklicksFactory.getFrameMouseMotionAdapter(clicks));
	}

	public void enterPressesWhenFocused(JComponent component, ActionListener actionListener, Integer key) {
		if (component == null || actionListener == null)
			return;
		if (key == null)
			key = KeyEvent.VK_ENTER;
		component.registerKeyboardAction(actionListener, KeyStroke.getKeyStroke(key, 0, true), JComponent.WHEN_FOCUSED);
	}

	public void drag(Component component) {
		if (component == null)
			return;
		DragAc.toDrag(component);
	}

}
