# Architecture before the ImGui migration

The baseline the migration spec asks for: what exists today, where the Java/native lines run, what
each plugin declares, and how config actually flows. Everything here was read out of the sources on
this branch and re-verified by a clean `sh gradlew test` (290 tests, 0 failures) on 2026-09-05. It is
the "before" picture -- the migration changes it, and this file says what there is to lose.

---

## 1. Processes and build

One Gradle project (`build.gradle`) drives both halves:

- **Java** lives in `java/` (not `src/main/java`), tests in `java-test/`, resources in `resources/`.
  Output: `build/dist/kewlklient.jar`. Java 17, external deps only lombok (compile-only, for the ported
  plugin's `@Getter`) and JUnit 4 + Mockito (test-only). Nothing external reaches the shipped jar.
- **Native** (DLL + launcher) is built by shelling out to CMake from the same Gradle file
  (`cmakeConfigure`/`cmakeBuild`), with `JAVA_HOME` pointed at the JDK Gradle runs on so `FindJNI`
  finds the matching `jni.h`. `writeIni` stamps the JDK path into `kewlklient.ini`
  (`Matcher.quoteReplacement` -- see the comment there for the backslash-eating bug it fixes).
- `gradlew dist` produces the shipping layout in `build/dist`: `KewlKlient.exe`, `kewlklient.dll`,
  `kewlklient.jar`, `kewlklient.ini` -- the DLL finds the jar next to itself. `gradlew run` builds and
  starts the launcher. On Linux, `sh tools/wine-setup.sh` sets up the C++ toolchain under Wine.
- `kewlklient.ini` holds exactly one setting: `java=` (the JDK path). It is **not** plugin state; the
  only plugin-adjacent things a user writes into it are coordinates they were told to keep there.

Two runtime shapes, chosen by whoever launched the game:

1. **Direct-inject (legacy, must keep working).** The DLL is injected into `osclient.exe` with no
   launcher. It creates its own host window, starts the JVM, and Java draws the control panel itself
   as a second layered window (`kewl.ui.SidePanel` -- 36px icon strip + 250px body, Java2D into a
   small image, presented through `Natives.presentPanel`).
2. **Launcher mode (the new path).** `KewlKlient.exe` (ImGui, `launcher/main.cpp`) spawns the game,
   injects the DLL, and reparents the game into its own window. It calls
   `KewlKlient.setPanelMode(true)` before the first tick; from then on Java draws **only** overlays
   (`SidePanel.frame` returns immediately, `panelMouse` never arrives) and the panel pixels belong to
   the launcher's software rasterizer.

## 2. The Java/native boundary

All of it is one JNI class plus one shared-memory region:

- `kewl.Natives` -- the JNI surface the JVM calls out through: `viewport()`, `present(pixels,w,h)`
  (overlay), `presentPanel(pixels,w,h)` (legacy panel window), `container(id)`, `input()`. Called
  from the frame thread only.
- `KewlKlient.tick(int keys)` -- the DLL calls this ~30x/sec. One call does everything:
  `Plugin.drainLater()` (queued edits), hotkey toggles from the keys bitmask, per-plugin `tick()`,
  then `render()` (overlay canvas + legacy panel).
- `kewl.panel.PanelBridge` -- the bridge. The DLL's thread pulls `PanelBridge.snapshot()` (a packed
  `int[]`, format below) over JNI and copies it into shared memory `Local\KewlKlientBridge-<pid>`;
  the launcher renders from that copy. Edits go the other way: the launcher writes a 208-byte edit
  record into a ring in the same region, the DLL reads it and calls `PanelBridge.setBool/setInt/
  setEnum/setText` (key `"enabled"` is the plugin switch, not a Setting). Every edit is queued through
  `Plugin.later` and applied at the top of the next frame -- the frame thread is the only thread that
  has ever touched plugin state, and the Java panel's clicks take the same road.
- `PanelBridge.modelRevision()` is what the DLL polls to decide whether the model is worth
  re-publishing: `(Setting.REVISION << 16) | (statusStamp << 8) | Plugin.enableVersion`. Status text
  changes with no Setting edit, and an enable flip changes no Setting at all -- hence three fields,
  none of which the DLL is allowed to decode.

## 3. Plugin inventory (the registry, in panel order)

`KewlKlient.PLUGINS` is a static `List.of(...)` -- the list **is** the registry. No scanning, no
annotation processor, no manifest. Index stability is load-bearing: edit records name plugins by
index into this list.

| # | Plugin (kewl name) | Class | Hotkey | Declares |
|---|---|---|---|---|
| 0 | Player visuals | `kewl.plugins.PlayerVisuals` | F1 | colour, tile, combat, range (4) |
| 1 | NPC visuals | `kewl.plugins.NpcVisuals` | F2 | colour, ids, showId, tile, range (5) |
| 2 | Woodcutter | `kewl.plugins.Woodcutter` | F5 | tree, x, y, delay, colour, panel (6) |
| 3 | Shortest Path | `kewl.rl.RlitePlugin` wrapping `shortestpath.ShortestPathPlugin` | - | `autoWalk` (kewl-side) + **79** proxied RuneLite settings across 7 sections |
| 4 | Test Rlite | `kewl.rl.RlitePlugin` wrapping `kewl.rl.TestRlite` | - | proxied shim smoke-test config |

