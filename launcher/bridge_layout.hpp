// bridge_layout.hpp -- the launcher's view of the shared-memory panel bridge.
//
// The AUTHORITATIVE definition of this layout lives in client/bridge.hpp, next to the code that
// WRITES it (the DLL creates the mapping, publishes the model and drains the edit ring). That file
// cannot be included here directly: it pulls in jvm.hpp and the JNI headers, and the launcher has
// no JVM and no reason to compile any of that. So the byte layout is restated below, and the
// static_asserts pin the same offsets client/bridge.hpp asserts -- if either side ever changes the
// layout, at least one of the two builds breaks instead of the two processes silently disagreeing
// about where byte 40 is.
//
// *** THIS FILE AND client/bridge.hpp MUST CHANGE TOGETHER. *** Every constant, every enum value and
// every static_assert below has a counterpart there; the format-2 additions (edit kinds 4..14, the
// pinned[]/profiles/hub sections) were added to both in the same change. client/bridge.hpp's layout
// comment at the top is the wordy version of everything summarised here, including the exact field
// order of the model region and the string encoding Java's snapshot uses.
//
// Everything else about the contract (naming, ownership, the mutex, the edit-notify message) is
// documented at the top of client/bridge.hpp. Three facts from there matter to this side:
//   * the mutex guards the MODEL REGION only -- the edit ring is a single-producer/single-consumer
//     ring the DLL reads with plain loads, so the launcher publishes a record and THEN bumps head,
//     and never blocks the DLL's publish path on an edit write;
//   * the ring indices are monotonically increasing, live slot = index % RING_SLOTS, and a FULL ring
//     is RING_SLOTS - 1 pending records: the launcher refuses to write at that point because the DLL
//     treats head - tail == RING_SLOTS as a lapped ring and drops the whole backlog (review
//     2026-09-06 -- filling to exactly 64 delivered nothing, not 64 edits);
//   * the model region starts at sizeof(Header) and is bounded by the mapping, which the DLL
//     created at MAPPING_BYTES (32 MB).
#pragma once

#include <cstdint>
#include <cstddef>

namespace kewl_bridge {

constexpr std::uint32_t MAGIC   = 0x4B424252u;   // 'KKBR'
constexpr std::uint32_t VERSION = 2;
constexpr int RING_SLOTS  = 64;
constexpr int MAX_OPTIONS = 8;
// v2 caps -- the same numbers client/bridge.hpp caps ITS reader at, restated because this side is the
// one that must not trust the writer: a model whose counts exceed these is not a model this launcher
// understands, and reading one would desynchronise the parser rather than merely show nonsense.
constexpr int MAX_PLUGINS            = 64;
constexpr int MAX_SETTINGS_PER_PLUGIN = 256;
constexpr int MAX_PROFILES           = 32;
constexpr int MAX_HUB                = 64;

enum EditKind : std::int32_t {
    EDIT_BOOL = 0,
    EDIT_INT  = 1,
    EDIT_ENUM = 2,      // enumIndex: an index into the setting's option list, not a raw value
    EDIT_TEXT = 3,
    // v2 -- command-shaped edits. Which of the record's five fields carries the argument is listed
    // in client/bridge.hpp's layout comment; the short form:
    //   RESET_SETTING(4): key. RESET_PLUGIN(5): pluginIdx only. SET_PIN(6): intVal 0/1.
    //   HUB_INSTALL(7)/HUB_REMOVE(8): text = hub plugin id, pluginIdx = -1. HUB_REFRESH(9): none.
    //   PROFILE_SWITCH(10)/DELETE(12)/DUPLICATE(14): intVal = profile index.
    //   PROFILE_CREATE(11): text = name. PROFILE_RENAME(13): intVal = index, text = new name.
    EDIT_RESET_SETTING     = 4,
    EDIT_RESET_PLUGIN      = 5,
    EDIT_SET_PIN           = 6,
    EDIT_HUB_INSTALL       = 7,
    EDIT_HUB_REMOVE        = 8,
    EDIT_HUB_REFRESH       = 9,
    EDIT_PROFILE_SWITCH    = 10,
    EDIT_PROFILE_CREATE    = 11,
    EDIT_PROFILE_DELETE    = 12,
    EDIT_PROFILE_RENAME    = 13,
    EDIT_PROFILE_DUPLICATE = 14,
};

enum SettingKind : std::int32_t {
    SET_BOOL    = 0,
    SET_INT     = 1,
    SET_ENUM    = 2,
    SET_KEYBIND = 3,
    SET_COLOR   = 4,
    SET_TEXT    = 5,
};

// Plugin flags: the int32 that follows `enabled` in every plugin record. bit0 is the field's
// original "hasConfig 0/1" meaning, unchanged, which is why the developer bit needed no format bump
// -- same field, same offset, spare bits. bit1 is kewl.Plugin.developer(): smoke tests and worked
// examples, which the plugin list sorts last under a "Developer" heading. Same constants in
// client/bridge.hpp (PLUGIN_FLAG_*) and kewl.panel.PanelBridge (PLUGIN_FLAG_CONFIG / _DEV).
constexpr std::int32_t PLUGIN_FLAG_CONFIG = 1 << 0;
constexpr std::int32_t PLUGIN_FLAG_DEV    = 1 << 1;

constexpr std::int32_t FLAG_KEYBIND  = 1 << 0;
constexpr std::int32_t FLAG_HASUNITS = 1 << 1;
// A SET_TEXT whose value is a secret (a password): the strip edits it in a password-mode field and
// never draws it in clear anywhere. valueText carries the real value regardless -- the masking is
// this side's job, so the field can round-trip an edit.
constexpr std::int32_t FLAG_SECRET   = 1 << 2;

// Hub entry flags and the hub's overall state (int32 fields in the model region's hub block).
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
    std::int32_t  kind;
    std::int32_t  pluginIdx;
    char          key[64];
    std::int64_t  intVal;      // offset 72: naturally 8-aligned inside the record, so no padding
    char          text[128];
};
#pragma pack(pop)

