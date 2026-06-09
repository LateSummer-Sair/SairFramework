package sair.sys.gui;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.MenuItem;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.KeyEvent;

import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

import sair.FCM;
import sair.sys.gui.swing.control.SBorder;
import sair.sys.gui.swing.control.SButton;
import sair.sys.gui.swing.control.SFrame;
import sair.sys.gui.swing.control.SairScrollBarUI;
import sair.sys.gui.swing.tools.Backgrounds;
import sair.sys.gui.swing.tools.BorderButton;
import sair.sys.gui.swing.tools.Clicks;
import sair.sys.gui.swing.tools.Fonts;

import javax.swing.JTextField;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.AbstractButton;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;

public class ConsFrame extends SFrame {

	public static final String title_str = "SairFrameWork";
	public static final Font font = Fonts.FONTS_TOOLS.getFont(null, null, 13.0f);
	public static final FrameActivity fa = new FrameActivity();
	static final int w = 800;
	static final int h = 600;
	/**
	 *
	 */
	private static final long serialVersionUID = 3271120553991368988L;
	public static ConsFrame cf = new ConsFrame();
	public DefaultListModel<String> listModel = new DefaultListModel<String>();
	public JList<String> list = new JList<String>(listModel);
	JScrollPane listP_JSP = new JScrollPane();
	JTextField input = new JTextField();
	JLabel title = new JLabel();
	JPanel inputPanel = new JPanel();
	JLabel sysinfo = new JLabel("SFW_>");
	JPanel titlePanel = new JPanel();
	SButton sair = new SButton("Sair");
	SButton exit = new SButton("Exit");
	JTextPane infoPane = new JTextPane();
	JScrollPane centerScorllPane = new JScrollPane();
	Color bgC = Color.DARK_GRAY;
	Color otC = Color.GREEN;
	private PopupMenu popup;
	private MenuItem resetSize_popup, close_popup;
	private Font p_f = font;
	private String BGpath;
	private TrayIcon trayIcon;

	private ConsFrame() {
		super(w, h);
		this.setTitle(title_str);
		this.setDefaultCloseOperation(SFrame.EXIT_ON_CLOSE);
		this.initTary();

		this.initComp();
		this.initAction();

		this.re_init_styles(w, h);

	}

	public static Color getFontColor() {
		return cf.otC;
	}

	public static final void setFontColor(Color c) {
		cf.otC = c;
		cf.reinit_Color();
		if (cf.BGpath != null)
			setImageBackground(cf.BGpath);
	}

	public static final void showFrame() {
		cf.setVisible(true);
	}

	public static final void hideFrame() {
		cf.setVisible(false);
	}

	public static final void flushPoint() {
		cf.flushSelect();
	}

	public static final void setBackgroundColor(Color c) {
		cf.bgC = c;
		cf.reinit_Color();
		if (cf.BGpath != null)
			setImageBackground(cf.BGpath);
	}

	public static final void setImageBackground(String path) {
		if (path != null && !"null".equals(path)) {
			Backgrounds.BG_TOOLS.setNewImageToJPanel(path, cf.centerPanel);
			cf.BGpath = path;
		} else {
			cf.BGpath = null;
			cf.reinit_Color();
		}
	}

	public static final void setTitleInfo(String title) {
		cf.setTitle(title);
	}

	public static final void printo(Integer index, Color c, String text) {
		if (c == null)
			c = cf.otC;
		if (null == text)
			text = "null";
		SimpleAttributeSet attrset = new SimpleAttributeSet();
		StyleConstants.setForeground(attrset, c);

		Document docs = cf.infoPane.getDocument();
		if (index == null)
			index = docs.getLength();
		try {
			if (docs.getLength() >= Integer.MAX_VALUE / 2)
				cf.infoPane.setText("");
			if (text.length() > Integer.MAX_VALUE / 2)
				docs.insertString(index, "text is too long", attrset);
			else
				docs.insertString(index, text, attrset);
		} catch (BadLocationException ble) {
		}

	}

	public static final JTextPane getTextPane() {
		return cf.infoPane;
	}

