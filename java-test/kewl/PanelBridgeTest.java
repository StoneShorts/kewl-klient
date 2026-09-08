// The launcher's whole view of the client arrives through PanelBridge.snapshot()'s packed int array,
// parsed by C++ that cannot be unit-tested here -- so the format is verified from this side by a
// reader that walks the array exactly the way the DLL's model copier does and refuses to run off the
// end. If the packer and this reader disagree about anything, a test fails here rather than a panel
// rendering garbage in Wine.
//
// The edit tests pin the one rule the bridge exists for: an edit lands in Setting.set, so the change
// listeners fire and Setting.REVISION moves. An edit that wrote the value field directly would pass
// every visual check and silently do nothing to the plugin.
//
// This file is in package kewl, not kewl.panel, for one reason: Plugin.drainLater() is package-private
// (only the frame loop may run it), and the edits are queued through Plugin.later, so a test that
// wants to see one applied has to stand where the frame loop stands. ConfigDefaultsTest makes the
// same trade for the same reason.
package kewl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import kewl.config.Setting;
import kewl.panel.PanelBridge;

public class PanelBridgeTest
{
	/** Reads the packed snapshot back, the way the DLL copies it into the shared-memory model. */
	private static final class Reader
	{
		final int[] a;
		int p;

		Reader(int[] a) { this.a = a; }

		int u32()
		{
			assertTrue("ran off the end of the snapshot at " + p + " of " + a.length, p < a.length);
			return a[p++];
		}

		/** A length in bytes, then the bytes four to an int, lowest byte first. */
		String str()
		{
			int len = u32();
			assertTrue("string length " + len + " at " + p + " is negative or past the array",
					len >= 0 && p + (len + 3) / 4 <= a.length);
			byte[] b = new byte[len];
			for (int i = 0; i < len; i++)
			{
				b[i] = (byte) (a[p + i / 4] >>> (8 * (i % 4)));
			}
			p += (len + 3) / 4;
			return new String(b, StandardCharsets.UTF_8);
		}
	}

	private static final class Packed
	{
		/** flags is the plugin record's FLAGS word: bit0 has settings, bit1 developer. */
		int enabled, flags, hotkey;
		String name, description, status;
		final List<SettingRecord> settings = new ArrayList<>();
	}

	private static final class SettingRecord
	{
		int kind, valueInt, min, max, enumIndex, optionCount, flags;
		String key, label, description, section, valueText;
		final List<String> options = new ArrayList<>();
	}

	/** The whole model, decoded. Each entry of {@link #plugins} matches KewlKlient.plugins().get(i). */
	private static final class Model
	{
		final List<Packed> plugins = new ArrayList<>();
		final List<Integer> pinned = new ArrayList<>();
		int activeProfileIndex;
		final List<ProfileRecord> profiles = new ArrayList<>();
		int hubState;
		String hubError;
		final List<HubRecord> hub = new ArrayList<>();
		final List<Integer> hubInstalledIdx = new ArrayList<>();

		Packed byName(String name)
		{
			return plugins.stream().filter(p -> p.name.equals(name)).findFirst().orElse(null);
		}
	}

	private static final class ProfileRecord
	{
		String name, id;
	}

	private static final class HubRecord
	{
		String id, name, version, author, description;
		int flags, installedPluginIdx;
	}

