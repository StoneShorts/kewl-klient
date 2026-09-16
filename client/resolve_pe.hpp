// resolve_pe.hpp -- the substrate under the runtime resolver: PE parsing, safe
// reads, anchor-string discovery, .pdata function starts, and a bounded x86-64
// instruction decoder.
//
// Dependency-free on purpose (winnt + CRT only) so the resolver cannot lean on
// the values it is deriving.
//
// The decoder is the C++ twin of the validated Python spec in
// tools/ida_scripts/test_recipes.py, which reproduced the IDA-verified
// client-240-7 recipes byte for byte. Its rules:
//   * decode a bounded window (a byte budget, not control flow -- early `ret`
//     paths do not stop the walk, recipes read past them);
//   * unknown bytes resync by ONE byte (never desync silently);
//   * mod -> SIB -> disp32 -> imm, always in that order; rip-relative is
//     mod=00,rm=101 with NO SIB, and its base is instruction-end (the classic
//     [rdx+rax*8]-reads-the-SIB-as-a-disp bug is what the Python spec exists
//     to have caught).
#pragma once
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winnt.h>
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <cstdio>
#include <string>
#include <vector>
#include <algorithm>

namespace kk::rsl::pe {

inline constexpr size_t MAX_ANCHOR_NAMES = 32;

using u8  = std::uint8_t;
using i8  = std::int8_t;
using i32 = std::int32_t;
using i64 = std::int64_t;
using u32 = std::uint32_t;
using u64 = std::uint64_t;
using uptr = std::uintptr_t;

inline u64 fnv1a(const u8* p, size_t n, u64 h = 1469598103934665603ULL) {
    for (size_t i = 0; i < n; ++i) { h ^= p[i]; h *= 1099511628211ULL; }
    return h;
}

// ---------------------------------------------------------------------------------
// safe read: ReadProcessMemory on SELF -- a bad address faults nothing, returns false
// ---------------------------------------------------------------------------------
inline bool safeRead(uptr addr, void* out, size_t n) {
    if (!addr || !out) return false;
    SIZE_T got = 0;
    return ::ReadProcessMemory(::GetCurrentProcess(), reinterpret_cast<LPCVOID>(addr),
                               out, n, &got) && got == n;
}
template <typename T>
inline bool safeReadVal(uptr addr, T& out) { return safeRead(addr, &out, sizeof(T)); }

// ---------------------------------------------------------------------------------
// module map + fingerprint (.text FNV-1a + PE checksum)
// ---------------------------------------------------------------------------------
struct ModuleMap {
    uptr base = 0;
    uptr textVa = 0, textEnd = 0, textSize = 0;
    uptr rdataVa = 0, rdataEnd = 0;
    uptr pdataVa = 0, pdataEnd = 0;
    // every WRITABLE data section, [begin, end) -- this client has TWO .data
    // stretches, and its interesting globals (containers, cells, registries)
    // live in the second one; inline string buffers live in .rdata, which is
    // exactly the discriminator the container recipe needs.
    std::vector<std::pair<uptr, uptr>> wdata;
    struct RF { u32 begin, end, unwind; };
    std::vector<RF> pdata;               // loaded once; .text function extents
    u64  textHash = 0;
    u32  peChecksum = 0;
    bool ok = false;

