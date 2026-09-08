// TestRlite's overlay: an OverlayPanel like a Hub plugin would write.
package kewl.rl;

import java.awt.Dimension;
import java.awt.Graphics2D;

import javax.inject.Inject;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

class TestRliteOverlay extends OverlayPanel
{
	private final TestRlite plugin;
	private final TestRliteConfig config;

	@Inject
	private TestRliteOverlay(TestRlite plugin, TestRliteConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showCounter())
		{
			return null;
		}
		panelComponent.getChildren().add(TitleComponent.builder()
			.text(config.label())
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Ticks")
			.right(Integer.toString(plugin.ticks()))
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("State")
			.right(net.runelite.api.Client.get().getGameState().toString())
			.build());
		return super.render(graphics);
	}
}
