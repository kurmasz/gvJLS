package jls;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.Document;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.jspecify.annotations.Nullable;

/**
 * The in-app help system (issue #11): a plain Swing viewer over the
 * bundled HTML help content, replacing the abandoned JavaHelp library.
 *
 * The existing help metadata is reused as-is: help/Map.jhm maps topic
 * ids to HTML files, and help/JLSHelpTOC.xml provides the table of
 * contents shown as a tree. Both are simple enough to parse directly.
 *
 * <p>The pages carry no stylesheet of their own, so the viewer supplies
 * one (a sans-serif body and headings sized from it), and the text size
 * can be changed with the window's A&minus; / A+ buttons or Cmd/Ctrl
 * +, - and 0. The chosen size is remembered between runs.
 */
public final class Help {

	/** Topic id to help page name, parsed from help/Map.jhm on first use. */
	private static @Nullable Map<String,String> topicToUrl = null;
	/** The single help window, built lazily on first use. */
	private static @Nullable JFrame window = null;
	/** The HTML viewer pane showing the current help page. */
	private static @Nullable JEditorPane page = null;
	/** The table-of-contents tree shown beside the page viewer. */
	private static @Nullable JTree toc = null;
	/** Topic id to its path in the contents tree, for selection sync. */
	private static @Nullable Map<String,TreePath> topicToTocPath = null;
	/** The scroll pane around the page viewer. */
	private static @Nullable JScrollPane pageScroll = null;
	/** The viewer's HTML kit, which owns the size-dependent stylesheet. */
	private static @Nullable HelpKit kit = null;
	/** Where the chosen text size is remembered. */
	private static @Nullable UserPrefs prefs = null;
	/** The zoom-out button, disabled at the smallest size. */
	private static @Nullable JButton smaller = null;
	/** The zoom-in button, disabled at the largest size. */
	private static @Nullable JButton larger = null;

	/** The body text sizes, in points, that zooming steps through. */
	static final int[] FONT_SIZES = {10, 11, 12, 13, 14, 16, 18, 20, 22, 24, 28, 32, 36};
	/** The body text size before the user changes it. */
	static final int DEFAULT_FONT_SIZE = 16;
	/** The current body text size, in points. */
	private static int fontSize = DEFAULT_FONT_SIZE;

	/** No instances: this is a static utility. */
	private Help() {
	}

	/**
	 * Make a button open the help window at a topic when pressed.
	 *
	 * @param button The help button.
	 * @param topic  The topic id (a Map.jhm target).
	 */
	public static void enableHelpOnButton(JButton button, final String topic) {

		button.addActionListener(e -> showTopic(topic));
	} // end of enableHelpOnButton method

	/**
	 * Open (or raise) the help window and show a topic.
	 *
	 * @param topic The topic id (a Map.jhm target); unknown ids show the
	 *              overview page.
	 */
	public static void showTopic(String topic) {

		loadTopicMap();
		if (window == null)
			buildWindow();

		Map<String,String> map = Objects.requireNonNull(topicToUrl);
		String url = map.get(topic);
		if (url == null)
			url = map.getOrDefault("top", "overview.html");
		showPage(url);

		JTree tocTree = Objects.requireNonNull(toc);
		TreePath path = Objects.requireNonNull(topicToTocPath).get(topic);
		if (path != null) {
			tocTree.setSelectionPath(path);
			tocTree.scrollPathToVisible(path);
		}

		JFrame win = Objects.requireNonNull(window);
		win.setVisible(true);
		win.toFront();
	} // end of showTopic method

