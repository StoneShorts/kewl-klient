# dump_lambdas.py -- the closure registrations hide their leaves inside lambda-invoker
# functions; dump those bodies, plus the small label-thunk cluster near getVarp's leaf
# (isLoggedIn etc. live there), and the getMapOrigin lambda.
import idc

TARGETS = [
    ("invGetObjId_lambda", 0x1403BDC50, 90),
    ("invGetNum_lambda",   0x1403BDDE0, 90),
    ("w2s_lambda_a",       0x14024E190, 60),
    ("w2s_lambda_b",       0x14024E1E0, 60),
    ("ifType_chain_a",     0x1400F7640, 50),
    ("ifType_chain_b",     0x140252300, 60),
    ("getMapOrigin_lambda",0x1403CBEE0, 70),
    ("getVarp_leaf_zone",  0x1400F8B60, 160),   # thunk cluster: getVarp..getIntegerVarc..
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

print("L_BEGIN\n" + "\n".join(out) + "\nL_END")
