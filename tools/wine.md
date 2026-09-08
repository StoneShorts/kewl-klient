# Testing KewlKlient on Linux, under Wine

The shipped target is Windows and stays Windows. But the game itself runs fine under Wine 10
(64-bit; the missing `wine32` does not matter, osclient.exe is x64), and Wine implements every Win32
surface the DLL touches — remote-thread injection, layered windows, `GetAsyncKeyState`. So the whole
loop — build the DLL, inject, JVM, plugins, overlay — works on a Linux desktop. This is how the
offsets and plugins here get tested without a Windows machine.

## One-time setup

1. **llvm-mingw** (cross-compiler, no root needed):
   <https://github.com/mstorsjo/llvm-mingw/releases> — the `ucrt-ubuntu-*-x86_64.tar.xz`, unpacked
   anywhere (`/opt/llvm-mingw` or `~/tools`).
2. **CMake**: `pip install --user --break-system-packages cmake`, or the cmake.org Linux tarball.
3. **A Windows JRE** for `jvm.dll` (the DLL loads it at runtime): unpack any Windows x64 JDK/JRE
   (Temurin works) somewhere under `~/.wine/drive_c/` — e.g. `~/.wine/drive_c/jdk-17/`.

## Build

```bash
TOOLCHAIN=<llvm-mingw>/bin/x86_64-w64-mingw32-g++
cmake -DCMAKE_TOOLCHAIN_FILE=tools/mingw-toolchain.cmake.in ...   # see script below
```

`tools/wine-setup.sh` does all of it: builds `kewlklient.dll` + `KewlKlient.exe` with llvm-mingw,
builds the jar, and assembles `build/wine-dist/` (DLL, jar, exe, ini, `wine_inject.exe`).

Two things make the cross build different from the MSVC one, both handled in `CMakeLists.txt`:

- **FindJNI cannot work in cross mode** (no target JVM to link — we `LoadLibrary` `jvm.dll` at
  runtime anyway). The fallback takes headers from `$JAVA_HOME` and *generates* the win32
  `jni_md.h`. It must be the Windows one: `long` is 4 bytes on Windows, so the Linux header's
  `typedef long jlong` would silently make every 64-bit value 4 bytes.
- **llvm-mingw links libc++ as a DLL by default**, which would ship `libc++.dll` + `libunwind.dll`
  beside ours. `-static` folds them in and keeps the dist one file.

## Run

```bash
cd /tmp/osclient && WINEDEBUG=-all wine osclient.exe > /tmp/osrs-wine.log 2>&1 &
# log in...
cd build/wine-dist && wine wine_inject.exe 'Z:\build\wine-dist\kewlklient.dll'
```

- `wine_inject.exe` is the launcher's injection without the button — for scripting. Same rules as
  the launcher: it refuses to inject twice into one game session, so restart the game after a rebuild.
- The injected JVM's `System.out` goes to the **game's stdout**, i.e. `/tmp/osrs-wine.log`. The
  success line is `KewlKlient: 5 plugins`; plugin tick/render exceptions land there too.
- Known harmless noise on startup: one `NetworkInterface` stack trace from Java's entropy seeding —
  Wine's enumeration trips it, Java falls back to another source, nothing is affected.
- C++-side errors (`java=` wrong, jar missing) draw in red on the overlay itself, as on Windows.

### The launcher under Wine

`build/wine-dist/KewlKlient.exe` is the ImGui launcher: it spawns the game, injects the DLL, embeds
the game's window into its own and draws the 286px panel strip itself (software rasterizer, no
second GL context). Point it at the game first — `[kewl] game=` in `kewlklient.ini`, absolute or
relative to the exe; the DLL's own `[kewlklient] java=` is read from the same file:

```ini
[kewlklient]
java=C:\jdk-17.0.20.1+1-jre

[kewl]
game=Z:\home\me\.cache\osclient\osclient.exe
```

`tools/launcher-smoke.sh` brings the launcher up under Wine, verifies the panel strip offline
(`KEWL_FAKE_PANEL` — a synthetic model region through the real parser, checked by pixel values in a
PAM dump of the window DIB, never a screenshot), prints the launcher's pid, and then lists the live
probes. It never clicks "+ client" itself: spawning and injecting a real game is the human's test.

Two env switches are useful without a game at all: `KEWL_FAKE_PANEL=1` draws the strip from the
synthetic model (add `KEWL_FAKE_CONFIG=1` to push a config view, `KEWL_FAKE_TAB=debug|profiles` to
pick a tab), and `KEWL_DUMP_FRAME=<path>` writes frame 30 of the window's DIB as a P7 PAM.

## What does not work here (yet)

- **Verify everything you would on Windows**: layered-window overlays can flicker differently under
  Mutter/Wayland, and that is Wine's display stack, not the DLL's logic.
- The game-under-Wine needs a GPU with working GL; on a headless box nothing will render.
