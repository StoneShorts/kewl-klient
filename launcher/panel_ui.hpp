// panel_ui.hpp -- the ImGui side panel the LAUNCHER draws, over the format-2 bridge model.
//
// In launcher mode the strip on the right of the launcher window is this file's job: the launcher
// process has no OpenGL and no Java, so the panel is Dear ImGui rasterized by the CPU
// (client/imgui_sw.hpp) into the window's DIB. The DATA, though, still lives in the game process --
// Java owns the plugin list, the Setting objects, the profiles and the hub, so everything drawn here
// comes from the shared-memory bridge (client/bridge.hpp, parsed in launcher/main.cpp) and every
// mutation goes back as an edit record. Edits MUST land in Java through the DLL (see kewl/panel/
// PanelBridge): an edit that went around Setting.set would silently do nothing to the plugin.
//
// The layout is RuneLite's sidebar in shape -- a 250px body plus a 36px icon rail, four routes
// (plugins, profiles, hub, debug), a plugin list, a pushed config view per plugin -- but the
// TREATMENT is the design pass described below rather than a Java2D transcription.
//
// -------------------------------------------------------------------------------------------------
// THE DESIGN PASS (2026-09-06). Six rules, and what each one cost or bought here:
//
//  1. TOKENS. Every spacing, size, radius, duration and colour below is a named constant on a 4px
//     base with a reason attached. No LAYOUT value is written inline: a position, a gap, a size or a
//     colour in the drawing code is a token, or arithmetic on tokens, or 0.5f for a centre and 1.0f
//     for a hairline. This is the "you can defend every value" rule.
//     THE ONE EXCEPTION, stated so it does not rot into an excuse: the interior path coordinates of
//     the icon glyphs (pencilGlyph, copyGlyph, crossGlyph, resetGlyph, chevronGlyph, gearWidget,
//     starWidget, tabIcon) are literals like 9.5f and 1.4f. Those are vector artwork inside an
//     ICON_GLYPH-sized cell, not layout -- the cell's ORIGIN and SIZE are tokens, and where the
//     pencil's nib sits inside it is a drawing, the same way a .svg's path data is not a design
//     system. The exception is PATH COORDINATES ONLY: the spinner's rotation rate and sweep are
//     timing and shape rather than a path, so they are tokens (SPIN_*), not literals.
//
//  2. RESPONSE ON POINTER-DOWN. ImGui::Button() fires on RELEASE and only shades on release; that
//     reads as lag on a 30fps software-rasterised strip. Every control here is an InvisibleButton
//     read through IsItemActive() (pressed look, this frame) and IsItemClicked() (which in ImGui is
//     the PRESS edge, not the release edge). pressButton() replaces ImGui::Button everywhere.
//
//  3. MOTION IS A SPRING, NOT A TWEEN. springTo() integrates a damped spring against io.DeltaTime
//     FROM THE CURRENT ON-SCREEN VALUE, so an interrupted animation redirects instead of restarting.
//     Two parameters, damping ratio and response, and one default: critically damped (1.0). Nothing
//     in this panel is reached by a momentum gesture, so nothing bounces. Input is never locked
//     during a transition -- items are submitted at the animated position, so what you see is what
//     you can click.
//
//  4. MATERIAL ENCODES HIERARCHY, AND THE RASTERISER GETS A VOTE. The rail is the heaviest plane,
//     the body sits on it, rows sit on the body, and a pressed row rises LIGHTER rather than sinking
//     darker. Hard 1px dividers are gone: the body/rail seam is carried by the material step, and
//     the list's edges against the fixed header and footer are scroll-edge fades.
//     THE CONSTRAINT: imgui_sw's fast fill path (detail::matchQuad -> fillQuad, a straight row loop)
//     is only taken for an INTEGER-ALIGNED, UNROUNDED, CONSTANT-COLOUR rect. A rounded rect is one
//     convex polygon, so every pixel of it goes through the barycentric path instead. Thirty rounded
//     230x24 row fills is ~165k px/frame down the slow path; the spike measured ~11.5ms for ~229k px
//     that way. So: full-width fills are square and snapped (RADIUS_ROW == 0), rounding is spent
//     only on small controls, and even the animated offsets are snapped to whole pixels so the fast
//     path survives a transition.
//
//  5. TYPE HAS ONE SIZE, SO HIERARCHY COMES FROM ELSEWHERE. The atlas is AddFontDefaultBitmap at
//     13px -- a bitmap face, sampled NEAREST by imgui_sw (sampleTexture), and "recommended at 13px
//     with no scaling". Scaling it would smear the stems, so there is no size axis and no italic.
//     What is left, and what this file uses deliberately: WEIGHT (textAt's bold double-strike at
//     exactly +1px), COLOUR (a three-step text ramp), LEADING (LEAD_*), and TRACKING (textTracked,
//     integral only -- a fractional advance would move a glyph quad off the texel grid and the
//     nearest sample would drop a column). Every text position is snapped for the same reason.
//
//  6. WAYFINDING. Two fixed lines of chrome at the top answer "where am I" (route name, or the
//     plugin's name) and "how do I get out" (a back control that names its destination and never
//     scrolls away), and one fixed line at the bottom answers "is this thing connected".
//
// KNOWN GAPS, on purpose rather than faked:
//   * plugin tags are not searchable: the model region carries name/desc/status and no tag list, so
//     the filter runs over what the bridge actually sends;
//   * colour settings render read-only: the contract has no colour edit kind (kinds 0..3 are
//     bool/int/enum/text), so there is nothing to send. TEXT settings are NOT read-only -- they are
//     edited in place through textInput()'s keyboard handoff, which exists precisely to give the
//     strip a keyboard, with secrets masked in a password field. Their one limit is the bridge's
//     63-byte valueText field, which the field itself enforces (see SET_TEXT in settingRow);
//   * sections render in first-appearance order -- the bridge model does not carry RlConfigMeta's
//     section positions, which is what the Java ConfigView sorts by;
//   * a reset echo cannot show the default value: Setting's default lives Java-side, so the row keeps
//     its old value until the next publish carries the reset one back;
//   * "Reduced motion" is persisted by launcher/main.cpp, not here: uiReducedMotion() is a plain
//     accessor precisely so main.cpp's loadPaths/persistCollapse can mirror it to the ini's
//     [kewl] motion= key, the same way it mirrors uiCollapsed() to sidebar=. This file owns no file
//     I/O and no ini path, so the switch is the state and main.cpp is the storage.
#pragma once

#include <windows.h>           // SecureZeroMemory: the secret text row wipes its own edit buffer.
                               // main.cpp includes this first anyway; stated here so the header
                               // stands on its own.

#include "imgui.h"
#include "imgui_internal.h"    // ClearActiveID: the launcher surrenders a live text field when
                               // the user clicks back into the game (see main.cpp keepKeyboard)
#include "bridge_layout.hpp"

#include <algorithm>
#include <cmath>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <map>
#include <string>
#include <vector>

namespace kewl_panel {

// =================================================================================================
// DESIGN TOKENS
//
// One block, one reason per line, no magic numbers past it. The scale is deliberate rather than
// inherited: a 4px base, because the smallest thing that has to line up with anything else here is
// a 12px glyph inside a 16px hit box inside a 24px row, and 4 is the largest step that divides all
// three. SP_HAIR (2) exists for optical centring of odd-width glyphs only and is not a layout step.
// =================================================================================================

// ---- structure ----------------------------------------------------------------------------------
constexpr int BODY_W  = 250;                  // SidePanel.java BODY_W: the width Java's panel occupies
constexpr int RAIL_W  = 36;                   // SidePanel.java TAB_W
constexpr int PANEL_W = BODY_W + RAIL_W;      // 286 -- what main.cpp lays the game child out against

// ---- spacing: a 4px base ------------------------------------------------------------------------
constexpr float SP_HAIR = 2.0f;               // optical nudge only (centring a 12px glyph in 16px)
constexpr float SP_1    = 4.0f;               // the gap between two things that belong together
constexpr float SP_2    = 8.0f;               // the gap between two things that merely sit together
constexpr float SP_3    = 12.0f;              // block padding inside a region
constexpr float SP_4    = 16.0f;              // separation between blocks

// ---- sizes --------------------------------------------------------------------------------------
constexpr float ROW_H      = 24.0f;           // 13px line + SP_1 above and below, rounded to the base:
                                              // the shortest row that is still a comfortable target
constexpr float ROW_GAP    = SP_1;            // set as ItemSpacing.y, so the row PITCH is ROW_H+ROW_GAP
constexpr float ICON_HIT   = 16.0f;           // hit box for a 12px glyph: 4 base units, +2 each side
constexpr float ICON_GLYPH = 12.0f;           // the glyphs themselves (star, gear, pencil, cross...)
constexpr float TOGGLE_W   = 28.0f;           // Widgets.toggle's pill, kept: it is the one control
constexpr float TOGGLE_H   = 16.0f;           // whose size the Java panel and this one must agree on
constexpr float TOGGLE_PAD = 2.0f;            // pill wall to knob edge; knob r = H/2 - PAD = 6
constexpr float BOX_W      = 72.0f;           // stepper / spinner box (18 base units)
constexpr float COMBO_W    = 112.0f;          // slider / combo (28 base units): the widest control a
                                              // 234px content column fits beside a label worth reading
constexpr float SECTION_H  = ROW_H;           // a section header is a row, so it shares the pitch
constexpr float LABEL_LINE_H = 20.0f;         // the label line above a full-width text field
constexpr float RAIL_CELL    = 40.0f;         // a rail tab: 10 base units, > ROW_H because the rail
                                              // is the coarsest navigation in the strip
constexpr float RAIL_MARK_W  = 3.0f;          // active-tab edge, SidePanel's 3px marker
constexpr float RAIL_MARK_H  = 20.0f;         // ...as a bar shorter than the cell, so it reads as a
                                              // marker travelling between cells, not as a cell border
constexpr float FADE_H       = 12.0f;         // scroll-edge fade: three base units is enough to read
                                              // as content passing under chrome, short enough not to
                                              // dim a row you are trying to click
constexpr float STATUS_DOT_R = 3.0f;          // the footer's bridge light

// ---- radii --------------------------------------------------------------------------------------
// RADIUS_ROW is 0 ON PURPOSE, and it is a performance decision, not a taste one -- see rule 4 in the
// header. Full-width fills stay square so imgui_sw's fillQuad takes them; rounding is spent on the
// small controls, where the polygon is a few hundred pixels rather than a few thousand per row.
constexpr float RADIUS_ROW     = 0.0f;
constexpr float RADIUS_CONTROL = 3.0f;        // buttons, fields, the stepper box
constexpr float RADIUS_PILL    = TOGGLE_H * 0.5f;   // the toggle, fully round by definition

// ---- type ---------------------------------------------------------------------------------------
// One 13px bitmap face (see rule 5). LEAD_* are the vertical steps between lines of different rank;
// TRACK_* must stay INTEGRAL so glyph quads land on the texel grid under nearest sampling.
// Only the HAND-LAID lines need leading tokens: ImGui's own TextWrapped/Text lines lead themselves
// off ItemSpacing.y, which is ROW_GAP. These are the steps this file places by hand.
constexpr float LEAD_TIGHT = 14.0f;           // two lines of ONE record: hub author over description.
                                              // Dense information takes tighter leading -- one pixel
                                              // over the 13px face, so the pair reads as one block.
constexpr float LEAD_LOOSE = 20.0f;           // a title line: rank shown by the air around it
constexpr float TRACK_BODY = 0.0f;            // body text: no tracking
constexpr float TRACK_SMALL = 1.0f;           // small dim labels (section names, the wordmark): loosen
                                              // slightly, because at 13px dim text closes up. 1px, not
                                              // 0.5px -- a fractional advance smears a bitmap glyph.

// The two fixed chrome lines, sized off the type scale rather than picked: line 1 carries a small
// dim label (the wordmark, or the back control), line 2 carries the title and the plugin's switch,
// so it is one optical step taller to give the 16px pill room to sit on the baseline.
constexpr float HEAD_LINE_1 = LEAD_LOOSE;              // 20
constexpr float HEAD_LINE_2 = LEAD_LOOSE + SP_HAIR;    // 22

// ---- motion -------------------------------------------------------------------------------------
constexpr float MOTION_DAMPING     = 1.0f;    // critically damped. Nothing in this panel is reached
                                              // by a momentum gesture, so nothing may overshoot.
constexpr float MOTION_RESPONSE_NAV = 0.34f;  // whole-view push/pop: the largest surface that moves
constexpr float MOTION_RESPONSE_UI  = 0.28f;  // knob, indicator, chevron, section: small parts settle
                                              // sooner than big ones, or the panel feels syrupy
constexpr float MOTION_EPS         = 0.001f;  // below this the spring is parked, so it stops costing
constexpr float MOTION_DT_MAX      = 1.0f / 30.0f;   // a stalled frame must not launch the spring:
                                              // clamping dt makes a slow frame slow the motion down
                                              // instead of exploding the integrator (c*dt < 2 holds)
constexpr float NAV_TRAVEL     = 24.0f;       // how far a view slides: one row. Enough to read the
                                              // direction, short enough not to feel like a page turn.
constexpr float NAV_ANCHOR_MAX = 40.0f;       // cap on the pull toward the row that was clicked: the
                                              // transition must come FROM that row without a row 400px
                                              // down the list throwing the view off screen
constexpr float NAV_ALPHA_FLOOR = 0.25f;      // a transition never fades to nothing: an invisible but
                                              // still-clickable view is worse than a faint one

// ---- behaviour ----------------------------------------------------------------------------------
constexpr const char* ENABLE_KEY = "enabled"; // kewl.panel.PanelBridge.ENABLE_KEY: a plugin's on/off
                                              // switch is not a Setting -- setBool("enabled") routes
                                              // to Plugin.setEnabled, every other key to Setting.set
constexpr double CONFIRM_SECS = 2.0;          // how long a destructive control stays armed

constexpr float kPi = 3.14159265358979f;

// ---- the busy spinner ---------------------------------------------------------------------------
// The one piece of motion that is NOT a spring, because it has no target to settle on: it turns for
// as long as a hub refresh is in flight. It is still tokenised rather than left as literals -- a
// rotation rate is a duration, and the header's artwork exception covers glyph PATHS, not timing.
constexpr float SPIN_RATE     = 4.0f;         // rad/s: ~0.64 turns/s, fast enough to read as working
                                              // and slow enough not to strobe at the strip's ~30fps
constexpr float SPIN_SWEEP    = kPi * 1.2f;   // 216 deg of arc: leaves a gap big enough to see the
                                              // rotation, which a near-closed ring hides
constexpr int   SPIN_SEGMENTS = 12;           // one segment per 18 deg of sweep; the arc is a stroked
                                              // polyline on imgui_sw's slow path, so it buys nothing
                                              // to subdivide a 12px circle further
constexpr float SPIN_STROKE   = 1.6f;         // just over a hairline: 1.0f disappeared against ROW

// ---- thresholds ---------------------------------------------------------------------------------
constexpr float FADE_MIN      = 0.001f;       // below this fraction of hidden content the scroll-edge
                                              // fade is skipped: a 0-alpha gradient still walks the
                                              // rasteriser's barycentric path for FADE_H * 250 px
constexpr float DEBUG_NAME_W  = 0.6f;         // the debug list's name column, as a fraction of the
                                              // content width: the remaining 0.4 is the status text
                                              // that follows it on the same line

// -------------------------------------------------------------------------------------------------
// MATERIAL. Four planes, and the rule is that weight goes DOWN as you go up: the rail is the frame
// everything is cut out of, the body is the page, a row is a card on the page, and a pressed row is
// the lightest thing on screen because it has come up to meet the pointer. Never two light
// translucent surfaces stacked -- every plane here is opaque, and the only alpha in the file is the
// transition fade and the scroll-edge gradient.
// -------------------------------------------------------------------------------------------------
namespace theme {
// planes, dark to light
constexpr ImU32 STRUCT     = IM_COL32( 18,  18,  22, 255);  // the rail: the deepest, heaviest plane
constexpr ImU32 CANVAS     = IM_COL32( 28,  28,  32, 255);  // the body: the page rows sit on
constexpr ImU32 ROW        = IM_COL32( 38,  38,  44, 255);  // a row / button at rest
constexpr ImU32 ROW_HOVER  = IM_COL32( 48,  48,  56, 255);  // ...under the pointer
constexpr ImU32 ROW_PRESS  = IM_COL32( 60,  60,  70, 255);  // ...held down: lighter, never darker
constexpr ImU32 FIELD      = IM_COL32( 22,  22,  26, 255);  // a text field is a WELL, so it is cut
constexpr ImU32 FIELD_HOVER= IM_COL32( 26,  26,  31, 255);  // into the page rather than raised on it
constexpr ImU32 LINE       = IM_COL32( 62,  62,  72, 255);  // the only hairlines left: control outlines

// text, three steps. Rank is carried here and by weight/leading, because there is no size axis.
constexpr ImU32 TEXT_1     = IM_COL32(234, 234, 240, 255);  // titles, names, values: what you read
constexpr ImU32 TEXT_2     = IM_COL32(164, 164, 176, 255);  // descriptions, secondary labels
constexpr ImU32 TEXT_3     = IM_COL32(118, 118, 130, 255);  // meta, hints, disabled, the wordmark

// state
constexpr ImU32 ACCENT     = IM_COL32(220, 138,   0, 255);  // RuneLite's orange: SELECTION and only
constexpr ImU32 ACCENT_DIM = IM_COL32(146,  92,   0, 255);  // selection, held down
constexpr ImU32 ON         = IM_COL32(104, 206, 134, 255);  // a toggle that is on
constexpr ImU32 ON_HOVER   = IM_COL32(126, 222, 154, 255);
constexpr ImU32 ON_PRESS   = IM_COL32(146, 234, 172, 255);
constexpr ImU32 OFF        = IM_COL32( 74,  74,  86, 255);  // an off pill must still read as a
constexpr ImU32 OFF_HOVER  = IM_COL32( 92,  92, 106, 255);  // CONTROL, not as dead text
constexpr ImU32 OFF_PRESS  = IM_COL32(110, 110, 126, 255);
constexpr ImU32 KNOB       = IM_COL32( 24,  24,  28, 255);  // the knob is a hole punched in the pill
constexpr ImU32 WARN       = IM_COL32(255, 140, 120, 255);  // destructive, errors, bridge down
constexpr ImU32 SCRIM      = IM_COL32(  0,   0,   0, 102);  // behind a modal: 40% black, the one
                                                            // translucent plane in the file, and
                                                            // it sits over the whole strip rather
                                                            // than over another light surface

inline ImVec4 f(ImU32 c) {                  // style colours arrive as ImVec4
    return ImGui::ColorConvertU32ToFloat4(c);
}
}  // namespace theme

// -------------------------------------------------------------------------------------------------
// The model, exactly as bridge.hpp's format-2 model region carries it and main.cpp's Reader parses
// it. The launcher does not get to trust another process's memory, so main.cpp's reader bounds-checks
// everything; this file only ever sees a parsed copy it may mutate (which is what the optimistic
// echo below does -- hence the pointers: an echo written into a per-frame copy would be gone by the
// next frame, before Java's revision bump arrives to replace it with truth).
// -------------------------------------------------------------------------------------------------
struct Setting {
    std::int32_t kind = 0, valueInt = 0, min = 0, max = 0, enumIndex = 0, optionCount = 0, flags = 0;
    std::string key, label, desc, section, valueText;
    std::vector<std::string> options;
};

struct PluginModel {
    // hasConfig is the plugin record's FLAGS word (kewl_bridge::PLUGIN_FLAG_*), not a bool: bit0 is
    // the "has settings" it has always been -- which is why the developer bit fitted into a field
    // that was already there, with no format bump -- and bit1 is developer scaffolding. The name is
    // kept because main.cpp's reader fills it by that name; read it through the two accessors.
    std::int32_t enabled = 0, hasConfig = 0, hotkey = -1, pinned = 0;
    std::string name, desc, status;
    std::vector<Setting> settings;

