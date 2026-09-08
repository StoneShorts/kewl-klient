// Shim of net.runelite.api.NPCComposition (BSD-2, RuneLite). Upstream this is an interface over the
// NPC definition (cache type); here it is a concrete class backed by what nEntities exports -- the type
// id and the name -- with honest defaults for every definition field no offset has been derived for.
// Each stub names the offset it waits for (client/offsets.hpp only knows DEF_NAME = def+0x8).
package net.runelite.api;

import javax.annotation.Nullable;

public class NPCComposition
{
	private final NPC npc;

	NPCComposition(NPC npc)
	{
		this.npc = npc;
	}

	/**
	 * The NPC's name. "" while the client has not yielded one (upstream returns non-null and so does
	 * this; upstream never returns "" -- callers filtering by name should treat "" as unknown).
	 */
	public String getName()
	{
		return npc.rawName();
	}

	public int getId()
	{
		return npc.getId();
	}

	/** 0 = no combat level (upstream's convention). Waits on a DEF_COMBAT_LEVEL offset on the definition. */
	public int getCombatLevel()
	{
		gap("NPCComposition.getCombatLevel", "reads 0, upstream's \"no combat level\", for every NPC:"
			+ " client/offsets.hpp knows only DEF_NAME (def+0x8) on the NPC definition, so a filter"
			+ " written against combat level matches nothing rather than matching wrongly");
		return 0;
	}

	/** Footprint in tiles. 1 until a DEF_SIZE offset is derived; a 2x2 boss reads as 1x1 here. */
	public int getSize()
	{
		gap("NPCComposition.getSize", "reads 1 for every NPC: no DEF_SIZE offset, so a 2x2 or 5x5 boss"
			+ " has a 1x1 highlight tile and a 1x1-wide hull, drawn around the centre of its"
			+ " footprint");
		return 1;
	}

	/** Five null slots: the definition's action strings have no offset yet. */
	public String[] getActions()
	{
		gap("NPCComposition.getActions", "reads five NULL slots: the definition's action strings"
			+ " (\"Attack\", \"Talk-to\") have no offset, so nothing can match an NPC by what it"
			+ " can be asked to do");
		return new String[5];
	}

	/**
	 * True for every NPC. This is a PERMISSIVE default, not a reading: an NPC the client would refuse
	 * to interact with still looks interactible here. Chosen that way because the alternative hides
	 * NPCs from overlays that are perfectly visible on screen.
	 */
	public boolean isInteractible()
	{
		gap("NPCComposition.isInteractible", "reads true for every NPC (permissive): the definition"
			+ " flag byte has no offset");
		return true;
	}

	public boolean isFollower()
	{
		gap("NPCComposition.isFollower", "reads false for every NPC, so a pet or familiar is treated"
			+ " like any other NPC -- the definition flag byte has no offset");
		return false;
	}

	public boolean isVisible()
	{
		gap("NPCComposition.isVisible", "reads true for every NPC (permissive): the definition flag"
			+ " byte has no offset. An NPC the client is hiding would still be highlighted");
		return true;
	}

	public boolean isMinimapVisible()
	{
		gap("NPCComposition.isMinimapVisible", "reads true for every NPC (permissive) -- the same"
			+ " missing definition flag byte as isVisible");
		return true;
	}

	/** No multi-NPC varbit/varp configs read; null = "not a transforming NPC" upstream. */
	@Nullable
	public int[] getConfigs()
	{
		gap("NPCComposition.getConfigs", "reads null, upstream's \"not a transforming NPC\": the"
			+ " multi-NPC varbit/varp config array has no offset, so an NPC whose real identity"
			+ " depends on a varbit reports its base definition");
		return null;
	}

	private static void gap(String accessor, String reason)
	{
		ShimSupport.note(accessor, ShimSupport.Kind.NEEDS_OFFSET, reason);
	}

	public NPCComposition transform()
	{
		return this;
	}
}
