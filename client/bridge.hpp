// bridge.hpp -- the shared-memory panel bridge: the DLL's half.
//
// In launcher mode the panel is ImGui, drawn by the launcher PROCESS, which cannot touch Java (a
// second JVM is out of the question, and a second GL context is forbidden by design -- the game owns
// OpenGL). So the panel's data crosses a process boundary in both directions, and this file is the
// DLL end of it:
//
//   DLL -> launcher : a snapshot of the plugin/setting model. Java owns it; the DLL pulls it from
//                     kewl.panel.PanelBridge over JNI (jvm.hpp) and publishes it into shared memory
//                     whenever Java's revision changes.
//   launcher -> DLL : small edit records in a ring. Each is applied by calling PanelBridge's
//                     setBool/setInt/setEnum/setText -- i.e. through Setting.set, which is the ONLY
//                     acceptable landing place for an edit, because the RL ConfigManager shim's
//                     change listeners hang off Setting.set and an edit that bypasses them silently
//                     does nothing to the plugin.
//
// The launcher opens the mapping by name (it knows the pid it spawned) and finds both windows in the
// header, so neither side needs a window-class or title hunt.
//
// ------------------------------------------------------------------------------------------------
// THE SHARED LAYOUT -- all three sides must compile the same bytes: this file (the DLL, which writes
// the model region and drains the ring), launcher/bridge_layout.hpp (the launcher, which reads the
// model region and writes the ring) and kewl.panel.PanelBridge (Java, which produces the snapshot the
// DLL repacks). The launcher cannot include this file -- it pulls jvm.hpp and the JNI headers -- so
// bridge_layout.hpp RESTATES the layout and both files carry matching static_asserts: when this file
// changes, that one must change with it, and at least one of the two builds breaks if it does not.
// The byte offsets below are the contract.
//
//   "Local\KewlKlientBridge-<gamePid>"       the mapping, created here
//   "Local\KewlKlientBridge-<gamePid>-mtx"   its mutex, guarding the MODEL REGION only
//
//   offset      0  uint32 magic 'KKBR' (0x4B424252)
//   offset      4  uint32 format (2)
//   offset      8  uint64 launcherHwnd (the launcher's top-level window, as a handle value)
//   offset     16  uint64 dllMsgHwnd   (this DLL's hidden bridge window; edit-notify target)
//   offset     24  int64  modelRevision (volatile) -- Java's revision the model region was built from
//   offset     32  int64  editSeq       (volatile) -- records the launcher has written, ever
//   offset     40  EditRecord edits[64]                       (208 bytes each, see below)
//   offset  13352  int32 head (volatile)  launcher: writes record head % 64, then increments
//   offset  13356  int32 tail (volatile)  this DLL: applies record tail % 64, then increments
//   offset  13360  MODEL REGION (sizeof(Header); the static_asserts below pin all of this)
//
//   EditRecord: int32 kind, int32 pluginIdx, char key[64], int64 intVal (offset 72: naturally
//   8-aligned, so no padding anywhere), char text[128]. v2 turns the record into a small command:
//   the five fields are reused as arguments, and which of them mean something is listed per kind.
//   The launcher's writer zeroes the unused ones; the DLL's reader ignores them.
//
//     kind  name                    arguments and landing place in Java (PanelBridge)
//      0    EDIT_BOOL               key = setting key, intVal = 0/1 -> setBool. The one RESERVED key
//                                   is "enabled" (PanelBridge.ENABLE_KEY): that is the plugin's
//                                   on/off switch, not a setting -- setBool routes it to
//                                   Plugin.setEnabled. Every other key must name a Setting and lands
//                                   in Setting.set, which is the whole reason edits go through Java
//                                   rather than through the model region (the RL ConfigManager shim's
//                                   change listeners hang off Setting.set).
//      1    EDIT_INT                key = setting key, intVal = value -> setInt
//      2    EDIT_ENUM               key = setting key, intVal = INDEX into the setting's option
//                                   list, not a raw value -> setEnum
//      3    EDIT_TEXT               key = setting key, text = value -> setText
//      4    EDIT_RESET_SETTING      key = setting key -> resetSetting: Setting.reset for that one
//                                   setting. intVal/text unused.
//      5    EDIT_RESET_PLUGIN       pluginIdx only -> resetPlugin: every setting of that plugin
//                                   reset. intVal/key/text unused.
//      6    EDIT_SET_PIN            pluginIdx, intVal = 0/1 -> setPinned. Pinned is v2 Java-side
//                                   state (a v1 jar has nowhere to put it and drops the edit).
//      7    EDIT_HUB_INSTALL        pluginIdx = -1, text = hub plugin id -> hubInstall
//      8    EDIT_HUB_REMOVE         pluginIdx = -1, text = hub plugin id -> hubRemove
//      9    EDIT_HUB_REFRESH        no arguments -> hubRefresh
//     10    EDIT_PROFILE_SWITCH     intVal = profile index -> profileSwitch
//     11    EDIT_PROFILE_CREATE     text = profile name -> profileCreate
//     12    EDIT_PROFILE_DELETE     intVal = profile index -> profileDelete
//     13    EDIT_PROFILE_RENAME     intVal = profile index, text = new name -> profileRename
//     14    EDIT_PROFILE_DUPLICATE  intVal = profile index -> profileDuplicate
//
//   MODEL REGION, format 2. Format 1's bytes are an exact prefix of format 2's -- the new sections
//   are APPENDED after the plugin records, nothing in the v1 part moved -- so a v1 reader stops
//   being right exactly at the first byte it never knew about, which is why the header's format
//   field is the gate rather than any attempt at dual parsing.
//
//     [v1] int32 pluginCount                                    (cap MAX_PLUGINS)
//          per plugin:
//            int32 enabled, flags, hotkey(-1..7)
//                  (flags: bit0 = the plugin has settings -- this int used to BE "hasConfig 0/1"
//                   and bit0 still is exactly that; bit1 = developer scaffolding, which the panel
//                   sorts last under a "Developer" heading. PLUGIN_FLAG_* below. The developer bit
//                   fits in the spare bits of a field that was already here, which is why format 2
//                   did not have to become format 3.)
//            char name[64], desc[160], status[160]
//            int32 settingCount                                  (cap MAX_SETTINGS_PER_PLUGIN)
//            per setting:
//              int32 kind (0=bool, 1=int, 2=enum, 3=keybind, 4=color, 5=text),
//                    valueInt, min, max, enumIndex, optionCount, flags
//                    (flags: bit0=keybind, bit1=hasUnits, bit2=secret -- SETTING_FLAG_* below)
//              char key[64], label[96], desc[192], section[64], valueText[64]
//              optionCount (max MAX_OPTIONS) x char option[48]
//     [v2] int32 pinned[pluginCount]          0 or 1 each, index-parallel to the plugin records
//                                             above -- a plugin with no pinned state in Java is 0
//     [v2] int32 activeProfileIndex           -1 = no active profile, else 0..profileCount-1
//     [v2] int32 profileCount                 (cap MAX_PROFILES)
//          per profile: char name[64], char id[64]   -- id is the stable identifier the UI keys on,
//                                             name is what it shows; they are separate on purpose
//     [v2] int32 hubState                     0 = idle, 1 = loading, 2 = error, 3 = ready
//     [v2] char   hubError[160]               NUL-padded; "" unless hubState == 2
//     [v2] int32 hubCount                     (cap MAX_HUB)
//          per hub entry:
//            char id[64], name[96], version[32], author[64], desc[160]
//            int32 flags     bit0 = installed, bit1 = hasUpdate, bit2 = install/remove in flight
//            int32 installedPluginIdx    index into the plugin records above of the plugin this
//                                        entry installed, or -1 when it is not installed
//
//   THE JAVA SIDE of the same layout: kewl.panel.PanelBridge.snapshot() returns an int[] covering
//   exactly the region above, in the same order, with every fixed char[N] field replaced by a
//   length-prefixed UTF-8 string -- int32 byte length, then the bytes packed four to an int, lowest
//   byte first, padded up to a WHOLE number of ints (so a string of n bytes advances ceil(n/4) ints;
//   getting that wrong desynchronises everything after it). Concretely the int[] is:
//     magic, format, pluginCount, <plugin records as above with str name/desc/status, str key/label/
//     desc/section/valueText, str option...>, pinned[pluginCount], activeProfileIndex, profileCount,
//     <str name, str id> per profile, hubState, str hubError, hubCount, then per hub entry
//     str id, str name, str version, str author, str desc, int32 flags, int32 installedPluginIdx.
//   buildModel below is the parser for that array and the writer of the region's bytes; the launcher
//   reads the region, never the int[].
//
//   Every char field is NUL-padded UTF-8, cut back to a character boundary rather than a raw byte
//   (see truncUtf8). All multi-byte scalars are written little-endian by hand (see putU32) rather
//   than left to the compiler, and the sizes are static_asserted, so the sides cannot drift apart
//   silently.
// ------------------------------------------------------------------------------------------------
#pragma once
#include <windows.h>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include "jvm.hpp"

