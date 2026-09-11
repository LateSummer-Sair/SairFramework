package sair.sys.gui;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionAdapter;

import sair.sys.SairCons;

/**
 * 控制台事件监听器集合:集中定义标题栏按钮(Exit/Sair)、输入框回车/历史翻页、托盘双击与右键菜单的动作,
 * 以及右侧控件隔离区的命名事件模板类(手柄拖动/右键菜单,延续"一文件多包私有类"风格)。
 * <p>
 * 架构角色:单例 {@link #clicksActs}(构造器私有);各监听器为包可见实例字段,由 ConsFrame.initAction/
 * initTary 注册;隔离区的事件模板类(文件尾部)由 ConsFrame.initComp 实例化后挂接。
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

		/** 列表当前是否已挂载(仅clicks_sair回调内读写;系统在EDT派发的ActionListener回调) */
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

/**
 * 手柄按下监听(命名事件模板类):记录按下时的屏幕X与隔离区当前宽度,
 * 供 {@link TabsGripMotionAdapter} 计算拖动增量。
 */
class TabsGripAdapter extends MouseAdapter {

	/**
	 * 按下回调:快照屏幕绝对X与当前宽度到 ConsFrame(包级字段 gripStartX/gripStartW)。
	 */
	@Override
	public void mousePressed(MouseEvent e) {
		ConsFrame.cf.gripStartX = e.getXOnScreen();
		ConsFrame.cf.gripStartW = ConsFrame.cf.tabsWidth;
	}
}

/**
 * 手柄拖动监听(命名事件模板类):按"向左拖动增宽 / 向右拖动减宽"实时调整隔离区宽度
 * (下限120px,上限窗口宽度70%),并重排中心面板。
 */
class TabsGripMotionAdapter extends MouseMotionAdapter {

	/**
	 * 拖动回调:delta=按下屏幕X-当前屏幕X(左拖为正→增宽);
	 * 宽度收敛到[ConsFrame.TABS_MIN_W, 窗口70%]后写回eastWrap的preferredSize并重排。
	 */
	@Override
	public void mouseDragged(MouseEvent e) {
		ConsFrame cf = ConsFrame.cf;
		// 拖动增量=按下屏幕X-当前屏幕X(向左拖为正→增宽)
		int delta = cf.gripStartX - e.getXOnScreen();
		// 新宽度收敛到[TABS_MIN_W, 窗口宽70%]
		cf.tabsWidth = Math.max(ConsFrame.TABS_MIN_W,
				Math.min((int) (cf.getWidth() * 0.7f), cf.gripStartW + delta));
		// 写回eastWrap宽度并重排中心面板
		cf.eastWrap.setPreferredSize(new java.awt.Dimension(cf.tabsWidth, 0));
		ConsFrame.relayoutCenter();
	}
}

/**
 * 右键菜单动作(命名事件模板类):关闭被右键的选项卡(消费 ConsFrame.popupTabIndex),
 * 随后按剩余选项卡数同步隔离区显隐。
 */
class CloseTabAction implements ActionListener {

	/**
	 * 动作回调:popupTabIndex 合法时 removeTabAt;无论是否关闭都调用 updateCompAreaVisible
	 * (关到最后一个时隔离区自动隐藏)。
	 */
	@Override
	public void actionPerformed(ActionEvent e) {
		ConsFrame cf = ConsFrame.cf;
		if (cf.popupTabIndex >= 0 && cf.popupTabIndex < cf.tabsPane.getTabCount())
			cf.tabsPane.removeTabAt(cf.popupTabIndex);
		ConsFrame.updateCompAreaVisible();
	}
}

/**
 * 右键菜单动作(命名事件模板类):清除全部选项卡(等价 ConsFrame.clearComponents(),隔离区随之隐藏)。
 */
class ClearTabsAction implements ActionListener {

	/**
	 * 动作回调:清空隔离区。
	 */
	@Override
	public void actionPerformed(ActionEvent e) {
		ConsFrame.clearComponents();
	}
}

/**
 * 控制台滚动容器尺寸监听(命名事件模板类):centerScorllPane 宽高变化时重算控制台字号。
 * 由 ConsFrame.initComp 挂接到 centerScorllPane;字号实际未变时 ConsFrame.reinitFont 内部跳过重刷。
 */
class ConsoleResizeAdapter extends ComponentAdapter {

	/**
	 * 尺寸变化回调:按容器新宽高重算字号并重刷组件字体(见 ConsFrame.reinitFont)。
	 */
	@Override
	public void componentResized(ComponentEvent e) {
		ConsFrame.reinitFont();
	}
}

/**
 * 标签区右键弹出监听(命名事件模板类):按下/释放双 isPopupTrigger 触发(兼容各平台);
 * 记录被右键的选项卡索引到 ConsFrame.popupTabIndex,空白处右键时禁用"关闭此面板"并弹菜单。
 */
class TabsPopupAdapter extends MouseAdapter {

	/** "关闭此面板"菜单项引用:空白处右键时置灰 */
	private final javax.swing.JMenuItem closeTabItem;

	/**
	 * @param closeTabItem 菜单中的"关闭此面板"项(用于按命中情况启用/禁用)
	 */
	TabsPopupAdapter(javax.swing.JMenuItem closeTabItem) {
		this.closeTabItem = closeTabItem;
	}

	/** 统一触发入口:非弹出触发直接返回;否则定位选项卡、刷新菜单并弹出 */
	private void maybeShow(MouseEvent e) {
		if (!e.isPopupTrigger())
			return;
		ConsFrame cf = ConsFrame.cf;
		// 定位命中的选项卡(空白处=-1),记录索引供CloseTabAction消费
		int idx = cf.tabsPane.indexAtLocation(e.getX(), e.getY());
		cf.popupTabIndex = idx;
		// 空白处右键时禁用"关闭此面板"
		closeTabItem.setEnabled(idx >= 0);
		// 按当前主题刷新菜单配色后原位弹出
		cf.styleTabPopup();
		cf.tabPopup.show(cf.tabsPane, e.getX(), e.getY());
	}

	@Override
	public void mousePressed(MouseEvent e) {
		maybeShow(e);
	}

	@Override
	public void mouseReleased(MouseEvent e) {
		maybeShow(e);
	}
}
