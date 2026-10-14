#!/usr/bin/env bash
# Rebuild only orchestrator and camera-worker; all other components are reused.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKIP_CAMERA_WORKER=false
SKIP_ORCHESTRATOR=false
LEGACY_OPTIONS=false

usage() {
  cat <<'EOF'
Usage: bash ./rebuild-and-run.sh [options]
  -c, --skip-camera-worker  Skip camera-worker rebuild
  -o, --skip-orchestrator   Skip orchestrator rebuild
  -h, --help               Show this help

Only orchestrator and camera-worker are rebuilt; services are not stopped or started.
Orchestrator build requires JDK 17+ and Maven; camera build requires CMake
and a C compiler. MVS_ROOT defaults to /opt/MVS.
Legacy -f, -r, --build-only, --config and --rt-interval are accepted but ignored.
For RT diagnostics use: bash ./run-rt.sh -r
EOF
}

fail() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }
require_tool() { command -v "$1" >/dev/null 2>&1 || fail "Command not found: $1"; }
resolve_maven() {
  if [[ -n "${MAVEN_CMD:-}" ]]; then
    MAVEN_COMMAND=("$(command -v "$MAVEN_CMD")") || fail "MAVEN_CMD not found: $MAVEN_CMD"
    return
  fi
  if command -v mvn >/dev/null 2>&1; then MAVEN_COMMAND=(mvn); return; fi
  for wrapper in "$REPO_ROOT/orchestrator-java/mvnw" "$REPO_ROOT/mvnw"; do
    if [[ -f "$wrapper" ]]; then MAVEN_COMMAND=(bash "$wrapper"); return; fi
  done
  local candidate
  local candidates=()
  if [[ -n "${MAVEN_HOME:-}" ]]; then candidates+=("$MAVEN_HOME/bin/mvn"); fi
  if [[ -n "${M2_HOME:-}" ]]; then candidates+=("$M2_HOME/bin/mvn"); fi
  shopt -s nullglob
  candidates+=(/usr/share/maven/bin/mvn /opt/maven*/bin/mvn /opt/apache-maven*/bin/mvn
    /opt/idea*/plugins/maven*/lib/maven3/bin/mvn
    /opt/idea*/*/plugins/maven*/lib/maven3/bin/mvn
    "$HOME"/idea*/plugins/maven*/lib/maven3/bin/mvn
    "$HOME"/.local/share/JetBrains/Toolbox/apps/*/plugins/maven*/lib/maven3/bin/mvn
    "$HOME"/.local/share/JetBrains/Toolbox/apps/*/*/plugins/maven*/lib/maven3/bin/mvn
    "$HOME"/.local/share/JetBrains/Toolbox/apps/*/*/*/plugins/maven*/lib/maven3/bin/mvn)
  shopt -u nullglob
  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate" ]]; then MAVEN_COMMAND=("$candidate"); return; fi
  done
  fail "Maven not found. Install it: sudo apt update && sudo apt install maven; or set MAVEN_HOME / MAVEN_CMD. To use the existing orchestrator JAR, pass -o."
}
resolve_cmake() {
  if [[ -n "${CMAKE_CMD:-}" ]]; then
    CMAKE_COMMAND="$(command -v "$CMAKE_CMD")" || fail "CMAKE_CMD not found: $CMAKE_CMD"
    return
  fi
  if command -v cmake >/dev/null 2>&1; then CMAKE_COMMAND=cmake; return; fi
  local candidate
  local candidates=()
  if [[ -n "${CMAKE_HOME:-}" ]]; then candidates+=("$CMAKE_HOME/bin/cmake"); fi
  shopt -s nullglob
  candidates+=(/usr/local/bin/cmake /usr/bin/cmake /snap/bin/cmake
    /opt/cmake*/bin/cmake /opt/cmake*/*/bin/cmake
    "$HOME"/.local/bin/cmake "$HOME"/cmake*/bin/cmake
    "$HOME"/Downloads/cmake*/bin/cmake "$HOME"/Downloads/cmake*/*/bin/cmake
    "$HOME"/Загрузки/cmake*/bin/cmake "$HOME"/Загрузки/cmake*/*/bin/cmake
    "$REPO_ROOT"/cmake*/bin/cmake "$REPO_ROOT"/analisSurface/backend/.venv/bin/cmake
    /opt/clion*/bin/cmake/linux/*/bin/cmake
    "$HOME"/clion*/bin/cmake/linux/*/bin/cmake
    "$HOME"/.local/share/JetBrains/Toolbox/apps/*/bin/cmake/linux/*/bin/cmake
    "$HOME"/.local/share/JetBrains/Toolbox/apps/*/*/bin/cmake/linux/*/bin/cmake
    "$HOME"/.local/share/JetBrains/Toolbox/apps/*/*/*/bin/cmake/linux/*/bin/cmake)
  shopt -u nullglob
  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate" ]]; then CMAKE_COMMAND="$candidate"; return; fi
  done
  fail "CMake not found in PATH or standard installation directories. Set CMAKE_CMD=/path/to/bin/cmake or CMAKE_HOME=/path/to/cmake. Pass -c to reuse the existing camera-worker."
}
trap 'echo "ERROR: Build stopped at line $LINENO (exit $?). " >&2' ERR

