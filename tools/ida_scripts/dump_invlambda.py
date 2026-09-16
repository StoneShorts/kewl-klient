# dump_invlambda.py -- where does the runtime invGetObjId lambda read the container table?
import idc

RANGES = [
    ("inv_lambda_runtime", 0x1401AB0F0, 0x1401AB0F0 + 0x180),
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

print("V_BEGIN\n" + "\n".join(out) + "\nV_END")