	/** Walks a full snapshot into a Model, asserting the documented layout at every step. */
	private static Model decode(int[] snapshot)
	{
		Reader r = new Reader(snapshot);
		assertEquals("magic", PanelBridge.MAGIC, r.u32());
		assertEquals("format", PanelBridge.FORMAT, r.u32());

		Model m = new Model();
		int plugins = r.u32();
		assertTrue("a registry with no plugins cannot be right", plugins > 0);
		for (int i = 0; i < plugins; i++)
		{
			Packed p = new Packed();
			p.enabled = r.u32();
			p.flags = r.u32();
			assertEquals("plugin flags " + p.flags + " carries a bit the layout does not document",
					0, p.flags & ~(PanelBridge.PLUGIN_FLAG_CONFIG | PanelBridge.PLUGIN_FLAG_DEV));
			p.hotkey = r.u32();
			assertTrue("hotkey " + p.hotkey + " is outside the documented -1..7",
					p.hotkey >= -1 && p.hotkey <= 7);
			p.name = r.str();
			p.description = r.str();
			p.status = r.str();

			int settings = r.u32();
			for (int j = 0; j < settings; j++)
			{
				SettingRecord s = new SettingRecord();
				s.kind = r.u32();
				assertTrue("kind " + s.kind + " is not one of the documented 0..5",
						s.kind >= 0 && s.kind <= 5);
				s.valueInt = r.u32();
				s.min = r.u32();
				s.max = r.u32();
				s.enumIndex = r.u32();
				s.optionCount = r.u32();
				assertTrue("optionCount " + s.optionCount + " breaks the 8 cap", s.optionCount <= 8);
				s.flags = r.u32();
				s.key = r.str();
				s.label = r.str();
				s.description = r.str();
				s.section = r.str();
				s.valueText = r.str();
				for (int k = 0; k < s.optionCount; k++) s.options.add(r.str());
				p.settings.add(s);
			}
			m.plugins.add(p);
		}

		// -- the v2 tail. Same rule as above: the reader walks it exactly the way the DLL's model
		//    copier does, and running off the end here means the C++ side would run off the end too.
		for (int i = 0; i < plugins; i++) m.pinned.add(r.u32());

		m.activeProfileIndex = r.u32();
		assertTrue("activeProfileIndex " + m.activeProfileIndex + " is neither -1 nor an index",
				m.activeProfileIndex >= -1 && m.activeProfileIndex < 64);
		int profileCount = r.u32();
		for (int i = 0; i < profileCount; i++)
		{
			ProfileRecord pr = new ProfileRecord();
			pr.name = r.str();          // name first, then id: the contract's order, on both sides
			pr.id = r.str();
			m.profiles.add(pr);
		}

		m.hubState = r.u32();
		assertTrue("hubState " + m.hubState + " is not one of 0..3", m.hubState >= 0 && m.hubState <= 3);
		m.hubError = r.str();
		if (m.hubState != 2) assertEquals("hubError must be empty unless the hub is in error",
				"", m.hubError);
		int hubCount = r.u32();
		for (int i = 0; i < hubCount; i++)
		{
			HubRecord h = new HubRecord();
			h.id = r.str();
			h.name = r.str();
			h.version = r.str();
			h.author = r.str();
			h.description = r.str();
			h.flags = r.u32();
			h.installedPluginIdx = r.u32();
			m.hub.add(h);
			m.hubInstalledIdx.add(h.installedPluginIdx);
		}

		assertEquals("trailing ints after the last setting: the packer and the documented format "
				+ "disagree, and the C++ side is about to copy nonsense", r.a.length, r.p);
		return m;
	}

