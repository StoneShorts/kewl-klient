// model_geometry.hpp -- read-only RuntimeModel geometry for future model hulls.
//
// This layer owns the unsafe boundary: it validates the concrete vtable, bounds the
// vertex count, and copies all three mutable arrays before any projection work. It
// intentionally does not call the actor model bridge. That bridge returns managed
// pairs and its external ABI/ownership is not yet proven on Windows 240-7.
#pragma once

#include <windows.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
#include "offsets.hpp"
#include "runtime_layout.hpp"

namespace kk::model {

enum class EntityKind { Npc, Player };

struct CurrentModelRef {
    std::uintptr_t model = 0;   // second member of a managed pair, when acquired safely
    std::uintptr_t control = 0; // optional owner/control member; never passed as model
};

struct Vertex { std::int32_t x = 0, y = 0, z = 0; };
struct ScreenPoint { float x = 0.0f, y = 0.0f; };
struct EntityPose {
    float fineX = 0.0f, fineY = 0.0f, fineH = 0.0f;
    int orientation = 0;
};
struct WorldPoint { float x = 0.0f, h = 0.0f, y = 0.0f; };

struct Snapshot {
    std::uintptr_t sourceModel = 0;
    std::vector<Vertex> vertices;
    void clear() { sourceModel = 0; vertices.clear(); }
    bool empty() const { return vertices.empty(); }
};

enum class Failure {
    None, CapabilityUnavailable, NullModel, WrongVtable, BadVertexCount,
    BadVertexArrays, ReadFailed
};

inline bool readable(std::uintptr_t p, std::size_t n) {
    if (p < 0x10000) return false;
    MEMORY_BASIC_INFORMATION m{};
    if (!VirtualQuery(reinterpret_cast<void*>(p), &m, sizeof m)) return false;
    if (m.State != MEM_COMMIT || (m.Protect & (PAGE_NOACCESS | PAGE_GUARD))) return false;
    return p <= reinterpret_cast<std::uintptr_t>(m.BaseAddress) + m.RegionSize &&
           n <= reinterpret_cast<std::uintptr_t>(m.BaseAddress) + m.RegionSize - p;
}

template <class T>
inline bool read(std::uintptr_t p, T& out) {
    if (!readable(p, sizeof(T))) return false;
    out = *reinterpret_cast<const T*>(p);
    return true;
}

inline bool copy(std::uintptr_t p, void* out, std::size_t n) {
    if (!readable(p, n)) return false;
    std::memcpy(out, reinterpret_cast<const void*>(p), n);
    return true;
}

inline bool snapshot(std::uintptr_t moduleBase, std::uintptr_t model,
                     Snapshot& out, Failure* why = nullptr) {
    out.clear();
    auto fail = [&](Failure f) { if (why) *why = f; return false; };
    if (why) *why = Failure::None;
    if (!layout::runtimeModelGeometry()) return fail(Failure::CapabilityUnavailable);
    if (!model) return fail(Failure::NullModel);

    std::uintptr_t vtable = 0;
    if (!read(model, vtable) || vtable != moduleBase + off::RUNTIME_MODEL_VTABLE)
        return fail(Failure::WrongVtable);
    std::int32_t count = 0;
    if (!read(model + off::MODEL_VERTEX_COUNT, count) || count <= 0 || count > 262144)
        return fail(Failure::BadVertexCount);
    std::uintptr_t xs = 0, ys = 0, zs = 0;
    if (!read(model + off::MODEL_VERTEX_X, xs) ||
        !read(model + off::MODEL_VERTEX_Y, ys) ||
        !read(model + off::MODEL_VERTEX_Z, zs) || !xs || !ys || !zs)
        return fail(Failure::BadVertexArrays);

    const std::size_t bytes = static_cast<std::size_t>(count) * sizeof(std::int32_t);
    std::vector<std::int32_t> x(count), y(count), z(count);
    if (!copy(xs, x.data(), bytes) || !copy(ys, y.data(), bytes) || !copy(zs, z.data(), bytes))
        return fail(Failure::ReadFailed);
    out.sourceModel = model;
    out.vertices.resize(static_cast<std::size_t>(count));
    for (std::int32_t i = 0; i < count; ++i)
        out.vertices[static_cast<std::size_t>(i)] = { x[i], y[i], z[i] };
    return true;
}

// Acquisition is intentionally fail-closed until the managed-pair ABI is proven.
// Providers can later be installed from a render/getModel observation hook without
// changing snapshot(), transforms, projection, or the Java overlay filters.
// The transform is kept separate from acquisition and projection so the axis
// convention can be corrected from a render-path trace without touching either.
// These are the current OSRS-style hypotheses: yaw is 2048 units/revolution and
// model Y is upward while the client's fine-height datum is downward.
inline WorldPoint modelToWorld(const Vertex& v, const EntityPose& p) {
    constexpr float tau = 6.28318530717958647692f;
    const float a = static_cast<float>(p.orientation & 2047) * tau / 2048.0f;
    const float s = std::sin(a), c = std::cos(a);
    const float x = static_cast<float>(v.x), z = static_cast<float>(v.z);
    return { p.fineX + x * c + z * s,
             p.fineH - static_cast<float>(v.y),
             p.fineY + z * c - x * s };
}

inline float cross(const ScreenPoint& o, const ScreenPoint& a, const ScreenPoint& b) {
    return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
}

inline std::vector<ScreenPoint> convexHull(std::vector<ScreenPoint> points) {
    std::sort(points.begin(), points.end(), [](const ScreenPoint& a, const ScreenPoint& b) {
        return a.x != b.x ? a.x < b.x : a.y < b.y;
    });
    points.erase(std::unique(points.begin(), points.end(), [](const ScreenPoint& a, const ScreenPoint& b) {
        return std::fabs(a.x - b.x) < 0.01f && std::fabs(a.y - b.y) < 0.01f;
    }), points.end());
    if (points.size() <= 3) return points;
    std::vector<ScreenPoint> h(points.size() * 2);
    std::size_t k = 0;
    for (const auto& p : points) {
        while (k >= 2 && cross(h[k - 2], h[k - 1], p) <= 0.0f) --k;
        h[k++] = p;
    }
    for (std::size_t i = points.size(), t = k + 1; i > 0; --i) {
        const auto& p = points[i - 1];
        while (k >= t && cross(h[k - 2], h[k - 1], p) <= 0.0f) --k;
        h[k++] = p;
    }
    if (k > 1) --k;
    h.resize(k);
    return h;
}

template <class Project>
inline bool projectSnapshot(const Snapshot& snapshot, const EntityPose& pose,
                            Project&& project, std::vector<ScreenPoint>& out) {
    out.clear();
    out.reserve(snapshot.vertices.size());
    for (const auto& v : snapshot.vertices) {
        const WorldPoint w = modelToWorld(v, pose);
        ScreenPoint s;
        if (project(w.x, w.h, w.y, s) && std::isfinite(s.x) && std::isfinite(s.y))
            out.push_back(s);
    }
    out = convexHull(std::move(out));
    return out.size() >= 3;
}

inline bool currentForEntity(EntityKind, std::uintptr_t, CurrentModelRef& out) {
    out = {};
    return false;
}

} // namespace kk::model