namespace kk::bridge {

constexpr std::uint32_t BRIDGE_MAGIC   = 0x4B424252u;   // 'KKBR'
constexpr std::uint32_t BRIDGE_VERSION = 2;

constexpr int RING_SLOTS  = 64;
constexpr int MAX_PLUGINS = 64;
// The ported RuneLite plugins carry their full config: Shortest Path alone declares 80 settings, so
// a cap sized for "a dozen" would reject the very plugin that most needs the panel. The cap is a
// sanity bound against a desynchronised parse, not a design limit -- a snapshot that trips it is
// garbage, and one that fits under it is published.
constexpr int MAX_SETTINGS_PER_PLUGIN = 256;
constexpr int MAX_OPTIONS = 8;
// v2 caps. Hub entries and profiles have no Java-side counterpart yet (PanelBridge v1 published
// neither), so these are sized generously rather than measured: 32 profiles and 64 hub entries are
// an order of magnitude past any real panel, and a cap here doubles as the desynchronised-parse
// guard the plugin/setting caps already are.
constexpr int MAX_PROFILES = 32;
constexpr int MAX_HUB      = 64;

// The edit kinds, in the order the table in the layout comment above lists them. Values are part of
// the contract: the launcher writes the number, the DLL's dispatch switches on it.
enum EditKind : std::int32_t {
    EDIT_BOOL              = 0,
    EDIT_INT               = 1,
    EDIT_ENUM              = 2,      // enumIndex: an index into the setting's option list
    EDIT_TEXT              = 3,
    // v2. Everything from here on is a command rather than a value write; see the layout comment.
    EDIT_RESET_SETTING     = 4,      // key -> Setting.reset for that one setting
    EDIT_RESET_PLUGIN      = 5,      // every setting of pluginIdx reset
    EDIT_SET_PIN           = 6,      // intVal 0/1: pinned/favourite
    EDIT_HUB_INSTALL       = 7,      // text = hub plugin id
    EDIT_HUB_REMOVE        = 8,      // text = hub plugin id
    EDIT_HUB_REFRESH       = 9,
    EDIT_PROFILE_SWITCH    = 10,     // intVal = profile index
    EDIT_PROFILE_CREATE    = 11,     // text = profile name
    EDIT_PROFILE_DELETE    = 12,     // intVal = profile index
    EDIT_PROFILE_RENAME    = 13,     // intVal = index, text = new name
    EDIT_PROFILE_DUPLICATE = 14,     // intVal = profile index
};

// Plugin flags (the int32 that follows `enabled` in each plugin record). CONFIG is the field's
// original "hasConfig 0/1" meaning, kept in bit0 so the field's old readers are still right; DEV is
// kewl.Plugin.developer() -- test rigs and worked examples the panel groups under a "Developer"
// heading, sorted after everything else. Mirrored in launcher/bridge_layout.hpp and as
// PLUGIN_FLAG_CONFIG / PLUGIN_FLAG_DEV in kewl.panel.PanelBridge.
constexpr std::int32_t PLUGIN_FLAG_CONFIG = 1 << 0;
constexpr std::int32_t PLUGIN_FLAG_DEV    = 1 << 1;

// Setting flags (int32 flags per setting record, per the layout comment). SECRET marks a text
// setting whose value the launcher edits in a password field and never draws in clear -- the value
// itself still travels in valueText, unmasked, because the field has to be able to edit it.
constexpr std::int32_t SETTING_FLAG_KEYBIND  = 1 << 0;
constexpr std::int32_t SETTING_FLAG_HASUNITS = 1 << 1;
constexpr std::int32_t SETTING_FLAG_SECRET   = 1 << 2;

// Hub entry flags (int32 flags per hub record, per the layout comment).
constexpr std::int32_t HUB_FLAG_INSTALLED  = 1 << 0;
constexpr std::int32_t HUB_FLAG_HAS_UPDATE = 1 << 1;
constexpr std::int32_t HUB_FLAG_BUSY       = 1 << 2;   // an install or remove is in flight

enum HubState : std::int32_t {
    HUB_IDLE    = 0,
    HUB_LOADING = 1,
    HUB_ERROR   = 2,
    HUB_READY   = 3,
};

#pragma pack(push, 4)
struct EditRecord {
    std::int32_t kind;
    std::int32_t pluginIdx;
    char         key[64];
    std::int64_t intVal;
    char         text[128];
};
#pragma pack(pop)
static_assert(sizeof(EditRecord) == 208, "bridge contract: an edit record is 208 bytes");

struct Header {
    std::uint32_t magic;
    std::uint32_t version;
    std::uint64_t launcherHwnd;
    std::uint64_t dllMsgHwnd;
    volatile std::int64_t modelRevision;
    volatile std::int64_t editSeq;
    EditRecord edits[RING_SLOTS];
    volatile std::int32_t head;
    volatile std::int32_t tail;
};
static_assert(offsetof(Header, edits) == 40,                    "bridge contract: ring at 40");
static_assert(offsetof(Header, head) == 40 + RING_SLOTS * 208,  "bridge contract: head after ring");
static_assert(offsetof(Header, tail) == 40 + RING_SLOTS * 208 + 4, "bridge contract: tail after head");

// The model region follows the header. 32 MB of address space for a page-file-backed section costs
// nothing until touched; the worst legal model (64 plugins x 256 settings, every setting carrying 8
// options) is about 14.6 MB of it, and format 2's appended sections add a few hundred KB at the caps
// (pinned[] 256 B, profiles ~4 KB, hub entries ~6 KB each). The real jar publishes 5 plugins /
// ~100 settings, a few kilobytes.
constexpr std::size_t MAPPING_BYTES = 32u * 1024 * 1024;
constexpr std::size_t MODEL_OFFSET  = sizeof(Header);
constexpr std::size_t MODEL_BYTES   = MAPPING_BYTES - MODEL_OFFSET;
static_assert(MODEL_OFFSET % 8 == 0, "bridge contract: the model region is 8-aligned");

// The reader's sanity caps, pinned so a future edit cannot quietly widen one past what Reader::count
// (and the launcher's matching guard) is willing to believe.
static_assert(MAX_PLUGINS <= 64 && MAX_SETTINGS_PER_PLUGIN <= 256 && MAX_PROFILES <= 64 &&
              MAX_HUB <= 64, "bridge contract: caps must stay within the reader's sanity bounds");

// ------------------------------------------------------------------------------------------------
// UTF-8 helpers. Java hands the snapshot over as standard UTF-8 (PanelBridge packs
// StandardCharsets.UTF_8 bytes four to an int), and the shared-memory char fields are UTF-8 too --
// ImGui renders UTF-8 -- so this is a copy with a boundary-safe cut, no transcoding.
// ------------------------------------------------------------------------------------------------

/// Cut to at most `max` bytes WITHOUT splitting a character: back over any continuation byte, the
/// same rule PanelBridge.utf8 applies on the Java side. A field cut mid-sequence would hand the
/// renderer a broken glyph instead of simply a shorter string.
inline std::string truncUtf8(const std::string& s, std::size_t max) {
    if (s.size() <= max) return s;
    std::size_t cut = max;
    while (cut > 0 && (static_cast<unsigned char>(s[cut]) & 0xC0) == 0x80) --cut;
    return s.substr(0, cut);
}

inline void putU32(std::vector<std::uint8_t>& v, std::uint32_t x) {
    v.push_back(static_cast<std::uint8_t>(x));
    v.push_back(static_cast<std::uint8_t>(x >> 8));
    v.push_back(static_cast<std::uint8_t>(x >> 16));
    v.push_back(static_cast<std::uint8_t>(x >> 24));
}

inline void putI32(std::vector<std::uint8_t>& v, std::int32_t x) { putU32(v, static_cast<std::uint32_t>(x)); }

/// A NUL-padded fixed char field. `field` includes the terminator, so the text gets field-1 bytes.
inline void putField(std::vector<std::uint8_t>& v, const std::string& s, std::size_t field) {
    std::string t = truncUtf8(s, field - 1);
    std::size_t start = v.size();
    v.resize(start + field, 0);
    if (!t.empty()) std::memcpy(v.data() + start, t.data(), t.size());
}

/// Walks the packed int[] Java produced. Every read is bounds-checked; one bad field marks the whole
/// snapshot rejected, because a half-parsed model would show the user a lie.
struct Reader {
    const jint* p   = nullptr;
    const jint* end = nullptr;
    bool ok = true;

