# test_recipes.py -- validate the resolver's decoder + recipes against the REAL
# 240-7 binary, using function starts taken from the IDA session. The same logic
# is implemented in client/resolve_pe.hpp + client/resolve.hpp; this file is the
# executable specification it must reproduce.
import pefile, struct

PE = pefile.PE(r'C:\Users\trump\Desktop\KewlClient\kewl-klient\build\dist\osclient.exe', fast_load=True)
BASE = PE.OPTIONAL_HEADER.ImageBase
DATA = PE.get_memory_mapped_image()

def read(va, n):
    off = va - BASE
    return bytes(DATA[off:off + n])

def u32(va): return struct.unpack_from('<I', DATA, va - BASE)[0]

# ---- function starts from .pdata (same binary search the C++ does) ----------
pdata = next(s for s in PE.sections if s.Name.decode().rstrip('\x00') == '.pdata')
p_lo, p_hi = pdata.VirtualAddress, pdata.VirtualAddress + pdata.Misc_VirtualSize

def func_start(va):
    lo, hi = p_lo, p_hi
    while lo + 12 <= hi:
        mid = lo + ((hi - lo) // 12) * 12
        begin, end = u32(BASE + mid), u32(BASE + mid + 4)
        if va < BASE + begin: hi = mid
        elif va >= BASE + end: lo = mid + 12
        else: return BASE + begin
    return 0

# ---- bounded decoder (mirrors resolve_pe.hpp) --------------------------------
def disasm(va, nbytes):
    b = read(va, nbytes)
    out = []
    i = 0
    def fits(off, n):
        return off + n <= len(b)
    def rd_i32(off):
        return struct.unpack_from('<i', b, off)[0] if fits(off, 4) else None

    # memory-operand layout for a modrm byte at mp:
    #   returns (sibLen, dispLen, dispPos, ripRelative)
    def memop(modrm, mp):
        mod, rm = modrm >> 6, modrm & 7
        if mod == 3:
            return (0, 0, mp + 1, False)
        if mod == 0 and rm == 5:
            return (0, 4, mp + 1, True)          # rip-relative, no SIB
        sib = 1 if rm == 4 else 0
        base5 = sib and fits(mp + 1, 1) and (b[mp + 1] & 7) == 5
        d = 4 if (mod == 0 and base5) else (1 if mod == 1 else (4 if mod == 2 else 0))
        return (sib, d, mp + 1 + sib, False)

    def memop_len(modrm, mp):
        s, d, _, _ = memop(modrm, mp)
        return s + d

    while i < len(b):
        if b[i] == 0xCC:
            i += 1
            continue
        insn = {'addr': va + i}
        p = 0
        while p < len(b) - i:
            c = b[i + p]
            if c in (0x66, 0x67, 0xF2, 0xF3, 0x2E, 0x3E, 0x26, 0x36, 0x64, 0x65):
                p += 1; continue
            if (c & 0xF0) == 0x40:
                p += 1; continue
            break
        if i + p >= len(b): break
        op = b[i + p]
        L = 0
        rex = (i + p > 0) and (b[i + p - 1] & 0xF0) == 0x40 and p > 0

        # helper: decode a modrm-consuming instruction whose opcode byte is at i+p
        # (with optional REX before it). opnd after modrm: imm8/imm32.
        def modrm_insn(mp, immLens=(0,)):
            if not fits(mp, 1):
                return None
            modrm = b[mp]
            s, d, dispPos, rip = memop(modrm, mp)
            if rip:
                rel = rd_i32(dispPos)
                if rel is None:
                    return None
                insn['rip'] = va + dispPos + 4 + rel          # rip base = end of instruction
                return ((dispPos + 4) - i, True)              # full length incl. prefixes
            if d and not fits(dispPos, d):
                return None
            if d == 1:
                insn['disp'] = b[dispPos] - 256 if b[dispPos] >= 0x80 else b[dispPos]
            elif d == 4:
                insn['disp'] = struct.unpack_from('<i', b, dispPos)[0]
            total = (dispPos + d) - i                          # full length incl. prefixes
            return (total, False)

        # handle REX by treating opcode stream with rex consumed: positions relative
        # NOTE: 'p' already skips prefixes INCLUDING rex; so opcode byte is at i+p,
        # modrm at i+p+1. The REX-fallback path below handles the 0F two-byte ops.
        if op == 0x8B:
            r = modrm_insn(i + p + 1)
            if r is None: break
            L = r[0]
            if r[1]: insn['op'] = 'mov_r64_rip'
            elif 'disp' in insn: insn['op'] = 'mov_r64_disp'
            else: insn['op'] = 'mov_r64_rm'
        elif op == 0x8D:
            r = modrm_insn(i + p + 1)
            if r is None: break
            L = r[0]
            insn['op'] = 'lea_rip' if r[1] else 'lea'
        elif op in (0x89, 0xC7):
            mp = i + p + 1
            r = modrm_insn(mp)
            if r is None: break
            L = r[0]
            imm = 4 if op == 0xC7 else 0
            # mod==3 (register) C7 still carries imm32
            if (b[mp] >> 6) == 3:
                L = p + 2 + imm
            else:
                L += imm
            insn['op'] = 'store_disp' if 'disp' in insn else 'store'
        elif op == 0x83:
            mp = i + p + 1
            r = modrm_insn(mp)
            if r is None: break
            reg = (b[mp] >> 3) & 7
            L = r[0] + 1
            if reg == 7 and 'disp' in insn:
                raw = b[i + L - 1]
                insn['imm'] = raw if raw < 0x80 else raw - 256
                insn['op'] = 'cmp_ea_imm8'
        elif op in (0x63, 0x39, 0x3B, 0x31, 0x33, 0x85, 0x29, 0x01, 0x23, 0x0B, 0x88, 0x8A):
            mp = i + p + 1
            r = modrm_insn(mp)
            if r is None: break
            L = r[0]
            insn['op'] = 'alu'
        elif op == 0xFF or op == 0xF7:
            mp = i + p + 1
            r = modrm_insn(mp)
            if r is None: break
            L = r[0] + (4 if (op == 0xF7 and (b[mp] >> 6) != 3) else 0)
            insn['op'] = 'grp'
        elif op == 0xE8:
            rel = rd_i32(i + p + 1)
            if rel is None: break
            insn['op'] = 'call'
            insn['target'] = va + i + p + 5 + rel
            L = p + 5
        elif 0xB8 <= op <= 0xBF:
            L = p + 5                       # mov r32, imm32
        elif 0x70 <= op <= 0x7F or op == 0xEB:
            L = p + 2                       # jcc rel8 / jmp rel8
        elif op == 0x0F:
            op2 = b[i + p + 1] if i + p + 1 < len(b) else 0
            if 0x80 <= op2 <= 0x8F:
                L = p + 6                   # jcc rel32
            elif op2 in (0x1F, 0x10, 0x11, 0x28, 0x29, 0x2E, 0x2F, 0x54, 0x57,
                         0xB6, 0xB7, 0xBE, 0xBF, 0xAF, 0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x9C, 0x9E, 0xB0, 0xB1):
                mp = i + p + 2
                r = modrm_insn(mp)
                if r is None: break
                L = r[0]
            else:
                L = 0
        elif op in (0x98, 0x99, 0x9C, 0x9D, 0xF8, 0xF9, 0xFC, 0xFD, 0x50, 0x51, 0x52, 0x53, 0x55, 0x56, 0x57, 0x58, 0x5A, 0x5B, 0x5D, 0x5E, 0x5F):
            L = p + 1                       # single-byte, no modrm
        if L == 0:
            i += 1
            continue
        i += L
        out.append(insn)
    return out

# ---- expected values from IDA (240-7) -----------------------------------------
EXPECT = {
    'clientObjPtr': 0xE95668, 'varpArrayPtr': 0x155C508,
    'skillEffective': 0x3360, 'skillBase': 0x33C4, 'skillXp': 0x3428,
    'gameState': 0x2160, 'tickCountAlt': 0x31A8,
    'registryMap': 0xC9C8, 'sceneNpcUids': 0xD0, 'sceneNpcUidCount': 0xD8,
    'entitySceneX': 0x3F0, 'entitySceneY': 0x418,
    'entityDefPtr': 0x730, 'defName': 0x8,
    'containerBuckets': 0x154C500, 'containerMask': 0x154C508,
    'getVarbit': 0x5B52C0,
}

FUNCS = {
    'getStatEffectiveLevel': 0x1400F8A70, 'getStatBaseLevel': 0x1400F8AD0,
    'getStatXP': 0x1400F8AA0, 'getVarp': 0x1400F8B60,
    'isLoggedIn': 0x1400F8B90, 'getTickCount': 0x1400F8B80,
    'getNpcIdAll': 0x1403AE450, 'npcCoord': 0x1403AF450,
    'npcName': 0x1401EE430, 'invGetObjId_lambda': 0x1401AB0F0,
}

ok, bad = 0, []

# client cell from stat leaves
for fn in ('getStatEffectiveLevel',):
    for in_ in disasm(FUNCS[fn], 64):
        if in_.get('op') == 'mov_r64_rip' and 'rip' in in_:
            cell = in_['rip'] - BASE
            print(f"{fn}: client cell = {hex(cell)}  expect {hex(EXPECT['clientObjPtr'])}", 'OK' if cell == EXPECT['clientObjPtr'] else 'FAIL')
            ok += cell == EXPECT['clientObjPtr']; break

# varp cell
for in_ in disasm(FUNCS['getVarp'], 32):
    if in_.get('op') == 'mov_r64_rip' and 'rip' in in_:
        v = in_['rip'] - BASE
        print(f"getVarp: varp cell = {hex(v)} expect {hex(EXPECT['varpArrayPtr'])}", 'OK' if v == EXPECT['varpArrayPtr'] else 'FAIL')
        ok += v == EXPECT['varpArrayPtr']; break

# gameState from isLoggedIn
for in_ in disasm(FUNCS['isLoggedIn'], 64):
    if in_.get('op') == 'cmp_ea_imm8' and in_.get('imm') == 30:
        print(f"isLoggedIn: GAME_STATE = {hex(in_['disp'])} expect {hex(EXPECT['gameState'])}", 'OK' if in_['disp'] == EXPECT['gameState'] else 'FAIL')
        ok += in_['disp'] == EXPECT['gameState']; break

# tickCountAlt
for in_ in disasm(FUNCS['getTickCount'], 32):
    if in_.get('op') == 'mov_r64_disp' and in_.get('disp', 0) > 0x1000:
        print(f"getTickCount: TICKCOUNT_ALT = {hex(in_['disp'])} expect {hex(EXPECT['tickCountAlt'])}", 'OK' if in_['disp'] == EXPECT['tickCountAlt'] else 'FAIL')
        ok += in_['disp'] == EXPECT['tickCountAlt']; break

# registry + scene uid array from getNpcIdAll: the leaf reads GROUPS then GROUP_COUNT
# (0xC9E8, 0xC9F0); REGISTRY_MAP is groups-0x20 (validated by npcCoord's lea [rbp+map]).
insns = disasm(FUNCS['getNpcIdAll'], 400)
cell = next((i['rip'] - BASE for i in insns if 'rip' in i), 0)
disps = [i['disp'] for i in insns if i.get('op') == 'mov_r64_disp' and 0x100 < i.get('disp', 0) < 0x100000]
groups, gcount = disps[0], disps[1]
print(f"getNpcIdAll: GROUPS/COUNT = {hex(groups)}/{hex(gcount)} expect {hex(0xC9E8)}/{hex(0xC9F0)}",
      'OK' if (groups, gcount) == (0xC9E8, 0xC9F0) and gcount == groups + 8 else 'FAIL')
ok += (groups, gcount) == (0xC9E8, 0xC9F0) and gcount == groups + 8

# entity coords from npcCoord LOADS (mov eax,[rsi+X] ... mov eax,[rsi+X+0x28])
insns = disasm(FUNCS['npcCoord'], 500)
d = [i['disp'] for i in insns
     if i.get('op') in ('mov_r64_disp', 'mov_r64_rm') and i.get('disp', 0) and 0x100 < i['disp'] < 0x1000]
pair = next(((a, b) for a, b in zip(d, d[1:]) if b - a == 0x28), (0, 0))
print(f"npcCoord: ENTITY_SCENE_X/Y = {hex(pair[0])}/{hex(pair[1])} expect {hex(EXPECT['entitySceneX'])}/{hex(EXPECT['entitySceneY'])}",
      'OK' if pair == (EXPECT['entitySceneX'], EXPECT['entitySceneY']) else 'FAIL')
ok += pair == (EXPECT['entitySceneX'], EXPECT['entitySceneY'])

# def ptr from npcName
insns = disasm(FUNCS['npcName'], 300)
defptr = next((i['disp'] for i in insns if i.get('op') == 'mov_r64_disp' and i.get('disp', 0) > 0x100), 0)
print(f"npcName: ENTITY_DEF_PTR = {hex(defptr)} expect {hex(EXPECT['entityDefPtr'])}", 'OK' if defptr == EXPECT['entityDefPtr'] else 'FAIL')
ok += defptr == EXPECT['entityDefPtr']

# container globals: lambda -> call impl -> impl reads [MASK] then [BUCKETS]
# (adjacent, mask == buckets + 8), plus the node layout off the same impl.
insns = disasm(FUNCS['invGetObjId_lambda'], 0x120)
impl = next((i['target'] for i in insns if i.get('op') == 'call'), 0)
print('lambda call target:', hex(impl) if impl else 'NONE', '(IDA: 0x140032610)')
insns = disasm(impl, 0x100)
rips = sorted(i['rip'] - BASE for i in insns if 'rip' in i and (i['rip'] - BASE) > 0x100000)
adj = next(((a, b) for a, b in zip(rips, rips[1:]) if b - a == 8), (0, 0))
buckets, mask = adj
print(f"container impl: BUCKETS/MASK = {hex(buckets)}/{hex(mask)} expect {hex(EXPECT['containerBuckets'])}/{hex(EXPECT['containerMask'])}",
      'OK' if adj == (EXPECT['containerBuckets'], EXPECT['containerMask']) else 'FAIL')
ok += adj == (EXPECT['containerBuckets'], EXPECT['containerMask'])
# node layout: disps read off the node register in the impl
ndisps = sorted(set(i['disp'] for i in insns if 'disp' in i and 0 <= i.get('disp', -1) <= 0x40))
# +0x00 (the container-id compare) has no disp byte, so it never appears as a key
expected_nodes = [8, 0x10, 0x20, 0x28, 0x38]
print('node disps seen:', [hex(x) for x in ndisps], '(IDA: 0x0-implicit, 0x8, 0x10, 0x20, 0x28, 0x38)')
print('node id/qty/next layout derived:', 'OK' if ndisps == expected_nodes else 'CHECK')
ok += ndisps == expected_nodes

print(f"\n{ok} checks passed")
