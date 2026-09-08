// rt_probe.cpp -- the integrator's byte-level cross-check of the bridge v2 contract.
//
// One exe, three independent decoders of the SAME data, run under Wine against the real jar:
//
//   1. kewl.panel.PanelBridge.snapshot()   the real packed int[] Java publishes (pulled over JNI the
//                                          same way client/jvm.hpp's bridgeSnapshot does)
//   2. kk::bridge::buildModel              the DLL's writer, included from client/bridge.hpp
//   3. RegionDecoder / ArrayDecoder below  two parsers written from launcher/bridge_layout.hpp's
//                                          documented layout, not from either side's code
//
// Every plugin, setting, pinned flag, profile and hub entry must agree across all three, and the
// region decoder must consume the byte region to the last byte. Then the edit ring: a record of EVERY
// kind 0..14 is written the way launcher/main.cpp's writeEdit writes it, read back the way
// client/bridge.hpp's drainEdits reads it, and every field must survive. Finally the v2 commands are
// dispatched the way bridgeApply dispatches them, against a real ProfileManager, to prove the JNI
// signatures resolve and the edits actually land in Java.
//
// Build (-static is LOAD-BEARING: without it the exe imports libc++.dll/libunwind.dll, which the
// JDK's bin dir does not ship, and the probe dies under Wine with exit 53 and no output at all --
// the same reason CMakeLists.txt links kewl_dll/kewl_launcher statically):
//   x86_64-w64-mingw32-g++ -O2 -std=c++20 -static \
//       -I/usr/lib/jvm/<host-jdk>/include -I/usr/lib/jvm/<host-jdk>/include/win32 \
//       -I<probe-dir>/jni-include -I<repo>/client -I<repo>/launcher \
//       bridge-roundtrip-probe.cpp -o rt_probe.exe
//
// The probe dir (arg 3) is only a workspace: it goes on the classpath (harmless) and becomes
// -Dkewl.data.dir=<dir>\data, so ProfileManager's create/rename/delete below write into a sandbox
// instead of a real data directory. The snapshot itself is pulled live over JNI -- no dump file.
//
// Run:
//   WINEDEBUG=-all wine ./rt_probe.exe 'C:\jdk-17...-jre' 'Z:\...\build\dist\kewlklient.jar' 'Z:\probe-dir'
// Prints one "ok: ..." line per check and finishes with "rt_probe: ALL PASS".

#include <windows.h>
#include <jni.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "bridge.hpp"          // client/ -- kk::bridge::buildModel + the whole layout comment
#include "bridge_layout.hpp"   // launcher/ -- kewl_bridge:: constants and their static_asserts

// ---------------------------------------------------------------------------
// Decoder 1: the Java snapshot array, read straight from the format documented
// in kewl.panel.PanelBridge's class comment. Independent of buildModel.
// ---------------------------------------------------------------------------
struct ExpSetting {
    int kind, valueInt, min, max, enumIndex, optionCount, flags;
    std::string key, label, desc, section, valueText;
    std::vector<std::string> options;
};
struct ExpPlugin {
    int enabled, hasConfig, hotkey;
    std::string name, desc, status;
    std::vector<ExpSetting> settings;
};
struct ExpProfile { std::string name, id; };
struct ExpHub {
    std::string id, name, version, author, desc;
    int flags, installedIdx;
};
struct ExpModel {
    std::vector<ExpPlugin> plugins;
    std::vector<int> pinned;
    int activeProfile = -1;
    std::vector<ExpProfile> profiles;
    int hubState = 0;
    std::string hubError;
    std::vector<ExpHub> hub;
};

struct ArrReader {
    const std::vector<jint>& a;
    size_t p = 0;
    bool ok = true;
    explicit ArrReader(const std::vector<jint>& v) : a(v) {}
    jint u32() {
        if (!ok || p >= a.size()) { ok = false; return 0; }
        return a[p++];
    }
    std::string str() {                       // len bytes, packed 4/int, lowest byte first
        int len = u32();
        if (!ok || len < 0 || p + (len + 3) / 4 > a.size()) { ok = false; return {}; }
        std::string s((size_t)len, '\0');
        for (int i = 0; i < len; ++i)
            s[(size_t)i] = (char)(a[p + (size_t)i / 4] >> (8 * (i % 4)));
        p += (size_t)(len + 3) / 4;
        return s;
    }
};

