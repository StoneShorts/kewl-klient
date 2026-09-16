# find_anchors.py -- locate KewlKlient's Lua-binding anchor strings in the loaded IDB,
# with the functions that reference each. Output: JSON on stdout.
# Run via POST /api/executebypath {"path": "..."}.
import idautils, ida_funcs, json

ANCHORS = [
    "worldToScreenCoord", "npcCoord", "playerCoord", "npcName", "playerName",
    "getVarp", "getVarbit", "invGetObjId", "invGetNum", "invSize", "invTotal",
    "ifType", "getMapOrigin", "getMapCoordinate", "isLoggedIn",
    "getStatEffectiveLevel", "getStatBaseLevel", "getStatXP",
    "playerFindSelf", "getNpcIdAll", "coord", "worldid", "getTickCount",
    "getGameState", "getWorld", "getWidget",
]

wanted = {}
for s in idautils.Strings():
    try:
        t = str(s)
    except Exception:
        continue
    if t in ANCHORS:
        wanted.setdefault(t, []).append(s.ea)

res = {}
for name, eas in sorted(wanted.items()):
    entries = []
    for ea in eas:
        refs = []
        for xr in idautils.XrefsTo(ea):
            f = ida_funcs.get_func(xr.frm)
            refs.append({
                "from": hex(xr.frm),
                "func": hex(f.start_ea) if f else None,
                "funcend": hex(f.end_ea) if f else None,
            })
        entries.append({"string_ea": hex(ea), "refs": refs})
    res[name] = entries

missing = [a for a in ANCHORS if a not in wanted]
print("RESULT_JSON=" + json.dumps({"found": res, "missing": missing}))
