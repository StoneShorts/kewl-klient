// offsets.hpp -- the RUNTIME LAYOUT the rest of KewlKlient consumes.
//
// Every number below used to be a `constexpr` measured by hand on ONE build of
// osclient.exe, and every game update made the whole file rot. Now they are
// mutable inline variables: the RUNTIME RESOLVER (client/resolve.hpp +
// client/resolve_pe.hpp) reconstructs the game's layout from the client's own
// Lua binding layer at injection time and OVERWRITES these slots with
// instruction-derived, validated values. What is left here unchanged is only
// what no recipe can derive (see RESOLUTION.md for the full mapping and the
// reasons):
//
//   * values the game itself verifies live before use (guarded fallbacks),
//   * behavioural constants that are not addresses at all (menu opcodes),
//   * nothing else.
//
// The resolver's method -- anchor on a string the client names itself, walk to
// the code that references it, read the rip-relative globals and struct
// displacements OUT OF THE INSTRUCTIONS, validate, cache per build -- is the
// one documented in .claude/skills/deob/SKILL.md and proven instruction-by-
// instruction against the real client-240-7 bytes in
// tools/ida_scripts/test_recipes.py.
//
// The numbers below are the client-240-6/240-7 measurements kept as FALLBACKS
// (and as the self-test's expectations); on a new build the resolver replaces
// them or zeroes the slot and the feature disables itself. A slot at 0 is the
// designed "not available on this build" state, exactly the old DO_ACTION=0.
//
// Consumers keep writing `off::SOME_NAME` exactly as before.
#pragma once
#include <cstdint>

// (resolve.hpp includes THIS file and overwrites these slots at injection time;
// nothing here may include the resolver -- this file stays the pure layout.)

