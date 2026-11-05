#!/usr/bin/env bash
# Start an already-built, fresh production run independently of the SSH session.
set -euo pipefail
task_repo=$(cd "$(dirname "$0")/../../.." && pwd)
task_profile=${1:-quad-3090.sh}
task_profile="$task_repo/experiments/scripts/servers/$task_profile"
test -f "$task_profile"
cd "$task_repo"
mkdir -p experiments/logs experiments/profiles
test ! -e experiments/data/search
test ! -e experiments/logs/extension.log
nohup setsid bash "$task_profile" > experiments/logs/launcher.log 2>&1 < /dev/null &
task_launch_pid=$!
task_java_pid=
for task_attempt in {1..60}; do
  if [[ -f experiments/logs/extension.log ]]; then
    task_java_pid=$(sed -nE 's/^RUN PID=([0-9]+).*/\1/p' experiments/logs/extension.log | head -n 1)
    if [[ -n "$task_java_pid" ]]; then break; fi
  fi
  if ! kill -0 "$task_launch_pid" 2>/dev/null; then
    cat experiments/logs/launcher.log
    exit 1
  fi
  sleep 0.5
done
if [[ -z "$task_java_pid" ]]; then
  printf 'Application did not start; inspect experiments/logs/launcher.log\n' >&2
  exit 1
fi
kill -0 "$task_java_pid"
echo "$task_java_pid" > experiments/logs/extension.pid
nohup setsid bash experiments/scripts/servers/monitor.sh "$task_java_pid" \
  > experiments/logs/resources.log 2>&1 < /dev/null &
echo "$!" > experiments/logs/monitor.pid
find gpu-common/src experiments/src emitter/src/native -type f -print0 | sort -z | xargs -0 sha256sum > experiments/logs/source-sha256.txt
sha256sum experiments/target/native/*ptx > experiments/logs/ptx-sha256.txt
printf 'SEARCH_PID=%s\n' "$task_java_pid"
