// Shim of net.runelite.client.ui.overlay.OverlayManager (BSD-2, RuneLite) -- just the collection.
package net.runelite.client.ui.overlay;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

public class OverlayManager
{
	// Copy-on-write: startUp/shutDown mutate this while the renderer iterates it. Both now run on the
	// frame thread, but the panel's refresh timer and any stray Swing code can still read concurrently,
	// and a copy-on-write list makes that a non-event instead of a CME that blanks a frame.
	private final List<Overlay> overlays = new CopyOnWriteArrayList<>();

	public void add(Overlay overlay)
	{
		overlays.add(overlay);
	}

	public void add(Collection<Overlay> toAdd)
	{
		overlays.addAll(toAdd);
	}

	public void remove(Overlay overlay)
	{
		overlays.remove(overlay);
	}

	public void remove(Collection<Overlay> toRemove)
	{
		overlays.removeAll(toRemove);
	}

	public void removeIf(Predicate<Overlay> filter)
	{
		overlays.removeIf(filter);
	}

	public List<Overlay> getOverlays()
	{
		return Collections.unmodifiableList(overlays);
	}
}
