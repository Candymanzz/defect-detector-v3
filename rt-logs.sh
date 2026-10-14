#!/usr/bin/env bash
# Standalone observation only: never start, stop, or reconfigure application services.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INTERVAL="${IML_RT_DIAGNOSTICS_INTERVAL:-1}"
OUTPUT=""
while (($#)); do
  case "$1" in
    -r|--rt-diagnostics|-f|--no-frontend) shift ;;
    --rt-interval|--log-dir)
      [[ $# -ge 2 && -n "$2" && "$2" != -* ]] || { echo "$1 requires a value" >&2; exit 1; }
      if [[ "$1" == --rt-interval ]]; then INTERVAL="$2"; else OUTPUT="$2"; fi
      shift 2 ;;
    -h|--help)
      echo "Usage: bash ./rt-logs.sh [--rt-interval SECONDS] [--log-dir PATH]"
      echo "Log system and existing service metrics only. Ctrl+C stops only the collector."
      exit 0 ;;
    *) echo "Unknown diagnostic option: $1" >&2; exit 1 ;;
  esac
done
command -v python3 >/dev/null || { echo "Python 3 is required for diagnostics" >&2; exit 1; }
python3 -c 'import math,sys; v=float(sys.argv[1]); sys.exit(0 if math.isfinite(v) and 0.2 <= v <= 60 else "RT interval must be 0.2 to 60 seconds")' "$INTERVAL"
if [[ -z "$OUTPUT" ]]; then OUTPUT="$REPO_ROOT/logs/$(date +%Y-%m-%d_%H-%M-%S-%N)-rt-$$"; fi
mkdir -p "$OUTPUT"
echo "System diagnostics only; existing application services are unchanged."
echo "Logs: $OUTPUT"
echo "Interval: $INTERVAL seconds. Ctrl+C stops only logging."
exec python3 "$REPO_ROOT/deploy/linux/rt_diagnostics.py" --root "$REPO_ROOT" \
  --output "$OUTPUT" --interval "$INTERVAL" >> "$OUTPUT/rt-monitor.log" 2>&1
