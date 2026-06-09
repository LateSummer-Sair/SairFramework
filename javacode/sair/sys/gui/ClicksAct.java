package sair.sys.gui;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import sair.sys.SairCons;

public class ClicksAct {

	public final static ClicksAct clicksActs = new ClicksAct();
	ActionListener clicks_exit = new ActionListener() {// 0--exit

		@Override
		public void actionPerformed(ActionEvent arg0) {
			SairCons.runner(false, "/exit");
		}
	};
	ActionListener clicks_sair = new ActionListener() {// 1-Sair

		private boolean isLoaded;

		@Override
		public void actionPerformed(ActionEvent arg0) {
			if (isLoaded) {
				ConsFrame.cf.getCenter().remove(ConsFrame.cf.listP_JSP);
				isLoaded = false;
			} else {
				// SairCons.runner(false, "/help");
				ConsFrame.cf.getCenter().add(ConsFrame.cf.listP_JSP, BorderLayout.WEST);
				isLoaded = true;
			}
			ConsFrame.showFrame();
		}

	};
	ActionListener clicks_enter = new ActionListener() {// 2-Enter

		@Override
		public void actionPerformed(ActionEvent arg0) {
			String cmd = ConsFrame.cf.input.getText();
			ConsFrame.cf.input.setText("");
			SairCons.runner(true, cmd);
			SairCons.localRunnerHistory_Index = SairCons.localRunnerHistory.size() - 1;
		}

	};
	ActionListener clicks_up = new ActionListener() {// 3-UP

		@Override
		public void actionPerformed(ActionEvent arg0) {

			if (SairCons.localRunnerHistory.size() <= 0)
				return;

			if (SairCons.localRunnerHistory_Index < 0)
				SairCons.localRunnerHistory_Index = 0;

			String cmd = SairCons.localRunnerHistory.get(SairCons.localRunnerHistory_Index);
			ConsFrame.cf.input.setText(cmd);
			if (SairCons.localRunnerHistory_Index > 0)
				SairCons.localRunnerHistory_Index--;
		}

	};
	ActionListener clicks_down = new ActionListener() {// 4-DOWN

		@Override
		public void actionPerformed(ActionEvent arg0) {

			if (SairCons.localRunnerHistory.size() <= 0)
				return;

			if (SairCons.localRunnerHistory_Index >= SairCons.localRunnerHistory.size())
				SairCons.localRunnerHistory_Index = SairCons.localRunnerHistory.size() - 1;

			String cmd = SairCons.localRunnerHistory.get(SairCons.localRunnerHistory_Index);
			ConsFrame.cf.input.setText(cmd);
			if (SairCons.localRunnerHistory_Index < SairCons.localRunnerHistory.size() - 1)
				SairCons.localRunnerHistory_Index++;
		}

	};
	ActionListener clicks_resetGUI = new ActionListener() {
		public void actionPerformed(ActionEvent e) {
			SairCons.runner(false, "/show");
			SairCons.runner(false, "/resize " + ConsFrame.w + " " + ConsFrame.h);
		}
	};
	MouseListener icoClick = new MouseAdapter() {
		public void mouseClicked(MouseEvent e) {
			if (e.getClickCount() == 2) {
				if (!ConsFrame.cf.isVisible())
					ConsFrame.showFrame();
			}
		}
	};

	private ClicksAct() {
	}
}
