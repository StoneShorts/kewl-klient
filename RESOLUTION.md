# RESOLUTION.md -- from hardcoded offsets to the runtime resolver

Every number that used to live in `client/offsets.hpp` as a per-build constant,
what it represents, and how it is now obtained at runtime. The resolver is
`client/resolve.hpp` (recipes, cache, live validation) over
`client/resolve_pe.hpp` (PE parsing, anchor strings, bounded x86-64 decoder).

## How the resolver works

1. **Module map.** Parse the PE headers of the injected `osclient.exe`;
   `.text`, `.rdata`, every writable section (this client has TWO `.data`
   stretches), `.pdata` (resident function table). Fingerprint = FNV-1a of
   the mapped `.text` bytes + PE checksum.
2. **Cache.** `resolve-cache.json` next to the DLL, keyed by the fingerprint.
   On a hit, slots are restored and STILL live-validated; a failed validation
   falls through to a full re-resolve. A scan with any RECIPE-FAIL is never
   cached.
3. **Anchors.** One pass over `.rdata` for whole-string matches of the Lua
   binding names the client prints about itself, then an xref pass over
   `.text` for rip-relative references (REX + 8B/8D + modrm mod=0 rm=101 +
   disp32, decoded with instruction-end base).
4. **Recipes.** Each recipe = anchor -> code -> shape gate -> slot, reading
   displacements out of decoded instructions. A gate failure leaves the slot
   untouched (fallback or 0) and logs RECIPE-FAIL. Nothing is guessed.
5. **Live validation.** With a readable client object: GAME_STATE must be one
   of the known state values, the registry group count 1..64, the iface group
   count 64..65536, the container mask 1..1M, the varp array pointer non-null,
   the world-map relation `origin = 8*centre - 48`. A failure zeroes ONLY the
   affected slot (and dependents); the feature that reads 0 disables itself.
6. **Call.** `kk::rsl::init(moduleBase)` from `dllmain.cpp`, BEFORE the JVM
   starts. The old BUILD_VERSION refusal is now an advisory log line.

Verified on client-240-7 (`tools/resolve_selftest.cpp` maps the real exe and
runs the real resolver headers): 16/16 ground-truth slots exact, fresh scan
and cache path.

## Slot table

| slot | what it represents | resolution method | derived? |
|---|---|---|---|
| `CLIENT_OBJ_PTR` | the god-object cell: `*(base+X)` = client | stat leaves share one rip-cell (tiny functions, `.pdata`+padding starts) | YES |
| `SKILL_EFFECTIVE/BASE/XP` | 3 int arrays of 25 on the client (health = eff[3]/base[3]) | the leaves' disps; gate = distinct set, 0x64 spacing, eff<base<xp | YES |
| `VARP_ARRAY_PTR` | `*(base+X)` = int[] varp values | getVarp's 3-insn leaf rip-cell | YES |
| `GAME_STATE` | state machine int (30 = logged in) | isLoggedIn leaf: cmp qword[X+disp],-1 then cmp 0x1E (opcodes 0x81/0x83) | YES |
| `CYCLE` | frame tick counter | GAME_STATE + 4 (convention, next int above) | YES (weak) |
| `REGISTRY_GROUPS/GROUP_COUNT` | entity registry head array + u64 count | getNpcIdAll: first two client disps 8 apart | YES |
| `REGISTRY_MAP` | the registry map object (groups = map+0x20) | `REGISTRY_GROUPS - 0x20` (240-6 structural note) | YES (weak) |
| `SCENE_NPC_UIDS/COUNT` | scene uid array cross-check | getNpcIdAll: scene disps pair 8 apart | YES || `ENTITY_SCENE_X/Y` | entity tile coords | npcCoord/playerCoord leaves: two loads 0x28 apart | YES |
| `ENTITY_PLANE_COORD` | entity plane/level coordinate | coord leaf: unique third displacement alongside the verified scene pair | YES |
| `ENTITY_DEF_PTR` / `DEF_NAME` | npc definition ptr / name offset in it | npcName leaf: adjacent pair (def, name +8) | YES |
| `PLAYER_NAME_PTR` | player NxtString pointer | playerName leaf: unique entity pointer displacement | YES |

