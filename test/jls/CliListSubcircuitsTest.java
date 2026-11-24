package jls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests of -list-subcircuits: it prints the circuit's
 * subcircuits as a tree (the dotted paths -circuit accepts) and exits,
 * optionally filtered by a glob matched against each subcircuit's
 * dotted path (the glob syntax itself is covered by SubcircuitGlobTest).
 */
class CliListSubcircuitsTest {

	@TempDir
	Path tmp;

	/** Captured outcome of a child JLS process. */
	private record Result(int exit, String stdout, String stderr) {
	}

	private Result run(String... args) throws Exception {
		final String java = System.getProperty("java.home")
				+ File.separator + "bin" + File.separator + "java";
		final List<String> cmd = new ArrayList<>();
		cmd.add(java);
		cmd.addAll(jls.CoverageAgent.jvmArgs());
		cmd.add("-Djava.awt.headless=true");
		cmd.add("-cp");
		cmd.add(System.getProperty("java.class.path"));
		cmd.add("jls.JLS");
		cmd.addAll(List.of(args));
		final ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(tmp.toFile());
		pb.environment().remove("JAVA_TOOL_OPTIONS");
		pb.redirectError(tmp.resolve("stderr.txt").toFile());
		final Process p = pb.start();
		p.getOutputStream().close();
		final String stdout = drain(p.getInputStream());
		assertTrue(p.waitFor(60, TimeUnit.SECONDS), "CLI run timed out");
		final String stderr = Files.readString(tmp.resolve("stderr.txt"),
				StandardCharsets.UTF_8);
		return new Result(p.exitValue(), stdout.replace("\r\n", "\n"),
				stderr);
	}

	private static String drain(InputStream in) throws Exception {
		return new String(in.readAllBytes(), StandardCharsets.UTF_8);
	}

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

	/**
	 * Write cpu.jls: cpu holds regs and alu (in that file order); alu
	 * holds adder10, adder2 and shifter; adder2 holds carry.
	 */
	private void writeCpu() throws Exception {
		final String adder2 = levelBody("adder2") + subCircuit(30, levelBody("carry"));
		final String alu = levelBody("alu")
				+ subCircuit(20, levelBody("adder10"))
				+ subCircuit(21, adder2)
				+ subCircuit(22, levelBody("shifter"));
		final String text = levelBody("cpu")
				+ subCircuit(10, levelBody("regs"))
				+ subCircuit(11, alu)
				+ "ENDCIRCUIT\n";
		Files.writeString(tmp.resolve("cpu.jls"), text, StandardCharsets.UTF_8);
	}

	@Test
	void printsTheWholeTreeInNaturalOrder() throws Exception {
		writeCpu();
		final Result r = run("-list-subcircuits", "cpu.jls");
		assertEquals(0, r.exit(), r.stderr());
		assertEquals("""
				. (cpu)
				|-- alu
				|   |-- adder2
				|   |   `-- carry
				|   |-- adder10
				|   `-- shifter
				`-- regs
				""", r.stdout());
	}

	@Test
	void aPatternWithoutADotMatchesNamesAtAnyDepth() throws Exception {
		writeCpu();
		final Result r = run("-list-subcircuits", "adder*", "cpu.jls");
		assertEquals(0, r.exit(), r.stderr());
		// the enclosing alu is listed for context; carry, inside a
		// match but not matching itself, is not
		assertEquals("""
				. (cpu)
				`-- alu
				    |-- adder2
				    `-- adder10
				""", r.stdout());
	}

	@Test
	void aPatternWithADotMatchesFromTheTop() throws Exception {
		writeCpu();
		Result r = run("-list-subcircuits", "alu.*", "cpu.jls");
		assertEquals(0, r.exit(), r.stderr());
		assertEquals("""
				. (cpu)
				`-- alu
				    |-- adder2
				    |-- adder10
				    `-- shifter
				""", r.stdout());
		r = run("-list-subcircuits", "**.carry", "cpu.jls");
		assertEquals(0, r.exit(), r.stderr());
		assertEquals("""
				. (cpu)
				`-- alu
				    `-- adder2
				        `-- carry
				""", r.stdout());
	}

	@Test
	void aPatternMatchesWholeNamesOnly() throws Exception {
		writeCpu();
		// "adder" names neither adder2 nor adder10
		final Result r = run("-list-subcircuits", "{regs,adder}", "cpu.jls");
		assertEquals(0, r.exit(), r.stderr());
		assertEquals(". (cpu)\n`-- regs\n", r.stdout());
	}

	@Test
	void attachedPatternAndFlagsAfterTheFileWork() throws Exception {
		writeCpu();
		Result r = run("-list-subcircuitsregs", "cpu.jls");
		assertEquals(0, r.exit(), r.stderr());
		assertEquals(". (cpu)\n`-- regs\n", r.stdout());
		r = run("cpu.jls", "-list-subcircuits", "regs");
		assertEquals(0, r.exit(), r.stderr());
		assertEquals(". (cpu)\n`-- regs\n", r.stdout());
	}

	@Test
	void dotSelectsOnlyTheTopLevel() throws Exception {
		writeCpu();
		final Result r = run("-list-subcircuits", ".", "cpu.jls");
		assertEquals(0, r.exit(), r.stderr());
		assertEquals(". (cpu)\n", r.stdout());
		assertEquals("", r.stderr());
	}

	@Test
	void noMatchPrintsTheRootAndAWarning() throws Exception {
		writeCpu();
		final Result r = run("-list-subcircuits", "nope", "cpu.jls");
		assertEquals(0, r.exit(), r.stderr());
		assertEquals(". (cpu)\n", r.stdout());
		assertTrue(r.stderr().contains("jls: warning: no subcircuit matches nope"),
				r.stderr());
	}

	@Test
	void invalidGlobIsAUsageError() throws Exception {
		writeCpu();
		final Result r = run("-list-subcircuits", "alu[", "cpu.jls");
		assertEquals(2, r.exit(), r.stderr());
		assertTrue(r.stderr().contains("not a valid glob: alu[ (unclosed ["),
				r.stderr());
	}

	@Test
	void missingCircuitFileIsARuntimeError() throws Exception {
		final Result r = run("-list-subcircuits");
		assertEquals(1, r.exit(), r.stderr());
		assertTrue(r.stderr().contains(
				"-list-subcircuits requires a circuit file"), r.stderr());
	}

	@Test
	void combiningWithAnotherModeIsAUsageError() throws Exception {
		writeCpu();
		for (String other : new String[] {"-i", "-b"}) {
			final Result r = run(other, "-list-subcircuits", "cpu.jls");
			assertEquals(2, r.exit(), other + ": " + r.stderr());
			assertTrue(r.stderr().contains(
					"-list-subcircuits cannot be combined with " + other),
					r.stderr());
		}
	}
}
