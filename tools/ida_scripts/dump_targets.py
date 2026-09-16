# dump_targets.py -- targeted disassembly for the cases the leaf-walk missed:
#  (a) windows AFTER a string xref inside its registration (label-thunk registrations)
#  (b) bodies of specific functions (closure chains to real RVAs)
import idautils, ida_funcs, idc, json

# anchor -> (max instructions after the xref to dump)
WINDOWS = {
    "isLoggedIn": 70,
    "invGetObjId": 60,
    "invGetNum": 60,
    "getMapOrigin": 70,
    "getMapCoordinate": 70,
    "worldToScreenCoord": 90,
    "ifType": 90,
    "getTickCount": 50,
}

FUNCS = {
    "worldToScreenCoord_reg": (0x14021FB60, 260),
}

wanted = {}
for s in idautils.Strings():
    try:
        t = str(s)
    except Exception:
        continue
    if t in WINDOWS:
        wanted.setdefault(t, []).append(s.ea)

out = []
for name, n in WINDOWS.items():
    for sea in wanted.get(name, []):
        for xr in idautils.XrefsTo(sea):
            out.append(f"===== WIN {name} str={hex(sea)} ref={hex(xr.frm)} +{n} =====")
            a, cnt = xr.frm, 0
            f = ida_funcs.get_func(xr.frm)
            hi = f.end_ea if f else xr.frm + n * 8
            while a != idc.BADADDR and a < hi and cnt < n:
                line = idc.generate_disasm_line(a, 0)
                if line:
                    out.append(f"{hex(a)}: {line}")
                    cnt += 1
                a = idc.next_head(a, hi)
            out.append("")

for tag, (ea, n) in FUNCS.items():
    out.append(f"===== FUNC {tag} {hex(ea)} =====")
    a, cnt = ea, 0
    while a != idc.BADADDR and cnt < n:
        line = idc.generate_disasm_line(a, 0)
        if line:
            out.append(f"{hex(a)}: {line}")
            cnt += 1
        a = idc.next_head(a, a + 16)
    out.append("")

print("T_BEGIN\n" + "\n".join(out) + "\nT_END")