	@Test
	public void snapshotDecodesToExactlyTheRealRegistry()
	{
		Model m = decode(PanelBridge.snapshot());
		List<Plugin> real = KewlKlient.plugins();
		assertEquals(real.size(), m.plugins.size());

		for (int i = 0; i < real.size(); i++)
		{
			Plugin p = real.get(i);
			Packed packed = m.plugins.get(i);
			assertEquals("plugin " + i + " name", p.name(), packed.name);
			assertEquals("plugin " + i + " description", p.description(), packed.description);
			assertEquals("plugin " + i + " status", p.status(), packed.status);
			assertEquals("plugin " + i + " enabled", p.isEnabled() ? 1 : 0, packed.enabled);
			// bit0 is the field's original hasConfig, unchanged -- the developer bit rode into the
			// spare bits of this same int rather than costing a FORMAT bump, so a dropped or shifted
			// bit here shows up as a gear that stops opening rather than as a parse failure.
			assertEquals("plugin " + i + " has-settings flag",
					p.config.isEmpty() ? 0 : PanelBridge.PLUGIN_FLAG_CONFIG,
					packed.flags & PanelBridge.PLUGIN_FLAG_CONFIG);
			assertEquals("plugin " + i + " developer flag",
					p.developer() ? PanelBridge.PLUGIN_FLAG_DEV : 0,
					packed.flags & PanelBridge.PLUGIN_FLAG_DEV);
			assertEquals("plugin " + i + " hotkey", p.hotkey(), packed.hotkey);

			// Every declared setting is there, in declaration order, with the kind the panel draws.
			Map<String, SettingRecord> byKey = new LinkedHashMap<>();
			for (SettingRecord s : packed.settings) byKey.put(s.key, s);
			assertEquals("plugin " + p.name() + " setting count",
					p.config.all().size(), packed.settings.size());
			for (Setting s : p.config.all())
			{
				SettingRecord rec = byKey.get(s.key());
				assertTrue("plugin " + p.name() + " is missing setting " + s.key(), rec != null);
				assertEquals("plugin " + p.name() + "/" + s.key() + " label", s.label(), rec.label);
				assertEquals("plugin " + p.name() + "/" + s.key() + " kind",
						documentedKind(p, s), rec.kind);
				assertEquals("plugin " + p.name() + "/" + s.key() + " min", s.min(), rec.min);
				assertEquals("plugin " + p.name() + "/" + s.key() + " max", s.max(), rec.max);
				// flags bit2 is the secret marker, and it is the only thing that separates a password
				// field from a text field on the launcher's side -- a dropped bit draws the password.
				assertEquals("plugin " + p.name() + "/" + s.key() + " secret flag",
						s.secret() ? PanelBridge.FLAG_SECRET : 0, rec.flags & PanelBridge.FLAG_SECRET);
				assertEquals("plugin " + p.name() + "/" + s.key() + " keybind flag",
						kewl.ui.RlConfigMeta.of(p).isKeybind(s.key()) ? 1 : 0, rec.flags & 1);
			}
		}
	}

	@Test
	public void theDeveloperScaffoldingIsMarkedAndNothingElseIs()
	{
		// The four the panel groups under "Developer": two shim smoke tests, and the two kewl box
		// drawers the RuneLite ports (NPC Indicators / Player Indicators) replaced as the real
		// visuals. They are marked, not deleted -- so the model must still carry every one of them,
		// with the bit set, and the plugins someone actually runs must NOT have it.
		Model m = decode(PanelBridge.snapshot());
		List<String> developer = List.of("Test Rlite", "Test Actors", "NPC visuals", "Player visuals");
		for (String name : developer)
		{
			Packed packed = m.byName(name);
			assertTrue("the registry no longer carries \"" + name + "\"", packed != null);
			assertEquals(name + " must be marked developer scaffolding",
					PanelBridge.PLUGIN_FLAG_DEV, packed.flags & PanelBridge.PLUGIN_FLAG_DEV);
		}
		for (Packed packed : m.plugins)
		{
			if (developer.contains(packed.name)) continue;
			assertEquals(packed.name + " is not developer scaffolding and must not be flagged as it",
					0, packed.flags & PanelBridge.PLUGIN_FLAG_DEV);
		}
		// The mark is a grouping, not a deletion: the record count still matches the registry, so the
		// launcher's positional edit indices are untouched by it.
		assertEquals("the developer mark must not change the record count",
				KewlKlient.plugins().size(), m.plugins.size());
		assertTrue("every plugin cannot be developer scaffolding",
				m.plugins.size() > developer.size());
	}

	@Test
	public void secretTextSettingsCarryTheFlagAndStillTheValue()
	{
		// The real registry carries one: AutoLogin's password. It must arrive as kind 5 (text) with
		// bit2 set, and with its value in valueText -- masking is the launcher's job, and a bridge
		// that blanked the value would leave the field unable to round-trip an edit.
		Setting secret = null;
		for (Plugin p : KewlKlient.plugins())
		{
			for (Setting s : p.config.all()) if (s.secret()) secret = s;
		}
		assertTrue("no secret setting in the registry -- AutoLogin should declare its password as one",
				secret != null);
		assertEquals(Setting.Kind.TEXT, secret.kind());
		Object was = secret.value();
		try
		{
			secret.set("bridge-test-value");
			Model m = decode(PanelBridge.snapshot());
			Packed owner = m.byName(pluginOf(secret).name());
			SettingRecord rec = null;
			for (SettingRecord r : owner.settings) if (r.key.equals(secret.key())) rec = r;
			assertTrue(rec != null);
			assertEquals(5, rec.kind);
			assertEquals(PanelBridge.FLAG_SECRET, rec.flags & PanelBridge.FLAG_SECRET);
			assertEquals("bridge-test-value", rec.valueText);
			// And nothing a non-secret text setting carries has the bit.
			for (Packed p : m.plugins)
			{
				for (SettingRecord r : p.settings)
				{
					if (r.kind == 5 && !(p == owner && r.key.equals(secret.key())))
						assertEquals(p.name + "/" + r.key + " must not be flagged secret",
								0, r.flags & PanelBridge.FLAG_SECRET);
				}
			}
		}
		finally
		{
			secret.set(was);
			Plugin.drainLater();
		}
	}

