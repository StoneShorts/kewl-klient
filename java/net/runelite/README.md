# This is a shim, not RuneLite

Everything under `java/net/runelite/` exists so that RuneLite plugin-hub plugin *source* can be
ported into this project with minimal edits: imports stay, logic stays, and the classes the plugin
calls are reimplemented here on top of kewl's API.

There is no RuneLite here. No Guice (a small reflection injector in `kewl/rl/Injector.java`
understands the `@Inject`/`@Provides` annotations plugins keep), no real `ClientThread` (everything
runs on kewl's overlay frame thread), no plugin hub, no `.jar` loading. Upstream plugin `.jar` files
will not run as-is; that is not the goal.

Two kinds of file live here:

- **Vendored verbatim** from RuneLite (BSD-2): value types and constants a plugin reads, e.g.
  `coords/*`, `events/*`, `gameval/*`, `MenuAction`, `Skill`, `SpriteID`. These carry an attribution
  header.
- **Hand-written shims**: everything that talks to the game (`Client`, `ClientState`,
  `Perspective`, `Actor`/`NPC`/`Player`/`ActorTable`) or to kewl's client infrastructure
  (`config/ConfigManager`, `eventbus/EventBus`, `ui/overlay/*` including `OverlayUtil`,
  `callback/ClientThread`), and the small utilities ported plugins call (`util/Text`,
  `util/WildcardMatcher`). These say so in their first comment lines.
- **Ported plugins** under `client/plugins/` (`npchighlight`, `playerindicators`): RuneLite's own
  plugin source (BSD-2 headers kept), edited where the shim cannot honour upstream -- each config
  header lists what was omitted and the offset or native it waits on.

`ClientState` is the seam for memory offsets that have not been reverse-engineered yet: every method
waiting on an offset holds an honest default and names the offset it is waiting for.

`ShimSupport` is where those defaults stop being silent. **The rule: an accessor may not hand back a
placeholder without saying so** -- a `return 0` is indistinguishable from a real 0, which is what
made "the camera shows zeros" impossible to act on. Call
`ShimSupport.note(accessor, kind, reason)` immediately before returning the placeholder, with the
reason naming what it returns and what that costs a caller; it logs once per accessor per session
and never throws. `ShimSupport.reasonFor(name)` answers "is this value real?" for one accessor,
`report()` is the whole session's list. An accessor that is fully wired never appears there.

The camera yaw AND pitch are DERIVED from the game's own projection rather than read (six probe
points a frame, reduced by `Perspective.yawFromScreenBasis` / `pitchFromScreenBasis`), so neither
needs an offset; see docs/plugin-system.md 5b-ii for the derivation and its measured accuracy.

The bridge that runs a ported plugin is `java/kewl/rl/RlitePlugin.java`; `java/kewl/rl/TestRlite.java`
is the smoke test for the whole chain.
