package jls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The glob syntax for dotted subcircuit paths: * and ? stay within one
 * name, ** spans levels, classes and braces work as in shells, and a
 * pattern without a dot matches a name at any depth.
 */
class SubcircuitGlobTest {

	private static final List<String> PATHS = List.of(
			"alu", "alu.adder2", "alu.adder10", "alu.adder2.carry",
			"alu.shifter", "regs", "carry", "fpu.alu");

	/** The paths the glob matches, in PATHS order. */
	private static List<String> matching(String glob) {
		final Pattern p = SubcircuitGlob.compile(glob);
		return PATHS.stream().filter(path -> p.matcher(path).matches())
				.toList();
	}

	@Test
	void aPlainNameMatchesThatNameAtAnyDepth() {
		assertEquals(List.of("alu.adder2.carry", "carry"), matching("carry"));
		assertEquals(List.of("alu", "fpu.alu"), matching("alu"));
	}

	@Test
	void aPlainNameMatchesWholeNamesOnly() {
		assertEquals(List.of(), matching("adder"));
		assertEquals(List.of(), matching("lu"));
	}

	@Test
	void starStaysWithinOneName() {
		assertEquals(List.of("alu.adder2", "alu.adder10"), matching("adder*"));
		assertEquals(List.of("alu.adder2", "alu.adder10", "alu.shifter"),
				matching("alu.*"));
		assertEquals(List.of("alu.adder2.carry"), matching("alu.*.*"));
	}

	@Test
	void aDottedPatternIsAnchoredAtTheTop() {
		assertEquals(List.of("alu.shifter"), matching("alu.shifter"));
		assertEquals(List.of(), matching("adder2.carry"));
	}

	@Test
	void doubleStarSpansLevels() {
		assertEquals(List.of("alu.adder2", "alu.adder10", "alu.adder2.carry",
				"alu.shifter"), matching("alu.**"));
		// **. also covers zero levels
		assertEquals(List.of("alu.adder2.carry", "carry"),
				matching("**.carry"));
		assertEquals(List.of("alu.adder2.carry"), matching("alu.**.carry"));
		assertEquals(PATHS, matching("**"));
	}

	@Test
	void questionMarkMatchesOneCharacterButNotADot() {
		assertEquals(List.of("alu.adder2"), matching("adder?"));
		assertEquals(List.of(), matching("alu?adder2"));
	}

	@Test
	void characterClassesMatchOneCharacterButNotADot() {
		assertEquals(List.of("alu.adder2"), matching("adder[0-9]"));
		assertEquals(List.of("alu.adder2"), matching("adder[2]"));
		assertEquals(List.of(), matching("adder[!0-9]"));
		assertEquals(List.of("alu.adder2"), matching("adder[^1]"));
		assertEquals(List.of(), matching("alu[.]adder2"));
		assertEquals(List.of(), matching("alu[!x]adder2"));
	}

	@Test
	void bracesMatchAnyAlternative() {
		assertEquals(List.of("alu", "regs", "fpu.alu"), matching("{alu,regs}"));
		assertEquals(List.of("alu.adder2", "alu.shifter"),
				matching("alu.{adder2,shift*}"));
		assertEquals(List.of("alu.adder2", "alu.adder10"),
				matching("adder{2,1{0,1}}"));
	}

	@Test
	void backslashMakesTheNextCharacterLiteral() {
		assertEquals(List.of(), matching("alu\\*"));
		assertEquals(List.of("alu.shifter"), matching("alu\\.shifter"));
	}

	@Test
	void malformedGlobsAreRejected() {
		for (String bad : new String[] {"", "alu[", "adder[!", "{alu,regs",
				"alu}", "alu\\"}) {
			assertThrows(IllegalArgumentException.class,
					() -> SubcircuitGlob.compile(bad), bad);
		}
	}
}
