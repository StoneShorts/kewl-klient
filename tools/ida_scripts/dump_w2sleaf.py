# dump_w2sleaf.py -- validate the projection-leaf candidate on 240-7: camera triple shape.
import idc

RANGES = [("w2s_leaf_2407", 0x140220320, 0x140220320 + 0x100)]

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

print("D_BEGIN\n" + "\n".join(out) + "\nD_END")
