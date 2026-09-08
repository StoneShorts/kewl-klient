package kewl.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import kewl.Plugin;
import kewl.config.Setting;

/**
 * The manager's rules, tested on toy plugins rather than on the real registry, because the rules are
 * about lifecycle and isolation -- and the real plugins' {@code onEnable} builds game state, which
 * needs a game. This file is in package {@code kewl} for the same reason {@code PanelBridgeTest} is:
 * {@link Plugin#drainLater} is package-private and the edits run through it.
 *
 * <p>Everything installs and uninstalls in the same test method, not in a fixture: the manager is a
 * static singleton, and another test class reading {@code KewlKlient.plugins()} in this JVM must
 * never see a toy plugin in the registry because this file left one behind.</p>
 */
public class PluginManagerTest
{
	/** Counts its hooks, and can be told to throw from either of them. */
	private static final class ToyPlugin extends Plugin
	{
		int enables, disables;

		@Override public String name() { return "Toy"; }

		@Override protected void onEnable()
		{
			enables++;
			if (throwOnEnable) throw new IllegalStateException("boom");
		}

		@Override protected void onDisable() { disables++; }

		boolean throwOnEnable;
	}

	/** Records what the profile store would have heard. */
	private static final class RecordingListener implements PluginManager.Listener
	{
		final List<String> events = new ArrayList<>();

		@Override public void onRegistered(Plugin p) { events.add("registered:" + p.name()); }

		@Override public void onUnregistered(Plugin p) { events.add("unregistered:" + p.name()); }

		@Override public void onEnabledChanged(Plugin p, boolean on) { events.add("enabled:" + p.name() + ":" + on); }
	}

	@After
	public void dropManager()
	{
		PluginManager.uninstall();
	}

	@Test
	public void enableAndDisableAreIdempotent()
	{
		ToyPlugin p = new ToyPlugin();
		PluginManager.install(List.of(p), new RecordingListener());
		p.setEnabled(true);
		p.setEnabled(true);
		p.setEnabled(true);
		assertEquals("a switch that is already on must not run onEnable again", 1, p.enables);
		assertTrue(p.isEnabled());

		p.setEnabled(false);
		p.setEnabled(false);
		assertEquals("nor onDisable", 1, p.disables);
		assertFalse(p.isEnabled());
	}

	@Test
	public void aPluginWhoseOnEnableThrowsIsLeftDisabledAndSurfaced()
	{
		ToyPlugin p = new ToyPlugin();
		RecordingListener listener = new RecordingListener();
		PluginManager.install(List.of(p), listener);
		p.throwOnEnable = true;

		boolean ok = PluginManager.instance().setEnabled(p, true);   // must not throw, whatever happens

		assertFalse("a hook that blew up is not a success", ok);
		assertFalse("it did not come up, so it must not be running", p.isEnabled());
		assertNotNull("the failure is surfaced, not swallowed", PluginManager.instance().failure(p));
		assertEquals("the tick loop keeps running and the plugin does not tick: never enabled", 1, p.enables);
		assertTrue("the listener hears the state the plugin is ACTUALLY in (off)",
				listener.events.contains("enabled:Toy:false"));
		assertEquals("a failed plugin is not a registry problem: the other plugins are untouched", 1,
				PluginManager.instance().plugins().size());
	}

	@Test
	public void aPluginWhoseOnDisableThrowsStillStops()
	{
		Plugin broken = new Plugin()
		{
			@Override public String name() { return "Broken Teardown"; }

			@Override protected void onDisable()
			{
				throw new IllegalStateException("cannot stop");
			}
		};
		PluginManager.install(List.of(broken), new RecordingListener());
		broken.setEnabled(true);
		assertTrue("the disable hook threw, but the plugin IS off, and off is the requested state",
				PluginManager.instance().setEnabled(broken, false));
		assertFalse("a plugin that refuses to stop must still stop", broken.isEnabled());
		assertNotNull("and the refusal is surfaced, not swallowed", PluginManager.instance().failure(broken));
	}

	@Test
	public void aRecoveringPluginLosesItsFailure()
	{
		ToyPlugin p = new ToyPlugin();
		PluginManager.install(List.of(p), new RecordingListener());
		p.throwOnEnable = true;
		PluginManager.instance().setEnabled(p, true);
		assertNotNull(PluginManager.instance().failure(p));

		p.throwOnEnable = false;
		PluginManager.instance().setEnabled(p, true);
		assertNull("a plugin that came up on the second try is not a failure any more",
				PluginManager.instance().failure(p));
		assertTrue(p.isEnabled());
	}

	@Test
	public void registrationTellsTheListenerAndRefusesDuplicates()
	{
		ToyPlugin p = new ToyPlugin();
		RecordingListener listener = new RecordingListener();
		PluginManager.install(List.of(p), listener);

		assertTrue(listener.events.contains("registered:Toy"));

		ToyPlugin sameName = new ToyPlugin();
		assertFalse("a second plugin derived from the same name gets the same id, and two plugins "
				+ "with one id would make profile state ambiguous",
				PluginManager.instance().register(sameName));
		assertEquals(1, PluginManager.instance().plugins().size());
	}

	@Test
	public void unregisterDisablesThenRemoves()
	{
		ToyPlugin p = new ToyPlugin();
		RecordingListener listener = new RecordingListener();
		PluginManager.install(List.of(p), listener);
		p.setEnabled(true);
		listener.events.clear();

		assertTrue(PluginManager.instance().unregister(p));
		assertEquals("the removal is announced (the profile store drops the plugin's owner map)",
				List.of("unregistered:Toy"), listener.events);
		assertTrue("a plugin leaving the registry is not left running", p.disables == 1 || !p.isEnabled());
		assertTrue(PluginManager.instance().plugins().isEmpty());
		assertFalse(PluginManager.instance().unregister(p));
	}

	@Test
	public void resetRestoresDeclaredValuesThroughSetting()
	{
		ToyPlugin p = new ToyPlugin();
		PluginManager.install(List.of(p), new RecordingListener());
		Setting s = p.config.bool("flag", "Flag", "", true);
		Setting n = p.config.number("count", "Count", "", 5, 0, 10);
		s.set(false);
		n.set(9);
		Setting.REVISION = 0;

		PluginManager.instance().resetPlugin(p);

		assertTrue("reset goes back to what was declared", s.asBool());
		assertEquals(5, n.asInt());
		assertTrue("a reset that did not move Setting.REVISION would never be republished",
				Setting.REVISION > 0);

		n.set(9);
		Setting.REVISION = 0;
		PluginManager.instance().resetSetting(p, "count");
		assertEquals(5, n.asInt());
		PluginManager.instance().resetSetting(p, "no-such-setting");   // logged, not fatal
	}

	@Test
	public void shutdownStopsEverythingAndRefusesFurtherTransitions()
	{
		ToyPlugin a = new ToyPlugin();
		ToyPlugin b = new ToyPlugin();
		PluginManager.install(List.of(a, b), new RecordingListener());
		a.setEnabled(true);
		b.setEnabled(true);

		PluginManager.instance().shutdown();

		assertFalse(a.isEnabled());
		assertFalse(b.isEnabled());
		assertTrue("shutdown empties the registry", PluginManager.instance().plugins().isEmpty());
		assertFalse("a dead manager cannot be brought back by a stray toggle",
				PluginManager.instance().setEnabled(a, true));
	}
}
