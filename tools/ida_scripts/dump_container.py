# dump_container.py -- sub_140032610 = invGetObjId implementation.
import idc

RANGES = [("container_impl", 0x140032610, 0x140032610 + 0x130)]

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

print("C_BEGIN\n" + "\n".join(out) + "\nC_END")
