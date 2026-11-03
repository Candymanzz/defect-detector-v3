#!/usr/bin/env bash
# Incrementally rebuild the deployed Linux stack, then load the new backend and UI.
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE=defect-detector.service
UI_HELPER=/usr/local/sbin/defect-detector-restart-ui
BUILD_ONLY=false

usage() {
  cat <<'EOF'
Usage: bash ./rebuild-and-run-all.sh [--build-only]

Rebuild Java, C++ detector, camera JNI and frontend; install frontend assets.
After a successful build, restart defect-detector.service, wait for its API,
then restart the kiosk UI. Existing build caches are reused; tests are not run.

  --build-only  Build and install assets without restarting backend or UI
  -h, --help    Show this help

Run as your normal user; sudo/pkexec is used only for privileged operations.
IML_BUILD_JOBS controls C++ build parallelism (default: 2).
EOF
}

fail() { echo "ERROR: $*" >&2; exit 1; }
as_root() {
  if ((EUID == 0)); then
    "$@"
  elif command -v pkexec >/dev/null; then
    # sudo -n -l only checks policy: success does not mean the command can
    # run without a password. Prefer the desktop authentication helper.
    pkexec "$@"
  else
    sudo "$@"
  fi
}

while (($#)); do
  case "$1" in
    --build-only) BUILD_ONLY=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "Unknown option: $1 (use --help)" ;;
  esac
done

if [[ "$BUILD_ONLY" == false ]]; then
  SYSTEMCTL="$(command -v systemctl)" || fail "systemctl is required"
  command -v curl >/dev/null || fail "curl is required for API readiness checks"
  [[ -x "$UI_HELPER" ]] || fail "Run once: sudo bash deploy/linux/setup-frontend-updates.sh"
  [[ "${FRONTEND_INSTALL_DIR:-/opt/defect-detector-ui}" == /opt/defect-detector-ui ]] \
    || fail "Kiosk restart requires FRONTEND_INSTALL_DIR=/opt/defect-detector-ui"
  service_directory="$("$SYSTEMCTL" show "$SERVICE" --property=WorkingDirectory --value)"
  [[ -d "$service_directory" && "$(cd "$service_directory" && pwd -P)" == "$(cd "$REPO_ROOT" && pwd -P)" ]] \
    || fail "$SERVICE must use this checkout as its WorkingDirectory: $REPO_ROOT"
fi

echo "==> Rebuild and install all application components"
bash "$REPO_ROOT/rebuild-and-run.sh"

if [[ "$BUILD_ONLY" == true ]]; then
  echo "Build complete; backend and UI were not restarted."
  exit 0
fi

echo "==> Restart backend with the new Java, C++ and camera JNI builds"
as_root "$SYSTEMCTL" restart "$SERVICE"

wait_for_api() {
  local url="$1" deadline=$((SECONDS + 120))
  while ((SECONDS < deadline)); do
    "$SYSTEMCTL" is-active --quiet "$SERVICE" \
      || fail "$SERVICE stopped; inspect: journalctl -u $SERVICE -n 50 --no-pager"
    if curl --noproxy '*' --fail --silent --max-time 2 "$url" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  fail "API did not become ready within 120 seconds: $url"
}

echo "==> Wait for backend and learning API"
wait_for_api http://127.0.0.1:8000/health
wait_for_api http://127.0.0.1:8099/health
wait_for_api http://127.0.0.1:8099/api/client/learning/accepted-cases

echo "==> Restart kiosk with the new frontend"
as_root "$UI_HELPER"
echo "All components rebuilt; backend API is ready and kiosk restart requested."