	/**
	 * Display a help page in the viewer, or an inline "not found" notice
	 * if the resource is missing from this build.
	 *
	 * @param url The help-relative page name (a Map.jhm url value).
	 */
	private static void showPage(String url) {

		JEditorPane viewer = Objects.requireNonNull(page);
		URL resource = Help.class.getResource("/help/" + url);
		if (resource == null) {
			System.err.println("JLS help: page missing from this build: help/" + url);
			viewer.setContentType("text/html");
			viewer.setText("<html><body><h1>Help page not found</h1><p>The page <tt>help/"
					+ url + "</tt> is missing from this copy of JLS."
					+ " Please report this as a bug.</p></body></html>");
			return;
		}
		try {
			viewer.setPage(resource);
		}
		catch (IOException ex) {
			System.err.println("JLS help: cannot display help/" + url + ": " + ex);
		}
	} // end of showPage method

	/**
	 * The size from FONT_SIZES nearest to a size (a stored preference
	 * may be anything).
	 *
	 * @param pt A text size in points.
	 *
	 * @return the nearest listed size.
	 *
	 * @jls.testedby jls.HelpZoomTest
	 */
	static int snapFontSize(int pt) {

		int best = FONT_SIZES[0];
		for (int size : FONT_SIZES) {
			if (Math.abs(size - pt) < Math.abs(best - pt)) {
				best = size;
			}
		}
		return best;
	} // end of snapFontSize method

	/**
	 * The text size some steps up (positive) or down (negative) from a
	 * size, stopping at the ends of FONT_SIZES.
	 *
	 * @param pt The current size in points.
	 * @param steps How many sizes to move.
	 *
	 * @return the new size.
	 *
	 * @jls.testedby jls.HelpZoomTest
	 */
	static int stepFontSize(int pt, int steps) {

		int i = java.util.Arrays.binarySearch(FONT_SIZES, snapFontSize(pt));
		i = Math.max(0, Math.min(FONT_SIZES.length - 1, i + steps));
		return FONT_SIZES[i];
	} // end of stepFontSize method

	/**
	 * The stylesheet rules for a body text size: sans-serif text with
	 * headings scaled from the body size.
	 *
	 * @param pt The body text size in points.
	 *
	 * @return CSS rules for StyleSheet.addRule.
	 *
	 * @jls.testedby jls.HelpZoomTest
	 */
	static String styleRules(int pt) {

		return "body { font-family: SansSerif; font-size: " + pt + "pt }\n"
				+ "h1 { font-size: " + Math.round(pt * 1.6) + "pt }\n"
				+ "h2 { font-size: " + Math.round(pt * 1.35) + "pt }\n"
				+ "h3 { font-size: " + Math.round(pt * 1.15) + "pt }\n"
				+ "pre { font-family: Monospaced; font-size: " + pt + "pt }\n"
				+ "code { font-family: Monospaced; font-size: " + pt + "pt }\n"
				+ "tt { font-family: Monospaced; font-size: " + pt + "pt }\n";
	} // end of styleRules method

	/**
	 * The help viewer's HTML kit. It has a stylesheet of its own (the
	 * shared default one is left alone, since other HTML panes use it)
	 * and loads pages synchronously, so a page can be re-shown at a new
	 * size and scrolled back to where the reader was.
	 */
	static final class HelpKit extends HTMLEditorKit {

		/** Serialization version (HTMLEditorKit is Serializable). */
		private static final long serialVersionUID = 1L;

		/** The kit's stylesheet: Swing's defaults plus styleRules. */
		private final StyleSheet sheet = new StyleSheet();

		/**
		 * Create a kit for a body text size.
		 *
		 * @param pt The body text size in points.
		 */
		HelpKit(int pt) {

			sheet.addStyleSheet(super.getStyleSheet());
			setFontSize(pt);
		}

		/**
		 * Change the body text size for documents created from now on.
		 *
		 * @param pt The body text size in points.
		 */
		void setFontSize(int pt) {

			sheet.addRule(styleRules(pt));
		}

		/** @return this kit's own stylesheet. */
		@Override
		public StyleSheet getStyleSheet() {
			return sheet;
		}

