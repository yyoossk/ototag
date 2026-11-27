#!/bin/bash
# Rebuilds app/lib/arm64-v8a/libotofp.so (JNI + libchromaprint 1.6.2).
# Needs: Android NDK r27 and the chromaprint 1.6.2 sources.
#   NDK=$HOME/workspace/toolchain/android/android-ndk-r27
#   CHROMAPRINT_SRC=<chromaprint-1.6.2 source tree>
set -euo pipefail
NDK="${NDK:?set NDK}"
SRC="${CHROMAPRINT_SRC:?set CHROMAPRINT_SRC}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
cmake -S "$SRC" -B "$WORK/arm" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release -DFFT_LIB=kissfft \
  -DKISSFFT_SOURCE_DIR="$SRC/src/3rdparty/kissfft" \
  -DBUILD_TOOLS=OFF -DBUILD_TESTS=OFF -DBUILD_SHARED_LIBS=OFF
make -C "$WORK/arm" -j2 chromaprint
mkdir -p "$HERE/../lib/arm64-v8a"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang++" \
  -shared -fPIC -O2 "$HERE/fp_jni.cpp" -I"$SRC/src/include" \
  -L"$WORK/arm/src" -lchromaprint -o "$HERE/../lib/arm64-v8a/libotofp.so"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  "$HERE/../lib/arm64-v8a/libotofp.so"
echo "built $HERE/../lib/arm64-v8a/libotofp.so"