    explicit Reader(const std::vector<jint>& v) : p(v.data()), end(v.data() + v.size()) {}

    std::int32_t i32() {
        if (!ok || p >= end) { ok = false; return 0; }
        return *p++;
    }
    /// A count that must be sane. Java caps its own counts, so reaching for the caps here means the
    /// two sides disagree about the format -- reject rather than guess.
    std::int32_t count(std::int32_t max) {
        std::int32_t n = i32();
        if (!ok || n < 0 || n > max) { ok = false; return 0; }
        return n;
    }
    /// Length-in-bytes then the bytes, four to an int, lowest byte first -- on the little-endian
    /// x86 we run under that is exactly the bytes in memory, so this is a memcpy (PanelBridge.Buf).
    /// Java pads the bytes up to a WHOLE number of ints, so the advance is ceil(n/4), not n: get
    /// that wrong and every string desynchronises the rest of the snapshot.
    std::string str() {
        std::int32_t n = count(65536);
        std::size_t words = (static_cast<std::size_t>(n) + 3) / 4;
        if (!ok || static_cast<std::size_t>(end - p) < words) { ok = false; return {}; }
        std::string s(reinterpret_cast<const char*>(p), static_cast<std::size_t>(n));
        p += static_cast<std::ptrdiff_t>(words);
        return s;
    }
};

// Field widths of the model region's char fields -- restated in launcher/bridge_layout.hpp under
// namespace model, which must match. Declared here because putField calls below need them.
namespace model {
constexpr std::size_t PLUGIN_NAME   = 64;
constexpr std::size_t PLUGIN_DESC   = 160;
constexpr std::size_t PLUGIN_STATUS = 160;
constexpr std::size_t SET_KEY       = 64;
constexpr std::size_t SET_LABEL     = 96;
constexpr std::size_t SET_DESC      = 192;
constexpr std::size_t SET_SECTION   = 64;
constexpr std::size_t SET_VALUETEXT = 64;
constexpr std::size_t SET_OPTION    = 48;
// v2
constexpr std::size_t PROFILE_NAME  = 64;
constexpr std::size_t PROFILE_ID    = 64;
constexpr std::size_t HUB_ERROR     = 160;   // same width as a plugin desc: one line of text
constexpr std::size_t HUB_ID        = 64;
constexpr std::size_t HUB_NAME      = 96;    // same width as a setting label
constexpr std::size_t HUB_VERSION   = 32;
constexpr std::size_t HUB_AUTHOR    = 64;
constexpr std::size_t HUB_DESC      = 160;   // same width as a plugin desc

// Fixed record sizes in the model region's v2 sections. The launcher's reader walks these fields one
// at a time rather than by record, but the numbers are pinned anyway (and restated in
// launcher/bridge_layout.hpp) so a width change here is a build break there, not a read that
// silently lands four bytes off for the rest of the region.
constexpr std::size_t PROFILE_RECORD_BYTES = PROFILE_NAME + PROFILE_ID;
constexpr std::size_t HUB_RECORD_BYTES     = HUB_ID + HUB_NAME + HUB_VERSION + HUB_AUTHOR +
                                             HUB_DESC + 4 /*flags*/ + 4 /*installedPluginIdx*/;
}  // namespace model

static_assert(model::PROFILE_RECORD_BYTES == 128, "bridge contract: a profile record is 128 bytes");
static_assert(model::HUB_RECORD_BYTES     == 424, "bridge contract: a hub record is 424 bytes");

/// Parse PanelBridge.snapshot() into the model region's byte layout. Returns false when the array is
/// not the format this DLL speaks (wrong tag, wrong format, truncated) -- the caller then keeps the
/// last good model instead of publishing garbage.
inline bool buildModel(std::vector<std::uint8_t>& out, const std::vector<jint>& snap) {
    Reader r(snap);
    if (r.i32() != static_cast<std::int32_t>(BRIDGE_MAGIC)) return false;    // PanelBridge MAGIC
    if (r.i32() != static_cast<std::int32_t>(BRIDGE_VERSION)) return false;  // PanelBridge FORMAT

    std::int32_t pluginCount = r.count(MAX_PLUGINS);
    if (!r.ok) return false;
    putI32(out, pluginCount);

    for (std::int32_t i = 0; i < pluginCount; ++i) {
        std::int32_t enabled = r.i32();
        std::int32_t flags   = r.i32();   // PLUGIN_FLAG_*: bit0 has settings, bit1 developer
        std::int32_t hotkey  = r.i32();
        std::string  name    = r.str();
        std::string  desc    = r.str();
        std::string  status  = r.str();
        if (!r.ok) return false;
        putI32(out, enabled);
        putI32(out, flags);
        putI32(out, hotkey);
        putField(out, name,   model::PLUGIN_NAME);
        putField(out, desc,   model::PLUGIN_DESC);
        putField(out, status, model::PLUGIN_STATUS);

        std::int32_t setCount = r.count(MAX_SETTINGS_PER_PLUGIN);
        if (!r.ok) return false;
        putI32(out, setCount);
        for (std::int32_t s = 0; s < setCount; ++s) {
            std::int32_t kind        = r.i32();
            std::int32_t valueInt    = r.i32();
            std::int32_t min         = r.i32();
            std::int32_t max         = r.i32();
            std::int32_t enumIndex   = r.i32();
            std::int32_t optionCount = r.count(MAX_OPTIONS);
            std::int32_t flags       = r.i32();
            std::string  key         = r.str();
            std::string  label       = r.str();
            std::string  sdesc       = r.str();
            std::string  section     = r.str();
            std::string  valueText   = r.str();
            if (!r.ok) return false;
            putI32(out, kind);
            putI32(out, valueInt);
            putI32(out, min);
            putI32(out, max);
            putI32(out, enumIndex);
            putI32(out, optionCount);
            putI32(out, flags);
            putField(out, key,       model::SET_KEY);
            putField(out, label,     model::SET_LABEL);
            putField(out, sdesc,     model::SET_DESC);
            putField(out, section,   model::SET_SECTION);
            putField(out, valueText, model::SET_VALUETEXT);
            for (std::int32_t o = 0; o < optionCount; ++o) putField(out, r.str(), model::SET_OPTION);
            if (!r.ok) return false;
        }
    }

    // ---- everything below is format 2. The order here is the contract order in the layout comment
    // ---- above, and launcher/bridge_layout.hpp documents the same sequence for its reader.

    // Pinned, one flag per plugin. Java owns the state; a v1 jar never sends this section at all and
    // the parse above has already failed by the time we would reach for it.
    for (std::int32_t i = 0; i < pluginCount; ++i) {
        std::int32_t pinned = r.i32();
        if (!r.ok) return false;
        putI32(out, pinned ? 1 : 0);
    }

    // Profiles: the active index, then the list. Both fields per profile, id after name, matching
    // the model region order the launcher reads.
    std::int32_t activeProfile = r.i32();
    std::int32_t profileCount  = r.count(MAX_PROFILES);
    if (!r.ok) return false;
    putI32(out, activeProfile);
    putI32(out, profileCount);
    for (std::int32_t i = 0; i < profileCount; ++i) {
        std::string pname = r.str();
        std::string pid   = r.str();
        if (!r.ok) return false;
        putField(out, pname, model::PROFILE_NAME);
        putField(out, pid,   model::PROFILE_ID);
    }

    // Hub: state, the error string ("" unless state == HUB_ERROR), then the entries.
    std::int32_t hubState = r.i32();
    std::string  hubError = r.str();
    std::int32_t hubCount = r.count(MAX_HUB);
    if (!r.ok) return false;
    putI32(out, hubState);
    putField(out, hubError, model::HUB_ERROR);
    putI32(out, hubCount);
    for (std::int32_t i = 0; i < hubCount; ++i) {
        std::string id      = r.str();
        std::string hname   = r.str();
        std::string version = r.str();
        std::string author  = r.str();
        std::string hdesc   = r.str();
        std::int32_t flags  = r.i32();
        std::int32_t inst   = r.i32();
        if (!r.ok) return false;
        putField(out, id,      model::HUB_ID);
        putField(out, hname,   model::HUB_NAME);
        putField(out, version, model::HUB_VERSION);
        putField(out, author,  model::HUB_AUTHOR);
        putField(out, hdesc,   model::HUB_DESC);
        putI32(out, flags);
        putI32(out, inst);
    }
    return true;
}

// ------------------------------------------------------------------------------------------------
// The server: the mapping, the mutex and the hidden window that receives edit notifications.
// ------------------------------------------------------------------------------------------------

inline UINT  g_msgEditNotify = 0;   // registered: launcher -> dllMsgHwnd after each edit batch
// Registered "KewlKlientBridgeActivate": the launcher also posts this to dllMsgHwnd on WM_ACTIVATE
// (wParam 1 = activated, 0 = deactivated) so the DLL can re-focus JagRenderView after the user clicks
// the strip. ADDITIVE on purpose -- this proc ignores it, matching today's behaviour where focus is
// already held by the attached input queues; a mode that needs it can read the flag later.
inline bool  g_editsPending  = false;
// int64, not long: Java's revision is a long and `long` is 32 bits in this toolchain, so a revision
// past 2^31 truncated on the way in here and never compared equal again -- a full snapshot rebuilt
// and republished on every retry tick for the rest of the session (review 2026-09-06).
inline std::int64_t g_publishedRevision = -1;
inline bool  g_publishedEmpty = false;
inline bool  g_bridgeLogged   = false;
// Set when a snapshot was rejected and cleared when one publishes: tick() uses it to say "recovered"
// once, so a jar that fixed itself mid-session is visible in the log rather than silently resuming.
inline bool  g_rejected       = false;
// Earliest GetTickCount64() at which a rejected snapshot may be retried. A snapshot Java cannot
// produce must not be re-pulled at 30 Hz -- each attempt is a JNI call that builds the whole model
// array -- but it must not be abandoned either: the revision the failure came from is the one the
// user is waiting on. One retry a second is nothing next to a frame and self-heals the moment Java
// can produce the model again.
inline std::uint64_t g_nextSnapshotRetryMs = 0;

struct Server {
    HANDLE  mapping = nullptr;
    HANDLE  mutex   = nullptr;
    Header* hdr     = nullptr;
    HWND    msgWnd  = nullptr;
};
inline Server g_srv;

/// The bridge window's proc. It exists for one message; everything else is DefWindowProc. The edit
/// notification only sets a flag -- the drain happens on the tick loop, under JNI, and doing JNI work
/// inside a window proc on a message the launcher just posted would serialise the launcher against us.
inline LRESULT CALLBACK msgProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == g_msgEditNotify && g_msgEditNotify) { g_editsPending = true; return 0; }
    // The real w/l, not zeros: DefWindowProc answers WM_NCCREATE from lParam's CREATESTRUCT, and a
    // null one makes it return FALSE -- which is CreateWindowExW failing with GetLastError() == 0,
    // exactly the "no message window (both ... failed, GetLastError=0)" line seen live on Windows
    // 2026-09-05. Edits still drained on the tick loop; only the notify latency was lost.
    return DefWindowProcW(h, m, w, l);
}

