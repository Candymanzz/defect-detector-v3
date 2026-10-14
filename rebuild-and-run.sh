#!/usr/bin/env bash
# Rebuild only orchestrator and camera-worker; all other components are reused.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="${CONFIG:-config/config.yaml}"
NO_FRONTEND=false
SKIP_CAMERA_WORKER=false
SKIP_ORCHESTRATOR=false
BUILD_ONLY=false

usage() {
  cat <<'EOF'
Usage: bash ./rebuild-and-run.sh [options]
  -c, --skip-camera-worker  Skip camera-worker rebuild
  -o, --skip-orchestrator   Skip orchestrator rebuild
  -f, --no-frontend         Launch without UI (frontend is never rebuilt)
  --config PATH          Configuration file (default: config/config.yaml)
  --build-only           Rebuild without starting/stopping the running stack
  -h, --help             Show this help

Only orchestrator and camera-worker are rebuilt by default.
Orchestrator build requires JDK 17+ and Maven; camera build requires CMake
and a C compiler. Launch requires Java. MVS_ROOT defaults to /opt/MVS.
Python, geometry, positioning and frontend must already be prepared.
EOF
}

fail() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }
require_tool() { command -v "$1" >/dev/null 2>&1 || fail "Command not found: $1"; }
trap 'echo "ERROR: Build stopped at line $LINENO (exit $?). Stack was not launched." >&2' ERR

while (($#)); do
  case "$1" in
    -f|--no-frontend) NO_FRONTEND=true; shift ;;
    -c|-с|--skip-camera-worker) SKIP_CAMERA_WORKER=true; shift ;;
    -o|--skip-orchestrator) SKIP_ORCHESTRATOR=true; shift ;;
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
if [[ -n "${JAVA_HOME:-}" && ( "$SKIP_ORCHESTRATOR" == false || "$BUILD_ONLY" == false ) ]]; then
  [[ -x "$JAVA_HOME/bin/java" ]] || fail "JAVA_HOME must contain bin/java"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [[ "$SKIP_ORCHESTRATOR" == false ]]; then
  for tool in java javac mvn; do require_tool "$tool"; done
elif [[ "$BUILD_ONLY" == false ]]; then
  require_tool java
fi
if [[ "$SKIP_CAMERA_WORKER" == false ]]; then require_tool cmake; fi
if [[ "$BUILD_ONLY" == false ]]; then
  [[ -f "$CONFIG" ]] || fail "Config not found: $CONFIG"
  [[ -f "$REPO_ROOT/run-supervised.sh" ]] || fail "Missing run-supervised.sh"
  if [[ "$SKIP_ORCHESTRATOR" == true ]]; then
    [[ -f "$REPO_ROOT/orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar" ]] || fail "Existing orchestrator JAR missing; omit -o to build it"
  fi
fi

if [[ "$SKIP_ORCHESTRATOR" == false ]]; then
  step "Build orchestrator-java"
  # A rebuild skips both test execution and test compilation; run-all-tests.sh runs tests.
  (cd "$REPO_ROOT/orchestrator-java" && mvn -q package -Dmaven.test.skip=true)
  [[ -f "$REPO_ROOT/orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar" ]] || fail "Orchestrator build artifact missing"
fi

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

step "Build complete"
if [[ "$BUILD_ONLY" == true ]]; then exit 0; fi
step "Start stack supervisor"
export CONFIG
run_args=()
if [[ "$NO_FRONTEND" == true ]]; then run_args+=(--no-frontend); fi
# The launcher performs process cleanup and creates the session log directory.
exec bash "$REPO_ROOT/run-supervised.sh" "${run_args[@]}"
