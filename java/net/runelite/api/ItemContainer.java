// Shim of net.runelite.api.ItemContainer (BSD-2, RuneLite), cut to getItems().
package net.runelite.api;

import lombok.Data;

@Data
public class ItemContainer
{
	private final int id;
	private final Item[] items;

	public Item[] getItems()
	{
		return items;
	}

	public int size()
	{
		return items == null ? 0 : items.length;
	}
}
