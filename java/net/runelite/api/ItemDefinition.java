// Shim of net.runelite.api.ItemDefinition (BSD-2, RuneLite).
//
// Item definitions live in the game's cache, which we do not read. Names degrade to "item <id>"
// until an item-name table is bundled next to the varbit table (same cache dump, same format) --
// the id at least keeps two different items from rendering as the same label.
package net.runelite.api;

public class ItemDefinition
{
	private final int id;
	private final String name;

	public ItemDefinition(int id)
	{
		this(id, null);
	}

	public ItemDefinition(int id, String name)
	{
		this.id = id;
		this.name = name;
	}

	public int getId()
	{
		return id;
	}

	public String getName()
	{
		return name == null ? "item " + id : name;
	}

	public boolean isStackable()
	{
		ShimSupport.note("ItemDefinition.isStackable", ShimSupport.Kind.NEEDS_CACHE_DATA,
			"reads false for EVERY item, coins and runes included: stackability is cache data and no"
				+ " item table is bundled. A caller counting a stack sees one item");
		return false;
	}
}
