package jls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;

import javax.swing.JEditorPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.GlyphView;
import javax.swing.text.View;
import javax.swing.text.html.HTMLEditorKit;

import org.junit.jupiter.api.Test;

/**
 * The help viewer's text size: the sizes zooming steps through, and the
 * viewer's own stylesheet, which must reach the rendered text (body,
 * headings and code) and change it when the size changes, without
 * touching the stylesheet shared by every other HTML pane.
 */
class HelpZoomTest {

	private static final String PAGE = "<html><body><h1>Heading</h1>"
			+ "<p>Body text with <code>codeword</code> in it.</p></body></html>";

	/** A laid-out viewer showing PAGE through a kit. */
	private static JEditorPane show(Help.HelpKit kit) {
		final JEditorPane pane = new JEditorPane();
		pane.setEditorKitForContentType("text/html", kit);
		pane.setEditorKit(kit);
		pane.setText(PAGE);
		pane.setSize(800, 600);
		return pane;
	}

	/** The font the view holding some text is drawn in. */
	private static Font fontOf(JEditorPane pane, String text) throws Exception {
		// paragraphs only create their text views when laid out
		final View root = pane.getUI().getRootView(pane);
		root.setSize(800, 600);
		final Font f = find(root, pane, text);
		assertNotNull(f, "no view shows " + text);
		return f;
	}

	private static Font find(View v, JEditorPane pane, String text)
			throws BadLocationException {
		if (v instanceof GlyphView g) {
			final String shown = pane.getDocument().getText(g.getStartOffset(),
					g.getEndOffset() - g.getStartOffset());
			if (shown.contains(text)) {
				return g.getFont();
			}
		}
		for (int i = 0; i < v.getViewCount(); i += 1) {
			final Font f = find(v.getView(i), pane, text);
			if (f != null) {
				return f;
			}
		}
		return null;
	}

	@Test
	void stepsMoveThroughTheListAndStopAtItsEnds() {
		assertEquals(18, Help.stepFontSize(16, 1));
		assertEquals(14, Help.stepFontSize(16, -1));
		final int[] sizes = Help.FONT_SIZES;
		assertEquals(sizes[0], Help.stepFontSize(sizes[0], -1));
		assertEquals(sizes[sizes.length - 1],
				Help.stepFontSize(sizes[sizes.length - 1], 1));
		assertEquals(sizes[0], Help.stepFontSize(16, -100));
	}

	@Test
	void anyStoredSizeSnapsToTheNearestListedOne() {
		assertEquals(16, Help.snapFontSize(17));
		assertEquals(Help.FONT_SIZES[0], Help.snapFontSize(-5));
		assertEquals(Help.FONT_SIZES[Help.FONT_SIZES.length - 1],
				Help.snapFontSize(500));
		assertTrue(java.util.Arrays.stream(Help.FONT_SIZES)
				.anyMatch(s -> s == Help.DEFAULT_FONT_SIZE));
	}

	@Test
	void theViewerShowsSansSerifTextAtTheChosenSize() throws Exception {
		final JEditorPane pane = show(new Help.HelpKit(20));
		final Font body = fontOf(pane, "Body text");
		assertEquals(20, body.getSize());
		assertEquals(Font.SANS_SERIF, body.getFamily(), body.toString());
		assertTrue(fontOf(pane, "Heading").getSize() > 20,
				"headings should be larger than body text");
		assertEquals(Font.MONOSPACED, fontOf(pane, "codeword").getFamily());
		assertEquals(20, fontOf(pane, "codeword").getSize());
	}

	@Test
	void aNewSizeReachesDocumentsCreatedAfterTheChange() throws Exception {
		final Help.HelpKit kit = new Help.HelpKit(12);
		final JEditorPane pane = show(kit);
		assertEquals(12, fontOf(pane, "Body text").getSize());
		final int heading = fontOf(pane, "Heading").getSize();

		kit.setFontSize(24);
		pane.setDocument(kit.createDefaultDocument());
		pane.setText(PAGE);
		pane.setSize(800, 600);
		assertEquals(24, fontOf(pane, "Body text").getSize());
		assertTrue(fontOf(pane, "Heading").getSize() > heading,
				"headings should grow with the text");
	}

	@Test
	void bodyTextFollowsTheSizeWhenThePaneHonorsItsOwnFont() throws Exception {
		// FlatLaf turns on honorDisplayProperties, which makes the pane's
		// own font override the stylesheet's body rule: only headings
		// changed size until the viewer's font was sized as well
		final Help.HelpKit kit = new Help.HelpKit(Help.DEFAULT_FONT_SIZE);
		final JEditorPane pane = new JEditorPane();
		pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		pane.setEditorKitForContentType("text/html", kit);
		pane.setEditorKit(kit);
		for (int pt : new int[] {12, 24}) {
			Help.sizeViewer(pane, kit, pt);
			pane.setDocument(kit.createDefaultDocument());
			pane.setText(PAGE);
			pane.setSize(800, 600);
			assertEquals(pt, fontOf(pane, "Body text").getSize(), "body at " + pt);
			assertEquals(pt, fontOf(pane, "codeword").getSize(), "code at " + pt);
			assertTrue(fontOf(pane, "Heading").getSize() > pt, "heading at " + pt);
		}
	}

	@Test
	void theSharedDefaultStylesheetIsLeftAlone() throws Exception {
		final JEditorPane plain = new JEditorPane();
		plain.setEditorKit(new HTMLEditorKit());
		plain.setText(PAGE);
		plain.setSize(800, 600);
		final Font before = fontOf(plain, "Body text");

		new Help.HelpKit(32).setFontSize(36);

		final JEditorPane again = new JEditorPane();
		again.setEditorKit(new HTMLEditorKit());
		again.setText(PAGE);
		again.setSize(800, 600);
		assertEquals(before, fontOf(again, "Body text"));
	}
}