namespace kk::off {

// ---------------------------------------------------------------------------
// FUNCTIONS WE CALL (absolute VAs; 0 = disabled).
//   WORLD_TO_SCREEN: derived from the worldToScreenCoord Lua closure chain,
//     gated on the camera-triple shape. DO_ACTION: hook-and-log only -- calling
//     the wrong address crashes the game, so no recipe is allowed to nominate
//     it; it stays 0 until a hook run confirms a candidate.
// ---------------------------------------------------------------------------
inline std::uintptr_t WORLD_TO_SCREEN = 0;
inline std::uintptr_t DO_ACTION       = 0;

// RuntimeModel function/vtable RVAs and object-relative geometry fields.
// Function/vtable values are RVAs added to moduleBase(); MODEL_* values are
// displacements added to a validated RuntimeModel object. They are installed
// only for an exact, structurally validated 240-7 image.
inline std::uintptr_t RUNTIME_MODEL_VTABLE     = 0;
inline std::uintptr_t RUNTIME_MODEL_CTOR       = 0;
inline std::uintptr_t RUNTIME_MODEL_CLONE      = 0;
inline std::uintptr_t RUNTIME_MODEL_APPLY_ANIM = 0;
inline std::uintptr_t RUNTIME_MODEL_TRANSFORM  = 0;
inline std::uintptr_t RUNTIME_MODEL_INVALIDATE = 0;
inline std::uintptr_t RUNTIME_MODEL_SCALE      = 0;

inline std::uintptr_t MODEL_VERTEX_COUNT = 0;
inline std::uintptr_t MODEL_VERTEX_X     = 0;
inline std::uintptr_t MODEL_VERTEX_Y     = 0;
inline std::uintptr_t MODEL_VERTEX_Z     = 0;
inline std::uintptr_t MODEL_ANIM_GROUPS  = 0;

// Diagnostic model-path RVAs. NPC_GET_MODEL_ENTRY is intentionally not called
// until its managed-pair ABI and ownership semantics are recovered.
inline std::uintptr_t NPC_GET_MODEL_ENTRY = 0;
inline std::uintptr_t NPC_MODEL_RESOLVER  = 0;

// ---------------------------------------------------------------------------
// THE ROOT POINTER + GLOBALS (RVAs from module base).
//   CLIENT_OBJ_PTR: the stat leaves' shared rip-cell. VARP_ARRAY_PTR: getVarp's
//   leaf cell. Containers: the invGetObjId impl's rip pair. Sentinel: beside
//   the container pair (+0xE8), shape-checked.
// ---------------------------------------------------------------------------
inline std::uintptr_t CLIENT_OBJ_PTR       = 0xE95668;
inline std::uintptr_t VARP_ARRAY_PTR       = 0x155C508;
inline std::uintptr_t CONTAINER_BUCKETS    = 0x154C500;
inline std::uintptr_t CONTAINER_MASK       = 0x154C508;
inline std::uintptr_t IFACE_EMPTY_SENTINEL = 0x155C5F0;

// ---------------------------------------------------------------------------
// FIELDS ON THE CLIENT OBJECT.
//   SCENE/LOCAL_PLAYER_IDX/player table/registry: layout-derived from the stat
//   cluster (identical 0x64 spacing + 0x10 alignment). RUN_ENERGY: never had a
//   real source on 240-6 (the old file's own note says suspect) -- starts 0,
//   which turns runEnergy() into a documented no-op, honest about being
//   unknown. GAME_STATE/CYCLE: isLoggedIn's cmp gate + spacing invariant.
//   WORLD_MAP: stats-cluster derived, live-checked (0x400..0x8000, u64 >= 4)
//   before use. IFACE_MANAGER: stats-cluster derived, live-checked (u64
//   64..4096 at manager+GROUP_COUNT) before use.
// ---------------------------------------------------------------------------
inline std::uintptr_t SCENE                = 0xCA90;
inline std::uintptr_t LOCAL_PLAYER_IDX     = 0xCC5C;
inline std::uintptr_t PLAYER_COUNT         = 0xCCE0;
inline std::uintptr_t PLAYER_IDS           = 0xCCE4;
inline std::uintptr_t SKILL_EFFECTIVE      = 0x3360;
inline std::uintptr_t SKILL_BASE           = 0x33C4;
inline std::uintptr_t SKILL_XP             = 0x3428;
inline std::uintptr_t RUN_ENERGY           = 0;
inline std::uintptr_t CYCLE                = 0x2164;
inline std::uintptr_t GAME_STATE           = 0x2160;
inline std::uintptr_t WORLD_MAP            = 0x49B8;
inline std::uintptr_t REGISTRY_MAP         = 0xC9C8;
inline std::uintptr_t REGISTRY_GROUPS      = 0xC9E8;
inline std::uintptr_t REGISTRY_GROUP_COUNT = 0xC9F0;
inline std::uintptr_t IFACE_MANAGER        = 0x413BE8;

// ---------------------------------------------------------------------------
// SCENE OBJECT FIELDS (base = the tile anchor every scene read is checked
// against; npc uid array = the stat-cluster form at 0xD0/0xD8).
// ---------------------------------------------------------------------------
inline std::uintptr_t SCENE_BASE_X        = 0x24;
inline std::uintptr_t SCENE_BASE_Y        = 0x28;
inline std::uintptr_t SCENE_NPC_UIDS      = 0xD0;
inline std::uintptr_t SCENE_NPC_UID_COUNT = 0xD8;

// ---------------------------------------------------------------------------
// FIELDS ON AN ENTITY.
//   sceneX/sceneY: npcCoord's 0x28-apart load pair. defPtr+defName: npcName's
//   adjacent-pair gate (def first, name second, exactly +8). The rest are the
//   live-verified 240-6 measurements with the guards their notes describe; a
//   live scan on a new build would re-derive them from behaviour, not from a
//   static recipe.
// ---------------------------------------------------------------------------
inline std::uintptr_t ENTITY_SCENE_X       = 0x3F0;
inline std::uintptr_t ENTITY_SCENE_Y       = 0x418;
inline std::uintptr_t ENTITY_FINE_H        = 0x1F8;
inline std::uintptr_t ENTITY_FINE_X        = 0x1FC;
inline std::uintptr_t ENTITY_FINE_Y        = 0x200;
inline std::uintptr_t ENTITY_PLANE         = 0x420;  // SUSPECT legacy; the guarded dual read below decides
inline std::uintptr_t ENTITY_PLANE_COORD   = 0x7CC;  // decompile-derived; the guarded dual read decides
inline std::uintptr_t ENTITY_DEF_PTR       = 0x730;
inline std::uintptr_t ENTITY_NAME_OVERRIDE = 0x710;  // inline NxtString on the entity
inline std::uintptr_t PLAYER_NAME_PTR      = 0x718;  // -> NxtString (players only)
inline std::uintptr_t DEF_NAME             = 0x8;    // NxtString on the NPC definition
inline std::uintptr_t ENTITY_ANIMATION     = 0x4D8;  // -1 when idle (guarded by range checks)
inline std::uintptr_t ENTITY_ORIENTATION   = 0x3E0;  // 0..2047 cardinal (guarded)

// ---------------------------------------------------------------------------
// FIELDS ON THE WORLD-MAP OBJECT (MapCoord origin + the centre/scroll pair it
// is derived from; relation 8*centre = origin + 48 is checked live).
// ---------------------------------------------------------------------------
inline std::uintptr_t WM_ORIGIN_LEVEL = 0x54B8;
inline std::uintptr_t WM_ORIGIN_X     = 0x54BC;
inline std::uintptr_t WM_ORIGIN_Z     = 0x54C0;
inline std::uintptr_t WM_CENTRE_X     = 0x54C4;
inline std::uintptr_t WM_CENTRE_Z     = 0x54C8;

// ---------------------------------------------------------------------------
// IFACE (widget) SYSTEM.
//   MANAGER/GROUP_* on the manager: stats-cluster derived, live-checked
//   (sane group count) before use. GROUP_ENTRY_*: layout-derived (constant
//   u64 at +8 before the first ptr, 24-byte stride). IFTYPE_* rect block:
//   CAN BE DERIVED LIVE -- widgetScanRects() (game.hpp) already finds the
//   layout-wide rect/hidden/text block by scanning loaded widgets for values
//   we know (the positive-control id, the canvas-sized roots); resolve.cpp's
//   widgetRectScan() calls it and overwrites these slots only when exactly one
//   candidate survives. X/Y/W/H: 240-6 values, also covered by the
//   +4-adjacent shape check inside that scan. TEXT_FLAG is TEXT+0x17 by the
//   NxtString layout, never independent.
// ---------------------------------------------------------------------------
inline std::uintptr_t IFACE_GROUP_COUNT        = 0x6600;
inline std::uintptr_t IFACE_GROUP_ARRAY        = 0x6608;
inline std::uintptr_t IFACE_GROUP_ENTRY_STRIDE = 24;
inline std::uintptr_t IFACE_GROUP_ENTRY_COUNT  = 0x8;
inline std::uintptr_t IFACE_GROUP_ENTRY_DATA   = 0x10;
inline std::uintptr_t IFTYPE_X                 = 0x5C;
inline std::uintptr_t IFTYPE_Y                 = 0x60;
inline std::uintptr_t IFTYPE_WIDTH             = 0x64;
inline std::uintptr_t IFTYPE_HEIGHT            = 0x68;
inline std::uintptr_t IFTYPE_HIDDEN            = 0x78;
inline std::uintptr_t IFTYPE_CHILDREN_COUNT    = 0xB50;
inline std::uintptr_t IFTYPE_CHILDREN_DATA     = 0xB58;
inline std::uintptr_t IFTYPE_TEXT              = 0x158;
inline std::uintptr_t IFTYPE_TEXT2             = 0x170;
inline std::uintptr_t IFTYPE_SCAN_SPAN         = 0x400;
inline int            IFTYPE_CHAIN_MAX         = 16;   // depth cap: not build-dependent

// The NxtString flag bytes: TEXT+0x17 by layout (see the original file's NxtString
// note). Separate slots so the resolver keeps them in step with TEXT/TEXT2.
inline std::uintptr_t IFTYPE_TEXT_FLAG  = IFTYPE_TEXT + 0x17;
inline std::uintptr_t IFTYPE_TEXT2_FLAG = IFTYPE_TEXT2 + 0x17;

// ---------------------------------------------------------------------------
// REGISTRY TABLE + NODE LAYOUT (the hashtable holding players + npcs).
//   node uid/entity/next: getNpcIdAll's walk shape (uid at +0, entity at +0x10,
//   next at +0x18, exactly as the live-verified walk decodes). group
//   table/next + pair fields: the same walk's group level, whose shape the
//   registry live-check verifies (count in 1..64, buckets non-null).
// ---------------------------------------------------------------------------
inline std::uintptr_t GROUP_TABLE         = 0x10;
inline std::uintptr_t GROUP_NEXT          = 0x18;
inline std::uintptr_t PLAYER_BUCKETS      = 0x68;
inline std::uintptr_t PLAYER_BUCKET_COUNT = 0x70;
inline std::uintptr_t NPC_BUCKETS         = 0x98;
inline std::uintptr_t NPC_BUCKET_COUNT    = 0xA0;
inline std::uintptr_t NODE_UID            = 0x00;
inline std::uintptr_t NODE_ENTITY         = 0x10;
inline std::uintptr_t NODE_NEXT           = 0x18;

// ---------------------------------------------------------------------------
// CONTAINER (inventory) NODE LAYOUT (from the invGetObjId/invGetNum walks;
// (end-start)>>2 = slot count, parallel id/qty arrays).
// ---------------------------------------------------------------------------
inline std::uintptr_t CONTAINER_NODE_IDS      = 0x08;
inline std::uintptr_t CONTAINER_NODE_IDS_END  = 0x10;
inline std::uintptr_t CONTAINER_NODE_QTYS     = 0x20;
inline std::uintptr_t CONTAINER_NODE_QTYS_END = 0x28;
inline std::uintptr_t CONTAINER_NODE_NEXT     = 0x38;

// ---------------------------------------------------------------------------
// CAMERA / VIEW (the projection leaf reads the camera triple off the client
// object; the view object + its scale block are the leaf's rescale source).
//   CAMERA_FINE_*: derived from the leaf itself (three adjacent int reads
//   before its first call). VIEW_OBJ/_OBJ_SCALE_BASE: stats-cluster derived;
//   the IN/OUT pairs' live equality check (both read the canvas size) is what
//   actually guards them at projection time.
// ---------------------------------------------------------------------------
inline std::uintptr_t CAMERA_FINE_X       = 0x895D8;
inline std::uintptr_t CAMERA_FINE_H       = 0x895DC;
inline std::uintptr_t CAMERA_FINE_Y       = 0x895E0;
inline std::uintptr_t VIEW_OBJ            = 0x90;
inline std::uintptr_t VIEW_OBJ_SCALE_BASE = 0x10;
inline std::uintptr_t VIEW_IN_W           = 0x20;
inline std::uintptr_t VIEW_IN_H           = 0x24;
inline std::uintptr_t VIEW_OUT_W          = 0x5C;
inline std::uintptr_t VIEW_OUT_H          = 0x60;

// ---------------------------------------------------------------------------
// MENU OPCODES: behavioural constants captured by hook-and-log on an older
// build -- they are per-build and they DO get shuffled, but they are not
// addresses: no instruction sequence reveals them. They can only be re-derived
// by hooking DO_ACTION (which this build cannot do until a candidate is
// confirmed). Kept as constants with that caveat.
// ---------------------------------------------------------------------------
inline constexpr int OPLOC1 = 3;                                        // scenery, first option
inline constexpr int OPNPC1 = 9, OPNPC2 = 10, OPNPC3 = 11, OPNPC4 = 12, OPNPC5 = 13;
inline constexpr int OP_WALK = 31;                                      // scene tiles, NOT the ratio-scaled neighbour

// The build string is advisory now: the resolver, not this string, decides
// whether a layout is trustworthy. It feeds the log line only.
inline constexpr const wchar_t* BUILD_VERSION = L"240-7";

}  // namespace kk::off
