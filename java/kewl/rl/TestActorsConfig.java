// Config for TestActors, declared the way a Hub plugin declares config.
package kewl.rl;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("testactors")
public interface TestActorsConfig extends Config
{
	@ConfigItem(keyName = "showNpcs", name = "Show NPCs", description = "Draw nearby NPCs")
	default boolean showNpcs()
	{
		return true;
	}

	@ConfigItem(keyName = "showPlayers", name = "Show players", description = "Draw nearby players (you included)")
	default boolean showPlayers()
	{
		return true;
	}

	@ConfigItem(keyName = "drawHull", name = "Draw hull", description = "The approximate convex hull (a prism, no model access)")
	default boolean drawHull()
	{
		return true;
	}

	@ConfigItem(keyName = "drawTile", name = "Draw tile", description = "The tile outline at the actor's own ground height")
	default boolean drawTile()
	{
		return true;
	}

	@ConfigItem(keyName = "drawName", name = "Draw name", description = "Name (or #id when the name is unread) above the head")
	default boolean drawName()
	{
		return true;
	}

	@Range(min = 1, max = 30)
	@ConfigItem(keyName = "range", name = "Range", description = "Only draw actors within this many tiles")
	default int range()
	{
		return 12;
	}

	@Range(min = 1, max = 1000)
	@ConfigItem(keyName = "hullHeight", name = "Hull height", description = "Actor logical height in fine units; tune until the name sits above the head")
	default int hullHeight()
	{
		return 200;
	}
}
