# Third-party notices

KewlKlient itself is **GPL-3.0** — see [`LICENSE`](LICENSE). This file covers the code and data in
this repository that other people wrote, and where each one's licence text lives. Everything here is
either vendored with its licence in place or carried as an upstream-licensed source file; nothing
below is GPL-incompatible with the project's own licence.

---

## Dear ImGui — vendored, MIT

- **What:** `third_party/imgui/` — `imgui.h/.cpp`, `imgui_draw.cpp`, `imgui_internal.h`,
  `imgui_tables.cpp`, `imgui_widgets.cpp`, `imconfig.h`, `imstb_*.h`.
- **Upstream:** https://github.com/ocornut/imgui — v1.93.0 WIP (see `third_party/imgui/VENDORED.md`
  for exactly what was taken, why it is vendored rather than a submodule, and the backend-contract
  note for the software rasterizer in `client/imgui_sw.hpp`).
- **Licence:** the MIT License, Copyright (c) 2014-2026 Omar Cornut. The complete text ships with the
  sources at [`third_party/imgui/LICENSE.txt`](third_party/imgui/LICENSE.txt) and is reproduced here
  as required:

  > Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
  > associated documentation files (the "Software"), to deal in the Software without restriction,
  > including without limitation the rights to use, copy, modify, merge, publish, distribute,
  > sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
  > furnished to do so, subject to the following conditions: The above copyright notice and this
  > permission notice shall be included in all copies or substantial portions of the Software.
  >
  > THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
  > NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
  > NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
  > DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  > OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

  `imgui_demo.cpp` is deliberately not in the build (it drags in half the API surface this project
  does not use — see the `kewl_imgui` target in `CMakeLists.txt`); its absence does not change the
  licence of what is built.

## Shortest Path plugin — vendored, BSD 2-Clause

- **What:** `java/shortestpath/` (the ported plugin sources) and the data resources under
  `resources/` (`collision-map.zip`, `transports/`, `destinations/`, `leagues/`, `marker.png`).
- **Upstream:** https://github.com/Zoinkwiz/shortest-path (this fork) and
  https://github.com/Skretzo/shortest-path (original).
- **Licence and notice:** [`resources/NOTICE-shortest-path`](resources/NOTICE-shortest-path) is the
  notice, and it reproduces the licence as required; the upstream licence file itself ships at
  [`resources/LICENSE-shortest-path`](resources/LICENSE-shortest-path), a byte-for-byte copy of
  upstream's. One oddity worth saying out loud: upstream's own LICENSE carries the template text
  "Copyright (c) \<YEAR\>, \<COPYRIGHT HOLDER\>" — it was added to the upstream repository that way and
  never filled in, so the copy here shows the same placeholders. Nothing was invented to replace
  them; the NOTICE names the authors the work belongs to. BSD 2-Clause — redistribution and use in
  source and binary forms, with or without modification, are permitted provided the copyright notice
  and that disclaimer accompany them, which is what this file and those two do.

## RuneLite — vendored verbatim files, BSD 2-Clause

- **What:** the value types and constants under `java/net/runelite/` that were copied from RuneLite
  rather than reimplemented — `coords/*`, `events/*`, `gameval/*`, `MenuAction`, `Skill`,
  `SpriteID`, and the like. Each such file carries its upstream attribution header in the first
  lines (e.g. `Copyright (c) 2017, Adam <Adam@sigterm.info>`, all rights reserved, followed by the
  two BSD-2 redistribution clauses).
- **What is *not* upstream:** everything else under `java/net/runelite/` is a hand-written shim, not
  RuneLite — the client/config/eventbus/overlay/callback classes this project reimplements on top of
  kewl's API. [`java/net/runelite/README.md`](java/net/runelite/README.md) is the statement of which
  is which, file class by file class. No RuneLite source beyond the vendored constant/value files is
  present, and no RuneLite binaries or artwork are redistributed.

## Hand-written shims that borrow package names

The following are **this project's own code**, written here to satisfy a ported plugin's imports —
they are not copies of the upstream libraries and carry no upstream licence obligations:

- `java/com/google/inject/`, `java/javax/inject/`, `java/javax/annotation/` — the `@Inject`,
  `@Provides`, `@Nonnull`, `@Nullable` annotation shims (`kewl.rl.Injector` is the hand-rolled
  injector that reads them).
- `java/com/google/common/util/concurrent/ThreadFactoryBuilder.java` — the whole used surface of
  Guava's builder, reimplemented so the pathfinder's thread naming compiles.

## Build- and test-time dependencies (Gradle, never shipped)

Declared in `build.gradle` and fetched by the wrapper; **none of them reach `kewlklient.jar`**
(lombok is `compileOnly`/`annotationProcessor` — build-time code generation for the vendored
RuneLite files' `@Getter`s — and the other two are `testImplementation`):

- **Lombok** 1.18.34 — MIT License.
- **JUnit 4** 4.13.2 — Eclipse Public License 1.0.
- **Mockito** 4.11.0 — MIT License.

There are no other third-party dependencies: no JSON library, no GUI toolkit, no network stack, no
detour/injection library. The JSON reader/writer (`kewl/json/Json.java`), the atomic file store
(`kewl/persist/JsonStore.java`) and the software ImGui rasterizer (`client/imgui_sw.hpp`) are this
project's own code.

## Artwork, icons, fonts

The panel's icons and glyphs are drawn with ImGui draw-list calls in `launcher/panel_ui.hpp` and the
Java2D helpers in `kewl/ui/Hud.java`/`Theme.java` — there are no third-party image assets, icon sets
or font files vendored anywhere in the repository, and nothing is distributed that was not either
written here or licensed above. (`kewl/ui/Theme.java` loads fonts from the operating system at
runtime — Segoe UI / Arial / Consolas on Windows, DejaVu under Wine — it does not ship them.) The one
image resource, `resources/marker.png`, belongs to the Shortest Path plugin and is covered by its
notice above.
