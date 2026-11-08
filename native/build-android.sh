#!/usr/bin/env bash
# Cross-compiles libgutenprint (static) for Android arm64 with the NDK, and collects the XML data.
set -euo pipefail
cd "$(dirname "$0")"
SRC=$PWD/gutenprint-src
NDK=${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] || { echo "No NDK found" >&2; exit 1; }
API=33
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
OUT=$PWD/prebuilt/arm64-v8a
rm -rf build-android "$OUT" && mkdir -p build-android "$OUT"
cd build-android
export CC="$TC/aarch64-linux-android$API-clang" AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip"
export CFLAGS="-O2 -fPIC"
"$SRC/configure" --host=aarch64-linux-android --prefix="$OUT/install" $(cat ../configure-flags.txt) >/tmp/gp-android-configure.log 2>&1 || { tail -60 /tmp/gp-android-configure.log; exit 1; }
make -j"$(nproc)" -C src/main >/tmp/gp-android-make.log 2>&1 || { tail -100 /tmp/gp-android-make.log; exit 1; }
make -C src/main install >/dev/null
make -C include install >/dev/null 2>&1 || true
# XML printer data comes from the host install (same files; architecture-independent)
mkdir -p "$OUT/data"
cp -r /tmp/gp-host/share/gutenprint/*/xml "$OUT/data/" 2>/dev/null || cp -r "$SRC/src/xml" "$OUT/data/xml"
ls -la "$OUT/install/lib"
du -sh "$OUT/data"
echo "Android build OK"
