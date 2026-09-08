// Shim of net.runelite.client.plugins.Plugin (BSD-2, RuneLite), cut down to what a plugin needs to
// override here. Lifecycle is driven by kewl.rl.RlitePlugin, which calls startUp/shutDown on
// enable/disable and fires events from the frame thread.
package net.runelite.client.plugins;

import net.runelite.client.eventbus.EventBus;

public abstract class Plugin
{
	private EventBus eventBus;

	/** Called by RlitePlugin once, after injection, before startUp. */
	public void setEventBus(EventBus eventBus)
	{
		this.eventBus = eventBus;
	}

	public EventBus getEventBus()
	{
		return eventBus;
	}

	// Match upstream: protected, non-abstract, empty. A plugin overrides what it needs; RlitePlugin
	// invokes these reflectively because protected members are not callable from kewl.rl.
	protected void startUp() throws Exception
	{
	}

	protected void shutDown() throws Exception
	{
	}
}
