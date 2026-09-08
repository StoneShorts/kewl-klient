#!/usr/bin/env bash
# tools/launcher-smoke.sh -- bring the ImGui launcher up under Wine and hand the integrator the
# probes. It does NOT click anything: pressing "+ client" spawns and injects a real game, and that
# is the human's live test (see the LIVE PROBES section this script prints).
#
#   tools/launcher-smoke.sh              # offline strip check + live launcher, then the probe list
#   tools/launcher-smoke.sh --no-live    # only the offline strip check (no window at all)
#   tools/launcher-smoke.sh --secs 20    # keep the live launcher up for N seconds (default 15)
#
# What each stage proves:
#   offline  -- KewlKlient.exe starts under Wine at all, ImGui initialises with the software
#               rasterizer, and the strip renders the bridge's model format (KEWL_FAKE_PANEL feeds
#               the real parser a synthetic model region). Verified by pixel values in a PAM dump of
#               the window's own DIB -- never a screenshot, which cannot see Wine's GDI content.
#   live     -- the launcher's window exists on the X server and paints the home screen; everything
#               past that needs a mouse and a real osclient.exe.

set -euo pipefail
cd "$(dirname "$0")/.."

DIST=build/wine-dist
LIVE_SECS=15
NO_LIVE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --no-live) NO_LIVE=1 ;;
        --secs)    LIVE_SECS="$2"; shift ;;
        *) echo "unknown option: $1"; exit 2 ;;
    esac
    shift
done

command -v wine >/dev/null || { echo "wine not on PATH"; exit 1; }
for f in KewlKlient.exe kewlklient.dll kewlklient.jar kewlklient.ini; do
    [ -f "$DIST/$f" ] || { echo "$DIST/$f missing -- run sh tools/wine-setup.sh first"; exit 1; }
done

# -----------------------------------------------------------------------------------------
# 1. OFFLINE: the strip, from a synthetic model region, verified by pixel value.
# -----------------------------------------------------------------------------------------
echo "== offline strip check (KEWL_FAKE_PANEL, no game, no injection)"
DUMP=/tmp/kk-smoke-frame.pam
rm -f "$DUMP"
# A run exits only via timeout (124) -- the launcher is a window loop, that IS the success case.
KEWL_FAKE_PANEL=1 KEWL_DUMP_FRAME="$DUMP" timeout 12 wine "$DIST/KewlKlient.exe" \
    >/tmp/kk-smoke-launcher.log 2>&1 || rc=$?
rc=${rc:-0}
if [ "$rc" != "124" ]; then
    echo "   FAILED: launcher exited early (rc=$rc) -- see /tmp/kk-smoke-launcher.log"
    exit 1
fi
[ -f "$DUMP" ] || { echo "   FAILED: no frame dumped -- KEWL_DUMP_FRAME never fired"; exit 1; }
if command -v python3 >/dev/null; then
    python3 - "$DUMP" <<'EOF'
import sys
data = open(sys.argv[1], 'rb').read()
px = data[data.index(b'ENDHDR\n') + 7:]
w = int(data.split(b'WIDTH ')[1].split()[0])
def pix(x, y):
    i = (y * w + x) * 4
    return tuple(px[i:i + 3])
# Theme colours the strip cannot accidentally produce (see launcher/panel_ui.hpp): the clear
# colour everywhere else, the RL_ORANGE active-tab rail edge, the green ON toggle.
clear = (31, 27, 27)          # 0xFF1B1B1F, the frame clear colour
orange = [(x, y) for y in range(0, 900, 2) for x in range(w - 40, w) if pix(x, y) == (220, 138, 0)]
green = [(x, y) for y in range(0, 900, 2) for x in range(w - 290, w) if pix(x, y) == (110, 220, 140)]
print(f"   frame {w}x{data.split(b'HEIGHT ')[1].split()[0].decode()}, "
      f"clear colour present: {pix(100, 400) == clear}, rail accent px: {len(orange)}, "
      f"toggle-ON px: {len(green)}")
