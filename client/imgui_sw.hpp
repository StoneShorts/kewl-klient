// imgui_sw.hpp -- header-only Dear ImGui software rasterizer for KewlKlient.
//
// WHY A SOFTWARE RASTERIZER AT ALL
//   The game process owns the one OpenGL context NXT is allowed to have, and a second GL context in
//   the same process is exactly the kind of thing we cannot verify offline and do not want to debug
//   live under Wine. The launcher (and the DLL's panel, in direct-inject mode) renders its ImGui UI
//   with the CPU straight into the DIB section backing its window -- no GPU, no extra context, no
//   d3dcompiler. The verified spike that proved this out (/tmp/imgui-swtest: rast.cpp/rast.hpp,
//   frame.png, ~1-3ms/frame for a realistic panel WITH the fast paths below) is the direct ancestor
//   of this file, so anything that looks odd here was probably measured there first.
//
// OUTPUT FORMAT CONTRACT
//   `dst` is a top-down 32bpp DIB as handed out by CreateDIBSection with a negative (top-down)
//   height: each unsigned int is 0xAARRBBGG -- in memory byte order B,G,R,A. That is byte-identical
//   to IM_COL32 little-endian, so vertex colours need no swizzle, and blendPixel writes back the
//   exact layout GDI blits. The alpha byte of the buffer is pinned to FF: the DIB IS the window
//   content, there is nothing underneath it to composite with.
//
// BACKEND CONTRACT (imgui 1.92+, RendererHasTextures)
//   Since 1.92 the backend no longer bakes the font atlas up front; ImGui hands us ImTextureData
//   entries with Status == WantCreate/WantUpdates/WantDestroy and expects SetTexID() + Status OK.
//   On the CPU there is nothing to upload: tex->GetPixels() is host memory we can sample directly,
//   so "texture management" reduces to remembering where each atlas lives.

#pragma once
#include "imgui.h"

#include <cmath>
#include <cstdint>

