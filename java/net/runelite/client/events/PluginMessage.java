// Shim of net.runelite.client.events.PluginMessage (BSD-2, RuneLite) -- the real thing is a
// namespace/name/data triple; plugins post it through the bus and other plugins subscribe to it.
package net.runelite.client.events;

import java.util.Collections;
import java.util.Map;

public class PluginMessage
{
	private final String namespace;
	private final String name;
	private final Map<String, Object> data;

	public PluginMessage(String namespace, String name)
	{
		this(namespace, name, Collections.emptyMap());
	}

	public PluginMessage(String namespace, String name, Map<String, Object> data)
	{
		this.namespace = namespace;
		this.name = name;
		this.data = data;
	}

	public String getNamespace()
	{
		return namespace;
	}

	public String getName()
	{
		return name;
	}

	public Map<String, Object> getData()
	{
		return data;
	}
}