ok = pix(100, 400) == clear and orange and green
print("   offline strip: " + ("OK -- ImGui panel renders under Wine" if ok else "FAILED"))
sys.exit(0 if ok else 1)
EOF
else
    echo "   (python3 absent -- inspect $DUMP by hand; it is a P7 PAM of the window DIB)"
fi

# -----------------------------------------------------------------------------------------
# 2. LIVE: the real launcher window, no fake data. Prints its pid, then the probes.
# -----------------------------------------------------------------------------------------
if [ "$NO_LIVE" = 1 ]; then echo; echo "live stage skipped (--no-live)"; exit 0; fi

echo
echo "== live launcher (no fake panel): pid printed below, window should show the home screen"
DISPLAY=${DISPLAY:-:0} wine "$DIST/KewlKlient.exe" >/tmp/kk-smoke-live.log 2>&1 &
WINE_PID=$!
echo "   launcher pid: $WINE_PID   (log: /tmp/kk-smoke-live.log)"
sleep 5

echo "   window probes (run these while it is up):"
if command -v wmctrl >/dev/null 2>&1; then
    echo "     wmctrl -l | grep -i kewlklient          # expect one 'KewlKlient' top-level window"
fi
if command -v xdotool >/dev/null 2>&1; then
    echo "     xdotool search --name '^KewlKlient$'   # expect one X window id"
fi
echo "     xprop -root _NET_CLIENT_LIST           # cross-check the id list"

trap 'kill $WINE_PID 2>/dev/null || true' EXIT
sleep "$LIVE_SECS"
echo
echo "launcher still running after ${LIVE_SECS}s: no crash. Killing it now; the live probes follow."
echo
echo "=========================================================================================="
echo "LIVE PROBES (the human does these -- this script never clicks the button):"
echo
echo "  0. Point kewlklient.ini (next to $DIST/KewlKlient.exe) at the game, e.g.:"
echo "       [kewl]"
echo "       game=Z:\\home\\me\\.wine\\drive_c\\Program Files\\osclient\\osclient.exe"
echo "     (the [kewlklient] section works too; dll= defaults to kewlklient.dll beside the exe)"
echo
echo "  1. Start the launcher, press '+ client'. Expect: status 'osclient.exe started (pid N)',"
echo "     then 'embedded. waiting for the DLL bridge...' and the game's window inside the"
echo "     launcher's, panel strip (286px) on the right."
echo
echo "  2. BRIDGE SUCCESS looks like: the strip's header note changes from 'bridge: opening...'"
echo "     to nothing, and the plugin list shows the REAL plugin names (Player visuals,"
echo "     Npc visuals, Woodcutter, Shortest Path, Test Rlite) -- not the status line. That list"
echo "     can only come from Java through the bridge."
echo
echo "  3. Toggle one plugin's switch. Expect: the pill flips green at once (the optimistic"
echo "     echo), the plugin's overlay appears/disappears, and the status column changes -- the"
echo "     enable travelled edit-ring -> DLL -> Plugin.setEnabled. In the game's stdout"
echo "     (/tmp/osrs-wine.log) look for the [bridge] / plugin log lines."
echo
echo "  4. Click a plugin name (gear or label) to open its config; combos, sliders and steppers"
echo "     write edits the same way -- a changed value must survive a panel refresh (the launcher"
echo "     re-reads the model whenever Java's revision moves, so a value that snaps back means the"
echo "     edit did not reach Setting.set)."
echo
echo "  5. Diagnostics: the debug tab (rail, bottom icon) shows bridge up/down, the model"
echo "     revision and the edit sequence numbers. If it stays 'bridge: opening...' for 30 s, the"
echo "     DLL never created the mapping -- check the game log for '[bridge]' lines and whether"
echo "     launcher mode engaged (the DLL logs nothing on success; the panel staying empty while"
echo "     overlays work is the signature of a rejected snapshot)."
echo "=========================================================================================="