Player visuals and NPC visuals are switched on by `KewlKlient.start()` -- a hardcoded
`instanceof` check, which is the current (weak) answer to "default enabled state".

**Behaves like a plugin but does not extend `kewl.Plugin`:** `shortestpath.ShortestPathPlugin` and
`kewl.rl.TestRlite` extend the *shim* `net.runelite.client.plugins.Plugin` (protected empty
`startUp`/`shutDown`, `setEventBus`). They never appear in `KewlKlient.plugins()` directly; a
`kewl.rl.RlitePlugin` adapter owns each one, and it is the adapter that is a kewl plugin. Everything
else in `kewl.rl` (`Injector`, `Events`, `AutoWalk`, `MenuPopup`, `OverlayRenderer`, `PathCheck`) is a
helper object, not a plugin.

## 4. Config lifecycle (today, end to end)

1. **Declare.** A plugin constructor calls `config.bool/number/text/colour/enumeration(...)` on its
   `public final Config config` (insertion-ordered `LinkedHashMap` of `kewl.config.Setting`). Each
   `Setting` carries key, label, description, kind, default, min/max, enum options.
2. **Enrich.** For an adapted RuneLite plugin, `net.runelite.client.config.ConfigManager` (shim)
   walks the `@ConfigGroup` interface and *declares the missing settings onto the same `Config`* --
   booleans, `@Range` ints, colours, enums, `Keybind` (stored as an F-index INT), text. Defaults come
   from the interface's **default methods**, invoked non-virtually via
   `MethodHandles.privateLookupIn + findSpecial` (a `findStatic` here once silently zeroed every
   default; `ConfigDefaultsTest` pins it). `Setting.onChange` posts a shim `ConfigChanged` on the
   EventBus, which is how a ported plugin notices panel edits.
3. **Recover what the flatten lost.** `kewl.ui.RlConfigMeta` re-reads the annotations by reflection
   (sections, item position, keybind-ness, `@Units`) -- the metadata `ConfigManager` throws away. The
   bridge packs it so the launcher can group and suffix without knowing about RuneLite annotations.
4. **Edit.** The only write path is `Setting.set(Object)`: bumps the static `Setting.REVISION`,
   clamps INTs to min/max, fires change listeners. The Java panel's slider drags and the bridge's
   `set*` methods both land here; nothing writes the value field directly.
5. **Reset.** `kewl.ui.SettingDefaults` captures every setting's declared default once, at
   `SidePanel.setPlugins` (start-up, before any input can touch a value), into an `IdentityHashMap`.
   `reset(plugin)` / `resetOne(setting)` push defaults back **through `Setting.set`**, so listeners
   fire exactly as if the user had clicked. There is no other notion of "default" anywhere.
6. **Persist. Nowhere.** There is no disk layer: no properties file, no `Preferences`, no JSON, no
   registry. The only `java.io` use in `kewl` is `Theme` loading a TTF font. Values live and die with
   the process, and the Profiles tab says so on screen rather than pretending otherwise.

## 5. Profiles (what actually exists)

`kewl.ui.Profiles` is a package-private static class -- real semantics, no storage:

- `Map<String, Map<pluginName, Map<settingKey, Object>>>`, insertion-ordered, in memory only.
- `save` snapshots every setting of every plugin under a generated name ("profile 1", "profile 2"
  ...) because the panel has no keyboard; `load` pushes a snapshot back through `Setting.set`
  (unknown keys skipped); `delete` removes. There is **no rename, no duplicate, no active-profile
  concept, no enable-state capture, and no persistence**.
- Keyed by plugin *name*, not index or id -- renaming a plugin silently orphans its snapshot.
- `kewl.ui.ProfilesView` is the only caller (the Java2D Profiles tab); the launcher's profiles tab is
  a placeholder. There is no `ProfileManager`; if the migration adds one, the seam is exactly this
  class: same operations, a real store behind them, and `SettingDefaults`' capture folded in (a
  profile that cannot say what the defaults were cannot implement "reset" per profile).

## 6. Lifecycle, threads, isolation

- One thread owns everything: the DLL's overlay/frame thread. `tick()` runs on it, the Java2D panel
  renders on it, all edits queue onto it (`Plugin.later`, a `ConcurrentLinkedQueue`, drained at the
  top of the frame by `Plugin.drainLater()`, each runnable individually try/caught).
- `Plugin.setEnabled` is final and idempotent (no-op on no change), bumps the static
  `Plugin.enableVersion`, and try/catches `onEnable`/`onDisable` -- a plugin misbehaving on a toggle
  must not take the client with it.
- `KewlKlient.tick` wraps every plugin's `tick()` and `render()` in its own try/catch: one broken
  plugin skips its frame, the others run. There is **no** further isolation -- a plugin that throws
  every frame throws forever (logged each time), and nothing suspends or unregisters it.
