#!/usr/bin/env bash
# Build without stopping a running service. Publish by rename, preserving its mapped executable.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CMAKE="${CMAKE_CMD:-cmake}"
JOBS="${IML_BUILD_JOBS:-2}"
[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || { echo "IML_BUILD_JOBS must be a positive integer" >&2; exit 1; }
command -v "$CMAKE" >/dev/null || { echo "CMake is required" >&2; exit 1; }
# The factory host keeps C++ dependencies in a private runtime, outside ldconfig.
# CMake also runs its bundled pkgconf, so it needs these libraries during builds.
RUNTIME="$ROOT/../tools/cpp-runtime-ubuntu24"
if [[ -x "$RUNTIME/usr/bin/pkgconf" ]]; then
  export LD_LIBRARY_PATH="$RUNTIME/usr/lib/x86_64-linux-gnu:$RUNTIME/usr/lib/x86_64-linux-gnu/blas:$RUNTIME/usr/lib/x86_64-linux-gnu/lapack:$RUNTIME/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export PKG_CONFIG_PATH="$RUNTIME/usr/lib/x86_64-linux-gnu/pkgconfig:$RUNTIME/usr/share/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
  export PKG_CONFIG_SYSROOT_DIR="$RUNTIME"
  export CPATH="$RUNTIME/usr/include:$RUNTIME/usr/include/x86_64-linux-gnu${CPATH:+:$CPATH}"
fi
"$CMAKE" -S "$ROOT" -B "$ROOT/build-linux" -DCMAKE_BUILD_TYPE=Release "$@"
"$CMAKE" --build "$ROOT/build-linux" --parallel "$JOBS"
mkdir -p "$ROOT/build"
target="$ROOT/build/cpp_analis_surface"
temporary="$target.new.$$"
trap 'rm -f -- "$temporary"' EXIT
cp "$ROOT/build-linux/cpp_analis_surface" "$temporary"
chmod +x "$temporary"
mv -f "$temporary" "$target"
echo "Detector built: $target"
