#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
[[ -x build/cpp_analis_surface ]] || { echo "Run bash cppAnalisSurface/build.sh first" >&2; exit 1; }
exec ./build/cpp_analis_surface --host 127.0.0.1 --port 8000 --workers "${ANALIS_INSPECT_WORKERS:-4}" --data-dir data "$@"