		/** @return a document that loads synchronously. */
		@Override
		public Document createDefaultDocument() {
			final Document doc = super.createDefaultDocument();
			((AbstractDocument) doc).setAsynchronousLoadPriority(-1);
			return doc;
		}
	} // end of HelpKit class

	/**
	 * Size a help viewer's text. Both the stylesheet and the viewer's own
	 * font are sized: FlatLaf turns on the pane's honorDisplayProperties,
	 * under which the pane's font replaces the stylesheet's body font,
	 * so sizing only the stylesheet changed nothing but the headings.
	 *
	 * @param viewer The viewer.
	 * @param htmlKit Its HTML kit.
	 * @param pt The body text size in points.
	 *
	 * @jls.testedby jls.HelpZoomTest
	 */
	static void sizeViewer(JEditorPane viewer, HelpKit htmlKit, int pt) {

		htmlKit.setFontSize(pt);
		viewer.setFont(viewer.getFont().deriveFont((float) pt));
	} // end of sizeViewer method

	/**
	 * Show the help text at a new size, keep the reader's place, and
	 * remember the size.
	 *
	 * @param pt The new body text size in points.
	 */
	private static void setFontSize(int pt) {

		final JButton down = Objects.requireNonNull(smaller);
		final JButton up = Objects.requireNonNull(larger);
		down.setEnabled(pt > FONT_SIZES[0]);
		up.setEnabled(pt < FONT_SIZES[FONT_SIZES.length - 1]);
		if (pt == fontSize) {
			return;
		}
		fontSize = pt;
		Objects.requireNonNull(prefs).rememberHelpFontSize(pt);
		final HelpKit k = Objects.requireNonNull(kit);
		final JEditorPane viewer = Objects.requireNonNull(page);
		sizeViewer(viewer, k, pt);

		// re-show the page from a fresh document, which picks up the
		// new rules, at the same relative scroll position
		final JScrollBar bar = Objects.requireNonNull(pageScroll).getVerticalScrollBar();
		final int range = bar.getMaximum() - bar.getVisibleAmount();
		final double where = range > 0 ? (double) bar.getValue() / range : 0;
		final URL current = viewer.getPage();
		final String text = current == null ? viewer.getText() : "";
		viewer.setDocument(k.createDefaultDocument());
		try {
			if (current != null) {
				viewer.setPage(current);
			}
			else {
				viewer.setText(text);
			}
		}
		catch (IOException ex) {
			System.err.println("JLS help: cannot redisplay " + current + ": " + ex);
		}
		SwingUtilities.invokeLater(() -> {
			final int newRange = bar.getMaximum() - bar.getVisibleAmount();
			bar.setValue((int) Math.round(where * newRange));
		});
	} // end of setFontSize method

	/**
	 * Bind a key, with the platform's menu modifier (Cmd or Ctrl), to an
	 * action anywhere in the help window.
	 *
	 * @param keys Where to bind it.
	 * @param keyCode The key.
	 * @param extra Extra modifiers (e.g. shift), or 0.
	 * @param name The action's name in the action map.
	 */
	private static void bind(InputMap keys, int keyCode, int extra, String name) {

		final int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
		keys.put(KeyStroke.getKeyStroke(keyCode, menu | extra), name);
	} // end of bind method

	/**
	 * Parse help/Map.jhm: lines of
	 * {@code <mapID target="..." url="..." />}.
	 */
	private static synchronized void loadTopicMap() {

		if (topicToUrl != null)
			return;
		Map<String,String> map = new HashMap<String,String>();
		topicToUrl = map;
		String text = readResource("/help/Map.jhm");
		Matcher m = Pattern
				.compile("<mapID\\s+target=\"([^\"]+)\"\\s+url=\"([^\"]+)\"")
				.matcher(text);
		while (m.find()) {
			map.put(m.group(1), m.group(2));
		}
	} // end of loadTopicMap method