/// Create the mapping, the mutex and the hidden window, and fill in the header's window handles.
/// `launcherHwnd` came from launcher-mode detection; the launcher already knows its own hwnd, this is
/// just the shared record so either side can verify the other. Returns false if Windows refused --
/// launcher mode then runs with an empty panel rather than a crashed game.
inline void stop();
inline bool start(HMODULE module, HWND launcherHwnd) {
    // std::wstring, NOT swprintf: mingw-w64's C++ mode routes swprintf through its own C99 formatter,
    // where a wide format's "%s" means a NARROW string -- so the old `swprintf(L"%s-mtx", name)`
    // read the wide name as bytes and produced the mutex "Local\L-mtx". The mapping existed, its
    // mutex did not, and the launcher (which requires both) sat on "waiting for the DLL bridge"
    // forever. Seen live on Windows 2026-09-05, invisible under the llvm-mingw Wine build.
    const std::wstring name = L"Local\\KewlKlientBridge-" + std::to_wstring(GetCurrentProcessId());
    const std::wstring mtxName = name + L"-mtx";

    g_srv.mapping = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
                                       static_cast<DWORD>(MAPPING_BYTES), name.c_str());
    if (!g_srv.mapping) {
        kk::logf("[bridge] CreateFileMappingW failed (GetLastError=%lu)\n",
                    static_cast<unsigned long>(GetLastError()));
        std::fflush(stdout);
        return false;
    }
    // ERROR_ALREADY_EXISTS here means a previous DLL instance in this pid left one behind; the header
    // is rewritten below either way, so the launcher reads current data whichever way it landed.

    g_srv.hdr = static_cast<Header*>(MapViewOfFile(g_srv.mapping, FILE_MAP_ALL_ACCESS, 0, 0, MAPPING_BYTES));
    if (!g_srv.hdr) {
        kk::logf("[bridge] MapViewOfFile failed (GetLastError=%lu)\n",
                    static_cast<unsigned long>(GetLastError()));
        std::fflush(stdout);
        stop();
        return false;
    }

    // Only the header: the model region is written wholesale by publish, and zeroing 32 MB here would
    // be the most expensive line in the DLL for no reason.
    std::memset(g_srv.hdr, 0, sizeof(Header));
    g_srv.hdr->magic        = BRIDGE_MAGIC;
    g_srv.hdr->version      = BRIDGE_VERSION;
    g_srv.hdr->launcherHwnd = reinterpret_cast<std::uint64_t>(launcherHwnd);

    g_srv.mutex = CreateMutexW(nullptr, FALSE, mtxName.c_str());
    if (!g_srv.mutex) {
        kk::logf("[bridge] CreateMutexW failed (GetLastError=%lu)\n",
                    static_cast<unsigned long>(GetLastError()));
        std::fflush(stdout);
        stop();
        return false;
    }

    // A message-only window: it never appears on screen, has no X window under Wine, and still
    // receives PostMessageW from another process -- which is all it is for. Seen live under Wine 10:
    // CreateWindowExW with HWND_MESSAGE fails here with GetLastError left at 0, so fall back to a
    // plain hidden top-level -- equally invisible in practice (zero size, not enumerated on screen
    // with any content) and cross-process PostMessageW works the same. And because the DLL drains
    // the edit ring every tick anyway, a failed window only costs latency, not the bridge: failure
    // here is logged, not fatal.
    WNDCLASSEXW wc{ sizeof wc };
    wc.lpfnWndProc   = msgProc;
    wc.hInstance     = module;
    wc.lpszClassName = L"KewlKlientBridgeMsg";
    RegisterClassExW(&wc);
    g_srv.msgWnd = CreateWindowExW(0, wc.lpszClassName, L"", 0, 0, 0, 0, 0, HWND_MESSAGE,
                                   nullptr, module, nullptr);
    if (!g_srv.msgWnd)
        g_srv.msgWnd = CreateWindowExW(0, wc.lpszClassName, L"", WS_POPUP, 0, 0, 0, 0,
                                       nullptr, nullptr, module, nullptr);
    if (!g_srv.msgWnd) {
        kk::logf("[bridge] no message window (both HWND_MESSAGE and hidden fallback failed, GetLastError=%lu)"
                    " -- edits drain on the tick loop, the notify is just latency\n",
                    static_cast<unsigned long>(GetLastError()));
        std::fflush(stdout);
    }

    g_srv.hdr->dllMsgHwnd = reinterpret_cast<std::uint64_t>(g_srv.msgWnd);
    g_msgEditNotify = RegisterWindowMessageW(L"KewlKlientBridgeEdit");
    return true;
}

