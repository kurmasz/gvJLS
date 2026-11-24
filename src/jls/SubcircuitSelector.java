package jls;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Which levels of a circuit a set of -circuit / -list-subcircuits
 * patterns selects. Each pattern is either "." (the top-level circuit)
 * or a {@link SubcircuitGlob} over dotted subcircuit paths; a bare
 * "**" selects the top level as well as every subcircuit. Globs never
 * select the top level otherwise, since its path is empty and "*"
 * would match it.
 *
 * <p>Selected circuits come back in tree order: the top level first,
 * then depth-first with siblings in natural order (adder2 before
 * adder10) - the order -list-subcircuits prints them in. A name
 * repeated at one level counts once, since a dotted path can only
 * reach the first.
 */
public final class SubcircuitSelector {

	/** The pattern that selects the top-level circuit. */
	public static final String TOP = ".";

	/**
	 * One selected circuit.
	 *
	 * @param path Its dotted path, or "" for the top level.
	 * @param circuit The circuit itself.
	 */
	public record Selected(String path, Circuit circuit) {
	}

	/** The patterns as given. */
	private final List<String> patterns;
	/** The compiled globs, parallel to patterns; null for ".". */
	private final List<@Nullable Pattern> globs;

	/**
	 * Create a selector.
	 *
	 * @param patterns The patterns, each already accepted by problem().
	 */
	private SubcircuitSelector(List<String> patterns) {

		this.patterns = List.copyOf(patterns);
		this.globs = new ArrayList<>();
		for (String p : patterns) {
			globs.add(p.equals(TOP) ? null : SubcircuitGlob.compile(p));
		}
	} // end of constructor

	/**
	 * A selector for some patterns.
	 *
	 * @param patterns The patterns, each already accepted by problem().
	 *
	 * @return the selector.
	 *
	 * @throws IllegalArgumentException if a pattern is malformed.
	 *
	 * @jls.testedby jls.SubcircuitSelectorTest
	 */
	public static SubcircuitSelector of(List<String> patterns) {
		return new SubcircuitSelector(patterns);
	} // end of of method

	/**
	 * What is wrong with a pattern, if anything: "." is fine; a pattern
	 * with no glob characters must be a valid dotted path (names of
	 * letters, digits and underscores, starting with a letter); a glob
	 * must compile and have no empty level (no leading, trailing or
	 * doubled dot).
	 *
	 * @param pattern The pattern to check.
	 *
	 * @return a short description of the problem, or null if the
	 *         pattern is valid.
	 *
	 * @jls.testedby jls.SubcircuitSelectorTest
	 */
	public static @Nullable String problem(String pattern) {

		if (pattern.equals(TOP)) {
			return null;
		}
		if (pattern.isEmpty() || pattern.startsWith(".")
				|| pattern.endsWith(".") || pattern.contains("..")) {
			return "empty level";
		}
		if (SubcircuitGlob.isLiteral(pattern)) {
			return JLSStart.parseName(pattern) == null ? "invalid name"
					: null;
		}
		try {
			SubcircuitGlob.compile(pattern);
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		return null;
	} // end of problem method

	/**
	 * Whether some pattern selects the circuit at a path.
	 *
	 * @param path The dotted path, or "" for the top level.
	 *
	 * @return true if selected.
	 */
	public boolean selects(String path) {

		for (int k = 0; k < patterns.size(); k += 1) {
			if (selects(k, path)) {
				return true;
			}
		}
		return false;
	} // end of selects method

	/**
	 * Whether pattern k selects the circuit at a path.
	 *
	 * @param k The pattern's index.
	 * @param path The dotted path, or "" for the top level.
	 *
	 * @return true if selected.
	 */
	private boolean selects(int k, String path) {

		final Pattern glob = globs.get(k);
		if (path.isEmpty()) {
			return glob == null || patterns.get(k).equals("**");
		}
		return glob != null && glob.matcher(path).matches();
	} // end of selects method

	/**
	 * The circuits some pattern selects, in tree order.
	 *
	 * @param top The top-level circuit.
	 *
	 * @return the selected circuits.
	 *
	 * @jls.testedby jls.SubcircuitSelectorTest
	 */
	public List<Selected> select(Circuit top) {

		final List<Selected> out = new ArrayList<>();
		for (Selected s : allLevels(top)) {
			if (selects(s.path())) {
				out.add(s);
			}
		}
		return out;
	} // end of select method

	/**
	 * The patterns that select nothing in a circuit.
	 *
	 * @param top The top-level circuit.
	 *
	 * @return those patterns, in the order given.
	 *
	 * @jls.testedby jls.SubcircuitSelectorTest
	 */
	public List<String> unmatched(Circuit top) {

		final List<Selected> levels = allLevels(top);
		final List<String> out = new ArrayList<>();
		for (int k = 0; k < patterns.size(); k += 1) {
			boolean hit = false;
			for (Selected s : levels) {
				if (selects(k, s.path())) {
					hit = true;
					break;
				}
			}
			if (!hit) {
				out.add(patterns.get(k));
			}
		}
		return out;
	} // end of unmatched method

	/**
	 * Every level of a circuit, in tree order, top level first.
	 *
	 * @param top The top-level circuit.
	 *
	 * @return the levels with their paths.
	 */
	public static List<Selected> allLevels(Circuit top) {

		final List<Selected> out = new ArrayList<>();
		out.add(new Selected("", top));
		addLevels(top, "", out);
		return out;
	} // end of allLevels method

	/**
	 * Append the subcircuits below a circuit, depth-first.
	 *
	 * @param circ The circuit.
	 * @param path Its dotted path, or "" for the top level.
	 * @param out Where to append.
	 */
	private static void addLevels(Circuit circ, String path,
			List<Selected> out) {

		for (String name : childNames(circ)) {
			final Circuit sub = circ.getSubCircuit(name);
			if (sub == null)
				continue;
			final String subPath = path.isEmpty() ? name : path + "." + name;
			out.add(new Selected(subPath, sub));
			addLevels(sub, subPath, out);
		}
	} // end of addLevels method

	/**
	 * The distinct names of the subcircuits directly inside a circuit,
	 * in natural order.
	 *
	 * @param circ The circuit.
	 *
	 * @return the names.
	 */
	public static List<String> childNames(Circuit circ) {

		final List<String> names = new ArrayList<>(
				new LinkedHashSet<>(circ.getSubCircuitNames()));
		names.sort(Util::naturalCompare);
		return names;
	} // end of childNames method
}
