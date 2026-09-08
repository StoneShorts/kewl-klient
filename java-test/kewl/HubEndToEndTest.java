package kewl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import kewl.json.Json;
import kewl.plugin.PluginManager;
import kewl.plugin.hub.Hub;

/**
 * One hub install, all the way through: a plugin compiled into a jar by this test, hashed, served
 * from a {@code file:} URL as a manifest entry, downloaded, checksummed, classloaded, registered and
 * then removed again. The pieces have their own tests ({@code kewl.plugin.hub.HubTest}); this one
 * exists because the interesting failures are between them -- and because {@link Plugin#drainLater}
 * is package-private, so the one test that has to run the frame queue lives here next to it.
 */
public class HubEndToEndTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Path dataDir;

	private Path dataDir() throws Exception
	{
		if (dataDir == null) dataDir = folder.newFolder("data").toPath();
		return dataDir;
	}

	@After
	public void teardown()
	{
		Hub.uninstall();
		PluginManager.uninstall();
		System.clearProperty("kewl.hub.url");
	}

	@Test
	public void anInstallGoesFromManifestToRegisteredPluginAndBackOutAgain() throws Exception
	{
		Path jar = jarWith("hubtest", "HubTestPlugin", "public class HubTestPlugin extends kewl.Plugin {\n"
				+ "  @Override public String name() { return \"Hub Test Plugin\"; }\n"
				+ "}\n");
		String sha = sha256(jar);

		PluginManager.install(List.of(), null);
		Hub.install(PluginManager.instance(), dataDir());
		Hub hub = Hub.instance();
		System.setProperty("kewl.hub.url", manifestUrl(jar, sha));
		hub.refresh();
		await(hub);
		assertEquals("the manifest was read", Hub.State.READY, hub.state());
		assertEquals(1, hub.entries().size());
		assertEquals("nothing is installed yet", 0,
				hub.flagsOf(hub.entries().get(0)) & Hub.FLAG_INSTALLED);

		hub.install("hubtestplugin");
		await(hub, h -> h.isInstalled("hubtestplugin"));
		Plugin.drainLater();                            // what the frame loop does at the top of a tick

		Plugin installed = hub.installedPlugin("hubtestplugin");
		assertNotNull("the plugin came up", installed);
		assertTrue("it is in the live registry", PluginManager.instance().plugins().contains(installed));
		assertEquals("and the manifest's metadata rode along", "Hub Test Plugin", installed.name());
		assertTrue("the installed flag is set",
				(hub.flagsOf(hub.entries().get(0)) & Hub.FLAG_INSTALLED) != 0);
		assertTrue("the jar landed in the external plugins dir",
				Files.exists(dataDir().resolve("external").resolve("hubtestplugin").resolve("1.0.0.jar")));
		assertTrue("and the install is recorded, so it comes back next session",
				Files.exists(dataDir().resolve("hub").resolve("installed.json")));

		hub.remove("hubtestplugin");
		await(hub, h -> !h.isInstalled("hubtestplugin"));
		Plugin.drainLater();
		assertNull("removal unregisters the plugin", hub.installedPlugin("hubtestplugin"));
		// The record leaves the hub's map before the worker gets to unlinking the files, so the
		// directory has its own wait rather than riding on the one above.
		Path pluginDir = dataDir().resolve("external").resolve("hubtestplugin");
		long deadline = System.currentTimeMillis() + 10_000;
		while (Files.exists(pluginDir) && System.currentTimeMillis() < deadline) Thread.sleep(20);
		assertFalse("and the jar is gone from disk, not just from the list", Files.exists(pluginDir));
	}

	@Test
	public void anArtifactThatFailsItsChecksumIsNotInstalled() throws Exception
	{
		Path jar = jarWith("hubtest", "HubTestPlugin", "public class HubTestPlugin extends kewl.Plugin {\n"
				+ "  @Override public String name() { return \"Hub Test Plugin\"; }\n"
				+ "}\n");
		String wrongSha = "0".repeat(64);

		PluginManager.install(List.of(), null);
		Hub.install(PluginManager.instance(), dataDir());
		Hub hub = Hub.instance();
		System.setProperty("kewl.hub.url", manifestUrl(jar, wrongSha));
		hub.refresh();
		await(hub);
		hub.install("hubtestplugin");

		long deadline = System.currentTimeMillis() + 10_000;
		while (hub.state() != Hub.State.ERROR && System.currentTimeMillis() < deadline) Thread.sleep(20);
		assertEquals(Hub.State.ERROR, hub.state());
		assertNull("a jar that did not match its manifest hash is never loaded",
				hub.installedPlugin("hubtestplugin"));
		assertTrue("the message says why", hub.error().contains("install"));
		assertFalse("and nothing was recorded as installed",
				Files.exists(dataDir().resolve("external").resolve("hubtestplugin")));
	}

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
		Path file = Files.createTempFile("kewl-hub-manifest", ".json");
		Files.writeString(file, Json.write(Map.of("plugins", List.of(entry))), StandardCharsets.UTF_8);
		return file.toUri().toString();
	}

	/** Compiles one source file against this classpath and zips the result into a jar. */
	private Path jarWith(String pkg, String className, String body)
	{
		try
		{
			Path dir = dataDir().resolve("build");
			Path classes = Files.createDirectories(dir.resolve("classes"));
			Path src = classes.resolve(className + ".java");
			Files.writeString(src, "package " + pkg + ";\n" + body, StandardCharsets.UTF_8);

			JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
			assumeTrue("these tests need a JDK, not just a JRE", compiler != null);
			String classpath = Path.of(
					Plugin.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
			int rc = compiler.run(null, null, null, "-d", classes.toString(), "-classpath", classpath,
					src.toString());
			assumeTrue("the test plugin failed to compile", rc == 0);

			Path jar = dir.resolve("plugin.jar");
			try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar)))
			{
				Files.walk(classes).filter(Files::isRegularFile)
						.filter(p -> p.toString().endsWith(".class"))
						.forEach(p ->
						{
							try
							{
								out.putNextEntry(new java.util.jar.JarEntry(
										classes.relativize(p).toString().replace('\\', '/')));
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

	/** The hub works on one background thread; tests wait for it rather than sleeping a fixed time. */
	private static void await(Hub hub) throws Exception
	{
		await(hub, h -> h.state() == Hub.State.READY || h.state() == Hub.State.ERROR);
	}

	private static void await(Hub hub, java.util.function.Predicate<Hub> done) throws Exception
	{
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline)
		{
			if (done.test(hub)) return;
			Thread.sleep(20);
		}
		throw new AssertionError("the hub never reached the expected state (now " + hub.state() + ": "
				+ hub.error() + ")");
	}
}
