package jls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Scanner;

import org.junit.jupiter.api.Test;

/**
 * Which levels -circuit / -list-subcircuits patterns select: "." is the
 * top level, a bare "**" is the top level and everything, other globs
 * select subcircuits only, and results come in tree order.
 */
class SubcircuitSelectorTest {

	/** One circuit level (a constant wired to an output pin), unclosed. */
	private static String levelBody(String name) {
		return "CIRCUIT " + name + "\n"
				+ "ELEMENT Constant\n"
				+ " int id 0\n int x 60\n int y 60\n"
				+ " int width 24\n int height 24\n"
				+ " Int value 1\n int base 10\n"
				+ " String orient \"RIGHT\"\n"
				+ "END\n"
				+ "ELEMENT OutputPin\n"
				+ " int id 1\n int x 480\n int y 120\n"
				+ " int width 48\n int height 24\n"
				+ " String name \"out\"\n int bits 1\n int watch 0\n"
				+ " String orient \"RIGHT\"\n"
				+ "END\n"
				+ "ELEMENT WireEnd\n"
				+ " int id 2\n int x 24\n int y 240\n"
				+ " int width 8\n int height 8\n"
				+ " String put \"output\"\n ref attach 0\n ref wire 3\n"
				+ "END\n"
				+ "ELEMENT WireEnd\n"
				+ " int id 3\n int x 36\n int y 240\n"
				+ " int width 8\n int height 8\n"
				+ " String put \"input\"\n ref attach 1\n ref wire 2\n"
				+ "END\n";
	}

	/** A SubCircuit element wrapping the (unclosed) circuit body. */
	private static String subCircuit(int id, String body) {
		return "ELEMENT SubCircuit\n"
				+ " String orient \"RIGHT\"\n"
				+ " int id " + id + "\n int x 300\n int y 300\n"
				+ " int width 60\n int height 40\n"
				+ body
				+ "ENDCIRCUIT\n"
				+ "END\n";
	}

	/** cpu holds regs and alu; alu holds adder10, adder2; adder2 holds carry. */
	private static Circuit cpu() throws Exception {
		final String adder2 = levelBody("adder2") + subCircuit(30, levelBody("carry"));
		final String alu = levelBody("alu")
				+ subCircuit(20, levelBody("adder10"))
				+ subCircuit(21, adder2);
		final String text = levelBody("cpu")
				+ subCircuit(10, levelBody("regs"))
				+ subCircuit(11, alu)
				+ "ENDCIRCUIT\n";
		final Circuit circuit = new Circuit("cpu");
		assertTrue(circuit.load(new Scanner(text)),
				() -> "load failed: " + JLSInfo.loadError);
		assertTrue(circuit.finishLoad(null),
				() -> "finishLoad failed: " + JLSInfo.loadError);
		return circuit;
	}

	/** The paths the patterns select, in order. */
	private static List<String> paths(String... patterns) throws Exception {
		return SubcircuitSelector.of(List.of(patterns)).select(cpu()).stream()
				.map(SubcircuitSelector.Selected::path).toList();
	}

	@Test
	void dotSelectsOnlyTheTopLevel() throws Exception {
		assertEquals(List.of(""), paths("."));
	}

	@Test
	void doubleStarSelectsTheTopLevelAndEverythingInTreeOrder()
			throws Exception {
		assertEquals(List.of("", "alu", "alu.adder2", "alu.adder2.carry",
				"alu.adder10", "regs"), paths("**"));
	}

	@Test
	void otherGlobsNeverSelectTheTopLevel() throws Exception {
		assertEquals(List.of("alu", "alu.adder2", "alu.adder2.carry",
				"alu.adder10", "regs"), paths("*"));
		assertEquals(List.of("alu.adder2", "alu.adder2.carry", "alu.adder10"),
				paths("alu.**"));
	}

	@Test
	void severalPatternsSelectTheirUnionOnceInTreeOrder() throws Exception {
		assertEquals(List.of("", "alu.adder2", "alu.adder10", "regs"),
				paths("regs", "adder*", ".", "alu.adder2"));
	}

	@Test
	void unmatchedListsPatternsThatSelectNothing() throws Exception {
		final SubcircuitSelector s =
				SubcircuitSelector.of(List.of("regs", "x*", ".", "nope"));
		assertEquals(List.of("x*", "nope"), s.unmatched(cpu()));
	}

	@Test
	void problemAcceptsNamesPathsGlobsAndDot() {
		for (String ok : new String[] {".", "alu", "alu.adder2", "adder*",
				"**", "alu.**", "{alu,regs}"}) {
			assertNull(SubcircuitSelector.problem(ok), ok);
		}
	}

	@Test
	void problemRejectsEmptyLevelsBadNamesAndBadGlobs() {
		for (String bad : new String[] {"", "..", "alu.", ".alu", "alu..x",
				"9alu", "alu[", "a*."}) {
			assertNotNull(SubcircuitSelector.problem(bad), bad);
		}
	}
}