- There is **no `PluginManager`**. `KewlKlient` is the de facto manager (registry + tick loop +
  hotkey dispatch + default-enable), `SidePanel`/`PanelBridge` mutate only through
  `Plugin.later`-queued `setEnabled`/`Setting.set`, and that discipline is what a real manager has to
  formalise without changing.
- Adapted RuneLite plugins get one extra wrinkle: `RlitePlugin.onEnable` re-asserts
  `ClientThread.setGameThread(frame thread)` and normalises EventBus registration to exactly one
  subscription (enable/disable/enable must not stack subscribers); `onDisable` unregisters, resets
  AutoWalk, and removes exactly the overlays `startUp` added.

## 7. PanelBridge v1 packed format (the contract the launcher renders from)

Header `MAGIC` ('KKBR') + `FORMAT = 1`, then `pluginCount`, then per plugin:

```
enabled, hasConfig, hotkey
str name(63)  str description(159)  str status(159)
settingCount
per setting:
  kind (0=bool 1=int 2=enum 3=keybind 4=color 5=text)
  valueInt, min, max, enumIndex, optionCount (cap 8), flags (bit0 keybind, bit1 units)
  str key(63) str label(95) str description(191) str section(63) str valueText(63)
  optionCount x str option(47)
```

Strings are a byte-length word then UTF-8 packed 4 bytes per int, lowest byte first, zero-padded, no
terminator -- on little-endian x86 the C++ side memcpys straight out of the int array. Length caps
are applied in Java by `utf8`, on **byte** boundaries (never splitting a multi-byte character).
An exception anywhere in `snapshot()` degrades to a valid empty header rather than a hung panel.

Test coverage: `java-test/kewl/PanelBridgeTest` (9 tests) walks a snapshot with a reader that mirrors
the DLL's model copier -- asserts exact field order, the trailing-int "packer and docs agree" check,
per-setting kinds against a restated kind table, the 8-option cap, and the edit contract (edits run
on the frame thread only, land in `Setting.set` so listeners fire and `REVISION` moves, out-of-range
ints are clamped by the Setting not the bridge, bogus enum indices are dropped, unknown plugin/key is
ignored, `ENABLE_KEY` flips the plugin and bumps `enableVersion`, `modelRevision` moves for edits and
enables and is stable when nothing changed, `debugLines` shape).

## 8. Shortest Path (spec phase 8's subject)

- **Wrapping.** `kewl.rl.RlitePlugin("Shortest Path", ..., ShortestPathPlugin::new)` builds the whole
  object graph eagerly in its constructor via `kewl.rl.Injector` -- a hand-rolled, ~150-line stand-in
  for Guice that honours `@Inject` fields and `@Provides` methods and binds `Client`, `EventBus`,
  `OverlayManager`, `ConfigManager`, `ClientThread`, `KeyManager`, `SpriteManager`. Building eagerly
  is what makes the proxied config appear in the panel before the plugin is ever enabled.
