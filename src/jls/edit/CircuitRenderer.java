package jls.edit;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.print.Book;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.jspecify.annotations.Nullable;

import jls.Circuit;
import jls.core.Geometry;
import jls.elem.Element;
import jls.elem.StateMachine;
import jls.elem.SubCircuit;
import jls.elem.TruthTable;
import jls.elem.Wire;

/**
 * GUI-side rendering of a {@link Circuit} (issue #77). Drawing, printing,
 * and image export are view concerns that used to live on the model class
 * (Circuit implemented Printable and imported AWT/Swing to draw itself);
 * they move here so the model stays headless. The circuit exposes only
 * the model seams this needs — its elements, spatial-index queries,
 * bounds, and load-finishing — none of which touch AWT.
 */
public final class CircuitRenderer implements Printable {

	/**
	 * How far outside its index bounds an element may draw (labels and
	 * similar decorations). Draw culling pads by this margin on both the
	 * index query and the exact visibility check.
	 */
	private static final int DRAW_MARGIN = 8 * Geometry.SPACING;

	/** The circuit this renderer draws. */
	private final Circuit circuit;

	/**
	 * Creates a renderer for a circuit.
	 *
	 * @param circuit The circuit to render.
	 */
	private CircuitRenderer(Circuit circuit) {
		this.circuit = circuit;
	} // end of constructor

	/**
	 * A renderer for a circuit.
	 *
	 * @param circuit The circuit to render.
	 * @return a renderer over it.
	 */
	public static CircuitRenderer of(Circuit circuit) {
		return new CircuitRenderer(circuit);
	} // end of of method

	/**
	 * Draw the circuit by drawing every element. First the set of elements
	 * not in the second set are drawn, then the ones in the second set are
	 * drawn. Wires are drawn first in each set. Moved verbatim from
	 * {@code Circuit.draw} (issue #77).
	 *
	 * @param g The graphics object to draw with.
	 * @param second The second set of elements to draw.
	 * @param ed The editor window doing the drawing.
	 * @throws Exception if an unexpected problem stops the deferred
	 *             finish-load or the drawing pass.
	 *
	 * @jls.testedby jls.DrawCullingParityTest#tiledClippedDrawsMatchFullDraw()
	 */
	public void draw(Graphics g, Set<Element> second, @Nullable SimpleEditor ed)
			throws Exception {

		// finish up loading process if necessary
		if (circuit.isLoadPending()) {
			if (!circuit.finishLoad(SwingTextMetrics.forGraphics(g))) {
				// report once instead of silently re-failing on every
				// repaint (#58)
				circuit.reportDeferredFinishFailure();
			}

			// set circuit size to the largest of the default area or the
			// needed area
			Rectangle rect = new Rectangle(0, 0, Geometry.CIRCUITSIZE,
					Geometry.CIRCUITSIZE);
			rect.add(AwtGeom.awt(circuit.getBounds()));
			if (ed != null) {
				ed.setCircuitSize(rect.getSize());
			}
		}

		// partition into draw layers in one pass instead of four full
		// scans (#27 S3): wires under non-wires, the second (selected)
		// set on top of both. Elements far outside the clip cannot be
		// visible and are skipped, so a scrolled view pays for what it
		// shows, not for the whole circuit (#17). The candidates come
		// from the spatial index, not a full scan, so a dirty-region
		// repaint during a drag costs O(visible), not O(circuit); the
		// query pads the clip by the same margin mayBeVisible allows
		// for labels, so its exact check below accepts the same
		// elements a full scan would. That parity is machine-checked:
		// THEOREM 2 (culling-parity) in
		// proofs/SpatialIndexCorrectness.agda, with the margin/grow/
		// intersects assumptions pinned by jls.ProofBridgeTest.
		Rectangle clip = g.getClipBounds();
		Collection<Element> candidates;
		if (clip == null) {
			candidates = circuit.getElements();
		} else {
			Rectangle query = new Rectangle(clip);
			query.grow(DRAW_MARGIN, DRAW_MARGIN);
			candidates = circuit.elementsNear(AwtGeom.bounds(query));
		}
		List<Element> wires = new ArrayList<Element>();
		List<Element> parts = new ArrayList<Element>();
		List<Element> secondWires = new ArrayList<Element>();
		List<Element> secondParts = new ArrayList<Element>();
		for (Element el : candidates) {
			if (clip != null && !mayBeVisible(el, clip)) {
				continue;
			}
			if (el instanceof Wire) {
				(second.contains(el) ? secondWires : wires).add(el);
			} else {
				(second.contains(el) ? secondParts : parts).add(el);
			}
		}
		for (Element el : wires) {
			ElementRenderers.draw(g, el);
		}
		for (Element el : parts) {
			ElementRenderers.draw(g, el);
		}
		for (Element el : secondWires) {
			ElementRenderers.draw(g, el);
		}
		for (Element el : secondParts) {
			ElementRenderers.draw(g, el);
		}
	} // end of draw method

