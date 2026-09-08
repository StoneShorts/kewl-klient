# The plugin system

How plugins are written, registered, configured, persisted, and how external ones arrive through the
hub. The design rule this document exists to protect: **the simplest plugin stays simple**. Nothing
below asks a plugin author to write UI, annotations, manifests, or lifecycle boilerplate — the one
place the outside world is allowed to be complicated is the hub's manifest, and that is somebody
else's file, not your plugin's.

---

## 1. A built-in plugin

Extend `kewl.Plugin`, override what you need, add one line to the list. This one draws a marker on
the nearest cow and counts them:

```java
package kewl.plugins;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;

import kewl.Plugin;
import kewl.api.Entity;
import kewl.api.Npcs;
import kewl.ui.Hud;

public final class CowSpotter extends Plugin {

    public CowSpotter() {
        config.number("range", "Range", "How far to look", 10, 1, 30);
        config.colour("colour", "Colour", "", new Color(255, 120, 200));
    }

    @Override public String name()        { return "Cow spotter"; }
    @Override public String description() { return "Marks the nearest cow."; }
    @Override public int    hotkey()      { return 5; }          // F6

    @Override
    public void render(Graphics2D g) {
        Entity cow = Npcs.nearestWithin(config.number("range"), 2805);
        if (cow == null) return;
        Point at = cow.screen();
        if (at != null) Hud.entityBox(g, at, 16, 30, config.colour("colour"));
    }
}
```

The registry is a list, and the list **is** the registry — in `java/kewl/KewlKlient.java`:

```java
private static final List<Plugin> PLUGINS = new ArrayList<>(List.of(
        new kewl.plugins.PlayerVisuals().markDeveloper(),   // scaffolding: grouped under "Developer"
        new kewl.plugins.NpcVisuals().markDeveloper(),
        new kewl.plugins.Woodcutter(),
        new kewl.plugins.CowSpotter()          // <- yours
));
```

Rebuild, restart. Your plugin is in both panels (the ImGui strip and the direct-inject Java2D one)
with a slider and a colour picker you never wrote. No scanning, no annotation processor, no
descriptor file — nothing that can silently fail to find your class.

The list's **order is load-bearing**: panel edit records name plugins by index into it
(`KewlKlient.plugins().get(i)`), so it is never re-sorted at runtime, and hub-installed plugins are
appended, never spliced in.

#### Developer scaffolding

Some entries in the registry are test rigs (`Test Rlite`, `Test Actors`) or worked examples the real
ports have replaced (`NPC visuals`, `Player visuals` — `NPC Indicators` / `Player Indicators` are the
visuals now). They are kept, not deleted, and marked instead: `Plugin.developer()` returns `false` by
default, `markDeveloper()` sets it on one instance (used above, so the plugin classes themselves stay
unaware of how the panel groups them), and a plugin that knows it is a test rig can override
`developer()` to return `true`.

Both panels then draw them the same way: sorted below everything else — below the pins too — under a
collapsed **Developer** heading, one click from visible. Nothing else changes: they are still
switchable, still keep their index in the registry, and the mark does not touch what is on by default
(`KewlKlient.defaultOn`).

Across the panel bridge the mark rides in the per-plugin flags word — the `int32` after `enabled`
that used to be `hasConfig`, whose bit0 still means exactly "has settings"; bit1 is the developer bit
(`PLUGIN_FLAG_CONFIG` / `PLUGIN_FLAG_DEV` in `kewl.panel.PanelBridge`, `client/bridge.hpp` and
`launcher/bridge_layout.hpp`). Spare bits of a field that already existed, so the bridge FORMAT
stayed at 2 and no side had to relearn an offset.

### The two methods, and the rules

| | for | rules |
|---|---|---|
| `tick()` | deciding and acting | runs every frame while enabled |
| `render(Graphics2D)` | drawing, and nothing else | runs after every plugin has ticked |

