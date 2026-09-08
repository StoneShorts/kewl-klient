// Shim of net.runelite.api.IndexedObjectSet (BSD-2, RuneLite): an iterable of actors with a by-index
// lookup. Upstream wraps the client's sparse arrays; here it is a view over an ActorTable list plus the
// table's by-uid lookup (the uid plays the index role, see NPC.getIndex).
package net.runelite.api;

import java.util.Iterator;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import javax.annotation.Nullable;

public final class IndexedObjectSet<T> implements Iterable<T>
{
	private final List<T> items;
	private final IntFunction<T> byIndex;

	public IndexedObjectSet(List<T> items, IntFunction<T> byIndex)
	{
		this.items = items;
		this.byIndex = byIndex;
	}

	@Nullable
	public T byIndex(int index)
	{
		return byIndex.apply(index);
	}

	public int size()
	{
		return items.size();
	}

	@Override
	public Iterator<T> iterator()
	{
		return items.iterator();
	}

	public Stream<T> stream()
	{
		return items.stream();
	}
}
