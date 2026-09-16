# dump_w2s.py -- follow the Graphics worldToScreenCoord closure chain to the projection leaf.
import idc

TARGETS = [
    ("w2s_lambda_a", 0x14024E190, 40),
    ("w2s_lambda_b", 0x14024E1E0, 40),
]

out = []
for tag, ea, n in TARGETS:
    out.append(f"===== {tag} {hex(ea)} =====")
    a, cnt = ea, 0
    while a != idc.BADADDR and cnt < n:
        line = idc.generate_disasm_line(a, 0)
        if line:
            out.append(f"{hex(a)}: {line}")
            cnt += 1
        a = idc.next_head(a, a + 16)
    out.append("")

print("W_BEGIN\n" + "\n".join(out) + "\nW_END")
