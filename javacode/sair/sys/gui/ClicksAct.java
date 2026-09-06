package sair.sys.gui;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import sair.sys.SairCons;

/**
 * 控制台事件监听器集合:集中定义标题栏按钮(Exit/Sair)、输入框回车/历史翻页、托盘双击与右键菜单的动作。
 * <p>
 * 架构角色:单例 {@link #clicksActs}(构造器私有);各监听器为包可见实例字段,由 ConsFrame.initAction/
 * initTary 注册,回调全部运行在EDT(Swing事件派发线程)。
 * <p>
 * 线程模型(已彻底弃用EDT调度):AWT事件回调本身仍由系统在EDT派发(无法改变),
 * 但框架不再主动 invokeLater/Timer 调度——回调内直接执行(恢复旧版方式)。
 * 历史翻页(clicks_up/clicks_down)对SairCons.localRunnerHistory的size检查与get在同一synchronized块内,
 * 防止并发裁剪历史时越界。
 * <p>
 * 二进制兼容:公开字段 {@link #clicksActs} 与各ActionListener字段名(clicks_exit等)保持原样,
 * 框架内部(ConsFrame)按字段名引用。
 */
public class ClicksAct {

	/** 单例:全局唯一的监听器实例(构造器私有) */
	public final static ClicksAct clicksActs = new ClicksAct();
	/** 0--exit:Exit按钮动作,执行/exit退出框架 */
	ActionListener clicks_exit = new ActionListener() {// 0--exit

		@Override
		public void actionPerformed(ActionEvent arg0) {
			SairCons.runner(false, "/exit");
		}
	};
	/** 1-Sair(输出列表切换):按下在中心区西侧挂载/移除插件列表,并确保窗体可见 */
	ActionListener clicks_sair = new ActionListener() {// 1-Sair(输出列表切换)

		/** 列表当前是否已挂载(EDT读写) */
		private boolean isLoaded;

		@Override
		public void actionPerformed(ActionEvent arg0) {
			if (isLoaded) {
				ConsFrame.cf.getCenter().remove(ConsFrame.cf.listP_JSP);
				isLoaded = false;
			} else {
				ConsFrame.cf.getCenter().add(ConsFrame.cf.listP_JSP, BorderLayout.WEST);
				isLoaded = true;
			}
			ConsFrame.showFrame();
		}

	};
	/**
	 * 2-Enter:提交输入框命令(恢复旧版方式:监听器回调线程内同步执行,不再分线程调度);
	 * 执行后历史索引指向最新一条。
	 */
	ActionListener clicks_enter = new ActionListener() {// 2-Enter

		@Override
		public void actionPerformed(ActionEvent arg0) {
			String cmd = ConsFrame.cf.input.getText();
			ConsFrame.cf.input.setText("");
			SairCons.runner(true, cmd);
			SairCons.localRunnerHistory_Index = SairCons.localRunnerHistory.size() - 1;
		}

	};
	/** 3-UP:上翻历史(size检查与get同锁,防worker裁剪历史时越界) */
	ActionListener clicks_up = new ActionListener() {// 3-UP

		@Override
		public void actionPerformed(ActionEvent arg0) {
			// 修复:size检查与get同锁,避免worker线程裁剪历史时EDT越界
			synchronized (SairCons.localRunnerHistory) {
				if (SairCons.localRunnerHistory.size() <= 0)
					return;

				if (SairCons.localRunnerHistory_Index < 0)
					SairCons.localRunnerHistory_Index = 0;

				String cmd = SairCons.localRunnerHistory.get(SairCons.localRunnerHistory_Index);
				ConsFrame.cf.input.setText(cmd);
				if (SairCons.localRunnerHistory_Index > 0)
					SairCons.localRunnerHistory_Index--;
			}
		}

	};
	/** 4-DOWN:下翻历史(size检查与get同锁,防worker裁剪历史时越界) */
	ActionListener clicks_down = new ActionListener() {// 4-DOWN

		@Override
		public void actionPerformed(ActionEvent arg0) {
			// 修复:size检查与get同锁,避免worker线程裁剪历史时EDT越界
			synchronized (SairCons.localRunnerHistory) {
				if (SairCons.localRunnerHistory.size() <= 0)
					return;

				if (SairCons.localRunnerHistory_Index >= SairCons.localRunnerHistory.size())
					SairCons.localRunnerHistory_Index = SairCons.localRunnerHistory.size() - 1;

				String cmd = SairCons.localRunnerHistory.get(SairCons.localRunnerHistory_Index);
				ConsFrame.cf.input.setText(cmd);
				if (SairCons.localRunnerHistory_Index < SairCons.localRunnerHistory.size() - 1)
					SairCons.localRunnerHistory_Index++;
			}
		}

	};
	/** 托盘"reset GUI":重新显示窗体并恢复默认尺寸 */
	ActionListener clicks_resetGUI = new ActionListener() {
		public void actionPerformed(ActionEvent e) {
			SairCons.runner(false, "/show");
			SairCons.runner(false, "/resize " + ConsFrame.w + " " + ConsFrame.h);
		}
	};
	/** 托盘图标双击:窗体隐藏时重新显示 */
	MouseListener icoClick = new MouseAdapter() {
		public void mouseClicked(MouseEvent e) {
			if (e.getClickCount() == 2) {
				if (!ConsFrame.cf.isVisible())
					ConsFrame.showFrame();
			}
		}
	};

	/** 私有构造:仅内部单例使用 */
	private ClicksAct() {
	}
}