	/**
	 * Parse help/JLSHelpTOC.xml into a tree. The format is nested
	 * {@code <tocitem text="..." [target="..."]>} elements.
	 *
	 * @return The root node of the contents tree.
	 */
	private static DefaultMutableTreeNode loadToc() {

		String text = readResource("/help/JLSHelpTOC.xml");
		DefaultMutableTreeNode root = new DefaultMutableTreeNode(new TocEntry("JLS Help", null));
		DefaultMutableTreeNode current = root;
		Matcher m = Pattern.compile(
				"<tocitem\\s+text=\"([^\"]+)\"(?:\\s+target=\"([^\"]+)\")?\\s*(/?)>"
						+ "|</tocitem>").matcher(text);
		while (m.find()) {
			if (m.group().equals("</tocitem>")) {
				if (current != root)
					current = (DefaultMutableTreeNode)current.getParent();
				continue;
			}
			DefaultMutableTreeNode node =
					new DefaultMutableTreeNode(new TocEntry(m.group(1), m.group(2)));
			current.add(node);
			if (m.group(3).isEmpty()) {
				current = node; // open element: children follow
			}
		}
		return root;
	} // end of loadToc method

	/**
	 * Read a bundled classpath resource as ISO-8859-1 text.
	 *
	 * @param path The absolute classpath resource path.
	 * @return The resource contents, or "" if absent or unreadable.
	 */
	private static String readResource(String path) {

		try (InputStream in = Help.class.getResourceAsStream(path)) {
			if (in == null)
				return "";
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
		}
		catch (IOException ex) {
			return "";
		}
	} // end of readResource method

	/**
	 * A TOC tree node: display text plus optional topic id.
	 *
	 * @param text  The label shown in the contents tree.
	 * @param topic The topic id to open when selected, or null.
	 */
	private record TocEntry(String text, @Nullable String topic) {

		/** The display text, so the tree renders the label directly. */
		@Override
		public String toString() {
			return text;
		}
	}

	/** Build the help window: the contents tree, the page viewer, and the
	 *  topic-to-tree-path index, all lazily on first use. */
	private static void buildWindow() {

		prefs = UserPrefs.open();
		fontSize = snapFontSize(Objects.requireNonNull(prefs)
				.helpFontSize(DEFAULT_FONT_SIZE));
		final HelpKit htmlKit = new HelpKit(fontSize);
		kit = htmlKit;

		final JEditorPane viewer = new JEditorPane();
		page = viewer;
		viewer.setEditorKitForContentType("text/html", htmlKit);
		viewer.setEditorKit(htmlKit);
		viewer.setEditable(false);
		sizeViewer(viewer, htmlKit, fontSize);
		viewer.addHyperlinkListener(new HyperlinkListener() {
			/** Follow an activated hyperlink to its target page. */
			@Override
			public void hyperlinkUpdate(HyperlinkEvent event) {
				if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED
						&& event.getURL() != null) {
					try {
						viewer.setPage(event.getURL());
					}
					catch (IOException ex) {
						System.err.println("JLS help: dead link: "
								+ event.getURL() + ": " + ex);
					}
				}
			}
		});

		DefaultMutableTreeNode root = loadToc();
		final JTree tree = new JTree(new DefaultTreeModel(root));
		toc = tree;
		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
		tree.addTreeSelectionListener(new TreeSelectionListener() {
			/** Show the page for the newly selected contents-tree node. */
			@Override
			public void valueChanged(TreeSelectionEvent event) {
				TreePath path = tree.getSelectionPath();
				if (path == null)
					return;
				DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();
				TocEntry entry = (TocEntry)node.getUserObject();
				String topic = entry.topic();
				Map<String,String> map = topicToUrl;
				if (topic != null && map != null) {
					String url = map.get(topic);
					if (url != null)
						showPage(url);
				}
			}
		});
		for (int row = 0; row < tree.getRowCount(); row += 1) {
			tree.expandRow(row);
		}

