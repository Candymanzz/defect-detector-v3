#!/usr/bin/env bash
# Build without stopping a running service. Publish by rename, preserving its mapped executable.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CMAKE="${CMAKE_CMD:-cmake}"
JOBS="${IML_BUILD_JOBS:-2}"
[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || { echo "IML_BUILD_JOBS must be a positive integer" >&2; exit 1; }
command -v "$CMAKE" >/dev/null || { echo "CMake is required" >&2; exit 1; }
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
