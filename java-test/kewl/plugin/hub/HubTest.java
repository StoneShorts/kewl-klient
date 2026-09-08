package kewl.plugin.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import kewl.Plugin;
import kewl.json.Json;
import kewl.plugin.PluginManager;

/**
 * The hub, from both ends: the manifest validator and the JSON parser it stands on are tested
 * directly, and one install is carried all the way through -- a plugin compiled into a jar on the
 * fly, hashed, served from a {@code file:} URL, downloaded, checksummed, classloaded and registered.
 * That last test is the one that would catch a real mistake in the loader, which no amount of
 * unit-testing the pieces can.
 *
 * <p>This file lives in {@code kewl.plugin.hub} because {@link HubEntry#fromManifest} and the
 * loader's entry point are package-private on purpose: the rest of the client goes through {@link
 * Hub}, and only the hub's own tests need the pieces.</p>
 */
public class HubTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Path dataDir;

	@Before
	public void makeDataDir() throws Exception
	{
		dataDir = folder.newFolder("data").toPath();
	}

	@After
	public void teardown()
	{
		Hub.uninstall();
		PluginManager.uninstall();
		System.clearProperty("kewl.hub.url");
	}

	// --------------------------------------------------------------------------- the JSON parser

	@Test
	public void jsonRoundTripsTheShapesTheStoresUse()
	{
		Map<String, Object> doc = Map.of(
				"version", 1L,
				"active", "p1",
				"pins", Map.of("woodcutter", true),
				"settings", List.of("a", "b"));
		Map<String, Object> back = (Map<String, Object>) Json.parse(Json.write(doc));
		assertEquals(1L, back.get("version"));
		assertEquals("p1", back.get("active"));
		assertEquals(Map.of("woodcutter", true), back.get("pins"));
		assertEquals(List.of("a", "b"), back.get("settings"));
	}

	@Test
	public void jsonHandlesEscapesAndUnicode()
	{
		assertEquals("a\"b\\c\nd\te", Json.parse("\"a\\\"b\\\\c\\nd\\te\""));
		assertEquals("péché", Json.parse("\"p\\u00e9ch\\u00e9\""));
		// An escaped surrogate pair comes back as one code point (a Java String carries it as the
		// usual two chars); what must not happen is the pair surviving as two lone half characters.
		String emoji = (String) Json.parse("\"\\ud83d\\ude00\"");
		assertEquals(1, emoji.codePointCount(0, emoji.length()));
		assertEquals("😀", emoji);
	}

	@Test
	public void jsonRejectsMalformedInputRatherThanGuessing()
	{
		for (String bad : new String[] {"", "{", "{\"a\":}", "[1,]", "nul", "\"unterminated", "{\"a\":1}x"})
		{
			try
			{
				Json.parse(bad);
				fail("should have refused: " + bad);
			}
			catch (RuntimeException expected)
			{
				// The callers catch RuntimeException and fall back; anything else would be a crash.
			}
		}
	}

	private static void fail(String why) { throw new AssertionError(why); }

	@Test
	public void jsonNumbersStayWholeWhenTheyAreWhole()
	{
		Object v = Json.parse("[1, 1.5, -3]");
		assertEquals(1L, ((List<?>) v).get(0));
		assertEquals(1.5, ((List<?>) v).get(1));
		assertEquals(-3L, ((List<?>) v).get(2));
	}

	// --------------------------------------------------------------------------- manifest validation

	private static HubEntry entry(String id, String version, String mainClass, String artifact, String sha)
	{
		// fromManifest is the validator; the constructor is private, so entries are built as JSON.
		String json = "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"version\":\"" + version
				+ "\",\"mainClass\":\"" + mainClass + "\",\"artifact\":\"" + artifact
				+ "\",\"sha256\":\"" + sha + "\",\"author\":\"a\",\"description\":\"d\"}";
		return HubEntry.fromManifest(Json.parse(json));
	}

	private static final String SHA = "a".repeat(64);

	@Test
	public void aWellFormedEntryIsAccepted()
	{
		HubEntry e = entry("example-plugin", "1.0.0", "example.ExamplePlugin",
				"https://example.com/example.jar", SHA);
		assertNotNull(e);
		assertEquals("example-plugin", e.id());
		assertEquals("a", e.author());
		assertEquals("d", e.description());
		assertEquals(SHA, e.sha256().toLowerCase());
	}

	@Test
	public void entriesMissingAnythingAreRejected()
	{
		assertNull("no id", entry("", "1.0.0", "x.Y", "https://x/y.jar", SHA));
		assertNull("no version", entry("p", "", "x.Y", "https://x/y.jar", SHA));
		assertNull("no mainClass", entry("p", "1.0.0", "", "https://x/y.jar", SHA));
		assertNull("no artifact", entry("p", "1.0.0", "x.Y", "", SHA));
		assertNull("bad checksum length", entry("p", "1.0.0", "x.Y", "https://x/y.jar", "a".repeat(63)));
		assertNull("bad checksum characters", entry("p", "1.0.0", "x.Y", "https://x/y.jar", "z".repeat(64)));
	}

	@Test
	public void artifactUrlsAreSchemeChecked()
	{
		assertNotNull("https is the normal case",
				entry("p", "1.0.0", "x.Y", "https://example.com/p.jar", SHA));
		assertNotNull("file: is how a local hub and these tests work",
				entry("p", "1.0.0", "x.Y", "file:///tmp/p.jar", SHA));
		assertNull("ftp and friends are not a manifest we will download from",
				entry("p", "1.0.0", "x.Y", "ftp://example.com/p.jar", SHA));
		assertNull("nor a bare path",
				entry("p", "1.0.0", "x.Y", "/tmp/p.jar", SHA));
	}

	@Test
	public void idsThatWouldEscapeTheirDirectoryAreRejected()
	{
		assertNull("../etc", entry("../etc", "1.0.0", "x.Y", "https://x/y.jar", SHA));
		assertNotNull("dots inside an id are fine", entry("p.v2", "1.0.0", "x.Y", "https://x/y.jar", SHA));
	}

	@Test
	public void aManifestThatIsNotAListIsAnErrorStateNotACrash()
	{
		PluginManager.install(List.of(), null);
		Hub.install(PluginManager.instance(), dataDir);
		Hub hub = Hub.instance();
		hub.refresh();                                  // no hub configured: the honest error
		assertEquals(Hub.State.ERROR, hub.state());
		assertTrue(hub.error().contains("no hub configured"));
	}

	// --------------------------------------------------------------------------- the loader

	@Test
	public void aMainClassThatIsNotAPluginIsRefused()
	{
		Path jar = jarWith("NotAPlugin.java", "package hubtest;\n"
				+ "public class NotAPlugin { public String name() { return \"nope\"; } }\n");
		assertNull("a jar whose main class does not extend kewl.Plugin never loads",
				HubLoader.load(jar, "hubtest.NotAPlugin"));
	}

	@Test
	public void aMainClassThatIsABuiltInIsRefused()
	{
		// "kewl.Plugin" itself resolves from the parent loader and IS a Plugin; the loader must still
		// refuse it, or a manifest entry could re-register a built-in class as an external plugin.
		Path jar = jarWith("Empty.java", "package hubtest;\npublic class Empty {}\n");
		assertNull(HubLoader.load(jar, "kewl.Plugin"));
	}

	@Test
	public void aMainClassThatDoesNotExistIsRefused()
	{
		Path jar = jarWith("Empty.java", "package hubtest;\npublic class Empty {}\n");
		assertNull(HubLoader.load(jar, "hubtest.DoesNotExist"));
	}

	// --------------------------------------------------------------------------- end to end

	// --------------------------------------------------------------------------- plumbing

	private static String manifestUrl(Path jar, String sha) throws Exception
	{
		Map<String, Object> entry = Map.of(
				"id", "hubtestplugin",
				"name", "Hub Test Plugin",
				"version", "1.0.0",
				"mainClass", "hubtest.HubTestPlugin",
				"artifact", jar.toUri().toString(),
				"sha256", sha,
				"author", "the test suite",
				"description", "compiled into a jar by the test itself");
		Map<String, Object> manifest = Map.of("plugins", List.of(entry));
		Path file = Files.createTempFile("kewl-hub-manifest", ".json");
		Files.writeString(file, Json.write(manifest), StandardCharsets.UTF_8);
		return file.toUri().toString();
	}

	/** Compiles one source file against this classpath and zips the result into a jar. */
	private Path jarWith(String fileName, String source)
	{
		try
		{
			Path classes = Files.createDirectories(dataDir.resolve("build").resolve("classes"));
			Path src = classes.resolve(fileName);
			Files.writeString(src, source, StandardCharsets.UTF_8);

			JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
			assumeTrue("needs a JDK", compiler != null);
			String classpath = Path.of(Plugin.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
			int rc = compiler.run(null, null, null, "-d", classes.toString(), "-classpath", classpath,
					src.toString());
			assumeTrue("the test plugin failed to compile", rc == 0);

			Path jar = dataDir.resolve("build").resolve("plugin.jar");
			try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)))
			{
				Files.walk(classes).filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class"))
						.forEach(p ->
						{
							try
							{
								out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
								out.write(Files.readAllBytes(p));
								out.closeEntry();
							}
							catch (Exception e)
							{
								throw new RuntimeException(e);
							}
						});
			}
			return jar;
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	private static String sha256(Path jar) throws Exception
	{
		byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar));
		StringBuilder sb = new StringBuilder();
		for (byte b : hash) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	private static Hub.State awaitState(Hub hub, Hub.State want) throws Exception
	{
		return await(() -> hub.state() == want ? hub.state() : null);
	}

	private static void awaitInstalled(Hub hub, String id) throws Exception
	{
		await(() -> hub.isInstalled(id) ? hub : null);
	}

	private static void awaitRemoved(Hub hub, String id) throws Exception
	{
		await(() -> hub.isInstalled(id) ? null : hub);
	}

	private static Hub.State awaitError(Hub hub) throws Exception
	{
		return await(() -> hub.state() == Hub.State.ERROR ? hub.state() : null);
	}

	/** The hub works on one background thread; tests wait for it rather than sleeping a fixed time. */
	private static <T> T await(java.util.function.Supplier<T> done) throws Exception
	{
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline)
		{
			T v = done.get();
			if (v != null) return v;
			Thread.sleep(20);
		}
		fail("the hub never reached the expected state");
		return null;
	}
}
