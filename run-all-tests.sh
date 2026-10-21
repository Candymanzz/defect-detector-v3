#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LEGACY_PYTHON=false
INTEGRATION=false
for arg in "$@"; do
  case "$arg" in
    --legacy-python) LEGACY_PYTHON=true ;;
    --integration) INTEGRATION=true ;;
    *) echo "Usage: bash ./run-all-tests.sh [--legacy-python] [--integration]" >&2; exit 1 ;;
  esac
done

run_step() {
  local name="$1"
  local dir="$2"
  shift 2
  echo ""
  echo "== $name =="
  (cd "$dir" && "$@")
}

export MAVEN_OPTS="${MAVEN_OPTS:-}"

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
run_step "Linux service scripts (unittest)" "$ROOT" python3 -m unittest discover -s deploy/linux -p 'test_*.py'

run_step "C++ detector (configure)" "$ROOT" cmake -S cppAnalisSurface -B cppAnalisSurface/build-test \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON
run_step "C++ detector (build)" "$ROOT" cmake --build cppAnalisSurface/build-test --parallel "${IML_BUILD_JOBS:-2}"
run_step "C++ detector (CTest)" "$ROOT" ctest --test-dir cppAnalisSurface/build-test --output-on-failure
run_step "Java -> C++ (HTTP/SHM)" "$ROOT/orchestrator-java" mvn -q \
  "-Diml.cpp.detector.binary=$ROOT/cppAnalisSurface/build-test/cpp_analis_surface" \
  -Dtest=CppDetectorLinuxIntegrationTest test

if [[ "$LEGACY_PYTHON" == true ]]; then
BACKEND="$ROOT/analisSurface/backend"
PYTHON="$BACKEND/.venv/bin/python"
if [[ ! -x "$PYTHON" ]]; then
  PYTHON="python3"
fi

run_step "analisSurface backend (pytest)" "$BACKEND" bash -lc "
  $PYTHON -m pip install -q -r requirements-dev.txt
  if [[ '$INTEGRATION' == true ]]; then
    $PYTHON -m pytest tests/ -q
  else
    $PYTHON -m pytest tests/ -q -m 'not integration'
  fi
"
fi

run_step "front-end (Vitest)" "$ROOT/front-end" bash -lc "
  if [[ ! -d node_modules ]]; then npm install; fi
  npm test
"

echo ""
echo "All tests passed."
