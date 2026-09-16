# find_leaves.py -- automate step two of the recipe: from each anchor string reference,
# find the leaf function pointer the registration stores next to the name, then dump the
# leaf's own disassembly (where the rip-relative globals and struct displacements live).
import idautils, ida_funcs, ida_segment, idc, json

ANCHORS = [
    "getVarp", "getVarbit", "isLoggedIn", "getStatEffectiveLevel",
    "getStatBaseLevel", "getStatXP", "getTickCount",
    "npcCoord", "playerCoord", "coord", "npcName", "playerName",
    "invGetObjId", "invGetNum", "invSize", "invTotal",
    "playerFindSelf", "getNpcIdAll", "getMapOrigin", "getMapCoordinate",
]

text = ida_segment.get_segm_by_name(".text")
T_LO, T_HI = text.start_ea, text.end_ea

def is_text(addr):
    return T_LO <= addr < T_HI

def rip_lea_target(line):
    # idc line like: lea r8, sub_1400F8B60  or lea rax, [rip+X] with comment
    return None

wanted = {}
for s in idautils.Strings():
    try:
        t = str(s)
    except Exception:
        continue
    if t in ANCHORS:
        wanted.setdefault(t, []).append(s.ea)

report = {}
for name in ANCHORS:
    entries = []
    for sea in wanted.get(name, []):
        for xr in idautils.XrefsTo(sea):
            # walk forward from the string ref within its function, collecting
            # lea instructions whose target is a .text address => leaf candidates.
            f = ida_funcs.get_func(xr.frm)
            if not f:
                continue
            leaves = []
            a = idc.next_head(xr.frm, f.end_ea)
            steps = 0
            while a != idc.BADADDR and a < f.end_ea and steps < 40:
                line = idc.generate_disasm_line(a, 0) or ""
                if line.startswith("lea"):
                    v = idc.get_operand_value(a, 1)
                    if v and is_text(v) and ida_funcs.get_func(v):
                        leaves.append((hex(a), line, hex(v)))
                if len(leaves) >= 3:
                    break
                a = idc.next_head(a, f.end_ea)
                steps += 1
            entries.append({"ref": hex(xr.frm), "func": hex(f.start_ea), "leaves": leaves[:3]})
    report[name] = entries

# now dump the leaf bodies for the single-leaf anchors
def dump_func(ea, max_instr=90):
    f = ida_funcs.get_func(ea)
    if not f:
        return [f"(no function at {hex(ea)})"]
    lines = []
    a = f.start_ea
    n = 0
    while a != idc.BADADDR and a < f.end_ea and n < max_instr:
        line = idc.generate_disasm_line(a, 0)
        if line:
            lines.append(f"{hex(a)}: {line}")
        a = idc.next_head(a, f.end_ea)
        n += 1
    return lines

bodies = {}
LEAF_PICK = {
    "getVarp": 0, "getVarbit": 0, "isLoggedIn": 0, "getStatEffectiveLevel": 0,
    "getStatBaseLevel": 0, "getStatXP": 0, "getTickCount": 0,
    "npcCoord": 0, "playerCoord": 0, "coord": 1, "npcName": 0, "playerName": 0,
    "invGetObjId": 0, "invGetNum": 0, "playerFindSelf": 0, "getNpcIdAll": 0,
    "getMapOrigin": 0, "getMapCoordinate": 0,
}
for name, pick in LEAF_PICK.items():
    ents = report.get(name, [])
    if not ents or not ents[0]["leaves"]:
        continue
    ent = ents[0] if len(ents) == 1 else (ents[1] if name == "coord" and len(ents) > 1 else ents[0])
    if pick >= len(ent["leaves"]):
        continue
    leaf_ea = int(ent["leaves"][pick][2], 16)
    bodies[f"{name}@{ent['leaves'][pick][2]}"] = dump_func(leaf_ea)

print("LEAVES_JSON=" + json.dumps(report))
print("BODIES_BEGIN")
for k, v in bodies.items():
    print(f"===== {k} =====")
    print("\n".join(v))
print("BODIES_END")
