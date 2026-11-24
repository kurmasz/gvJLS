package jls;

import java.util.regex.Pattern;

/**
 * Shell-style glob patterns over dotted subcircuit paths such as
 * "alu.adder2", used by -list-subcircuits (and intended for -circuit).
 * The dot separates levels, so:
 * <ul>
 * <li>{@code *} matches any run of characters within one name;</li>
 * <li>{@code ?} matches one character within a name;</li>
 * <li>{@code [abc]}, {@code [a-z]}, {@code [!0-9]} (or {@code [^0-9]})
 * match one character of (or not of) a set;</li>
 * <li>{@code {alu,regs}} matches any one of the alternatives;</li>
 * <li>{@code **} matches any number of whole levels, including none:
 * {@code **.carry} matches "carry" and "alu.adder2.carry", and
 * {@code alu.**} matches everything inside alu;</li>
 * <li>{@code \} makes the next character literal.</li>
 * </ul>
 * A pattern always matches a whole path, never part of one. As in
 * .gitignore, a pattern with no dot matches a name at any depth
 * ({@code adder*} matches "alu.adder2"), while a pattern with a dot is
 * matched from the top level ({@code alu.*} matches alu's direct
 * subcircuits only).
 *
 * <p>This translator is deliberately self-contained rather than built
 * on java.nio's glob PathMatcher, whose separator is the platform's
 * file separator and so behaves differently on Windows.
 */
public final class SubcircuitGlob {

	/**
	 * Private constructor to keep this class from being instantiated.
	 */
	private SubcircuitGlob() {}

	/**
	 * Translate a glob into a regular expression that matches whole
	 * dotted paths (use {@code matcher(path).matches()}).
	 *
	 * @param glob The glob pattern.
	 *
	 * @return the compiled equivalent.
	 *
	 * @throws IllegalArgumentException if the glob is empty, has an
	 *             unclosed [ or {, an unmatched }, or ends in a lone \.
	 *
	 * @jls.testedby jls.SubcircuitGlobTest
	 */
	public static Pattern compile(String glob) {

		if (glob.isEmpty()) {
			throw new IllegalArgumentException("empty pattern");
		}
		final StringBuilder re = new StringBuilder();

		// no dot: the name may sit at any depth (.gitignore's rule)
		if (glob.indexOf('.') < 0) {
			re.append("(?:.*\\.)?");
		}

		int braces = 0;
		int i = 0;
		while (i < glob.length()) {
			final char c = glob.charAt(i);
			if (c == '*') {
				if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
					// ** followed by a dot also covers zero levels, so
					// **.carry matches a top-level carry
					if (i + 2 < glob.length() && glob.charAt(i + 2) == '.') {
						re.append("(?:.*\\.)?");
						i += 3;
					}
					else {
						re.append(".*");
						i += 2;
					}
				}
				else {
					re.append("[^.]*");
					i += 1;
				}
			}
			else if (c == '?') {
				re.append("[^.]");
				i += 1;
			}
			else if (c == '[') {
				i = appendClass(glob, i, re);
			}
			else if (c == '{') {
				re.append("(?:");
				braces += 1;
				i += 1;
			}
			else if (c == '}') {
				if (braces == 0) {
					throw new IllegalArgumentException(
							"unmatched } at position " + (i + 1));
				}
				re.append(')');
				braces -= 1;
				i += 1;
			}
			else if (c == ',' && braces > 0) {
				re.append('|');
				i += 1;
			}
			else if (c == '\\') {
				if (i + 1 >= glob.length()) {
					throw new IllegalArgumentException("pattern ends in \\");
				}
				appendLiteral(glob.charAt(i + 1), re);
				i += 2;
			}
			else {
				appendLiteral(c, re);
				i += 1;
			}
		}
		if (braces > 0) {
			throw new IllegalArgumentException("unclosed {");
		}
		return Pattern.compile(re.toString());
	} // end of compile method

	/**
	 * Whether a pattern has no glob characters, so it names one dotted
	 * path (or, without a dot, one name at any depth) literally.
	 *
	 * @param pattern The pattern.
	 *
	 * @return true if it contains none of * ? [ ] { } , \.
	 *
	 * @jls.testedby jls.SubcircuitGlobTest
	 */
	public static boolean isLiteral(String pattern) {

		for (int i = 0; i < pattern.length(); i += 1) {
			if ("*?[]{},\\".indexOf(pattern.charAt(i)) >= 0) {
				return false;
			}
		}
		return true;
	} // end of isLiteral method

	/**
	 * Translate one [...] character class, which never matches the dot
	 * that separates levels.
	 *
	 * @param glob The glob pattern.
	 * @param open The position of the opening [.
	 * @param re Where to append the regular expression.
	 *
	 * @return the position just past the closing ].
	 */
	private static int appendClass(String glob, int open, StringBuilder re) {

		int i = open + 1;
		final boolean negated = i < glob.length()
				&& (glob.charAt(i) == '!' || glob.charAt(i) == '^');
		if (negated) {
			i += 1;
		}
		final StringBuilder members = new StringBuilder();
		// a ] right after [ or [! is a member, as in shells
		boolean first = true;
		while (i < glob.length() && (glob.charAt(i) != ']' || first)) {
			final char c = glob.charAt(i);
			if (c == '-' && !first && i + 1 < glob.length()
					&& glob.charAt(i + 1) != ']') {
				members.append('-');
			}
			else {
				appendLiteral(c, members);
			}
			first = false;
			i += 1;
		}
		if (i >= glob.length()) {
			throw new IllegalArgumentException(
					"unclosed [ at position " + (open + 1));
		}
		if (negated) {
			re.append("[^.").append(members).append(']');
		}
		else {
			re.append("(?:(?!\\.)[").append(members).append("])");
		}
		return i + 1;
	} // end of appendClass method

	/**
	 * Append one character so the regular expression matches it
	 * literally.
	 *
	 * @param c The character.
	 * @param re Where to append it.
	 */
	private static void appendLiteral(char c, StringBuilder re) {

		if (Character.isLetterOrDigit(c) || c == '_') {
			re.append(c);
		}
		else {
			re.append('\\').append(c);
		}
	} // end of appendLiteral method
}
