#!/usr/bin/env bash
# tools/wine-setup.sh -- build KewlKlient on Linux (llvm-mingw cross) and assemble a Wine-test dist.
# See tools/wine.md for the one-time setup and how to run the result.
#
#   tools/wine-setup.sh                       # build build/wine-dist/
#   KEWL_WINJDK=jdk-17.0.20.1+1-jre tools/wine-setup.sh   # java= path (C:\ form) for the ini
#
# Env it honours:
#   LLVM_MINGW  where the toolchain lives (default: first /opt/llvm-mingw-* or /tmp/llvm-mingw-*)
#   KEWL_WINJDK Windows path of the JRE for kewlklient.ini (default jdk-17* found in drive_c)

set -euo pipefail
cd "$(dirname "$0")/.."

LLVM_MINGW=${LLVM_MINGW:-$(ls -d /opt/llvm-mingw-* /tmp/llvm-mingw-* 2>/dev/null | head -1 || true)}
[ -n "${LLVM_MINGW}" ] || { echo "llvm-mingw not found -- see tools/wine.md"; exit 1; }
CXX="${LLVM_MINGW}/bin/x86_64-w64-mingw32-g++"
[ -x "${CXX}" ] || { echo "no compiler at ${CXX}"; exit 1; }
CMAKE=$(command -v cmake || echo ~/.local/bin/cmake)
[ -x "${CMAKE}" ] || { echo "cmake not found -- see tools/wine.md"; exit 1; }
# A JDK for the JNI headers. Try $JAVA_HOME, java on PATH, then the usual install spots.
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in "$(command -v java && dirname "$(dirname "$(readlink -f "$(command -v java)")")")" \
                     /usr/lib/jvm/* ~/.jdk/*; do
        [ -d "${candidate}/include" ] && JAVA_HOME="${candidate}" && break
    done 2>/dev/null
fi
[ -n "${JAVA_HOME:-}" ] && [ -d "${JAVA_HOME}/include" ] || { echo "no JDK with include/ found -- set JAVA_HOME"; exit 1; }
export JAVA_HOME
echo "== using JDK: ${JAVA_HOME}"

echo "== jar"
sh gradlew jar -q

echo "== native (llvm-mingw)"
BUILD=build/wine-native
mkdir -p "${BUILD}"
cat > "${BUILD}/toolchain.cmake" <<EOF
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(CMAKE_C_COMPILER   ${LLVM_MINGW}/bin/x86_64-w64-mingw32-gcc)
set(CMAKE_CXX_COMPILER ${CXX})
set(CMAKE_RC_COMPILER  ${LLVM_MINGW}/bin/x86_64-w64-mingw32-windres)
set(CMAKE_FIND_ROOT_PATH ${LLVM_MINGW}/x86_64-w64-mingw32)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
EOF
"${CMAKE}" -B "${BUILD}" -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE="${BUILD}/toolchain.cmake" \
    -DKEWL_DIST="${PWD}/build/wine-dist" . >/dev/null
"${CMAKE}" --build "${BUILD}"

echo "== dist"
DIST=build/wine-dist
mkdir -p "${DIST}"
cp build/dist/kewlklient.jar "${DIST}/"
"${CXX}" -O2 -static -o "${DIST}/wine_inject.exe" tools/wine_inject.cpp
if [ ! -f "${DIST}/kewlklient.ini" ]; then
    WINJDK=${KEWL_WINJDK:-$(ls -d ~/.wine/drive_c/jdk* 2>/dev/null | head -1 | xargs -r basename)}
    [ -n "${WINJDK}" ] || { echo "no Windows JRE under ~/.wine/drive_c -- see tools/wine.md"; exit 1; }
    printf '[kewlklient]\njava=C:\\%s\n' "${WINJDK}" > "${DIST}/kewlklient.ini"
    echo "   wrote kewlklient.ini -> java=C:\\${WINJDK} (edit if that is not your JRE)"
fi
# KewlKlient.exe and kewlklient.dll land in DIST directly (KEWL_DIST above).

echo
echo "Done. To test:"
echo "  cd /path/to/osclient && WINEDEBUG=-all wine osclient.exe > /tmp/osrs-wine.log 2>&1 &"
echo "  cd ${DIST} && wine wine_inject.exe \"Z:${PWD}/${DIST}/kewlklient.dll\""
echo "  -- or the whole thing at once (launcher spawns + injects + embeds the game):"
echo "  tools/launcher-smoke.sh     # offline strip check + live launcher + the manual probes"
