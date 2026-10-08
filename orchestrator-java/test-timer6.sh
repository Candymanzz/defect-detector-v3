#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$TASK_ROOT"
JAVA_BIN="$TASK_ROOT/tools/java/bin/java"
if [[ ! -x "$JAVA_BIN" ]]; then JAVA_BIN=java; fi
JAR="$TASK_ROOT/orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar"
if [[ ! -f "$JAR" ]]; then
  echo "Build orchestrator-java first; see orchestrator-java/MVS-IO.md" >&2
  exit 1
fi
if [[ $# -eq 0 ]]; then set -- --config config.exemple/config.yaml --group 0; fi
exec "$JAVA_BIN" -Diml.log.dir=/tmp/mvs-timer-smoke -cp "$JAR" \
  com.example.iml.orchestrator.integration.io.mvs.MvsTimerSmokeMain "$@"