inline void stop() {
    if (g_srv.msgWnd)  { DestroyWindow(g_srv.msgWnd); g_srv.msgWnd = nullptr; }
    if (g_srv.hdr)     { UnmapViewOfFile(g_srv.hdr);  g_srv.hdr = nullptr; }
    if (g_srv.mapping) { CloseHandle(g_srv.mapping);  g_srv.mapping = nullptr; }
    if (g_srv.mutex)   { CloseHandle(g_srv.mutex);    g_srv.mutex = nullptr; }
}

/// Write the model region. JNI work (the snapshot pull) happens BEFORE the mutex is taken, so the
/// launcher never waits on a Java call; the critical section is one memcpy. Returns false when the
/// snapshot could not be published -- the caller then keeps the last good model and the last good
/// revision, and retries (rate-limited, see tick) instead of consuming the revision silently.
inline bool publishModel(std::int64_t revision) {
    std::vector<jint> snap = kk::bridgeSnapshot();
    std::vector<std::uint8_t> bytes;
    bytes.reserve(64 * 1024);
    if (!buildModel(bytes, snap)) {
        // Not the format we speak (wrong tag, wrong format, TRUNCATED -- which includes a snapshot
        // that came back null because Java's walk threw): keep the last good model. The size is in
        // the log because the three failures look identical from here and are nothing alike -- a
        // truncated v2 tail reads as a format mismatch but is Java's bug, not the jar's vintage.
        if (!g_bridgeLogged) {
            g_bridgeLogged = true;
            kk::logf("[bridge] snapshot rejected (%zu ints) -- keeping the last good model; "
                        "retrying while Java's revision stays ahead\n", snap.size());
            std::fflush(stdout);
        }
        return false;
    }
    g_rejected = false;
    if (bytes.size() > MODEL_BYTES) return false;   // cannot happen within the caps; guarded regardless

    DWORD wait = WaitForSingleObject(g_srv.mutex, 2000);
    if (wait != WAIT_OBJECT_0 && wait != WAIT_ABANDONED) return false;   // ABANDONED: launcher died mid-write
    std::memcpy(reinterpret_cast<std::uint8_t*>(g_srv.hdr) + MODEL_OFFSET, bytes.data(), bytes.size());
    InterlockedExchange64(reinterpret_cast<volatile LONG64*>(&g_srv.hdr->modelRevision), revision);
    ReleaseMutex(g_srv.mutex);
    return true;
}