ExpModel decodeArray(const std::vector<jint>& snap, bool& ok) {
    ArrReader r(snap);
    ExpModel m;
    if (r.u32() != (jint)kewl_bridge::MAGIC)   { ok = false; return m; }
    if (r.u32() != (jint)kewl_bridge::VERSION) { ok = false; return m; }
    int n = r.u32();
    for (int i = 0; i < n && r.ok; ++i) {
        ExpPlugin pl;
        pl.enabled = r.u32(); pl.hasConfig = r.u32(); pl.hotkey = r.u32();
        pl.name = r.str(); pl.desc = r.str(); pl.status = r.str();
        int sc = r.u32();
        for (int s = 0; s < sc && r.ok; ++s) {
            ExpSetting st;
            st.kind = r.u32(); st.valueInt = r.u32(); st.min = r.u32(); st.max = r.u32();
            st.enumIndex = r.u32(); st.optionCount = r.u32(); st.flags = r.u32();
            st.key = r.str(); st.label = r.str(); st.desc = r.str();
            st.section = r.str(); st.valueText = r.str();
            for (int o = 0; o < st.optionCount && r.ok; ++o) st.options.push_back(r.str());
            pl.settings.push_back(std::move(st));
        }
        m.plugins.push_back(std::move(pl));
    }
    for (int i = 0; i < n && r.ok; ++i) m.pinned.push_back(r.u32());
    m.activeProfile = r.u32();
    int pc = r.u32();
    for (int i = 0; i < pc && r.ok; ++i) {
        ExpProfile pr; pr.name = r.str(); pr.id = r.str();
        m.profiles.push_back(std::move(pr));
    }
    m.hubState = r.u32();
    m.hubError = r.str();
    int hc = r.u32();
    for (int i = 0; i < hc && r.ok; ++i) {
        ExpHub h;
        h.id = r.str(); h.name = r.str(); h.version = r.str(); h.author = r.str(); h.desc = r.str();
        h.flags = r.u32(); h.installedIdx = r.u32();
        m.hub.push_back(std::move(h));
    }
    ok = r.ok && r.p == snap.size();          // no trailing ints either
    return m;
}

// ---------------------------------------------------------------------------
// Decoder 2: the model REGION bytes, read straight from launcher/bridge_layout.hpp's
// documented field order. This is the shape main.cpp's Reader walks.
// ---------------------------------------------------------------------------
struct RegPlugin {
    int enabled, hasConfig, hotkey, pinned;
    std::string name, desc, status;
    std::vector<ExpSetting> settings;
};
struct RegModel {
    std::vector<RegPlugin> plugins;
    int activeProfile = -1;
    std::vector<ExpProfile> profiles;
    int hubState = 0;
    std::string hubError;
    std::vector<ExpHub> hub;
    size_t consumed = 0;
};

struct RegReader {
    const unsigned char* p;
    const unsigned char* end;
    bool ok = true;
    std::int32_t i32() {
        if (!ok || p + 4 > end) { ok = false; return 0; }
        std::int32_t v; std::memcpy(&v, p, 4); p += 4; return v;
    }
    std::string fld(size_t w) {
        if (!ok || p + w > end) { ok = false; return {}; }
        size_t n = strnlen((const char*)p, w);
        std::string s((const char*)p, n);
        p += w;
        return s;
    }
};