    bool isText(uptr a)  const { return ok && a >= textVa  && a < textEnd; }
    bool isRdata(uptr a) const { return ok && a >= rdataVa && a < rdataEnd; }
    bool isWdata(uptr a) const {
        if (!ok) return false;
        for (const auto& r : wdata) if (a >= r.first && a < r.second) return true;
        return false;
    }
};

inline bool initModuleMap(uptr base, ModuleMap& M) {
    M = ModuleMap{};
    M.base = base;
    u32 eLfanew = 0;
    if (!safeReadVal(base + offsetof(IMAGE_DOS_HEADER, e_lfanew), eLfanew) || !eLfanew) return false;
    u32 ntSig = 0;
    if (!safeReadVal(base + eLfanew, ntSig) || ntSig != IMAGE_NT_SIGNATURE) return false;
    IMAGE_NT_HEADERS nt;
    if (!safeReadVal(base + eLfanew, nt)) return false;
    M.peChecksum = nt.OptionalHeader.CheckSum;

    const auto firstSec = reinterpret_cast<const IMAGE_SECTION_HEADER*>(base + eLfanew + sizeof(u32) + sizeof(IMAGE_FILE_HEADER) + nt.FileHeader.SizeOfOptionalHeader);
    for (unsigned i = 0; i < nt.FileHeader.NumberOfSections; ++i) {
        IMAGE_SECTION_HEADER sh;
        if (!safeReadVal(uptr(firstSec + i), sh)) return false;
        char name[9] = {};
        std::memcpy(name, sh.Name, 8);
        uptr va = base + sh.VirtualAddress;
        uptr end = va + sh.Misc.VirtualSize;
        if      (!std::strcmp(name, ".text"))  { M.textVa = va; M.textEnd = end; M.textSize = sh.Misc.VirtualSize; }
        else if (!std::strcmp(name, ".rdata")) { M.rdataVa = va; M.rdataEnd = end; }
        else if (!std::strcmp(name, ".pdata")) { M.pdataVa = va; M.pdataEnd = end; }
        // every writable data section (IMAGE_SCN_MEM_WRITE), incl. multiple .data
        if (sh.Characteristics & IMAGE_SCN_MEM_WRITE) M.wdata.push_back({ va, end });
    }
    if (!M.textVa || !M.rdataVa || !M.textSize) return false;

    std::vector<u8> buf((size_t)M.textSize);
    if (!safeRead(M.textVa, buf.data(), buf.size())) return false;
    M.textHash = fnv1a(buf.data(), buf.size());

    // the .pdata function table, resident: every recipe asks for function starts
    if (M.pdataVa && M.pdataEnd > M.pdataVa) {
        const size_t n = (size_t)((M.pdataEnd - M.pdataVa) / 12);
        M.pdata.resize(n);
        if (!safeRead(M.pdataVa, M.pdata.data(), n * 12)) M.pdata.clear();
    }
    M.ok = true;
    return true;
}

// function [begin, end) from the resident .pdata table (binary search)
inline bool funcExtentFromPdata(const ModuleMap& M, uptr addr, uptr& begin, uptr& end) {
    if (!M.ok || M.pdata.empty() || !M.isText(addr)) return false;
    size_t lo = 0, hi = M.pdata.size();
    while (lo < hi) {
        const size_t mid = lo + (hi - lo) / 2;
        const uptr b = M.base + M.pdata[mid].begin, e = M.base + M.pdata[mid].end;
        if (addr < b) hi = mid;
        else if (addr >= e) lo = mid + 1;
        else { begin = b; end = e; return true; }
    }
    return false;
}

// function start from .pdata (RUNTIME_FUNCTION table, binary search)
inline uptr funcStartFromPdata(const ModuleMap& M, uptr addr) {
    uptr b = 0, e = 0;
    return funcExtentFromPdata(M, addr, b, e) ? b : 0;
}

// ---------------------------------------------------------------------------------
// anchors: every NUL-terminated whole string in .rdata that equals a wanted name,
// plus the .text instructions that rip-reference each string.
// ONE pass over .rdata (each chunk read once, compared against all names) -- the
// string table is ~3 MB, this is the resolver's dominant cost by design.
// ---------------------------------------------------------------------------------
struct AnchorHit {
    uptr strVa = 0;
    std::vector<uptr> refs;      // instruction addresses in .text that rip-reference strVa
};
struct Anchors {
    std::vector<AnchorHit> hits; // across all names
    std::vector<uptr> missNames; // diagnostics
};

// rip-ref xref: REX(0x48/0x49/0x4C/0x4D) + 8B/8D + modrm(mod=00,rm=101) + disp32
inline void addRipRefs(const std::vector<u8>& text, uptr textVa, AnchorHit& h) {
    if (h.refs.size() > 8) return;                      // enough for any recipe
    const size_t N = text.size();
    for (size_t i = 0; i + 7 < N; ++i) {
        const u8 rex = text[i];
        if (rex != 0x48 && rex != 0x49 && rex != 0x4C && rex != 0x4D) continue;
        const u8 opc = text[i + 1];
        if (opc != 0x8B && opc != 0x8D) continue;
        const u8 modrm = text[i + 2];
        if ((modrm & 0xC7) != 0x05) continue;
        i32 rel;
        std::memcpy(&rel, text.data() + i + 3, 4);
        const uptr target = textVa + i + 7 + (uptr)(i64)rel;
        if (target == h.strVa) {
            h.refs.push_back(textVa + i);
            if (h.refs.size() > 8) return;
        }
    }
}

// case-sensitive whole-string match against the wanted names
inline void buildAnchors(const ModuleMap& M, const char* const* names, size_t nNames,
                         size_t maxPerName, Anchors& out) {
    const uptr span = M.rdataEnd - M.rdataVa;
    const size_t CHUNK = 0x100000;
    std::vector<u8> buf(CHUNK + 64);
    size_t remaining[MAX_ANCHOR_NAMES] = {};
    for (size_t k = 0; k < nNames; ++k) remaining[k] = maxPerName;
    bool miss[MAX_ANCHOR_NAMES] = {};
    for (size_t k = 0; k < nNames; ++k) miss[k] = true;

    for (uptr off = 0; off < span; off += CHUNK) {
        const size_t n = (size_t)std::min<uptr>(CHUNK, span - off);
        if (!safeRead(M.rdataVa + off, buf.data(), n)) break;
        bool allDone = true;
        for (size_t k = 0; k < nNames; ++k) if (remaining[k]) { allDone = false; break; }
        if (allDone) break;
        for (size_t i = 0; i < n; ++i) {
            if (!buf[i]) continue;
            for (size_t k = 0; k < nNames; ++k) {
                if (!remaining[k]) continue;
                const size_t len = std::strlen(names[k]);
                if (i + len + 1 > n) continue;      // cannot judge across the boundary
                if (buf[i + len] != 0) continue;    // not NUL-terminated here
                if (std::memcmp(buf.data() + i, names[k], len) != 0) continue;
                if (i > 0 && buf[i - 1] != 0) continue;   // must be whole-string
                AnchorHit h;
                h.strVa = M.rdataVa + off + i;
                out.hits.push_back(h);
                --remaining[k];
                miss[k] = false;
                break;                              // one name per position
            }
        }
    }
    // xref pass needs the .text bytes
    std::vector<u8> text((size_t)std::min<uptr>(M.textSize, 0x1000000));
    const bool haveText = safeRead(M.textVa, text.data(), text.size());
    for (AnchorHit& h : out.hits) {
        if (haveText) addRipRefs(text, M.textVa, h);
    }
    for (size_t k = 0; k < nNames; ++k)
        if (miss[k]) out.missNames.push_back((uptr)names[k]);
}

// ---------------------------------------------------------------------------------
// bounded x86-64 decoder
// ---------------------------------------------------------------------------------
enum Op {
    OP_NONE = 0,    // decoded for alignment only
    OP_MOV_R_RIP,   // mov r64, [rip+X]
    OP_MOV_R_DISP,  // mov r64, [reg+disp] (or [reg] with disp 0 -- in.disp says which)
    OP_MOV_R_RM,    // mov r64, r/m (register or no-disp forms: alignment only)
    OP_LEA_RIP,     // lea r64, [rip+X]
    OP_STORE_DISP,  // mov [reg+disp], r64/imm32
    OP_CMP_EA_IMM,  // cmp/arith [reg+disp], imm (reg field 7, op 0x83)
    OP_CALL_REL,    // call rel32
    OP_JMP_REL,     // jmp rel32 (tail call)
    OP_ALU,         // other modrm-consuming ops (alignment only)
};

struct Insn {
    Op   op = OP_NONE;
    uptr addr = 0;
    i32  disp = 0;         // memory displacement
    uptr ripTarget = 0;    // absolute VA for rip-relative operands
    uptr funcTarget = 0;   // call/jmp target
    i32  imm = 0;          // immediate (OP_CMP_EA_IMM)
};

// memory-operand layout for a modrm byte at mp: (sib, d, dispPos, rip)
struct MemOp { size_t sib; size_t d; size_t dispPos; bool rip; };
inline MemOp memop(const u8* b, size_t N, u8 modrm, size_t mp) {
    const u8 mod = modrm >> 6, rm = modrm & 7;
    if (mod == 3) return {0, 0, mp + 1, false};
    if (mod == 0 && rm == 5) return {0, 4, mp + 1, true};
    const size_t sib = (rm == 4) ? 1 : 0;
    const bool base5 = sib && mp + 1 < N && (b[mp + 1] & 7) == 5;
    const size_t d = (mod == 0 && base5) ? 4 : (mod == 1 ? 1 : (mod == 2 ? 4 : 0));
    return {sib, d, mp + 1 + sib, false};
}

// decode up to maxInsns instructions from `va` within `maxBytes`. Unknown bytes
// resync by one; the byte budget (not control flow) stops the walk.
inline void disasm(const ModuleMap& M, uptr va, size_t maxBytes, size_t maxInsns,
                   std::vector<Insn>& out) {
    std::vector<u8> buf(std::min<size_t>(maxBytes, 8192));
    const size_t N = buf.size();
    if (!N || !safeRead(va, buf.data(), N)) return;
    const u8* b = buf.data();

    auto fits = [&](size_t off, size_t n) { return off + n <= N; };
    auto rdI32 = [&](size_t off, i32& v) { if (!fits(off, 4)) return false;
                                           std::memcpy(&v, b + off, 4); return true; };

    size_t i = 0;
    while (i < N && out.size() < maxInsns) {
        if (b[i] == 0xCC) { ++i; continue; }          // int3 padding between functions

        Insn in; in.addr = va + i;
        size_t p = 0;
        while (i + p < N) {                            // prefixes (incl. REX)
            const u8 c = b[i + p];
            if (c == 0x66 || c == 0x67 || c == 0xF2 || c == 0xF3 || c == 0x2E ||
                c == 0x3E || c == 0x26 || c == 0x36 || c == 0x64 || c == 0x65) { ++p; continue; }
            if ((c & 0xF0) == 0x40) { ++p; continue; }
            break;
        }
        if (i + p >= N) break;
        const u8 op = b[i + p];
        size_t L = 0;

        // modrm instruction helper: modrm byte at mp; returns total length from i
        // (including prefixes), or 0 on failure. `extraImm` = trailing immediate bytes.
        auto modrmInsn = [&](size_t mp, size_t extraImm, bool isLea) -> size_t {
            if (!fits(mp, 1)) return 0;
            const u8 modrm = b[mp];
            const MemOp mo = memop(b, N, modrm, mp);
            if (mo.rip) {
                i32 rel;
                if (!rdI32(mo.dispPos, rel)) return 0;
                in.ripTarget = va + mo.dispPos + 4 + (uptr)(i64)rel;
                in.op = isLea ? OP_LEA_RIP : OP_MOV_R_RIP;
                return (mo.dispPos + 4 + extraImm) - i;
            }
            if (mo.d && !fits(mo.dispPos, mo.d)) return 0;
            if (mo.d == 1)      { in.disp = (i8)b[mo.dispPos]; }
            else if (mo.d == 4) { std::memcpy(&in.disp, b + mo.dispPos, 4); }
            in.op = (mo.d || mo.sib) ? OP_MOV_R_DISP : OP_MOV_R_RM;
            if (mo.d == 0) in.disp = 0;
            return (mo.dispPos + mo.d + extraImm) - i;
        };

        switch (op) {
            case 0x8B: { L = modrmInsn(i + p + 1, 0, false); break; }              // mov r64, r/m64
            case 0x8D: { L = modrmInsn(i + p + 1, 0, true);  break; }              // lea
            case 0x89: case 0xC7: {                                                // mov [r/m], r64/imm32
                const size_t mp = i + p + 1;
                const size_t imm = (op == 0xC7) ? 4 : 0;
                const size_t Lx = modrmInsn(mp, imm, false);
                if (!Lx) break;
                L = Lx;
                if (in.op == OP_MOV_R_DISP) in.op = OP_STORE_DISP;
                else if (in.op == OP_MOV_R_RIP) in.op = OP_MOV_R_RM;  // not expected; keep ripTarget
                break;
            }
            case 0x83: {                                                           // cmp/arith r/m64, imm8
                const size_t mp = i + p + 1;
                const size_t Lx = modrmInsn(mp, 1, false);
                if (!Lx) break;
                L = Lx;
                const u8 reg = (b[mp] >> 3) & 7;
                if (reg == 7 && in.op == OP_MOV_R_DISP) {
                    in.op = OP_CMP_EA_IMM;
                    if (fits(i + L - 1, 1)) in.imm = (i8)b[i + L - 1];
                }
                break;
            }
            case 0x63: case 0x39: case 0x3B: case 0x31: case 0x33: case 0x85:
            case 0x29: case 0x01: case 0x23: case 0x0B: case 0x88: case 0x8A:
            case 0x38: case 0x3A: {                                                // cmp r/m8 too: alignment
                L = modrmInsn(i + p + 1, 0, false);
                if (L) in.op = OP_ALU;
                break;
            }
            case 0x81: {                                                           // cmp/arith r/m64, imm32
                const size_t mp = i + p + 1;
                const size_t Lx = modrmInsn(mp, 4, false);
                if (!Lx) break;
                L = Lx;
                const u8 reg = (b[mp] >> 3) & 7;
                if (reg == 7 && in.op == OP_MOV_R_DISP) {
                    in.op = OP_CMP_EA_IMM;
                    i32 v = 0;
                    if (fits(i + L - 4, 4)) std::memcpy(&v, b + i + L - 4, 4);
                    in.imm = v;
                }
                break;
            }
            case 0xFF: case 0xF7: {                                                // call [r/m] / idiv etc.
                const size_t mp = i + p + 1;
                const size_t extra = (op == 0xF7 && fits(mp, 1) && (b[mp] >> 6) != 3) ? 4 : 0;
                L = modrmInsn(mp, extra, false);
                if (L) in.op = OP_ALU;
                break;
            }
            case 0xE8: {                                                           // call rel32
                i32 rel;
                if (!rdI32(i + p + 1, rel)) break;
                in.op = OP_CALL_REL;
                in.funcTarget = va + i + p + 5 + (uptr)(i64)rel;
                L = p + 5;
                break;
            }
            case 0xE9: {                                                           // jmp rel32 (tail call)
                i32 rel;
                if (!rdI32(i + p + 1, rel)) break;
                in.op = OP_JMP_REL;
                in.funcTarget = va + i + p + 5 + (uptr)(i64)rel;
                L = p + 5;
                break;
            }
            default:
                if (op >= 0xB8 && op <= 0xBF) L = p + 5;                           // mov r32, imm32
                else if ((op >= 0x70 && op <= 0x7F) || op == 0xEB) L = p + 2;      // jcc rel8 / jmp rel8
                else if (op == 0x0F) {
                    const u8 op2 = fits(i + p + 1, 1) ? b[i + p + 1] : 0;
                    if (op2 >= 0x80 && op2 <= 0x8F) L = p + 6;                     // jcc rel32
                    else if (op2 == 0x1F || op2 == 0x10 || op2 == 0x11 || op2 == 0x28 ||
                             op2 == 0x29 || op2 == 0x2E || op2 == 0x2F || op2 == 0x54 ||
                             op2 == 0x57 || op2 == 0xB6 || op2 == 0xB7 || op2 == 0xBE ||
                             op2 == 0xBF || op2 == 0xAF || (op2 >= 0x90 && op2 <= 0x95) ||
                             op2 == 0x9C || op2 == 0x9E || op2 == 0xB0 || op2 == 0xB1) {
                        L = modrmInsn(i + p + 2, 0, false);                        // alignment only
                        if (L) in.op = OP_ALU;
                    }
                }
                else if (op == 0x98 || op == 0x99 || op == 0x9C || op == 0x9D ||
                         op == 0xF8 || op == 0xF9 || op == 0xFC || op == 0xFD ||
                         (op >= 0x50 && op <= 0x5F)) L = p + 1;                    // singles, push/pop
                else if ((op & 0xF8) == 0xD8) {                                    // FPU: modrm byte
                    if (fits(i + p + 1, 1)) L = p + 2;
                }
                break;
        }
        if (!L) { ++i; continue; }                     // bounded resync
        i += L;
        out.push_back(in);
    }
}

}  // namespace kk::rsl::pe