**Neither may block.** They run about thirty times a second on the overlay/frame thread; a
`Thread.sleep` in either freezes the overlay, and a network call in either is a ban-worthy stall.
For "every few seconds", keep a `long lastX` field and compare `System.currentTimeMillis()` —
`Woodcutter` shows the pattern. Expensive work belongs on your own executor with results handed back
to the frame thread; `shortestpath` is the worked example (its pathfinder runs on
`shortest-path-%d`, results arrive via `ClientThread.invokeLater`).

`onEnable()`/`onDisable()` reset and tear down state. They run on the same thread as everything
else, are individually caught (a plugin whose hook throws is left **disabled** and its failure is
surfaced, not swallowed — `kewl.plugin.PluginManager` owns that rule), and are idempotent: asking
for a state the plugin is already in does nothing.

### Optional metadata

`name()` is the only thing you must write. Everything else defaults, so a plugin that does not care
stays short:

| method | default | what it is for |
|---|---|---|
| `description()` | `""` | the line under the name |
| `id()` | derived from `name()` (lower-case, non-alphanumerics → `-`) | the key profiles and pins store this plugin's state under. Override only when two plugins would derive the same id or the name is something a file path should not carry — it must be stable across sessions |
| `version()` | `""` | built-ins version with the client |
| `author()` | `""` | display only |
| `tags()` | empty | extra search words for the plugin list. Declared today, consumed by none: the bridge model does not carry tags, so the launcher's search matches name, description and the live status line. Declaring tags is harmless and forward-compatible; do not rely on them being searched yet |
| `hotkey()` | `-1` | 0..7 = F1..F8; duplicates are allowed and both toggle |
| `developer()` | `false` | test rigs and worked examples: both panels sort them last, under a collapsed "Developer" heading. Override it, or call `markDeveloper()` on the instance in the registry |
| `status()` | `""` | a short line for the panel's status column |

## 2. Config: every setting type

Declare in the constructor; the panel builds the widget. Reading uses the same key
(`config.number("range")`), and an unknown key returns a harmless default rather than throwing — a
typo shows up as a plugin that does nothing, not an exception thirty times a second.

| declare | widget | stores | notes |
|---|---|---|---|
| `config.bool(key, label, description, def)` | toggle | `true`/`false` | |
| `config.number(key, label, description, def, min, max)` | slider + steppers | int, **clamped** to min..max on every set, so a slider cannot wedge | |
| `config.text(key, label, description, def)` | text field | `String` | |
| `config.colour(key, label, description, def)` | colour swatch | `java.awt.Color` | persisted as `#rrggbb`, or `#aarrggbb` when the colour has any alpha (standard ARGB — the order `Color.getRGB()` and `Color.decode` both speak) |
| `config.enumeration(key, label, description, enumConstant)` | drop-down | the enum constant | options are the enum's constants in declaration order; the *selected option* is stored, not an index, so a plugin that reorders or extends its enum keeps every stored value that still names one |

Two more shapes exist, both arriving through the RuneLite config shim rather than the kewl-native
API: a **section** (a grouping header — `RlConfigMeta` recovers `@ConfigSection`s for the bridge so
both panels draw the grouping) and a **keybind** (an INT setting stored as an F-index, 0 = not set,
reported as kind 3 in the bridge model so the panel draws it as a hotkey). Both are documented from
the porting side in [`../java/net/runelite/README.md`](../java/net/runelite/README.md).

**A credential in a Setting is a `config.secret`.** Every setting value is persisted by the profile
store under `~/.kewlklient/profiles`, so a password declared as a setting is written to that local
config.json in clear -- the trade the autologin plugin makes so the panel can take the login. A
`secret` is a TEXT setting with `Setting.secret()` set: both panels mask it (`displayText()`, never
`asText()`, in anything that draws or logs a value), the bridge flags it (bit2) and the launcher edits
it in a password-mode field. Anyone who would rather not have it in the profile leaves the settings
empty and uses `~/.kewlklient/autologin.properties` instead (README, "Autologin").