/// Publish an empty model once, so a jar without PanelBridge shows the launcher an empty panel rather
/// than leaving it waiting for a revision that will never come.
inline void publishEmptyOnce() {
    if (g_publishedEmpty) return;
    g_publishedEmpty = true;
    if (!g_srv.hdr) return;
    // A COMPLETE empty model, not just a zeroed plugin count: since format 2 the region does not end
    // at the plugin records, and a reader that parsed "0 plugins" and then reached for pinned[],
    // activeProfileIndex and the hub block would find the mapping's zero pages -- which happen to
    // parse (profileCount 0, hubCount 0), but only by luck of the layout. Writing the real tail keeps
    // the launcher's reader honest: every count it reads was written deliberately. The hub error is
    // a fixed char[160] field here (the length-prefixed form is Java's snapshot encoding, not the
    // region's), so the region is 4 + 4 + 4 + 4 + 160 + 4 = 180 bytes.
    std::uint8_t empty[180] = {};
    {
        std::uint8_t* w = empty;
        auto zero = [&w]() { w[0] = w[1] = w[2] = w[3] = 0; w += 4; };
        auto minusOne = [&w]() { w[0] = w[1] = w[2] = w[3] = 0xFF; w += 4; };
        zero();                     // pluginCount = 0, hence no pinned[] entries
        minusOne();                 // activeProfileIndex = -1: no active profile
        zero();                     // profileCount = 0
        zero();                     // hubState = HUB_IDLE
        w += model::HUB_ERROR;      // hubError: a fixed field, already all zero == ""
        zero();                     // hubCount = 0
    }
    DWORD wait = WaitForSingleObject(g_srv.mutex, 2000);
    if (wait != WAIT_OBJECT_0 && wait != WAIT_ABANDONED) return;
    std::memcpy(reinterpret_cast<std::uint8_t*>(g_srv.hdr) + MODEL_OFFSET, empty, sizeof empty);
    InterlockedExchange64(reinterpret_cast<volatile LONG64*>(&g_srv.hdr->modelRevision), 0);
    ReleaseMutex(g_srv.mutex);
    if (!g_bridgeLogged) {
        g_bridgeLogged = true;
        kk::logf("[bridge] kewl/panel/PanelBridge not found -- panel model stays empty\n");
        std::fflush(stdout);
    }
}