RegModel decodeRegion(const unsigned char* p, const unsigned char* end, bool& ok) {
    RegReader r{p, end};
    RegModel m;
    int n = r.i32();
    for (int i = 0; i < n && r.ok; ++i) {
        RegPlugin pl;
        pl.enabled = r.i32(); pl.hasConfig = r.i32(); pl.hotkey = r.i32();
        pl.name = r.fld(kewl_bridge::model::PLUGIN_NAME);
        pl.desc = r.fld(kewl_bridge::model::PLUGIN_DESC);
        pl.status = r.fld(kewl_bridge::model::PLUGIN_STATUS);
        int sc = r.i32();
        for (int s = 0; s < sc && r.ok; ++s) {
            ExpSetting st;
            st.kind = r.i32(); st.valueInt = r.i32(); st.min = r.i32(); st.max = r.i32();
            st.enumIndex = r.i32(); st.optionCount = r.i32(); st.flags = r.i32();
            st.key = r.fld(kewl_bridge::model::SET_KEY);
            st.label = r.fld(kewl_bridge::model::SET_LABEL);
            st.desc = r.fld(kewl_bridge::model::SET_DESC);
            st.section = r.fld(kewl_bridge::model::SET_SECTION);
            st.valueText = r.fld(kewl_bridge::model::SET_VALUETEXT);
            for (int o = 0; o < st.optionCount && r.ok; ++o)
                st.options.push_back(r.fld(kewl_bridge::model::SET_OPTION));
            pl.settings.push_back(std::move(st));
        }
        m.plugins.push_back(std::move(pl));
    }
    for (int i = 0; i < n && r.ok; ++i) m.plugins[(size_t)i].pinned = r.i32();
    m.activeProfile = r.i32();
    int pc = r.i32();
    for (int i = 0; i < pc && r.ok; ++i) {
        ExpProfile pr;
        pr.name = r.fld(kewl_bridge::model::PROFILE_NAME);
        pr.id   = r.fld(kewl_bridge::model::PROFILE_ID);
        m.profiles.push_back(std::move(pr));
    }
    m.hubState = r.i32();
    m.hubError = r.fld(kewl_bridge::model::HUB_ERROR);
    int hc = r.i32();
    for (int i = 0; i < hc && r.ok; ++i) {
        ExpHub h;
        h.id = r.fld(kewl_bridge::model::HUB_ID);
        h.name = r.fld(kewl_bridge::model::HUB_NAME);
        h.version = r.fld(kewl_bridge::model::HUB_VERSION);
        h.author = r.fld(kewl_bridge::model::HUB_AUTHOR);
        h.desc = r.fld(kewl_bridge::model::HUB_DESC);
        h.flags = r.i32();
        h.installedIdx = r.i32();
        m.hub.push_back(std::move(h));
    }
    ok = r.ok;
    m.consumed = (size_t)(r.p - p);
    return m;
}

// ---------------------------------------------------------------------------
static int g_fail = 0;
static void check(bool good, const std::string& what) {
    if (good) { std::printf("  ok   %s\n", what.c_str()); return; }
    ++g_fail;
    std::printf("  FAIL %s\n", what.c_str());
}

template <typename A, typename B>
static void same(const A& a, const B& b, const std::string& what) {
    if (!(a == b)) {
        ++g_fail;
        std::printf("  FAIL %s ('%s' vs '%s')\n", what.c_str(), std::to_string(a).c_str(),
                    std::to_string(b).c_str());
    }
}
static void sameStr(const std::string& a, const std::string& b, const std::string& what) {
    if (a != b) {
        ++g_fail;
        std::printf("  FAIL %s: \"%s\" vs \"%s\"\n", what.c_str(), a.c_str(), b.c_str());
    }
}