- **Config.** `shortestpath.ShortestPathConfig`: `@ConfigGroup("shortestpath")`, 84 `@ConfigItem`s
  (5 hidden -> 79 kewl settings, the number `ConfigDefaultsTest` asserts), 7 `@ConfigSection`s.
  The adapter adds one kewl-native setting on top: `autoWalk` (default **on**, unlike upstream --
  see RlitePlugin's comment for why).
- **Threading.** Pathfinding runs on its **own** single-thread executor (`shortest-path-%d`, via the
  guava `ThreadFactoryBuilder` shim), created per `restartPathfinding` under `pathfinderMutex`;
  results come back through callbacks and `ClientThread.invokeLater`, which queues to the frame
  thread and drains at the top of the next frame (`ClientThread.drain()`). Everything else --
  events, menu popup, auto-walk, overlays -- is frame-thread.
- **Bespoke UI state** the refactor must respect: the cached `drawTiles`/`drawMap`/... colour/style
  fields (`cacheConfigValues` on startUp, not live reads), the **static** `configOverride` map fed by
  plugin messages, `pendingTasks`, `lastMenuOpenedPoint`, the world-map `marker`, the cached minimap
  sprites and resizable/fixed clip shapes, the shift-clear `KeyListener`, and the menu-target colour
  strings. On the kewl side, `kewl.rl.MenuPopup` draws a Java2D right-click fallback menu and re-fires
  the menu events (the real game menu is still not readable -- `offsets.hpp` `DO_ACTION`),
  and `kewl.rl.AutoWalk` walks the computed path one game tick at a time, stopping at plane changes,
  surfacing its state as the adapter's `status()`.

## 9. Swing audit (spec: remove cleanly)

- **`kewl/ui/Sidebar.java` is the only Swing in the repo** -- JFrame, JPanel, JSlider, JColorChooser,
  SwingUtilities, javax.swing.Timer. It is the pre-overlay control panel and it is **dead code**: no
  production reference remains (SidePanel replaced it; its javadoc explains why). It still compiles
  into the jar.
- One live dependency on it: `java-test/.../ConfigDefaultsTest.declaringTheFullConfigSurvivesPanelConstruction`
  reflects into `Sidebar.controls(Config)` to prove all 79 Shortest Path settings can be declared and
  rendered without throwing. Deleting Sidebar means rewiring that test to the ImGui-side equivalent
  (or to ConfigView) -- the assertion to preserve is "79 settings survive panel construction".
- `java/com` and `java/javax` are Guice/javax shims (`Inject`, `Provides`, `Nonnull`...) -- annotations
  only, no Swing. Everything else that uses `java.awt` uses the **Java2D/Graphics2D half** (overlays,
  panel canvas, fonts, `Color`), which the spec says keep. One deliberate AWT object in live code:
  `RlitePlugin`'s dummy `java.awt.Panel` as a `KeyEvent` source (a KeyEvent refuses a null source).

## 10. What the migration starts from (gap list)

| Spec wants | Today |
|---|---|
| PluginManager owning state transitions | `KewlKlient` statics + `Plugin.later` discipline |
| Plugin metadata (id/version/tags/author/pinned/icon/hidden) | name, description, hotkey, status only; `@PluginDescriptor` exists on the wrapped RL plugins but is read nowhere |
| Persistent settings + enabled state | nothing is written anywhere, ever |
| Profiles with real storage, active profile, rename/duplicate | in-memory map, generated names, load/save/delete |
| Pinned plugins | does not exist |
| Plugin Hub | does not exist |
| Panel model v2 (pins, profiles, hub) | PanelBridge v1: plugins + settings only |
| Reset setting / reset plugin edits | exists in the Java panel (`SettingDefaults`) but has **no bridge edit kind** -- the launcher cannot reach it |
| Swing removal | one dead file (`Sidebar`) + one test hooked to it |
| Search, icons, navigation | none in the Java panel; the launcher has the tab strip only |

---

# Native architecture

Audited files: `client/bridge.hpp`, `client/dllmain.cpp`, `client/jvm.hpp`, `client/overlay.hpp`,
`client/panel.hpp`, `client/imgui_sw.hpp`, `launcher/main.cpp`, `launcher/panel_ui.hpp`,
`launcher/bridge_layout.hpp`, `CMakeLists.txt`, `tools/wine-setup.sh`. Baseline confirmed to
compile with `sh tools/wine-setup.sh` after the audit (jar + `kewlklient.dll` + `KewlKlient.exe`
into `build/wine-dist/`).

## N1. The bridge, as actually implemented (v1)

### Byte layout

Both sides compile a hand-written description of the same bytes: `client/bridge.hpp` (the DLL, which
creates the mapping and writes) and `launcher/bridge_layout.hpp` (a restatement, because bridge.hpp
pulls in jvm.hpp and the launcher must not compile JNI). `static_assert`s pin every offset on BOTH
sides, so drift breaks a build rather than a session:

```
offset      0  u32 magic 'KKBR' (0x4B424252)
offset      4  u32 format (1)
offset      8  u64 launcherHwnd
offset     16  u64 dllMsgHwnd          (hidden fallback top-level; HWND_MESSAGE fails under Wine)
offset     24  i64 modelRevision (volatile, InterlockedExchange64, under the mutex)
offset     32  i64 editSeq       (volatile, launcher-owned, InterlockedExchange64)
offset     40  EditRecord edits[64]    (208 bytes each)
offset  13352  i32 head (volatile)     launcher-only
offset  13356  i32 tail (volatile)     DLL-only
offset  13360  MODEL REGION            = sizeof(Header); MAPPING_BYTES = 32 MB
```

`EditRecord` is `#pragma pack(push,4)` `{ i32 kind; i32 pluginIdx; char key[64]; i64 intVal; char
text[128]; }` — 208 bytes, `intVal` naturally 8-aligned at record offset 72, no padding anywhere.
The v2 contract keeps this record shape exactly; only the `kind` vocabulary grows.

### String encoding, two different kinds

This is the one spot where the two regions look similar but are NOT the same encoding:

- **Model region** — fixed-width NUL-padded UTF-8 fields (`putField`: `truncUtf8` to `field-1`
  bytes, cutting back over continuation bytes so a character is never split, then zero-fill to
  `field`). Widths: plugin name 64, desc 160, status 160; setting key 64, label 96, desc 192,
  section 64, valueText 64, option 48. The launcher's `Reader::str(width)` reads with `strnlen`.
- **The Java→DLL snapshot** (`PanelBridge.snapshot()`, the `int[]` `buildModel` consumes before
  anything is written) — length-prefixed UTF-8, four bytes per int, lowest byte first, padded to a
  whole number of ints so the advance is `ceil(n/4)` not `n` (`Reader::str`). Getting that wrong
  desynchronises the rest of the snapshot; the `count()` guard rejects anything over the cap rather
  than clamping. v2's new sections must use the same two encodings in the same places: fixed fields
  in the region (`char name[96]` etc. for hub entries, `char name[64]`/`char id[64]` for profiles)
  and len-prefixed ints inside `PanelBridge.snapshot()`'s output.

### Model region contents (v1)

`i32 pluginCount`, then per plugin: `i32 enabled, hasConfig, hotkey`; fixed name/desc/status;
`i32 settingCount`; per setting: `i32 kind (0=bool,1=int,2=enum,3=keybind,4=color,5=text),
valueInt, min, max, enumIndex, optionCount, flags (bit0=keybind, bit1=hasUnits)`; fixed
key/label/desc/section/valueText; then `optionCount` (cap 8) fixed option[48]. Caps: 64 plugins,
256 settings/plugin (Shortest Path alone declares 80 — the cap is a desync tripwire, not a design
limit). The launcher's `readModel` is deliberately stricter about `optionCount` (>8 rejects the
whole region) but looser about counts generally (4096); v2 should tighten those to the real caps
and add caps for the new blocks.

