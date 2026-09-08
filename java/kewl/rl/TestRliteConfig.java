// Config for TestRlite. Declared exactly the way a Hub plugin declares config, so this exercises the
// whole chain: annotation -> kewl Setting -> control panel -> ConfigChanged back to the plugin.
package kewl.rl;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("testrlite")
public interface TestRliteConfig extends Config
{
	@ConfigItem(
		keyName = "showCounter",
		name = "Show tick counter",
		description = "Draw the panel counting game ticks"
	)
	default boolean showCounter()
	{
		return true;
	}

	@ConfigItem(
		keyName = "label",
		name = "Panel title",
		description = "Text shown as the panel's title"
	)
	default String label()
	{
		return "Test Rlite";
	}
}
