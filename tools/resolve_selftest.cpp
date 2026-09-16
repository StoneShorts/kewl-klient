// resolve_selftest.cpp -- runs the REAL resolver (client/resolve_pe.hpp +
// client/resolve.hpp) against a mapped copy of osclient.exe without executing
// it, and prints the layout it derives. Verification tool, not shipped.
//
// Build (from kewl-klient/, any VS dev prompt):
//   cl /nologo /std:c++20 /EHsc /I client tools/resolve_selftest.cpp ^
//      /Fe:build/scratch/resolve_selftest.exe
// Run:
//   build\scratch\resolve_selftest.exe build\dist\osclient.exe
//
// It uses the same self-ReadProcessMemory substrate the DLL uses, against a
// view of the file mapped at its preferred base (0x140000000), so every address
// and every recipe behaves exactly as in the injected process.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <cstdio>
#include <string>
#include <vector>
#include <cstring>
#include <cstdint>

#include "../client/resolve_pe.hpp"
#include "../client/resolve.hpp"

// raw .pdata entry for the probe below
namespace kk::rsl::pe { struct RUNTIME_FUNCTION_RAW { std::uint32_t begin, end, unwind; }; }

namespace {

// map the file as IMAGE (sections at their RVAs, preferred base) so the
// resolver's ReadProcessMemory substrate sees the same bytes the injected
// process would.
HANDLE mapImage(const char* path, void*& view, SIZE_T& viewSize) {
    HANDLE h = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, nullptr,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return nullptr;
    HANDLE m = CreateFileMappingA(h, nullptr, PAGE_READONLY | SEC_IMAGE, 0, 0, nullptr);
    CloseHandle(h);
    if (!m) return nullptr;
    view = MapViewOfFileEx(m, FILE_MAP_READ, 0, 0, 0, (LPVOID)0x140000000);
    if (!view) view = MapViewOfFile(m, FILE_MAP_READ, 0, 0, 0);   // fallback: ASLR-chosen base
    if (!view) { CloseHandle(m); return nullptr; }
    MEMORY_BASIC_INFORMATION mbi;
    viewSize = VirtualQuery(view, &mbi, sizeof mbi) ? mbi.RegionSize : 0;
    return m;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) { std::printf("usage: resolve_selftest <osclient.exe>\n"); return 2; }
    void* view = nullptr; SIZE_T sz = 0;
    HANDLE m = mapImage(argv[1], view, sz);
    if (!view) { std::printf("FAILED: could not map %s (err %lu)\n", argv[1], GetLastError()); return 1; }
    const std::uintptr_t base = reinterpret_cast<std::uintptr_t>(view);
    std::printf("mapped at 0x%llX (%llu KB)\n\n", (unsigned long long)base, (unsigned long long)(sz >> 10));

    kk::rsl::logSink = [](const char* line) { std::printf("%s", line); };
    const bool ok = kk::rsl::init(base);
    std::printf("\ninit -> %s\n", ok ? "true" : "false");

    namespace off = kk::off;
    // the IDA-verified ground truth for client-240-7 (all confirmed on real
    // bytes in tools/ida_scripts/test_recipes.py):
    struct Check { const char* name; std::uintptr_t* got; std::uintptr_t want; bool critical; };
    static std::uintptr_t w2s = off::WORLD_TO_SCREEN;            // function slots are RVAs
    const Check checks[] = {
        { "CLIENT_OBJ_PTR",      &off::CLIENT_OBJ_PTR,      0xE95668, true  },
        { "VARP_ARRAY_PTR",      &off::VARP_ARRAY_PTR,      0x155C508, false },
        { "SKILL_EFFECTIVE",     &off::SKILL_EFFECTIVE,     0x3360,   true  },
        { "SKILL_BASE",          &off::SKILL_BASE,          0x33C4,   true  },
        { "SKILL_XP",            &off::SKILL_XP,            0x3428,   true  },
        { "GAME_STATE",          &off::GAME_STATE,          0x2160,   true  },
        { "CYCLE",               &off::CYCLE,               0x2164,   false },
        { "REGISTRY_GROUPS",     &off::REGISTRY_GROUPS,     0xC9E8,   true  },
        { "REGISTRY_GROUP_COUNT",&off::REGISTRY_GROUP_COUNT,0xC9F0,   false },
        { "ENTITY_SCENE_X",      &off::ENTITY_SCENE_X,      0x3F0,    true  },
        { "ENTITY_SCENE_Y",      &off::ENTITY_SCENE_Y,      0x418,    true  },
        { "ENTITY_DEF_PTR",      &off::ENTITY_DEF_PTR,      0x730,    false },
        { "CONTAINER_BUCKETS",   &off::CONTAINER_BUCKETS,   0x154C500,true  },
        { "CONTAINER_MASK",      &off::CONTAINER_MASK,      0x154C508,true  },
        { "CAMERA_FINE_X",       &off::CAMERA_FINE_X,       0x895D8,  true  },
        // 0x2202A0 is the 240-6 constant; 0x220320 is the 240-7 leaf (IDA:
        // camera triple 0x895D8/DC/E0 read at this exact address)
        { "WORLD_TO_SCREEN(rva)",&w2s,                      0x220320, true  },
    };
    int pass = 0, fail = 0;
    for (const Check& c : checks) {
        const bool match = *c.got == c.want;
        const bool nonzero = *c.got != 0;
        if (match) ++pass;
        else if (c.critical || !nonzero) ++fail;
        std::printf("%-9s %-24s = 0x%-8llX (want 0x%llX)\n",
                    match ? "OK" : (nonzero ? "DIFF" : "ZERO"),
                    c.name, (unsigned long long)*c.got, (unsigned long long)c.want);
    }
    std::printf("\n%d match, %d mismatch\n", pass, fail);
    return fail ? 1 : 0;
}
