// Shim of net.runelite.api.Item (BSD-2, RuneLite).
package net.runelite.api;

import lombok.Data;

@Data
public class Item
{
	private final int id;
	private final int quantity;
}
