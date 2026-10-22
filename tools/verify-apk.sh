#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
exec "$task_root/tools/dev.sh" python3 "$task_root/tools/verify-apk.py" "$@"