	public static final void printComponent(Component component) {
		ConsFrame.printo(null, null, "\r\n");
		int posi = cf.infoPane.getDocument().getLength();
		if (posi < 0)
			posi = 0;
		cf.infoPane.setCaretPosition(posi);
		cf.infoPane.insertComponent(component);
	}

	public static final void printComponent(Color c, String labelString) {
		if (c == null)
			c = cf.otC;
		JLabel lab = new JLabel();
		lab.setText(labelString);
		//lab.setEditable(false);
		lab.setFont(cf.p_f);
		lab.setForeground(c);
		lab.setBorder(null);
		//lab.setOpaque(false);
		printComponent(lab);
	}

	public static final void printComponent(String labelString) {
		printComponent(null, labelString);
	}

	public static final void dePrinto(Integer offs, Integer len) {
		Document docs = cf.infoPane.getDocument();
		if (offs == null && len == null) {
			offs = docs.getLength() - 1;
			len = 1;
		} else {
			if (offs == null)
				offs = 0;
			if (len == null || len > docs.getLength())
				len = docs.getLength() - offs;
		}
		try {
			docs.remove(offs, len);
		} catch (Exception ble) {
			// System.out.println("E");
		}

	}

	public static String getAllText() {
		return cf.infoPane.getText();
	}

	public final static int getPaneSize() {
		int docLen = cf.infoPane.getDocument().getLength();
		int comLen = cf.infoPane.getComponentCount();
		return docLen + comLen;
	}

	public final static void close() {
		System.exit(0);
	}

	private void initTary() {
		if (SystemTray.isSupported()) {
			trayIcon = new TrayIcon(this.getIconImage());
			trayIcon.setImageAutoSize(true);
			trayIcon.setToolTip(this.getTitle());

			popup = new PopupMenu();

			resetSize_popup = new MenuItem("reset GUI");
			resetSize_popup.addActionListener(ClicksAct.clicksActs.clicks_resetGUI);
			resetSize_popup.setFont(font);

			close_popup = new MenuItem("exit");
			close_popup.addActionListener(ClicksAct.clicksActs.clicks_exit);
			close_popup.setFont(font);

			popup.add(resetSize_popup);
			popup.add(close_popup);

			popup.setFont(font);

			trayIcon.addMouseListener(ClicksAct.clicksActs.icoClick);

			trayIcon.setPopupMenu(popup);
		}
	}

	private void re_init_styles(int w, int h) {

		this.initSize(w, h);
		this.initFont(w, h);
		this.reinit_Color();

	}

	public void setSize(int w, int h) {
		this.re_init_styles(w, h);
		super.setSize(w, h);
		if (BGpath != null)
			setImageBackground(BGpath);
	}

	private void initFont(int w, int h) {
		float wf = w;
		float hf = h;
		float result = (wf * 0.01f) + (hf * 0.01f);
		p_f = Fonts.FONTS_TOOLS.getFont(null, null, result);
	}

	private void initAction() {
		// showList.setModel(dlm);
		infoPane.setEditable(false);

		exit.addActionListener(ClicksAct.clicksActs.clicks_exit);
		sair.addActionListener(ClicksAct.clicksActs.clicks_sair);
		// showList.addMouseListener(listClick);

		Clicks.CLICKS_TOOLS.enterPressesWhenFocused(input, ClicksAct.clicksActs.clicks_enter, null);
		Clicks.CLICKS_TOOLS.enterPressesWhenFocused(input, ClicksAct.clicksActs.clicks_up, KeyEvent.VK_UP);
		Clicks.CLICKS_TOOLS.enterPressesWhenFocused(input, ClicksAct.clicksActs.clicks_down, KeyEvent.VK_DOWN);

		Clicks.CLICKS_TOOLS.drag(input);
	}