    /** Something to configure: the gear, and a clickable name that opens the config view. */
    bool configurable() const { return (hasConfig & kewl_bridge::PLUGIN_FLAG_CONFIG) != 0; }

    /** A smoke test or a worked example -- shown under the list's Developer heading, sorted last. */
    bool developer() const { return (hasConfig & kewl_bridge::PLUGIN_FLAG_DEV) != 0; }
};

struct ProfileModel {
    std::string name, id;        // id is the stable identifier Java keys on; name is what is shown
};

struct HubEntry {
    std::string id, name, version, author, desc;
    std::int32_t flags = 0;               // kewl_bridge::HUB_FLAG_*
    std::int32_t installedPluginIdx = -1; // the plugin record this entry installed, or -1
};

// What main.cpp hands draw() each frame: the live model plus the bridge bookkeeping the chrome shows.
struct Model {
    std::vector<PluginModel>* plugins = nullptr;    // mutable: the optimistic echo writes here
    std::vector<ProfileModel>* profiles = nullptr;  // ditto
    std::vector<HubEntry>* hub = nullptr;           // ditto
    std::int32_t* activeProfile = nullptr;          // ditto: index into *profiles, -1 = none
    std::int32_t* hubState = nullptr;               // ditto: kewl_bridge::HubState
    std::string* hubError = nullptr;                // ditto: one line, when *hubState == HUB_ERROR
    std::int64_t modelRevision = -1;
    std::int64_t editSeq = 0;                       // hdr->editSeq: the bridge's own edit counter
    bool bridgeUp = false;
    std::string note;                               // one utf8 line of bridge state, for the footer
    std::string gameStatus;                         // utf8 of the launcher's status line, no-model case
};

// One edit, in the bridge contract's shape. main.cpp's writeEdit enqueues it and pokes the DLL.
using EditSink = std::function<void(std::int32_t kind, std::int32_t pluginIdx,
                                    const char* key, std::int64_t intVal, const char* text)>;

void applyStyle();      // once, after ImGui::CreateContext
void draw(const Model& m, const EditSink& edit);

// Offline probe, same spirit as main.cpp's KEWL_DUMP_FRAME: push a plugin's config view without a
// mouse to click the gear with. The fake-panel mode (KEWL_FAKE_PANEL + KEWL_FAKE_CONFIG) uses it to
// make the config view dumpable -- and a view that only a mouse could reach is a view that never
// gets verified. Out of range is a no-op, so a caller cannot wedge the UI with it.
void debugPushConfig(int pluginIdx);

// =================================================================================================
// Implementation. Header-only because launcher/main.cpp is the only TU that includes it -- the same
// choice imgui_sw.hpp makes -- and the UI state (active tab, nav stack, search text, armed confirms)
// lives in function-local statics, which are exactly as thread-unsafe and as sufficient as the
// one-thread PeekMessage loop that drives them.
// =================================================================================================

enum Tab { TAB_PLUGINS = 0, TAB_PROFILES = 1, TAB_HUB = 2, TAB_DEBUG = 3 };
constexpr int TAB_COUNT = 4;

inline const char* tabName(int t) {
    switch (t) {
        case TAB_PLUGINS:  return "Plugins";
        case TAB_PROFILES: return "Profiles";
        case TAB_HUB:      return "Plugin hub";
        default:           return "Debug";
    }
}

// -------------------------------------------------------------------------------------------------
// MOTION
//
// A spring, not a tween: two parameters (damping ratio and response), integrated against
// io.DeltaTime FROM THE CURRENT VALUE. That last part is the whole point -- an animation that is
// interrupted redirects from wherever it is on screen instead of snapping back and restarting.
// Reduced motion collapses every spring to an immediate transition; it is checked inside springTo so
// there is exactly one place that can get it wrong.
// -------------------------------------------------------------------------------------------------
inline bool& uiReducedMotion() { static bool v = false; return v; }

struct Spring {
    float value  = 0.0f;
    float vel    = 0.0f;
    bool  seeded = false;      // first sight of a spring parks it AT the target: a toggle that is
                               // already on must not animate on the frame its row scrolls into view
};

inline float springTo(Spring& s, float target, float response, float damping = MOTION_DAMPING) {
    if (!s.seeded) { s.seeded = true; s.value = target; s.vel = 0.0f; return s.value; }
    if (uiReducedMotion() || response <= 0.0f) { s.value = target; s.vel = 0.0f; return s.value; }
    float dt = ImGui::GetIO().DeltaTime;
    if (dt <= 0.0f) return s.value;
    if (dt > MOTION_DT_MAX) dt = MOTION_DT_MAX;
    const float w = 2.0f * kPi / response;      // undamped angular frequency
    const float k = w * w;                      // stiffness
    const float c = 2.0f * damping * w;         // damping coefficient
    // Semi-implicit Euler (velocity first, then position): stable for the (response, dt) pairs this
    // panel can produce -- at response 0.28 and the clamped dt, c*dt is ~1.5 and k*dt^2 is ~0.56,
    // both inside the stability bounds (c*dt < 2, k*dt^2 < 4).
    s.vel   += (k * (target - s.value) - c * s.vel) * dt;
    s.value += s.vel * dt;
    if (std::fabs(target - s.value) < MOTION_EPS && std::fabs(s.vel) < MOTION_EPS) {
        s.value = target;
        s.vel   = 0.0f;
    }
    return s.value;
}

// Springs keyed by ImGuiID, so a per-row control gets its own without the caller inventing a name:
// ImGui::GetID("##on") inside a PushID(rowIndex) scope is already unique and already stable across
// frames. Bounded by the number of animated widgets that have ever been drawn.
inline Spring& springById(ImGuiID id) { static std::map<ImGuiID, Spring> m; return m[id]; }

// -------------------------------------------------------------------------------------------------
// The transition alpha. ImGui's own widgets take ImGuiStyleVar_Alpha, but most of this panel is
// hand-drawn into the draw list, and a draw list does not know about style alpha -- so every custom
// colour goes through dcol() and the two halves fade together.
// -------------------------------------------------------------------------------------------------
inline float& viewAlpha() { static float a = 1.0f; return a; }

inline ImU32 dcol(ImU32 c) {
    const float a = viewAlpha();
    if (a >= 0.999f) return c;
    unsigned al = (unsigned)((float)((c >> IM_COL32_A_SHIFT) & 0xFF) * a);
    return (c & ~IM_COL32_A_MASK) | (al << IM_COL32_A_SHIFT);
}

// Whole pixels. Two reasons, both hard: the font atlas is sampled NEAREST (imgui_sw sampleTexture),
// so a glyph quad on a half-pixel drops or doubles a column of the stem; and imgui_sw's fast fill
// path (matchQuad) refuses a rect whose corners are not integral, which is why even the animated
// offsets below are snapped -- a transition must not cost 8ms of barycentric fill.
inline float  snap(float v)         { return std::floor(v + 0.5f); }
inline ImVec2 snap(const ImVec2& v) { return ImVec2(std::floor(v.x + 0.5f), std::floor(v.y + 0.5f)); }

// ---- the navigation model ------------------------------------------------------------------------
// RuneLite's multiplexing panel, reduced to what this sidebar needs: a route (the rail icon) plus a
// stack of pushed plugin-config pages over it. A tab switch resets to that route's root, back pops
// one page, and nothing here is a loose boolean -- every "am I in a config view" question is answered
// by looking at the stack.
//
// Every navigation also arms ONE transition, described by three pieces of state: which axis it runs
// on (drill-down is horizontal, changing route is vertical -- so the two kinds of navigation cannot
// be confused), which way along it, and which row on screen it is anchored to.
inline int& uiTab() { static int t = TAB_PLUGINS; return t; }
inline std::vector<int>& uiStack()          { static std::vector<int> s; return s; }
inline int uiTop()                          { auto& s = uiStack(); return s.empty() ? -1 : s.back(); }

inline Spring& navSpring()   { static Spring s; return s; }
inline int&    navAxis()     { static int a = 0; return a; }        // 0 = horizontal, 1 = vertical
inline int&    navDir()      { static int d = 1; return d; }        // +1 forward, -1 back
inline float&  navAnchorY()  { static float y = -1.0f; return y; }  // screen y of the row, -1 = none

// Restart the view transition. The content is REPLACED at the swap, so the position cannot carry
// over -- but the velocity can, and that is what makes a fast back-back-back read as one continuous
// motion instead of three separate starts.
inline void navRestart(int axis, int dir, float anchorY) {
    Spring& s = navSpring();
    s.seeded = true;
    s.value  = 0.0f;
    navAxis()    = axis;
    navDir()     = dir;
    navAnchorY() = anchorY;
}

inline void navPush(int pluginIdx, float anchorY = -1.0f) {
    uiStack().push_back(pluginIdx);
    navRestart(0, +1, anchorY);            // drill IN: horizontal, forward, out of the clicked row
}
inline void navPop() {
    if (uiStack().empty()) return;
    uiStack().pop_back();
    navRestart(0, -1, navAnchorY());       // drill OUT: the same axis, reversed, same anchor row --
}                                          // enter and exit along one path
inline void navReset() { uiStack().clear(); }

// Scroll memory for the plugin list: pushing a config view replaces the child's content, which would
// otherwise drop the user back to the top of the list on the way back out. Saved on push, restored on
// the first frame back at the root.
inline float& uiScrollSaved()   { static float v = 0; return v; }

// The sidebar's open/closed state. main.cpp reads the ini once at boot, writes it back when this
// changes, and lays the game child out against effectivePanelW() -- that is the whole contract.
inline bool& uiCollapsed() { static bool c = false; return c; }
inline int  effectivePanelW() { return uiCollapsed() ? RAIL_W : PANEL_W; }

// ---- keyboard focus hooks ------------------------------------------------------------------------
// The game child owns Windows keyboard focus (the DLL holds it on JagRenderView), so a text field in
// the strip has to ASK for it: typing while a field is live must reach ImGui, not the game. Every
// InputText in the panel goes through textInput(), which latches a one-shot request the launcher's
// frame loop consumes with SetFocus on the launcher window, reports whether any field is live (so
// the loop can keep re-asserting focus against the DLL's WM_ACTIVATE re-focus), and lets the loop
// hand the keyboard back to the game when the last field closes.
inline bool& kbRequestFlag()   { static bool v = false; return v; }
inline bool& kbActiveFlag()    { static bool v = false; return v; }   // reset at the top of draw()
inline bool& kbSurrenderFlag() { static bool v = false; return v; }   // launcher -> draw(): close the live field

inline bool uiKeyboardRequested() { bool v = kbRequestFlag(); kbRequestFlag() = false; return v; }
inline bool uiKeyboardActive()    { return kbActiveFlag(); }

// `flags` are ImGuiInputTextFlags_*; a secret setting passes ImGuiInputTextFlags_Password, which
// draws every character as '*' and disables copy -- the one place the strip ever puts such a value
// on screen, and it is masked there.
inline bool textInput(const char* id, char* buf, size_t bufsz, const char* hint = nullptr,
                      float width = 0.0f, ImGuiInputTextFlags flags = 0) {
    ImGui::PushItemWidth(width > 0.0f ? width : ImGui::GetContentRegionAvail().x);
    bool changed = hint ? ImGui::InputTextWithHint(id, hint, buf, bufsz, flags)
                        : ImGui::InputText(id, buf, bufsz, flags);
    ImGui::PopItemWidth();
    if (ImGui::IsItemActivated()) kbRequestFlag() = true;
    if (ImGui::IsItemActive())    kbActiveFlag() = true;
    return changed;
}

// The session's edit count, debug tab.
inline long& uiEditsSent() { static long n = 0; return n; }


// -------------------------------------------------------------------------------------------------
// Style. Only the parts ImGui itself draws (text fields, combos, sliders, popups, the scroll grab);
// everything with a shape of its own is hand-drawn below so it can respond on pointer-down.
// -------------------------------------------------------------------------------------------------
inline void applyStyle() {
    ImGuiStyle& s = ImGui::GetStyle();
    s.WindowPadding      = ImVec2(SP_2, SP_2);
    s.FramePadding       = ImVec2(SP_2 - SP_HAIR, SP_1);   // 6x4: a 13px line in a 21px frame
    s.ItemSpacing        = ImVec2(SP_2, ROW_GAP);          // y IS the row gap (see endRow)
    s.ItemInnerSpacing   = ImVec2(SP_1, SP_1);
    s.ScrollbarSize      = SP_2;
    s.GrabMinSize        = SP_2 + SP_1;
    s.WindowRounding     = 0.0f;
    s.ChildRounding      = 0.0f;
    s.PopupRounding      = RADIUS_CONTROL;
    s.FrameRounding      = RADIUS_CONTROL;
    s.GrabRounding       = RADIUS_CONTROL;
    s.ScrollbarRounding  = RADIUS_CONTROL;
    s.WindowBorderSize   = 0.0f;         // the material step carries the strip's edges, not a line
    s.ChildBorderSize    = 0.0f;
    s.PopupBorderSize    = 1.0f;         // a popup floats over everything, so it does get an outline
    s.WindowTitleAlign   = ImVec2(0, 0);
    s.ButtonTextAlign    = ImVec2(0.5f, 0.5f);

    ImVec4* c = s.Colors;
    c[ImGuiCol_Text]             = theme::f(theme::TEXT_1);
    c[ImGuiCol_TextDisabled]     = theme::f(theme::TEXT_3);
    c[ImGuiCol_WindowBg]         = theme::f(theme::CANVAS);
    c[ImGuiCol_ChildBg]          = theme::f(theme::CANVAS);
    c[ImGuiCol_PopupBg]          = theme::f(theme::ROW);
    c[ImGuiCol_Border]           = theme::f(theme::LINE);
    c[ImGuiCol_TitleBg]          = theme::f(theme::CANVAS);
    c[ImGuiCol_TitleBgActive]    = theme::f(theme::CANVAS);
    c[ImGuiCol_TitleBgCollapsed] = theme::f(theme::CANVAS);
    c[ImGuiCol_MenuBarBg]        = theme::f(theme::ROW);
    c[ImGuiCol_ScrollbarBg]      = theme::f(theme::CANVAS);
    c[ImGuiCol_ScrollbarGrab]        = theme::f(theme::ROW_HOVER);
    c[ImGuiCol_ScrollbarGrabHovered] = theme::f(theme::TEXT_3);
    c[ImGuiCol_ScrollbarGrabActive]  = theme::f(theme::TEXT_2);
    // A field is a well cut INTO the page, not a card raised on it -- that is what tells you it
    // takes typing rather than clicks.
    c[ImGuiCol_FrameBg]              = theme::f(theme::FIELD);
    c[ImGuiCol_FrameBgHovered]       = theme::f(theme::FIELD_HOVER);
    c[ImGuiCol_FrameBgActive]        = theme::f(theme::FIELD_HOVER);
    c[ImGuiCol_Button]               = theme::f(theme::ROW);
    c[ImGuiCol_ButtonHovered]        = theme::f(theme::ROW_HOVER);
    c[ImGuiCol_ButtonActive]         = theme::f(theme::ROW_PRESS);
    c[ImGuiCol_Header]               = theme::f(theme::ROW);
    c[ImGuiCol_HeaderHovered]        = theme::f(theme::ROW_HOVER);
    c[ImGuiCol_HeaderActive]         = theme::f(theme::ROW_PRESS);
    c[ImGuiCol_CheckMark]            = theme::f(theme::ACCENT);
    c[ImGuiCol_SliderGrab]           = theme::f(theme::ACCENT);
    c[ImGuiCol_SliderGrabActive]     = theme::f(theme::TEXT_1);
    c[ImGuiCol_Separator]            = theme::f(theme::LINE);
    c[ImGuiCol_SeparatorHovered]     = theme::f(theme::ACCENT);
    c[ImGuiCol_SeparatorActive]      = theme::f(theme::ACCENT);
    c[ImGuiCol_ResizeGrip]           = ImVec4(0, 0, 0, 0);
    c[ImGuiCol_NavCursor]            = theme::f(theme::ACCENT);
    c[ImGuiCol_ModalWindowDimBg]     = theme::f(theme::SCRIM);
}

// =================================================================================================
// TEXT
//
// One 13px bitmap face, so the axes available are weight, colour, leading and tracking (header rule
// 5). These three helpers are the only way text reaches the draw list in this file, which is what
// keeps every glyph on a whole pixel.
// =================================================================================================

// The bold is a double strike at exactly +1px. On a 13px pixel face that thickens the stem by one
// column and reads as a semibold; anything fancier (a +0.5 strike, an outline) fights the nearest
// sampler. This is the panel's ONLY weight axis, and it is spent on titles and names alone.
inline void textAt(ImDrawList* dl, const ImVec2& pos, ImU32 col, const char* s, bool bold = false) {
    ImVec2 p = snap(pos);
    dl->AddText(p, col, s);
    if (bold) dl->AddText(ImVec2(p.x + 1.0f, p.y), col, s);
}

// Width of a tracked run, so a caller can right-align or centre it.
inline float trackedWidth(const char* s, float track) {
    if (!s || !*s) return 0.0f;
    float w = ImGui::CalcTextSize(s).x;
    int glyphs = 0;
    for (const char* p = s; *p; ++p)
        if (((unsigned char)*p & 0xC0) != 0x80) ++glyphs;      // count codepoints, not bytes
    return w + track * (float)(glyphs > 0 ? glyphs - 1 : 0);
}

// Tracking, one codepoint at a time. UTF-8-aware because plugin and section names come from Java and
// carry non-ASCII; splitting a continuation byte would hand the atlas a broken sequence. `track` must
// be integral (see TRACK_SMALL) or the glyphs come off the texel grid.
inline void textTracked(ImDrawList* dl, const ImVec2& pos, ImU32 col, const char* s, float track,
                        bool bold = false) {
    if (!s || !*s) return;
    if (track == 0.0f) { textAt(dl, pos, col, s, bold); return; }
    ImVec2 p = snap(pos);
    for (const char* q = s; *q; ) {
        const char* next = q + 1;
        while (*next && ((unsigned char)*next & 0xC0) == 0x80) ++next;
        dl->AddText(p, col, q, next);
        if (bold) dl->AddText(ImVec2(p.x + 1.0f, p.y), col, q, next);
        p.x = snap(p.x + ImGui::CalcTextSize(q, next).x + track);
        q = next;
    }
}

// Widgets.clip: cut a string to maxW, appending "...". UTF-8-aware on purpose -- the model's strings
// come from Java and plugin names carry non-ASCII; cutting a continuation byte would hand the font
// atlas a broken sequence.
inline std::string clip(const char* s, float maxW) {
    if (!s || !*s) return {};
    if (ImGui::CalcTextSize(s).x <= maxW) return s;
    static const char* ell = "...";
    const float ellW = ImGui::CalcTextSize(ell).x;
    std::string out;
    const char* p = s;
    while (*p) {
        const char* next = p + 1;
        while (*next && ((unsigned char)*next & 0xC0) == 0x80) ++next;   // a continuation byte
        if (ImGui::CalcTextSize(s, next).x + ellW > maxW) break;
        p = next;
    }
    out.assign(s, p - s);
    out += ell;
    return out;
}

// The y that centres one line of text in a box of height h. Snapped, like every other text position.
inline float centreTextY(float top, float h) { return snap(top + (h - ImGui::GetTextLineHeight()) * 0.5f); }

// =================================================================================================
// CONTROLS
//
// All of them place themselves with SetCursorScreenPos + InvisibleButton, so they can sit at a
// computed position inside a hand-laid-out row, and all of them follow the same three-line contract:
//   clicked = IsItemClicked()   -- ImGui's CLICK is the PRESS edge, so this is response on down
//   down    = IsItemActive()    -- the pressed look, applied this frame, not on release
//   hovered = IsItemHovered()
// =================================================================================================

// The pill. The knob is sprung, so a toggle flipped twice quickly redirects mid-travel instead of
// jumping; the pill's fill changes on the press, before the model has even echoed.
inline bool toggleWidget(const char* id, bool on, const ImVec2& pos) {
    ImGui::SetCursorScreenPos(snap(pos));
    const ImGuiID key = ImGui::GetID(id);
    ImGui::InvisibleButton(id, ImVec2(TOGGLE_W, TOGGLE_H));
    const bool clicked = ImGui::IsItemClicked();
    const bool down    = ImGui::IsItemActive();
    const bool hovered = ImGui::IsItemHovered();

    const float p = springTo(springById(key), on ? 1.0f : 0.0f, MOTION_RESPONSE_UI);

    ImDrawList* dl = ImGui::GetWindowDrawList();
    const ImVec2 a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
    ImU32 fill = on ? theme::ON : theme::OFF;
    if (down)         fill = on ? theme::ON_PRESS : theme::OFF_PRESS;
    else if (hovered) fill = on ? theme::ON_HOVER : theme::OFF_HOVER;
    dl->AddRectFilled(a, b, dcol(fill), RADIUS_PILL);

    const float r  = TOGGLE_H * 0.5f - TOGGLE_PAD;
    const float x0 = a.x + TOGGLE_PAD + r, x1 = b.x - TOGGLE_PAD - r;
    // NOT snapped: the knob is a circle on the slow path anyway, and snapping its travel to whole
    // pixels would make a 12px slide read as four steps.
    dl->AddCircleFilled(ImVec2(x0 + (x1 - x0) * p, (a.y + b.y) * 0.5f), r, dcol(theme::KNOB));
    return clicked;
}

// The pin/favourite: a 5-point star, filled when pinned, inside an ICON_HIT box.
inline bool starWidget(const char* id, bool on, const ImVec2& pos) {
    ImGui::SetCursorScreenPos(snap(pos));
    ImGui::InvisibleButton(id, ImVec2(ICON_HIT, ICON_HIT));
    const bool clicked = ImGui::IsItemClicked();
    ImDrawList* dl = ImGui::GetWindowDrawList();
    ImVec2 c = ImGui::GetItemRectMin();
    c.x += ICON_HIT * 0.5f;
    c.y += ICON_HIT * 0.5f;
    ImU32 col = on ? theme::ACCENT : theme::TEXT_3;
    if (ImGui::IsItemActive())       col = theme::ACCENT_DIM;   // down: darkens under the finger
    else if (ImGui::IsItemHovered()) col = theme::ACCENT;
    ImVec2 pts[10];
    for (int k = 0; k < 10; ++k) {
        float ang = -kPi * 0.5f + (float)k * kPi * 0.2f;        // -90 deg, then every 36 deg
        float r   = (k % 2 == 0) ? ICON_GLYPH * 0.5f : ICON_GLYPH * 0.22f;
        pts[k] = ImVec2(c.x + r * std::cos(ang), c.y + r * std::sin(ang));
    }
    if (on) dl->AddConvexPolyFilled(pts, 10, dcol(col));
    else    dl->AddPolyline(pts, 10, dcol(col), ImDrawFlags_Closed, 1.0f);
    return clicked;
}

// The gear: a ring, a hub, four teeth. `visible` lets a row reserve the slot but keep the glyph off
// until the row is hovered -- the click target never moves, the resting list stays quiet.
inline bool gearWidget(const char* id, const ImVec2& pos, bool visible) {
    ImGui::SetCursorScreenPos(snap(pos));
    ImGui::InvisibleButton(id, ImVec2(ICON_HIT, ICON_HIT));
    const bool clicked = ImGui::IsItemClicked();
    if (!visible && !ImGui::IsItemHovered()) return clicked;

    ImDrawList* dl = ImGui::GetWindowDrawList();
    ImVec2 o = ImGui::GetItemRectMin();
    o.x += SP_HAIR; o.y += SP_HAIR;                             // 12px glyph centred in a 16px box
    ImU32 col = theme::TEXT_3;
    if (ImGui::IsItemActive())       col = theme::ACCENT_DIM;
    else if (ImGui::IsItemHovered()) col = theme::ACCENT;
    const ImU32 c = dcol(col);
    ImVec2 mid(o.x + 6, o.y + 6);
    dl->AddCircle(mid, 3.0f, c, 0, 1.0f);
    dl->AddCircleFilled(mid, 1.2f, c);
    dl->AddRectFilled(ImVec2(o.x + 5, o.y),      ImVec2(o.x + 7,  o.y + 3),  c);
    dl->AddRectFilled(ImVec2(o.x + 5, o.y + 9),  ImVec2(o.x + 7,  o.y + 12), c);
    dl->AddRectFilled(ImVec2(o.x,     o.y + 5),  ImVec2(o.x + 3,  o.y + 7),  c);
    dl->AddRectFilled(ImVec2(o.x + 9, o.y + 5),  ImVec2(o.x + 12, o.y + 7),  c);
    return clicked;
}

// WAYFINDING: the way out of a pushed view. It is not a bare 12px arrow in a corner -- it is a
// labelled control that NAMES ITS DESTINATION and sits on the first line of fixed chrome, so it can
// never scroll away from the view it is the exit for. Returns true on the press.
//
// There is no left inset: the arrow starts exactly on the content edge, so it sits directly above
// the title on the line below. An 8px pad would have read as "nearly aligned", which is worse than
// either alignment or a deliberate indent.
inline bool backButton(const char* id, const ImVec2& pos, const char* dest, float maxW) {
    const std::string label = clip(dest, maxW - (ICON_GLYPH + SP_1 + SP_2));
    const float w = ICON_GLYPH + SP_1 + ImGui::CalcTextSize(label.c_str()).x + SP_2;

    ImGui::SetCursorScreenPos(snap(pos));
    ImGui::InvisibleButton(id, ImVec2(w, HEAD_LINE_1));
    const bool clicked = ImGui::IsItemClicked();
    const bool down    = ImGui::IsItemActive();
    const bool hovered = ImGui::IsItemHovered();

    ImDrawList* dl = ImGui::GetWindowDrawList();
    const ImVec2 a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
    if (down || hovered)
        dl->AddRectFilled(a, b, dcol(down ? theme::ROW_PRESS : theme::ROW_HOVER), RADIUS_CONTROL);
    const ImU32 col = dcol(down ? theme::TEXT_1 : (hovered ? theme::TEXT_1 : theme::TEXT_2));

    // The arrow: a shaft and a head, vertically centred on the line.
    const float ax = a.x, ay = snap((a.y + b.y) * 0.5f);
    dl->AddLine(ImVec2(ax + ICON_GLYPH - 1, ay), ImVec2(ax, ay), col, 1.0f);
    dl->AddLine(ImVec2(ax, ay), ImVec2(ax + 4, ay - 4), col, 1.0f);
    dl->AddLine(ImVec2(ax, ay), ImVec2(ax + 4, ay + 4), col, 1.0f);
    textAt(dl, ImVec2(ax + ICON_GLYPH + SP_1, centreTextY(a.y, HEAD_LINE_1)), col, label.c_str());
    return clicked;
}

// Widgets.reset: a circular arrow, for "restore this setting's default". Draws only -- the caller
// owns the hotspot and the click, because the same glyph is used with two different edit kinds.
inline void resetGlyph(ImDrawList* dl, const ImVec2& pos, ImU32 col) {
    ImVec2 c(pos.x + 6, pos.y + 6);
    dl->PathArcTo(c, 4.0f, kPi * 0.25f, kPi * 1.6f, 12);
    dl->PathStroke(col, 0, 1.0f);
    // The head, at the arc's upper end, pointing the way the arrow "unwinds".
    dl->AddTriangleFilled(ImVec2(c.x - 3, c.y - 5), ImVec2(c.x + 2, c.y - 4), ImVec2(c.x - 1, c.y - 0.5f), col);
}

inline void pencilGlyph(ImDrawList* dl, const ImVec2& a, ImU32 col) {   // rename
    dl->AddLine(ImVec2(a.x + 2, a.y + 10), ImVec2(a.x + 9, a.y + 3), col, 1.4f);
    dl->AddTriangleFilled(ImVec2(a.x + 9, a.y + 3), ImVec2(a.x + 10.5f, a.y + 1.5f),
                          ImVec2(a.x + 8, a.y + 1.5f), col);
    dl->AddLine(ImVec2(a.x + 2, a.y + 10), ImVec2(a.x + 3.5f, a.y + 10), col, 1.4f);
}

inline void copyGlyph(ImDrawList* dl, const ImVec2& a, ImU32 col) {     // duplicate: two sheets
    dl->AddRect(ImVec2(a.x + 3.5f, a.y + 1.0f), ImVec2(a.x + 10.5f, a.y + 8.0f), col);   // behind
    dl->AddRect(ImVec2(a.x + 1.5f, a.y + 4.0f), ImVec2(a.x + 8.5f, a.y + 11.0f), col);   // in front
}

inline void crossGlyph(ImDrawList* dl, const ImVec2& a, ImU32 col) {    // delete
    dl->AddLine(ImVec2(a.x + 2.5f, a.y + 2.5f), ImVec2(a.x + 9.5f, a.y + 9.5f), col, 1.4f);
    dl->AddLine(ImVec2(a.x + 9.5f, a.y + 2.5f), ImVec2(a.x + 2.5f, a.y + 9.5f), col, 1.4f);
}

// A 12px glyph in an ICON_HIT box. Presses on down like everything else.
inline bool iconButton(const char* id, const ImVec2& pos,
                       void (*glyph)(ImDrawList*, const ImVec2&, ImU32)) {
    ImGui::SetCursorScreenPos(snap(pos));
    ImGui::InvisibleButton(id, ImVec2(ICON_HIT, ICON_HIT));
    const bool clicked = ImGui::IsItemClicked();
    ImU32 col = theme::TEXT_3;
    if (ImGui::IsItemActive())       col = theme::ACCENT_DIM;
    else if (ImGui::IsItemHovered()) col = theme::ACCENT;
    ImVec2 o = ImGui::GetItemRectMin();
    glyph(ImGui::GetWindowDrawList(), ImVec2(o.x + SP_HAIR, o.y + SP_HAIR), dcol(col));
    return clicked;
}

// The chevron for the rail's collapse control and for a section header. `turn` is 0..1: 0 points
// right (closed), 1 points down (open) -- so a section's chevron can ride its own spring.
inline void chevronGlyph(ImDrawList* dl, const ImVec2& centre, float turn, ImU32 col) {
    const float ang = turn * kPi * 0.5f;               // 0 -> right, 90 deg -> down
    const float ca = std::cos(ang), sa = std::sin(ang);
    const float r = 4.0f;
    // Three points of a right-pointing triangle, rotated about the centre.
    const ImVec2 raw[3] = { ImVec2(-r * 0.6f, -r), ImVec2(-r * 0.6f, r), ImVec2(r * 0.9f, 0) };
    ImVec2 p[3];
    for (int i = 0; i < 3; ++i)
        p[i] = ImVec2(centre.x + raw[i].x * ca - raw[i].y * sa,
                      centre.y + raw[i].x * sa + raw[i].y * ca);
    dl->AddTriangleFilled(p[0], p[1], p[2], col);
}

// A loading spinner: an arc that sweeps with the clock, for the hub's refresh-in-flight state.
inline void spinner(ImDrawList* dl, const ImVec2& c, float r, ImU32 col) {
    const float start = (float)ImGui::GetTime() * SPIN_RATE;
    dl->PathArcTo(c, r, start, start + SPIN_SWEEP, SPIN_SEGMENTS);
    dl->PathStroke(col, 0, SPIN_STROKE);
}

// The panel's button. ImGui::Button() is deliberately not used anywhere in this file: it shades and
// fires on RELEASE, and the whole response rule is that a control must change the instant it is
// pressed. Returns true on the press edge, and advances the cursor like any other item.
inline bool pressButton(const char* id, const char* label, const ImVec2& size,
                        bool danger = false, bool disabled = false) {
    ImGui::InvisibleButton(id, size);
    const bool clicked = !disabled && ImGui::IsItemClicked();
    const bool down    = !disabled && ImGui::IsItemActive();
    const bool hovered = !disabled && ImGui::IsItemHovered();

    ImDrawList* dl = ImGui::GetWindowDrawList();
    const ImVec2 a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
    ImU32 fill = down ? theme::ROW_PRESS : (hovered ? theme::ROW_HOVER : theme::ROW);
    dl->AddRectFilled(a, b, dcol(fill), RADIUS_CONTROL);
    const ImU32 col = dcol(disabled ? theme::TEXT_3 : (danger ? theme::WARN : theme::TEXT_1));
    const ImVec2 ts = ImGui::CalcTextSize(label);
    // Weight on a danger label: the one place bold carries meaning rather than rank.
    textAt(dl, ImVec2(a.x + (size.x - ts.x) * 0.5f, a.y + (size.y - ts.y) * 0.5f), col, label, danger);
    return clicked;
}

// ConfigView.stepper: the shared [ < value > ] box. Sets *dec / *inc when the matching end is
// pressed; draws its own surface so it reads as a control, not as text.
inline void stepperWidget(const char* id, const ImVec2& pos, const ImVec2& size,
                          const char* value, bool* dec, bool* inc) {
    *dec = *inc = false;
    ImDrawList* dl = ImGui::GetWindowDrawList();
    const ImVec2 p = snap(pos);
    dl->AddRectFilled(p, ImVec2(p.x + size.x, p.y + size.y), dcol(theme::ROW), RADIUS_CONTROL);

    const float endW = SP_4 + SP_1;            // 20px: an ICON_HIT plus the box wall
    char bid[64];

    std::snprintf(bid, sizeof bid, "%s_dec", id);
    ImGui::SetCursorScreenPos(p);
    ImGui::InvisibleButton(bid, ImVec2(endW, size.y));
    *dec = ImGui::IsItemClicked();
    ImU32 chev = ImGui::IsItemActive() ? theme::TEXT_1
                                       : (ImGui::IsItemHovered() ? theme::TEXT_1 : theme::TEXT_3);
    ImVec2 c = ImGui::GetItemRectMin();
    chevronGlyph(dl, ImVec2(c.x + endW * 0.5f, c.y + size.y * 0.5f), 2.0f, dcol(chev));  // turn 2 = left

    std::snprintf(bid, sizeof bid, "%s_inc", id);
    ImGui::SetCursorScreenPos(ImVec2(p.x + size.x - endW, p.y));
    ImGui::InvisibleButton(bid, ImVec2(endW, size.y));
    *inc = ImGui::IsItemClicked();
    chev = ImGui::IsItemActive() ? theme::TEXT_1
                                 : (ImGui::IsItemHovered() ? theme::TEXT_1 : theme::TEXT_3);
    c = ImGui::GetItemRectMin();
    chevronGlyph(dl, ImVec2(c.x + endW * 0.5f, c.y + size.y * 0.5f), 0.0f, dcol(chev));  // turn 0 = right

    // The value, centred in what is left of the box.
    const std::string shown = clip(value, size.x - endW * 2.0f - SP_1);
    const ImVec2 ts = ImGui::CalcTextSize(shown.c_str());
    textAt(dl, ImVec2(p.x + endW + (size.x - endW * 2.0f - ts.x) * 0.5f,
                      p.y + (size.y - ts.y) * 0.5f), dcol(theme::TEXT_1), shown.c_str());
}

// SidePanel.tabIcon, drawn with the draw list instead of Java2D. p is the glyph's top-left of a
// 16x16 cell.
inline void tabIcon(ImDrawList* dl, const ImVec2& pos, int tab, ImU32 col) {
    const ImVec2 p = snap(pos);
    switch (tab) {
        case TAB_PLUGINS:                       // a list: three lines of shrinking length
            dl->AddRectFilled(ImVec2(p.x, p.y + 1),  ImVec2(p.x + 16, p.y + 3),  col);
            dl->AddRectFilled(ImVec2(p.x, p.y + 7),  ImVec2(p.x + 12, p.y + 9),  col);
            dl->AddRectFilled(ImVec2(p.x, p.y + 13), ImVec2(p.x + 8,  p.y + 15), col);
            break;
        case TAB_PROFILES: {                    // a person: head and shoulders
            dl->AddCircle(ImVec2(p.x + 8, p.y + 3), 3.0f, col, 0, 1.0f);
            // Java draws a 14x10 ellipse arc; PathArcTo is circular only, so a r=7 dome stands in.
            dl->PathArcTo(ImVec2(p.x + 8, p.y + 13), 7.0f, kPi, 2.0f * kPi, 12);
            dl->PathStroke(col, 0, 1.0f);
            break;
        }
        case TAB_HUB: {                         // a plug: two prongs, a body, a cable
            dl->AddLine(ImVec2(p.x + 1,  p.y + 5),  ImVec2(p.x + 6,  p.y + 5),  col, 1.6f);
            dl->AddLine(ImVec2(p.x + 1,  p.y + 11), ImVec2(p.x + 6,  p.y + 11), col, 1.6f);
            dl->AddRectFilled(ImVec2(p.x + 5, p.y + 2), ImVec2(p.x + 11, p.y + 14), col, 2.0f);
            dl->AddLine(ImVec2(p.x + 11, p.y + 8), ImVec2(p.x + 16, p.y + 8), col, 1.6f);
            break;
        }
        case TAB_DEBUG: {                       // a pulse: flat, spike, flat
            const float ys[6] = { 8, 8, 2, 14, 8, 8 };
            for (int i = 0; i < 5; ++i)
                dl->AddLine(ImVec2(p.x + i * 3, p.y + ys[i]),
                            ImVec2(p.x + (i + 1) * 3, p.y + ys[i + 1]), col, 1.0f);
            break;
        }
        default: break;
    }
}

// The scroll-edge fade: where scrolling content meets FIXED CHROME, the content dissolves into the
// chrome's own colour instead of being cut by a 1px rule. `top`/`bot` are 0..1 -- how much content is
// hidden past that edge -- so the fade appears only when there is something under it to hide, which
// makes it a scroll indicator as well as a seam.
inline void edgeFade(ImDrawList* dl, const ImVec2& a, const ImVec2& b, float top, float bot) {
    const ImU32 solid = theme::CANVAS;
    const ImU32 clear = solid & ~IM_COL32_A_MASK;
    if (top > FADE_MIN) {
        ImU32 s = (solid & ~IM_COL32_A_MASK) | ((unsigned)(255.0f * top) << IM_COL32_A_SHIFT);
        dl->AddRectFilledMultiColor(ImVec2(a.x, a.y), ImVec2(b.x, a.y + FADE_H), s, s, clear, clear);
    }
    if (bot > FADE_MIN) {
        ImU32 s = (solid & ~IM_COL32_A_MASK) | ((unsigned)(255.0f * bot) << IM_COL32_A_SHIFT);
        dl->AddRectFilledMultiColor(ImVec2(a.x, b.y - FADE_H), ImVec2(b.x, b.y), clear, clear, s, s);
    }
}

// =================================================================================================
// SECTIONS
//
// A collapsible group whose body is REVEALED by a spring rather than snapped. The reveal is done
// with a child window of animated height: a child clips natively AND removes clipped items from hit
// testing, which a PushClipRect around raw items does not reliably do.
//
// The caller passes the body's exact height. That is not laziness -- every row in this panel has a
// height this file chose (ROW_H, or textRowH()), so the height is arithmetic, and computing it up
// front avoids the measure-then-draw dance that would otherwise flash the body at full size on the
// first frame a section is ever opened.
// =================================================================================================
struct SectionCtx { ImGuiID id; bool child; };
inline std::vector<SectionCtx>& sectionStack() { static std::vector<SectionCtx> s; return s; }

// Whether the header row sectionBegin() just drew is under the pointer. A caller cannot ask
// IsItemHovered() for it: by the time sectionBegin returns, the last item is the layout Dummy (or,
// in the animating case, the body child) -- which is exactly the bug the row tooltips were fixed for
// once already. Read it immediately after the sectionBegin call.
inline bool& lastSectionHovered() { static bool v = false; return v; }

// The ImGuiID sectionBegin() will use for `strId`, computed the same way it does: PushID seeds the
// hash, so ImGui::GetID("strId/##sec") is a DIFFERENT id and silently addresses nothing. A caller
// that wants to force a section open (the search below) has to go through here.
inline ImGuiID sectionId(const char* strId) {
    ImGui::PushID(strId);
    const ImGuiID id = ImGui::GetID("##sec");
    ImGui::PopID();
    return id;
}

// Draws the header row; returns true when there is a body to draw this frame (in which case the
// caller MUST call sectionEnd()).
inline bool sectionBegin(const char* strId, const char* label, bool defaultOpen, float contentH) {
    ImGui::PushID(strId);
    const ImGuiID id = ImGui::GetID("##sec");
    bool* open = ImGui::GetStateStorage()->GetBoolRef(id, defaultOpen);

    const float avail = ImGui::GetContentRegionAvail().x;
    const ImVec2 top  = ImGui::GetCursorScreenPos();

    ImGui::InvisibleButton("##hdr", ImVec2(avail, SECTION_H));
    if (ImGui::IsItemClicked()) *open = !*open;             // RESPONSE: on the press
    const bool down    = ImGui::IsItemActive();
    const bool hovered = ImGui::IsItemHovered();
    lastSectionHovered() = hovered;

    const float p = springTo(springById(id), *open ? 1.0f : 0.0f, MOTION_RESPONSE_UI);

    ImDrawList* dl = ImGui::GetWindowDrawList();
    if (down || hovered)
        dl->AddRectFilled(top, ImVec2(top.x + avail, top.y + SECTION_H),
                          dcol(down ? theme::ROW_PRESS : theme::ROW_HOVER), RADIUS_ROW);
    // The chevron turns with the same spring that opens the body, so the marker and the motion are
    // one gesture rather than two.
    chevronGlyph(dl, ImVec2(top.x + SP_2 + ICON_GLYPH * 0.5f, top.y + SECTION_H * 0.5f), p,
                 dcol(hovered || down ? theme::TEXT_1 : theme::TEXT_3));
    // A section name is small and dim, so it is tracked open a pixel and struck bold: at 13px that
    // is the difference between a heading and a row of body text, with no size axis to spend.
    textTracked(dl, ImVec2(top.x + SP_2 + ICON_GLYPH + SP_2, centreTextY(top.y, SECTION_H)),
                dcol(hovered || down ? theme::TEXT_1 : theme::TEXT_2), label, TRACK_SMALL, true);

    ImGui::SetCursorScreenPos(ImVec2(top.x, top.y + SECTION_H));
    ImGui::Dummy(ImVec2(avail, 0));

    const float shown = contentH * p;
    if (shown < 1.0f) { ImGui::PopID(); return false; }     // fully closed: no body, no hit targets

    if (p >= 0.999f) {
        // Settled open: draw inline, with no child at all. A child would clip a Combo's popup
        // measurement and cost a window per section for nothing.
        sectionStack().push_back({ id, false });
        return true;
    }
    sectionStack().push_back({ id, true });
    ImGui::BeginChild("##body", ImVec2(avail, snap(shown)), ImGuiChildFlags_None,
                      ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoScrollWithMouse |
                      ImGuiWindowFlags_NoBackground | ImGuiWindowFlags_NoSavedSettings);
    return true;
}

inline void sectionEnd() {
    if (sectionStack().empty()) return;
    const SectionCtx c = sectionStack().back();
    sectionStack().pop_back();
    if (c.child) ImGui::EndChild();
    ImGui::PopID();
}

// -------------------------------------------------------------------------------------------------
// Edits. Every mutation goes through commit() (value edits) or sendEdit() (the command-shaped kinds
// 4..14): echo the change into the local model copy FIRST so the strip reacts within this frame, then
// hand the record to the bridge. At 30fps across two processes that echo is the difference between a
// control that feels attached and one that feels like it is on a laggy socket; the next modelRevision
// bump (Java bumps it on every Setting.set, every status change and every enable) overwrites the echo
// with truth, so a value Java rejects reads wrong for at most one refresh.
// -------------------------------------------------------------------------------------------------
inline void sendEdit(const EditSink& edit, std::int32_t kind, std::int32_t pi,
                     const char* key, std::int64_t intVal, const char* text = nullptr) {
    edit(kind, pi, key, intVal, text);
    ++uiEditsSent();
}

inline void commit(const Model& m, const EditSink& edit, std::int32_t kind, std::int32_t pi,
                   const char* key, std::int64_t intVal, const char* text = nullptr) {
    if (pi < 0 || !m.plugins || pi >= (int)m.plugins->size()) return;
    PluginModel& pl = (*m.plugins)[pi];

    // The bridge's valueText field is SET_VALUETEXT bytes INCLUDING its terminator, so anything past
    // 63 bytes cannot round-trip: Java would store the long value, publish back the 63-byte prefix,
    // and the next edit from this field would write that prefix through Setting.set -- silently
    // shortening a working password to something that no longer logs in, with nothing on screen to
    // say so (review 2026-09-06). The text field below is sized so ImGui refuses the 64th byte; this
    // is the backstop for every other caller, and it cuts on a UTF-8 character boundary rather than
    // mid-sequence, the same rule client/bridge.hpp's truncUtf8 applies.
    std::string clipped;
    if (kind == kewl_bridge::EDIT_TEXT && text &&
        std::strlen(text) > kewl_bridge::model::SET_VALUETEXT - 1) {
        std::size_t cut = kewl_bridge::model::SET_VALUETEXT - 1;
        while (cut > 0 && ((unsigned char)text[cut] & 0xC0) == 0x80) --cut;
        clipped.assign(text, cut);
        text = clipped.c_str();
    }

    if (kind == kewl_bridge::EDIT_BOOL && std::strcmp(key, ENABLE_KEY) == 0) {
        pl.enabled = intVal ? 1 : 0;                     // Plugin.setEnabled, not a Setting
    } else {
        for (Setting& st : pl.settings) {
            if (st.key != key) continue;
            switch (kind) {
                case kewl_bridge::EDIT_BOOL:
                    st.valueInt = intVal ? 1 : 0;
                    st.valueText = intVal ? "on" : "off";     // PanelBridge.valueText's words
                    break;
                case kewl_bridge::EDIT_INT:
                    st.valueInt = (std::int32_t)intVal;
                    // The echo loses the @Units suffix Java bakes into valueText; the next publish
                    // puts it back. Showing "12" where "12 ms" was for one frame is honest.
                    st.valueText = std::to_string((long long)intVal);
                    break;
                case kewl_bridge::EDIT_ENUM: {
                    int idx = (std::int32_t)intVal;
                    st.valueInt = idx;
                    st.enumIndex = idx;
                    if (idx >= 0 && idx < (int)st.options.size()) st.valueText = st.options[idx];
                    break;
                }
                case kewl_bridge::EDIT_TEXT:
                    st.valueInt = 0;
                    st.valueText = text ? text : "";
                    break;
                default: break;
            }
            break;
        }
    }

    sendEdit(edit, kind, pi, key, intVal, text);
}

// PanelBridge keeps Java's declaration order and the launcher edits by that index, but RuneLite's
// list is alphabetical -- so sort the ROWS, keep the indices (PluginListView's Comparator.comparing).
// v2 adds pinning: pinned plugins surface first (PluginListPanel does the same), still alphabetical
// within the group, and still stable so a re-publish cannot shuffle equal rows.
//
// Developer scaffolding sorts BELOW everything, ahead of even the pin: those rows live under the
// list's own "Developer" heading (pluginsView), so a pinned test rig floating to the top would jump
// out of its section. Pinning still orders them among themselves.
inline std::vector<int> sortedOrder(const std::vector<PluginModel>& plugins) {
    std::vector<int> order(plugins.size());
    for (int i = 0; i < (int)order.size(); ++i) order[i] = i;
    std::stable_sort(order.begin(), order.end(), [&](int a, int b) {
        if (plugins[a].developer() != plugins[b].developer()) return plugins[b].developer();
        if (plugins[a].pinned != plugins[b].pinned) return plugins[a].pinned > plugins[b].pinned;
        std::string la = plugins[a].name, lb = plugins[b].name;
        for (char& c : la) c = (char)std::tolower((unsigned char)c);
        for (char& c : lb) c = (char)std::tolower((unsigned char)c);
        return la < lb;
    });
    return order;
}

// Case-insensitive substring, the way RuneLite's plugin search matches. Runs over name, description
// and the live status line -- tags are not part of the bridge model (see the header's gaps note).
inline bool matches(const std::string& hay, const std::string& needle) {
    if (needle.empty()) return true;
    auto it = std::search(hay.begin(), hay.end(), needle.begin(), needle.end(),
                          [](char a, char b) { return std::tolower((unsigned char)a) ==
                                                    std::tolower((unsigned char)b); });
    return it != hay.end();
}

// SidePanel.mouse's x < 0 rule, translated: never show a row tooltip while a popup (a Combo's option
// list) is open over the row that spawned it.
inline bool tooltipsAllowed() {
    return !ImGui::IsPopupOpen(nullptr, ImGuiPopupFlags_AnyPopupId);
}

// `hovered` is not optional: BeginTooltip() shows unconditionally, so a caller that forgets the
// hover test appends every row's text into one tooltip each frame -- the band that hid the rows
// below the pointer and read some other plugin's status (seen live, review 2026-09-06).
inline void rowTooltip(bool hovered, const std::string& desc, int hotkey) {
    if (!hovered) return;
    if (!tooltipsAllowed()) return;
    if (desc.empty() && hotkey < 0) return;
    if (ImGui::BeginTooltip()) {
        if (!desc.empty()) ImGui::TextUnformatted(desc.c_str());
        if (hotkey >= 0 && hotkey < 8) ImGui::TextDisabled("[F%d]", hotkey + 1);
        ImGui::EndTooltip();
    }
}

// End a hand-laid-out row: pad the current line out to `rowH` with a Dummy. Two reasons this is an
// item and not just a SetCursorScreenPos: imgui asserts when SetCursorScreenPos moves the cursor
// past the window's extents and no item ever grows them (ErrorCheckUsingSetCursorPosToExtendParent
// Boundaries -- it fired here the first time), and the scrollbar needs the rows' real heights to
// size the content. The newline + ItemSpacing.y the Dummy emits IS the row gap, so the row pitch is
// ROW_H + ROW_GAP.
inline void endRow(const ImVec2& top, float rowH, float avail) {
    ImGui::SetCursorScreenPos(ImVec2(top.x, top.y + rowH));
    ImGui::Dummy(ImVec2(avail, 0));
}

// =================================================================================================
// VIEWS
// =================================================================================================

// One plugin row (PluginListItem): pin star, name, gear, toggle -- one line, tooltip for the rest.
//
// The ROW ITSELF is submitted first, as one hit target, with SetNextItemAllowOverlap so the three
// controls layered over it still win their own pixels. That is what lets the whole row carry the
// press state and the click-to-configure, instead of three IsMouseHoveringRect tests that disagreed
// with the controls at their edges.
inline void pluginRow(const Model& m, const EditSink& edit, int idx) {
    PluginModel& pl = (*m.plugins)[idx];
    ImGui::PushID(idx);

    const ImVec2 top   = ImGui::GetCursorScreenPos();
    const float  avail = ImGui::GetContentRegionAvail().x;
    ImDrawList*  dl    = ImGui::GetWindowDrawList();
    const bool   clickable = pl.configurable();

    ImGui::SetNextItemAllowOverlap();
    ImGui::InvisibleButton("##row", ImVec2(avail, ROW_H));
    const bool rowHovered = ImGui::IsItemHovered();
    const bool rowDown    = ImGui::IsItemActive();      // pressed look, THIS frame
    const bool rowClicked = ImGui::IsItemClicked();     // ImGui's click IS the press edge

    // MATERIAL: the row is a card on the page, and pressing it brings it UP (lighter), not down.
    // Square and snapped so imgui_sw's fast fill path takes it -- see the header's rule 4.
    ImU32 surf = theme::ROW;
    if (clickable && rowDown)         surf = theme::ROW_PRESS;
    else if (clickable && rowHovered) surf = theme::ROW_HOVER;
    else if (rowHovered)              surf = theme::ROW_HOVER;
    dl->AddRectFilled(snap(top), snap(ImVec2(top.x + avail, top.y + ROW_H)), dcol(surf), RADIUS_ROW);

    // Leading edge: the pin. A favourite marker belongs at the start of the row, where the eye
    // enters it, and it is the one control here that changes the LIST rather than the plugin.
    if (starWidget("##pin", pl.pinned != 0,
                   ImVec2(top.x + SP_1, top.y + (ROW_H - ICON_HIT) * 0.5f))) {
        pl.pinned = pl.pinned ? 0 : 1;               // echo, then the SET_PIN edit carries the value
        sendEdit(edit, kewl_bridge::EDIT_SET_PIN, idx, "", pl.pinned);
    }

    // Trailing cluster: the toggle at the edge, the gear inboard of it. The gear's SLOT is always
    // reserved -- the name never reflows -- but its glyph only appears on the hovered row: clicking
    // the row already opens the config, so at rest the gear is noise (every element earns its place).
    const float toggleX = top.x + avail - SP_2 - TOGGLE_W;
    const float gearX   = toggleX - SP_2 - ICON_HIT;
    if (toggleWidget("##on", pl.enabled != 0, ImVec2(toggleX, top.y + (ROW_H - TOGGLE_H) * 0.5f)))
        commit(m, edit, kewl_bridge::EDIT_BOOL, idx, ENABLE_KEY, pl.enabled ? 0 : 1);
    if (clickable &&
        gearWidget("##gear", ImVec2(gearX, top.y + (ROW_H - ICON_HIT) * 0.5f), rowHovered))
        navPush(idx, top.y);

    // The name. TEXT_1 with no bold: in a list of forty rows, weight on every name is weight on
    // none -- rank here is the row's own material, and the accent is reserved for state.
    const float nameX = top.x + SP_1 + ICON_HIT + SP_2;
    const float nameW = gearX - SP_1 - nameX;
    const ImU32 nameCol = (clickable && (rowHovered || rowDown)) ? theme::ACCENT : theme::TEXT_1;
    textAt(dl, ImVec2(nameX, centreTextY(top.y, ROW_H)), dcol(nameCol),
           clip(pl.name.c_str(), nameW).c_str());

    if (clickable && rowClicked) navPush(idx, top.y);   // the anchor the config view comes out of

    endRow(top, ROW_H, avail);

    // Description and live status as a tooltip -- the row is one line, like RuneLite's. ONLY for the
    // row under the pointer: BeginTooltip() shows unconditionally, and calling it for every row
    // appended every row's text into one tooltip each frame -- a band that hid the rows below the
    // pointer and read some other plugin's status (seen live 2026-09-06). The rect test rather than
    // the item's own hover state, so the tooltip survives the pointer crossing onto the toggle.
    {
        std::string tip = pl.desc;
        if (pl.enabled && !pl.status.empty())
            tip = (tip.empty() ? "" : tip + "  |  ") + pl.status;
        rowTooltip(ImGui::IsMouseHoveringRect(top, ImVec2(top.x + avail, top.y + ROW_H)), tip, pl.hotkey);
    }

    ImGui::PopID();
}

inline void pluginsView(const Model& m, const EditSink& edit) {
    if (m.plugins->empty()) {
        // No model yet (or a jar without PanelBridge): the status line says where the launch stands,
        // which is more useful than an empty list that looks like a working client with no plugins.
        ImGui::TextWrapped("%s", m.gameStatus.c_str());
        return;
    }

    // The search field, before the list -- PluginListPanel's search box. Local filter only: it
    // touches neither the bridge nor Java, exactly as the spec's "no bridge traffic" asks.
    static char search[64] = "";
    textInput("##pluginsearch", search, sizeof search, "search plugins");
    ImGui::Dummy(ImVec2(0, SP_1));

    const std::string needle = search;
    std::vector<int> dev;                 // sortedOrder already put these last; hold them back
    int shown = 0;
    for (int idx : sortedOrder(*m.plugins)) {
        const PluginModel& pl = (*m.plugins)[idx];
        if (!matches(pl.name, needle) && !matches(pl.desc, needle) && !matches(pl.status, needle))
            continue;
        if (pl.developer()) { dev.push_back(idx); continue; }
        pluginRow(m, edit, idx);
        ++shown;
    }
    if (!shown && dev.empty()) ImGui::TextDisabled("(no matches)");

    // Developer scaffolding, under its own heading at the bottom: the shim smoke tests and the two
    // kewl box drawers the RuneLite ports replaced. The user asked for them out of the way, not
    // deleted -- so they are one press from visible and still switchable, and nothing about their
    // enabled state changes. Closed by default, because the point is a list of the six plugins
    // someone runs rather than ten rows, four of them scaffolding.
    if (dev.empty()) return;
    ImGui::Dummy(ImVec2(0, SP_2));
    // A search that matched a developer row forces the section open: a row the search found but the
    // list will not draw reads as the search being broken. Closing the search hands the collapse
    // state back to whatever the user last chose, because this only ever writes `true`.
    if (!needle.empty())
        *ImGui::GetStateStorage()->GetBoolRef(sectionId("devsec"), false) = true;
    const float devH = (float)dev.size() * (ROW_H + ROW_GAP);
    const bool devOpen = sectionBegin("devsec", "Developer", false, devH);
    if (lastSectionHovered() && tooltipsAllowed())
        ImGui::SetTooltip("smoke tests and worked examples, kept for development");
    if (devOpen) {
        for (int idx : dev) pluginRow(m, edit, idx);
        sectionEnd();
    }
}

// ---- text-row edit buffers -----------------------------------------------------------------------
// One buffer per text setting (keyed "pluginIdx/key"), because ImGui's InputText renders from its
// buffer every frame: a single shared scratch buffer would show row B's value in row A's field
// whenever both were drawn in one frame. Exactly one field can be live (active) at a time -- the
// one the user is typing into -- and only that one keeps its buffer across publishes.
inline std::string& textRowBuffer(int pi, const std::string& key) {
    static std::map<std::string, std::string> bufs;
    std::string& s = bufs[std::to_string(pi) + "/" + key];
    // Reserve the full field width once, at creation. Growing it in place later reallocates, and the
    // pre-growth bytes -- a prefix of whatever was typed, a password included -- are left in freed
    // heap where the deactivate-time wipe cannot reach them (review 2026-09-06).
    if (s.capacity() < kewl_bridge::model::SET_VALUETEXT) s.reserve(kewl_bridge::model::SET_VALUETEXT);
    return s;
}
inline std::string& textRowLiveId() { static std::string s; return s; }
inline bool textRowLive(int pi, const std::string& key) {
    return textRowLiveId() == std::to_string(pi) + "/" + key;
}
inline void textRowSetLive(int pi, const std::string& key, bool live) {
    std::string id = std::to_string(pi) + "/" + key;
    if (live) textRowLiveId() = id;
    else if (textRowLiveId() == id) textRowLiveId().clear();
}

// A text setting is two lines (label, then a full-width field), so its row is taller. Computed from
// the live frame height rather than hard-coded: FramePadding is a token, and a row that assumed a
// height the style no longer produces would clip the field.
inline float textRowH() { return LABEL_LINE_H + ImGui::GetFrameHeight() + SP_1; }
inline float settingRowH(const Setting& st) {
    return st.kind == kewl_bridge::SET_TEXT ? textRowH() : ROW_H;
}

// One setting row (ConfigView.item): label left, control right -- except text, whose value goes on a
// full-width line below the label, exactly where ConfigPanel's BorderLayout.SOUTH puts it. The label
// carries the per-setting reset glyph: Setting.reset is a Java-side call (kinds 4..14 grew one), and
// the row is where ConfigPanel would put the affordance.
inline void settingRow(const Model& m, const EditSink& edit, int pi, int si) {
    PluginModel& pl = (*m.plugins)[pi];
    Setting& st = pl.settings[si];
    ImGui::PushID(si);

    const float  avail = ImGui::GetContentRegionAvail().x;
    const ImVec2 top   = ImGui::GetCursorScreenPos();
    ImDrawList*  dl    = ImGui::GetWindowDrawList();
    const bool   isText = st.kind == kewl_bridge::SET_TEXT;
    const float  rowH  = settingRowH(st);

    const bool rowRegion = ImGui::IsMouseHoveringRect(top, ImVec2(top.x + avail, top.y + rowH));

    // The reset glyph owns the row's right edge; every control is laid out inboard of it.
    const float resetX = top.x + avail - ICON_HIT;
    const float ctrlR  = resetX - SP_1;                  // right edge available to the control

    // How much room the control takes, so the label knows what it may not overlap. One switch, so
    // the label clip and the control placement can never disagree (they used to, as two magic
    // numbers that had to be kept in step by hand).
    float ctrlW = 0.0f;
    switch (st.kind) {
        case kewl_bridge::SET_BOOL:    ctrlW = TOGGLE_W; break;
        case kewl_bridge::SET_INT:     ctrlW = (st.max > st.min && (long long)st.max - st.min <= 1000)
                                               ? COMBO_W : BOX_W; break;
        case kewl_bridge::SET_KEYBIND: ctrlW = BOX_W;    break;
        case kewl_bridge::SET_ENUM:    ctrlW = COMBO_W;  break;
        case kewl_bridge::SET_COLOR:   ctrlW = SP_4 * 2 + SP_HAIR; break;   // a 34px swatch
        default:                       ctrlW = isText ? 0.0f : COMBO_W; break;
    }
    const float labelW = (isText ? (avail - ICON_HIT - SP_2) : (ctrlR - ctrlW - SP_2 - top.x));

    // The label. TEXT_1 at rest, accent while the pointer is on the row -- the same feedback the
    // plugin list gives, so "this line is live" means one thing in both views.
    textAt(dl, ImVec2(top.x, centreTextY(top.y, isText ? LABEL_LINE_H : ROW_H)),
           dcol(rowRegion ? theme::ACCENT : theme::TEXT_1), clip(st.label.c_str(), labelW).c_str());

    // The reset control, at the row's right edge -- on both one-line and text rows, where the value
    // box below the label would otherwise swallow the press.
    if (iconButton("##reset", ImVec2(resetX, top.y + (isText ? (LABEL_LINE_H - ICON_HIT) * 0.5f
                                                             : (ROW_H - ICON_HIT) * 0.5f)),
                   resetGlyph))
        sendEdit(edit, kewl_bridge::EDIT_RESET_SETTING, pi, st.key.c_str(), 0);
        // No echo on purpose: the default value lives in Java, so the row keeps showing what it had
        // until the next publish delivers the reset value. Faking a default here would be a second
        // source of truth.

    // The description, as a tooltip on the row. Title then body, the body dimmer and wrapped: two
    // ranks in a tooltip that has room for both, unlike the row.
    if (rowRegion && !st.desc.empty() && tooltipsAllowed()) {
        if (ImGui::BeginTooltip()) {
            ImGui::TextUnformatted(st.label.c_str());
            ImGui::PushStyleColor(ImGuiCol_Text, theme::f(theme::TEXT_2));
            ImGui::TextWrapped("%s", st.desc.c_str());
            ImGui::PopStyleColor();
            ImGui::EndTooltip();
        }
    }

    const float ctrlX = ctrlR - ctrlW;
    switch (st.kind) {
        case kewl_bridge::SET_BOOL:
            if (toggleWidget("##v", st.valueInt != 0, ImVec2(ctrlX, top.y + (ROW_H - TOGGLE_H) * 0.5f)))
                commit(m, edit, kewl_bridge::EDIT_BOOL, pi, st.key.c_str(), st.valueInt ? 0 : 1);
            break;

        case kewl_bridge::SET_INT: {
            bool bounded = st.max > st.min && (long long)st.max - st.min <= 1000;
            if (bounded) {
                // ConfigView.slider: bounded ints get a track, value inside the grab.
                int v = st.valueInt;
                ImGui::SetCursorScreenPos(snap(ImVec2(ctrlX, top.y + (ROW_H - ImGui::GetFrameHeight()) * 0.5f)));
                ImGui::PushItemWidth(COMBO_W);
                if (ImGui::SliderInt("##v", &v, st.min, st.max, "%d", ImGuiSliderFlags_AlwaysClamp))
                    commit(m, edit, kewl_bridge::EDIT_INT, pi, st.key.c_str(), v);
                ImGui::PopItemWidth();
            } else {
                // ConfigView.spinner: no @Range, so +/- 1 per press through the shared stepper.
                bool dec = false, inc = false;
                stepperWidget("##v", ImVec2(ctrlX, top.y + (ROW_H - LABEL_LINE_H) * 0.5f),
                              ImVec2(BOX_W, LABEL_LINE_H), st.valueText.c_str(), &dec, &inc);
                if (dec) commit(m, edit, kewl_bridge::EDIT_INT, pi, st.key.c_str(), st.valueInt - 1);
                if (inc) commit(m, edit, kewl_bridge::EDIT_INT, pi, st.key.c_str(), st.valueInt + 1);
            }
            break;
        }

        case kewl_bridge::SET_KEYBIND: {
            // ConfigView.keybind: the shim stores a Keybind as an F-index (0 = not set), so the
            // control is a stepper over "not set", F1..F8, not a real key catcher.
            bool dec = false, inc = false;
            stepperWidget("##v", ImVec2(ctrlX, top.y + (ROW_H - LABEL_LINE_H) * 0.5f),
                          ImVec2(BOX_W, LABEL_LINE_H), st.valueText.c_str(), &dec, &inc);
            int f = st.valueInt;
            if (dec) commit(m, edit, kewl_bridge::EDIT_INT, pi, st.key.c_str(), f > 0 ? f - 1 : 0);
            if (inc) commit(m, edit, kewl_bridge::EDIT_INT, pi, st.key.c_str(), f < 8 ? f + 1 : 8);
            break;
        }

        case kewl_bridge::SET_ENUM: {
            // Combo of the option strings (the contract's EDIT_ENUM carries an option index, not a
            // raw value -- bridge.hpp's EditKind note). PanelBridge puts the current index in both
            // valueInt and enumIndex; trust valueInt, fall back when it is out of range.
            int cur = st.valueInt;
            if (cur < 0 || cur >= (int)st.options.size()) cur = st.enumIndex;
            std::vector<const char*> items;
            for (const std::string& o : st.options) items.push_back(o.c_str());
            ImGui::SetCursorScreenPos(snap(ImVec2(ctrlX, top.y + (ROW_H - ImGui::GetFrameHeight()) * 0.5f)));
            ImGui::PushItemWidth(COMBO_W);
            int sel = cur;
            if (!items.empty() && ImGui::Combo("##v", &sel, items.data(), (int)items.size()))
                commit(m, edit, kewl_bridge::EDIT_ENUM, pi, st.key.c_str(), sel);
            else if (items.empty())
                ImGui::TextDisabled("%s", st.valueText.c_str());
            ImGui::PopItemWidth();
            break;
        }

        case kewl_bridge::SET_COLOR: {
            // Read-only. The contract has no colour edit kind (kinds 0..3), and Java's own panel
            // cycles a palette with s.set(Color) -- a path the bridge cannot express yet. The
            // outline is one of the few hairlines left in the panel: a swatch that happens to match
            // the page would otherwise have no edge at all.
            ImVec2 a = snap(ImVec2(ctrlX, top.y + (ROW_H - LABEL_LINE_H) * 0.5f));
            ImVec2 b(a.x + ctrlW, a.y + LABEL_LINE_H);
            std::uint32_t rgb = (std::uint32_t)st.valueInt;   // 0xRRGGBB(A) from PanelBridge.valueInt
            dl->AddRectFilled(a, b, dcol(IM_COL32((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255)),
                              RADIUS_CONTROL);
            dl->AddRect(a, b, dcol(theme::LINE), RADIUS_CONTROL);
            break;
        }

        case kewl_bridge::SET_TEXT: {
            // An editable field on the full-width line below the label (ConfigPanel's SOUTH slot).
            // The keyboard handoff that once made this read-only is textInput()'s job now, the same
            // as the search and profile fields. The edit goes out when the field DEACTIVATES after a
            // change (Enter, Tab, or a click elsewhere), not per keystroke: the EditRecord ring is
            // 208 bytes a record and Java persists every Setting.set, so a keystroke-per-record
            // stream would be a lot of writes for one word. Until then the field owns the text and
            // a publish cannot overwrite what is being typed; once it closes, valueText is the truth
            // again on the next publish.
            //
            // Secret (password) settings: the same field in ImGui password mode, so the characters
            // are drawn as '*' and copy is disabled. valueText DOES carry the real value (the field
            // has to round-trip it), which is exactly why nothing else in this file -- tooltip,
            // debug tab, default branch -- may ever print a SET_TEXT's valueText.
            bool secret = (st.flags & kewl_bridge::FLAG_SECRET) != 0;
            std::string& buf = textRowBuffer(pi, st.key);
            bool live = textRowLive(pi, st.key);
            if (!live) buf = st.valueText;              // not being typed into: mirror the model
            // CEILING: valueText is a 63-byte bridge string (format 2), so a longer value cannot
            // round-trip -- it would come back truncated and the next edit would write the truncated
            // form into Java through Setting.set. Widening it is a FORMAT 3 change; until then the
            // field must REFUSE the 64th byte rather than accept 127 and lose the tail on the way
            // home (review 2026-09-06: a 70-character password typed here logged in, then published
            // back as 63 characters, and the next keystroke in this field overwrote the working one).
            // The buffer is therefore sized to the model field exactly: ImGui stops inserting when
            // the next character would not fit with its terminator, and it never splits a multi-byte
            // character to get there.
            buf.resize(kewl_bridge::model::SET_VALUETEXT, '\0');
            ImGui::SetCursorScreenPos(snap(ImVec2(top.x, top.y + LABEL_LINE_H)));
            textInput("##v", buf.data(), buf.size(), nullptr, avail,
                      secret ? ImGuiInputTextFlags_Password : 0);
            buf.resize(std::strlen(buf.c_str()));
            if (ImGui::IsItemActivated()) textRowSetLive(pi, st.key, true);
            if (ImGui::IsItemDeactivated()) {
                if (ImGui::IsItemDeactivatedAfterEdit())
                    commit(m, edit, kewl_bridge::EDIT_TEXT, pi, st.key.c_str(), 0, buf.c_str());
                textRowSetLive(pi, st.key, false);
                // A secret's edit buffer is a clear-text password living in a static map for the rest
                // of the session. It gets re-mirrored from valueText on the next frame (the format
                // carries the value in clear; that copy is not ours to drop), but nothing is served by
                // ALSO keeping the typed one here between edits (review 2026-09-06).
                if (secret) {
                    buf.resize(kewl_bridge::model::SET_VALUETEXT, '\0');   // also covers the bytes
                    SecureZeroMemory(buf.data(), buf.size());              // the strlen shrink left
                    buf.clear();
                }
            }
            break;
        }

        default: {
            // A kind from a newer DLL: show, don't invent. A secret must not leak through an unknown
            // kind either, so a newer DLL that flags one keeps it masked here.
            const char* shown = (st.flags & kewl_bridge::FLAG_SECRET) ? "****" : st.valueText.c_str();
            const std::string cut = clip(shown, ctrlW);
            textAt(dl, ImVec2(ctrlR - ImGui::CalcTextSize(cut.c_str()).x, centreTextY(top.y, ROW_H)),
                   dcol(theme::TEXT_3), cut.c_str());
            break;
        }
    }

    endRow(top, rowH, avail);
    ImGui::PopID();
}

// ConfigView.draw, minus its top bar: the plugin's name, its toggle and the way out live in the
// panel's FIXED chrome (see headerBar) so they cannot scroll away from the settings they belong to.
// What is left here is the settings themselves and the whole-plugin reset.
inline void configView(const Model& m, const EditSink& edit, int pi) {
    PluginModel& pl = (*m.plugins)[pi];
    const float avail = ImGui::GetContentRegionAvail().x;

    if (pl.settings.empty()) {
        ImGui::TextDisabled("no settings");
        return;
    }

    // Group by the section name the bridge carries, keeping first-appearance order. ConfigView
    // sorts by RlConfigMeta's section positions, which the model region does not carry -- so the
    // order here is Java's declaration order, and the section headers land where Java's metadata
    // first mentions them.
    std::vector<std::pair<std::string, std::vector<int>>> sections;
    std::vector<int> loose;
    for (int si = 0; si < (int)pl.settings.size(); ++si) {
        const std::string& sec = pl.settings[si].section;
        if (sec.empty()) { loose.push_back(si); continue; }
        auto found = std::find_if(sections.begin(), sections.end(),
                                  [&](const auto& s) { return s.first == sec; });
        if (found == sections.end()) sections.push_back({ sec, { si } });
        else found->second.push_back(si);
    }

    // The common path first: settings with no section are the plugin's plain options, so they come
    // before any heading, exactly as they do in Java's panel.
    for (int si : loose) settingRow(m, edit, pi, si);
    if (!loose.empty() && !sections.empty()) ImGui::Dummy(ImVec2(0, SP_1));

    for (const auto& sec : sections) {
        // Open by default: ConfigView opens every section whose RlConfigMeta does not say
        // closedByDefault, and that flag is not part of the bridge model -- open is the only honest
        // default. The state itself lives in ImGui's per-window storage, so whatever the user last
        // chose survives the next publish.
        float h = 0.0f;
        for (int si : sec.second) h += settingRowH(pl.settings[si]) + ROW_GAP;
        if (sectionBegin(sec.first.c_str(), sec.first.c_str(), true, h)) {
            for (int si : sec.second) settingRow(m, edit, pi, si);
            sectionEnd();
        }
    }

    // ConfigView's Reset row, over the whole plugin: every setting of this plugin back to its
    // default. Destructive, so it is armed: the first press turns the button into "confirm reset?"
    // for CONFIRM_SECS, and only a second press inside that window sends EDIT_RESET_PLUGIN. Both
    // presses land on the DOWN edge, so an armed control never fires from a stray release.
    ImGui::Dummy(ImVec2(0, SP_2));
    static double armed = -1.0;
    static int armedFor = -1;
    const double now = ImGui::GetTime();
    if (armedFor != pi) { armed = -1; armedFor = pi; }   // an arm belongs to one plugin's view
    bool isArmed = armed > 0 && now - armed < CONFIRM_SECS;
    if (!isArmed) armed = -1;

    if (pressButton("##reset", isArmed ? "confirm reset?" : "Reset",
                    ImVec2(avail, ROW_H), isArmed)) {
        if (isArmed) {
            sendEdit(edit, kewl_bridge::EDIT_RESET_PLUGIN, pi, "", 0);
            armed = -1;      // sent; the next publish replaces every row with Java's defaults
        } else {
            armed = now;
        }
    }
    if (ImGui::IsItemHovered() && tooltipsAllowed())
        ImGui::SetTooltip(isArmed ? "press again to reset every setting of %s"
                                  : "restore every setting of %s to its default", pl.name.c_str());
    ImGui::Dummy(ImVec2(0, SP_2));
}

// The debug tab's launcher-side half of kewl.ui.DebugView: what the bridge is actually carrying,
// plus the panel's own settings -- this is the only surface the strip owns, so the one preference it
// has lives here rather than inventing a fifth route for it.
inline void debugView(const Model& m) {
    const float avail = ImGui::GetContentRegionAvail().x;

    // ---- panel preferences -----------------------------------------------------------------------
    // REDUCED MOTION: a gentler equivalent, not none -- springTo() collapses every spring to an
    // immediate transition, so the panel still changes state, it just stops travelling to get there.
    // Not persisted: the ini belongs to launcher/main.cpp (see the header's gaps note).
    {
        const ImVec2 top = ImGui::GetCursorScreenPos();
        ImDrawList* dl = ImGui::GetWindowDrawList();
        textAt(dl, ImVec2(top.x, centreTextY(top.y, ROW_H)), dcol(theme::TEXT_1), "Reduced motion");
        if (toggleWidget("##redmo", uiReducedMotion(),
                         ImVec2(top.x + avail - TOGGLE_W, top.y + (ROW_H - TOGGLE_H) * 0.5f)))
            uiReducedMotion() = !uiReducedMotion();
        endRow(top, ROW_H, avail);
        if (ImGui::IsMouseHoveringRect(top, ImVec2(top.x + avail, top.y + ROW_H)) && tooltipsAllowed())
            ImGui::SetTooltip("transitions arrive immediately instead of travelling");
    }
    ImGui::Dummy(ImVec2(0, SP_2));

    // ---- bridge state ----------------------------------------------------------------------------
    ImGui::PushStyleColor(ImGuiCol_Text, theme::f(theme::TEXT_3));
    ImGui::TextUnformatted("bridge state, not game state");
    ImGui::PopStyleColor();
    ImGui::Text("bridge: %s", m.bridgeUp ? "up" : "down");
    ImGui::TextWrapped("%s", m.note.c_str());
    ImGui::Text("model rev: %lld", (long long)m.modelRevision);
    ImGui::Text("edits sent: %ld", uiEditsSent());
    ImGui::Text("edit seq:  %lld", (long long)m.editSeq);
    ImGui::Dummy(ImVec2(0, SP_1));
    if (!m.plugins) return;
    ImGui::Text("%d plugins", (int)m.plugins->size());
    for (const PluginModel& pl : *m.plugins) {
        ImGui::BulletText("%s%s", pl.pinned ? "* " : "", clip(pl.name.c_str(), avail * DEBUG_NAME_W).c_str());
        ImGui::SameLine();
        if (pl.enabled) ImGui::TextDisabled("%s", pl.status.c_str());
        else ImGui::TextDisabled("off");
    }
    if (m.profiles && m.activeProfile)
        ImGui::Text("%d profiles, active %d", (int)m.profiles->size(), *m.activeProfile);
    if (m.hub && m.hubState)
        ImGui::Text("hub: %d entries, state %d", (int)m.hub->size(), *m.hubState);
    ImGui::Dummy(ImVec2(0, SP_1));
    ImGui::PushStyleColor(ImGuiCol_Text, theme::f(theme::TEXT_3));
    ImGui::TextWrapped("not in the bridge contract yet: the memory-read lines (ready, you, npcs, "
                       "inventory, pathcheck).");
    ImGui::PopStyleColor();
}

// The profiles tab (ProfilePanel): the active profile marked, one row per profile with rename /
// duplicate / delete, and a create field at the top. Pressing a row switches profiles. Every action
// is an edit; the optimistic echo below only rearranges what the panel already knows, and the next
// publish replaces it with whatever Java actually did.
inline void profilesView(const Model& m, const EditSink& edit) {
    if (!m.profiles || !m.activeProfile) return;

    // Create: a field and a button, ProfilePanel's add row. Java names the id; the panel only
    // sends the display name the user typed.
    static char newName[64] = "";
    float avail = ImGui::GetContentRegionAvail().x;
    const float createW = ImGui::CalcTextSize("create").x + SP_3;
    textInput("##newprofile", newName, sizeof newName, "new profile name", avail - createW - SP_1);
    ImGui::SameLine(0.0f, SP_1);
    if (pressButton("##create", "create", ImVec2(createW, ImGui::GetFrameHeight())) && newName[0]) {
        // Echo: a profile Java has not confirmed yet, keyed by its name. The next publish replaces
        // the whole list, so a rejected create simply disappears a frame later.
        m.profiles->push_back({ newName, newName });
        sendEdit(edit, kewl_bridge::EDIT_PROFILE_CREATE, -1, "", 0, newName);
        newName[0] = 0;
    }
    ImGui::Dummy(ImVec2(0, SP_1));

    if (m.profiles->empty()) {
        ImGui::TextDisabled("(no profiles yet)");
        return;
    }

    static int renameIdx = -1;
    static char renameBuf[64] = "";
    static double armDelete = -1.0;
    static int armDeleteIdx = -1;
    const double now = ImGui::GetTime();
    if (armDeleteIdx >= 0 && now - armDelete >= CONFIRM_SECS) armDeleteIdx = -1;

    for (int i = 0; i < (int)m.profiles->size(); ++i) {
        ProfileModel& pf = (*m.profiles)[i];
        const bool active = i == *m.activeProfile;
        ImGui::PushID(i);

        const ImVec2 top = ImGui::GetCursorScreenPos();
        avail = ImGui::GetContentRegionAvail().x;
        ImDrawList* dl = ImGui::GetWindowDrawList();

        if (renameIdx == i) {
            // Rename in place: the row's name becomes the field, prefilled; committing sends
            // PROFILE_RENAME, and Esc/blur without a change just closes it.
            ImGui::SetCursorScreenPos(snap(ImVec2(top.x, top.y)));
            textInput("##rename", renameBuf, sizeof renameBuf, nullptr, avail);
            bool commitNow = ImGui::IsItemDeactivatedAfterEdit();
            if (commitNow && renameBuf[0]) {
                pf.name = renameBuf;                 // echo; Java's id never changes
                sendEdit(edit, kewl_bridge::EDIT_PROFILE_RENAME, -1, "", i, renameBuf);
            }
            renameIdx = -1;
            endRow(top, ROW_H, avail);
            ImGui::PopID();
            continue;
        }

        ImGui::SetNextItemAllowOverlap();
        ImGui::InvisibleButton("##row", ImVec2(avail, ROW_H));
        const bool rowHovered = ImGui::IsItemHovered();
        const bool rowDown    = ImGui::IsItemActive();
        if (ImGui::IsItemClicked() && !active) {
            *m.activeProfile = i;                    // echo, then the switch edit
            sendEdit(edit, kewl_bridge::EDIT_PROFILE_SWITCH, -1, "", i);
        }

        ImU32 surf = rowDown ? theme::ROW_PRESS : (rowHovered ? theme::ROW_HOVER : theme::ROW);
        dl->AddRectFilled(snap(top), snap(ImVec2(top.x + avail, top.y + ROW_H)), dcol(surf), RADIUS_ROW);
        // The active profile's marker: the same 3px accent edge the rail uses for the active tab, so
        // "this is the one you are on" looks the same everywhere in the strip.
        if (active)
            dl->AddRectFilled(snap(top), snap(ImVec2(top.x + RAIL_MARK_W, top.y + ROW_H)),
                              dcol(theme::ACCENT), RADIUS_ROW);

        // Row controls, right-aligned: rename, duplicate, delete.
        const float bx = top.x + avail - SP_1 - ICON_HIT;
        const float by = top.y + (ROW_H - ICON_HIT) * 0.5f;
        const bool deleting = armDeleteIdx == i;
        float nameW = bx - SP_2 - (top.x + SP_2);
        if (deleting) {
            // Armed delete: the cross becomes a warn-coloured "sure?" the row's own width away from
            // the name, and a second press inside the window sends PROFILE_DELETE.
            const float w = ImGui::CalcTextSize("sure?").x + SP_2;
            ImGui::SetCursorScreenPos(snap(ImVec2(top.x + avail - w, by)));
            if (pressButton("##sure", "sure?", ImVec2(w, ICON_HIT), true)) {
                m.profiles->erase(m.profiles->begin() + i);
                if (*m.activeProfile == i) *m.activeProfile = -1;   // truth arrives with the publish
                else if (*m.activeProfile > i) *m.activeProfile -= 1;
                sendEdit(edit, kewl_bridge::EDIT_PROFILE_DELETE, -1, "", i);
                armDeleteIdx = -1;
                // pf is dangling from the erase above, so this row ends here. endRow before PopID,
                // the same unwind order every other row in the file uses.
                endRow(top, ROW_H, avail);
                ImGui::PopID();
                continue;
            }
            nameW = avail - w - SP_2 - SP_2;
        } else {
            if (iconButton("##del", ImVec2(bx, by), crossGlyph)) {
                armDelete = now; armDeleteIdx = i;
            }
            if (iconButton("##dup", ImVec2(bx - ICON_HIT - SP_1, by), copyGlyph)) {
                // Echo a copy next to the original; Java picks the real name ("copy of ...").
                std::string dupName = pf.name + " copy";
                m.profiles->insert(m.profiles->begin() + i + 1, { dupName, dupName });
                if (*m.activeProfile > i) *m.activeProfile += 1;
                sendEdit(edit, kewl_bridge::EDIT_PROFILE_DUPLICATE, -1, "", i);
            }
            if (iconButton("##ren", ImVec2(bx - (ICON_HIT + SP_1) * 2.0f, by), pencilGlyph)) {
                renameIdx = i;
                std::snprintf(renameBuf, sizeof renameBuf, "%s", pf.name.c_str());
            }
            nameW = bx - (ICON_HIT + SP_1) * 2.0f - SP_2 - (top.x + SP_2);
        }

        // The name. Bold when it is the active profile: this is the one place a bold weight carries
        // STATE rather than rank, and it is doubled by the accent colour and the edge marker so it
        // never depends on weight alone.
        const ImU32 col = active ? theme::ACCENT : ((rowHovered || rowDown) ? theme::ACCENT : theme::TEXT_1);
        textAt(dl, ImVec2(top.x + SP_2, centreTextY(top.y, ROW_H)), dcol(col),
               clip(pf.name.c_str(), nameW).c_str(), active);

        endRow(top, ROW_H, avail);
        ImGui::PopID();
    }
}

// The hub tab (PluginHubPanel): the third-party warning, a search field, refresh with a spinner
// while the manifest loads, and one row per hub entry with install/remove/update. All of the
// network and filesystem work happens Java-side; this view only reflects hubState and the per-entry
// flags, and every button is just an edit.
inline void hubView(const Model& m, const EditSink& edit) {
    // WARNING feedback, one of the four kinds: third-party plugins are not reviewed by us. It sits
    // above everything and stays there -- a warning that scrolls away has not warned anybody.
    ImGui::PushStyleColor(ImGuiCol_Text, theme::f(theme::WARN));
    ImGui::TextWrapped("Third-party plugins. The hub is community content -- review a plugin "
                       "before installing it.");
    ImGui::PopStyleColor();
    ImGui::Dummy(ImVec2(0, SP_1));

    if (!m.hub || !m.hubState || !m.hubError) return;
    const bool loading = *m.hubState == kewl_bridge::HUB_LOADING;
    const bool errored = *m.hubState == kewl_bridge::HUB_ERROR;

    // Search + refresh on one line: the field takes what is left of the button, which needs the
    // width of the word "refresh" plus padding -- narrower and the label clips mid-glyph.
    static char search[64] = "";
    float avail = ImGui::GetContentRegionAvail().x;
    const float refreshW = ImGui::CalcTextSize("refresh").x + SP_3;
    const float fieldH   = ImGui::GetFrameHeight();
    textInput("##hubsearch", search, sizeof search, "search the hub", avail - refreshW - SP_1);
    ImGui::SameLine(0.0f, SP_1);
    if (loading) {
        // STATUS feedback: the spinner takes the button's place rather than the button vanishing --
        // a control that disappears while you are reaching for it is worse than one that waits.
        const ImVec2 p = ImGui::GetCursorScreenPos();
        pressButton("##refresh", "", ImVec2(refreshW, fieldH), false, true);
        spinner(ImGui::GetWindowDrawList(), ImVec2(p.x + refreshW * 0.5f, p.y + fieldH * 0.5f),
                SP_2 - SP_HAIR, dcol(theme::ACCENT));
    } else if (pressButton("##refresh", "refresh", ImVec2(refreshW, fieldH))) {
        sendEdit(edit, kewl_bridge::EDIT_HUB_REFRESH, -1, "", 0);
    }
    ImGui::Dummy(ImVec2(0, SP_1));

    if (errored) {
        // ERROR feedback: what failed, in the warn colour, in place of the list it would have filled.
        ImGui::PushStyleColor(ImGuiCol_Text, theme::f(theme::WARN));
        ImGui::TextWrapped("could not load the hub: %s", m.hubError->c_str());
        ImGui::PopStyleColor();
        return;
    }

    const std::string needle = search;
    int shown = 0;
    for (int i = 0; i < (int)m.hub->size(); ++i) {
        const HubEntry& he = (*m.hub)[i];
        if (!matches(he.name, needle) && !matches(he.desc, needle) && !matches(he.author, needle))
            continue;
        ++shown;

        ImGui::PushID(i);
        const bool busy      = (he.flags & kewl_bridge::HUB_FLAG_BUSY) != 0;
        const bool installed = (he.flags & kewl_bridge::HUB_FLAG_INSTALLED) != 0;
        const bool hasUpdate = (he.flags & kewl_bridge::HUB_FLAG_HAS_UPDATE) != 0;

        // The record, laid out by hand: name and version on the first line with the action at its
        // right, author and description under at LEAD_TIGHT -- a hub entry is one paragraph of dense
        // information, and dense information takes tighter leading.
        const ImVec2 top = ImGui::GetCursorScreenPos();
        ImDrawList* dl = ImGui::GetWindowDrawList();
        avail = ImGui::GetContentRegionAvail().x;

        const char* action = installed ? (hasUpdate ? "update" : "remove") : "install";
        const float bw = ImGui::CalcTextSize(action).x + SP_3;

        const std::string name = clip(he.name.c_str(), avail - bw - SP_2 - SP_4 * 2);
        textAt(dl, ImVec2(top.x, top.y), dcol(theme::TEXT_1), name.c_str(), true);   // the record's title
        const float nameW = ImGui::CalcTextSize(name.c_str()).x + 1.0f;              // +1: the bold strike
        const std::string vers = clip(("v" + he.version).c_str(), SP_4 * 3);
        textAt(dl, ImVec2(top.x + nameW + SP_1, top.y), dcol(theme::TEXT_3), vers.c_str());

        // Update is an install of the newer artifact, so it sends the same HUB_INSTALL edit; BUSY
        // disables it until Java reports the entry idle again.
        ImGui::SetCursorScreenPos(snap(ImVec2(top.x + avail - bw, top.y - SP_HAIR)));
        if (pressButton("##act", busy ? "..." : action, ImVec2(bw, ROW_H - SP_1), false, busy)) {
            if (installed && !hasUpdate)
                sendEdit(edit, kewl_bridge::EDIT_HUB_REMOVE, -1, "", 0, he.id.c_str());
            else
                sendEdit(edit, kewl_bridge::EDIT_HUB_INSTALL, -1, "", 0, he.id.c_str());
        }

        ImGui::SetCursorScreenPos(snap(ImVec2(top.x, top.y + LEAD_TIGHT + SP_HAIR)));
        ImGui::PushStyleColor(ImGuiCol_Text, theme::f(theme::TEXT_2));
        ImGui::TextWrapped("by %s", he.author.c_str());
        ImGui::PushStyleColor(ImGuiCol_Text, theme::f(theme::TEXT_3));
        ImGui::TextWrapped("%s", he.desc.c_str());
        ImGui::PopStyleColor(2);

        if (installed && he.installedPluginIdx >= 0 &&
            (!m.plugins || he.installedPluginIdx >= (int)m.plugins->size())) {
            // The entry says it installed a plugin the model no longer lists: say so rather than
            // draw a configure link into a config view that cannot open.
            ImGui::TextDisabled("installed plugin not in the model");
        }
        ImGui::Dummy(ImVec2(0, SP_2));
        ImGui::PopID();
    }
    if (!shown) ImGui::TextDisabled(loading ? "loading the hub..."
                                            : (needle.empty() ? "(the hub is empty)" : "(no matches)"));
}

// The debugPushConfig probe: the only way the config view gets exercised offline (see the header
// note on KEWL_FAKE_PANEL). Not reached by any input path -- clicks go through the gear and the
// plugin row, and both set exactly this.
inline void debugPushConfig(int pluginIdx) {
    uiTab() = TAB_PLUGINS;
    navPush(pluginIdx);          // no anchor: there was no row on screen to come out of
}

// -------------------------------------------------------------------------------------------------
// CHROME
//
// Two fixed lines at the top of the body, and they are the whole wayfinding story:
//   line 1 -- WHERE FROM. At a route root this is the wordmark (you are at the top). In a pushed
//             config view it is a back control that NAMES ITS DESTINATION, so the exit is a labelled
//             target on fixed chrome rather than a 12px arrow that scrolls away with the content.
//   line 2 -- WHERE AM I. The route's name, or the plugin's name, in the panel's one bold weight,
//             with the plugin's own switch beside it so the most common act in a config view (turn
//             this thing on) never needs a scroll.
// The height is the same in both states, so the list below does not jump when you push or pop.
// -------------------------------------------------------------------------------------------------
inline float headerH() { return SP_2 + HEAD_LINE_1 + HEAD_LINE_2 + SP_1; }

inline void headerBar(const Model& m, const EditSink& edit, int top) {
    const float avail = ImGui::GetContentRegionAvail().x;
    const ImVec2 p    = ImGui::GetCursorScreenPos();
    ImDrawList* dl    = ImGui::GetWindowDrawList();
    const float y1    = p.y + SP_2;
    const float y2    = y1 + HEAD_LINE_1;

    if (top >= 0 && m.plugins && top < (int)m.plugins->size()) {
        PluginModel& pl = (*m.plugins)[top];
        if (backButton("##back", ImVec2(p.x, y1), tabName(uiTab()), avail))
            navPop();
        // The plugin's switch, beside its name -- ConfigPanel duplicates it for the same reason.
        if (toggleWidget("##hon", pl.enabled != 0,
                         ImVec2(p.x + avail - TOGGLE_W, y2 + (HEAD_LINE_2 - TOGGLE_H) * 0.5f)))
            commit(m, edit, kewl_bridge::EDIT_BOOL, top, ENABLE_KEY, pl.enabled ? 0 : 1);
        textAt(dl, ImVec2(p.x, centreTextY(y2, HEAD_LINE_2)), dcol(theme::TEXT_1),
               clip(pl.name.c_str(), avail - TOGGLE_W - SP_2).c_str(), true);
    } else {
        // The wordmark: small, dim and tracked open. It is the quietest text in the panel on purpose
        // -- branding is not wayfinding, it is just the answer to "whose window is this".
        textTracked(dl, ImVec2(p.x, centreTextY(y1, HEAD_LINE_1)), dcol(theme::TEXT_3),
                    "KEWLKLIENT", TRACK_SMALL, true);
        textAt(dl, ImVec2(p.x, centreTextY(y2, HEAD_LINE_2)), dcol(theme::TEXT_1),
               clip(tabName(uiTab()), avail).c_str(), true);
    }

    ImGui::SetCursorScreenPos(ImVec2(p.x, p.y + headerH()));
    ImGui::Dummy(ImVec2(avail, 0));
}

// One fixed line at the foot: is this thing connected. STATUS feedback, always on screen, never in
// the way -- and the second edge that earns the list its bottom scroll fade.
inline float footerH() { return SP_1 + ImGui::GetTextLineHeight() + SP_1; }

inline void footerBar(const Model& m) {
    const float avail = ImGui::GetContentRegionAvail().x;
    const ImVec2 p    = ImGui::GetCursorScreenPos();
    ImDrawList* dl    = ImGui::GetWindowDrawList();
    const float h     = footerH();

    const ImU32 dot = m.bridgeUp ? theme::ON : theme::WARN;
    dl->AddCircleFilled(ImVec2(p.x + STATUS_DOT_R, p.y + h * 0.5f), STATUS_DOT_R, dcol(dot));
    const float tx = p.x + STATUS_DOT_R * 2.0f + SP_2;
    textAt(dl, ImVec2(tx, centreTextY(p.y, h)), dcol(m.bridgeUp ? theme::TEXT_3 : theme::WARN),
           clip(m.note.c_str(), avail - (tx - p.x)).c_str());

    // No trailing Dummy: nothing follows the footer, and the cursor is already at the last pixel the
    // window owns -- pushing it further is exactly the "SetCursorPos extends the parent" case the
    // rows go out of their way to avoid.
    if (ImGui::IsMouseHoveringRect(p, ImVec2(p.x + avail, p.y + h)) && tooltipsAllowed() &&
        !m.note.empty())
        ImGui::SetTooltip("%s", m.note.c_str());
}

// -------------------------------------------------------------------------------------------------
// The strip itself: one body window (250px, hidden when collapsed), one rail window (36px), the pair
// positioned at the window's right edge. Both are ordinary ImGui windows rather than children of
// main.cpp's full-window root -- that root carries ImGuiWindowFlags_NoMouseInputs (clicks over the
// game child must not be ImGui's business), and a NoMouseInputs parent is skipped by hit-testing
// while its children are not.
// -------------------------------------------------------------------------------------------------
inline void draw(const Model& m, const EditSink& edit) {
    ImGuiIO& io = ImGui::GetIO();
    const float dispW = io.DisplaySize.x, dispH = io.DisplaySize.y;
    const bool collapsed = uiCollapsed();
    if (dispW < (float)effectivePanelW() || dispH <= 0) return;    // not laid out yet / squeezed

    kbActiveFlag() = false;                        // rebuilt below from this frame's text fields
    if (kbSurrenderFlag()) {                       // the user clicked back into the game: close the
        kbSurrenderFlag() = false;                 // live field, whose deactivating click (landing
        ImGui::ClearActiveID();                    // on the game child) can never reach ImGui
    }

    // The view transition, resolved once for the frame. p is 0 at the swap and 1 when the new view
    // has arrived; everything below reads it and nothing else advances it.
    const float navP = springTo(navSpring(), 1.0f, MOTION_RESPONSE_NAV);
    viewAlpha() = NAV_ALPHA_FLOOR + (1.0f - NAV_ALPHA_FLOOR) * navP;

    const ImGuiWindowFlags stripFlags = ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
                                        ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoSavedSettings |
                                        ImGuiWindowFlags_NoBringToFrontOnFocus |
                                        ImGuiWindowFlags_NoScrollbar;

    // ---- body ---------------------------------------------------------------------------------
    // Hidden, not skipped, when collapsed: skipping it would leave the last frame's window record
    // around, and imgui's implicit fallback window would eat the clicks meant for the rail.
    if (!collapsed) {
        ImGui::SetNextWindowPos(ImVec2(dispW - (float)PANEL_W, 0.0f));
        ImGui::SetNextWindowSize(ImVec2((float)BODY_W, dispH));
        ImGui::Begin("##kewl.body", nullptr, stripFlags);

        // A pushed index that no longer names a plugin (the game died mid-view, a shrunken model)
        // un-pushes itself rather than drawing from a stale slot.
        if (uiTop() >= 0 && (!m.plugins || uiTop() >= (int)m.plugins->size())) navReset();
        const int curTop = uiTop();

        // Chrome does NOT travel. The fade applies to it so the two halves of the view change
        // together, but the back control and the title hold still: chrome that slides is chrome you
        // have to chase, and this is the one part of the panel whose job is to always be where it was.
        ImGui::PushStyleVar(ImGuiStyleVar_Alpha, viewAlpha());
        headerBar(m, edit, curTop);

        ImGui::BeginChild("##kewl.scroll", ImVec2(0, -footerH()), ImGuiChildFlags_None,
                          stripFlags | ImGuiWindowFlags_NoScrollbar);
        const ImVec2 clipA = ImGui::GetWindowPos();
        const ImVec2 clipB = ImVec2(clipA.x + ImGui::GetWindowWidth(), clipA.y + ImGui::GetWindowHeight());

        // Scroll memory: leaving the list for a config view saves the position; the first frame back
        // at the root restores it. One frame late is invisible at 30fps and keeps the logic out of
        // every click path that pops.
        static int lastTop = -2;
        if (lastTop < 0 && curTop >= 0) uiScrollSaved() = ImGui::GetScrollY();
        if (lastTop >= 0 && curTop < 0) ImGui::SetScrollY(uiScrollSaved());
        lastTop = curTop;

        // The travel. Horizontal for a drill in/out, vertical for a change of route -- two kinds of
        // navigation that must not look like each other. The anchor pulls the start of the motion
        // toward the row that was pressed, capped so a row far down the list does not throw the view
        // off screen; the whole offset is SNAPPED, which keeps every row fill on the rasteriser's
        // fast path even mid-transition.
        const ImVec2 base = ImGui::GetCursorScreenPos();
        const float rest = 1.0f - navP;
        float dx = 0.0f, dy = 0.0f;
        if (navAxis() == 0) {
            dx = (float)navDir() * rest * NAV_TRAVEL;
            if (navAnchorY() >= 0.0f) {
                float d = navAnchorY() - base.y;
                if (d < 0.0f) d = 0.0f;
                if (d > NAV_ANCHOR_MAX) d = NAV_ANCHOR_MAX;
                dy = rest * d;
            }
        } else {
            dy = (float)navDir() * rest * NAV_TRAVEL;
        }
        ImGui::SetCursorScreenPos(ImVec2(snap(base.x + dx), snap(base.y + dy)));

        if (curTop >= 0) {
            configView(m, edit, curTop);
        } else {
            switch (uiTab()) {
                case TAB_PLUGINS:  pluginsView(m, edit);  break;
                case TAB_PROFILES: profilesView(m, edit); break;
                case TAB_HUB:      hubView(m, edit);      break;
                default:           debugView(m);          break;
            }
        }

        // How much content is hidden past each edge, for the fades below. Read before EndChild,
        // because after it the child's scroll state is no longer the current window's.
        const float sy = ImGui::GetScrollY(), smax = ImGui::GetScrollMaxY();
        ImGui::EndChild();

        // MATERIAL: where the scrolling list meets fixed chrome, the content dissolves into the
        // page's own colour. No 1px rule at either seam -- a divider says "two regions", a fade says
        // "one region, continuing under this one", which is the truth.
        edgeFade(ImGui::GetWindowDrawList(), clipA, clipB,
                 sy / FADE_H > 1.0f ? 1.0f : sy / FADE_H,
                 (smax - sy) / FADE_H > 1.0f ? 1.0f : (smax - sy) / FADE_H);

        footerBar(m);
        ImGui::PopStyleVar();
        // No divider at BODY_W: the rail is a heavier plane than the body, and that step IS the seam.
        ImGui::End();
    }

    // ---- rail ---------------------------------------------------------------------------------
    // The heaviest plane in the strip, and the only one that never scrolls: it is the frame the rest
    // is cut out of, so it is the darkest thing on screen and it is drawn at full opacity even
    // mid-transition (the frame does not fade with its contents).
    ImGui::SetNextWindowPos(ImVec2(dispW - (float)RAIL_W, 0.0f));
    ImGui::SetNextWindowSize(ImVec2((float)RAIL_W, dispH));
    ImGui::PushStyleColor(ImGuiCol_WindowBg, theme::f(theme::STRUCT));
    ImGui::Begin("##kewl.rail", nullptr, stripFlags);
    const float savedAlpha = viewAlpha();
    viewAlpha() = 1.0f;

    const ImVec2 rp = ImGui::GetWindowPos();
    ImDrawList* rdl = ImGui::GetWindowDrawList();
    for (int i = 0; i < TAB_COUNT; ++i) {
        // The route stays marked while a config view is pushed over it: you are still in Plugins
        // when you are three levels into a plugin, and a rail that forgets that is a rail that
        // cannot answer "where am I".
        const bool active = uiTab() == i;
        ImGui::SetCursorScreenPos(ImVec2(rp.x, rp.y + (float)i * RAIL_CELL));
        char id[16];
        std::snprintf(id, sizeof id, "##nav%d", i);
        ImGui::InvisibleButton(id, ImVec2((float)RAIL_W, RAIL_CELL));
        if (ImGui::IsItemClicked()) {
            if (uiTab() != i) {
                // A route change travels VERTICALLY, in the direction the rail moved.
                const int dir = i > uiTab() ? +1 : -1;
                uiTab() = i;
                navReset();
                navRestart(1, dir, -1.0f);
            } else if (collapsed) {
                uiCollapsed() = false;                // the rail alone is the collapsed bar
            }
        }
        const bool down = ImGui::IsItemActive(), hovered = ImGui::IsItemHovered();
        const ImVec2 a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
        // The active cell is filled with the BODY's colour, so the route you are on reads as an
        // opening cut through the rail into the page beside it.
        if (active)       rdl->AddRectFilled(a, b, theme::CANVAS);
        else if (down)    rdl->AddRectFilled(a, b, theme::ROW_PRESS);
        else if (hovered) rdl->AddRectFilled(a, b, theme::ROW);
        tabIcon(rdl, ImVec2(a.x + (RAIL_W - ICON_HIT) * 0.5f, a.y + (RAIL_CELL - ICON_HIT) * 0.5f), i,
                (active || hovered || down) ? theme::TEXT_1 : theme::TEXT_3);
        if (tooltipsAllowed() && hovered) ImGui::SetTooltip("%s", tabName(i));
    }

    // The active-tab marker, on a spring: ONE bar that travels between cells rather than four that
    // blink on and off. Snapped, so a 3px bar stays a crisp 3px while it moves.
    {
        const float targetY = rp.y + (float)uiTab() * RAIL_CELL + (RAIL_CELL - RAIL_MARK_H) * 0.5f;
        const float y = springTo(springById(ImGui::GetID("##railmark")), targetY, MOTION_RESPONSE_UI);
        rdl->AddRectFilled(ImVec2(rp.x, snap(y)), ImVec2(rp.x + RAIL_MARK_W, snap(y + RAIL_MARK_H)),
                           theme::ACCENT);
    }

    // The collapse control, pinned to the bottom of the rail: the chevron points out when the body
    // can be hidden and in when it can be brought back, and it TURNS on a spring rather than
    // swapping glyphs. The state itself lives in uiCollapsed(); main.cpp notices the change, writes
    // the ini key and lays the game child out against the new width.
    ImGui::SetCursorScreenPos(ImVec2(rp.x, rp.y + dispH - RAIL_CELL));
    ImGui::InvisibleButton("##collapse", ImVec2((float)RAIL_W, RAIL_CELL));
    {
        const bool down = ImGui::IsItemActive(), hovered = ImGui::IsItemHovered();
        if (ImGui::IsItemClicked()) uiCollapsed() = !uiCollapsed();
        const ImVec2 a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
        if (down)         rdl->AddRectFilled(a, b, theme::ROW_PRESS);
        else if (hovered) rdl->AddRectFilled(a, b, theme::ROW);
        // turn 0 = pointing right (collapse, the body goes away to the right); turn 2 = pointing
        // left (expand). The spring runs between them, so the control rotates instead of flipping.
        const float turn = springTo(springById(ImGui::GetID("##collapseturn")),
                                    uiCollapsed() ? 2.0f : 0.0f, MOTION_RESPONSE_UI);
        chevronGlyph(rdl, ImVec2((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f), turn,
                     (hovered || down) ? theme::TEXT_1 : theme::TEXT_3);
        if (tooltipsAllowed() && hovered)
            ImGui::SetTooltip(uiCollapsed() ? "open the sidebar" : "collapse the sidebar");
    }

    viewAlpha() = savedAlpha;
    ImGui::End();
    ImGui::PopStyleColor();
    viewAlpha() = 1.0f;          // the frame's transition state does not leak into the next frame
}

}  // namespace kewl_panel
