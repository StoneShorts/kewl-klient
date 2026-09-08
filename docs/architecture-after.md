# Architecture after the ImGui migration

The "after" picture, mirroring [`architecture-before.md`](architecture-before.md) section for section:
what the migration built, where the Java/native lines run now, and what each piece owns. Everything
here was read out of the sources on this branch on 2026-09-05 and re-verified by a clean
`sh gradlew test` (402 tests, 0 failures, 40 classes -- last recorded run 2026-09-06) and
`sh tools/wine-setup.sh` (zero errors).
The bridge contract summary is section 3; the byte-level detail stays where it belongs, in the
`static_assert`ed headers that enforce it.

---

## 1. Processes and build

Still one Gradle project (`build.gradle`) driving both halves, and the same output layout:
`build/dist/` holds `KewlKlient.exe`, `kewlklient.dll`, `kewlklient.jar`, `kewlklient.ini`. What
changed is who draws the control panel and where its state lives:

```
       launcher (its own process)            injected into the game (its own process)
    ┌──────────────────────────┐          ┌──────────────────────────────────────────┐
    │  KewlKlient.exe (ImGui)  │ spawn    │  osclient.exe                            │
    │  "+ client" button  ─────┼────────> │   └─ kewlklient.dll (injected)           │
    │  reads the model region  │  inject  │       reads memory, starts a JVM ────────┼──> kewlklient.jar
    │  writes the edit ring    │<════════>│       25 natives, Java2D overlays        │      api + your plugins
    │  software-raster ImGui   │  shared  │       PluginManager, ProfileManager, Hub │      plugin state + profiles
    └──────────────────────────┘  memory  └──────────────────────────────────────────┘
```

- **`launcher/main.cpp`** spawns `osclient.exe` (path from `[kewl] game=` in `kewlklient.ini`),
  injects `kewlklient.dll` (`CreateRemoteThread` + `LoadLibraryW`), and `SetParent`s the game into
  its own window: game on the left, a 286 px ImGui strip on the right (250 px body + 36 px rail).
  The strip is drawn by the **launcher process** with the software rasterizer
  (`client/imgui_sw.hpp`, vendored ImGui 1.93 under `third_party/imgui/`) — the game owns the only
  OpenGL context, so the panel never touches a GPU.
- **`client/dllmain.cpp`** detects launcher mode (embed message / window prop / parent process),
  and in that mode skips the host-window + Java2D-panel creation entirely: the DLL keeps overlays
  pinned to the game child and runs the bridge server. In direct-inject (no launcher) it does
  exactly what it always did — host window, Java2D `SidePanel` popup, `panelMouse` events.