/// Apply one edit record through Java. Returns true when the edit is CONSUMED -- delivered to Java, or
/// rejected by it. Only a failure to deliver at all (no VM, out of memory) returns false, because
/// retrying a rejected edit would burn the ring on an edit Java has already said no to.
inline bool applyEdit(const EditRecord& r) {
    char key[65]  = {};   // the shared fields are not guaranteed NUL-terminated -- copy into one
    char text[129] = {};  // that is, then hand Java a well-formed string
    std::memcpy(key, r.key, sizeof r.key);
    std::memcpy(text, r.text, sizeof r.text);
    bool ok = kk::bridgeApply(r.kind, r.pluginIdx, key, r.intVal, text);
    // text may be a password on its way to Setting.set: do not leave a copy of it on this thread's
    // stack for the next frame to reuse or a crash dump to capture (review 2026-09-06).
    SecureZeroMemory(text, sizeof text);
    return ok;
}

/// Take every record the launcher has published and land each one in Java. The tail advances only
/// after a record is consumed, so a crash between the JNI call and the bump replays that one edit --
/// the same at-least-once choice every queue makes, and the safe one here (a dropped edit is a
/// settings change the user never sees). The one seam: a crash between the slot wipe below and the
/// bump replays a zeroed record, which Java rejects as an empty key -- a strictly better outcome than
/// leaving a consumed password in shared memory to avoid it.
inline void drainEdits() {
    if (!g_srv.hdr) return;
    std::int32_t head = g_srv.hdr->head;
    std::int32_t tail = g_srv.hdr->tail;
    if (head <= tail) { g_editsPending = false; return; }

    if (head - tail >= RING_SLOTS) {
        // THE INVARIANT: the launcher never lets head - tail exceed RING_SLOTS - 1 (writeEdit refuses
        // at that point), so a FULL ring is 63 pending records and every one of them is drained
        // normally by the loop below. Reaching RING_SLOTS here therefore means the producer broke the
        // contract, not that the user dragged a slider through a stall -- which is exactly what this
        // guard used to punish: the launcher filled to 64, this branch called it lapped, and all 64
        // edits (the final slider value included) were dropped with no log line (review 2026-09-06).
        //
        // Why the drop is still right at RING_SLOTS: every slot then holds a record we never read and
        // the launcher's NEXT write targets slot head % 64 -- exactly where record `tail` lives -- so
        // reading it would copy a 208-byte struct being concurrently overwritten, half of one edit and
        // half of the next. The honest response is to drop the whole backlog: the next model publish
        // re-syncs what the panel shows, and chasing 64 stale edits would replay old values over new
        // ones.
        tail = head;
        InterlockedExchange(reinterpret_cast<volatile LONG*>(&g_srv.hdr->tail), tail);
        return;
    }

    while (tail < head) {
        EditRecord* slot = &g_srv.hdr->edits[static_cast<std::size_t>(tail) % RING_SLOTS];
        EditRecord local;      // copy out: applying it takes a JNI call, and the launcher may be
        std::memcpy(&local, slot, sizeof local);
        if (!applyEdit(local)) break;
        // Wipe the slot before the tail bump. An EDIT_TEXT record carries a setting's value in the
        // clear -- the AutoLogin password among them -- and a consumed record used to sit in the 32 MB
        // section for the rest of the session, readable by anything in the session that opens the
        // mapping by name and present in any full-memory dump of this process (review 2026-09-06).
        // Safe here: the slot the launcher may write next is head % 64, and head - tail < RING_SLOTS
        // means that is never this one. The local copy dies with this iteration's stack frame.
        SecureZeroMemory(slot, sizeof *slot);
        SecureZeroMemory(&local, sizeof local);
        ++tail;
        InterlockedExchange(reinterpret_cast<volatile LONG*>(&g_srv.hdr->tail), tail);
    }
    g_editsPending = false;
}

