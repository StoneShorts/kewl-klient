// runtime_layout.hpp -- semantic status for the runtime layout.
//
// offsets.hpp remains the compatibility surface consumed by the older game readers.
// This layer records which values were actually recovered from the current client,
// which came from a per-build cache, and which are only legacy fallbacks.
#pragma once
#include <cstdint>

namespace kk::layout {

enum class State : std::uint8_t {
    Unavailable,
    LegacyFallback,
    Resolved,
    Validated,
};

enum class Field : std::uint8_t {
    ClientObject,
    VarpArray,
    GameState,
    Registry,
    EntityScene,
    EntityDefinition,
    EntityPlane,
    PlayerName,
    Projection,
    Camera,
    Count,
};

struct Record {
    State state = State::Unavailable;
    const char* source = "";
};

inline Record records[static_cast<unsigned>(Field::Count)]{};

inline void reset() {
    for (auto& r : records) r = {};
}

inline void set(Field f, State state, const char* source) {
    records[static_cast<unsigned>(f)] = { state, source ? source : "" };
}

inline const Record& get(Field f) {
    return records[static_cast<unsigned>(f)];
}

inline bool available(Field f) {
    const State s = get(f).state;
    return s == State::Resolved || s == State::Validated;
}

// Capabilities are deliberately semantic rather than one boolean for the whole
// layout. A failed entity-name recipe must not disable projection, and a failed
// projection recipe must not disable inventory access.
inline bool entities() {
    return available(Field::Registry) && available(Field::EntityScene);
}

inline bool npcNames() {
    return entities() && available(Field::EntityDefinition);
}

inline bool playerNames() {
    return entities() && available(Field::PlayerName);
}

inline bool projection() {
    return available(Field::Projection) && available(Field::Camera);
}

inline bool gameState() {
    return available(Field::ClientObject) && available(Field::GameState);
}

} // namespace kk::layout
