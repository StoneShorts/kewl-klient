// NPC Indicators and Player Indicators configs, built through the real ConfigManager over a real
// 0xClient Config, then held against the invariants every control panel draws from -- so every
// annotation the two interfaces use is proven supported here, not in-game.
//
// Also the regression test for the `double` branch (RuneLite's borderWidth): before it, a double
// item became a text box and the proxy answered null for a primitive double (an NPE on first read).
package net.runelite.client.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.lang.reflect.Method;

import org.junit.Test;

import net.runelite.client.plugins.npchighlight.NpcIndicatorsConfig;
import net.runelite.client.plugins.playerindicators.HighlightSetting;
import net.runelite.client.plugins.playerindicators.PlayerIndicatorsConfig;
import net.runelite.client.plugins.playerindicators.PlayerNameLocation;

public class IndicatorsConfigTest
{
	@Test
	public void npcIndicatorsDefaultsAndPanel()
	{
		oxclient.config.Config oxcConfig = new oxclient.config.Config();
		NpcIndicatorsConfig config = new ConfigManager(null, oxcConfig).getConfig(NpcIndicatorsConfig.class, oxcConfig);

		assertEquals("double branch: stored as an int slider, read back as a double", 2.0, config.borderWidth(), 0d);
		assertEquals(Color.CYAN, config.highlightColor());
		assertEquals("fill alpha preserved", 20, config.fillColor().getAlpha());
		assertTrue(config.highlightHull());
		assertTrue(config.ignoreDeadNpcs());
		assertEquals("", config.getNpcToHighlight());
		// 0xClient extensions: the sweep mode and its range (2026-09-06). The range is what keeps
		// "Highlight every NPC" from drawing the whole 104x104 scene.
		assertTrue(config.highlightAll());
		assertEquals(20, config.highlightAllRange());

		assertEquals("one 0xClient setting per declared item (none hidden)",
			declaredItems(NpcIndicatorsConfig.class), oxcConfig.all().size());
		oxclient.config.Setting width = oxcConfig.get("borderWidth");
		assertEquals("@Range honoured on the double", 1, width.min());
		assertEquals(8, width.max());
		ConfigPanelInvariants.assertEverySettingIsRenderable(oxcConfig, "NpcIndicatorsConfig");

		// The list setting is a text box the plugin re-parses on ConfigChanged; writing through the
		// Setting is the only write path (the panel's), so this is what a user edit looks like.
		oxcConfig.get("npcToHighlight").set("Goblin, 1234");
		assertEquals("Goblin, 1234", config.getNpcToHighlight());
		width.set(5);
		assertEquals(5.0, config.borderWidth(), 0d);
	}

	@Test
	public void playerIndicatorsDefaultsAndPanel()
	{
		oxclient.config.Config oxcConfig = new oxclient.config.Config();
		PlayerIndicatorsConfig config = new ConfigManager(null, oxcConfig).getConfig(PlayerIndicatorsConfig.class, oxcConfig);

		assertEquals(PlayerNameLocation.ABOVE_HEAD, config.playerNamePosition());
		assertEquals(HighlightSetting.ENABLED, config.highlightOthers());   // 0xClient default: on (2026-09-06)
		assertEquals(HighlightSetting.ENABLED, config.highlightOwnPlayer());
		assertEquals(new Color(0, 184, 212), config.getOwnPlayerColor());
		assertEquals(new Color(255, 0, 0), config.getOthersColor());
		// 0xClient extension: the render-style section this plugin grew when it took over from the worked
		// example oxclient.plugins.PlayerVisuals (2026-09-06). borderWidth is the same double branch NPC
		// Indicators exercises above -- an int slider read back as a double.
		assertTrue(config.highlightHull());
		assertEquals(20, config.fillOpacity());
		assertEquals(2.0, config.borderWidth(), 0d);
		oxclient.config.Setting playerWidth = oxcConfig.get("borderWidth");
		assertEquals("@Range honoured on the double", 1, playerWidth.min());
		assertEquals(8, playerWidth.max());
		playerWidth.set(6);
		assertEquals(6.0, config.borderWidth(), 0d);

		assertEquals(declaredItems(PlayerIndicatorsConfig.class), oxcConfig.all().size());
		ConfigPanelInvariants.assertEverySettingIsRenderable(oxcConfig, "PlayerIndicatorsConfig");

		// Enum options are what the combo shows: the display names, via toString.
		oxclient.config.Setting pos = oxcConfig.get("playerNamePosition");
		assertEquals(4, pos.options().length);
		assertEquals("Above head", String.valueOf(pos.options()[0]));
		pos.set(PlayerNameLocation.MODEL_RIGHT);
		assertEquals(PlayerNameLocation.MODEL_RIGHT, config.playerNamePosition());
	}

	private static int declaredItems(Class<?> iface)
	{
		int n = 0;
		for (Method m : iface.getMethods())
		{
			ConfigItem item = m.getAnnotation(ConfigItem.class);
			if (item != null && !item.hidden())
			{
				n++;
			}
		}
		return n;
	}

}
