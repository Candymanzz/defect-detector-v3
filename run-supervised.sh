#!/usr/bin/env bash
# Launch managed services; optionally record read-only RT telemetry in session logs.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"
CONFIG="${CONFIG:-config/config.yaml}"
ORCHESTRATOR_JAR="$REPO_ROOT/orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar"
NO_FRONTEND=false
RT_DIAGNOSTICS=false
RT_INTERVAL="${IML_RT_DIAGNOSTICS_INTERVAL:-1}"

while (($#)); do
  case "$1" in
    -f|--no-frontend) NO_FRONTEND=true; shift ;;
    -r|--rt-diagnostics) RT_DIAGNOSTICS=true; shift ;;
    --rt-interval)
      [[ $# -ge 2 ]] || { echo "--rt-interval requires seconds" >&2; exit 1; }
      RT_INTERVAL="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: bash ./run-supervised.sh [-f|--no-frontend] [-r|--rt-diagnostics] [--rt-interval SECONDS]"
      exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

if [[ "$RT_DIAGNOSTICS" == true ]]; then
  exec bash "$REPO_ROOT/rt-logs.sh" --rt-interval "$RT_INTERVAL"
fi

[[ -f "$CONFIG" ]] || { echo "Config not found: $CONFIG" >&2; exit 1; }
[[ -f "$ORCHESTRATOR_JAR" ]] || { echo "Orchestrator JAR missing: $ORCHESTRATOR_JAR" >&2; exit 1; }
command -v java >/dev/null || { echo "Java is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "Python 3 is required to stop managed services" >&2; exit 1; }
if [[ "${IML_STACK_ALREADY_STOPPED:-0}" != 1 ]]; then
  echo "==> Stop previous application stack"
  bash "$REPO_ROOT/stop-dev.sh" --skip-kiosk
fi
unset IML_STACK_ALREADY_STOPPED

export IML_LOG_SESSION_DIR="$REPO_ROOT/logs/$(date +%Y-%m-%d_%H-%M-%S-%N)-$$"
mkdir -p "$IML_LOG_SESSION_DIR"
if [[ "$NO_FRONTEND" == true ]]; then export IML_FRONTEND_AUTOSTART=false; fi
if [[ "$NO_FRONTEND" == false && -f "$REPO_ROOT/.kiosk-paused.json" ]]; then
  # An installed kiosk owns the UI; do not start a second Electron instance.
  export IML_FRONTEND_AUTOSTART=false
  bash "$REPO_ROOT/stop-dev.sh" --resume-kiosk
fi
export IML_ORCHESTRATOR_JAR="$ORCHESTRATOR_JAR"
supervisor_pid=""
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  echo "==> Stop application stack"
  bash "$REPO_ROOT/stop-dev.sh" --processes-only --skip-kiosk || status=1
  if [[ -n "$supervisor_pid" ]]; then wait "$supervisor_pid" 2>/dev/null || true; fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "Starting stack supervisor. Ctrl+C = stop all."
echo "Session logs: $IML_LOG_SESSION_DIR"
echo "Orchestrator health: http://127.0.0.1:8099/health"
java -cp "$ORCHESTRATOR_JAR" com.example.iml.orchestrator.supervisor.StackSupervisorMain "$CONFIG" &
supervisor_pid=$!
wait "$supervisor_pid"