		// remember where each topic lives in the tree so showTopic can select it
		Map<String,TreePath> tocPaths = new HashMap<String,TreePath>();
		topicToTocPath = tocPaths;
		java.util.Enumeration<?> all = root.depthFirstEnumeration();
		while (all.hasMoreElements()) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode)all.nextElement();
			TocEntry entry = (TocEntry)node.getUserObject();
			String topic = entry.topic();
			if (topic != null && !tocPaths.containsKey(topic)) {
				tocPaths.put(topic, new TreePath(node.getPath()));
			}
		}

		JScrollPane tocScroll = new JScrollPane(tree);
		tocScroll.setMinimumSize(new Dimension(150, 100));
		JScrollPane scroll = new JScrollPane(viewer);
		pageScroll = scroll;
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tocScroll, scroll);
		split.setDividerLocation(220);

		final JFrame win = new JFrame("JLS Help");
		window = win;
		win.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
		win.getContentPane().setLayout(new BorderLayout());
		win.getContentPane().add(split, BorderLayout.CENTER);

		// text size: A- / A+ buttons and Cmd/Ctrl +, -, 0
		final String mod = KeyEvent.getModifiersExText(
				Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
		final String sep = mod.length() == 1 ? "" : "+";
		final AbstractAction zoomIn = new AbstractAction("A+") {
			private static final long serialVersionUID = 1L;
			/** Show larger text. */
			@Override
			public void actionPerformed(ActionEvent e) {
				setFontSize(stepFontSize(fontSize, 1));
			}
		};
		final AbstractAction zoomOut = new AbstractAction("A\u2212") {
			private static final long serialVersionUID = 1L;
			/** Show smaller text. */
			@Override
			public void actionPerformed(ActionEvent e) {
				setFontSize(stepFontSize(fontSize, -1));
			}
		};
		final AbstractAction zoomReset = new AbstractAction("reset") {
			private static final long serialVersionUID = 1L;
			/** Go back to the default text size. */
			@Override
			public void actionPerformed(ActionEvent e) {
				setFontSize(DEFAULT_FONT_SIZE);
			}
		};
		final JButton down = new JButton(zoomOut);
		down.setToolTipText("Smaller text (" + mod + sep + "\u2212)");
		smaller = down;
		final JButton up = new JButton(zoomIn);
		up.setToolTipText("Larger text (" + mod + sep + "+; " + mod + sep
				+ "0 for the default size)");
		larger = up;
		final JToolBar bar = new JToolBar();
		bar.setFloatable(false);
		bar.add(down);
		bar.add(up);
		win.getContentPane().add(bar, BorderLayout.NORTH);
		down.setEnabled(fontSize > FONT_SIZES[0]);
		up.setEnabled(fontSize < FONT_SIZES[FONT_SIZES.length - 1]);

		final JComponent rootPane = win.getRootPane();
		final InputMap keys = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		bind(keys, KeyEvent.VK_EQUALS, 0, "helpZoomIn");
		bind(keys, KeyEvent.VK_EQUALS, InputEvent.SHIFT_DOWN_MASK, "helpZoomIn");
		bind(keys, KeyEvent.VK_PLUS, 0, "helpZoomIn");
		bind(keys, KeyEvent.VK_ADD, 0, "helpZoomIn");
		bind(keys, KeyEvent.VK_MINUS, 0, "helpZoomOut");
		bind(keys, KeyEvent.VK_SUBTRACT, 0, "helpZoomOut");
		bind(keys, KeyEvent.VK_0, 0, "helpZoomReset");
		bind(keys, KeyEvent.VK_NUMPAD0, 0, "helpZoomReset");
		rootPane.getActionMap().put("helpZoomIn", zoomIn);
		rootPane.getActionMap().put("helpZoomOut", zoomOut);
		rootPane.getActionMap().put("helpZoomReset", zoomReset);
		win.setSize(900, 650);
		win.setLocationRelativeTo(JLSInfo.frame);
	} // end of buildWindow method

} // end of Help class