// Unpacked on purpose -- this is the exact struct client/bridge.hpp defines. HWNDs travel as u64
// here because that is what the writer stores; the launcher casts them back to HWND.
struct Header {
    std::uint32_t magic;
    std::uint32_t version;
    std::uint64_t launcherHwnd;
    std::uint64_t dllMsgHwnd;
    volatile std::int64_t modelRevision;
    volatile std::int64_t editSeq;
    EditRecord edits[RING_SLOTS];
    volatile std::int32_t head;      // launcher-owned: written after the record it points past
    volatile std::int32_t tail;      // DLL-owned
};

static_assert(sizeof(EditRecord) == 208, "bridge contract (client/bridge.hpp): a record is 208 bytes");
static_assert(offsetof(Header, launcherHwnd) == 8,   "bridge contract (client/bridge.hpp): hwnds after the two u32s");
static_assert(offsetof(Header, modelRevision) == 24, "bridge contract (client/bridge.hpp): revision at 24");
static_assert(offsetof(Header, editSeq) == 32,       "bridge contract (client/bridge.hpp): editSeq at 32");
static_assert(offsetof(Header, edits) == 40,         "bridge contract (client/bridge.hpp): ring at 40");
static_assert(offsetof(Header, head) == 40 + RING_SLOTS * 208,     "bridge contract (client/bridge.hpp): head after the ring");
static_assert(offsetof(Header, tail) == 40 + RING_SLOTS * 208 + 4, "bridge contract (client/bridge.hpp): tail after head");

// client/bridge.hpp maps MAPPING_BYTES and puts the model region at sizeof(Header); these two
// constants restate that so the launcher's bounds checks and the DLL's writes cannot drift.
//
// MAPPING_BYTES must equal client/bridge.hpp's, but the launcher does not RELY on the number: it
// maps with dwNumberOfBytesToMap = 0 (the whole section, however big the DLL made it) and takes the
// real size from VirtualQuery, so a page-file-backed section can grow here without this file
// noticing. The constant is still pinned, because readModel's `end` bound is expressed against the
// mapping and a reader that assumed a smaller mapping than the writer created would clip a legal
// model. The worst legal model (64 plugins x 256 settings x 8 options, plus format 2's appended
// sections) is ~14.7 MB, hence 32.
constexpr std::size_t MAPPING_BYTES = 32u * 1024 * 1024;
constexpr std::size_t MODEL_OFFSET  = sizeof(Header);      // 13360
static_assert(MODEL_OFFSET == 13360, "bridge contract (client/bridge.hpp): the model region starts at 13360");

// Field widths in the model region -- the packed byte stream the DLL writes under the mutex.
// client/bridge.hpp::buildModel is the writer; the order there is the order here. The plugin record
// itself is int32 enabled, int32 flags (PLUGIN_FLAG_* above), int32 hotkey, then the three char
// fields below and the setting count.
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
// format 2. These five sections come AFTER the plugin records, in exactly this order:
//   1. int32 pinned[pluginCount]                0/1, index-parallel to the plugin records
//   2. int32 activeProfileIndex                 -1 = none
//   3. int32 profileCount, then per profile     char name[PROFILE_NAME], char id[PROFILE_ID]
//   4. int32 hubState, char hubError[HUB_ERROR],
//      int32 hubCount, then per hub entry       char id[HUB_ID], char name[HUB_NAME],
//                                               char version[HUB_VERSION], char author[HUB_AUTHOR],
//                                               char desc[HUB_DESC], int32 flags,
//                                               int32 installedPluginIdx (-1 when not installed)
constexpr std::size_t PROFILE_NAME  = 64;
constexpr std::size_t PROFILE_ID    = 64;
constexpr std::size_t HUB_ERROR     = 160;
constexpr std::size_t HUB_ID        = 64;
constexpr std::size_t HUB_NAME      = 96;
constexpr std::size_t HUB_VERSION   = 32;
constexpr std::size_t HUB_AUTHOR    = 64;
constexpr std::size_t HUB_DESC      = 160;

constexpr std::size_t PROFILE_RECORD_BYTES = PROFILE_NAME + PROFILE_ID;
constexpr std::size_t HUB_RECORD_BYTES     = HUB_ID + HUB_NAME + HUB_VERSION + HUB_AUTHOR +
                                             HUB_DESC + 4 /*flags*/ + 4 /*installedPluginIdx*/;
}  // namespace model

static_assert(model::PROFILE_RECORD_BYTES == 128, "bridge contract (client/bridge.hpp): a profile record is 128 bytes");
static_assert(model::HUB_RECORD_BYTES     == 424, "bridge contract (client/bridge.hpp): a hub record is 424 bytes");

}  // namespace kewl_bridge
