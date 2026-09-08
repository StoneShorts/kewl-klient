// imgui_sw.cpp -- existence proof for client/imgui_sw.hpp.
//
// The rasterizer is header-only so the launcher and the DLL can each include exactly the code they
// need, but an object library member with no symbols of its own still has to be COMPILED somewhere
// to catch errors at build time rather than in whichever TU includes the header first. This TU is
// that somewhere; it produces no code of its own (everything in the header is inline).
#include "imgui_sw.hpp"
