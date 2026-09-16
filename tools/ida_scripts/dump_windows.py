# dump_windows.py -- for each anchor string, print disassembly around each xref so the
# sol2 registration shape (label thunk vs closure) and the leaf pointer are visible.
import idautils, idc, json

WINDOWS = [
    "getVarp", "getVarbit", "isLoggedIn", "getStatEffectiveLevel",
    "getStatBaseLevel", "getStatXP", "getTickCount",
    "npcCoord", "playerCoord", "coord", "npcName", "playerName",
    "invGetObjId", "invGetNum", "playerFindSelf", "getNpcIdAll",
    "worldToScreenCoord", "ifType", "getMapOrigin", "getMapCoordinate",
]

wanted = {}
for s in idautils.Strings():
    try:
        t = str(s)
    except Exception:
        continue
    if t in WINDOWS:
        wanted.setdefault(t, []).append(s.ea)

out = []
for name in WINDOWS:
    for ea in wanted.get(name, []):
        for xr in idautils.XrefsTo(ea):
            lo, hi = xr.frm - 0xB0, xr.frm + 0xB0
            out.append(f"===== {name} @ {hex(ea)}  ref@ {hex(xr.frm)} =====")
            a = lo
            while a < hi:
                line = idc.generate_disasm_line(a, 0)
                if line:
                    out.append(f"{hex(a)}: {line}")
                a = idc.next_head(a, hi)
            out.append("")

print("DISASM_BEGIN\n" + "\n".join(out) + "\nDISASM_END")
