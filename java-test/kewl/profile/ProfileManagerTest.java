package kewl.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import kewl.Plugin;
import kewl.config.Setting;
import kewl.plugin.PluginManager;

/**
 * The profile store: that it persists, that it round-trips, that profiles are isolated from each
 * other, and that a directory it cannot read produces a working client rather than an exception.
 *
 * <p>Each test uses its own temporary data directory and tears both managers down afterwards, because
 * {@code ProfileManager} and {@code PluginManager} are singletons in a JVM that runs every test class
 * in this suite. {@link Setting#setSink} is global too, and an uninstall is what clears it -- a test
 * that forgot would keep writing profile files for the rest of the run.</p>
 */
public class ProfileManagerTest
{
	/** One bool and one int, at defaults the tests can tell apart from the values they set. */
	private static final class ConfiguredPlugin extends Plugin
	{
		@Override public String name() { return "Configured"; }

		Setting flag() { return config.get("flag"); }

		Setting radius() { return config.get("radius"); }
	}

	private static ConfiguredPlugin plugin()
	{
		ConfiguredPlugin p = new ConfiguredPlugin();
		p.config.bool("flag", "Flag", "", false);
		p.config.number("radius", "Radius", "", 5, 0, 20);
		return p;
	}

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
		PluginManager.uninstall();
		ProfileManager.uninstall();
	}

	private void install(ConfiguredPlugin... plugins)
	{
		ProfileManager.install(dataDir);
		PluginManager.install(List.of(plugins), ProfileManager.instance());
	}

	private static String indexJson(Path dataDir) throws Exception
	{
		return Files.readString(dataDir.resolve("profiles").resolve("index.json"), StandardCharsets.UTF_8);
	}

	// --------------------------------------------------------------------------- persistence

	@Test
	public void enabledStateAndSettingsSurviveAReinstall() throws Exception
	{
		ConfiguredPlugin p = plugin();
		install(p);
		PluginManager.instance().setEnabled(p, true);
		p.radius().set(11);
		p.flag().set(true);
		ProfileManager.instance().flush();          // force the debounced write out now

		ConfiguredPlugin fresh = plugin();
		install(fresh);                             // a second session over the same directory

		assertTrue("a switch the profile recorded must come back on", fresh.isEnabled());
		assertEquals("a setting the profile recorded must come back", 11, fresh.radius().asInt());
		assertTrue(fresh.flag().asBool());
	}

	@Test
	public void backToDefaultStopsBeingStoredAtAll() throws Exception
	{
		ConfiguredPlugin p = plugin();
		install(p);
		p.radius().set(11);
		ProfileManager.instance().flush();
		p.radius().set(5);                          // back to the declared value
		ProfileManager.instance().flush();

		String stored = Files.readString(
				dataDir.resolve("profiles").resolve(ProfileManager.instance().active().id())
						.resolve("config.json"), StandardCharsets.UTF_8);
		assertFalse("an override the user reverted should not sit in the file forever",
				stored.contains("radius"));

		ConfiguredPlugin fresh = plugin();
		install(fresh);
		assertEquals("and nothing was lost by not storing it", 5, fresh.radius().asInt());
	}

	@Test
	public void aStoredOffBeatsTheCodeDefault()
	{
		ConfiguredPlugin p = plugin();
		install(p);
		PluginManager.instance().setEnabled(p, true);
		PluginManager.instance().setEnabled(p, false);   // the user turned it off
		ProfileManager.instance().flush();

		ConfiguredPlugin fresh = plugin();
		install(fresh);
		assertFalse("a profile that says off is off, whatever the code default is", fresh.isEnabled());
	}

	// --------------------------------------------------------------------------- CRUD and switching

	@Test
	public void createSwitchRenameDuplicateDelete()
	{
		ConfiguredPlugin p = plugin();
		install(p);
		ProfileManager profiles = ProfileManager.instance();
		assertEquals("a first run has one profile to migrate into", 1, profiles.profiles().size());
		assertEquals("default", profiles.active().name());

		profiles.create("pvm");
		assertEquals(2, profiles.profiles().size());
		assertTrue("the active profile does not move when one is created", profiles.activeIndex() == 0);

		profiles.rename(1, "pvm2");
		assertEquals("pvm2", profiles.profiles().get(1).name());

		profiles.switchTo(1);
		assertEquals(1, profiles.activeIndex());

		Profile dup = profiles.duplicate(1);
		assertNotNull(dup);
		assertEquals("pvm2 copy", dup.name());
		assertEquals("duplicating does not switch to the copy", 1, profiles.activeIndex());

		profiles.switchTo(0);
		profiles.delete(1);
		assertEquals(2, profiles.profiles().size());
		assertEquals("pvm2 copy", profiles.profiles().get(1).name());

		profiles.delete(0);
		profiles.delete(0);
		assertEquals("the last profile cannot be deleted: a client with no profiles has no state",
				1, profiles.profiles().size());
	}

	@Test
	public void profilesAreIsolatedFromEachOther()
	{
		ConfiguredPlugin p = plugin();
		install(p);
		ProfileManager profiles = ProfileManager.instance();

		profiles.create("second");                   // captures how the world is right now...
		profiles.switchTo(1);                        // ...and edits now land in the new profile
		p.radius().set(17);
		PluginManager.instance().setEnabled(p, true);
		ProfileManager.instance().flush();

		profiles.switchTo(0);
		assertEquals("the first profile does not inherit the second's values", 5, p.radius().asInt());
		assertFalse("nor its switch", p.isEnabled());

		profiles.switchTo(1);
		assertEquals("switching back restores the second profile's values", 17, p.radius().asInt());
		assertTrue(p.isEnabled());
	}

	@Test
	public void switchingRestoresSettingsTheNewProfileIsSilentAbout()
	{
		ConfiguredPlugin p = plugin();
		install(p);
		ProfileManager profiles = ProfileManager.instance();

		// "second" is created while radius is still at its default, so it captures nothing about it:
		// it is silent. Edits made after switching to it land in it, never in the first profile.
		profiles.create("second");
		profiles.switchTo(1);
		p.radius().set(17);

		profiles.switchTo(0);
		assertEquals("a profile silent about a setting resets it, it does not keep whatever was live",
				5, p.radius().asInt());
		assertFalse("silent about the switch too", p.isEnabled());

		profiles.switchTo(1);
		assertEquals("and the profile that did record it gets it back", 17, p.radius().asInt());
	}

	@Test
	public void switchingDoesNotFireHooksThatDoNotChange()
	{
		ConfiguredPlugin p = plugin();
		install(p);
		PluginManager.instance().setEnabled(p, true);
		ProfileManager profiles = ProfileManager.instance();

		profiles.create("second");                   // captures enabled=true, radius=5
		int versionBefore = Plugin.enableVersion();  // every real switch bumps it exactly once

		profiles.switchTo(1);
		profiles.switchTo(0);

		assertEquals("an enable state both profiles agree on must not run onEnable twice",
				versionBefore, Plugin.enableVersion());
	}

	// --------------------------------------------------------------------------- pins

	@Test
	public void pinsAreGlobalNotPerProfile() throws Exception
	{
		ConfiguredPlugin p = plugin();
		install(p);
		ProfileManager profiles = ProfileManager.instance();
		profiles.create("second");

		profiles.setPinned(p, true);
		assertTrue(profiles.isPinned(p));
		profiles.switchTo(1);
		assertTrue("a pin follows the user across profiles -- see the pin decision in ProfileManager",
				profiles.isPinned(p));

		ProfileManager.instance().flush();
		assertTrue("pins live in index.json, not inside any profile",
				indexJson(dataDir).contains("\"pins\""));

		profiles.setPinned(p, false);
		assertFalse(profiles.isPinned(p));
		ProfileManager.instance().flush();
		assertFalse(indexJson(dataDir).contains("\"pins\""));
	}

	// --------------------------------------------------------------------------- migration and recovery

	@Test
	public void aFirstRunMigratesToASingleDefaultProfile()
	{
		install(plugin());
		ProfileManager profiles = ProfileManager.instance();
		assertEquals(1, profiles.profiles().size());
		assertEquals("default", profiles.profiles().get(0).name());
		assertEquals(0, profiles.activeIndex());
	}

	@Test
	public void aCorruptIndexFallsBackToAFreshDefaultProfile() throws Exception
	{
		Path profiles = dataDir.resolve("profiles");
		Files.createDirectories(profiles);
		Files.writeString(profiles.resolve("index.json"), "{\"profiles\": [ not json", StandardCharsets.UTF_8);

		install(plugin());

		assertNotNull("the store is up", ProfileManager.instance());
		assertEquals(1, ProfileManager.instance().profiles().size());
		assertEquals("default", ProfileManager.instance().active().name());
		assertTrue("the unreadable file is kept for diagnosis, not deleted",
				Files.exists(profiles.resolve("index.json.bad")));
	}

	@Test
	public void aCorruptProfileConfigCostsOneProfileNotTheClient() throws Exception
	{
		// Write a good index by hand pointing at a profile whose config is garbage.
		Path profiles = dataDir.resolve("profiles");
		Files.createDirectories(profiles.resolve("p1"));
		Files.writeString(profiles.resolve("index.json"),
				"{\"version\":1,\"active\":\"p1\",\"profiles\":[{\"id\":\"p1\",\"name\":\"kept\"}]}",
				StandardCharsets.UTF_8);
		Files.writeString(profiles.resolve("p1").resolve("config.json"), "]]]", StandardCharsets.UTF_8);

		ConfiguredPlugin p = plugin();
		install(p);

		assertEquals("the profile list survives", 1, ProfileManager.instance().profiles().size());
		assertEquals("kept", ProfileManager.instance().active().name());
		assertEquals("a profile with an unreadable config means 'defaults', not 'refuse to start'",
				5, p.radius().asInt());
	}

	@Test
	public void settingChangesReachTheStoreThroughTheDebounce() throws Exception
	{
		ConfiguredPlugin p = plugin();
		install(p);
		Path config = dataDir.resolve("profiles")
				.resolve(ProfileManager.instance().active().id()).resolve("config.json");

		assertFalse(Files.exists(config));
		p.radius().set(13);
		assertTrue("the write is scheduled, not immediate: a slider drag must not write per frame",
				!Files.exists(config));

		// Waiting out the real 750ms debounce would make the suite slow and flaky; flush() is the
		// same code path the debounce runs, minus the timer.
		ProfileManager.instance().flush();
		assertTrue(Files.exists(config));
		assertTrue(Files.readString(config, StandardCharsets.UTF_8).contains("radius"));
	}

	@Test
	public void generationMovesWhenTheModelChangesButNotWhenAValueDoes()
	{
		ConfiguredPlugin p = plugin();
		install(p);
		ProfileManager profiles = ProfileManager.instance();
		long before = profiles.generation();

		p.radius().set(9);
		assertEquals("a setting change is Setting.REVISION's job, not the profile generation's",
				before, profiles.generation());

		profiles.setPinned(p, true);
		assertTrue(profiles.generation() > before);

		long afterPin = profiles.generation();
		profiles.create("another");
		assertTrue(profiles.generation() > afterPin);
	}

	/**
	 * The bridge carries a fixed profile count (MAX_PROFILES in client/bridge.hpp), and the DLL
	 * rejects an over-cap snapshot WHOLE -- a 33rd profile would freeze every tab of the panel, not
	 * just the profiles list. The cap lives with the owner, so create refuses past it rather than
	 * letting the snapshot be rejected.
	 */
	@Test
	public void theThirtyThirdProfileIsRefusedNotCreated()
	{
		install(plugin());
		ProfileManager profiles = ProfileManager.instance();

		for (int i = 1; i < ProfileManager.MAX_PROFILES; i++) profiles.create("p" + i);
		assertEquals(ProfileManager.MAX_PROFILES, profiles.profiles().size());

		long before = profiles.generation();
		assertTrue(profiles.create("the one too many") == null);
		assertEquals("an over-cap create must not change the list",
				ProfileManager.MAX_PROFILES, profiles.profiles().size());
		assertEquals("...nor move the revision the launcher polls", before, profiles.generation());
	}
}
