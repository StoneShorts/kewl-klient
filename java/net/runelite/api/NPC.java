// Shim of net.runelite.api.NPC (BSD-2, RuneLite): a stable identity for one NPC handle, re-pointed at
// this frame's kewl.api.Entity snapshot by ActorTable each frame.
//
// Identity is what ported plugins lean on: an NPC received in NpcSpawned is held in Sets/Maps until
// the matching NpcDespawned, and compared by reference. So one NPC object lives per (npc, uid) for as
// long as the handle is on screen, and its `last` snapshot is kept after despawn so a plugin that
// still holds it reads the last known position instead of a NullPointerException.
package net.runelite.api;

import javax.annotation.Nullable;

import kewl.api.Entity;

public class NPC extends Actor
{
	private final int uid;
	/** This frame's snapshot (kept after despawn). Written by ActorTable, read on the same frame thread. */
	volatile Entity last;
	volatile boolean present;
	private final NPCComposition composition = new NPCComposition(this);

	NPC(int uid, Entity first)
	{
		this.uid = uid;
		this.last = first;
		this.present = true;
	}

	@Override
	int uid()
	{
		return uid;
	}

	@Override
	boolean present()
	{
		return present;
	}

	@Override
	int sceneX()
	{
		return last.sceneX();
	}

	@Override
	int sceneY()
	{
		return last.sceneY();
	}

	@Override
	int fineX()
	{
		return last.fineX();
	}

	@Override
	int fineY()
	{
		return last.fineY();
	}

	@Override
	int height()
	{
		return last.height();
	}

	@Override
	int animation()
	{
		return last.animation();
	}

	@Override
	int orientation()
	{
		return last.orientation();
	}

	@Override
	String rawName()
	{
		return ActorTable.nameOf(this, last);
	}

	@Override
	int size()
	{
		return composition.getSize();
	}

	/** The NPC type id (what kind of creature), from ENTITY_DEF_PTR's definition. */
	public int getId()
	{
		return last.id();
	}

	/**
	 * Upstream's index is the slot in the client's NPC array; here the game's own handle IS the stable
	 * per-NPC integer, so it plays that role (and WorldView.npcs().byIndex resolves it).
	 */
	public int getIndex()
	{
		return uid;
	}

	@Override
	@Nullable
	public String getName()
	{
		return composition.getName();
	}

	public NPCComposition getComposition()
	{
		return composition;
	}

	/** No multi-NPC (varbit-transformed) definitions are read; the composition is its own transform. */
	@Nullable
	public NPCComposition getTransformedComposition()
	{
		return composition;
	}

	// equals/hashCode: identity, upstream semantics -- one object per live handle.

	@Override
	public String toString()
	{
		return "npc#" + getId() + " uid=" + uid + " @" + last.worldX() + "," + last.worldY()
			+ (present ? "" : " (despawned)");
	}
}
