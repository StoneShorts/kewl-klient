# KewlKlient ImGui Migration Progress

Checked means: implemented, and verified (compiled / tested / probed offline, or live under Wine when
the section says so). Unchecked means not started or partial -- say which in the item.

Final verification pass, run 2026-09-05 against this exact tree, with the test line refreshed from
the last recorded run (`build/test-results/test/*.xml`, 2026-09-06):

- `gradlew dist test -q` -- BUILD SUCCESSFUL, **648 tests, 0 failures, 0 errors, 0 skipped**
  (2026-09-07, counted from `build/test-results/test/*.xml`). It was 524 before the widget-geometry,
  map-overlay, walker, menu-probe and panel-layout work of 2026-09-07, 402 across 40 classes on
  2026-09-06, and 332 across 30 classes on 2026-09-05.
- `sh tools/wine-setup.sh` -- zero errors (jar + kewlklient.dll + KewlKlient.exe into build/wine-dist/).
- `sh tools/launcher-smoke.sh --no-live` -- "offline strip: OK -- ImGui panel renders under Wine"
  (1600x900 frame, clear colour present, 54 rail-accent pixels, 134 toggle-ON pixels).
- The bridge round-trip probe (`tools/bridge-roundtrip-probe.cpp`, run under Wine against the real
  format-2 jar, cold and warm, plus the rejection path) -- `rt_probe: ALL PASS` (recorded by the
  integrator; see the Tests section below).
- The LIVE GAME TEST is the human's; see docs/testing.md's checklist.

## Architecture audit
- [x] Plugin inventory mapped: `KewlKlient.PLUGINS` (5 entries: PlayerVisuals, NpcVisuals,
      Woodcutter, RlitePlugin("Shortest Path"), RlitePlugin("Test Rlite")); the list is the registry,
      edit records name plugins by index into it.
- [x] Plugin-shaped-but-not-kewl-Plugin classes identified: `shortestpath.ShortestPathPlugin` and
      `kewl.rl.TestRlite` extend the shim `net.runelite.client.plugins.Plugin`, driven by
      `kewl.rl.RlitePlugin` adapters.
- [x] Persistence audit: **none exists**. `kewl.config.Setting`/`Config` are memory-only; no file,
      `Preferences`, or registry writes anywhere in `kewl` (the only `java.io` use is Theme's font
      load). `kewlklient.ini` is build-generated DLL config (JDK path), not plugin state.
- [x] `kewl.ui.Profiles` audit: real save/load/delete semantics over an in-memory
      `Map<name, Map<pluginName, Map<key, value>>>`, generated names, keyed by plugin *name*; no
      rename, duplicate, active-profile concept, or storage. No `ProfileManager` exists; the seam for
      one is exactly this class (plus `SettingDefaults` for per-profile defaults).
- [x] `kewl.ui.SettingDefaults` role: captures declared defaults at start-up (`SidePanel.setPlugins`)
      into an `IdentityHashMap`; backs the config panel's Reset buttons; resets go through
      `Setting.set` so listeners fire. No bridge edit kind reaches it from the launcher yet.
- [x] Lifecycle audit: no `PluginManager`; `KewlKlient` owns the registry, tick loop, F-key hotkeys
      and default-enable (hardcoded `instanceof` in `start()`); per-plugin `tick()`/`render()` and
      `onEnable`/`onDisable` are individually try/caught; `setEnabled` is final + idempotent and
      bumps `Plugin.enableVersion`; all edits queue through `Plugin.later` onto the frame thread. A
      plugin that throws every frame keeps throwing every frame -- no suspension.
- [x] Metadata audit: kewl plugins expose name/description/hotkey/status only. No id, version, tags,
      author, icon, pinned, hidden, external/built-in flag. `@PluginDescriptor` exists on the wrapped
      RuneLite plugins but is read nowhere at runtime (panel names come from `RlitePlugin`'s
      constructor args, so the metadata is duplicated by hand).
- [x] PanelBridge v1 format documented and pinned by `java-test/kewl/PanelBridgeTest` (9 tests: full
      decode against the real registry, kind table, 8-option cap, edit contract, modelRevision
      semantics, debugLines shape).
- [x] Swing audit: exactly one Swing file, `kewl/ui/Sidebar.java` -- dead code, no production
      references; one live test (`ConfigDefaultsTest`) reflects into it to prove 79 Shortest Path
      settings survive panel construction, so removing Sidebar means rewiring that test.
      `java/com` and `java/javax` are annotation shims only. All other `java.awt` use is Java2D
      overlays (keep).
- [x] Shortest Path audit: `RlitePlugin` builds the object graph eagerly via the hand-rolled
      `kewl.rl.Injector`; config proxied by the `ConfigManager` shim onto the adapter's `Config`
      (84 `@ConfigItem`s / 5 hidden / 7 sections + the kewl-native `autoWalk`); pathfinding runs on
      its own single-thread executor, results marshalled to the frame thread via `ClientThread`;
      bespoke UI state inventoried (cached draw/colour fields, static `configOverride`, marker,
      minimap clips, shift-clear key listener, `MenuPopup` fallback menu, `AutoWalk` driver).
- [x] Baseline verified: `sh gradlew test` clean re-run -- 290 tests, 0 failures, 29 classes.
- Written record: `docs/architecture-before.md`.

## ImGui foundation
- [x] Dear ImGui vendored under `third_party/imgui` (MIT, its `LICENSE.txt` kept in place next to the
      sources) (2026-09-05)
- [x] The panel renders under Wine without a GPU path: `tools/launcher-smoke.sh --no-live` drives a
      real frame in the launcher and checks pixels in the dumped PAM (clear colour present, the
      orange rail accent, green toggle-ON) (2026-09-05)

## Launcher
- [x] `launcher/main.cpp`: spawns osclient.exe, injects the DLL, embeds the game child, and hosts the
      panel's data half -- reads the shared-model region, writes the edit ring. `writeEdit` publishes
      the record BEFORE bumping head, so the DLL's plain read of head never names a half-written slot
      (2026-09-05)
- [x] The v2 model parser is bounds-checked: counts outside the caps and an activeProfileIndex
      outside -1..profileCount-1 are rejected rather than read off the end (2026-09-05)
- [x] `KEWL_FAKE_PANEL` builds a synthetic v2 region (pins, three profiles, hub entries exercising
      every button state) so the whole sidebar is probeable with no game and no JVM (2026-09-05)
- [x] Live: spawn + inject + embed against the real client -- on WINDOWS, against client-240-6
      fetched from the CDN, logged in. The strip carried the real registry through the bridge, the
      Shortest Path config view rendered every section, and edits made in it landed in
      `~/.kewlklient/profiles/*/config.json`. Four bugs the Wine pass could not see, all fixed
      (2026-09-05, see "Live on Windows" below)
- [x] `KewlKlient.exe --launch` / `KEWL_AUTOSTART=1` presses "+ client" itself (same startLaunch
      path, nothing bypassed) so a terminal or a script can go straight to the game (2026-09-05)

## Native sidebar
- [x] `launcher/panel_ui.hpp`: the full ImGui strip -- plugins, config, profiles, hub, debug -- plus
      the navigation stack, keyboard focus hooks, and optimistic echo of edits (2026-09-05)
