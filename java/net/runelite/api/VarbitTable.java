// A slice of the OSRS varbit definition table: the ids KewlKlient actually reads.
//
// Generated from the varbit/varp definition archives of the OSRS cache (rev 240,
// cache 2686 of 2026-09-02, via archive.openrs2.org as dumped by github.com/Joshua-F/osrs-dumps),
// so every row is the cache's own {parent varp, low bit, bit count} triple rather than a guess.
// A varbit's value is then (varp >> lowBit) & ((1 << bits) - 1), which is exactly what
// ClientState.getVarbitValue does on top of the live varp array.
//
// This is deliberately not the full ~19k-entry table: entries are added here as the plugin or
// the transport data starts reading them, each with its cache name for cross-checking. A full
// table can still be dropped in as resources/varbits.csv -- that file wins over this one.
//
// The names here are the cache's own (openrs2's lowercase internal names), which differ from
// RuneLite's gameval names -- e.g. cache "ardougne_diary_easy_complete" is gameval
// DIARY_ARDOUGNE_EASY_COMPLETED, same id 4458. Ids were cross-checked against both gameval and
// the community varb index; only the names ever disagreed, never the ids.
package net.runelite.api;

final class VarbitTable
{
	/** {varbit id, varp id, low bit, bit count} triples, ascending by id. */
	static final int[][] ROWS =
		{
			{29, 1139, 0, 6},	// rune_pouch_type_1 [RUNE_POUCH_TYPE_1]
			{260, 423, 0, 8},	// mdaughter_quest_var
			{299, 433, 0, 8},	// dwarfrock_quest
			{346, 437, 0, 5},	// golem_a
			{418, 445, 0, 5},	// ics_little_var
			{451, 449, 0, 2},	// tog_juna_bowl
			{487, 455, 0, 5},	// zogre
			{496, 455, 16, 1},	// thzfe_blocking_barricade
			{532, 465, 0, 11},	// lost_tribe_quest
			{538, 465, 19, 1},	// lost_tribe_hole_2_dug
			{621, 1045, 31, 1},	// edgeville_spawn
			{668, 496, 22, 1},	// falador_spawn
			{1622, 1139, 6, 6},	// rune_pouch_type_2 [RUNE_POUCH_TYPE_2]
			{1623, 1139, 12, 6},	// rune_pouch_type_3 [RUNE_POUCH_TYPE_3]
			{1624, 1139, 18, 14},	// rune_pouch_quantity_1 [RUNE_POUCH_QUANTITY_1]
			{1625, 1140, 0, 14},	// rune_pouch_quantity_2 [RUNE_POUCH_QUANTITY_2]
			{1626, 1140, 14, 14},	// rune_pouch_quantity_3 [RUNE_POUCH_QUANTITY_3]
			{2098, 723, 0, 9},	// swansong
			{2187, 738, 0, 5},	// poh_house_location
			{2326, 810, 0, 7},	// fairy2_queencure_quest [FAIRY2_QUEENCURE_QUEST]
			{2573, 869, 0, 9},	// myq3_main_quest
			{2867, 912, 8, 2},	// zep_multi_basket
			{2868, 912, 10, 2},	// zep_multi_piccard
			{2869, 912, 12, 1},	// zep_multi_cast
			{2870, 912, 13, 1},	// zep_multi_gno
			{2871, 912, 14, 1},	// zep_multi_craft
			{2872, 912, 15, 1},	// zep_multi_varr
			{3264, 948, 28, 4},	// barbassault_arenanewb
			{3311, 970, 0, 10},	// fris_quest
			{3598, 1001, 29, 1},	// atjun_med_reward
			{3611, 1002, 29, 2},	// atjun_hard_done
			{3637, 1010, 0, 11},	// vm_kudos
			{3741, 177, 6, 1},	// dragonslayer_crandor_found_secret_door
			{3759, 1023, 4, 2},	// brut_fire
			{3910, 1050, 5, 1},	// camelot_spawn
			{4070, 439, 0, 2},	// spellbook
			{4441, 400, 24, 3},	// fenk_built_bridge_north
			{4458, 1188, 10, 1},	// ardougne_diary_easy_complete
			{4459, 1188, 11, 1},	// ardougne_diary_medium_complete
			{4460, 1188, 12, 1},	// ardougne_diary_hard_complete
			{4461, 1188, 13, 1},	// ardougne_diary_elite_complete
			{4464, 1188, 16, 1},	// falador_diary_hard_complete
			{4465, 1188, 17, 1},	// falador_diary_elite_complete
			{4467, 1188, 19, 1},	// wilderness_diary_medium_complete
			{4468, 1188, 20, 1},	// wilderness_diary_hard_complete
			{4469, 1188, 21, 1},	// wilderness_diary_elite_complete
			{4473, 1188, 24, 1},	// western_diary_hard_complete
			{4474, 1188, 25, 1},	// western_diary_elite_complete
			{4477, 1188, 28, 1},	// kandarin_diary_hard_complete
			{4478, 1188, 29, 1},	// kandarin_diary_elite_complete
			{4480, 1188, 31, 1},	// varrock_diary_medium_complete
			{4481, 1189, 0, 1},	// varrock_diary_hard_complete
			{4482, 1189, 1, 1},	// varrock_diary_elite_complete
			{4485, 1189, 4, 1},	// desert_diary_hard_complete
			{4486, 1189, 5, 1},	// desert_diary_elite_complete
			{4487, 1189, 6, 1},	// morytania_diary_easy_complete
			{4488, 1189, 7, 1},	// morytania_diary_medium_complete
			{4489, 1189, 8, 1},	// morytania_diary_hard_complete
			{4490, 1189, 9, 1},	// morytania_diary_elite_complete
			{4491, 1189, 10, 1},	// fremennik_diary_easy_complete
			{4493, 1189, 12, 1},	// fremennik_diary_hard_complete
			{4494, 1189, 13, 1},	// fremennik_diary_elite_complete
			{4496, 1189, 15, 1},	// lumbridge_diary_medium_complete
			{4497, 1189, 16, 1},	// lumbridge_diary_hard_complete
			{4498, 1189, 17, 1},	// lumbridge_diary_elite_complete
			{4541, 437, 21, 2},	// wilderness_sword_last_teleport
			{4542, 318, 25, 3},	// morytania_legs_last_teleport
			{4548, 635, 21, 1},	// yanille_teleport_location
			{4552, 635, 25, 2},	// lumbridge_cabbage_teleport
			{4558, 635, 31, 1},	// desert_nardah_teleport
			{4560, 318, 18, 1},	// seers_camelot_teleport
			{4561, 318, 19, 2},	// seers_sherlock_teleport
			{4564, 318, 23, 2},	// western_pisc_teleport
			{4566, 1200, 7, 1},	// karamja_diary_elite_complete
			{4585, 318, 29, 1},	// varrock_ge_teleport
			{4607, 1055, 8, 1},	// resizable_stone_arrangement [RESIZABLE_STONE_ARRANGEMENT]
			{4744, 1047, 23, 1},	// poh_tele_toggle
			{4819, 1312, 7, 3},	// chinchompa_teleports
			{5005, 2633, 10, 3},	// fremennik_basic_teleport
			{5023, 400, 27, 3},	// fenk_built_bridge_south
			{5087, 1345, 0, 1},	// cata_hole1
			{5088, 1345, 1, 1},	// cata_hole2
			{5421, 1429, 0, 1},	// raids_guide_travel_unlock
			{5619, 1566, 0, 6},	// veos_progress
			{5628, 393, 0, 1},	// karam_dungeon_entryfee
			{5629, 393, 1, 2},	// karam_dungeon_backdoor
			{5672, 1589, 0, 10},	// bookofscrolls_nardah
			{5673, 1589, 10, 10},	// bookofscrolls_digsite
			{5674, 1589, 20, 10},	// bookofscrolls_feldip
			{5675, 1590, 0, 10},	// bookofscrolls_lunarisle
			{5676, 1590, 10, 10},	// bookofscrolls_mortton
			{5677, 1590, 20, 10},	// bookofscrolls_pestcontrol
			{5678, 1591, 0, 10},	// bookofscrolls_piscatoris
			{5679, 1591, 10, 10},	// bookofscrolls_taibwo
			{5680, 1591, 20, 10},	// bookofscrolls_elf
			{5681, 1592, 0, 10},	// bookofscrolls_mosles
			{5682, 1592, 10, 10},	// bookofscrolls_lumberyard
			{5683, 1592, 20, 10},	// bookofscrolls_zulandra
			{5684, 1593, 0, 10},	// bookofscrolls_cerberus
			{5810, 113, 17, 1},	// observatory_shortcut_rope
			{6027, 1671, 0, 6},	// hosidiusquest
			{6028, 1671, 6, 1},	// hosidiusquest_reward
			{6035, 1566, 23, 8},	// veos_memoir_charges
			{6038, 1672, 6, 1},	// piscquest_reward
			{6056, 1593, 14, 10},	// bookofscrolls_revenants
			{6069, 635, 18, 1},	// ardougne_cloak_lowbits
			{6076, 1677, 14, 1},	// corsair_cove_resource_entry
			{6359, 1729, 6, 1},	// shayzienquest_reward
			{6528, 1785, 0, 8},	// my2arm_status
			{7255, 2712, 0, 9},	// myq5
			{7796, 2066, 0, 4},	// lovaquest
			{7801, 2066, 15, 1},	// lovaquest_reward
			{7857, 2071, 6, 1},	// arcquest_reward
			{7925, 1189, 19, 1},	// kourend_diary_easy_complete
			{7926, 1189, 20, 1},	// kourend_diary_medium_complete
			{7927, 1189, 21, 1},	// kourend_diary_hard_complete
			{7928, 1189, 22, 1},	// kourend_diary_elite_complete
			{7937, 2088, 0, 3},	// zeah_blessing_woodland_teleport
			{7938, 2088, 3, 3},	// zeah_blessing_brimstone_teleport
			{8253, 1593, 24, 8},	// bookofscrolls_watson_lowbits
			{8397, 2231, 6, 1},	// hosdun_west_door_status
			{8398, 2231, 7, 1},	// hosdun_east_door_status
			{9805, 4138, 4, 1},	// civitas_spawn
			{10449, 2647, 11, 1},	// darkm_shortcut_inner
			{10450, 2647, 12, 1},	// darkm_shortcut_outer
			{10528, 1047, 30, 1},	// wilderness_spawn
			{10662, 2801, 0, 5},	// league_area_selection_0 [LEAGUE_AREA_SELECTION_0]
			{10663, 2801, 5, 5},	// league_area_selection_1 [LEAGUE_AREA_SELECTION_1]
			{10664, 2801, 10, 5},	// league_area_selection_2 [LEAGUE_AREA_SELECTION_2]
			{10665, 2801, 15, 5},	// league_area_selection_3 [LEAGUE_AREA_SELECTION_3]
			{10666, 2801, 20, 5},	// league_area_selection_4 [LEAGUE_AREA_SELECTION_4]
			{10667, 2801, 25, 5},	// league_area_selection_5 [LEAGUE_AREA_SELECTION_5]
			{11175, 4396, 1, 1},	// pendant_of_ates_darkfrost_found
			{11176, 4396, 2, 1},	// pendant_of_ates_twilight_found
			{11177, 4396, 3, 1},	// pendant_of_ates_ralos_found
			{11178, 4396, 4, 1},	// pendant_of_ates_aldarin_found
			{11179, 4395, 0, 2},	// tapoyauik_ruins_failed_wallslide
			{11180, 4395, 2, 2},	// tapoyauik_failed_stepping_stones
			{11410, 4138, 16, 6},	// colosseum_highest_wave
			{12310, 3063, 30, 1},	// kourend_spawn
			{12341, 1345, 4, 1},	// cata_hole_giants_den
			{13599, 3355, 0, 9},	// lotg
			{13839, 3420, 6, 1},	// pharaohs_sceptre_necropolis
			{13841, 3421, 0, 9},	// bcs
			{14285, 3574, 0, 6},	// rune_pouch_type_4 [RUNE_POUCH_TYPE_4]
			{14286, 3574, 6, 14},	// rune_pouch_quantity_4 [RUNE_POUCH_QUANTITY_4]
			{15373, 3574, 20, 6},	// rune_pouch_type_5 [RUNE_POUCH_TYPE_5]
			{15374, 3574, 26, 6},	// rune_pouch_type_6 [RUNE_POUCH_TYPE_6]
			{15375, 4017, 0, 14},	// rune_pouch_quantity_5 [RUNE_POUCH_QUANTITY_5]
			{15376, 4017, 14, 14},	// rune_pouch_quantity_6 [RUNE_POUCH_QUANTITY_6]
			{17226, 4446, 12, 1},	// met_auburn_mountain_guide
			{18351, 4968, 7, 1},	// amenity_rowboat_vatrachos
			{18353, 4968, 9, 1},	// amenity_bankchest_charred_island
			{18355, 4968, 11, 1},	// amenity_rowboat_anglers
			{18356, 4968, 12, 1},	// amenity_rowboat_soul_tear
			{18358, 4968, 14, 1},	// amenity_bankchest_sunbleak
			{18361, 4968, 17, 1},	// amenity_bankchest_deepfin_1
			{18362, 4968, 18, 1},	// amenity_bankchest_deepfin_2
			{18363, 4968, 19, 1},	// amenity_bankchest_onyx_crest
			{18369, 4968, 25, 1},	// amenity_bankchest_buccaneers
			{18370, 4968, 26, 1},	// amenity_rowboat_ynysdail
			{18371, 4968, 27, 1},	// amenity_rowboat_buccaneers
			{19136, 5060, 21, 1}	// sailing_boarded_boat [SAILING_BOARDED_BOAT]
		};
}