| `CONTAINER_BUCKETS/MASK` | the global container table | lambda (lea'd BEFORE the name ref) tail-calls the impl; impl's adjacent WRITABLE rip pair, agreed by both inv anchors | YES |
| `WORLD_TO_SCREEN` | the projection leaf we call | Graphics closure chain -> leaf; gate = camera triple (3 adjacent disps) AND loads the client cell | YES |
| `CAMERA_FINE_X/H/Y` | camera triple the leaf reads | the leaf's own instruction displacements | YES |
| `IFACE_*`, `IFTYPE_*` | widget system | live scan planned (game.hpp already has the tally); until then live-check gated | partially |
| `SCENE`, `LOCAL_PLAYER_IDX`, `PLAYER_*`, `WORLD_MAP`, `IFACE_MANAGER`, `SCENE_BASE_*`, view fields | client-object cluster | layout-derived in the cache epoch (identical 0x64 skill spacing + 0x10 alignment, root-anchored), live-check gated on new builds | partially |
| `ENTITY_FINE_*`, `ANIMATION`, `ORIENTATION` | model/render state | still guarded fallbacks; the `npcCoordFine`/`playerCoordFine` bindings calculate output through world-instance helpers rather than exposing a direct entity field, so no field offset is guessed | NO |
| `ENTITY_PLANE` | legacy plane candidate | retained only as a secondary guarded fallback; `coord` now derives `ENTITY_PLANE_COORD` when unique | NO (secondary) |
| `RUN_ENERGY` | was suspect on 240-6 already | 0: honest unknown; `runEnergy()` reads 0 | NO |
| `DO_ACTION` | the action entry we CALL | 0: never auto-derived (calling wrong = crash). Hook-and-log only | NO |
| `OPLOC1/OPNPC*/OP_WALK` | menu opcodes | behavioural constants; not addresses, no instruction recipe | NO |
| `BUILD_VERSION` | advisory string | log line only, no longer a gate | - |

## Semantic capabilities and provenance

`client/runtime_layout.hpp` adds a semantic layer above the compatibility offsets.
Each capability records whether it is unavailable, a legacy fallback, resolved
from an accessor, or validated. The current capabilities include the client
object, varp array, game state, entity registry, entity scene coordinates, NPC
definition/name path, and projection/camera path.

Readers now fail closed for the high-risk paths: entity enumeration requires the
registry and `npcCoord` capabilities, NPC names require the `npcName` definition
capability, and projection refuses to call `WORLD_TO_SCREEN` unless the current
projection and camera capabilities are installed. This prevents an old fallback
from being mistaken for a successful update-day resolution.

The r216 debug reference at `Documents/MobileOSRS/reference/lostcity-r216`
provides semantic provenance only. Its named `ClientNpc`, `PlayerRegistry`,
`ForEachNPC`, `GetNpcList`, `World`, and camera symbols guide the contracts; no
r216 address or field offset is copied into r240-7. The `coord` binding is also
used to derive the plane displacement only when exactly one third entity field
appears alongside the already-derived scene X/Y pair. The current IDA database
additionally confirms that `npcCoord`, `npcCoordFine`, `playerCoord`, and
`playerCoordFine` share the same world-instance coordinate helpers; that is why
fine model coordinates are not fabricated from a guessed entity layout.

## Failure behaviour

- Module map or all anchors unreadable -> `init` returns false, the compiled-in
  240-7 fallbacks stay, dllmain logs the degraded state. No game memory is read
  with unvalidated values beyond what the fallbacks already were.
- One recipe fails -> that slot keeps its fallback or 0; 0 disables exactly the
  feature that reads it (the `DO_ACTION = 0` precedent).
- Live validation fails -> slot zeroed at runtime, feature disables, log line
  names it.

## New build day

The resolver runs against whatever exe is loaded. If the binding layer and
recipe shapes survived (they are the client's own names and structural
invariants, not byte patterns), the layout re-derives, live-validates, caches,
and the client boots on a build it has never seen. Anything that did not
survive reports RECIPE-FAIL/LIVE-FAIL and disables its feature instead of
misreading memory.
