// The one enum the shim can answer for: the rune pouch's rune-slot enum (EnumID.RUNEPOUCH_RUNE,
// key = rune slot index, value = the rune item id). Its values are named-object references in the
// cache, resolved here to the shim's own gameval item ids.
//
// Generated from the enum archive of the OSRS cache (rev 240, cache 2686 of 2026-09-02, via
// archive.openrs2.org as dumped by github.com/Joshua-F/osrs-dumps). The client can renumber enums
// between revisions, and a reshuffle here would silently map pouch contents to the wrong rune --
// the trade-off for not reading the cache live. This enum has been stable for years; if it does
// change, regenerate the pairs from a fresh dump rather than guessing.
package net.runelite.api;

import net.runelite.api.gameval.ItemID;

final class EnumTable
{
	/** {enum key, rune item id} pairs for RUNEPOUCH_RUNE, ascending by key. */
	static final int[][] RUNEPOUCH_RUNE = {
		{1, ItemID.AIRRUNE},
		{2, ItemID.WATERRUNE},
		{3, ItemID.EARTHRUNE},
		{4, ItemID.FIRERUNE},
		{5, ItemID.MINDRUNE},
		{6, ItemID.CHAOSRUNE},
		{7, ItemID.DEATHRUNE},
		{8, ItemID.BLOODRUNE},
		{9, ItemID.COSMICRUNE},
		{10, ItemID.NATURERUNE},
		{11, ItemID.LAWRUNE},
		{12, ItemID.BODYRUNE},
		{13, ItemID.SOULRUNE},
		{14, ItemID.ASTRALRUNE},
		{15, ItemID.MISTRUNE},
		{16, ItemID.MUDRUNE},
		{17, ItemID.DUSTRUNE},
		{18, ItemID.LAVARUNE},
		{19, ItemID.STEAMRUNE},
		{20, ItemID.SMOKERUNE},
		{21, ItemID.WRATHRUNE},
		{22, ItemID.SUNFIRERUNE},
		{23, ItemID.AETHERRUNE},
	};
}