	/** The kind mapping, restated here so a change to it fails a test on both sides of the bridge. */
	private static int documentedKind(Plugin p, Setting s)
	{
		boolean keybind = kewl.ui.RlConfigMeta.of(p).isKeybind(s.key());
		return switch (s.kind())
		{
			case BOOL -> 0;
			case INT -> keybind ? 3 : 1;
			case ENUM -> 2;
			case COLOR -> 4;
			case TEXT -> 5;
		};
	}

	@Test
	public void enumOptionsAndCurrentIndexComeThrough()
	{
		Model m = decode(PanelBridge.snapshot());
		Setting enumSetting = find(Setting.Kind.ENUM);
		assertTrue("no enum setting is declared anywhere in the registry -- Shortest Path should "
				+ "declare some through the config proxy", enumSetting != null);

		// The wrapped RuneLite plugin's enum options are declared lazily by the proxy, so they may not
		// exist until its adapter has been built; if none made it into the model, that is a finding
		// about the proxy, not the bridge, and is asserted rather than assumed.
		Packed owner = m.byName(pluginOf(enumSetting).name());
		SettingRecord rec = owner.settings.stream()
				.filter(s -> s.key.equals(enumSetting.key())).findFirst().orElse(null);
		assertTrue(rec != null);
		// The contract caps options at 8 per setting (the C++ model region's fixed option slots). An
		// enum with more still works through setEnum(index), but the launcher can only ever show the
		// first 8 -- if that ever matters, the cap is the thing to raise, on both sides at once.
		assertEquals(Math.min(8, enumSetting.options().length), rec.optionCount);
		for (int i = 0; i < rec.optionCount; i++)
		{
			assertEquals(String.valueOf(enumSetting.options()[i]), rec.options.get(i));
		}
		assertEquals(rec.options.get(indexOf(enumSetting)), rec.valueText);
		assertEquals(indexOf(enumSetting), rec.enumIndex);
		assertEquals(indexOf(enumSetting), rec.valueInt);
	}

	@Test
	public void editsGoThroughSettingSetSoListenersFire()
	{
		Setting s = find(Setting.Kind.BOOL);
		assertTrue("no bool setting in the registry", s != null);
		AtomicInteger fired = new AtomicInteger();
		s.onChange(fired::incrementAndGet);
		long before = Setting.REVISION;
		boolean was = s.asBool();

		PanelBridge.setBool(KewlKlient.plugins().indexOf(pluginOf(s)), s.key(), !was);
		assertEquals("the edit must run on the frame thread (Plugin.later), never on the caller's "
				+ "-- the DLL's message thread has no business inside plugin state", 0, fired.get());
		Plugin.drainLater();                                   // what the frame loop does at its top

		assertEquals(!was, s.asBool());
		assertEquals("one edit, one listener fire", 1, fired.get());
		assertTrue("Setting.REVISION must move when an edit lands, or the launcher never republishes",
				Setting.REVISION != before);
		s.set(was);                                            // leave the registry as we found it
		Plugin.drainLater();
	}

	@Test
	public void intEditsAreClampedBySettingNotByTheBridge()
	{
		Setting s = find(Setting.Kind.INT);
		assertTrue("no int setting in the registry", s != null);
		int was = s.asInt();
		PanelBridge.setInt(KewlKlient.plugins().indexOf(pluginOf(s)), s.key(), s.max() + 1000);
		Plugin.drainLater();
		assertEquals("out-of-range values come back clamped to the declared max",
				s.max(), s.asInt());
		s.set(was);
		Plugin.drainLater();
	}

