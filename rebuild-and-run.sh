#!/usr/bin/env bash
# Linux rebuild and launch. -f skips frontend dependencies, build and autostart.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="${CONFIG:-config/config.yaml}"
NO_FRONTEND=false
SKIP_CAMERA_WORKER=false
BUILD_ONLY=false

usage() {
  cat <<'EOF'
Usage: bash ./rebuild-and-run.sh [options]
  -f, --no-frontend       Skip frontend installation/build and launch without UI
  --skip-camera-worker   Use an existing camera-worker binary
  --config PATH          Configuration file (default: config/config.yaml)
  --build-only           Rebuild without starting/stopping the running stack
  -h, --help             Show this help

Requires JDK 17+, Maven, Python 3 with venv/pip, CMake and a C compiler.
Frontend builds also require Node.js and npm. MVS_ROOT defaults to /opt/MVS.
EOF
}

fail() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }
require_tool() { command -v "$1" >/dev/null 2>&1 || fail "Command not found: $1"; }
trap 'echo "ERROR: Build stopped at line $LINENO (exit $?). Stack was not launched." >&2' ERR

while (($#)); do
  case "$1" in
    -f|--no-frontend) NO_FRONTEND=true; shift ;;
    --skip-camera-worker) SKIP_CAMERA_WORKER=true; shift ;;
    --build-only) BUILD_ONLY=true; shift ;;
    --config)
      [[ $# -ge 2 && -n "$2" && "$2" != -* ]] || fail "--config requires a path"
      CONFIG="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "Unknown option: $1 (use --help)" ;;
  esac
done

cd "$REPO_ROOT"
step "Check build tools"
if [[ -n "${JAVA_HOME:-}" ]]; then
  [[ -x "$JAVA_HOME/bin/java" && -x "$JAVA_HOME/bin/javac" ]] || fail "JAVA_HOME must point to a JDK"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
for tool in java javac mvn python3; do require_tool "$tool"; done
if [[ "$SKIP_CAMERA_WORKER" == false ]]; then require_tool cmake; fi
if [[ "$NO_FRONTEND" == false ]]; then
  require_tool node
  require_tool npm
fi
if [[ "$BUILD_ONLY" == false ]]; then
  [[ -f "$CONFIG" ]] || fail "Config not found: $CONFIG"
  [[ -f "$REPO_ROOT/run-supervised.sh" ]] || fail "Missing run-supervised.sh"
fi

for service in orchestrator-java java-geometry-service java-positioning-service; do
  step "Build $service"
  # A rebuild skips both test execution and test compilation; run-all-tests.sh runs tests.
  (cd "$REPO_ROOT/$service" && mvn -q package -Dmaven.test.skip=true)
  case "$service" in
    orchestrator-java) artifact="orchestrator-0.1.0-SNAPSHOT.jar" ;;
    *) artifact="$service-0.1.0-SNAPSHOT.jar" ;;
  esac
  [[ -f "$REPO_ROOT/$service/target/$artifact" ]] || fail "Build artifact missing: $service/target/$artifact"
done

PYTHON_BACKEND="$REPO_ROOT/analisSurface/backend"
PYTHON_VENV="$PYTHON_BACKEND/.venv"
step "Python venv + pip"
if [[ ! -e "$PYTHON_VENV" ]]; then
  python3 -m venv "$PYTHON_VENV"
fi
[[ -x "$PYTHON_VENV/bin/python" ]] || fail "Invalid Linux venv: $PYTHON_VENV (a Windows venv cannot be used on Linux)"
"$PYTHON_VENV/bin/python" -m pip install -q -r "$PYTHON_BACKEND/requirements.txt"

if [[ "$SKIP_CAMERA_WORKER" == false ]]; then
  step "Build camera-worker"
  # Keep Linux and Windows CMake caches separate.
  CAMERA_BUILD="$REPO_ROOT/camera-worker/build-linux"
  cmake -S "$REPO_ROOT/camera-worker" -B "$CAMERA_BUILD" \
    -DCMAKE_BUILD_TYPE=Release "-DMVS_ROOT=${MVS_ROOT:-/opt/MVS}"
  cmake --build "$CAMERA_BUILD" --config Release --target camera_worker
  [[ -x "$CAMERA_BUILD/camera_worker" ]] || fail "camera_worker binary missing"
  mkdir -p "$REPO_ROOT/camera-worker/build"
  # Existing configuration points to camera-worker/build/camera_worker.
  worker_target="$REPO_ROOT/camera-worker/build/camera_worker"
  # Replace the inode atomically: Linux disallows overwriting a running executable.
  cp "$CAMERA_BUILD/camera_worker" "$worker_target.new.$$"
  mv -f "$worker_target.new.$$" "$worker_target"
fi

if [[ "$NO_FRONTEND" == false ]]; then
  step "Install and build front-end"
  (cd "$REPO_ROOT/front-end" && npm ci && npm run build)
  [[ -f "$REPO_ROOT/front-end/dist/index.html" ]] || fail "Frontend build artifact missing"
fi

step "Build complete"
if [[ "$BUILD_ONLY" == true ]]; then exit 0; fi
step "Start stack supervisor"
export CONFIG
run_args=()
if [[ "$NO_FRONTEND" == true ]]; then run_args+=(--no-frontend); fi
# The launcher performs process cleanup and creates the session log directory.
exec bash "$REPO_ROOT/run-supervised.sh" "${run_args[@]}"
