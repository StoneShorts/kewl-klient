// Shim of net.runelite.client.events.ConfigChanged (BSD-2, RuneLite), minus the profile field.
package net.runelite.client.events;

import lombok.Data;

@Data
public class ConfigChanged
{
	private String group;
	private String key;
	private String value;
	private String oldValue;
}
