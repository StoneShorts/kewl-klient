// What a Setting must look like for the control panel to be able to draw it.
//
// This replaces a reflective call into kewl.ui.Sidebar, the Swing control panel that the ImGui strip
// and the Java2D SidePanel replaced and that was deleted on 2026-09-07. Two tests used to build real
// Swing controls for every declared setting and assert the construction did not throw. The thing
// they were actually guarding is narrower and is checked here directly: a @ConfigItem whose default
// falls outside its own @Range crashed the old JSlider constructor, KewlKlient's catch ate the
// exception, and the user got no control panel at all.
//
// Checking the invariant beats checking a constructor. The old test could only fail through Swing,
// so it proved nothing about the panels that actually ship, and it kept 310 lines of dead UI alive
// to do it. Every panel -- the ImGui strip via PanelBridge, the Java2D SidePanel via ConfigView --
// reads exactly these fields, so a setting that satisfies this is one both can render, and a
// setting that does not is the bug the Swing crash was a symptom of.
//
// The live serialisation path is covered separately and does not belong here: PanelBridgeTest
// decodes a real PanelBridge.snapshot() over the real registry, which packs every Shortest Path
// setting through the same code the launcher parses.
package net.runelite.client.config;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;

import kewl.config.Setting;

final class ConfigPanelInvariants
{
	private ConfigPanelInvariants()
	{
	}

	/**
	 * Assert every setting in {@code config} can be drawn as one panel row.
	 *
	 * @param what names the config in failure messages, so a failure says which interface broke
	 */
	static void assertEverySettingIsRenderable(kewl.config.Config config, String what)
	{
		for (Setting s : config.all())
		{
			String at = what + " setting \"" + s.key() + "\"";
			assertNotNull(at + " has no key", s.key());
			assertTrue(at + " has an empty key", !s.key().isEmpty());
			assertNotNull(at + " has no label", s.label());
			assertNotNull(at + " has no description", s.description());
			assertNotNull(at + " has no kind", s.kind());
			assertNotNull(at + " has a null value", s.value());

			switch (s.kind())
			{
				case INT:
					// The original crash, stated as the invariant it violated. A slider whose value
					// sits outside its own track is unrepresentable in every panel we have.
					assertTrue(at + " has min " + s.min() + " above max " + s.max(),
						s.min() <= s.max());
					assertTrue(at + " has value " + s.asInt() + " outside its range "
							+ s.min() + ".." + s.max(),
						s.asInt() >= s.min() && s.asInt() <= s.max());
					break;
				case ENUM:
					// A drop-down draws the option at the current index; a value that is not one of
					// the options has no index, and the panel would show the wrong row as selected.
					assertNotNull(at + " is an enum with no options", s.options());
					assertTrue(at + " is an enum with no options", s.options().length > 0);
					boolean found = false;
					for (Object o : s.options())
					{
						if (o != null && o.equals(s.value()))
						{
							found = true;
							break;
						}
					}
					assertTrue(at + " has a value that is not one of its options", found);
					break;
				case COLOR:
					assertTrue(at + " is a colour holding " + s.value().getClass().getName(),
						s.value() instanceof Color);
					break;
				case BOOL:
					assertTrue(at + " is a boolean holding " + s.value().getClass().getName(),
						s.value() instanceof Boolean);
					break;
				case TEXT:
				default:
					break;
			}
		}
	}
}
