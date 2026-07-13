#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INTEGRATION="${1:-}"

run_step() {
  local name="$1"
  local dir="$2"
  shift 2
  echo ""
  echo "== $name =="
  (cd "$dir" && "$@")
}

export MAVEN_OPTS="${MAVEN_OPTS:--Dmaven.repo.local=$ROOT/../.m2/repository}"

run_step "orchestrator-java (JUnit)" "$ROOT/orchestrator-java" mvn -q test
run_step "java-geometry-service (JUnit)" "$ROOT/java-geometry-service" mvn -q test

BACKEND="$ROOT/analisSurface/backend"
PYTHON="$BACKEND/.venv/bin/python"
if [[ ! -x "$PYTHON" ]]; then
  PYTHON="python3"
fi

run_step "analisSurface backend (pytest)" "$BACKEND" bash -lc "
  $PYTHON -m pip install -q -r requirements-dev.txt
  if [[ -n '$INTEGRATION' ]]; then
    $PYTHON -m pytest tests/ -q
  else
    $PYTHON -m pytest tests/ -q -m 'not integration'
  fi
"

run_step "front-end (Vitest)" "$ROOT/front-end" bash -lc "
  if [[ ! -d node_modules ]]; then npm install; fi
  npm test
"

if command -v dotnet >/dev/null 2>&1; then
  if [[ -d "$ROOT/IoInputMonitor/IoInputMonitor.Tests" ]]; then
    run_step "IoInputMonitor (xUnit)" "$ROOT/IoInputMonitor/IoInputMonitor.Tests" dotnet test -c Release
  fi
  if [[ -d "$ROOT/LightServer.v3/LightServer.Tests" ]]; then
    run_step "LightServer.v3 (xUnit)" "$ROOT/LightServer.v3/LightServer.Tests" dotnet test -c Release
  fi
else
  echo "dotnet not found — skipping C# tests"
fi

if command -v cmake >/dev/null 2>&1; then
  run_step "camera-worker (CTest)" "$ROOT/camera-worker" bash -lc "
    cmake -S . -B build-test
    cmake --build build-test --target cw_config_tests
    ctest --test-dir build-test --output-on-failure
  "
fi

echo ""
echo "All tests passed."