### Ring protocol — who writes what under which lock

- **The mutex guards the MODEL REGION only.** `publishModel` does its JNI work (the `snapshot()`
  pull, `bridgeSnapshot()`) *before* taking the mutex, so the critical section is one memcpy of a
  few KB plus an `InterlockedExchange64` of the revision; the launcher waits at most 2000 ms,
  treats `WAIT_ABANDONED` as "launcher died mid-write, carry on".
- **The edit ring is deliberately lock-free SPSC.** Launcher: write the whole 208-byte record into
  `edits[head % 64]` (a plain struct assignment), *then* `InterlockedExchange` head to `head+1`,
  then bump `editSeq`, then `PostMessageW(dllMsgHwnd, g_msgEdit)`. The store order is the safety
  argument: the DLL's plain read of head never names a slot whose record is not fully written.
- **DLL drain** (`drainEdits`, on the tick loop): reads head/tail with plain loads; copies a record
  out to a local before the JNI call (the launcher may overwrite the slot mid-call); advances tail
  only after the edit is *consumed* (delivered or rejected by Java) — at-least-once, never
  retried-on-reject, because a rejected edit would burn the ring. If `head - tail > RING_SLOTS`
  the backlog is dropped wholesale and the next model publish re-syncs the UI; chasing 64 stale
  edits would replay old values over new ones.
- **The edit-notify message only sets a flag** (`msgProc` → `g_editsPending`). The message-only
  window FAILED to create under Wine (traced: `HWND_MESSAGE` returns null with GetLastError left
  at 0; the WS_POPUP hidden fallback is used instead) and is treated as optional latency
  optimisation either way — the tick loop is the real pump.
- Telemetry reads (`editSeq` in `drawPanel`, the header note) happen without the lock; they are
  display-only and torn values are harmless.

### The reserved key

`EDIT_BOOL` with key `"enabled"` (`PanelBridge.ENABLE_KEY`) routes to `Plugin.setEnabled`, not
`Setting.set`. Everything else must name a Setting and lands in `Setting.set`, which is why the RL
ConfigManager shim's change listeners fire. v2's `SET_PIN` is the second non-Setting concept (it
edits pinned state, not config) and needs the same "named, not keyed" treatment in `PanelBridge`'s
dispatcher.

### Every place v2 must touch

1. `client/bridge.hpp`: `BRIDGE_VERSION` → 2; `enum EditKind` grows 4..14 (RESET_SETTING,
   RESET_PLUGIN, SET_PIN, HUB_INSTALL, HUB_REMOVE, HUB_REFRESH, PROFILE_SWITCH, PROFILE_CREATE,
   PROFILE_DELETE, PROFILE_RENAME, PROFILE_DUPLICATE); new caps `MAX_PROFILES=32`, `MAX_HUB=64`;
   `buildModel` emits `pinned[pluginCount]`, the profiles block, the hub block after the plugin
   loop (the `Reader`/`i32`/`str`/`count` machinery is reusable as-is); `MAPPING_BYTES` stays 32 MB
   (worst legal model grows by ~4 KB profiles + ~28 KB hub — nothing).
2. `client/jvm.hpp`: `bridgeResolve` gains method IDs for the new `PanelBridge` statics;
   `bridgeApply`'s `switch (kind)` gains a case per new kind (the record already carries
   pluginIdx+key+intVal+text, which is exactly what every new edit needs — `PROFILE_RENAME` =
   index+text, `RESET_SETTING` = index+key, `HUB_*` = pluginIdx −1 + text id). The unknown-kind
   path (log once, drop, never retry) must stay the default for any kind a half-matched jar lacks,
   or an old jar wedges the ring on the first v2 edit.
3. `launcher/bridge_layout.hpp`: `VERSION` → 2, the same enum and caps, hub field widths
   (id 64, name 96, version 32, author 64, desc 160), flags (bit0 installed, bit1 hasUpdate, bit2
   installing). The `Header` asserts are unchanged — that is the point of keeping the record and
   header shape frozen.
4. `launcher/main.cpp`: `readModel` parses the three trailing blocks under the same bounds-checked
   `Reader`; `loadFakePanelModel` must emit them or the fake-panel probe cannot exercise the new
   views.
