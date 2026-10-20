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

CAMERA_TEST_ARGS=()
if command -v cmake >/dev/null 2>&1; then
  # Tests use synthetic frames only and never initialize vendor camera backends.
  run_step "camera JNI (build)" "$ROOT" cmake -S camera-worker -B camera-worker/build-test \
    -DCMAKE_BUILD_TYPE=Debug -DCAMERA_BUILD_JNI=ON -DCAMERA_ENABLE_MVS=OFF -DCAMERA_ENABLE_ARAVIS=OFF
  run_step "camera JNI (compile)" "$ROOT" cmake --build camera-worker/build-test --config Debug --target camera_jni cw_config_tests
  run_step "camera-worker (CTest)" "$ROOT" ctest --test-dir camera-worker/build-test -C Debug --output-on-failure
  case "$(uname -s)" in
    Darwin) camera_library=libcamera_jni.dylib ;;
    MINGW*|MSYS*|CYGWIN*) camera_library=camera_jni.dll ;;
    *) camera_library=libcamera_jni.so ;;
  esac
  camera_library_path="$ROOT/camera-worker/build-test/$camera_library"
  if [[ ! -f "$camera_library_path" ]]; then
    camera_library_path="$ROOT/camera-worker/build-test/Debug/$camera_library"
  fi
  CAMERA_TEST_ARGS=("-Diml.camera.native.library=$camera_library_path")
fi

run_step "orchestrator-java (JUnit)" "$ROOT/orchestrator-java" mvn -q "${CAMERA_TEST_ARGS[@]}" test
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

echo ""
echo "All tests passed."