	/**
	 * Whether an element could draw inside the clip. The margin generously
	 * covers labels drawn near (but outside) an element's bounds.
	 *
	 * @param el The element to test.
	 * @param clip The clip rectangle.
	 *
	 * @return true if the element's margin-padded bounds intersect the clip.
	 */
	private static boolean mayBeVisible(Element el, Rectangle clip) {

		Rectangle b = AwtGeom.awt(el.getIndexBounds());
		b.grow(DRAW_MARGIN, DRAW_MARGIN);
		return b.intersects(clip);
	} // end of mayBeVisible method

	/**
	 * Print the circuit. Moved verbatim from {@code Circuit.print} (issue
	 * #77); the circuit no longer implements {@link Printable} - this
	 * renderer does.
	 *
	 * @param g The graphics object to use.
	 * @param format Page format info.
	 * @param pagenum Ignored.
	 * @return Printable.PAGE_EXISTS.
	 *
	 * @jls.testedby jls.PrintPathSmokeTest#printingTheCircuitDirectlyRenders()
	 */
	@Override
	public int print(Graphics g, PageFormat format, int pagenum) {

		// use better graphics
		Graphics2D gg = (Graphics2D) g;

		// construct name
		Circuit c = circuit;
		String nm = circuit.getName();
		SubCircuit se;
		while ((se = c.getSubElement()) != null) {
			c = se.getCircuit();
			nm += " in " + c.getName();
		}

		// set up
		FontMetrics fm = gg.getFontMetrics();
		int ascent = fm.getAscent();
		int descent = fm.getDescent();
		int fontHeight = ascent + descent;

		// get bounds of actual circuit
		Rectangle rect = AwtGeom.awt(circuit.getBounds());

		// translate to page area
		double width = format.getImageableWidth();
		double height = format.getImageableHeight();
		gg.translate(format.getImageableX(), format.getImageableY());

		// draw title
		gg.drawString(nm, 0, ascent);

		// translate and scale to fit circuit to remaining page area
		gg.translate(0, fontHeight * 2);
		height -= fontHeight * 2;
		double scale = 1.0;
		if (rect.width > width) {
			scale = width / rect.width;
		}
		if (rect.height + Geometry.POINT_DIAMETER > height) {
			scale = Math.min(scale, height
					/ (rect.height + Geometry.POINT_DIAMETER));
		}
		gg.scale(scale, scale);
		gg.translate(-rect.x + Geometry.POINT_DIAMETER / 2, -rect.y
				+ Geometry.POINT_DIAMETER / 2);

		// print
		try {
			draw(gg, new HashSet<Element>(), null);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return Printable.PAGE_EXISTS;
	} // end of print method

	/**
	 * Add this circuit to the print book, add any of its state machines,
	 * truth tables and all subcircuits. Moved verbatim from
	 * {@code Circuit.addToBook} (issue #77); the circuit's page is this
	 * renderer (a {@link Printable}), and subcircuits recurse through
	 * their own renderers.
	 *
	 * @param book The book to add to.
	 * @param format The page format to use.
	 *
	 * @jls.testedby jls.PrintPathSmokeTest#everyBookedPagePrintsIntoAGraphics()
	 */
	public void addToBook(Book book, PageFormat format) {

		// add this circuit
		book.append(this, format);

		// canonical page order (#182): iterating the element HashSet
		// would order the pages by identity hash, which varies between
		// runs - two prints of one circuit could page differently
		List<Element> ordered = circuit.getElementsInStableOrder();

		// add state machines
		for (Element el : ordered) {
			if (el instanceof StateMachine sm) {
				book.append(StateMachineRenderer.diagramPage(sm), format);
				Printable p = StateMachineRenderer.outSummaryPage(sm);
				if (p != null)
					book.append(p, format);
			}
		}

		// add truth tables
		for (Element el : ordered) {
			if (el instanceof TruthTable tt) {
				book.append(new TruthTablePrintable(tt), format);
			}
		}

		// add subcircuits
		for (Element el : ordered) {
			if (el instanceof SubCircuit sub) {
				CircuitRenderer.of(sub.getSubCircuit()).addToBook(book, format);
			}
		}
	} // end of addToBook method

	/**
	 * Export an image of the circuit. Moved verbatim from
	 * {@code Circuit.exportImage} (issue #77).
	 *
	 * @param file The name of the file to write to.
	 * @throws Exception if the image file cannot be written.
	 *
	 * @jls.testedby jls.ElementDrawSmokeTest#everyElementDrawsOnTheRasterExportPath()
	 * @jls.testedby jls.ElementDrawSmokeTest#everyElementDrawsOnTheSvgExportPath()
	 * @jls.testedby jls.SvgExportTest#exportingTwiceIsByteIdentical()
	 * @jls.testedby jls.SvgExportTest#theDocumentIsAnSvgImageWithDrawnContent()
	 */
	public void exportImage(String file) throws Exception {

		// get bounds of actual circuit
		Rectangle rect = AwtGeom.awt(circuit.getBounds());

		// add 10 pixels on all edges
		int border = 10;
		rect = new Rectangle(rect.x - border, rect.y - border, rect.width + 2
				* border, rect.height + 2 * border);

		// vector export (issue #154): the same element paint path that
		// fills a bitmap below draws into JFreeSVG's Graphics2D instead,
		// so .svg output needs no per-element work
		if (file.toLowerCase(java.util.Locale.ROOT).endsWith(".svg")) {
			org.jfree.svg.SVGGraphics2D svg =
					new org.jfree.svg.SVGGraphics2D(rect.width, rect.height);
			// a fixed defs prefix keeps two exports of the same circuit
			// byte-identical (the default prefix is instance-derived)
			svg.setDefsKeyPrefix("jls");
			AffineTransform svgTranslate = new AffineTransform();
			svgTranslate.translate(-rect.x, -rect.y);
			svg.setTransform(svgTranslate);
			svg.setColor(Color.white);
			svg.fill(rect);
			drawInStableOrder(svg);
			try {
				java.nio.file.Files.writeString(java.nio.file.Path.of(file),
						svg.getSVGDocument(),
						java.nio.charset.StandardCharsets.UTF_8);
			} finally {
				svg.dispose();
			}
			return;
		}

		// set up image
		BufferedImage image = new BufferedImage(rect.width, rect.height,
				BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		AffineTransform translate = new AffineTransform();
		translate.translate(-rect.x, -rect.y);
		g.setTransform(translate);

		// draw the image
		g.setColor(Color.white);
		g.fill(rect);
		draw(g, new HashSet<Element>(), null);

		// write the image, the format following the file extension
		// (issue #71): .png produces PNG, anything else the legacy JPEG
		try {
			String format = file.toLowerCase(java.util.Locale.ROOT)
					.endsWith(".png") ? "png" : "jpg";
			if (!ImageIO.write(image, format, new File(file))) {
				throw new IOException("no " + format + " image writer available");
			}
		} finally {
			// clean up
			g.dispose();
			image.flush();
		}

	} // end of exportImage method

	/**
	 * Draw every element in a deterministic order: elements live in a
	 * HashSet, and while raster export doesn't care (same pixels either
	 * way, overlaps aside), SVG serializes the draw order into the
	 * file - an unstable order would break byte-identical goldens
	 * across load instances. Wires under non-wires, like the
	 * interactive draw path.
	 *
	 * @param g The graphics to draw with.
	 */
	private void drawInStableOrder(Graphics2D g) {

		List<Element> wireLayer = new ArrayList<Element>();
		List<Element> partLayer = new ArrayList<Element>();
		for (Element el : circuit.getElements()) {
			(el instanceof Wire ? wireLayer : partLayer).add(el);
		}
		// order on index bounds, not x/y: wires keep x/y at their
		// defaults, but their bounds are derived from their ends
		java.util.Comparator<Element> drawOrder = java.util.Comparator
				.comparingInt((Element el) -> el.getIndexBounds().x())
				.thenComparingInt(el -> el.getIndexBounds().y())
				.thenComparingInt(el -> el.getIndexBounds().width())
				.thenComparingInt(el -> el.getIndexBounds().height())
				.thenComparing(el -> el.getClass().getName())
				.thenComparingInt(Element::getID);
		wireLayer.sort(drawOrder);
		partLayer.sort(drawOrder);
		for (Element el : wireLayer) {
			ElementRenderers.draw(g, el);
		}
		for (Element el : partLayer) {
			ElementRenderers.draw(g, el);
		}
	} // end of drawInStableOrder method

	/**
	 * One circuit in a stacked export, with the caption drawn above it.
	 *
	 * @param caption The caption, e.g. a dotted subcircuit path.
	 * @param circuit The circuit to draw.
	 */
	public record Captioned(String caption, Circuit circuit) {
	}

	/** Border around each circuit in an export, in pixels. */
	private static final int BORDER = 10;
	/** Height of the caption band above each circuit in a stack. */
	private static final int CAPTION_HEIGHT = 28;
	/** Vertical space between circuits in a stack (holds a rule). */
	private static final int STACK_GAP = 24;
	/** Largest side JPEG can encode. */
	private static final int JPEG_MAX_SIDE = 65535;

	/**
	 * Export several circuits into one image, stacked top to bottom,
	 * each under its caption and separated by a thin rule. The format
	 * follows the file extension as in {@link #exportImage(String)}.
	 *
	 * @param items The circuits, in the order to stack them.
	 * @param file The name of the file to write to.
	 * @throws IOException if the image is too large for its format or
	 *             cannot be written.
	 * @throws Exception if drawing fails.
	 *
	 * @jls.testedby jls.CliImageExportTest
	 */
	public static void exportStack(List<Captioned> items, String file)
			throws Exception {

		// each circuit's area: its bounds plus the border
		final List<Rectangle> areas = new ArrayList<>();
		long width = 0;
		long height = 0;
		for (Captioned item : items) {
			Rectangle r = AwtGeom.awt(item.circuit().getBounds());
			r = new Rectangle(r.x - BORDER, r.y - BORDER,
					r.width + 2 * BORDER, r.height + 2 * BORDER);
			areas.add(r);
			width = Math.max(width, r.width);
			height += CAPTION_HEIGHT + r.height;
		}
		height += (long) STACK_GAP * Math.max(0, items.size() - 1);
		final String lower = file.toLowerCase(java.util.Locale.ROOT);
		final boolean svgOut = lower.endsWith(".svg");
		final boolean jpegOut = !svgOut && !lower.endsWith(".png");
		if (jpegOut && (width > JPEG_MAX_SIDE || height > JPEG_MAX_SIDE)) {
			throw new IOException("the stacked image is " + width + "x"
					+ height + " pixels, but JPEG allows at most "
					+ JPEG_MAX_SIDE + " per side; use .png or .svg");
		}
		if (!svgOut && width * height > Integer.MAX_VALUE) {
			throw new IOException("the stacked image is " + width + "x"
					+ height + " pixels, too large for a bitmap; use .svg");
		}
		final int w = (int) width;
		final int h = (int) height;

		final org.jfree.svg.SVGGraphics2D svg = svgOut
				? new org.jfree.svg.SVGGraphics2D(w, h) : null;
		final BufferedImage image = svgOut ? null
				: new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = svg != null ? svg
				: java.util.Objects.requireNonNull(image).createGraphics();
		try {
			if (svg != null) {
				svg.setDefsKeyPrefix("jls");
			}
			g.setColor(Color.white);
			g.fillRect(0, 0, w, h);
			// element drawing inherits the context's font, so each
			// circuit must be drawn with the font a single export has,
			// not the caption's (else labels overflow their shapes)
			final java.awt.Font circuitFont = g.getFont();
			final java.awt.Font captionFont =
					new java.awt.Font(java.awt.Font.SANS_SERIF,
							java.awt.Font.BOLD, 16);
			int y = 0;
			for (int k = 0; k < items.size(); k += 1) {
				final Rectangle area = areas.get(k);
				if (k > 0) {
					g.setTransform(new AffineTransform());
					g.setColor(Color.lightGray);
					g.drawLine(BORDER, y + STACK_GAP / 2, w - BORDER,
							y + STACK_GAP / 2);
					y += STACK_GAP;
				}
				g.setTransform(new AffineTransform());
				g.setColor(Color.black);
				g.setFont(captionFont);
				g.drawString(items.get(k).caption(), BORDER,
						y + CAPTION_HEIGHT - 8);
				y += CAPTION_HEIGHT;
				final AffineTransform place = new AffineTransform();
				place.translate(-area.x, y - area.y);
				g.setTransform(place);
				g.setFont(circuitFont);
				g.setColor(Color.white);
				final CircuitRenderer renderer = of(items.get(k).circuit());
				if (svg != null) {
					renderer.drawInStableOrder(g);
				}
				else {
					renderer.draw(g, new HashSet<Element>(), null);
				}
				y += area.height;
			}

			if (svg != null) {
				java.nio.file.Files.writeString(java.nio.file.Path.of(file),
						svg.getSVGDocument(),
						java.nio.charset.StandardCharsets.UTF_8);
			}
			else {
				final String format = jpegOut ? "jpg" : "png";
				if (!ImageIO.write(java.util.Objects.requireNonNull(image),
						format, new File(file))) {
					throw new IOException("no " + format
							+ " image writer available");
				}
			}
		} finally {
			g.dispose();
			if (image != null) {
				image.flush();
			}
		}
	} // end of exportStack method

} // end of CircuitRenderer class
