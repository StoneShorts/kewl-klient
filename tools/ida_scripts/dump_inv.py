# dump_inv.py -- the inv closure's lambda pointer is stored BEFORE the name string assign;
# dump the window before invGetObjId's ref, plus the local-player resolve helper body.
import idc

RANGES = [
    ("invGetObjId_before", 0x1403A9560, 0x1403A96CA),
    ("invGetNum_before",   0x1403A9700, 0x1403A9812),
    ("localPlayerHelper",  0x1400A00B0, 0x1400A00B0 + 0x140),
]

out = []
for tag, lo, hi in RANGES:
    out.append(f"===== {tag} {hex(lo)}..{hex(hi)} =====")
    a = lo
    while a != idc.BADADDR and a < hi:
        line = idc.generate_disasm_line(a, 0)
        if line:
            out.append(f"{hex(a)}: {line}")
        a = idc.next_head(a, hi)
    out.append("")

print("I_BEGIN\n" + "\n".join(out) + "\nI_END")