**Masking is a display rule, not a boundary** (review 2026-09-06). A text setting has to reach the
launcher process for its field to edit it, so a `secret`'s value crosses the shared-memory bridge in
clear: it is the setting record's `valueText` in the model region
(`Local\KewlKlientBridge-<gamePid>`), and a committed edit crosses back in the edit ring's
`char text[128]`. Any process running as the same Windows user can `OpenFileMappingW` that name and
read it for the life of the game process -- the same trust boundary as the profile's `config.json`,
but without the file's opt-out. Leaving the panel settings empty and using the properties file keeps
the value out of both: an empty setting publishes an empty `valueText`.

**A secret is capped at 63 UTF-8 bytes in practice.** `valueText` is a 63-byte bridge string
(format 2) while the ImGui field and the edit record hold 128, so a longer value is committed whole
(and works) but is mirrored back truncated; the next edit of that field writes the truncated copy
through `Setting.set`. Widening it is a format 3 change.

Semantics worth knowing:

- The only write path is `Setting.set(Object)` — it bumps the global revision the launcher's model
  refresh is built on, clamps numbers, fires change listeners, and notifies the persistence sink.
  Reset buttons go through `Setting.reset()`, which is `set(defaultValue)`, so a reset fires exactly
  what a user edit fires.
- Settings persist only when they **differ from their declared default**. A setting a plugin gains in
  an update starts on its new default instead of a stale copy.
- Keys are per plugin; the reserved key `"enabled"` is the plugin's on/off switch and is not a
  Setting (the bridge routes it to `PluginManager.setEnabled`).

## 3. Lifecycle and who owns it

`kewl.plugin.PluginManager` is the only thing that transitions a plugin. Four callers arrive there —
the F-key hotkey loop, the Java2D panel, the ImGui launcher's edit ring, and profile switches — and
none of them call `onEnable` themselves. The manager enforces:

- **Idempotence** — no second `onEnable`, no spurious persistence write.
- **Exception isolation** — a hook that throws leaves the plugin off and records the failure; the
  tick loop survives. Per-frame `tick()`/`render()` throws are caught per plugin in
  `KewlKlient.tick` (one broken plugin skips its frame), but nothing suspends a plugin that throws
  every frame — turn it off.
- **One thread** — transitions outside start-up are queued via `Plugin.later` and run at the top of
  the next frame.
- **The registry cap** (64, the panel bridge's limit) and duplicate-id refusal.

## 4. Profiles

Stored under the client's data directory (`KewlKlient.dataDir()` — `kewl.data.dir` overrides,
otherwise `~/.kewlklient`):

```
<dataDir>/profiles/index.json        the profile list, the active id, and the pins
<dataDir>/profiles/<id>/config.json  that profile's enabled map and non-default settings
```

- Ids are stable (`p1-<random>`); names are display-only and de-duplicated ("x", "x 2", ...), so
  renaming a profile moves nothing.
- **A profile is a complete statement**: silence means the plugin is off and its settings are at
  their declared defaults. That is what makes switching actually isolate profiles — a profile that
  never mentions `range` puts `range` back where the code declared it. It is self-consistent because
  every real change is captured the moment it happens.
- Switch persists the state being left (`flush()`), then re-states the new profile onto the live
  plugins; a plugin whose state does not change gets no second `onEnable`/`onDisable`.
- Create starts as a **copy of how the world is right now**, not blank — switching to a blank profile
  would silently reset every plugin, which reads as data loss. Duplicate copies stored state too.
- The last profile cannot be deleted; deleting the active one falls back to the first remaining.
- Writes are atomic (tmp + rename) and debounced 750 ms on one IO thread; a corrupt file is
  quarantined to `.bad` and fallen back from rather than losing the whole index.
- Migration is honest: nothing was persisted before this feature existed, so first run creates one
  empty "default" profile. Nothing was lost, because nothing was ever written down.
