// The shim's proof of life, in the shape a Hub plugin is written.
//
// Config interface + @Provides, @Inject fields, @Subscribe event handler, an OverlayPanel added on
// startUp and removed on shutDown. If this compiles and, on Windows, its panel counts ticks and the
// checkbox in kewl's control panel hides it, the shim's wiring is sound and the real plugin is a
// matter of coverage, not architecture.
package kewl.rl;

import javax.inject.Inject;

import com.google.inject.Provides;

import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Test Rlite",
	description = "Exercises the RuneLite shim: config, events, overlay"
)
public class TestRlite extends net.runelite.client.plugins.Plugin
{
	@Inject
	private TestRliteConfig config;

	@Inject
	private TestRliteOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	private int ticks;

	int ticks()
	{
		return ticks;
	}

	@Provides
	TestRliteConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TestRliteConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		ticks++;
	}
}
