#!/usr/bin/env bash
# Builds Gutenprint for the CI machine (x86_64) and the probe tool, to inspect printer options.
set -euo pipefail
cd "$(dirname "$0")"
SRC=$PWD/gutenprint-src
OUT=/tmp/gp-host
rm -rf build-host && mkdir build-host && cd build-host
"$SRC/configure" --prefix="$OUT" $(cat ../configure-flags.txt) >/tmp/gp-host-configure.log 2>&1 || { tail -50 /tmp/gp-host-configure.log; exit 1; }
make -j"$(nproc)" >/tmp/gp-host-make.log 2>&1 || { tail -80 /tmp/gp-host-make.log; exit 1; }
make install >/dev/null
cc -O1 -o "$OUT/probe" ../probe/probe.c -I"$OUT/include" "$OUT/lib/libgutenprint.a" -lm
echo "Host build OK"
