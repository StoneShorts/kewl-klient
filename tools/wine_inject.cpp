// tools/wine_inject.cpp -- the launcher's injection, without the window.
//
// Exists for one reason: testing KewlKlient on Linux, where the game runs under Wine and there is
// nobody to press the launcher's button. It does exactly what launcher/main.cpp does -- find
// osclient.exe, refuse if the DLL is already in it, write the DLL path into the process, call
// LoadLibraryA on a remote thread -- and prints the result to stdout so a script can read it.
//
// Build (see tools/wine.md):  x86_64-w64-mingw32-g++ -O2 -o wine_inject.exe wine_inject.cpp
// Run:                        wine wine_inject.exe Z:\path\to\kewlklient.dll

#include <windows.h>
#include <tlhelp32.h>
#include <cstdio>
#include <string>

static bool alreadyLoaded(DWORD pid, const wchar_t* moduleName) {
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid);
    if (snap == INVALID_HANDLE_VALUE) return false;
    MODULEENTRY32W me{ sizeof me };
    bool found = false;
    if (Module32FirstW(snap, &me)) {
        do {
            if (_wcsicmp(me.szModule, moduleName) == 0) { found = true; break; }
        } while (Module32NextW(snap, &me));
    }
    CloseHandle(snap);
    return found;
}

int main(int argc, char** argv) {
    if (argc != 2) {
        printf("usage: wine_inject.exe <path to kewlklient.dll in Windows form, e.g. Z:\\tmp\\dist\\kewlklient.dll>\n");
        return 2;
    }
    std::string dll = argv[1];

    // The path written into the target must be an ANSI LoadLibraryA path; Wine accepts Z:\-style
    // DOS paths, which is exactly what we asked the caller for.
    DWORD pid = 0;
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) { printf("FAIL: process snapshot\n"); return 1; }
    PROCESSENTRY32W pe{ sizeof pe };
    if (Process32FirstW(snap, &pe)) {
        do {
            if (_wcsicmp(pe.szExeFile, L"osclient.exe") == 0) { pid = pe.th32ProcessID; break; }
        } while (Process32NextW(snap, &pe));
    }
    CloseHandle(snap);
    if (!pid) { printf("FAIL: osclient.exe is not running\n"); return 1; }

    if (alreadyLoaded(pid, L"kewlklient.dll")) {
        printf("FAIL: kewlklient.dll is already loaded -- restart the game to load a new build\n");
        return 1;
    }

    HANDLE proc = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION |
                              PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ, FALSE, pid);
    if (!proc) { printf("FAIL: OpenProcess\n"); return 1; }

    SIZE_T len = dll.size() + 1;
    void* remote = VirtualAllocEx(proc, nullptr, len, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!remote) { printf("FAIL: VirtualAllocEx\n"); return 1; }
    if (!WriteProcessMemory(proc, remote, dll.c_str(), len, nullptr)) { printf("FAIL: WriteProcessMemory\n"); return 1; }

    LPTHREAD_START_ROUTINE load = reinterpret_cast<LPTHREAD_START_ROUTINE>(
        GetProcAddress(GetModuleHandleA("kernel32.dll"), "LoadLibraryA"));
    if (!load) { printf("FAIL: no LoadLibraryA\n"); return 1; }

    HANDLE thread = CreateRemoteThread(proc, nullptr, 0, load, remote, 0, nullptr);
    if (!thread) { printf("FAIL: CreateRemoteThread\n"); return 1; }
    WaitForSingleObject(thread, 10000);

    DWORD exitCode = 0;
    GetExitCodeThread(thread, &exitCode);
    printf("%s: injected into pid %lu, LoadLibraryA returned %p (0 = the DLL itself failed to load)\n",
           exitCode ? "OK" : "FAIL", static_cast<unsigned long>(pid), reinterpret_cast<void*>(exitCode));
    CloseHandle(thread);
    CloseHandle(proc);
    return exitCode ? 0 : 1;
}
