#!/usr/bin/env bash
# Rebuild orchestrator, camera-worker and the frontend; install the kiosk UI.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKIP_CAMERA_WORKER=false
SKIP_ORCHESTRATOR=false
SKIP_FRONTEND=false
RESTART_FRONTEND=false
FRONTEND_INSTALL_DIR="${FRONTEND_INSTALL_DIR:-/opt/defect-detector-ui}"
FRONTEND_INSTALL_COMMAND=()
LEGACY_OPTIONS=false

usage() {
  cat <<'EOF'
Usage: bash ./rebuild-and-run.sh [options]
  -c, --skip-camera-worker  Skip camera-worker rebuild
  -o, --skip-orchestrator   Skip orchestrator rebuild
  -f, --skip-frontend       Skip frontend build and kiosk installation
  --restart-frontend       Restart kiosk UI after installation, without a password
  -h, --help               Show this help

Rebuild orchestrator, camera-worker and frontend; services are not stopped or started.
Frontend: npm run build, then copy dist/, electron/ and package.json to
/opt/defect-detector-ui (override with FRONTEND_INSTALL_DIR). Existing kiosk
Electron/node_modules are reused. sudo is requested only for protected files.
Restart the kiosk Electron separately to load the new UI.
For updates without sudo prompts, run deploy/linux/setup-frontend-updates.sh once as root.
Orchestrator build requires JDK 17+ and Maven; camera build requires CMake
and a C compiler. Frontend requires Node.js and npm (NVM installs are detected).
MVS_ROOT defaults to /opt/MVS.
--no-frontend aliases --skip-frontend.
Legacy -r, --build-only, --config and --rt-interval are accepted but ignored.
For RT diagnostics use: bash ./run-rt.sh -r
EOF
}

fail() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }
require_tool() { command -v "$1" >/dev/null 2>&1 || fail "Command not found: $1"; }
resolve_frontend_tools() {
  if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    local candidate
    local candidates=()
    [[ -z "${NODE_HOME:-}" ]] || candidates+=("$NODE_HOME/bin")
    shopt -s nullglob
    candidates+=("${NVM_DIR:-$HOME/.nvm}"/versions/node/*/bin)
    shopt -u nullglob
    while IFS= read -r candidate; do
      if [[ -x "$candidate/node" && -x "$candidate/npm" ]]; then
        export PATH="$candidate:$PATH"
        break
      fi
    done < <(printf '%s\n' "${candidates[@]}" | sort -Vr)
  fi
  require_tool node
  require_tool npm
  node -e 'const [major, minor] = process.versions.node.split(".").map(Number); if (major < 20 || (major === 20 && minor < 19)) process.exit(1)' \
    || fail "Frontend requires Node.js >=20.19.0"
  echo "Node.js: $(node --version); npm: $(npm --version)"
}
prepare_frontend_install() {
  [[ "$FRONTEND_INSTALL_DIR" == /* && "$FRONTEND_INSTALL_DIR" != / ]] \
    || fail "FRONTEND_INSTALL_DIR must be an absolute application directory"
  [[ -x "$FRONTEND_INSTALL_DIR/node_modules/electron/dist/electron" ]] \
    || fail "Kiosk Electron missing under $FRONTEND_INSTALL_DIR/node_modules; provision the kiosk first (deploy/linux/README.md), or pass -f"
  local needs_sudo=false
  local destination
  # Existing child directories can be updated without write access to /opt itself.
  for destination in "$FRONTEND_INSTALL_DIR/electron" "$FRONTEND_INSTALL_DIR/dist"; do
    if [[ -d "$destination" ]]; then
      if [[ ! -w "$destination" || ! -x "$destination" ]] \
          || [[ -n "$(find "$destination" ! -type l ! -writable -print -quit)" ]]; then
        needs_sudo=true
      fi
    elif [[ ! -w "$FRONTEND_INSTALL_DIR" ]]; then
      needs_sudo=true
    fi
  done
  if [[ -e "$FRONTEND_INSTALL_DIR/package.json" ]]; then
    [[ -w "$FRONTEND_INSTALL_DIR/package.json" ]] || needs_sudo=true
  elif [[ ! -w "$FRONTEND_INSTALL_DIR" ]]; then
    needs_sudo=true
  fi
  if [[ "$needs_sudo" == true ]]; then
    if ((EUID != 0)); then
      require_tool sudo
      echo "Kiosk installation needs sudo access to $FRONTEND_INSTALL_DIR"
      sudo -v
      FRONTEND_INSTALL_COMMAND=(sudo)
    fi
  fi
}
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
    -f|--skip-frontend|--no-frontend) SKIP_FRONTEND=true; shift ;;
    --restart-frontend) RESTART_FRONTEND=true; shift ;;
    -r|--rt-diagnostics|--build-only) LEGACY_OPTIONS=true; shift ;;
    --config|--rt-interval)
      [[ $# -ge 2 && -n "$2" && "$2" != -* ]] || fail "$1 requires a value"
      LEGACY_OPTIONS=true; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "Unknown option: $1 (use --help)" ;;
  esac
done
if [[ "$RESTART_FRONTEND" == true ]]; then
  [[ "$SKIP_FRONTEND" == false ]] || fail "--restart-frontend cannot be combined with --skip-frontend"
  [[ "$FRONTEND_INSTALL_DIR" == /opt/defect-detector-ui ]] \
    || fail "--restart-frontend only restarts the installed /opt/defect-detector-ui kiosk"
  [[ -x /usr/local/sbin/defect-detector-restart-ui ]] \
    || fail "Run sudo bash deploy/linux/setup-frontend-updates.sh once to configure kiosk restart"
  require_tool sudo
  sudo -n -l /usr/local/sbin/defect-detector-restart-ui >/dev/null \
    || fail "Passwordless kiosk restart is not configured; run deploy/linux/setup-frontend-updates.sh as root once"
fi
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
if [[ "$SKIP_FRONTEND" == false ]]; then
  resolve_frontend_tools
  prepare_frontend_install
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

if [[ "$SKIP_FRONTEND" == false ]]; then
  step "Build frontend"
  (
    cd "$REPO_ROOT/front-end"
    if [[ ! -x node_modules/.bin/tsc || ! -x node_modules/.bin/vite ]]; then
      npm ci
    fi
    npm run build
  )
  [[ -f "$REPO_ROOT/front-end/dist/index.html" ]] || fail "Frontend build artifact missing"
  step "Install kiosk frontend to $FRONTEND_INSTALL_DIR"
  "${FRONTEND_INSTALL_COMMAND[@]}" mkdir -p "$FRONTEND_INSTALL_DIR/dist" "$FRONTEND_INSTALL_DIR/electron"
  # Keep older hashed assets until the running kiosk is reloaded.
  "${FRONTEND_INSTALL_COMMAND[@]}" cp -R "$REPO_ROOT/front-end/dist/." "$FRONTEND_INSTALL_DIR/dist/"
  "${FRONTEND_INSTALL_COMMAND[@]}" cp -R "$REPO_ROOT/front-end/electron/." "$FRONTEND_INSTALL_DIR/electron/"
  "${FRONTEND_INSTALL_COMMAND[@]}" cp "$REPO_ROOT/front-end/package.json" "$FRONTEND_INSTALL_DIR/package.json"
  if [[ "$RESTART_FRONTEND" == true ]]; then
    step "Restart kiosk UI"
    sudo -n /usr/local/sbin/defect-detector-restart-ui
  else
    echo "Frontend installed. Restart the kiosk Electron to load this build (--restart-frontend)."
  fi
fi

step "Build complete"