- `kewlklient.ini` grew from one setting to five, and only one of them is new plugin-adjacent state:
  `java=` (JDK path, build-stamped), `game=` and `dll=` (launcher inputs), `sidebar=open|collapsed`
  (the one thing the launcher persists between boots — the spec's "preserve sidebar open/closed
  state"), and `hub=` (the plugin hub's manifest URL; see `HubConfig`). It is still not plugin state.

`tools/wine-setup.sh` builds the whole thing on Linux (llvm-mingw cross + Wine dist), which is how
every claim below was verified offline.

## 2. The two runtime shapes

Unchanged as a concept, sharpened in implementation:

1. **Launcher mode (the default path).** `KewlKlient.exe` spawns, injects, embeds, and calls
   `KewlKlient.setPanelMode(true)` before the first tick. From then on Java draws **only** overlays
   (`SidePanel.frame` returns immediately, `panelMouse` never arrives) and the panel pixels belong
   to the launcher's rasterizer. All panel data crosses the shared-memory bridge (section 3).
2. **Direct-inject (legacy, still working, deliberately untouched).** `osclient.exe` run by hand with
   the DLL injected (`tools/wine_inject.exe` does this on Linux): the DLL detects no launcher
   signal, builds its own host window, and the Java2D `kewl.ui.SidePanel` draws the control panel
   into its own layered window exactly as before. Its views (`kewl.ui.PluginListView`,
   `ConfigView`, `ProfilesView`, `DebugView`, `Theme`) were kept and now read and write the same
   Java-side owners the ImGui panel talks to — `ProfilesView` was moved off its session-only map
   onto the real profile store, and its Reset buttons now call `Setting.reset()` like the bridge's
   reset edits do. `kewl.ui.Sidebar` (the pre-overlay Swing panel) is still dead code, kept only
   because `ConfigDefaultsTest` reflects into it; see "Remaining limitations" in `PROGRESS.md`.

## 3. The bridge (v2)

All of it is still one JNI class plus one shared-memory region, now with a v2 contract that every
side compiles asserts against:

- `kewl.Natives` — unchanged: `viewport()`, `present(pixels,w,h)`, `presentPanel(pixels,w,h)`
  (direct-inject panel only), `container(id)`, `input()`, and the other registered natives. 21
  methods, all in `client/jvm.hpp`'s `RegisterNatives` table — the whole unsafe surface.
- `KewlKlient.tick(int keys)` — unchanged in shape: `Plugin.drainLater()` (queued edits), hotkey
  toggles, per-plugin `tick()`, then `render()`. Still the only thread that has ever touched plugin
  state.
- `kewl.panel.PanelBridge` — FORMAT **2**. `snapshot()` packs the whole panel model into an `int[]`;
  the DLL's `buildModel` (`client/bridge.hpp`) parses it and repacks it into the model region of
  `Local\KewlKlientBridge-<pid>`; the launcher's reader (`launcher/bridge_layout.hpp`) parses the
  region. Edits go the other way through a 208-byte record ring, and the DLL dispatches them onto
  `PanelBridge`'s set*/command methods.

### The three-sided layout, and how drift is prevented

`client/bridge.hpp` (DLL: writes region, drains ring), `launcher/bridge_layout.hpp` (launcher: reads
region, writes ring — it cannot include bridge.hpp, which pulls JNI), and `PanelBridge` (Java:
produces the snapshot) each restate the contract, and `static_assert`s pin every offset and record
size on the native sides:

- Header: magic `'KKBR'`, format **2**, two HWNDs, `modelRevision` @24, `editSeq` @32, 64×208-byte
  edit ring @40, head @13352, tail @13356, model region @**13360** (`MODEL_OFFSET == sizeof(Header)`,
  asserted equal to 13360 in both files).
- `EditRecord` (208 bytes, `#pragma pack(push,4)`): `{ i32 kind, i32 pluginIdx, char key[64], i64
  intVal, char text[128] }` — the v1 shape, unchanged; v2 only grew the `kind` vocabulary.
- Two string encodings, deliberately different (this is the spot the two regions look alike and are
  not): **fixed NUL-padded char[N] fields** in the region (`putField`, cut back over UTF-8
  continuation bytes so a character is never split), and **length-prefixed UTF-8 packed four bytes
  per int, lowest byte first, `ceil(n/4)` advance** inside Java's snapshot (`Buf.putString` /
  `Reader::str`).
- Caps: 64 plugins, 256 settings/plugin, 8 options/setting, 32 profiles, 64 hub entries — asserted
  on the native sides and restated (and enforced where the data is *produced*) in Java:
  `PluginManager.MAX_PLUGINS`, `ProfileManager.MAX_PROFILES`, `Hub.MAX_ENTRIES`,
  `PanelBridge.MAX_SETTINGS_PER_PLUGIN`. A snapshot that trips a cap is rejected by `buildModel`
  WHOLE (the desynchronisation guard), so the producers refuse the 65th plugin / 33rd profile
  rather than publish something the DLL would throw away.

### Model region, format 2

Format 1's bytes are an exact prefix; the v2 sections are appended after the plugin records:

```
[v1] int32 pluginCount
     per plugin: enabled, hasConfig, hotkey; char name[64] desc[160] status[160];
                 settingCount; per setting: kind, valueInt, min, max, enumIndex, optionCount,
                 flags(bit0 keybind, bit1 units); char key[64] label[96] desc[192] section[64]
                 valueText[64]; optionCount x char option[48]
[v2] int32 pinned[pluginCount]        0/1, index-parallel to the plugin records
[v2] int32 activeProfileIndex         -1 = none
[v2] int32 profileCount               then per profile: char name[64], char id[64]
[v2] int32 hubState                   0=idle 1=loading 2=error 3=ready
[v2] char   hubError[160]             "" unless hubState == 2
[v2] int32 hubCount                   then per entry: char id[64] name[96] version[32]
                                      author[64] desc[160]; int32 flags
                                      (bit0 installed, bit1 hasUpdate, bit2 installing/removing);
                                      int32 installedPluginIdx (-1 when not installed)
```

### Edit kinds

| kind | name | arguments → Java (all queued via `Plugin.later`, applied at the top of the next frame) |
|---|---|---|
| 0 | EDIT_BOOL | key + intVal 0/1 → `setBool`; the reserved key `"enabled"` is the plugin switch → `PluginManager.setEnabled` |
| 1 | EDIT_INT | key + intVal → `setInt` (`Setting.set` clamps) |
| 2 | EDIT_ENUM | key + option **index** → `setEnum` |
| 3 | EDIT_TEXT | key + text → `setText` |
| 4 | EDIT_RESET_SETTING | key → `resetSetting` → `PluginManager.resetSetting` → `Setting.reset` |
| 5 | EDIT_RESET_PLUGIN | pluginIdx → `resetPlugin` → every `Setting.reset` |
| 6 | EDIT_SET_PIN | pluginIdx + intVal 0/1 → `ProfileManager.setPinned` (pins are global) |
| 7/8 | EDIT_HUB_INSTALL / REMOVE | pluginIdx = −1, text = hub id → `Hub.install` / `Hub.remove` |
| 9 | EDIT_HUB_REFRESH | no arguments → `Hub.refresh` |
| 10–14 | EDIT_PROFILE_SWITCH/CREATE/DELETE/RENAME/DUPLICATE | index or text → `ProfileManager` |

Every command is routed through its **owning manager** — never around it. A session without the
owner installed (the bare test suite, a jar mid-upgrade) drops the command with a log line rather
than guessing; an unknown kind is consumed and dropped, so an old jar can never wedge the ring.

### Ring protocol and failure behaviour (what v2 kept from v1)

- The mutex guards the **model region only**; the edit ring is lock-free SPSC. The launcher writes
  the record, *then* bumps head, *then* `editSeq`; the DLL copies a record out before the JNI call
  and advances tail only after the edit is consumed (at-least-once; a replayed `Setting.set` is
  idempotent at the value).
- The launcher refuses to write into a full ring (producer-side flow control); the DLL's
  `head - tail >= RING_SLOTS` lap guard drops a lapped backlog wholesale — the next model publish
  re-syncs every widget, which is why dropped edits are not queued for retry (documented decision,
  see `PROGRESS.md`).