namespace kewl_sw {

// Render one frame of ImGui draw data into `dst` (bw x bh ARGB pixels, top-down).
// Everything is clipped to the buffer; a panel hanging off the window edge is fine.
inline void renderDrawData(ImDrawData* dd, unsigned* dst, int bw, int bh);

// ---------------------------------------------------------------------------
// Texture registry, keyed by ImTextureData::UniqueID -- which is exactly what
// we hand out as the ImTextureID, so a draw-cmd's texture lookup is an index.
// ---------------------------------------------------------------------------
struct SwTexture {
    int w = 0, h = 0;
    const unsigned char* pixels = nullptr;  // NOT owned: points into ImGui's atlas allocation.
    bool alpha8 = false;                    // A8 font atlas: shape is in alpha, RGB from the tint
};

namespace detail {

// Function-local statics keep this header-only without ODR pain (both the DLL and the launcher
// include it) and dodge static-initialisation-order questions entirely.
inline ImVector<SwTexture>& textures() {
    static ImVector<SwTexture> reg;
    return reg;
}

inline void processTextures(ImDrawData* dd) {
    // dd->Textures is this frame's view of the set; PlatformIO().Textures is the full list. Either
    // works -- we take the frame's when present, matching the spike and the 1.92 backend docs.
    ImVector<ImTextureData*>* list = dd->Textures ? dd->Textures : &ImGui::GetPlatformIO().Textures;
    ImVector<SwTexture>& reg = textures();
    for (int i = 0; i < list->size(); i++) {
        ImTextureData* tex = (*list)[i];
        // WantCreate and WantUpdates are the same operation for us: the atlas pixels already live
        // in host memory (ImGui rasterises them on this same CPU), so all we do is refresh our
        // pointer/size cache and tell ImGui the backend is done with it.
        if (tex->Status == ImTextureStatus_WantCreate || tex->Status == ImTextureStatus_WantUpdates) {
            tex->SetTexID((ImTextureID)(intptr_t)tex->UniqueID);
            if (tex->UniqueID >= reg.size()) reg.resize(tex->UniqueID + 1);
            SwTexture& t = reg[tex->UniqueID];
            t.w = tex->Width;
            t.h = tex->Height;
            t.alpha8 = (tex->Format == ImTextureFormat_Alpha8);
            t.pixels = (const unsigned char*)tex->GetPixels();
            tex->SetStatus(ImTextureStatus_OK);  // SetStatus, never a raw write: imgui.h is explicit
                                                 // that Status must go through it
        } else if (tex->Status == ImTextureStatus_WantDestroy) {
            // Only ever arrives for a texture NOT referenced by this frame's draw lists (ImGui
            // counts UnusedFrames before queuing the destroy) -- invalidating TexID here is safe.
            // SetStatus(Destroyed) rather than a raw write: if the backend destroys a texture ImGui
            // had not queued for destruction, SetStatus re-arms WantCreate so it comes back next
            // frame (documented behaviour in ImTextureData::SetStatus).
            tex->SetTexID(ImTextureID_Invalid);
            tex->SetStatus(ImTextureStatus_Destroyed);
            if (tex->UniqueID >= 0 && tex->UniqueID < reg.size()) {
                SwTexture& t = reg[tex->UniqueID];
                t.pixels = nullptr;  // stale IDs then degrade to "untextured", not to a bad pointer
                t.w = t.h = 0;
            }
        }
    }
}

inline unsigned sampleTexture(const SwTexture& t, float u, float v) {
    int x = (int)(u * t.w);
    int y = (int)(v * t.h);
    x = x < 0 ? 0 : (x >= t.w ? t.w - 1 : x);
    y = y < 0 ? 0 : (y >= t.h ? t.h - 1 : y);
    if (t.alpha8) {
        // A8 atlas: the glyph shape lives only in alpha; RGB is white here and gets multiplied by
        // the vertex tint further down.
        unsigned a = t.pixels[y * t.w + x];
        return (a << 24) | 0x00FFFFFF;
    }
    return ((const unsigned*)(t.pixels + (size_t)y * t.w * 4))[x];
}

// (a * b + 127) / 255 for 8-bit scales. Every texel and alpha multiply in this file goes through
// here: the divide is by 255 because that is the top of the 0..255 range, and the bias rounds to
// nearest -- see the long note at the textured path in rasterTri for the LSB bug >>8 caused.
inline unsigned mulRound255(unsigned a, unsigned b) {
    return (a * b + 127) / 255;
}

// src-over blend of a straight-alpha ARGB pixel. The |0xFF000000 is deliberate: see the header note
// -- the destination DIB is opaque window content, so we never accumulate destination alpha.
//
// The mix is (src*a + dst*(255-a) + 127) / 255, NOT the terser dst + (src-dst)*a/255 the spike used:
// with unsigned channel arithmetic that form either wraps on src<dst or truncates towards -inf, and
// the probe caught it landing one LSB darker in a channel depending on blend direction -- a red
// field half-covered by blue came out (126,0,127) instead of (127,0,128). The symmetric form is
// direction-independent and the +127 bias rounds to nearest.
inline void blendPixel(unsigned* px, unsigned rgb, unsigned a) {
    if (a >= 255) { *px = rgb | 0xFF000000u; return; }
    if (a == 0) return;
    unsigned d = *px;
    unsigned ia = 255 - a;
    unsigned dr = d & 0xFF, dg = (d >> 8) & 0xFF, db = (d >> 16) & 0xFF;
    unsigned sr = rgb & 0xFF, sg = (rgb >> 8) & 0xFF, sb = (rgb >> 16) & 0xFF;
    // Blue sits in the top byte of the colour bits (B,G,R,A order, byte-identical to IM_COL32),
    // hence <<16/<<8; each channel is src-weighted + dst-weighted, each term rounded separately --
    // double rounding costs at most 1 LSB and keeps every multiply inside 8-bit*8-bit+bias.
    *px = 0xFF000000u
        | ((mulRound255(sb, a) + mulRound255(db, ia)) << 16)
        | ((mulRound255(sg, a) + mulRound255(dg, ia)) << 8)
        | (mulRound255(sr, a) + mulRound255(dr, ia));
}

// One indexed triangle: affine barycentric UVs, per-vertex colour, D3D-style top-left fill rule.
//
// FILL RULE, because it is the difference between a clean frame and hairline artifacts: a pixel
// centre landing exactly on a shared edge is otherwise painted by BOTH triangles (double blend,
// visible seam) or by NEITHER (1px crack -- 45-degree diagonals hit this constantly, since
// px+0.5 == py+0.5 makes an edge weight exactly zero on integer geometry). The top-left rule keeps
// exactly one owner per boundary pixel: walking each edge with the triangle's winding, an edge is
// "top-left" when it steps in -y, or horizontally in +x for positive winding (the mirror of that
// for negative winding), and only that edge's zero-weight pixels are included.
struct Tri {
    float x[3], y[3], u[3], v[3];
    unsigned col[3];
};

inline void rasterTri(unsigned* dst, int bw, int bh, const Tri& t, const SwTexture* tex,
                      int cx0, int cy0, int cx1, int cy1) {
    float area = (t.x[1] - t.x[0]) * (t.y[2] - t.y[0]) - (t.x[2] - t.x[0]) * (t.y[1] - t.y[0]);
    if (std::fabs(area) < 1e-9f) return;  // degenerate: ImGui emits zero-area slivers for empty widgets
    bool neg = area < 0;
    const float inv = 1.0f / area;

    // Bounding box of the triangle, pre-clipped to the scissor rect. Pixel centres are at +0.5, so
    // floor/ceil is the conservative bound and the per-pixel edge test settles the exact pixels.
    float fx0 = t.x[0] < t.x[1] ? (t.x[0] < t.x[2] ? t.x[0] : t.x[2]) : (t.x[1] < t.x[2] ? t.x[1] : t.x[2]);
    float fx1 = t.x[0] > t.x[1] ? (t.x[0] > t.x[2] ? t.x[0] : t.x[2]) : (t.x[1] > t.x[2] ? t.x[1] : t.x[2]);
    float fy0 = t.y[0] < t.y[1] ? (t.y[0] < t.y[2] ? t.y[0] : t.y[2]) : (t.y[1] < t.y[2] ? t.y[1] : t.y[2]);
    float fy1 = t.y[0] > t.y[1] ? (t.y[0] > t.y[2] ? t.y[0] : t.y[2]) : (t.y[1] > t.y[2] ? t.y[1] : t.y[2]);
    int minx = (int)std::floor(fx0); if (minx < cx0) minx = cx0;
    int maxx = (int)std::ceil(fx1);  if (maxx > cx1 - 1) maxx = cx1 - 1;
    int miny = (int)std::floor(fy0); if (miny < cy0) miny = cy0;
    int maxy = (int)std::ceil(fy1);  if (maxy > cy1 - 1) maxy = cy1 - 1;
    if (minx > maxx || miny > maxy) return;

    // Zero-weight inclusion per edge (see fill-rule note). Edge i runs vertex i -> vertex (i+1)%3,
    // which is the same edge each w term below covers.
    bool inc0, inc1, inc2;
    if (!neg) {
        inc0 = t.y[1] < t.y[0] || (t.y[1] == t.y[0] && t.x[1] > t.x[0]);
        inc1 = t.y[2] < t.y[1] || (t.y[2] == t.y[1] && t.x[2] > t.x[1]);
        inc2 = t.y[0] < t.y[2] || (t.y[0] == t.y[2] && t.x[0] > t.x[2]);
    } else {
        inc0 = t.y[1] > t.y[0] || (t.y[1] == t.y[0] && t.x[1] < t.x[0]);
        inc1 = t.y[2] > t.y[1] || (t.y[2] == t.y[1] && t.x[2] < t.x[1]);
        inc2 = t.y[0] > t.y[2] || (t.y[0] == t.y[2] && t.x[0] < t.x[2]);
    }

    // Edge weights are linear in (px, py), so evaluate at the box corner once per row and step
    // across the row by the x-gradient. This was worth ~2x over per-pixel evaluation in the spike's
    // non-fast-path load (text, which is what is left after the rect fast path takes the rest).
    const float e0 = -(t.y[1] - t.y[0]);  // d(w0)/dpx
    const float e1 = -(t.y[2] - t.y[1]);
    const float e2 = -(t.y[0] - t.y[2]);

    // Per-channel vertex colours, hoisted out of the loop.
    const float c0r = (float)(t.col[0] & 0xFF), c0g = (float)((t.col[0] >> 8) & 0xFF), c0b = (float)((t.col[0] >> 16) & 0xFF), c0a = (float)((t.col[0] >> 24) & 0xFF);
    const float c1r = (float)(t.col[1] & 0xFF), c1g = (float)((t.col[1] >> 8) & 0xFF), c1b = (float)((t.col[1] >> 16) & 0xFF), c1a = (float)((t.col[1] >> 24) & 0xFF);
    const float c2r = (float)(t.col[2] & 0xFF), c2g = (float)((t.col[2] >> 8) & 0xFF), c2b = (float)((t.col[2] >> 16) & 0xFF), c2a = (float)((t.col[2] >> 24) & 0xFF);

    for (int py = miny; py <= maxy; py++) {
        float fy = py + 0.5f;
        // w_i at (minx, fy); w_i is the signed sub-area opposite vertex i, so for positive winding
        // the interior is w_i > 0 for all i (mirrored for negative).
        float w0 = (t.x[1] - t.x[0]) * (fy - t.y[0]) - ((float)minx + 0.5f - t.x[0]) * (t.y[1] - t.y[0]);
        float w1 = (t.x[2] - t.x[1]) * (fy - t.y[1]) - ((float)minx + 0.5f - t.x[1]) * (t.y[2] - t.y[1]);
        float w2 = (t.x[0] - t.x[2]) * (fy - t.y[2]) - ((float)minx + 0.5f - t.x[2]) * (t.y[0] - t.y[2]);
        unsigned* row = dst + (size_t)py * bw;
        for (int px = minx; px <= maxx; px++, w0 += e0, w1 += e1, w2 += e2) {
            bool inside;
            if (!neg)
                inside = (w0 > 0 || (w0 == 0 && inc0)) && (w1 > 0 || (w1 == 0 && inc1)) && (w2 > 0 || (w2 == 0 && inc2));
            else
                inside = (w0 < 0 || (w0 == 0 && inc0)) && (w1 < 0 || (w1 == 0 && inc1)) && (w2 < 0 || (w2 == 0 && inc2));
            if (!inside) continue;
            // w_i is the sub-area opposite vertex i, so /area gives the barycentric weight of the
            // OTHER end -- the (w1,w2,w0) -> (v0,v1,v2) shuffle below is the standard unwinding.
            float l0 = w1 * inv, l1 = w2 * inv, l2 = w0 * inv;
            float cr = c0r * l0 + c1r * l1 + c2r * l2;
            float cg = c0g * l0 + c1g * l1 + c2g * l2;
            float cb = c0b * l0 + c1b * l1 + c2b * l2;
            float a  = c0a * l0 + c1a * l1 + c2a * l2;
            unsigned rgb;
            if (tex) {
                float u = t.u[0] * l0 + t.u[1] * l1 + t.u[2] * l2;
                float v = t.v[0] * l0 + t.v[1] * l1 + t.v[2] * l2;
                unsigned tc = sampleTexture(*tex, u, v);
                // Texture RGB tints the vertex colour; texture alpha scales it (A8 glyphs are white
                // with alpha, RGBA atlas entries carry their own soft alpha for AA'd circles etc).
                // Multiplies round-trip through /255, NOT the >>8 the spike used: an atlas texel is
                // 0..255, so >>8 is a divide by 256 and an opaque texel (255) darkened every colour
                // by a whole LSB (255*255>>8 == 254), which then compounded through each blend. The
                // +127 bias rounds to nearest; /255 compiles to the same multiply-shift.
                rgb = mulRound255((unsigned)cr, tc & 0xFF)
                    | (mulRound255((unsigned)cg, (tc >> 8) & 0xFF) << 8)
                    | (mulRound255((unsigned)cb, (tc >> 16) & 0xFF) << 16);
                a = (float)mulRound255((unsigned)(a + 0.5f), (tc >> 24) & 0xFF);
            } else {
                rgb = (unsigned)cr | ((unsigned)cg << 8) | ((unsigned)cb << 16);
            }
            unsigned ia = (unsigned)a;
            if (ia == 0) continue;
            if (ia > 255) ia = 255;
            blendPixel(&row[px], rgb, ia);
        }
    }
}

// ---------------------------------------------------------------------------
// FAST PATH -- uniform-colour axis-aligned rectangle.
//
// Measured in the spike: a naive all-triangles repaint of a full 286px panel cost ~11.5ms, almost
// all of it in window/child/frame backgrounds -- which ImGui emits as exactly the shape recognised
// below (one PrimRect: 4 verts, 6 indices, one colour). Filling those rows directly took the same
// frame to 1-3ms; at 30fps that is the difference between "fine" and "the game stutters".
//
// Only taken under conditions where the fast pixel set provably equals what the general path would
// produce: integer positions (so a rect maps onto whole pixels, [x0,x1) x [y0,y1)) and a constant
// UV (ImGui points untextured primitives at its white atlas pixel, so flat fills always have
// uv == constant). Anything else falls through to rasterTri and costs what it costs.
struct Quad {
    int x0, y0, x1, y1;  // pixel bounds, exclusive right/bottom
    unsigned rgb;        // vertex colour already combined with the constant texel
    unsigned alpha;
};

// Verts arrive already in framebuffer space (DisplayPos/FramebufferScale applied by the caller).
// Matches ImDrawList::PrimRect's layout: v0=top-left v1=top-right v2=bottom-right v3=bottom-left,
// indices (0,1,2)(0,2,3). Producers that lay a quad out differently simply fail the axis check and
// take the general path, so a wrong guess here cannot draw anything wrong -- only slower.
inline bool matchQuad(const float xs[4], const float ys[4], const float us[4], const float vs[4],
                      const unsigned cols[4], const SwTexture* tex, Quad& out) {
    if (!(xs[0] == xs[3] && xs[1] == xs[2] && ys[0] == ys[1] && ys[2] == ys[3])) return false;
    if (!(cols[0] == cols[1] && cols[0] == cols[2] && cols[0] == cols[3])) return false;
    for (int k = 1; k < 4; k++)
        if (std::fabs(us[k] - us[0]) > 1e-4f || std::fabs(vs[k] - vs[0]) > 1e-4f) return false;

    float fx0 = xs[0] < xs[1] ? xs[0] : xs[1];
    float fx1 = xs[0] > xs[1] ? xs[0] : xs[1];
    float fy0 = ys[0] < ys[2] ? ys[0] : ys[2];
    float fy1 = ys[0] > ys[2] ? ys[0] : ys[2];
    if (std::fabs(fx0 - std::floor(fx0 + 0.5f)) > 1e-3f || std::fabs(fx1 - std::floor(fx1 + 0.5f)) > 1e-3f ||
        std::fabs(fy0 - std::floor(fy0 + 0.5f)) > 1e-3f || std::fabs(fy1 - std::floor(fy1 + 0.5f)) > 1e-3f)
        return false;
    int x0 = (int)fx0, x1 = (int)fx1, y0 = (int)fy0, y1 = (int)fy1;
    if (x1 <= x0 || y1 <= y0) return false;

    unsigned col = cols[0];
    unsigned alpha = (col >> 24) & 0xFF;
    if (tex) {
        unsigned tc = sampleTexture(*tex, us[0], vs[0]);
        col = mulRound255(col & 0xFF, tc & 0xFF)
            | (mulRound255((col >> 8) & 0xFF, (tc >> 8) & 0xFF) << 8)
            | (mulRound255((col >> 16) & 0xFF, (tc >> 16) & 0xFF) << 16);
        alpha = mulRound255(alpha, (tc >> 24) & 0xFF);
    }
    if (alpha == 0) return false;
    out.x0 = x0; out.y0 = y0; out.x1 = x1; out.y1 = y1;
    out.rgb = col & 0xFFFFFFu;
    out.alpha = alpha > 255 ? 255 : alpha;
    return true;
}

inline void fillQuad(unsigned* dst, int bw, int bh, const Quad& q, int cx0, int cy0, int cx1, int cy1) {
    int x0 = q.x0 < cx0 ? cx0 : q.x0;
    int y0 = q.y0 < cy0 ? cy0 : q.y0;
    int x1 = q.x1 > cx1 ? cx1 : q.x1;
    int y1 = q.y1 > cy1 ? cy1 : q.y1;
    if (x1 <= x0 || y1 <= y0) return;
    const size_t w = (size_t)(x1 - x0);
    if (q.alpha >= 255) {
        const unsigned c = q.rgb | 0xFF000000u;
        for (int y = y0; y < y1; y++) {
            unsigned* row = dst + (size_t)y * bw + x0;
            for (size_t k = 0; k < w; k++) row[k] = c;
        }
    } else {
        for (int y = y0; y < y1; y++) {
            unsigned* row = dst + (size_t)y * bw + x0;
            for (size_t k = 0; k < w; k++) blendPixel(&row[k], q.rgb, q.alpha);
        }
    }
}

// Load one vertex into framebuffer space. Kept as a tiny inline so the quad and triangle paths read
// the same transform (DisplayPos offset, then FramebufferScale -- Wine runs us at scale 1, but the
// contract should stay honest in case ImGui ever reports otherwise).
inline void toScreen(const ImDrawVert& v, ImVec2 off, float fs, float& x, float& y) {
    x = (v.pos.x - off.x) * fs;
    y = (v.pos.y - off.y) * fs;
}

}  // namespace detail

inline void renderDrawData(ImDrawData* dd, unsigned* dst, int bw, int bh) {
    if (!dd || !dst || bw <= 0 || bh <= 0) return;
    detail::processTextures(dd);
    ImVec2 off = dd->DisplayPos;
    float fs = dd->FramebufferScale.x;
    ImVector<SwTexture>& reg = detail::textures();
    for (int n = 0; n < dd->CmdListsCount; n++) {
        const ImDrawList* cl = dd->CmdLists[n];
        const ImDrawVert* vtx = cl->VtxBuffer.Data;
        const ImDrawIdx* idx = cl->IdxBuffer.Data;
        for (int ci = 0; ci < cl->CmdBuffer.Size; ci++) {
            const ImDrawCmd& cmd = cl->CmdBuffer[ci];
            if (cmd.UserCallback) {
                // ResetRenderState-style callbacks have no meaning to a CPU raster and KewlKlient
                // panels issue none; if one ever appears here it means a plugin is doing something
                // the launcher-side renderer needs to learn about, not that pixels are missing.
                continue;
            }
            int cx0 = (int)((cmd.ClipRect.x - off.x) * fs);
            int cy0 = (int)((cmd.ClipRect.y - off.y) * fs);
            int cx1 = (int)((cmd.ClipRect.z - off.x) * fs);
            int cy1 = (int)((cmd.ClipRect.w - off.y) * fs);
            if (cx0 < 0) cx0 = 0;
            if (cy0 < 0) cy0 = 0;
            if (cx1 > bw) cx1 = bw;
            if (cy1 > bh) cy1 = bh;
            if (cx1 <= cx0 || cy1 <= cy0) continue;

            const SwTexture* tex = nullptr;
            intptr_t tid = (intptr_t)cmd.GetTexID();
            if (tid >= 0 && tid < reg.size() && reg[(int)tid].pixels) tex = &reg[(int)tid];

            unsigned i = 0;
            while (i + 2 < cmd.ElemCount) {
                // Fast path first, one 6-index group at a time; on a miss the same group falls
                // through to the triangle loop, so a non-rect group costs one failed match and
                // nothing is skipped or double-drawn.
                if (i + 5 < cmd.ElemCount) {
                    unsigned base = cmd.IdxOffset + i;
                    ImDrawIdx i0 = idx[base], i1 = idx[base + 1], i2 = idx[base + 2];
                    ImDrawIdx i3 = idx[base + 3], i4 = idx[base + 4], i5 = idx[base + 5];
                    if (i0 == i3 && i2 == i4) {  // PrimRect's (0,1,2)(0,2,3) pattern
                        const ImDrawVert& v0 = vtx[cmd.VtxOffset + i0];
                        const ImDrawVert& v1 = vtx[cmd.VtxOffset + i1];
                        const ImDrawVert& v2 = vtx[cmd.VtxOffset + i2];
                        const ImDrawVert& v3 = vtx[cmd.VtxOffset + i5];
                        float xs[4], ys[4], us[4], vs[4];
                        unsigned cols[4];  // unsigned, not float: float drops the low 7 bits of a
                                           // 32-bit colour and the uniform-colour test needs exact
                        detail::toScreen(v0, off, fs, xs[0], ys[0]); us[0] = v0.uv.x; vs[0] = v0.uv.y; cols[0] = v0.col;
                        detail::toScreen(v1, off, fs, xs[1], ys[1]); us[1] = v1.uv.x; vs[1] = v1.uv.y; cols[1] = v1.col;
                        detail::toScreen(v2, off, fs, xs[2], ys[2]); us[2] = v2.uv.x; vs[2] = v2.uv.y; cols[2] = v2.col;
                        detail::toScreen(v3, off, fs, xs[3], ys[3]); us[3] = v3.uv.x; vs[3] = v3.uv.y; cols[3] = v3.col;
                        detail::Quad q;
                        if (detail::matchQuad(xs, ys, us, vs, cols, tex, q)) {
                            detail::fillQuad(dst, bw, bh, q, cx0, cy0, cx1, cy1);
                            i += 6;
                            continue;
                        }
                    }
                }
                for (int t = 0; t < 3 && i + 2 < cmd.ElemCount; t++, i += 3) {
                    unsigned base = cmd.IdxOffset + i;
                    const ImDrawVert& va = vtx[cmd.VtxOffset + idx[base]];
                    const ImDrawVert& vb = vtx[cmd.VtxOffset + idx[base + 1]];
                    const ImDrawVert& vc = vtx[cmd.VtxOffset + idx[base + 2]];
                    detail::Tri tri;
                    detail::toScreen(va, off, fs, tri.x[0], tri.y[0]);
                    detail::toScreen(vb, off, fs, tri.x[1], tri.y[1]);
                    detail::toScreen(vc, off, fs, tri.x[2], tri.y[2]);
                    tri.u[0] = va.uv.x; tri.v[0] = va.uv.y;
                    tri.u[1] = vb.uv.x; tri.v[1] = vb.uv.y;
                    tri.u[2] = vc.uv.x; tri.v[2] = vc.uv.y;
                    tri.col[0] = va.col; tri.col[1] = vb.col; tri.col[2] = vc.col;
                    detail::rasterTri(dst, bw, bh, tri, tex, cx0, cy0, cx1, cy1);
                }
            }
        }
    }
}

}  // namespace kewl_sw