/// One tick of the bridge, called from the main loop. Two cheap things: a poll of Java's revision
/// (one JNI int call) and a drain of whatever edits are queued. The edit-notify message only sets a
/// flag; the loop is the pump, at the same ~30 Hz as everything else in this DLL.
inline void tick() {
    if (!g_srv.hdr) return;

    if (kk::bridgeAvailable()) {
        std::int64_t rev = kk::bridgeModelRevision();
        std::uint64_t now = GetTickCount64();
        if (rev >= 0 && rev != g_publishedRevision && now >= g_nextSnapshotRetryMs) {
            // The revision is only consumed on SUCCESS: a rejected snapshot leaves g_publishedRevision
            // pointing at the last good model, so the comparison above stays true and this retries at
            // the rate limit until Java can produce the model it already announced.
            //
            // The rate limit belongs to the FAILURE path alone. Arming it before the attempt (as this
            // did) also throttled the good case: a publish that succeeded blocked the next one for a
            // second, so a toggle or a status line landing just after a publish waited up to 1 s to
            // reach the strip -- most visible on reset rows, which have no optimistic echo to cover
            // the gap (review 2026-09-06).
            if (publishModel(rev)) {
                g_publishedRevision = rev;
                g_nextSnapshotRetryMs = 0;
                if (g_rejected) {
                    g_rejected = false;
                    kk::logf("[bridge] snapshot recovered\n");
                    std::fflush(stdout);
                }
            } else {
                g_rejected = true;
                g_nextSnapshotRetryMs = now + 1000;
            }
        }
        drainEdits();
    } else {
        // No PanelBridge in the jar: an old jar beside a new launcher. Nothing to publish, nothing
        // that could consume an edit, so say so once and leave the ring untouched.
        publishEmptyOnce();
    }
}

}  // namespace kk::bridge