- `publishModel` does its JNI work *before* taking the mutex; a rejected snapshot (wrong magic,
  wrong format, truncated, or Java's walk threw and `snapshot()` returned null) keeps the last good
  model and retries at most once a second, logging the rejection and its recovery. The revision is
  consumed on success only.
- `modelRevision` packs five counters — `Setting.REVISION` (bits 32..63), status stamp,
  `Plugin.enableVersion`, `ProfileManager.generation`, `Hub.generation` — because pinning a plugin
  or renaming a profile moves no Setting and no switch, and without counters of their own those
  changes would never be republished.
- A jar without `PanelBridge` gets a complete empty model published once (with the real v2 tail,
  not zeroed pages), so the launcher shows an empty panel instead of waiting forever.

## 4. Java subsystems (what the migration added)

All new code lives in small packages under `java/kewl/`; none of it widens the JNI surface.

- **`kewl.plugin.PluginManager`** — the one place a plugin is switched on or off. Idempotence (no
  second `onEnable`), exception isolation (a hook that throws leaves the plugin **off** and records
  the failure — it must not keep ticking, and the tick loop must not die), registry ownership
  (`register`/`unregister`, insertion order, the cap, duplicate-id refusal), `shutdown()` for clean
  teardown. `Plugin.setEnabled` became a one-line hand-off to it, with the old inline transition
  kept verbatim for the pre-manager window (the bare test suite, the instant before start-up).
- **`kewl.profile.ProfileManager`** — the Setting persistence sink and the PluginManager listener.
  Per-profile enabled map + non-default settings under `<dataDir>/profiles/<id>/config.json`,
  `index.json` for the list/active id/pins. Writes are atomic (tmp + rename, `kewl.persist.JsonStore`)
  and debounced 750 ms on a single daemon IO thread; corrupt files are quarantined to `.bad` and
  fallen back from. "A profile is a complete statement": silence means off and code defaults, which
  is what makes switching actually isolate. **Pins are global**, not per profile — a documented
  decision (a pin is a fact about the user, not about a way of playing). Migration is honest:
  nothing was ever persisted before, so first run creates one empty "default" profile.
- **`kewl.profile.SettingCodec`** — folds a Setting's value into the four things JSON has. Colours
  are ARGB (`#rrggbb`, `#aarrggbb` — the order `Color.getRGB()`/`Color.decode` both speak; pinned by
  `SettingCodecTest`). Enums are stored by option `toString`, so a plugin that reorders its options
  keeps every stored value that still names one.
- **`kewl.plugin.hub`** — `HubConfig` (manifest URL from `kewl.hub.url` / `KEWL_HUB` / `hub=` in
  kewlklient.ini / `client.json`; empty means "no hub configured", shown as an error state),
  `HubEntry` (manifest validation: id shape, name/version/mainClass, https or `file:` artifact,
  mandatory SHA-256), `HubLoader` (child-first `URLClassLoader` per plugin; refuses a mainClass that
  does not extend `kewl.Plugin` or that resolved from the client), and `Hub` (the state machine:
  async fetch/download/verify on one worker, size limit, atomic move into place, `installed.json`,
  remove, update, restore at start-up). Hub state surfaces in the model as `hubState` + per-entry
  flags, so the UI shows progress from the model rather than from a blocking call.
- **`kewl.persist.JsonStore`** + **`kewl.json.Json`** — atomic JSON writes and a small reader; no
  external JSON dependency reached the jar.
- **`kewl.Plugin` metadata** — `id()` (derived from the name by default, override when two plugins
  would collide), `version()`, `author()`, `tags()`, all defaulted so the simple plugin stays
  exactly as simple as it was.
- **`KewlKlient.start()`** — the wiring order is the state-flow order: profile store first (it wants
  to be the Setting sink before anything can set), then the manager over the deterministic built-in
  list, then the hub (which reloads installed externals in the background), and only then the
  default-on list — and even there, only where the profile has no opinion, so a stored "off" beats a
  code default. A non-daemon shutdown hook is the last chance the profile store gets, because the
  process dies with the game without warning.

## 5. Plugin inventory (the registry is still the registry, plus the seam externals join through)

`KewlKlient.PLUGINS` is still a static `List.of(...)` and the list is still the registry — no
scanning, no annotation processor. `KewlKlient.plugins()` now returns the *manager's* live list once
one is installed, so hub-registered plugins appear in the same order-based index space the edit
records name. Ten entries as of 2026-09-06: PlayerVisuals, NpcVisuals, Woodcutter,
RlitePlugin("Shortest Path"), RlitePlugin("NPC Indicators"), RlitePlugin("Player Indicators"),
RlitePlugin("Test Rlite"), RlitePlugin("Test Actors"), AutoLogin, AntiIdle -- the two Indicators
ports are the only ones in `defaultOn()`. New entries are APPENDED, because edit records address a
plugin by its index in this list. The ported Shortest Path is unchanged:
eager object graph via `kewl.rl.Injector`, 79 proxied RuneLite settings + `autoWalk`, pathfinding on
its own single-thread executor with results marshalled to the frame thread via `ClientThread`.

## 6. Config lifecycle (after)

1. **Declare** — unchanged: `config.bool/number/text/colour/enumeration(...)` in the constructor.
2. **Enrich** — unchanged: the RuneLite `ConfigManager` shim declares the proxied settings onto the
   same `Config`, `RlConfigMeta` recovers sections/units/keybind-ness for the bridge.
3. **Edit** — unchanged write path: everything lands in `Setting.set`, which bumps `REVISION`,
   clamps INTs, fires change listeners, and now also notifies the persistence sink. Both panels and
   the bridge take the same road.
4. **Reset** — now routed: per-setting (kind 4) and per-plugin (kind 5) edits reach
   `PluginManager.resetSetting/resetPlugin` → `Setting.reset()`, the same call the Java2D panel's
   Reset buttons make. `SettingDefaults` (the old start-up snapshot that disagreed with this) is
   deleted — see the fixer note in `PROGRESS.md` for why it had to go.
5. **Persist** — the sink records the change under the owning plugin's id in the active profile and
   schedules a debounced write; values equal to the declared default are *removed* from the store
   rather than written, so a plugin that gains a setting in an update starts on its new default.

## 7. Threading model

The spec's thread list, with what actually runs on each:

| Thread | What runs there | The rule that holds |
|---|---|---|
| **Launcher UI thread** (`WinMain` pump) | message pump → `frame()`: phase machine, `bridgeTick` (mutex → revision check → `readModel`), self-heal, ImGui NewFrame/Render, software raster, BitBlt; all input via WndProc → ImGuiIO | never blocks: no network, no file I/O beyond the ini read at start-up, the ini write on a collapse toggle, and the PAM frame dumps the KEWL_DUMP_* probes ask for; model parsing is bounds-checked and reject-early |
| **DLL run thread** (`DllMain` spawns one) | window discovery, launcher-mode detection, `JNI_CreateJavaVM`, the 33 ms loop: window reconciliation, `bridge::tick()` (revision poll + `drainEdits`), `kk::tickJvm()` | the frame thread. Every JNI call happens here; Java's `tick()` drains `Plugin.later`, so an edit is sequenced with the frame thread by construction. A new edit kind's Java handler must not block — hub refresh and profile writes hand off to owners that do their I/O elsewhere and publish results by bumping the model revision |
| **Java frame thread** (the same thread, inside the JVM) | `KewlKlient.tick`: drain later-queue, hotkeys, per-plugin `tick()`/`render()`, overlay canvas, `SidePanel.frame` (direct-inject only) | the only thread that has ever touched plugin state; non-blocking, no network, no per-frame disk writes (persistence is mark-dirty + schedule) |
| **`kewl-profile-io`** (single daemon) | debounced profile/index writes | never holds the profile lock while doing file I/O — the JSON snapshot is built under `lock`, written after it is released |
| **`kewl-hub`** (single daemon) | manifest fetches, artifact download + SHA-256, install/remove file work | the frame thread only flips volatiles; registry changes are posted back through `Plugin.later` |
| **`shortest-path-%d`** (single, per restart) | the ported pathfinder | results marshalled to the frame thread via `ClientThread.invokeLater`/`drain()` |
| Swing event thread | **nothing new.** Direct-inject's Java2D panel draws on the frame thread like everything else; `kewl.ui.Sidebar` is the one Swing file left and nothing launches it | no Swing event-thread dependency in any new code |

No unsynchronized mutable collection is shared across the boundary: the model region crosses under
the mutex, the ring is SPSC with the publish-before-head store order, and everything else crosses as
queued runnables or immutable snapshots.

## 8. Ownership rules

1. **Java is the only source of truth.** Plugin objects, enabled state, metadata, settings,
   persistence, profiles, pins, external plugin state and hub state all live in Java. The native UI
   is a view/controller: it reads a snapshot, it writes edit records, it owns nothing but its own
   layout state (tab, nav stack, collapse, search text, scroll positions, the confirm-arm).
2. **All mutations go through the owners.** Toggles → `PluginManager.setEnabled`; values →
   `Setting.set`; resets → `PluginManager.resetSetting/resetPlugin`; pins and profiles →
   `ProfileManager`; hub actions → `Hub`. `PanelBridge` is a transport, never an owner. Nothing
   writes a `Setting`'s value field directly; nothing calls `onEnable` from UI code.
3. **The registry order is load-bearing.** Edit records name plugins by index into
   `KewlKlient.plugins()`; the list is never re-sorted (the launcher sorts its *display* copy only),
   and unregistering shifts the rest, which is exactly why the model revision moves on unregister.
4. **Optimistic echo, then truth.** The launcher mutates its local parsed copy first (toggle flips,
   enum echoes, pin stars) and enqueues the edit; the next `modelRevision` bump overwrites the echo
   with what Java actually holds. Profile and hub actions have no local value to echo — their
   feedback is the next publish, which is why the hub's `installing` flag exists in the contract.
5. **Producers enforce the caps.** The reader rejects an over-cap snapshot whole rather than clamping
   and silently hiding state; therefore the writers refuse the 65th plugin, the 33rd profile and the
   65th hub entry at the source.

## 9. Window lifecycle

Still `WS_CLIPCHILDREN` (load-bearing: the launcher `BitBlt`s the full DIB every frame and the clip
region protects the game child), still `SetParent` embed with a reposted embed message, still the
NXT self-heal that grows the host around the game when the client re-applies its own size. What the
migration added:

- **Collapse.** The rail's chevron hides the 250 px body and widens the game the same frame;
  `effectivePanelW()` is the single place the strip width is computed, so `layoutEmbed`, `selfHeal`
  and `WM_SIZE` all agree. The state persists in `kewlklient.ini` (`sidebar=open|collapsed`) —
  deliberately not `imgui.ini`, which is disabled (`io.IniFilename = nullptr`) on purpose.
- **Navigation.** `uiTab()` (four rail tabs: plugins, profiles, hub, debug) plus `uiStack()` — a real
  push/pop stack of plugin config views with back/reset, replacing v1's single `uiPushed()` int. A
  pushed index that no longer names a plugin self-pops rather than drawing a stale slot.
- **Keyboard.** Search/text fields request ImGui keyboard focus through explicit hooks
  (`uiKeyboardRequested`/`uiKeyboardActive`) so typing in the strip does not fight the game child.

## 10. What closed from the before-picture's gap list

| Before | After |
|---|---|
| `KewlKlient` statics as de facto manager | `kewl.plugin.PluginManager` owns every transition |
| name/description/hotkey/status only | `id/version/author/tags` defaulted on `Plugin`; failure state surfaced by the manager |
| nothing persisted anywhere | profile store + index.json + `installed.json`, atomic and debounced |
| in-memory profiles, generated names, no rename/duplicate | `ProfileManager` with stable ids, create/rename/duplicate/delete/switch, persisted active profile |
| pins did not exist | `SET_PIN` edit → global pin state in index.json, echoed in the model |
| Plugin Hub did not exist | `kewl.plugin.hub` + the hub tab, driven by a configurable manifest endpoint |
| PanelBridge v1 (plugins + settings) | format 2: pins, profiles, hub, per-section guards, five-counter revision |
| reset had no bridge edit kind | kinds 4 and 5, routed through the manager |
| launcher profiles tab a placeholder | profiles view: switch/create/rename/duplicate/delete with the click-again-to-confirm arm |
| Swing `Sidebar` dead + one test hooked to it | unchanged, deliberately (see `PROGRESS.md` "Remaining limitations") |

The live in-game verification of all of the above — launcher spawn through edit-to-plugin-behaviour,
profile switches mid-session, a real hub manifest, overlays and Shortest Path — is the human's step
and is listed in [`testing.md`](testing.md).