// ---------------------------------------------------------------------------
// ---- the three-way round trip, run against any snapshot -------------------
static void checkRoundTrip(const std::vector<jint>& snap, const std::string& label) {
    std::printf("[%s] snapshot: %zu ints, magic=0x%X format=%d\n", label.c_str(), snap.size(),
                (unsigned)(snap.empty() ? 0 : snap[0]), snap.empty() ? -1 : snap[1]);
    check(!snap.empty() && snap[0] == (jint)kewl_bridge::MAGIC, label + ": magic");
    check(!snap.empty() && snap[1] == (jint)kewl_bridge::VERSION, label + ": format is 2");
    bool arrOk = false;
    ExpModel exp = decodeArray(snap, arrOk);
    check(arrOk, label + ": independent array decode consumed the snapshot exactly");
    std::vector<std::uint8_t> bytes;
    check(kk::bridge::buildModel(bytes, snap), label + ": buildModel accepted the snapshot");
    bool regOk = false;
    RegModel reg = decodeRegion(bytes.data(), bytes.data() + bytes.size(), regOk);
    check(regOk, label + ": independent region decode ran clean");
    check(reg.consumed == bytes.size(),
          label + ": region fully consumed (" + std::to_string(reg.consumed) + " of " +
          std::to_string(bytes.size()) + " bytes)");
        // ---- field-by-field, array decoder vs region decoder -----------------------
        same((int)reg.plugins.size(), (int)exp.plugins.size(), "plugin count");
        for (size_t i = 0; i < exp.plugins.size() && i < reg.plugins.size(); ++i) {
            const ExpPlugin& e = exp.plugins[i];
            const RegPlugin& r = reg.plugins[i];
            std::string w = "plugin[" + std::to_string(i) + "]";
            same(r.enabled, e.enabled, w + " enabled");
            same(r.hasConfig, e.hasConfig, w + " hasConfig");
            same(r.hotkey, e.hotkey, w + " hotkey");
            sameStr(r.name, e.name, w + " name");
            sameStr(r.desc, e.desc, w + " desc");
            sameStr(r.status, e.status, w + " status");
            same(r.pinned, exp.pinned[i], w + " pinned");
            same((int)r.settings.size(), (int)e.settings.size(), w + " setting count");
            for (size_t s = 0; s < e.settings.size() && s < r.settings.size(); ++s) {
                const ExpSetting& es = e.settings[(size_t)s];
                const ExpSetting& rs = r.settings[(size_t)s];
                std::string sw = w + ".set[" + std::to_string(s) + "]";
                same(rs.kind, es.kind, sw + " kind");
                same(rs.valueInt, es.valueInt, sw + " valueInt");
                same(rs.min, es.min, sw + " min");
                same(rs.max, es.max, sw + " max");
                same(rs.enumIndex, es.enumIndex, sw + " enumIndex");
                same(rs.flags, es.flags, sw + " flags");
                same((int)rs.options.size(), (int)es.options.size(), sw + " optionCount");
                sameStr(rs.key, es.key, sw + " key");
                sameStr(rs.label, es.label, sw + " label");
                sameStr(rs.desc, es.desc, sw + " desc");
                sameStr(rs.section, es.section, sw + " section");
                sameStr(rs.valueText, es.valueText, sw + " valueText");
                for (size_t o = 0; o < es.options.size() && o < rs.options.size(); ++o)
                    sameStr(rs.options[o], es.options[o], sw + ".opt[" + std::to_string(o) + "]");
            }
        }
        same(reg.activeProfile, exp.activeProfile, "activeProfileIndex");
        same((int)reg.profiles.size(), (int)exp.profiles.size(), "profile count");
        for (size_t i = 0; i < exp.profiles.size() && i < reg.profiles.size(); ++i) {
            sameStr(reg.profiles[i].name, exp.profiles[i].name, "profile[" + std::to_string(i) + "] name");
            sameStr(reg.profiles[i].id,   exp.profiles[i].id,   "profile[" + std::to_string(i) + "] id");
        }
        same(reg.hubState, exp.hubState, "hubState");
        sameStr(reg.hubError, exp.hubError, "hubError");
        same((int)reg.hub.size(), (int)exp.hub.size(), "hub count");
        for (size_t i = 0; i < exp.hub.size() && i < reg.hub.size(); ++i) {
            sameStr(reg.hub[i].id, exp.hub[i].id, "hub[" + std::to_string(i) + "] id");
            sameStr(reg.hub[i].name, exp.hub[i].name, "hub[" + std::to_string(i) + "] name");
            sameStr(reg.hub[i].version, exp.hub[i].version, "hub[" + std::to_string(i) + "] version");
            sameStr(reg.hub[i].author, exp.hub[i].author, "hub[" + std::to_string(i) + "] author");
            sameStr(reg.hub[i].desc, exp.hub[i].desc, "hub[" + std::to_string(i) + "] desc");
            same(reg.hub[i].flags, exp.hub[i].flags, "hub[" + std::to_string(i) + "] flags");
            same(reg.hub[i].installedIdx, exp.hub[i].installedIdx, "hub[" + std::to_string(i) + "] installedIdx");
        }
        std::printf("[%s] cross-check: %d plugins, %zu profiles, %zu hub entries\n",
                    label.c_str(), (int)reg.plugins.size(), exp.profiles.size(), exp.hub.size());
}

