// panel.hpp -- the control panel's own window: the part of the client that can be clicked.
//
// The panel is a top-level WS_POPUP window OWNED by the KewlKlient host window (dllmain.cpp docks it
// into the strip right of the game), so it tracks the client instead of floating over the desktop
// -- an owned window hides with its owner and the run() loop hides it when the host is minimized.
// It is deliberately NOT a layered window: it has no per-pixel alpha -- it is an opaque sidebar,
// exactly like RuneLite's. That means plain BitBlt painting, and WM_PAINT re-blits the last
// presented frame so the panel survives being uncovered without waiting for the next Java frame.
//
// Why a top-level and not a child of the host: see the creation site in dllmain.cpp -- under Wine a
// child panel has no X window of its own and Wine's cursor application over it is unreliable (blank
// pointer on some boots). A top-level window gets an X window and a reliable class cursor.
//
// Mouse events go back the other way as upcalls: the panel window's WndProc (dllmain.cpp) calls
// kk::panelMouse, which calls kewl.KewlKlient.panelMouse. Java draws the panel opaque where it is
// interactive and dark everywhere else, so what you see is what you can click.
#pragma once
#include "overlay.hpp"

namespace kk::panel {

// Reuse the Layered struct for its DIB back buffer only -- nothing here calls UpdateLayeredWindow.
inline Layered g_panel;

/// Re-blit the last presented frame into the window. Safe from WM_PAINT: touches no Java state.
inline void blit_last() {
    if (!g_panel.hwnd || !g_panel.memDc || g_panel.width <= 0) return;
    HDC dc = GetDC(g_panel.hwnd);
    BitBlt(dc, 0, 0, g_panel.width, g_panel.height, g_panel.memDc, 0, 0, SRCCOPY);
    ReleaseDC(g_panel.hwnd, dc);
}

/// Copy Java's finished frame (w*h premultiplied ARGB) into the panel's back buffer, WITHOUT blitting
/// it. Split from present() for the same reason Layered::copyIn exists: the caller does this inside a
/// GetPrimitiveArrayCritical window, where ensure()'s CreateDIBSection and blit_last()'s BitBlt would
/// both be blocking work the JVM is not allowed to run a GC during. Returns false when there is no
/// panel to copy into.
inline bool copyIn(const void* src, int w, int h) {
    if (!g_panel.hwnd || !IsWindow(g_panel.hwnd)) return false;
    return g_panel.copyIn(src, w, h);
}

/// Copy Java's finished frame into the panel and blit it. `game` is unused since the panel no longer
/// positions itself -- layoutEmbed() in dllmain.cpp owns that -- but the signature stays so the call
/// site in jvm.hpp does not care where the panel lives.
inline void present(HWND game, const void* src, int w, int h) {
    (void)game;
    if (!copyIn(src, w, h)) return;
    blit_last();
}

}  // namespace kk::panel
