Vendored, not a submodule: see tools/wine.md for why the build must work offline.

Source:   https://github.com/ocornut/imgui, v1.93.0 WIP (IMGUI_VERSION_NUM 19295, IMGUI_HAS_TEXTURES).
Taken from: the verified software-rasterizer spike at /tmp/imgui-swtest/imgui, which was compiled
clean with the llvm-mingw toolchain and whose frame.png output was verified pixel-wise. The spike's
rast.cpp/rast.hpp became client/imgui_sw.hpp -- the two are the same renderer contract.

Kept: imgui.h imgui.cpp imgui_draw.cpp imgui_internal.h imgui_tables.cpp imgui_widgets.cpp
imconfig.h imstb_*.h LICENSE.txt. imgui_demo.cpp is deliberately NOT in the build (see the comment
on the kewl_imgui target in CMakeLists.txt).

Backend contract note for anything that renders here: with RendererHasTextures (1.92+) the atlas is
NOT pre-baked; you must consume ImTextureData WantCreate/WantUpdates/WantDestroy each frame and
SetTexID/SetStatus -- client/imgui_sw.hpp does this for the CPU path, and ImGui asserts
ImFontAtlasUpdateNewFrame if io.BackendFlags lacks the flag while the atlas is unbuilt.