- **Pin scope (the decision): pins are global, not per profile.** A pin is a UI fact about the user
  ("these are the ones I want at the top of the list"), not a fact about a way of playing; nobody
  wants their pinned plugins to change because they switched from skilling to PvM. Pins live in
  index.json next to the profile list. If per-profile pins are ever wanted, `ProfileManager`'s class
  comment and the pins block in `indexJson()` are the two places that change.

## 5. External plugins and the hub

An external plugin is not a second plugin API. It is a jar containing a class that **extends
`kewl.Plugin`** (directly — a wrapper adapter works too, which is how the ported RuneLite plugins
run), loaded into the same client, registered with the same manager, configured by the same config
model, persisted under the same profiles.

### The manifest

`hub=` in `kewlklient.ini` (`[kewl]` or `[kewlklient]` section) — or the `KEWL_HUB` environment
variable, or the `kewl.hub.url` system property, or `"hub"` in `<dataDir>/client.json` — points at a
manifest. It is either a JSON list of plugin objects or `{"plugins": [...]}`. Each entry:

```json
{
  "id": "example-plugin",
  "name": "Example Plugin",
  "version": "1.0.0",
  "author": "Author",
  "description": "Example",
  "mainClass": "example.ExamplePlugin",
  "artifact": "https://example.com/jars/example-plugin-1.0.0.jar",
  "sha256": "64 lowercase or uppercase hex characters"
}
```

Validation (`kewl.plugin.hub.HubEntry`) is exactly what the loader depends on:

- `id` — 1..64 chars of `[A-Za-z0-9._-]`, starting with an alphanumeric. It becomes a directory name,
  which is why path-shaped ids are rejected outright.
- `name`, `version`, `mainClass` — non-empty. `mainClass` is checked again after loading, when it has
  to actually extend `kewl.Plugin`.
- `artifact` — an `https://` URL. A `file:` URL is also accepted: that is how a local hub is
  developed and how the test suite builds one. Anything else (`ftp:`, `jar:`, ...) is rejected.
- `sha256` — **required**, 64 hex characters. A hub without checksums is a hub asking the user to
  trust every mirror in between, and the download path refuses an artifact it cannot check.
- `author`/`description` — optional, display-only. Extra fields in the manifest are ignored, so a
  hub can grow without breaking old clients.