int main(int argc, char** argv) {
    setvbuf(stdout, nullptr, _IONBF, 0);    // a crash must not take its own log with it
    if (argc < 4) {
        std::printf("usage: rt_probe.exe <javaHome win> <jar win> <workdir win>\n");
        return 2;
    }
    const std::wstring javaHome = std::wstring(argv[1], argv[1] + strlen(argv[1]));
    std::string cp = "-Djava.class.path=" + std::string(argv[2]) + ";" + argv[3];
    std::string dataDir = "-Dkewl.data.dir=" + std::string(argv[3]) + "\\data";

    std::wstring bin = javaHome + L"\\bin";
    AddDllDirectory(bin.c_str());
    HMODULE jvmDll = LoadLibraryExW((javaHome + L"\\bin\\server\\jvm.dll").c_str(), nullptr,
                                    LOAD_LIBRARY_SEARCH_DEFAULT_DIRS |
                                    LOAD_LIBRARY_SEARCH_USER_DIRS |
                                    LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR);
    if (!jvmDll) jvmDll = LoadLibraryW((javaHome + L"\\bin\\server\\jvm.dll").c_str());
    if (!jvmDll) { std::printf("no jvm.dll (GetLastError=%lu)\n", (unsigned long)GetLastError()); return 2; }
    auto create = (jint (JNICALL*)(JavaVM**, void**, void*))GetProcAddress(jvmDll, "JNI_CreateJavaVM");
    if (!create) { std::printf("no JNI_CreateJavaVM\n"); return 2; }

    JavaVMOption opt[2]{};
    opt[0].optionString = cp.data();
    opt[1].optionString = dataDir.data();
    JavaVMInitArgs args{};
    args.version = JNI_VERSION_1_8;
    args.nOptions = 2;
    args.options = opt;
    args.ignoreUnrecognized = JNI_FALSE;
    JNIEnv* env = nullptr;
    JavaVM* vm = nullptr;
    if (create(&vm, (void**)&env, &args) != JNI_OK || !env) {
        std::printf("JNI_CreateJavaVM failed\n");
        return 2;
    }
    kk::g_vm = vm;      // jvm.hpp's env() hands this thread's env to the bridge code
    std::printf("vm up\n");

    if (!kk::bridgeAvailable()) { std::printf("bridge not available\n"); return 2; }
    // First pass: the snapshot a bare session publishes (plugins, no profiles yet).
    checkRoundTrip(kk::bridgeSnapshot(), "cold (no profiles yet)");

    // ---- 2b. the rejection path: what buildModel does with a snapshot it cannot parse ----------
    // Java returns null when its own walk throws (jvm.hpp then hands us an empty vector), a snapshot
    // can arrive truncated, and the counts can be over the caps. Every one of these must be
    // REJECTED -- buildModel false, the DLL keeps the last good model -- never half-parsed into a
    // region the launcher renders as the truth. The 3-int header is the shape snapshot()'s old
    // catch-all used to return: it parses as far as the v2 tail and then runs dry, which is exactly
    // the failure this check pins.
    std::printf("rejection path, on the real snapshot's shape:\n");
    {
        std::vector<jint> snap = kk::bridgeSnapshot();
        check(snap.size() > 8, "a real snapshot to mangle");
        std::vector<std::uint8_t> bytes;

        check(!kk::bridge::buildModel(bytes, {}), "an empty snapshot (Java returned null) is rejected");

        std::vector<jint> headerOnly = { snap[0], snap[1], 0 };
        check(!kk::bridge::buildModel(bytes, headerOnly),
              "a v1-shaped 3-int header is rejected: the v2 tail is missing, not optional");

        for (size_t cut : { (size_t)1, (size_t)3, (size_t)10, snap.size() / 2, snap.size() - 1 }) {
            std::vector<jint> truncated(snap.begin(), snap.begin() + (long)cut);
            check(!kk::bridge::buildModel(bytes, truncated),
                  "a snapshot cut to " + std::to_string(cut) + " of " + std::to_string(snap.size()) +
                  " ints is rejected");
        }

        std::vector<jint> badMagic = snap;
        badMagic[0] ^= 0xFF;
        check(!kk::bridge::buildModel(bytes, badMagic), "a wrong magic is rejected");

        std::vector<jint> badFormat = snap;
        badFormat[1] = 99;
        check(!kk::bridge::buildModel(bytes, badFormat), "a wrong format is rejected");

        std::vector<jint> overCap = snap;                  // field 2 is pluginCount
        overCap[2] = kewl_bridge::MAX_PLUGINS + 1;
        check(!kk::bridge::buildModel(bytes, overCap), "a plugin count over the cap is rejected");
    }



    // ---- 4. the edit ring, every kind, launcher write -> DLL read ---------------
    // Write exactly the way main.cpp's writeEdit does (record first, then head), read exactly the
    // way bridge.hpp's drainEdits does (memcpy out of the slot, tail % RING_SLOTS). The payloads are
    // distinct per kind so a mis-copied field cannot pass by luck.
    std::printf("edit ring, kinds 0..14, launcher write -> dll read:\n");
    kk::bridge::Header hdr{};
    hdr.magic = kewl_bridge::MAGIC;
    hdr.version = kewl_bridge::VERSION;
    for (int kind = 0; kind <= 14; ++kind) {
        kk::bridge::EditRecord rec{};
        rec.kind = kind;
        rec.pluginIdx = (kind == 7 || kind == 8) ? -1 : 100 + kind;   // hub edits carry -1
        std::string key = (kind <= 4) ? ("key-" + std::to_string(kind)) : "";
        std::snprintf(rec.key, sizeof rec.key, "%s", key.c_str());
        rec.intVal = (std::int64_t)kind * 1000 + 7;
        std::string text = (kind == 3 || kind == 7 || kind == 8 || kind == 11 || kind == 13)
                               ? ("text-" + std::to_string(kind)) : "";
        std::snprintf(rec.text, sizeof rec.text, "%s", text.c_str());

        std::int32_t head = hdr.head;      // writeEdit: record, then publish head
        hdr.edits[(size_t)head % kewl_bridge::RING_SLOTS] = rec;
        hdr.head = head + 1;

        kk::bridge::EditRecord out;        // drainEdits: copy out of the slot
        std::memcpy(&out, &hdr.edits[(size_t)hdr.tail % kewl_bridge::RING_SLOTS], sizeof out);
        hdr.tail = hdr.tail + 1;
        bool ok = out.kind == rec.kind && out.pluginIdx == rec.pluginIdx &&
                  out.intVal == rec.intVal && std::strcmp(out.key, rec.key) == 0 &&
                  std::strcmp(out.text, rec.text) == 0;
        std::printf("  kind %2d idx %4d key %-10s int %6lld text %-8s -> %s\n", out.kind, out.pluginIdx,
                    out.key, (long long)out.intVal, out.text, ok ? "intact" : "CORRUPT");
        check(ok, "ring round-trip kind " + std::to_string(kind));

        // The record's byte shape must be what both layouts pin, whatever the compiler does.
        check(sizeof(kk::bridge::EditRecord) == 208 && sizeof(kewl_bridge::EditRecord) == 208,
              "EditRecord is 208 bytes on both sides");
    }
    // 15 written, 15 consumed: the counters the DLL's drop-everything branch (head - tail >
    // RING_SLOTS) reads must be back level, and each must have moved 15 times.
    check(hdr.head == 15 && hdr.tail == 15, "head and tail both advanced 15, level again");

    // ---- 5. the v2 commands, dispatched the way bridgeApply dispatches them -----
    // Install a real ProfileManager (the owners are what the statics route to), then push each
    // command through kk::bridge::applyEdit -- the DLL's actual path -- and watch it land.
    std::printf("v2 commands through bridgeApply, against the real jar:\n");
    jclass pmCls = env->FindClass("kewl/profile/ProfileManager");
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID pmInstall = pmCls ? env->GetStaticMethodID(pmCls, "install",
                                  "(Ljava/nio/file/Path;)Lkewl/profile/ProfileManager;") : nullptr;
    jmethodID pmProfiles = pmCls ? env->GetMethodID(pmCls, "profiles", "()Ljava/util/List;") : nullptr;
    if (!pmCls || !pmInstall || !pmProfiles) {
        std::printf("  (ProfileManager not resolvable -- skipping the landing checks)\n");
        if (env->ExceptionCheck()) env->ExceptionClear();
    } else {
        jclass strCls = env->FindClass("java/nio/file/Paths");
        jmethodID pathsGet = strCls ? env->GetStaticMethodID(strCls, "get",
            "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;") : nullptr;
        jobjectArray strArrCls = env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
        jstring jdir = env->NewStringUTF(std::string(argv[3]).c_str());
        jobject jpath = env->CallStaticObjectMethod(strCls, pathsGet, jdir, strArrCls);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        env->CallStaticObjectMethod(pmCls, pmInstall, jpath);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        std::printf("  ProfileManager installed on %s\n", argv[3]);

        auto drain = [&]() {
            // Plugin.drainLater is package-private; the frame loop is what normally runs it.
            jclass plg = env->FindClass("kewl/Plugin");
            jmethodID d = plg ? env->GetStaticMethodID(plg, "drainLater", "()V") : nullptr;
            if (d) env->CallStaticVoidMethod(plg, d);
            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
        };
        auto profileCount = [&]() -> int {
            jobject pm = env->CallStaticObjectMethod(pmCls,
                env->GetStaticMethodID(pmCls, "instance", "()Lkewl/profile/ProfileManager;"));
            if (!pm || env->ExceptionCheck()) { if (env->ExceptionCheck()) env->ExceptionClear(); return -1; }
            jobject list = env->CallObjectMethod(pm, pmProfiles);
            if (env->ExceptionCheck()) { env->ExceptionClear(); return -1; }
            int n = (int)env->CallIntMethod(list, env->GetMethodID(
                env->FindClass("java/util/List"), "size", "()I"));
            env->DeleteLocalRef(list);
            return n;
        };

        int before = profileCount();
        kk::bridge::EditRecord r11{}; r11.kind = 11; std::snprintf(r11.text, sizeof r11.text, "probe");
        bool c11 = kk::bridge::applyEdit(r11); drain();
        int afterCreate = profileCount();
        std::printf("  EDIT_PROFILE_CREATE  -> consumed=%d profiles %d -> %d\n", c11 ? 1 : 0,
                    before, afterCreate);
        check(c11 && afterCreate == before + 1, "profile create landed in ProfileManager");

        // rename the new profile (last index), duplicate it, switch to it, then delete both copies
        int idx = afterCreate - 1;
        kk::bridge::EditRecord r13{}; r13.kind = 13; r13.intVal = idx;
        std::snprintf(r13.text, sizeof r13.text, "probe-renamed");
        bool c13 = kk::bridge::applyEdit(r13); drain();
        kk::bridge::EditRecord r14{}; r14.kind = 14; r14.intVal = idx;
        bool c14 = kk::bridge::applyEdit(r14); drain();
        int afterDup = profileCount();
        kk::bridge::EditRecord r10{}; r10.kind = 10; r10.intVal = afterDup - 1;
        bool c10 = kk::bridge::applyEdit(r10); drain();
        int active = -1;
        {
            jobject pm = env->CallStaticObjectMethod(pmCls,
                env->GetStaticMethodID(pmCls, "instance", "()Lkewl/profile/ProfileManager;"));
            if (pm) active = (int)env->CallIntMethod(pm, env->GetMethodID(pmCls, "activeIndex", "()I"));
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        std::printf("  EDIT_PROFILE_RENAME/DUPLICATE/SWITCH -> consumed=%d/%d/%d profiles=%d active=%d\n",
                    c13 ? 1 : 0, c14 ? 1 : 0, c10 ? 1 : 0, afterDup, active);
        check(c13 && c14 && afterDup == afterCreate + 1 && active == afterDup - 1,
              "rename + duplicate + switch landed");

        kk::bridge::EditRecord r12{}; r12.kind = 12; r12.intVal = afterDup - 1;
        bool c12 = kk::bridge::applyEdit(r12); drain();
        kk::bridge::EditRecord r12b{}; r12b.kind = 12; r12b.intVal = afterDup - 2;
        bool c12b = kk::bridge::applyEdit(r12b); drain();
        int afterDel = profileCount();
        std::printf("  EDIT_PROFILE_DELETE  -> consumed=%d/%d profiles=%d\n", c12 ? 1 : 0,
                    c12b ? 1 : 0, afterDel);
        check(c12 && c12b && afterDel == before, "deletes landed, back to the original count");

        // Pin, unpin, reset setting, reset plugin -- all must be consumed; the pin one must move
        // ProfileManager's pinned state, read back through isPinned on plugin 0.
        jclass kkc = env->FindClass("kewl/KewlKlient");
        jmethodID pluginsM = env->GetStaticMethodID(kkc, "plugins", "()Ljava/util/List;");
        jobject plugins = env->CallStaticObjectMethod(kkc, pluginsM);
        jobject p0 = env->CallObjectMethod(plugins, env->GetMethodID(
            env->FindClass("java/util/List"), "get", "(I)Ljava/lang/Object;"), (jint)0);
        jobject pm = env->CallStaticObjectMethod(pmCls,
            env->GetStaticMethodID(pmCls, "instance", "()Lkewl/profile/ProfileManager;"));
        jmethodID isPinned = env->GetMethodID(pmCls, "isPinned", "(Lkewl/Plugin;)Z");
        bool was = pm ? env->CallBooleanMethod(pm, isPinned, p0) != 0 : false;
        kk::bridge::EditRecord r6{}; r6.kind = 6; r6.pluginIdx = 0; r6.intVal = was ? 0 : 1;
        bool c6 = kk::bridge::applyEdit(r6); drain();
        bool now = pm ? env->CallBooleanMethod(pm, isPinned, p0) != 0 : false;
        std::printf("  EDIT_SET_PIN         -> consumed=%d pinned %d -> %d\n", c6 ? 1 : 0,
                    was ? 1 : 0, now ? 1 : 0);
        check(c6 && now != was, "pin edit landed in ProfileManager");
        kk::bridge::EditRecord r6b{}; r6b.kind = 6; r6b.pluginIdx = 0; r6b.intVal = was ? 1 : 0;
        kk::bridge::applyEdit(r6b); drain();       // put it back

        kk::bridge::EditRecord r4{}; r4.kind = 4; r4.pluginIdx = 0;
        std::snprintf(r4.key, sizeof r4.key, "%s", "range");   // Player visuals' int setting
        bool c4 = kk::bridge::applyEdit(r4); drain();
        kk::bridge::EditRecord r5{}; r5.kind = 5; r5.pluginIdx = 0;
        bool c5 = kk::bridge::applyEdit(r5); drain();
        std::printf("  EDIT_RESET_SETTING/PLUGIN -> consumed=%d/%d\n", c4 ? 1 : 0, c5 ? 1 : 0);
        check(c4 && c5, "reset edits consumed by Java");

        kk::bridge::EditRecord r9{}; r9.kind = 9;
        bool c9 = kk::bridge::applyEdit(r9); drain();          // no hub configured: consumed, dropped
        std::printf("  EDIT_HUB_REFRESH     -> consumed=%d (no hub configured: dropped Java-side)\n",
                    c9 ? 1 : 0);
        check(c9, "hub refresh consumed");

        // Second round trip, now that Java has real profiles (and pins) to publish: the v2 tail's
        // profile records cross all three decoders with live data behind them, not zeros.
        checkRoundTrip(kk::bridgeSnapshot(), "warm (after profile CRUD + pin)");
    }

    std::printf(g_fail ? "rt_probe: %d FAILURES\n" : "rt_probe: ALL PASS\n", g_fail);
    if (vm) vm->DestroyJavaVM();
    return g_fail ? 1 : 0;
}