- [x] Every tab probed offline under Wine via `KEWL_FAKE_TAB` dumps (PAM -> PNG, inspected):
      plugins rows with pin stars and toggles, profiles (active profile's orange edge), hub
      (warning/search/refresh/install/update/busy/remove states), debug lines, config controls
      (sections, combo, colour, text, slider, steppers, per-setting and per-plugin Reset) (2026-09-05)
- [ ] Live: hover/tooltip behaviour with a real cursor over the panel, and focus hand-off between
      game and panel (offline dumps have no interactive cursor; one dump showed the rows' tooltips
      merged into one window because the real X cursor happened to hover the rows -- ImGui appends
      same-frame tooltips, not a layout fault; the smoke frame with the cursor elsewhere shows all
      four rows rendering normally)

## Java UI bridge
- [x] PanelBridge FORMAT=2: pins, active profile, profile list, hub state/error/entries packed per
      `client/bridge.hpp`'s contract, with a mirror reader in `PanelBridgeTest` (2026-09-05)
- [x] The eleven edit-entry statics the DLL resolves (`resetSetting` ... `profileDuplicate`), each
      routed through its owning manager via `Plugin.later` -- signatures match `client/jvm.hpp`

## Plugin manager
- [x] `kewl.plugin.PluginManager`: the single owner of enable/disable transitions, exception
      isolation (a hook that throws leaves the plugin off and surfaces as a failure, never kills the
      tick loop), idempotence, register/unregister, clean shutdown (2026-09-05)
- [x] `Plugin` metadata defaults (`id`, `version`, `author`, `tags`) derived from the name; existing
      plugins unchanged and source-compatible

## Config persistence
- [x] `Setting.Sink`: every `set` notifies the installed store (default-capturing `reset()` added);
      writes are debounced 750ms on one IO thread, atomic, and store only non-default values (2026-09-05)

## Profiles
- [x] `kewl.profile.ProfileManager` + `JsonStore`: per-profile enabled/settings under
      `<dataDir>/profiles/<id>/config.json`, index.json for the list/active/pins, corruption
      quarantined to `.bad` and fallen back from (2026-09-05)
- [x] A profile is a complete statement: silence means off and code defaults. Pins are global.
      Migration is honest: nothing persisted before (`docs/architecture-before.md`), so first run
      creates one empty "default" profile (2026-09-05)
- [x] The Java2D Profiles tab now reads and writes the same store instead of its session-only map
      (`kewl.ui.Profiles` deleted) (2026-09-05)

## Plugin list
- [x] Pins, per-plugin toggles, search filter, F-key hints; a pin rides edit kind 6 and lands in
      ProfileManager (the probe flips isPinned 0 -> 1 through the real JNI resolve) (2026-09-05)

## Config panel
- [x] All five setting kinds drawn and editable (bool toggle, int slider/steppers, enum combo,
      colour, text), with per-setting Reset (kind 4) and per-plugin Reset (kind 5) -- verified in the
      offline config dump; the edits themselves are proven to land by `PanelBridgeTest` and the probe
      (2026-09-05)

## Plugin Hub
- [x] `kewl.plugin.hub`: `HubConfig` (manifest URL from `hub=` in kewlklient.ini / `KEWL_HUB` /
      `kewl.hub.url`; empty means "no hub configured", shown as an error state, not silence),
      `HubEntry` manifest validation (id, version, https/file artifact, SHA-256 required),
      `HubLoader` (child-first URLClassLoader over the shim; refuses a mainClass that is not a
      `kewl.Plugin`), `Hub` (async fetch/download/verify on one worker, installed.json, remove and
      update) (2026-09-05)
- [x] Documented honestly in `HubLoader`: the classloader is isolation for convenience, NOT a
      security sandbox -- kewl.* and the shim are identity classes by design (2026-09-05)

## ShortestPath
- [x] Its proxied settings surface in the native config panel through the bridge, LIVE on Windows
      against a logged-in client-240-6: the strip's Shortest Path view showed Auto-walk, the
      Transport Thresholds section (every threshold with its stepper and per-setting reset),
      Settings, Player-Owned House and Colours; toggling `drawDebugPanel` and `postTransports` in
      it landed in the profile store as `"settings":{"shortest-path":{...}}` (2026-09-05).
- [ ] Its overlays and the ported pathfinder in-game (a target set, tiles drawn, the world-map
      overlays): still not seen by the integrator. Auto-walk cannot act until `DO_ACTION` is
      derived (see Remaining limitations).

## Live on Windows (2026-09-05)

First run of the launcher path on real Windows (10 Pro, mingw-w64 g++ 15 / Ninja build, JDK 17),
against osclient.exe client-240-6 (sha256 d6a43c08...) -- the exact build offsets.hpp was measured
on, and the CDN's production release that day. What the Wine pass had not been able to show:

- [x] **The DLL never found the embedded game window.** On Windows the launcher reparents the game
      into its own window within milliseconds of injecting -- before the DLL's thread runs -- and
      `findEmbeddedGameWindow` only walked top-level windows owned by the GAME's pid. The game's
      root now belongs to the launcher's pid, so the walk found nothing for 60 s and `run()`
      returned silently; the strip sat on "waiting for the DLL bridge" forever. Fixed: walk every
      top-level window's children (pickEmbedded filters by pid), and log the give-up.
- [x] **The bridge mutex was named "Local\L-mtx".** `swprintf(L"%s-mtx", name)` under mingw-w64's
      C++ mode routes through its C99 formatter, where a wide format's `%s` means a NARROW string:
      it read the wide name as bytes. The mapping existed, the mutex did not, and the launcher
      (which requires both) never connected. Proven with OpenFileMappingW/OpenMutexW from a third
      process. Fixed: `std::wstring` concatenation, no wide printf anywhere in the names.
- [x] **No DLL diagnostics reached KEWL_LOG.** `freopen(log, "a", stdout)` in a DLL loaded into a
      console-less GUI process redirected nothing usable; two runs produced a JVM and a written
      profile store and not one DLL line. Fixed: `client/log.hpp` -- a kernel handle opened with
      FILE_APPEND_DATA, `kk::logf` WriteFile()s to it, and the same handle is installed as the
      process's STD_OUTPUT/STD_ERROR before the JVM starts so Java's System.out/err land in the
      same file. `-XX:ErrorFile` puts a JVM crash report next to it. The launcher now deletes and
      re-opens the log in append mode too (its "w" stream had been overwriting the DLL's lines).
- [x] **BUILD_ID was documented as a refusal and enforced nowhere.** offsets.hpp now carries
      `BUILD_VERSION = L"240-6"` (the PE FileVersion string the client states about itself, checkable
      before any game byte is read); `dllmain.cpp` reads the host exe's version resource and refuses
      to start Java on a mismatch, rendering the reason natively. `KEWL_SKIP_BUILD_CHECK=1` overrides
      for the deob hook-and-log workflow, loudly.
- [x] The launcher's bridge note now says WHICH step of `Bridge::open` failed (no mapping / mapping
      but no mutex / view / size / header) instead of "the DLL never created it".
- [ ] Shutdown: in the first (pre-fix) run the game died with an access violation in osclient.exe
      right after a close -- its own CrashLogDump.txt says "ImmediateRenderContext_OGL_WGL.cpp(321)
      after SwapBuffers() - Error (6) The handle is invalid" on the Deferred Render thread. Not yet
      reproduced on a run where the bridge was up; watch the Windows Application log on the next
      close (checklist item 19).
- [ ] Not observed by the integrator: Shortest Path's tile overlay and the world-map overlays on a
      logged-in client (the user's target-setting step), the right-click MenuPopup, hover/focus
      hand-off with a real cursor, profile switching mid-session, hub against a real manifest.

### Visuals pass (2026-09-05, from the first logged-in [proj] trace)

Code-level fixes against the trace above (`[proj] you@scene(71,76) -> (716.0,761.0) canvas=1606x900
cam=(9167,-852,8641) view=(900/1606, 900/1606)` and two more). Not run in-game yet.

- [x] **The [proj] line paired the view ints wrongly.** It printed +0x60/+0x5C and +0x24/+0x20 -- a
      height over a width on both axes -- which read as a 0.56 scale factor. Read in the roles
      offsets.hpp gives them, the live values are x 1606/1606, y 900/900: the leaf's final rescale is
      IDENTITY and both (W,H) pairs equal the JagRenderView client size, so the leaf returns canvas
      pixels and NO ratio correction belongs in projectFine (none was ever applied). Renamed the four
      constants `VIEW_IN_W/IN_H/OUT_W/OUT_H` (hex values unchanged), the probe now prints
      `view=x1606/1606 y900/900`, and WORLD_TO_SCREEN's note says "output space verified live, point
      accuracy pending".
- [x] **The probe projected the tile's south-west corner, not the centre**, so "does it land on the
      character" could not be judged (the 62-117 px x-offsets in the trace are the size of that half-
      tile shift at the logged depth). It now projects the centre (+64, like every real caller),
      prints `cam-you=`, `plane=` and the raw ints at entity+0x420 (ENTITY_PLANE, SUSPECT) and +0x7CC
      (the decompile's candidate) -- read-only, ENTITY_PLANE itself unchanged.
- [x] **Every projection is at height 0, which is the client's height DATUM, not the ground.** The
      camera height moved -852/-801/-849 between three spots at fixed pitch/zoom (horizontal offset
      -1151 in all three), so the terrain there is not flat and not proven to be at 0; the y spread
      725..761 tracks it. No tile-height offset is derived (offsets.hpp has none) and none was guessed:
      the "0 = ground level" comments in game.hpp / Game.java / Perspective.java now say datum, and a
      new `[proj] h-sweep mouse=(x,y) h0->(..) h-100->(..) ... h-400->(..)` line projects your tile
      centre at five candidate heights next to the cursor. KNOWN LIMITATION until a heightmap reader
      exists.
- [x] **Entity kind was decided by a per-entity PLAYER_IDS scan**, (#entities x #players) VirtualQuery
      reads per frame, and wrong under a uid shared between the player and NPC tables. `kk::Entity`
      now carries `player`, set by the walker from which table the node was in; `isPlayerUid` is gone;
      `localPlayer` matches the player table only, `findEntity(uid, player)` / `interactNpc` match
      by kind; `entityName` is `(IZ)` (uid, isPlayer) end to end (Natives.java, Entity.java --
      name cache keyed by (kind, uid) -- and the RL shim's Player.getName); Game.refresh drops only
      the PLAYER carrying your handle.
- [x] **NPC/widget names read the NxtString flag from the wrong address.** `nxtString(field, flagOff)`
      added flagOff to field, and three of four callers passed object-relative offsets, so the heap/
      inline flag came from entity+0xE37 / def+0x27 / IfType+0x2C7 -- unrelated bytes -- and an empty
      override could hide the def name entirely (blank NPC names) or a heap name return pointer
      bytes. The helper now takes the string's base only and reads +0x17 (static_assert pins
      IFTYPE_TEXT_FLAG == IFTYPE_TEXT+0x17). Names/widget text go to Java through
      `gameBytesToJString`: valid UTF-8 as-is, otherwise Latin-1 widened via NewString, so JNI never
      sees a malformed byte (the client's encoding is NOT VERIFIED).
- [x] **npcTypeId is range-guarded** (0..0xFFFF else -1): def+0x0 is NOT re-verified on 240-6, and a
      pointer fragment would otherwise ship as a huge id. The probe prints the nearest NPC's
      `uid def rawId id name` so the layout can be checked against a known NPC.
- [ ] **For the user to confirm in-game (KEWL_LOG set, standing still):** (1) the `[proj] ... centre
      -> (x,y)` point should sit at your character's feet and `view=` should read `x W/W y H/H`; if the
      point is off, say by how much. (2) Hover your own feet and read `h-sweep`: the `hN` whose y
      matches `mouse=` is the ground height there -- report it, and whether it changes as you walk.
      (3) Climb a staircase and watch `raw420` vs `raw7CC` step 0..3 (offsets.hpp ENTITY_PLANE
      switch). (4) Near a Banker, `nearest npc` should read a small `id` and `name="Banker"`; NPC
      labels in the overlay should no longer be blank. (5) In a crowd, boxes should keep up with
      moving entities (the PLAYER_IDS scan is gone).
- [x] Swing is GONE (2026-09-07). `kewl/ui/Sidebar.java` deleted; `grep -rl javax.swing java/`
      now matches nothing. The two tests that reflected into it (`ConfigDefaultsTest`,
      `IndicatorsConfigTest`) were rewired first, onto
      `java-test/net/runelite/client/config/ConfigPanelInvariants.java`, which asserts what the old
      Swing constructor enforced only by accident: an INT setting's value lies inside its own
      min..max, an ENUM's value is one of its options, and every setting carries the key, label,
      description and typed value a panel row reads. That is the actual regression -- an
      out-of-range `@ConfigItem` default threw in `JSlider`, `KewlKlient` swallowed it, and the user
      got no control panel -- stated as an invariant instead of as a constructor that happens to
      throw. The live serialisation path is covered separately by `PanelBridgeTest`, which decodes a
      real `PanelBridge.snapshot()` over the real registry, so every Shortest Path setting still
      goes through the code the launcher parses.

### Shortest Path pass (2026-09-05, code-level; not run in-game)

The user reported the Shortest Path port needs fixing (no symptom detail). Nothing in the `[proj]`
trace names the plane, so the plane findings below are CONDITIONAL on what the client reads there;
the projection/heightmap items above are the other half of "nothing on the path".

- [x] **The plane came from ENTITY_PLANE (0x420) alone, which this build's own decompile notes
      contradict.** offsets.hpp now has `ENTITY_PLANE_COORD = 0x7CC` (the `coord` binding's third
      field, next to the verified scene x/y; FUN_1400a06e0 indexes a per-level array with it) as a
      separate constant -- 0x420 is NOT flipped. `forEachEntity` reads both and prefers 0x7CC when
      it is 0..3, falls back to 0x420 when that is, else -1 -- a preference the staircase check must
      settle, not a guarantee (a small unrelated int at +0x7CC would now win). The probe's
      `raw420=`/`raw7CC=` now read via the named constants; `plane=` shows the winner.
      Decompile-backed, NOT VERIFIED in-game.
- [x] **-1 (plane unknown) was silently pathed as plane 3 and never drawn.** The vendored plugin
      packs the plane with `(plane & 0x3) << 30`, so a -1 start AND target landed on plane 3 (no
      walkable collision data) while every overlay compare (`getPlane() != unpackWorldPlane`) and
      `LocalPoint.fromWorld` could never match -1: target accepted, no tiles, no minimap marker.
      The RuneLite shim's `WorldView.getPlane()` now returns 0 for -1 (javadoc records why the old
      "never match -1" contract bought nothing without a heightmap), and `Player.getWorldLocation`
      takes its plane from the WorldView so a WorldPoint never carries -1. `kewl.rl.AutoWalk` still
      reads `Local.plane()` raw and keeps its own -1 handling (unfiltered walk over a plane-0 path
      is self-consistent). RlitePlugin logs `[shortestpath] plane unreadable (-1 from the client),
      assuming ground floor` once per login when it fires. Cost: upstairs with an unreadable plane,
      the path starts from the ground-floor tile under you. If the plane reads an in-range WRONG
      value this changes nothing -- only the staircase check catches that.
- [x] **Scene bound off by one.** `Game.toScene` (and the native entity filter) accepted scene index
      104; the scene is 0..103 (`Constants.SCENE_SIZE`, `WorldPoint.isInScene`). Now `>= 104`; the
      "0..104" comments in offsets.hpp / game.hpp / Entity.java say 0..103. Cosmetic -- it did not
      cause the live symptom -- but `WorldView.contains` now agrees with the shim's own isInScene.
- [ ] **For the user to confirm in-game (KEWL_LOG set):** (1) the strip's "you: x, y plane N" and
      the `[proj] ... plane=P raw420=A raw7CC=B` line on the ground floor -- is P 0, and which of
      A/B is 0? If the log shows `[shortestpath] plane unreadable`, both reads were out of 0..3.
      (2) Climb a staircase: the one of `raw420`/`raw7CC` that steps 0->1 is the plane; then
      offsets.hpp collapses to that single read (0x7CC is preferred until then). (3) Shift+right-click
      "Set target" on flat ground: path tiles and the minimap marker should now appear (at datum
      height -- see the heightmap item above for why they may sit below the ground); report if the
      tiles are still missing entirely, which would point at the projection rather than the plane.
- Skipped, recorded: the alternative stopgap "refuse to target when getPlane() < 0 and show a
      status string" (sp-shim-1 step 3) -- it conflicts with the normalisation above, would have
      coupled the vendored plugin to kewl.rl, and `RlitePlugin.status()` is overwritten by the
      auto-walk tick every frame so the string would not have shown. The heightmap reader
      (Perspective.getTileHeight) still returns 0: no offset exists and none was guessed.

### Actor surface pass (2026-09-05, code-level; not run in-game AT THE TIME)

> Superseded 2026-09-06: hulls, names and the actor diff were seen live -- "Seen working live" below.

The RuneLite shim now exposes NPCs and other players, not only the local player. Everything below
compiles, passes the unit tests (350 green, 18 new), and has NOT been seen in-game; the `Test Actors`
plugin exists to make the first live run diagnosable.

- [x] `net.runelite.api.Actor` (abstract, over kewl's per-frame primitives), `NPC`, `NPCComposition`,
      `Player extends Actor` (local player via `Supplier<Local>`, others via `Entity` snapshots),
      `IndexedObjectSet`, `ActorTable` (shim-only: stable identity per (kind, uid), re-pointed each
      frame; a uid reused with a different type id becomes a new object).
- [x] `Client.getNpcs()/getPlayers()` (local player first), `WorldView.npcs()/players()`.
- [x] `Perspective.getCanvasTextLocation/getCanvasImageLocation/getCanvasTileAreaPoly`, the pure
      `convexHull` (monotone chain) and `approximateHull` (a prism's 8 projected corners), and
      `OverlayUtil` ported. `getCanvasTilePoly(client, lp, int)`'s third parameter is now upstream's
      zOffset (it was named plane and ignored).
- [x] `NpcSpawned/NpcDespawned/PlayerSpawned/PlayerDespawned` from `kewl.rl.Events.fireActorChanges`,
      per hosted plugin, before `GameTick`; one `[actors] first frame: N npcs, M players, K names
      non-empty` line per login.
- [x] Local `Player.getLocalLocation()` switched from the tile centre to the fine render position
      (upstream's interpolated semantics; Shortest Path's line-from-player now glides). Revertible in
      `Player.getLocalLocation` alone.
- [x] Name policy: a non-empty name sticks; `""` is retried every 30 frames per actor, because
      `Entity.name()` re-walks the registry on every empty result.
- [ ] **Live checks the `[actors]` probe and the `Test Actors` status line enable** (`npcs=.. players=..
      named=K/N spawn=+a/-b pspawn=+c/-d me=(x,y,h) hull=200`):
      - Does the name text land above the head? `head@+200=(px,py)` vs `feet=(px,py)`: the head
        pixel should be roughly one model height above the feet. Tune the `Hull height` slider
        (feeds `Actor.setLogicalHeight`) until it does, then make that the default.
      - Does the hull enclose the model (`hull=6 pts` expected for a box seen at an angle)?
      - Do `spawn=+a/-b` move as NPCs walk in and out of the loaded scene? Do they explode on a
        scene re-centre (uid churn) -- expected, upstream does the same on region load?
      - Is the local player present in `players` (count = others + 1) and drawn green?
      - `named=K/N`: do NPC names appear at all (DEF_NAME / NxtString flag-byte fix), and do other
        players' names (`Natives.entityName(uid, true)`) read?
      - `tilePoly=null` for an on-screen NPC would mean the actor's height is off (compare `h` with
        `me=(..,h)`).
- Known limits, deliberate: every actor reports the local player's plane (no per-entity plane is
  packed; ENTITY_PLANE is SUSPECT); NPC size 1 / combat level 0 (no definition offsets beyond
  DEF_NAME); the hull is a prism, not a model; a uid reused for the same type id between two frames
  is invisible to the diff; `getCachedNPCs()` is not offered.

### NPC / Player Indicators ported (2026-09-05, code-level; not run in-game AT THE TIME)

> Superseded 2026-09-06: both are now DEFAULT-ON (`KewlKlient.defaultOn`) after the live pass below;
> only the name-list path is still unverified.

RuneLite's NPC Indicators (`net.runelite.client.plugins.npchighlight`) and Player Indicators
(`net.runelite.client.plugins.playerindicators`) ported source-shaped onto the actor surface above,
registered as two opt-in `RlitePlugin` lines (after Shortest Path, before the smoke tests; kewl's
own `NpcVisuals`/`PlayerVisuals` stay default-on). Compiles, `gradlew dist` passes, unit tests green
(WildcardMatcher, list parsing / id matching / (id,name) cache, both configs through the real
ConfigManager + Sidebar). Nothing has been seen in-game.

- [x] Shim additions: `ConfigManager` `double` items (RuneLite's `borderWidth`) become the int
      slider an `int` gets, `@Range` honoured; `Text.fromCSV/toCSV/sanitize` (nbsp / figure-space
      folding); `WildcardMatcher` (whole-string, case-insensitive, metachars literal);
      `Game.heightNear(sceneX, sceneY)` (ground height from the nearest entity within a tile, else
      the guess) feeding `Perspective.getCanvasTileAreaPoly(client, lp, size)`, plus an explicit
      height overload.
- [x] `RlitePlugin` no longer declares `Auto-walk` for every wrapped plugin: it is declared in
      `build()` only when the factory produced a `ShortestPathPlugin`, before injection, so Shortest
      Path's panel order is unchanged and the ports' panels hold only their own items. `Test Rlite`
      loses a toggle that did nothing (a profile that stored `autoWalk` under it keeps an orphan
      key, harmless).
- SUPPORTED: hull (prism), tile, true tile, SW tile, SW true tile, fill/border colours, border
  width 1-8, `NPCs to highlight` with `*`/`?` and (shim extension) plain NPC type ids, name above
  the head, own/others colours, name position (above / centre / right / disabled).
- APPROXIMATED: minimap names (north-up, fixed zoom -- yaw/zoom stubs); centre/right name positions
  (logical height 200 is a constant); `Ignore dead NPCs` is a no-op until `Actor.isDead()` reads;
  `PvP` highlight setting behaves as Disabled (`getWorldType()` empty).
- UNSUPPORTED, omitted from the panels with the reason in each config header: outlines/feather,
  menu-name colouring, dead-NPC menu colour, respawn timer, Tag/Untag/Tag-All/Tag-Color, per-name
  colours, friend / friends-chat / clan / team / party colours, clan rank icons, player menu colour.
- Offsets that unlock more: `DEF_NAME` (name entries start matching; the name overlay draws); a
  health/dead read (`ignoreDeadNpcs`, respawn timers); camera yaw + minimap zoom (minimap names sit
  right); the env()/world-type native (`PvP` setting); friends/clan lists (the omitted colours).
- Deliberate: Player Indicators keeps upstream's draw-nothing defaults (own and others Disabled);
  the status line says `others Disabled` so it does not read as broken. Flipping the default to
  Enabled is a one-token change in `PlayerIndicatorsConfig.highlightOthers`.
- [ ] **Live checks** (enable the plugin, type e.g. `Goblin, 3080` into `NPCs to highlight`; the
      status line reads `N npcs · K highlighted · names ok/empty · P patterns`):
      - `[npchighlight] startUp: ...` in KEWL_LOG with the pattern counts; `[npchighlight] all N NPC
        names empty (DEF_NAME pending)` after ~10 ticks is the expected line until DEF_NAME lands
        (id entries still highlight); `[npchighlight] NPC names readable: '<name>' id <id>` is the
        line to look for after.
      - Does the hull prism enclose the NPC, and the name (once readable) sit above its head?
        Both use `Actor.logicalHeight()` (200): the `Test Actors` slider tunes it live; record the
        residual here and make it the default.
      - Do true tile / SW true tile sit ON the ground under the NPC (they sample `Game.heightNear`)?
        A tile hanging in the air or sunk means the heuristic picked the wrong entity.
      - Border width slider 1-8 changes the stroke; fill alpha 20 is faint by design.
      - Player Indicators: set `Highlight own player` / `Highlight others` to Enabled. Status line
        `N players · K drawn · own set · others Enabled · names k/n`: `own set` proves the local name
        reads; `names k/n` > 0 proves other players' names read (`[playerindicators] other player
        names readable`), else the `... empty after 10 ticks` line names `entityName(uid, true)` as
        the pending piece and nothing draws for others.
      - Minimap names (both plugins, off by default): dots/names north-up, unrotated -- expected.

### Input injection + Autologin (2026-09-06, code-level; NOT run live)

The first thing the DLL pushes INTO the game. Design and reasoning in the "Input INTO the game"
section of `client/jvm.hpp`; user-facing notes in README "Autologin". Nothing here has been seen
in-game; the plugin is built to report its own state so the first live run can be diagnosed from
KEWL_LOG alone.

- [x] Four natives, all `PostMessageW` to NXT's `JagRenderView` child, no `SendInput` anywhere:
      `postChar` (WM_CHAR -- text never goes as a key-down, because the game's own TranslateMessage
      would pick the case from the physical shift state and emit a second WM_CHAR), `postKey`
      (KEYDOWN/KEYUP for Tab/Enter/Backspace with driver-shaped lParams), `postMouse` (move/down/up in
      canvas coordinates), `inputTarget` (diagnosis: exists/render-view/focused/foreground/size, with
      an optional probe-guarded SetFocus). Grounded in a read-only import scan of osclient.exe
      client-240-6: TranslateMessage/DispatchMessage/GetMessage/PeekMessage present, no raw input,
      ToUnicode, GetKeyboardState or SendInput. `[input] target %p class=%ls` is logged once per
      target change.
- [x] `kewl.api.Input` wrapper (no `typeText` on purpose -- callers pace across frames).
- [x] `kewl.plugins.autologin`: `Credentials` (raw-line reader of `~/.kewlklient/autologin.properties`,
      presence flags only ever printed, values leave only via `armInto` into the sequence's character
      queue), `InputSink` (the test seam), `LoginSequence` (pure state machine: settle -> optional
      clicks -> backspaces -> username -> Tab -> password -> Enter -> await the state leaving the login
      value; one slot per keyDelayMs, key/click = two slots; doubling back-off, maxAttempts, maxRejects,
      stop on raw 11; IDLE after a logout until loginNow(), or re-arm with `reloginAfterDisconnect`;
      NO_CREDENTIALS re-reads the file every 5 s).
- [x] `kewl.plugins.AutoLogin` (plain `kewl.Plugin`, F7, last in the registry, NOT in defaultOn()):
      reloads the file per attempt, logs flags and key/click counts only (never a character or
      step count -- a length is a fact about the password), catches a missing native once
      (`input natives missing -- rebuild the DLL`), draws the click-offset crosshairs and a status
      panel while not logged in.
- [x] Tests: `LoginSequenceTest` (18 as of the 2026-09-06 run: order, pacing, submit/login, back-off,
      rejections, idle-after-logout, authenticator, re-arm, click offsets, the play click, and that
      nothing the sequence ever prints contains the password or username) and `CredentialsTest`
      (8: presence flags, panel-over-file precedence, the half-filled fallback, verbatim
      backslashes, BOM, no value in any string); `ActorTableTest` gained the local-name-forgotten
      case. **402 green across 40 classes** (counted from build/test-results/test/*.xml, run
      2026-09-06).
- [x] 2026-09-06: username/password are AutoLogin panel settings (`config.secret` = TEXT + `secret`
      flag, bridge flags bit2, ImGui password field, masked in ConfigView and everywhere printed;
      panel wins when both set, else the file -- `Credentials.resolve`); tests for the flag, the
      codec round-trip and the precedence. Not run live.

- [x] **2026-09-06, run live** (client-240-6, launcher path, canvas = JagRenderView client area
      1314x900 in a 1600x914 launcher window). What was seen, and what changed because of it:
      - NXT letterboxes its title screen: ~1090x670 of drawn content, centred horizontally in the
        canvas but TOP-aligned. Click targets are therefore `x from the canvas centre, y from the
        canvas top` (settings renamed `existingX/Y`, `usernameX/Y`, `passwordX/Y`, new `loginX/Y`,
        `tryAgainX/Y` -- new keys on purpose so a profile's old centre-relative y cannot apply);
        the crosshairs use the same convention. Measured: Existing User (+69, 288), username field
        (-82, 234), password field (-82, 257), Login button (-93, 315), Try again (-14, 288).
      - Welcome box (New User / Existing User) -> form ("Enter your Old School RuneScape login
        details", Login/Password fields, "Remember username" ticked here, "Hide username", Login /
        Cancel). With Remember username ticked the Login field is PRE-FILLED and the caret starts in
        the PASSWORD field. New setting `usernameRemembered` (default on) replaces `typeUsername` +
        `clickUsername`: on = backspaces + password only; off = click the username field, backspaces,
        username, Tab (or click) into the password field, password.
      - Enter does NOT submit the form -- not a real keyboard Enter, not a posted one (jvm.hpp's
        `nPostKey` also posts WM_CHAR '\r' for VK_RETURN now; harmless, kept). Clicking Login DOES.
        Tab and Backspace work as posted KEYDOWN/UP. The script now ends with the Login CLICK (Enter
        kept before it as a free no-op).
      - The old script (username, Tab, password, Enter) typed the username INTO the password field
        and appended more on every retry; nothing ever submitted.
      - A rejection is a separate screen ("Incorrect username or password. If you have upgraded to a
        Jagex Account you need to log in using the Jagex Launcher instead.") with "Try again" and
        "forgotten password?"; the raw state stays 10 throughout (no 20/25 seen). So the result
        timeout with the state unchanged is now treated as that screen: counted against `maxRejects`,
        logged as `no state change after Login click: assuming the rejection screen, clicking Try
        again`, Try again clicked (new phase `DISMISSING_REJECTION`), settle, attempt re-run without
        the welcome click. At `maxRejects`: `rejected N times -- check the credentials, and whether
        this is a Jagex Account (the standalone client cannot log those in; ...)`. The state-changing
        bounce path (10 -> 20 -> 10) is kept for builds that do that. F7 login-now and IDLE-after-
        logout unchanged. Whether Try again leaves the caret in the password field was unverified
        here; the live pass below answered it -- a failed first attempt recovers through the
        Try-again screen on its own, so the caret is back in the password field (2026-09-06).
      - Tests: `LoginSequenceTest` rewritten for the new order (default path = Existing User,
        backspaces, password, Enter, Login click; Try-again path to give-up; not-remembered path with
        the password click and with Tab; the fresh-start welcome click; no password/username/count in
        any string). Deleted: `emittedOrderIsBackspacesUsernameTabPasswordEnter...`,
        `timeoutBacksOffWithDoublingDelaysAndGivesUpAtMaxAttempts` and
        `clickOptionsEmitMouseMoveDownThenUpAtCanvasCentreOffsets` (all encoded Enter-submits /
        timeout-means-nothing-typed / centre-relative y). README "Autologin" rewritten to the real
        flow. Not yet re-run live after these changes.
- [x] **2026-09-06, seen live: autologin works** (Existing User -> backspaces -> password -> Login
      click -> raw 10 -> 20 -> 25 -> 30). At 30 the client shows the "Welcome to Old School RuneScape /
      Welcome back" screen and the world only loads after "CLICK HERE TO PLAY" (canvas 1314x900 NOT
      letterboxed there; button centre (654, 334) = centre - 3, top + 334; the state stays 30 after
      the click and the [actors]/[proj] lines start). New settings `clickPlay` (on), `playX` (-3),
      `playY` (334), `playDelaySec` (2): one paced click via the script queue, scheduled only by the
      sequence's own login paths (SUBMITTED / leftTitle / BACKOFF -> 30), never for a manual login
      noticed from IDLE/GAVE_UP/NO_CREDENTIALS/authenticator; `playPending()` drives the in-game
      `play` crosshair until the click, then nothing is drawn. Log `clicked play`, status
      `logged in -- clicked play`. Two LoginSequenceTest cases. **Seen live the same day** over
      several cold starts (see "Seen working live" below) -- corrected here 2026-09-06, this bullet
      used to end "NOT yet run live".

- [x] **2026-09-06, THE DISCONNECT BUG and the screen-variant fix (code-level; NOT run live).** Seen
      live: autologin works from a cold start, but after a disconnect (30 -> 10, which is what
      `reloginAfterDisconnect` re-arms on) the client puts up a DIFFERENT screen -- the login form
      itself, username pre-filled, "Please enter your password", no welcome box. The script still
      clicked Existing User (not a button there), typed into whatever had focus, submitted an empty
      password field twice and stopped with `rejected 2 times`.
      - New `kewl.plugins.autologin.LoginScreen`: WELCOME / FORM / DISCONNECT / REJECTION / UNKNOWN,
        with exactly two decisions on it -- `clicksExistingUser()` and `focusesPasswordField()`.
        Those are the only two ways the scripts differ.
      - `LoginSequence.noteScreen(int[] loadedGroups, boolean verbose)` takes the client's own
        `Natives.loadedGroups()` every tick at the login state. `LoginScreen.fingerprint` sorts and
        dedupes them into one comparable string (a loaded game -- ~970 groups -- collapses to
        `many:N`), `LoginScreen.KNOWN` maps a fingerprint to a screen, and it **ships EMPTY**: no id
        has been confirmed against a real screen and a wrong entry would break the cold start that
        works. Each DISTINCT fingerprint is logged once under KEWL_LOG with what was believed, which
        is exactly the evidence the next run has to produce. Widget POSITIONS are deliberately not
        used (offsets.hpp: single-id x/y are not parent-accumulated).
      - Fallback for an unknown fingerprint, i.e. all of them today: `LoginSequence.provenance()` --
        arrived from the world (25/30/40/45/1000 -> 10) = DISCONNECT, arrived after a Try again =
        FORM, anything else = WELCOME. **The cold start is byte-for-byte what it was.** A server
        bounce (20 -> 10) is deliberately NOT treated as a disconnect: nobody has seen which screen
        follows one, so it keeps today's behaviour.
      - Non-welcome screens also click the password field (centre - 82, top + 257) before the
        backspaces, since nothing says where the caret is on a screen that was already up. This
        changed the post-Try-again script, and its LoginSequenceTest expectation with it.
      - Panel: a `screen` line, and the crosshairs now light up per screen (`existing` dim and `pass`
        bright on the disconnect screen).
- [x] **2026-09-06, opt-in direct field write (code-level; NOT VERIFIED, default OFF).** Setting
      `Set the fields directly (experimental)` (`setFieldsDirectly`). `kewl.plugins.autologin.FieldWriter`
      finds the username with `findString`, takes the password at `LOGIN_PASSWORD_DELTA` (508,
      recorded in `client/offsets.hpp` with how it was derived and mirrored in
      `FieldWriter.PASSWORD_DELTA`), and writes both through a new native
      `Natives.setLoginField(long, String, int)` -- the ONLY write into game memory in the DLL.
      Gates, all required: a username hit that pairs with a password hit across printable text is a
      document (our own profile config.json in the JVM heap, gap `","password":"`) and is discarded;
      the candidate password buffer must be all zeroes; exactly one candidate may survive; and
      `nSetLoginField` refuses unless the target is committed, PAGE_READWRITE (no VirtualProtect,
      ever), already holds all zeroes or exactly the value, has room in its existing content plus
      zero run, and is not shaped like an inline NxtString. Any refusal falls back to typing and says
      why in the status line. When it succeeds the script types nothing at all -- it only clicks its
      way to the form and clicks Login.
      - Tests: `LoginScreenTest` (fingerprint, book lookup, the two per-screen decisions),
        `FieldWriterTest` (a fake sparse memory carrying BOTH the client struct and the JSON document,
        so the printable-gap rule is exercised on the layout the live probe actually found; plus
        two-candidate, occupied-buffer, read-only and native-refusal paths, and a leak test over
        every status string), and seven new `LoginSequenceTest` cases. 428 green, was 402.
- [x] **2026-09-06, review: FieldProbe was still publishing the password's LENGTH.** The probe walks
      the NxtString slots either side of a username hit to find the password field, and an inline
      NxtString stores `0x17 - length` in its flag byte at `+0x17`. It printed that byte and the
      length decoded from it (`neighbour +24: flag=0d inline, length 10`), and by construction the
      neighbour it is hunting for IS the password -- so every KEWL_LOG run wrote the password's
      length into the log, and the closing line told the reader to match it against their own. It
      also dumped the 16 raw bytes before each hit as hex, which for a hit inside a document is the
      neighbouring field's bytes: the same shape as the dump that leaked a password on this build
      earlier the same day. Both are now verdicts: `FieldProbe.neighbour(flagByte, wantedLength)` is
      pure and answers with one of three fixed sentences (none containing a digit), and `shape()`
      classifies a stretch of memory as unreadable / zeroes / printable text / binary through the
      same `FieldWriter.gapIsText` the writer uses. The pair-gap classification also started at
      `min(u,p) + username.length()`, which put the read back inside the password's own bytes
      whenever the password was longer than the username -- it now steps past whichever hit comes
      first, matching `FieldWriter.pairsAcrossText`. New `FieldProbeTest` holds every flag byte
      0..255 against every length 1..64 and asserts only three sentences can ever come out and that
      none of them contains a digit. 432 green, was 428.

**SUPERSEDED (2026-09-06): the live run happened -- see "Seen working live" below.** The list is
kept as the shape of the log, but two lines in it no longer exist verbatim. Step 5 is now
`script done (K keys/clicks, Login clicked); waiting up to N ms for the state to leave 10`,
and the timeout branch below was replaced by `no state change after Login click: assuming the
rejection screen, clicking Try again` -- grepping KEWL_LOG for "timed out" finds nothing because a
rejection is logged under that new wording, not because the path never ran.

What the first live run had to show in KEWL_LOG, in order:

1. `[input] target 0x... class=JagRenderView` -- if the class is anything else, the messages went to
   the wrong window (`inputTarget()` falls back to the game root).
2. `[autologin] enabled: credentials: file, username set, password set; raw state N` (or `credentials: panel, ...`).
3. `[autologin] state 0 -> 10 (raw)` -- or whatever the title screen really holds on this build.
   offsets.hpp verified GAME_STATE only as 30 while logged in and "something else" at the form; the
   setter compares against 1,2,5,6,10,11,20,25,30,40,45,1000. A line `raw state X is not the
   configured login state 10` means: set the plugin's `loginState` to X, no rebuild.
4. `[autologin] attempt 1/3: target=JagRenderView focused=1 foreground=1 canvas=WxH (user set,
   pass set)`.
5. `[autologin] script done (K keys/clicks, Login clicked)` then `submitted; state 10 -> 20 after N ms` -- the
   proof that posted WM_CHAR + Tab + Enter reach the login fields.
6. `[autologin] logged in (state 30)`.

Failure branches to look for:

- (This line is now `no state change after Login click: assuming the rejection screen, clicking Try
  again`.) Nothing visibly typed: check `focused=`/`foreground=`
  in the attempt line (NXT may ignore input while its window is inactive -> try `grabFocus`), raise
  `keyDelayMs` if only some characters arrived (the login screen may drop bursts), try
  turning `usernameRemembered` off (which clicks the username field) if the field is not focused -- and if posted clicks do nothing at all,
  that is the GetAsyncKeyState risk: NXT imports it and may validate clicks against physical button
  state. A SendInput fallback is deliberately not written (it can type into another app).
- Characters doubled or wrong case: the WM_CHAR-only rule is what to re-examine.
- `server bounced us back to the login screen`: the typed text reached the server; check the
  password or a remembered username that the 20 backspaces did not clear (raise `clearBackspaces`
  or leave `usernameRemembered` on if the client prefills it -- superseded by the 2026-09-06 live notes above).
- `authenticator screen (state 11)`: expected stop, a human is needed.

Open questions for the user (the plugin exposes a setting for each so none needs a rebuild):
whether the username field is focused by default; whether a welcome/"Existing User" step precedes
the fields (click offsets are guesses drawn as crosshairs); the real raw title-screen value; whether
the client prefills the username and clears the password after a rejection; whether F7 toggling
(enable = login now, enable-while-running = abort) is acceptable or `Plugin` should gain an
`onHotkey()` that does not toggle; whether re-arming after a disconnect is wanted.

#### What the NEXT live run has to show (2026-09-06 screen-variant + direct-write work)

Run with `KEWL_LOG` set, `Log in again after a disconnect` ON, `Set the fields directly` OFF first.

1. **Cold start, unchanged.** `screen: WELCOME (from how we got here) -- clicking Existing User
   first`, then the same 10 -> 20 -> 25 -> 30 as before. If anything about the cold start moved, the
   screen work is wrong and nothing else matters.
2. **Disconnect (log out, or pull the network).** `state 30 -> 10`, then
   `screen: DISCONNECT (from how we got here) -- no Existing User click`, a click at
   (centre - 82, top + 257) before the backspaces, and 10 -> 20. **This is the reported bug**; if it
   still fails, the thing to report is whether the password field took the click (does the caret
   land in it?) and whether the backspaces cleared anything.
3. **The fingerprints.** Every `login screen fingerprint [ids] -- ...` line, one per screen, with a
   note of what was actually on the screen at the time. Cold-start welcome box, the form after a
   disconnect, the "Incorrect username or password" screen (fail one on purpose), and the form after
   Try again. Those four id lists go into `LoginScreen.KNOWN` and the provenance fallback stops
   being load-bearing. If every line reads `[no groups loaded]`, the title screen loads no interface
   groups on this build and the fingerprint idea is dead -- say so and the fallback stays.
4. **Then, and only then, `Set the fields directly` ON.** Expect either
   `fields set directly (NOT VERIFIED ...)` -- in which case the question is whether the form
   VISIBLY shows the username and a filled password field, and whether the Login click is accepted --
   or a `direct write: ... -- typing instead` line. Both are useful; the refusal line's counts say
   which gate stopped it. If the form comes up EMPTY after a successful write, the client re-blanks
   its buffers when it draws the form and the whole approach needs the write to happen after the
   Existing User click instead of before it.
5. **Do not report a value.** The counts and reason words in those lines are all that is needed, and
   they are all the code will produce.


### Seen working live (2026-09-06, Windows, client-240-6, logged in)

- [x] Autologin end to end from a cold start: Existing User click -> backspaces -> password -> Login
      click -> raw state 10 -> 20 -> 25 -> 30, credentials from the panel's masked fields. (Enter does
      not submit this form; the Login click does.)
- [x] Entity boxes on the entities: NPC visuals' boxes sit on the bankers behind the counter and the
      player box on the character, from the per-entity render position + height (ENTITY_FINE_*).
- [x] Names: Test Actors draws "Banker" over the bankers, other players' names in blue and the local
      player's in green; the [actors] first-frame line reported 10/11 NPC names non-empty. DEF_NAME
      (+0x8) therefore reads on this build -- the one blank (id 6521, dist 2) is a nameless NPC, and
      the def-string scan found no other string on it.
- [x] Hull prisms (logical height 200) enclose the banker models at this camera; not yet checked at
      other zooms.
- [x] "Click here to play" is clicked by the plugin after login (seen live, several cold starts); a
      failed first attempt recovers through the Try-again screen on its own.
- [x] Shortest Path end to end: shift+right-click -> kewl popup "Set Target" -> pathfinder
      (32-step path, TARGET_REACHED) -> red path tiles on the ground, the minimap line and the debug
      panel. Three fixes were needed for it, all seen and verified live the same day:
      (1) nInput polled the mouse buttons once per frame, so a click shorter than a frame never
      produced the up->down edge the popup keys off -- a WH_MOUSE hook on the game's window thread
      now latches presses (and the shift state at the press) until the next snapshot;
      (2) every hosted plugin's MenuPopup that ended up with no rows nulled the shared parked tile,
      wiping the one Shortest Path's popup had just parked -- it now restores what was there;
      (3) the plugin treated any non-null world-map container widget as "the map is open" -- a
      hidden container now counts as closed (NXT keeps that interface loaded). The game's own
      "Walk here" row sits under our "Set Target" row, so setting a target also walks there.
- [x] NPC Indicators and Player Indicators are the default visuals (2026-09-06): hulls and names on
      every NPC in range ("Banker" reads live; a nameless transform definition shows "#id"), names
      over players. Anti-idle taps a camera key every few minutes -- the server had logged the idle
      account out ("You were disconnected from the server"), which no login-side timeout can prevent.
- [x] The 42 confirmed findings of the whole-tree review (2026-09-06) are applied; 402 tests green.
      One was a BLOCKER: with KEWL_LOG set the launcher printed every WM_CHAR it received, so a
      password typed into the panel's masked field landed in the log the README tells users to
      collect. The three printfs are gone and the session's logs were scrubbed.
- [x] The local player was in the actor list twice (Game.players()'s exclusion and ActorTable's
      insertion disagreeing on the uid), so Player Indicators drew two name tags, one off the
      character. ActorTable now filters the duplicate and says so once.
- [x] Both name-tag defects verified fixed in-game (2026-09-06): one tag per player, own in cyan and
      others in red, NPC hulls and "Banker" labels correct, nothing left floating. The second defect
      was the minimap overlays: with "Draw names on minimap" on, every name was drawn at the canvas
      origin, because ClientState.getWidget takes a single packed id's stored x/y as canvas
      coordinates and accumulates no parent offsets (offsets.hpp records that assumption as
      unverified for exactly this widget). Perspective.localToMinimap now refuses a (0,0) minimap
      widget and logs the reason once, so the minimap overlays stay off until widget bounds are
      right rather than piling names in the corner.
### Autologin after a disconnect (2026-09-06, run live)

The reported failure: autologin worked from a cold start but not after a disconnect, which is exactly
when "Log in again after a disconnect" fires. The two screens differ -- a cold start shows the welcome
box with New User / Existing User, a disconnect drops straight onto the login form with the username
pre-filled and "Please enter your password." -- and the script assumed the welcome box, so it clicked a
button that was not there and submitted an empty password field twice.

- [x] The script now asks which screen is up instead of assuming one, and clicks Existing User only on
      the welcome screen. Verified live by logging out on purpose: `screen: DISCONNECT (from how we got
      here) -- no Existing User click`, and the account was back in the world without a human.
- [x] **The interface-group fingerprint is inert at the login screen, and this is the run that proved
      it.** `loadedGroups()` returns `[no groups loaded]` on the title screen (38 groups in the world),
      so no fingerprint can distinguish welcome from form from rejection. `LoginScreen.KNOWN` therefore
      stays empty for good and PROVENANCE -- how the client arrived at state 10 -- is the whole
      mechanism: from the world (25/30/40/45/1000) means DISCONNECT, after a Try again click means
      FORM, otherwise WELCOME. The fingerprint code stays because it costs one call and would come
      alive if the client ever loads a group there, but nothing should be built on it.
- [x] **LOGIN_PASSWORD_DELTA = 508 is REFUTED** (the note in offsets.hpp carries the detail). With the
      pair-gap classifier reading the right window, both candidate pairs are printable text -- our own
      profile config.json in the JVM heap -- so the client's login buffers were never among the hits.
      `FieldWriter` refuses every candidate, which is the designed outcome; the "Set the fields
      directly" setting stays off with nothing verified behind it.
- [ ] The first attempt on the disconnect screen still does not submit (the recovery does: Try again ->
      FORM -> submitted -> in the world, seen live). Something about that screen swallows the first
      script; the password-field click position is the prime suspect. Worth one probe run before
      calling autologin finished.

### Clicking felt broken in-game (2026-09-06, found and fixed live)

The user reported that clicks in the game misbehaved -- the world map would not close, the minimap
would not walk. Two separate causes, both ours:

- [x] **Our right-click popup opened on EVERY plain right-click**, on top of the game's own menu.
      We cannot suppress the game's menu (the menu struct is unread), so both were up at once and the
      user's next click hit ours instead of the game's row. Every entry any hosted plugin contributes
      today is shift-gated anyway (shortestpath adds "Set target" only with shift held), so
      MenuPopup now requires shift to open. Verified live: a plain right-click shows only the game's
      "Choose Option / Chop down / Walk here / Examine / Cancel", and shift+right-click still offers
      "Walk here / Set Target".
- [x] **Auto-walk clicked the ground every few ticks for as long as a path existed** -- with a
      170-step path that is a synthetic click every couple of seconds, for ever, which cancels
      whatever the user is doing. It is off by default again; a walker that clicks must not be left
      running with a stale path, and the pace setting is not a substitute for stopping.
- [ ] Open, for the ImGui input path proper: the launcher feeds ImGui the PHYSICAL mouse state via
      GetAsyncKeyState every frame as a "safety net" beside the WndProc events. That is a global
      read -- a click aimed at the game also registers in the launcher's ImGui, at a position inside
      the launcher's client rect because the game is a child window there. It has not been shown to
      cause a user-visible fault, but it is the wrong shape and should be replaced by a
      capture-aware forward.


### The Set-target regression, and a destroyed game copy (2026-09-07, later)

The user reported that setting a target had stopped doing anything and that the world map could not
be closed and panned to random places. Both were regressions from the same morning's work, and both
were reproduced in the running game before anything was changed.

- **Set target did nothing once the world map had been opened once.** `getSelectedWorldPoint` had
  been restructured into `if (!mapOpen) {scene} else if (useMap) {map} else {UNDEFINED}`. Upstream
  guards the scene branch only on "the container widget is null". `useMap` is unsatisfiable on this
  build because `WorldMap.refusalFor` demands a calibrated pixels-per-tile and the binary has no zoom
  field, so once the world-map GROUP was loaded -- which it stays, for the rest of the session, after
  the first open -- every right-click returned `UNDEFINED`. And `setTarget(UNDEFINED)` is not inert:
  it cancels the pathfinder, nulls it, drops the marker and clears `startPointSet`. Live proof that
  the container is the whole difference, taken with the map never opened: `mapContainer=null
  branch=scene` then `pathfinder finished: TARGET_REACHED, path steps=4`. Fixed by making the scene
  tile the answer whenever the map cannot claim the click, and by refusing to let a FAILED resolve
  tear down an existing target.
- **The map panning and refusing to close was the walker.** `DO_ACTION` is 0, so AutoWalk steers by
  posting real clicks; its map interlock leaned on the unverified `IFTYPE_HIDDEN` byte alone and
  failed OPEN. It now reads four signals and stands down on UNKNOWN as well as OPEN.
- **Settled, so nobody re-opens it:** the vendored plugin is NOT missing files. Both upstreams were
  cloned and diffed -- same 55 files, 47 byte-identical, resources byte-identical. The real gaps are
  the ones the shim names in its own log.

- [x] **A clean rebuild destroyed the only local copy of the game, and it was recovered.**
      `build/game/osclient.exe` held client-240-6, the build every offset here was measured against,
      and `build/` is deleted by a clean. It took the hand-edited `build/dist/kewlklient.ini` with it.
      No local copy survived (nearest: Steam 240-5, .rsprox 239-4, .obsidian 239-1). Recovered the
      same day by re-fetching from Jagex's Akamai CDN with the procedure in
      `.claude/skills/deob/SKILL.md` ("Getting the binary"): `alias.json` -> `osrs-win.production`
      digest -> `metafile.json` -> 9 `.solidpiece` blobs, each with its 6-byte Solid State Networks
      header stripped and gunzipped, concatenated in metafile order and split by each file's size.
      Production is STILL `client-240-6`, and the reconstructed `osclient.exe` came out at
      `sha256 d6a43c08...471e1` -- an exact match for the hash in `offsets.hpp`, which is the
      end-to-end proof that the reconstruction is byte-correct.
      It now lives at `<repo>/game/osclient.exe`, NOT under `build/`. `build.gradle` warns when a copy
      is found under `build/` and writes `game=` pointing at whichever location it finds.
      **NEVER run a clean or `--rerun-tasks` build in this repo.**
- [x] **Retested live, end to end, after the game was recovered (2026-09-07).** With the world map
      never opened, a shift-right-click on the scene resolves and paths:
      `branch=SCENE sceneTile=scene(52,45) ... pathfinder finished: TARGET_REACHED, path steps=5`.
      The new log line names the refusal, whether the click was inside the map, and the branch taken,
      so the next report of this can be diagnosed from one line.
- [x] **The walker was silently refusing to walk, and it is fixed.** Found only because the driver's
      status reaches the SIDE PANEL and nothing else, so a live run got as far as "the path is drawn,
      the character never moves" with no way to say why. `RlitePlugin` now echoes the status to
      KEWL_LOG on CHANGE (never per frame). It immediately said `arrived (5 from target)` on the first
      tick of a 6-step path. `ARRIVED_DISTANCE` was 5, chosen to mirror the plugin's
      `reachedDistance`, but the two measure DIFFERENT distances: the plugin's is to the TARGET, the
      driver's is to the END OF THE PATH, so any path shorter than the radius began inside it and
      every walk was a no-op. Now 1. Verified live: `holding: 3154,3468 (8 left)` counting down to
      `(4 left)`, and the player moved from `scene(54,49)` to `scene(56,41)`.
- [x] **A click on the OPEN world map no longer becomes a scene target.** The user reported it:
      "when i open the worldmap and choose a location in it. its fucked up because it chooses on the
      gamescreen not worldmap." The resolver now asks three separate questions instead of two --
      (a) is the map on screen, (b) did the click land on it, (c) can the point be inverted -- and a
      click that is (b) yes and (c) no, or (b) unanswerable, REFUSES with a stated reason rather than
      falling back to the scene. A target the user did not choose is worse than no target, and a
      refusal is one keypress from recovery. The KEWL_LOG line names which of the three failed.
      Question (a) reads presence only, never geometry, and the map-closed scene path is the FIRST
      statement of the branch table, above every line that mentions the map, so no geometry misread
      can break it; a 32-row exhaustive test fails if a future edit re-couples them.
      `-Dkewl.shortestpath.sceneUnderOpenMap=true` is the escape hatch if the hidden byte ever
      misreports a closed map as open.
- [ ] **The map-open branch is still not live-verified.** Neither physical nor posted clicks would
      open the world map, at any position tried around the minimap frame -- and the button's position
      was confirmed exactly, the game logging `h-sweep mouse=(1157,167)` with the cursor on it. So no
      one here has ever seen group 595 loaded, and the whole (b)-unanswerable branch is designed
      rather than measured.
- [x] **An upstream bug was found and a pull request prepared** (not opened: there is no GitHub
      authentication on this machine). Upstream's own `getSelectedWorldPoint` branches on whether the
      map widget EXISTS, so from the moment the map is loaded every selection is inverted through the
      map projection -- including a right-click on the scene with a windowed map open, which lands a
      target far from the tile clicked. The same class already does the containment test correctly a
      few hundred lines above, when deciding whether to OFFER the map menu entries. The patch makes
      the resolver ask the same question, and incidentally fixes a null dereference of
      `lastMenuOpenedPoint`. Verified: compiles clean, and upstream's own suite runs 293 tests with 1
      failure that reproduces identically on unmodified master. Target established from the
      repositories themselves: Runemoro wrote it, Skretzo maintains it (484 commits, pushed four days
      ago), Zoinkwiz's fork is 0 ahead and 20 behind, so it goes to Skretzo/shortest-path. The branch,
      patch, body and instructions are in the session scratchpad under upstream-pr/. Checked with the
      real credential values that nothing publishable contains either of them, or any trace of this
      client.
- [ ] **Still routing around quest-gated transports.** 64 distinct quests gate transports in
      `resources/transports/*.tsv`. `Client.runScript` now emulates `QUEST_STATUS_GET` from the
      client's own varps, but it reads its quest-to-varp mapping from `/quests.csv`, which does not
      exist, so every quest still answers NOT_STARTED. The seam is real; the data is not there.
- [ ] **An opaque black band covers the top third of the canvas.** Seen in a screenshot of the
      running client. Attributed by one agent to `layoutEmbed`'s `SetWindowPos`, but the verifier
      showed the projection and the drawn scene agree to within 3 tiles at y=412, which a viewport
      short at the top would not produce. Treat the attribution as an untested hypothesis.

### World map, minimap and the walker (2026-09-07, tested in-game)

- [x] **The world map went fully black and could not be closed -- it was OUR overlay.** Proven by
      bisection: with Shortest Path disabled the map opened, rendered and closed normally; with it
      enabled the map was black. PathMapOverlay was clipping and filling from a rectangle the shim
      cannot supply correctly (the container's bounds are parent-relative) at a scale that does not
      exist on this build (no zoom field). It now stands down and says so once. Verified after the
      fix WITH the plugin enabled: the map opens, renders correctly, and its close button works.
- [x] **Minimap overlays drew in the wrong place, and now refuse instead.** The minimap widget
      resolves (152x152) but reports x=53 on a 1314px canvas, while the minimap is anchored top-right
      -- a parent-relative x. That was previously only a WARNING beside a "minimap overlays are live"
      line; it is now a refusal, so nothing is drawn in the wrong corner. Closing this properly needs
      the widget's parent chain (ClientState.getWidget accumulates parent offsets only for an explicit
      3+ id path), which is the next concrete step -- an empirical right-margin guess was tried and
      does not hold: predicted canvas x 1109 against a measured ~1143.
- [x] Shortest Path targeting works: shift+right-click -> "Set Target" -> pathfinder runs
      (4-step path, TARGET_REACHED). The popup is shift-only, so a plain right-click belongs to the
      game again, and its rows now have a right edge -- a click past the row's width is correctly
      ignored rather than treated as a hit (that missing edge was the click-eating bug).
- [x] The walker walks: four targets in an earlier session moved the character 3185,3444 ->
      3182,3441 -> 3181,3444 -> 3174,3447, arriving each time, and it walked out of the bank.
      Auto-walk is OFF by default because a live path makes it click the ground every few ticks,
      which fights the user's own mouse.

- [ ] Still unverified: profile switching mid-session, and the hub against a real manifest.
- [ ] Known and unchanged: the game's own menu still handles a right-click we also act on (no way to
      suppress it without the menu struct), auto-walk cannot act while DO_ACTION is 0, and there is
      no terrain heightmap -- tiles away from the player use the player's own ground height.

## Tests
- [x] 325 green including the new suites: PluginManagerTest (lifecycle, isolation, reset, shutdown),
      ProfileManagerTest (persistence round-trip, CRUD, per-profile isolation, migration, corrupt
      fallback, debounce), HubTest (JSON parser, manifest validation, loader refusals),
      HubEndToEndTest (a plugin compiled into a jar by the test itself, served over file:, hashed,
      downloaded, classloaded, registered, removed) (2026-09-05)
- [x] `sh gradlew test --rerun-tasks` re-run clean by the integrator after the v2 work landed
      (BUILD SUCCESSFUL) (2026-09-05)
- [x] Byte-level round-trip probe `tools/bridge-roundtrip-probe.cpp` (integrator): under Wine against
      the real format-2 jar, PanelBridge.snapshot()'s int[] is decoded by an independent reader, the
      same bytes go through the DLL's `buildModel`, and an independent region decoder written from
      `launcher/bridge_layout.hpp`'s documented layout re-reads them -- every plugin, setting, pin,
      profile and hub entry agrees and the region is consumed to the last byte, cold (52800/52800)
      and warm (after profile CRUD + a pin) (2026-09-05)
- [x] The probe also covers the REJECTION path now: an empty snapshot (Java returned null), the
      v1-shaped 3-int header the old catch-all used to return, five truncations of the real
      snapshot, a wrong magic, a wrong format and an over-cap plugin count are all refused by
      `buildModel` -- re-run under Wine after the adversarial fixes, `rt_probe: ALL PASS` (2026-09-05)
- [x] `SettingCodecTest` (6 tests): colour byte order pinned (`#8000ff00` = alpha first, ARGB), the
      round trip across every alpha position, junk staying null, and the bool/int/text kinds;
      `ProfileManagerTest` gains "the thirty-third profile is refused, not created" (2026-09-05)
- [x] The probe also rings every edit kind 0..14 through both struct definitions (208 bytes each
      side) and dispatches the v2 commands at a real ProfileManager over JNI: create 1 -> 2, rename,
      duplicate + switch -> 3 profiles with active=2, delete back to 1, pin 0 -> 1; kinds 4/5/9 are
      consumed and dropped with the "no owner installed" log when PluginManager/Hub are absent --
      the designed behaviour (2026-09-05)

## Documentation
- [x] `docs/architecture-after.md` -- the after picture, mirroring architecture-before.md section for
      section: processes and build, the two runtime shapes, the bridge v2 contract (header/record
      offsets, the two string encodings, the model region, all fifteen edit kinds, ring protocol and
      failure behaviour), the new Java subsystems, the config lifecycle after, the spec's thread
      list with what actually runs on each thread, the ownership rules, the window lifecycle
      (collapse, nav stack, keyboard), and the before/after gap table. Every claim cross-checked
      against the code the same day (2026-09-05).
- [x] `docs/plugin-system.md` -- the spec's "Writing a plugin" preservation: the built-in example
      exactly as simple as today plus its one registry line, the metadata defaults table, every
      config type (bool/number/text/colour/enum + the shim-side section and keybind shapes), the
      lifecycle rules and who owns them, profiles (isolation, the pin-scope decision, storage
      layout), external plugins end to end (manifest fields and validation, install/update/remove,
      the honest "no separate sideload: use a file: manifest" statement), and the isolation
      statement in the spec's own words -- the classloader is convenience isolation, NOT a security
      sandbox (2026-09-05).
- [x] `docs/testing.md` -- every automated suite and what each test proves (332 tests across 30
      classes when written; refreshed to 402 across 40 on 2026-09-06, including what the suite
      deliberately does NOT cover), the offline probe commands
      (KEWL_FAKE_PANEL/CONFIG/TAB, KEWL_DUMP_FRAME/EVERY, launcher-smoke.sh, wine-setup.sh, the
      round-trip probe), and the manual integration checklist with the human's live steps for both
      the launcher path and the direct-inject fallback (2026-09-05).
- [x] `THIRD_PARTY_NOTICES.md` -- imgui (MIT, vendored under third_party/imgui with its LICENSE.txt),
      Shortest Path (BSD-2, resources/NOTICE-shortest-path + resources/LICENSE-shortest-path),
      RuneLite's vendored verbatim files (BSD-2, per-file attribution headers under
      java/net/runelite/), the hand-written shims that borrow package names, and the
      test-time-only Gradle deps. No notice invented; every pointer checked to the file it names
      (2026-09-05).
- [x] `README.md` -- updated to the new reality without losing the voice: the launcher embeds the
      client and draws the ImGui strip, Java still owns plugin state and draws the Java2D overlays,
      the direct-inject fallback with its Java panel is still there, the Java-half and native-half
      file lists name the new packages, the stale "ten native methods"/"two thousand lines"/
      "LAUNCH OSRS CLIENT NOW"/"saving settings" claims were corrected to what is true now (2026-09-05).

## Final audit
- [x] Three-sided layout agreement re-proven end to end after v2: `client/bridge.hpp` (writer),
      `launcher/bridge_layout.hpp` (reader, frozen constants + static_asserts), `PanelBridge`
      (packer) -- see the probe entries under Tests (2026-09-05)
- [x] Guard rails: the direct-inject flow is untouched -- `run()` in `client/dllmain.cpp` still finds
      the game window, builds the host, embeds the game and creates the Java2D panel popup when no
      launcher signalled; launcher mode only adds `notifyPanelMode` (a no-op on an older jar) and
      `bridge::start`, and skips exactly the window creation that would fight the launcher (2026-09-05)
- [x] No leftover v1 offsets: 13360/13352/13356/208 appear only as the documented layout comment,
      `static_assert`s, or the asserted `MODEL_OFFSET` constant (`launcher/bridge_layout.hpp` pins
      `MODEL_OFFSET == 13360`; `main.cpp` indexes everything through the constants). The only other
      grep hits are unrelated RuneLite gameval/ItemID constants (2026-09-05)
- [x] Builds clean: `sh tools/wine-setup.sh` (zero errors), `tools/launcher-smoke.sh --no-live`
      ("offline strip: OK -- ImGui panel renders under Wine") (2026-09-05)
- [ ] LIVE GAME TEST (the human's; everything above is offline): launcher-spawned client end to end
      -- panel shows the real registry, edits change plugin behaviour in-game, profile switch swaps
      enabled/settings mid-session, hub install/update/remove against a real manifest, overlays and
      shortestpath still work, direct-inject path unchanged (`wine_inject` into a standalone
      osclient.exe), cursor and focus over the panel

## Adversarial review fixes (2026-09-05)
Nine findings triaged; eight fixed, none judged wholly wrong. Verification: `sh tools/wine-setup.sh`
zero errors, `sh gradlew test` 332 green (was 325; +7 new), `tools/launcher-smoke.sh --no-live` OK,
and the byte-level probe re-run under Wine against the real jar (`rt_probe: ALL PASS`, now with a
rejection-path section).
- [x] `PanelBridge.snapshot()` no longer publishes a well-formed EMPTY model on a throwable: every
      section (pins, profiles, hub) is built into its own buffer and guarded on its own, a plugin
      whose walk throws gets a PLACEHOLDER record that keeps the plugin indexes intact (edits name
      plugins by index -- a dropped record would misroute every later edit), and the catch-all
      returns null, which the DLL reads as "no snapshot this revision" and keeps the last good model.
      `bridge.hpp`'s tick() retries a rejected revision at most once a second instead of consuming it
      silently, and the reject log now says what actually happened (snapshot size, keep-last-good,
      recovery line when it heals) rather than blaming the jar's format (2026-09-05)
- [x] `ProfileManager` no longer does file I/O under `lock`: the JSON snapshot is built under it and
      `JsonStore.write` runs after it is released -- on the IO thread for the debounced saves, on the
      calling thread for the rare forced flush. The class comment states the rule and why (`lock` is
      the same one `Setting.set` takes on the frame thread) (2026-09-05)
- [x] Edit-ring lap guard is `head - tail >= RING_SLOTS` in `drainEdits` (`==` is exactly the slot
      the launcher is about to overwrite), and the launcher's `writeEdit` now REFUSES to write into a
      full ring -- producer-side flow control, logged once per stall, with the next model publish
      re-syncing the widgets of anything dropped (2026-09-05)
- [x] `GetPrimitiveArrayCritical` in `nPresent`/`nPresentPanel` now covers the memcpy and nothing
      else: the DIB `ensure()` runs before the critical region and the GDI present
      (`UpdateLayeredWindow` / `blit_last`'s BitBlt) after it. `Layered` is split into `copyIn` +
      `show`, `panel::present` into `panel::copyIn` + `blit_last` (2026-09-05)
- [x] Caps are enforced where the counts are PRODUCED, so the DLL's all-or-nothing rejection never
      fires from real state: `PluginManager.register` refuses a 65th plugin, `ProfileManager`
      create/duplicate refuse a 33rd profile and `load()` truncates an over-cap index with a line,
      `Hub` truncates a manifest past 64 entries with a line, and `PanelBridge` clamps defensively
      anyway (plugin count, settings per plugin, profiles, hub entries) because a clamped panel beats
      a frozen one. Java's caps restate `client/bridge.hpp`'s numbers, each with a comment saying so
      (2026-09-05)
- [x] `SettingCodec` colour round-trip: alpha is stored ARGB (`Color.getRGB`, the order
      `Color.decode` parses) and decode now reads 8 hex digits as ARGB. Note: the reported failure
      mechanism was partly wrong -- `"#8000ff00"` is 9 characters, so the old `length() == 8` branch
      never matched and an alpha colour failed to decode ENTIRELY (null -> setting reverted to its
      default on every profile apply), rather than alpha landing in the blue channel; and the
      javadoc's "alpha LAST" convention was itself wrong. `SettingCodecTest` (6 tests) pins the byte
      order and the round trip (2026-09-05)
- [x] `SettingDefaults` deleted: it captured values AFTER `ProfileManager.install` had applied the
      active profile, so the Java2D panel's Reset restored profile values as "factory defaults" and
      disagreed with the launcher's reset edits. `ConfigView` now uses `Setting.reset()`
      (`Setting.defaultValue()`), which is the same source the kinds-4/5 edits land on
      (2026-09-05)

## Remaining limitations

Honest list of what is NOT done, or done but never exercised against the real thing. Nothing on this
list is claimed as complete anywhere else.

### Needs the human's live pass (no login was ever attempted, no packet built)

- The whole live game test: launcher spawn -> inject -> embed -> bridge carrying the REAL registry,
  edits changing plugin behaviour in-game, overlays and the ported Shortest Path still working. The
  offline probes cover the renderer, the parser, the bytes and the Java logic; they cannot cover a
  logged-in client. The checklist is docs/testing.md section 3.
- Live multi-profile flows: switching profiles mid-session and watching enabled states and settings
  swap on real plugins. `ProfileManagerTest` proves the store logic; only a logged-in session proves
  the UX (and the persistence of the debounced writes across a real process death).
- Live hub flows: no hub endpoint is configured by default (an empty `hub=` is an error state by
  design), and no public KewlKlient manifest exists. The full install/update/remove path is proven
  end to end by `HubEndToEndTest` over `file:`; exercising it against a real https manifest is
  whoever stands up (or points at) a manifest endpoint.
- Hover/tooltip behaviour with a real cursor over the strip, and focus hand-off between game and
  panel (offline dumps have no interactive cursor; one dump showed the rows' tooltips merged into one
  window because the real X cursor happened to hover the rows -- ImGui appends same-frame tooltips,
  not a layout fault).
- Resize/move/minimize/monitor-DPI behaviour of the embedded game + strip, which only shows up live.

### Deliberate design decisions, with reasons (reviewed, not oversights)

- `buildModel` still rejects an over-cap snapshot WHOLE rather than clamping the tail sections. The
  fix went to the producing side instead (see the caps item under Adversarial review fixes): a
  clamping reader would publish a model that looks complete while silently hiding state, which is a
  worse lie than a rejection that keeps the last good model and logs.
- The launcher's dropped edits are not queued for retry. The model re-publish re-syncs every widget
  to what Java actually holds, so a dropped click self-corrects visually; a retry queue would need
  its own ack protocol for no user-visible gain.
- `drainEdits` remains at-least-once (a crash between the JNI apply and the tail bump replays one
  edit). Unchanged by design -- a replayed Setting.set is idempotent at the value, and dropping
  would hide a change the user made.
- `HubConfig.manifestUrl()` still reads kewlklient.ini / client.json on the calling thread, which is
  the frame thread -- but only on an explicit refresh click, for two small files, with the fetch
  itself already on the hub worker. Not worth the async plumbing.
- Swing removal is NOT done, deliberately: `kewl/ui/Sidebar.java` stays as dead code -- the
  direct-inject path must not regress, and `ConfigDefaultsTest.declaringTheFullConfigSurvivesPanelConstruction`
  still reflects into it to prove the 79 Shortest Path settings survive panel construction. Removal
  is its own step with that test rewired. No Swing panel launches in either runtime shape.
- There is no separate "sideload" loader for external plugins -- one loader path, one set of
  validation rules; local development points a manifest at `file:` URLs (documented in
  docs/plugin-system.md, proven by HubEndToEndTest).
- A plugin that throws every frame keeps throwing every frame: `KewlKlient.tick` and the manager
  isolate the throw, but nothing suspends or unregisters a permanently broken plugin. Turning it off
  is the user's action.
- External plugin isolation is namespace isolation (child-first classloader), not security; stated
  in HubLoader's javadoc and docs/plugin-system.md rather than pretended away.

### RuneLite shim -- remaining offsets

What the shim still holds an honest default for. Each method names its own gap in the code
(`java/net/runelite/api/ClientState.java` unless noted); this list is the same story in one place.

- **The game menu struct.** `DO_ACTION` is 0 (not derived this build), so the game's own menu entries
  and click records are unread. `kewl.rl.MenuPopup` is the deliberate fallback design, not a stopgap
  left in by accident: it detects the right-click from the input snapshot, fires the same
  MenuOpened/MenuEntryAdded events RuneLite would, and draws the plugin-contributed entries itself.
  Its limits are stated in its header comment -- it never sees the game's own entries ("Examine" and
  friends) and cannot stop the game handling the right-click too.
- **Consequence for auto-walk: nothing can act, and now it says so.** With `DO_ACTION` 0, the DLL's
  `doAction` is a guarded no-op (`client/game.hpp` refuses to call a null RVA and prints a once-only
  "doAction dropped" line), and `kewl.api.Actions.walkTo` returns that false to the caller.
  `kewl.rl.AutoWalk` reports "cannot act: actions unavailable on this client build" in the panel
  instead of believing it walked. The movement still does not happen -- auto-walk cannot honestly be
  switched on in-game until `DO_ACTION` is derived -- but the failure is no longer silent.
- **Minimap zoom and camera yaw.** `getMinimapZoom()` returns 4.0 and `getCameraYawTarget()` returns
  0; both wait on offsets near the camera/viewport code (anchor: worldToScreenCoord's camera reads),
  so the minimap stays north-up-approximate.
- **World map: centre derived and live, zoom not.** The origin MapCoord is derived and VERIFIED LIVE
  (`client/offsets.hpp` WORLD_MAP / WM_ORIGIN_*), and the centre's coordinate space is pinned in the
  decompile: one scroll unit is 8 world tiles, so `centreTile = 8*WM_CENTRE = WM_ORIGIN + 48`
  (FUN_1401ce8b0/FUN_1401cefe0 write `origin = 8*centre - 48`; cross-checked live at the GE, scroll
  398,429 -> origin 3136,3384). `kewl.rl.Events.pushWorldMap` feeds that centre to the shim's
  `WorldMap` every frame. The +48 (centre vs load-window corner) follows from the symmetric +-6 load
  window, not from a live "which tile is under the widget centre" measurement -- worth one probe with
  the map open. Zoom is deliberately absent: the adversarial pass proved there is no zoom field
  anywhere in the world-map object (no "zoom" string in the binary), so the shim's placeholder 4.0f
  is what the map overlays' maths run on -- `PathMapOverlay` and `PathMapTooltipOverlay` draw at the
  correct centre but a guessed scale whenever the map widget is open. World-map markers
  (`WorldMapPointManager`, rendered by `OverlayRenderer`) additionally gate on the map data being
  live.
- **Widget bounds parent-relative assumption (unverified).** The widget native returns the x/y the
  widget stores, which are parent-relative for nested widgets. `ClientState.getWidget` adds parent
  offsets only on the nested-descend path (two or more ids); a single packed id -- which is how
  Shortest Path fetches `InterfaceID.Worldmap.MAP_CONTAINER` -- takes the stored x/y as canvas
  coordinates with no parent accumulation. That assumption has not been verified for MAP_CONTAINER
  specifically.

### Verification gaps in the test suite

- The probe's rejection-path checks run against a live snapshot's bytes, not against a Java-side
  throwable; there is no test that forces `snapshot()` to throw (that needs a plugin that breaks
  mid-walk injected into `KewlKlient.plugins()`, which is a fixed registry). The per-section guards
  are read-verified, and the null catch-all's DLL handling is what the probe's "empty snapshot is
  rejected" line pins.
- Nothing automated drives the launcher's C++ UI logic (nav stack, widget geometry, edit
  serialisation); it is exercised through the pixel probes and the byte-level probe instead. The
  spec's "extract logic from Win32 rendering so it can be tested independently" was only partially
  worth doing here -- the strip is one TU and the state is function-local statics by design.