5. `launcher/panel_ui.hpp`: `Model`/`PluginModel` gain pinned, profiles, hub; `profilesView` stops
   being a placeholder; the Reset button (currently drawn disabled with a tooltip saying "no reset
   edit kind in the bridge contract yet") becomes live — that comment names it as the intended
   landing place; `pluginRow` gains the pin control; `commit()`'s optimistic echo grows a pinned
   array to echo into.
6. Java (`PanelBridge.snapshot` FORMAT → 2, the eleven new statics, the manager routing) — the
   Java-side audit covers what exists; the contract above is what the native side must be written
   against.

## N2. The launcher's panel UI structure

`launcher/panel_ui.hpp` is header-only (one TU includes it), and all its UI state lives in
function-local statics — exactly as thread-unsafe, and as sufficient, as the single-threaded pump
that drives it:

- `uiTab()` — `TAB_PLUGINS`/`TAB_PROFILES`/`TAB_DEBUG`, changed only by the rail buttons.
- `uiPushed()` — the pushed config view's plugin index, or −1. This IS the navigation model: there
  is no stack, no history. The back arrow and the Back button both set it to −1; clicking another
  plugin's name or gear overwrites it; selecting a rail tab clears it. A pushed index that no
  longer names a plugin (game died mid-view) self-un-pushes rather than drawing a stale slot. The
  spec asks to "preserve navigation history within a panel" — today there is exactly one level,
  and any deeper nesting needs a real stack next to `uiPushed()`.
- `uiResetArm()` — ConfigView's click-again-to-confirm arm, cleared on every navigation.
- `uiEditsSent()` — session edit counter for the debug tab.

Views, all drawn inside `draw()`: a 250px body window (`##kewl.body`) plus a 36px rail window
(`##kewl.rail`), positioned at `dispW - PANEL_W` / `dispW - RAIL_W` against the ImGui display size.
Two separate windows rather than children of main.cpp's root because the root is
`ImGuiWindowFlags_NoMouseInputs` (clicks over the game child must stay the game's) and a
NoMouseInputs parent is skipped by hit-testing while plain windows are not. The body holds a fixed
header (name + bridge note + separator) and a `##kewl.scroll` child that draws either the pushed
`configView` or the active tab's `pluginsView`/`profilesView`/`debugView`. `profilesView` is a
placeholder paragraph; `debugView` shows bridge-side numbers only (the memory-read lines are Java's
`debugLines` and not in the v1 contract). Plugin rows are hand-laid-out (`SetCursorScreenPos` +
`InvisibleButton` + draw-list glyphs, `endRow` pads with a Dummy so the scrollbar measures real
heights), sorted alphabetically by a `sortedOrder` that keeps Java's declaration indices — the
indices the edits travel by.

**Optimistic echo.** `commit()` mutates the *local parsed copy* (`main.cpp`'s `g_plugins`, handed
to `draw()` as a mutable pointer) first — toggle flips `enabled`, enum echoes the option string,
int loses its `@Units` suffix for one frame — then enqueues the edit. The next `modelRevision` bump
overwrites the echo with truth, so a value Java rejects reads wrong for at most one refresh. v2's
pin toggle needs the same treatment (echo into a local pinned array); profile switching and hub
actions have no local value to echo, so their feedback is the next publish (which is also why the
hub's `installing` flag exists in the contract — it is the "your click was heard" signal).

**Where search / pin / reset / profiles / hub slot in.** Search is a body-header widget that
filters `sortedOrder`'s output (RuneLite filters, it does not re-sort); it needs keyboard focus in
the strip, which the launcher already owns (`WM_CHAR` → `AddInputCharacter`) but which competes
with the game child holding focus — see N3. Pin is a second control in `pluginRow` right-aligned
next to gear/toggle (the row's right edge is already budgeted as `avail - 92.0f` for name clipping
and `TOGGLE_W + 20` for the controls — adding a control means re-budgeting that arithmetic in one
place). Reset is the existing disabled button in `configView`. Profiles replaces the placeholder
body of `profilesView`. Hub is either a fourth rail slot (the rail loop is `for i < 3` with 3px
active-edge painting — adding a tab is a loop bound plus a `tabIcon` case) or a pushed view off the
profiles tab; the rail is 36px cells with a 3px orange left edge and RL_TAB hover, so a fourth icon
fits without layout change.

**KEWL_FAKE_* probes.** `frame()` checks `KEWL_FAKE_PANEL` once: it skips all launch machinery,
forces `Phase::Embedded`, and on the first frame builds a synthetic model region in
`loadFakePanelModel` — bytes laid out exactly as `buildModel` writes them, then fed through the
REAL `readModel` (that parser is the code most likely to drift, so the probe exercises it, not a
look-alike). `KEWL_FAKE_CONFIG` pushes the first plugin with settings via `debugPushConfig` (a
view only a mouse could reach is a view that never gets verified); `KEWL_FAKE_TAB=debug|profiles`
selects a tab (unknown values ignored); edits go nowhere (`writeEdit` no-ops without a mapping).
`KEWL_DUMP_FRAME=<path>` writes the DIB as P7 PAM at frame 30 (PAM bytes are R,G,B,A — the DIB is
BGRA and the dump loop reorders, so do not channel-swap on the way out); `KEWL_DUMP_EVERY=<sec>`
keeps dumping `<path>-N` every N seconds (~30 fps assumption baked into the modulo). The plumbing
is `tools/launcher-smoke.sh`. **For v2**: `loadFakePanelModel` must grow pinned[]/profiles/hub
sections in the same byte order the real writer will use, and `KEWL_FAKE_TAB` needs values for
whatever tab hosts the hub; a collapsed-sidebar state needs its own env, since there is no offline
click path to a collapse control in a PAM dump.

## N3. Threading

**Launcher: one thread, everything on it.** `WinMain` registers messages, then pumps:
`while (PeekMessageW(PM_REMOVE)) DispatchMessageW`, then pace to the next 1/30 s and call
`frame()`. `frame()` does the phase machine, `bridgeTick` (mutex → revision check → `readModel` →
unlock), `selfHeal`, ImGui `NewFrame/Render`, software raster, `BitBlt`. Input arrives on the same
thread via `wndProc` → `ImGuiIO` events. Consequences worth keeping in mind:
- `readModel` runs *while holding the bridge mutex* — a few KB parse is fine, but the DLL's publish
  waits on it (up to 2 s). A v2 model that grows the parse cost grows that stall; keep the parse
  cheap and reject-early.
- Search-as-you-type does its work inside `frame()` on this thread; a filter over 64 plugins is
  nothing, but anything blocking (hub network I/O) must never be reached from here — the launcher
  has no way to do async work without a second thread, which today does not exist.

**DLL: `DllMain` spawns one `run` thread; that thread is everything.** Window discovery (up to 60 s
poll), launcher-mode detection (`detectLauncherMode` — three signals, 30 s patience when the parent
process is KewlKlient.exe, none otherwise, with a `PeekMessage` drain inside the wait loop so
posted signals land), `JNI_CreateJavaVM` **on that thread**, then a `Sleep(33)` loop doing window
reconciliation, `kk::bridge::tick()` (one JNI revision poll + `drainEdits`) and `kk::tickJvm()`.

**The frame-thread rule, and why v2 edits are safe on it.** Every JNI call the bridge makes —
revision poll, snapshot pull, and every edit applied via `bridgeApply` → `PanelBridge.set*` —
happens on the same `run` thread that calls `KewlKlient.tick(int)`. Java's `tick()` is what drains
`Plugin.later`, so an edit applied on this thread is sequenced with the frame thread by
construction; there is no second thread touching Java's plugin state from the native side, and
`env()`'s `AttachCurrentThread` has exactly one non-JVM thread to attach. The rule v2 must not
violate: **a new edit kind's Java handler must not block**. `hubRefresh` (a network fetch) and
`profileSwitch`/`profileCreate` (config-file I/O) done synchronously inside the upcall would stall
the game's own tick — the 33 ms loop that also draws overlays and presents frames. Both must hand
off to Java's existing executor pattern (the pathfinder already runs on its own executor) and
publish the result by bumping `modelRevision` so the next snapshot carries the new hub/profile
state; that is exactly what the `hubState` (idle/loading/error/ready) and the `installing` flag in
the contract are for — the UI shows progress from the model, not from the edit call. On the native
side nothing changes: `drainEdits` already treats "Java said no" as consumed and moves on, and the
DLL never waits on Java beyond the upcall returning.

One more native-side note: `bridgeApply` allocates a `jstring` per edit; a v2 burst (e.g. a bulk
reset) is still one string per record, bounded by the 64-slot ring, so nothing new there.

## N4. Window lifecycle, and what a sidebar COLLAPSE touches

The launcher window is class `KewlKlientLauncher`, `WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN`,
`CS_HREDRAW | CS_VREDRAW`, no `hbrBackground` (`WM_ERASEBKGND` returns 1 — the DIB owns every
pixel). `WS_CLIPCHILDREN` is load-bearing: the frame loop `GetDC`s the whole window and `BitBlt`s
the full DIB every frame, and the clip region excludes the game child so the repaint cannot smear
the game's frame. `embedGame` sets the launcher-hwnd property on the game window, posts the
registered embed message (reposted every 100 ms for 2.5 s — a posted message can beat the DLL's
subclass into place), then bluntly sets `WS_CHILD|WS_VISIBLE` and `SetParent`s the game in.

Layout lives in three places that must agree:
- `layoutEmbed()`: `gameW = clientW - PANEL_W`, floor `MIN_GAME_W = 800`, `SetWindowPos` the game
  child, records `g_setGameW/H`. Called from `WM_SIZE` and from `selfHeal`.
- `selfHeal()`: if the host did not change but the game's size is not what we set, NXT re-applied
  its own client size (once at login) — the host grows around the game (`gw + PANEL_W`) instead of
  fighting, except when maximized. Records even a refused size so it is not re-detected every
  frame.
- `draw()` in panel_ui.hpp: positions the body and rail windows from `io.DisplaySize` and bails
  out entirely when `dispW < PANEL_W`.

The DLL's launcher-mode loop, by contrast, owns NO sizing — it only records the game's size it
observes and re-resolves `JagRenderView` every frame (NXT can recreate the child; a stale HWND
reads as a 0x0 rect that hides the overlay). Overlay pinning is `SetWindowPos(host, overlay)` every
frame — immediately above the host, not topmost — plus hide-on-minimize for the overlay and the
(direct-inject) panel.

**A collapse (hide the body, keep the 36px rail, widen the game) touches, exhaustively:**
1. A `collapsed` flag (function-local static next to `uiTab()`, or a `Model` field) read by
   `layoutEmbed`/`selfHeal`/`WM_SIZE` in main.cpp and by `draw()` in panel_ui.hpp. The strip
   width constant stops being a constant: `PANEL_W` in `layoutEmbed` and in `selfHeal`'s
   `gw + PANEL_W` must both become "collapsed ? RAIL_W : PANEL_W" — getting only one of the two
   would make the self-heal re-grow the strip after every NXT snap-back.
2. `draw()`: the rail stays where it is (`dispW - RAIL_W`); the body window is either not
   `Begin`-n that frame or sized to zero — an ImGui window must not be left positioned off-screen
   with `NoSavedSettings`, it would still hit-test. The early return `dispW < PANEL_W` becomes a
   check against the *current* strip width, or a collapsed launcher squeezed below 36 px would
   draw nothing at all. The collapse control belongs on the rail (a top cell above the tabs, or a
   rail-edge chevron), and "preserve sidebar open/closed state" cannot go to imgui.ini
   (`io.IniFilename = nullptr` on purpose) — it is a static or a window prop.
3. Nothing on the DLL side: launcher mode already owns none of the sizing, and the overlay follows
   the canvas, not the strip. Direct-inject's Java panel is untouched by construction.
4. `WS_CLIPCHILDREN` needs no change — the game child simply gets wider, and the clip region
   follows.
5. An offline probe (`KEWL_FAKE_COLLAPSE` or similar) is needed, since there is no click path to a
   new rail control in a PAM dump (the same reason `KEWL_FAKE_TAB` exists).

## N5. Idle CPU / frame pacing

**Launcher.** The pump drains all pending messages, then sleeps until `nextFrame = t + 1/30`:
`Sleep(max(1, ms))`, with an explicit note that `Sleep(0)` under Wine is a spin. Wine's default
timer resolution means the `Sleep(ms)` lands late and the loop may take two or three `Sleep(1)`
iterations to reach the frame time — bounded spin, not a busy loop, but it does mean ~30 wakeups/s
plus a few, forever, even with nothing to draw. When minimized, `frame()` returns after
`ensureDib` and before `ImGui::NewFrame` — no ImGui work while iconic — but the pump still wakes at
30 fps and `bridgeTick` still polls (the open retry is throttled to every 300 ms; once open it is
one mutex acquisition plus one 8-byte read per frame). The spec's "no render loop busy-spin when
idle" is met today; the honest statement is "30 wakeups/s of nearly-free work", not "sleeps until
input". `g_clientW/H` come from `WM_SIZE`; a frame with a zero client size returns before the DIB.

**DLL.** `Sleep(33)` unconditionally at the end of every loop iteration — flat ~30 fps whether the
game is minimized or not, including the per-frame `FindWindowExW` re-resolve, the `GetCursorPos`
leave poll (direct-inject only) and the bridge tick (one JNI int call). Idle CPU is one thread
sleeping ~33 ms plus negligible work; the thing that would turn it into a spin is calling
`bridge::tick` more often than the model changes, which it does not (revision-gated publish;
`drainEdits` returns immediately on `head <= tail`).

**v2 risk to note:** the launcher has no way to sleep on the bridge — it cannot wait on the mutex
in the pump, and no event is signalled on publish. A hub "loading" state therefore arrives at
whatever frame the next 30 fps poll catches, which is fine; but resist any temptation to shorten
the poll interval to make the hub feel snappier — the edit-notify message is the latency path for
edits, and model freshness at 30 fps is already better than the eye needs.

## N6. What KEWL_FAKE_PANEL's synthetic model needs for the new views

`loadFakePanelModel` currently emits 3 plugins (one carrying 7 settings, one per widget shape the
config view knows, across sections `"render"`/`"general"`/loose) and nothing else. For v2 the same
function must append, in the real writer's order:

- `int32 pinned[pluginCount]` after the plugin records — at least one plugin pinned and one not,
  so the pin control draws both states and the pinned-first row ordering is visible in a dump.
- The profiles block: `activeProfileIndex`, `profileCount`, then per profile `char name[64]` +
  `char id[64]`. The fake set should include an active profile, a second one, and one with a
  non-ASCII name — UTF-8 fixed-field truncation is exactly the kind of thing only a dump shows.
- The hub block: `hubState` — all four values (idle/loading/error/ready) are worth dumping, since
  the loading and error states have no other verification path; the error string when state is
  error; `hubCount`; per entry `id[64], name[96], version[32], author[64], desc[160]`, flags with
  bit0 (installed), bit1 (hasUpdate) and bit2 (installing) each represented at least once, and
  `installedPluginIdx` both as −1 and as a real index into the fake plugin list — that
  cross-reference is what lets a hub row show which local plugin it maps to.

The fake region is built with local `putI32`/`putField` lambdas that mirror `buildModel` — they
must grow the same new helpers at the same time, or the probe starts "verifying" a format the real
writer no longer produces. `KEWL_FAKE_TAB` needs values for any new rail tab, and hub actions in
fake mode write edits nowhere, so the installing flag's progression can only be exercised by
making the fake model's flags change over frames (or by accepting that progression is verified
live, by the human).