	@Test
	public void enumEditOutOfRangeIsDroppedNotWrapped()
	{
		Setting s = find(Setting.Kind.ENUM);
		if (s == null) return;                                 // covered by the proxy's own tests
		Object was = s.value();
		PanelBridge.setEnum(KewlKlient.plugins().indexOf(pluginOf(s)), s.key(), 99);
		Plugin.drainLater();
		assertEquals("a bogus index must leave the value exactly as it was", was, s.value());
	}

	@Test
	public void unknownPluginOrKeyIsIgnoredNotFatal()
	{
		long before = Setting.REVISION;
		PanelBridge.setBool(99_999, "nope", true);
		PanelBridge.setInt(0, "bridge-test-no-such-setting", 7);
		PanelBridge.setEnum(0, "bridge-test-no-such-setting", 0);
		PanelBridge.setText(0, "bridge-test-no-such-setting", "x");
		Plugin.drainLater();
		assertEquals("a bad edit record must not touch any setting", before, Setting.REVISION);
	}

	@Test
	public void enabledKeyFlipsThePluginNotASetting()
	{
		Plugin p = KewlKlient.plugins().get(0);
		boolean was = p.isEnabled();
		long before = Plugin.enableVersion();
		try
		{
			PanelBridge.setBool(KewlKlient.plugins().indexOf(p), PanelBridge.ENABLE_KEY, !was);
			Plugin.drainLater();
			assertEquals("ENABLE_KEY is plugin state, not a Setting", !was, p.isEnabled());
			assertEquals("a flip bumps enableVersion, which is what the launcher republishes on",
					before + 1, Plugin.enableVersion());
		}
		finally
		{
			p.setEnabled(was);
			Plugin.drainLater();
		}
	}

	@Test
	public void modelRevisionMovesForEditsAndForEnables()
	{
		Setting s = find(Setting.Kind.INT);
		assertTrue("no int setting in the registry", s != null);
		long before = PanelBridge.modelRevision();
		PanelBridge.setInt(KewlKlient.plugins().indexOf(pluginOf(s)), s.key(), s.asInt());
		Plugin.drainLater();
		assertTrue("a setting edit changes the revision", PanelBridge.modelRevision() != before);

		Plugin p = KewlKlient.plugins().get(0);
		long afterEdit = PanelBridge.modelRevision();
		boolean was = p.isEnabled();
		p.setEnabled(!was);
		assertTrue("an enable/disable changes the revision too",
				PanelBridge.modelRevision() != afterEdit);
		p.setEnabled(was);
	}

	@Test
	public void modelRevisionIsStableWhenNothingChanged()
	{
		// The DLL polls this every frame to decide whether to republish; if it wobbled on its own, the
		// bridge would copy the model thirty times a second for nothing.
		assertEquals(PanelBridge.modelRevision(), PanelBridge.modelRevision());
	}

	@Test
	public void debugLinesCoversWhatTheJavaDebugTabShows()
	{
		List<String> lines = PanelBridge.debugLines();
		assertTrue("at minimum: ready, you, hp/run, npcs, inventory, pathcheck", lines.size() >= 6);
		assertTrue(lines.get(0).startsWith("ready:"));
		assertTrue("pathcheck must be the last line",
				lines.get(lines.size() - 1).startsWith("pathcheck:"));
	}

	// -- helpers. Settings are found by walking the real registry rather than by naming a plugin, so
	//    the tests survive plugins being added, removed or renamed.

	private static Setting find(Setting.Kind kind)
	{
		for (Plugin p : KewlKlient.plugins())
		{
			for (Setting s : p.config.all())
			{
				if (s.kind() == kind) return s;
			}
		}
		return null;
	}

	private static Plugin pluginOf(Setting target)
	{
		for (Plugin p : KewlKlient.plugins())
		{
			for (Setting s : p.config.all())
			{
				if (s == target) return p;
			}
		}
		throw new IllegalStateException("setting does not belong to any plugin");
	}

	private static int indexOf(Setting s)
	{
		Object v = s.value();
		Object[] options = s.options();
		for (int i = 0; i < options.length; i++)
		{
			if (options[i].equals(v)) return i;
		}
		return 0;                                              // ConfigView's rule: unknown reads first
	}
}
