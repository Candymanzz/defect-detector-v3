#!/usr/bin/env bash
# Supervised launch: external JVM monitors orchestrator, restarts on crash/OOM/kill.
# Usage:
#   ./run-supervised.sh
#   ./run-supervised.sh --no-frontend
# Stop: Ctrl+C

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

CONFIG="${CONFIG:-config/config.yaml}"
ORCHESTRATOR_JAR="$REPO_ROOT/orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar"
NO_FRONTEND=false

for arg in "$@"; do
  case "$arg" in
    --no-frontend) NO_FRONTEND=true ;;
  esac
done

echo "==> Cleanup stale processes and ports"
if [[ -x "$REPO_ROOT/stop-dev.sh" ]]; then
  "$REPO_ROOT/stop-dev.sh" || true
else
  for port in $(seq 8000 8009) 8099 8765 5173 5079 5080 8088; do
    fuser -k "${port}/tcp" 2>/dev/null || true
  done
fi

if [[ ! -f "$CONFIG" ]]; then
  echo "Config not found: $CONFIG" >&2
  exit 1
fi
if [[ ! -f "$ORCHESTRATOR_JAR" ]]; then
  echo "Orchestrator JAR missing: $ORCHESTRATOR_JAR" >&2
  exit 1
fi

if [[ "$NO_FRONTEND" == true ]]; then
  export IML_FRONTEND_AUTOSTART=false
fi
export IML_ORCHESTRATOR_JAR="$ORCHESTRATOR_JAR"

cleanup() {
  echo "==> Cleanup"
  if [[ -x "$REPO_ROOT/stop-dev.sh" ]]; then
    "$REPO_ROOT/stop-dev.sh" || true
  fi
}
trap cleanup EXIT INT TERM

echo ""
echo "Starting stack supervisor (auto-restart on crash). Ctrl+C = stop all."
echo "  Orchestrator health: http://127.0.0.1:8099/health"
echo ""

exec java -cp "$ORCHESTRATOR_JAR" com.example.iml.orchestrator.supervisor.StackSupervisorMain "$CONFIG"
