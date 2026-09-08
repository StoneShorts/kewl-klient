// log.hpp -- the DLL's diagnostics channel, and the only one that works everywhere.
//
// The DLL lives in a process it did not start. osclient.exe is a GUI-subsystem process with no console
// and no standard handles, so "just printf" goes nowhere -- and redirecting the C runtime's stdout with
// freopen only fixes the C runtime the DLL happens to link (msvcrt), not the JVM's (ucrt), and on
// Windows (unlike Wine) not even that reliably: two live runs on 2026-09-05 produced a JVM, a written
// profile store and not one line of DLL output. So this file writes with the kernel directly:
//
//   logf(...)      formats like printf and WriteFile()s to the KEWL_LOG file, appending, sharing the
//                  file with the launcher (which opened it first and keeps writing its [input] trace).
//                  With no KEWL_LOG it falls back to stdout, which is the direct-inject-from-a-shell
//                  case where stdout is real.
//   logOpen(path)  opens the file and also installs it as the process's STD_OUTPUT/STD_ERROR handle,
//                  which is how the JVM's System.out and System.err land in the same file: Java's
//                  FileDescriptor.out is GetStdHandle(STD_OUTPUT_HANDLE), read once when the VM
//                  starts, so this must run before JNI_CreateJavaVM (it runs in DllMain).
#pragma once
#include <windows.h>
#include <cstdarg>
#include <cstdio>

namespace kk {

inline HANDLE& logHandle() {
    static HANDLE h = INVALID_HANDLE_VALUE;
    return h;
}

inline void logOpen(const char* path) {
    if (!path || !*path) return;
    HANDLE h = CreateFileA(path, FILE_APPEND_DATA | GENERIC_READ,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                           OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return;
    logHandle() = h;
    // Java's standard streams pick these up when the VM starts (see the header comment).
    SetStdHandle(STD_OUTPUT_HANDLE, h);
    SetStdHandle(STD_ERROR_HANDLE, h);
}

inline void logf(const char* fmt, ...) {
    char buf[4096];
    va_list ap;
    va_start(ap, fmt);
    int n = std::vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (static_cast<size_t>(n) >= sizeof buf) n = static_cast<int>(sizeof buf) - 1;
    HANDLE h = logHandle();
    if (h != INVALID_HANDLE_VALUE) {
        DWORD written = 0;
        WriteFile(h, buf, static_cast<DWORD>(n), &written, nullptr);   // FILE_APPEND_DATA: atomic append
    } else {
        std::fwrite(buf, 1, static_cast<size_t>(n), stdout);
        std::fflush(stdout);
    }
}

}  // namespace kk