while (($#)); do
  case "$1" in
    -c|-с|--skip-camera-worker) SKIP_CAMERA_WORKER=true; shift ;;
    -o|--skip-orchestrator) SKIP_ORCHESTRATOR=true; shift ;;
    -f|--no-frontend|-r|--rt-diagnostics|--build-only) LEGACY_OPTIONS=true; shift ;;
    --config|--rt-interval)
      [[ $# -ge 2 && -n "$2" && "$2" != -* ]] || fail "$1 requires a value"
      LEGACY_OPTIONS=true; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "Unknown option: $1 (use --help)" ;;
  esac
done
if [[ "$LEGACY_OPTIONS" == true ]]; then
  echo "Build-only: legacy launch options are ignored. RT diagnostics: bash ./run-rt.sh -r"
fi

cd "$REPO_ROOT"
step "Check build tools"
if [[ -n "${JAVA_HOME:-}" && "$SKIP_ORCHESTRATOR" == false ]]; then
  [[ -x "$JAVA_HOME/bin/java" ]] || fail "JAVA_HOME must contain bin/java"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [[ "$SKIP_ORCHESTRATOR" == false ]]; then
  for tool in java javac; do require_tool "$tool"; done
  resolve_maven
  echo "Maven: ${MAVEN_COMMAND[*]}"
fi
if [[ "$SKIP_CAMERA_WORKER" == false ]]; then
  resolve_cmake
  "$CMAKE_COMMAND" --version >/dev/null || fail "Cannot run CMake: $CMAKE_COMMAND"
  echo "CMake: $CMAKE_COMMAND"
fi
if [[ "$SKIP_ORCHESTRATOR" == false ]]; then
  step "Build orchestrator-java"
  # A rebuild skips both test execution and test compilation; run-all-tests.sh runs tests.
  (cd "$REPO_ROOT/orchestrator-java" && "${MAVEN_COMMAND[@]}" --batch-mode --no-transfer-progress \
    -Dstyle.color=never package -Dmaven.test.skip=true)
  [[ -f "$REPO_ROOT/orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar" ]] || fail "Orchestrator build artifact missing"
fi

if [[ "$SKIP_CAMERA_WORKER" == false ]]; then
  step "Build camera-worker"
  # Keep Linux and Windows CMake caches separate.
  CAMERA_BUILD="$REPO_ROOT/camera-worker/build-linux"
  "$CMAKE_COMMAND" -S "$REPO_ROOT/camera-worker" -B "$CAMERA_BUILD" \
    -DCMAKE_BUILD_TYPE=Release "-DMVS_ROOT=${MVS_ROOT:-/opt/MVS}"
  "$CMAKE_COMMAND" --build "$CAMERA_BUILD" --config Release --target camera_worker
  [[ -x "$CAMERA_BUILD/camera_worker" ]] || fail "camera_worker binary missing"
  mkdir -p "$REPO_ROOT/camera-worker/build"
  # Existing configuration points to camera-worker/build/camera_worker.
  worker_target="$REPO_ROOT/camera-worker/build/camera_worker"
  # Replace the inode atomically: Linux disallows overwriting a running executable.
  cp "$CAMERA_BUILD/camera_worker" "$worker_target.new.$$"
  mv -f "$worker_target.new.$$" "$worker_target"
fi

step "Build complete"
