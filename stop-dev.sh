#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$REPO_ROOT/deploy/linux/stop_stack.py" --root "$REPO_ROOT" "$@"