	private void initSize(int wi, int hi) {
		int h = hi / 20, w = wi / 20;
		Dimension btd = new Dimension((int) (w * 2), (int) (h * 1.7)), ipd = new Dimension((int) (w * 2), h);
		listP_JSP.setPreferredSize(new Dimension((int) (w * 4), h));
		exit.setPreferredSize(btd);
		sair.setPreferredSize(btd);
		sysinfo.setPreferredSize(ipd);
		sysinfo.setHorizontalAlignment(SwingConstants.CENTER);
		sysinfo.setVerticalAlignment(SwingConstants.CENTER);
	}

	private synchronized void reinit_Color() {
		super.centerPanel.setBackground(bgC);
		setOpenSetting(false);
		JComponent[] cts = new JComponent[] { list, listP_JSP, input, title, inputPanel, sysinfo, titlePanel, sair,
				exit, infoPane, centerScorllPane, };
		for (JComponent ct : cts) {
			ct.setFont(p_f);
			ct.setOpaque(false);// 透明化
			if (ct instanceof JTextPane) {
				((JTextPane) ct).setSelectedTextColor(cavg(true));
				((JTextPane) ct).setSelectionColor(cavg(false));
			} else {
				ct.setForeground(otC);
				if (ct instanceof AbstractButton)
					((AbstractButton) ct).setContentAreaFilled(false);
				else
					ct.setBorder(null);

				if (ct instanceof JScrollPane) {
					JScrollPane jsp = (JScrollPane) ct;
					jsp.getViewport().setOpaque(false);// 透明化
					JScrollBar sbv = jsp.getVerticalScrollBar();
					if (sbv != null) {
						sbv.setUI(new SairScrollBarUI(otC, otC, otC));
						sbv.setOpaque(false);// 透明化
					}
					JScrollBar sbh = jsp.getHorizontalScrollBar();
					if (sbh != null) {
						sbh.setUI(new SairScrollBarUI(otC, otC, otC));
						sbh.setOpaque(false);// 透明化
					}
					jsp.setBorder(new SBorder(otC));
				}
				if (ct instanceof JTextField) {
					ct.setBorder(new SBorder(otC));
					((JTextField) ct).setSelectedTextColor(cavg(true));
					((JTextField) ct).setSelectionColor(cavg(false));
					((JTextField) ct).setCaretColor(cavg(false));
				}
				if (ct instanceof JList)
					ct.setBorder(new SBorder(FCM.loadExection_Color));
				for (BorderButton border : getBorders())
					border.setForeground(otC);
			}
		}
	}

	private void flushSelect() {
		Point p = new Point();
		p.setLocation(0, (int) ((double) (infoPane.getHeight()) * 1.5));
		centerScorllPane.getViewport().setViewPosition(p);
	}

	private void initComp() {
		input.setColumns(10);

		titlePanel.setLayout(new BorderLayout(0, 0));
		inputPanel.setLayout(new BorderLayout(0, 0));
		centerPanel.setLayout(new BorderLayout(0, 0));

		list.setCellRenderer(new ListCellRenderer());
		list.addMouseListener(new MouseAdapter());
		listP_JSP.setViewportView(list);

		inputPanel.add(input, BorderLayout.CENTER);
		inputPanel.add(sysinfo, BorderLayout.WEST);
		titlePanel.add(sair, BorderLayout.WEST);
		titlePanel.add(exit, BorderLayout.EAST);
		titlePanel.add(title, BorderLayout.CENTER);

		centerScorllPane.setViewportView(infoPane);

		centerPanel.add(inputPanel, BorderLayout.SOUTH);
		centerPanel.add(titlePanel, BorderLayout.NORTH);
		centerPanel.add(centerScorllPane, BorderLayout.CENTER);

	}

	public void setVisible(boolean b) {
		super.setVisible(b);
		try {
			if (trayIcon != null)
				if (b)
					SystemTray.getSystemTray().remove(trayIcon);
				else
					SystemTray.getSystemTray().add(trayIcon);
		} catch (AWTException e) {

		}
	}

	public void setBounds(Rectangle o_b) {
		super.setBounds(o_b);

		this.re_init_styles(o_b.width, o_b.height);
		if (BGpath != null)
			setImageBackground(BGpath);
	}

	private Color cavg(boolean ist) {
		if (!ist)
			return otC;
		else
			return bgC;
	}
}