A malformed row is dropped with a log line naming the field; it does not empty the tab. A manifest
with no valid rows is an error state, not silence. The first 64 valid entries are shown (the panel
bridge's cap).

### Install, update, remove

1. The jar is downloaded to `<dataDir>/hub-tmp/` (anything claiming to be larger than 64 MB is
   refused), hashed while streaming, and the hash is compared to the manifest's. A mismatch deletes
   the download and lands in the hub's error state.
2. Only then does `<dataDir>/external/<id>/<version>.jar` appear (atomic move), so a failed
   checksum never leaves an empty directory pretending something was installed.
3. `HubLoader` opens a classloader of its own for the jar, loads `mainClass`, refuses anything that
   is not a `kewl.Plugin` (or that resolved from the client rather than the jar), and instantiates it.
4. The plugin is registered with the `PluginManager` — queued onto the frame thread, because the
   registry is frame-thread state — and the active profile immediately applies whatever state it has
   stored for that id. That is how an installed plugin comes back enabled with its settings.
5. **Update** is the same path: the tab shows "update available" when the manifest's version differs
   from the installed one, and installing replaces the jar and swaps the plugin.
6. **Remove** unregisters the plugin, closes its classloader after it is out of the registry, and
   deletes the directory. Its saved profile state is kept — never silently deleted because a plugin
   is temporarily unavailable.
7. At start-up, `<dataDir>/hub/installed.json` is replayed: each jar that survived is reloaded in
   the background. A plugin that fails to load after an update **stays installed on disk, disabled**,
   with a log line — the user keeps their config and their "remove" button.

### Sideload for development

There is no separate sideload mechanism, on purpose — one loader path, one set of validation rules.
To run your own jar locally, point `hub=` (or `KEWL_HUB`) at a local manifest that uses `file:` URLs:

```
KEWL_HUB=/home/me/hub/manifest.json
```

```json
[{
  "id": "dev-plugin", "name": "Dev plugin", "version": "0.1",
  "mainClass": "dev.DevPlugin",
  "artifact": "file:///home/me/hub/dev-plugin-0.1.jar",
  "sha256": "<sha256sum of the jar>"
}]
```

Refresh the hub tab, press install. `HubEndToEndTest` does exactly this end to end — jar built by
the test itself, served over `file:`, hashed, downloaded, classloaded, registered, removed — if you
want a template. For iteration on a **built-in** plugin you do not need any of this: it is on the
list or it does not exist.

## 5b. The RuneLite entity API the shim offers

A ported plugin (hosted through `kewl.rl.RlitePlugin`) sees kewl's per-frame `Game.npcs()` /
`Game.players()` snapshot through `net.runelite.api`:

| Upstream call | Shim | Notes |
|---|---|---|
| `client.getNpcs()` / `wv.npcs()` | `ActorTable` over `Game.npcs()` | one `NPC` object per handle for as long as it is on screen; `getIndex()` is the handle |
| `client.getPlayers()` / `wv.players()` | same, over `Game.players()` + the local `Player` first | `client.getLocalPlayer()` is the same object, so `p == local` works |
| `Actor.getLocalLocation()` | fine render position (128/tile), tile centre when unread | glides between tiles, as upstream |
| `Actor.getWorldLocation()` | scene + base, **the local player's plane** | no per-entity plane is exported yet |
| `getCanvasTilePoly` / `getCanvasTextLocation` / `getCanvasImageLocation` | projected at the actor's **own** ground height | `Perspective.*` statics use `Game.groundHeightGuess()` instead |
| `getConvexHull()` | 2D hull of a prism (footprint x `Actor.logicalHeight()`) | **an approximation** -- no model access on this build |
| `NPCComposition` | id + name; combat level 0, size 1, no actions | each stub registers itself with `ShimSupport` (5b-ii), so it says so once instead of returning a silent 0 |
| `getName()` | `""` until the client yields one; retried every 30 frames | tolerate `""`; `TestActors` reports `named=K/N` |
| `NpcSpawned` … `PlayerDespawned` | diffed per frame in `kewl.rl.Events`, before `GameTick` | per hosted plugin, so each gets a complete diff |
| `OverlayUtil` | `renderPolygon`, `renderTextLocation`, `renderActorOverlay`, `renderImageLocation`, `renderMinimapLocation` | no `TileObject` variant |

Not provided: `getCachedNPCs()`/`getCachedPlayers()` (slot arrays; the handle range is unknown),
`getInteracting()`, health bars, hitsplats, overhead text, graphics. None of those is a silent
default any more -- each registers with `ShimSupport` the first time a plugin calls it (5b-ii). The
`Test Actors` plugin (`kewl.rl.TestActors`, last in the registry) draws hull/tile/name for nearby
actors and prints one `[actors]` probe line per login to KEWL_LOG.

### 5b-ii. The camera, and what the shim admits it cannot answer

Two things landed together on 2026-09-06, and they are two halves of the same complaint ("the camera
shows zeros"): the camera angles became real, and everything that is still fake started saying so.

**The camera is DERIVED, not read.** There is no camera-orientation offset on client-240-6 and there
does not need to be one. The game's own `worldToScreen` is exposed as `Game.projectFine`, and the
shape of a perspective projection pins both angles exactly. `ClientState` projects six points around
the player once per frame -- two tiles north/south, two east/west, and one pair directly above and
below -- and reduces them:

| Value | How | Accuracy |
|---|---|---|
| `getCameraYawTarget()` / `getCameraYaw()` | `Perspective.yawFromScreenBasis`: the screen-**X** spread of the north and east pairs is `K sin a` and `K cos a`, so `atan2` of the two is the yaw, with the depth and the pitch dividing out | worst 4 units (0.7 deg) over a full sweep |
| `getCameraPitch()` / `getCameraPitchTarget()` | `Perspective.pitchFromScreenBasis`: the same four probes give `K sin p` as a cross term, the vertical pair gives `K cos p`, and `atan2` of those is the pitch | worst 4 units; the four-probe fallback used when the vertical pair will not project is worst 64 near a vertical camera |

Both are in **0..2047 units** (`Perspective.YAW_UNITS`, one unit = `Perspective.UNIT` radians), the
units `PathMinimapOverlay` rotates by. The five-probe pitch form is the one in force because the
common factor `K` cancels in it, which makes it immune to the single assumption the four-probe form
has to make -- that the client divides one scale into both screen axes. The shim computes both and
logs their disagreement once (`Perspective.pitchFormsNote`), so if that assumption were ever wrong
the log would say so rather than the pitch quietly drifting. Unknown is **-1**, never 0: a pitch of 0
is a level camera, which is a real reading.

Still not derivable, and each says why in its own javadoc: **minimap zoom** (the minimap is a raster
the client draws itself, so no pair of projected points measures it -- 4.0 px/tile is the vanilla
value, cross-checked against the widget's size by `Perspective.minimapScaleNote`), and **world-map
zoom** (the client provably has no zoom field; feed a measurement in with
`-Dkewl.worldmap.pixelsPerTile=<value>`).

**`ShimSupport` is the registry for everything else.** The rule it enforces: *a stub that returns 0
is indistinguishable from a real 0*, so no accessor may hand back a placeholder without saying so.
Every one that does calls `ShimSupport.note(accessor, kind, reason)` immediately before returning,
where the reason names the placeholder and what it costs:

```
[shim] Actor.isDead needs an offset: reads false for every actor: death is inferred upstream from
       the health bar, which is not readable. NPC Indicators' "ignore dead NPCs" option therefore
       ignores nothing
```

- **Once per accessor per session.** A getter called per NPC per frame costs one line, not 60/s.
- **Only what was actually called.** The registry is a record of what the running plugins really
  depend on, not a to-do list of the whole shim -- which is what keeps `ShimSupport.status()` short
  enough for the control-panel row `RlitePlugin` puts it in when a plugin has nothing else to say.
- **Three kinds, because they are three different pieces of work.** `NEEDS_OFFSET` (run the deob,
  add it to `client/offsets.hpp` and a native), `NEEDS_CACHE_DATA` (bundle a table next to
  `VarbitTable`; no client work at all -- item names, enums, stackability), `UNSUPPORTABLE` (looked
  for, shown not to exist -- the two zooms above).
- `ShimSupport.reasonFor("Client.getMinimapZoom")` answers "is this value real?" for one accessor;
  `ShimSupport.report()` is the whole list, printed once per session into KEWL_LOG by
  `RlitePlugin` after ~500 frames.

An accessor that is fully wired -- varps, varbits, widgets, containers, game state, skills, the two
camera angles -- never appears there at all.

### 5b-i. Sending input: `kewl.api.Input`

The one API that pushes something INTO the game rather than reading it. It posts window messages
to NXT's render view (`postChar`/`postKey`/`postMouse` in `client/jvm.hpp`), never `SendInput`, so
nothing it sends can land in another application. The rule to keep: **text goes as WM_CHAR
(`Input.typeChar`), control keys as KEYDOWN/KEYUP (`Input.key`)** -- posting a letter as a key-down
would let the game's own TranslateMessage pick the case from the physical shift state and emit a
second character. Pace keystrokes across frames (there is no `typeText` on purpose) and never log
what you type; `kewl.plugins.autologin.LoginSequence` is the offline-tested worked example, and
`Input.target(grab)` tells the log where the messages went. Posted mouse clicks **are** honoured on
client-240-6 -- the autologin Login click is what submits the form, and the resulting `state 10 -> 20`
line is the proof (seen live 2026-09-06; this paragraph said "unverified" until then).

### 5c. Two RuneLite plugins ported onto it: NPC Indicators, Player Indicators

`net.runelite.client.plugins.npchighlight` and `net.runelite.client.plugins.playerindicators` are
RuneLite's own plugins, ported source-shaped (upstream group/key names, `@ConfigSection`s, overlay
classes, `PlayerIndicatorsService`) and registered as two `RlitePlugin` lines in `KewlKlient.PLUGINS`
(**default-on since 2026-09-06**, when hull/name/tile were seen at each entity's real height live;
kewl's own `NpcVisuals`/`PlayerVisuals` are the ones that are now off by default). They are the worked example of a port that has to say what it cannot do:

| Item | Status | Why |
|---|---|---|
| hull / tile / true tile / south-west tiles / fill / border width / colour | SUPPORTED | the actor API above; the hull is the prism approximation; true/SW tiles sample the ground from the nearest entity (`Game.heightNear`) |
| `NPCs to highlight` (`*`, `?`, plain numbers = NPC type id) | SUPPORTED + shim extension | names read `""` on this build, so an id is the only handle; `WildcardMatcher` + `Text.fromCSV` are hand-written shims |
| name above the head / minimap names | SUPPORTED when the name reads; the minimap now **turns with the camera** | the yaw is derived (5b-ii); the minimap zoom is still the vanilla 4.0 px/tile |
| `Ignore dead NPCs` | kept, no-op | `Actor.isDead()` is `false` until a health/dead offset exists; it registers with `ShimSupport`, so the log says the option is doing nothing |
| outlines, menu recolouring, respawn timer, Tag/Untag, friend/clan/team/party colours, clan rank icons | OMITTED from the panel | no model, no readable game menu, no death read, no membership lists |
| Player Indicators `PvP` setting | behaves as Disabled | `getWorldType()` is empty until the world id can be read; friend/clan/team colours collapse to "other" for the same reason (`Player.isFriend` and friends are registered gaps) |

Both implement `kewl.rl.StatusSource` (status line: `N npcs · K highlighted · names ok/empty · P
patterns`; `N players · K drawn · own set/empty · others Disabled/Enabled · names k/n`) and print
`[npchighlight]` / `[playerindicators]` lines to KEWL_LOG once per login: whether NPC names read at
all, whether other players' names read, what the parsed list holds. Two things to know when
writing the next port: `ConfigManager` now maps a `double` item to the same int slider an `int`
gets (add a `@Range`, the way `borderWidth` does), and the `Auto-walk` toggle is declared only for
Shortest Path, so a ported plugin's panel holds exactly its own items.

## 6. Isolation: what is real, and what is not

Each external plugin loads in its own child-first `URLClassLoader`. What that buys:

- A plugin can bundle its own copy of a library without fighting the client's version, and two
  plugins can disagree about a library without either winning.
- Child-first stops at a short list of parent-first prefixes — `java.*`/`javax.*`/`jdk.*`/`sun.*`,
  `kewl.*`, and the shim (`net.runelite.*`, `org.slf4j.*`) — because those are **identity**, not
  bytecode: the client hands a plugin live objects, and a second class named `kewl.Plugin` would make
  the first cast a `ClassCastException` nobody could act on.
- Closing the loader unloads the plugin (that is the remove path).

**What it does not buy: security.** The classloader is isolation for *convenience*, not a sandbox,
and nothing in this codebase should be described as one. A `Plugin` runs with every permission this
process has: it can read and write files, open sockets, load natives and call the game's memory.
Installing one from a hub is running someone else's code inside your client, and the hub tab says so
in those words. The defences that do exist are validation (manifest shape, checksum, the
`extends kewl.Plugin` check) and failure containment (one plugin's exceptions never kill the tick
loop, the hub, or the other plugins) — not confinement.
