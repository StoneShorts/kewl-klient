package kewl.plugins.autologin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The credentials file reader: presence flags, raw-line parsing, and that no value ever appears in
 * anything it prints. Uses a temp directory, never the real ~/.kewlklient.
 */
public class CredentialsTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final String USER = "tester@example.org";
	private static final String PASS = "Qq7#with\\back\\slash and spaces";

	/** A sink that only records counts, standing in for LoginSequence. */
	private static final class Counting implements InputSink {
		int chars;
		@Override public boolean postChar(int c) { chars++; return true; }
		@Override public boolean postKey(int vk, boolean down) { return true; }
		@Override public boolean postMouse(int x, int y, int action) { return true; }
	}

	private Path write(String... lines) throws IOException
	{
		Path dir = folder.getRoot().toPath();
		Files.write(dir.resolve(Credentials.FILE), List.of(lines), StandardCharsets.UTF_8);
		return dir;
	}

	@Test
	public void missingFileReportsAbsentAndEmpty()
	{
		Credentials c = Credentials.load(folder.getRoot().toPath());
		assertFalse(c.filePresent());
		assertFalse(c.usernameSet());
		assertFalse(c.passwordSet());
		assertTrue(c.describe().contains("missing"));
		assertEquals(Credentials.Source.FILE, c.source());
	}

	/**
	 * A sequence parked exactly where the plugin arms one: the login screen is up and the (zero
	 * length) settle is over, so it is asking for a script. Since review 2026-09-06 {@code arm()}
	 * refuses from anywhere else, so that the password can never be queued in a phase whose
	 * {@code step} would not drain it.
	 */
	private static LoginSequence sequence(Counting sink)
	{
		LoginSequence seq = new LoginSequence(sink,
				// usernameRemembered=false: these tests count the username's characters, so it must be typed.
				new LoginSequence.Settings(10, 0, 0, 1000, 1, 1, false, 0, true, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0));
		seq.step(1, 10);        // the login screen appears
		seq.step(2, 10);        // the settle is over: the plugin is asked for a script
		assertTrue("the sequence is at the point the plugin arms it", seq.wantsScript());
		return seq;
	}

	@Test
	public void panelValuesWinOverTheFileWhenBothAreSet() throws IOException
	{
		// The file holds one account, the panel another; the panel's is what gets typed, and the
		// file is not even consulted (filePresent stays false).
		Path dir = write("username=" + USER, "password=" + PASS);
		Credentials c = Credentials.resolve("  panel-user ", "panel-pw-123", dir);
		assertEquals(Credentials.Source.PANEL, c.source());
		assertTrue(c.fromPanel());
		assertFalse(c.filePresent());
		assertTrue(c.usernameSet());
		assertTrue(c.passwordSet());
		assertEquals("credentials: panel, username set, password set", c.describe());
		LoginSequence seq = sequence(new Counting());
		c.armInto(seq, 0, 0);
		assertEquals("panel-user".length(), seq.usernameChars());   // trimmed, like the file's values
		assertEquals("panel-pw-123".length(), seq.passwordChars());
	}

	@Test
	public void aHalfFilledPanelFallsBackToTheFile() throws IOException
	{
		Path dir = write("username=" + USER, "password=" + PASS);
		for (String[] panel : new String[][] { { "", "" }, { "panel-user", "" }, { "", "panel-pw" }, { null, null }, { "   ", "panel-pw" } })
		{
			Credentials c = Credentials.resolve(panel[0], panel[1], dir);
			assertEquals(Credentials.Source.FILE, c.source());
			assertFalse(c.fromPanel());
			assertTrue(c.filePresent());
			LoginSequence seq = sequence(new Counting());
			c.armInto(seq, 0, 0);
			assertEquals(USER.length(), seq.usernameChars());
			assertEquals(PASS.length(), seq.passwordChars());
		}
		// No file either: the empty, missing state, reported as such.
		Credentials none = Credentials.resolve("panel-user", "", folder.newFolder().toPath());
		assertEquals(Credentials.Source.FILE, none.source());
		assertFalse(none.filePresent());
		assertFalse(none.passwordSet());
	}

	@Test
	public void nothingPanelCredentialsPrintContainsAValue()
	{
		Credentials c = Credentials.resolve("panel-user", "panel-pw-123", folder.getRoot().toPath());
		for (String s : new String[] { c.describe(), c.toString(), String.valueOf(c.source()) })
		{
			assertFalse(s, s.contains("panel-user"));
			assertFalse(s, s.contains("panel-pw"));
			assertFalse(s, s.contains("123"));
		}
	}

	@Test
	public void presenceFlagsFollowTheValues() throws IOException
	{
		Credentials c = Credentials.load(write("username=" + USER, "password="));
		assertTrue(c.filePresent());
		assertTrue(c.usernameSet());
		assertFalse(c.passwordSet());
		assertEquals("credentials: file, username set, password empty", c.describe());

		c = Credentials.load(write("# a comment", "", "username = " + USER, "password = " + PASS));
		assertTrue(c.usernameSet());
		assertTrue(c.passwordSet());
		assertEquals("credentials: file, username set, password set", c.describe());
	}

	@Test
	public void valuesAreReadVerbatimIncludingBackslashesAndEqualsSigns() throws IOException
	{
		String tricky = "a\\b=c=d ";
		Credentials c = Credentials.load(write("username=" + USER, "password=" + tricky));
		// The only way out is into a sequence's character queue: count the characters it types.
		Counting sink = new Counting();
		LoginSequence seq = sequence(sink);
		c.armInto(seq, 0, 0);
		assertEquals(USER.length(), seq.usernameChars());
		assertEquals("backslashes are not escapes, trailing whitespace is trimmed",
				tricky.strip().length(), seq.passwordChars());
	}

	@Test
	public void byteOrderMarkDoesNotHideTheFirstKey() throws IOException
	{
		Path dir = folder.getRoot().toPath();
		Files.write(dir.resolve(Credentials.FILE), ("\uFEFFusername=" + USER + "\npassword=x\n").getBytes(StandardCharsets.UTF_8));
		Credentials c = Credentials.load(dir);
		assertTrue(c.usernameSet());
		assertTrue(c.passwordSet());
	}

	@Test
	public void nothingItPrintsContainsAValue() throws IOException
	{
		Credentials c = Credentials.load(write("username=" + USER, "password=" + PASS));
		List<String> out = new ArrayList<>();
		out.add(c.describe());
		out.add(c.toString());
		out.add(String.valueOf(c));
		out.add("" + c);
		for (String s : out)
		{
			assertFalse(s, s.contains(USER));
			assertFalse(s, s.contains(PASS));
			assertFalse(s, s.contains("Qq7"));
			assertFalse(s, s.contains("tester"));
		}
	}
}
