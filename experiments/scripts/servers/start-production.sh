#!/usr/bin/env bash
# Start an already-built, fresh production run independently of the SSH session.
set -euo pipefail
task_repo=$(cd "$(dirname "$0")/../../.." && pwd)
task_profile=${1:-quad-3090.sh}
if [[ $# -gt 0 ]]; then shift; fi
task_resume=false
if [[ ${1:-} == --resume ]]; then task_resume=true; shift; fi
task_default_build=td16-isolated-build
if [[ $task_profile == rtx5090.sh ]]; then
  : "${TDP_DATA_DIR:?Set TDP_DATA_DIR to a directory on the attached 256 GB volume}"
  task_default_build=td16-rtx5090-build
fi
task_build_root=${TDP_EXPERIMENT_BUILD_ROOT:-${TMPDIR:-/tmp}/$task_default_build}
task_data=${TDP_DATA_DIR:-$task_repo/experiments/data/search}
task_logs=${TDP_LOG_DIR:-$task_repo/experiments/logs}
export TDP_EXPERIMENT_BUILD_ROOT="$task_build_root" TDP_DATA_DIR="$task_data" TDP_LOG_DIR="$task_logs"
task_profile="$task_repo/experiments/scripts/servers/$task_profile"
test -f "$task_profile"
cd "$task_repo"
mkdir -p "$task_logs" experiments/profiles
if [[ $task_resume == false ]]; then
  test ! -e "$task_data"
  test ! -e "$task_logs/extension.log"
else test -d "$task_data"; fi
if [[ -s $task_logs/extension.pid ]]; then
  read -r task_previous_pid < "$task_logs/extension.pid"
  if [[ $task_previous_pid =~ ^[0-9]+$ ]] && kill -0 "$task_previous_pid" 2>/dev/null; then
    printf 'A run is already active with PID %s.\n' "$task_previous_pid" >&2; exit 1
  fi
fi
task_first_line=1
if [[ -f $task_logs/extension.log ]]; then task_first_line=$(( $(wc -l < "$task_logs/extension.log") + 1 )); fi
nohup setsid bash "$task_profile" --data-dir "$task_data" --log-file "$task_logs/extension.log" "$@" > "$task_logs/launcher.log" 2>&1 < /dev/null &
task_launch_pid=$!
task_java_pid=
for task_attempt in {1..60}; do
  if [[ -f $task_logs/extension.log ]]; then
    task_java_pid=$(tail -n +"$task_first_line" "$task_logs/extension.log" | sed -nE 's/^RUN PID=([0-9]+).*/\1/p' | head -n 1)
    if [[ -n "$task_java_pid" ]]; then break; fi
  fi
  if ! kill -0 "$task_launch_pid" 2>/dev/null; then
    cat "$task_logs/launcher.log"
    exit 1
  fi
  sleep 0.5
done
if [[ -z "$task_java_pid" ]]; then
  printf 'Application did not start; inspect %s/launcher.log\n' "$task_logs" >&2
  exit 1
fi
kill -0 "$task_java_pid"
echo "$task_java_pid" > "$task_logs/extension.pid"
nohup setsid bash experiments/scripts/servers/monitor.sh "$task_java_pid" "$task_data" \
  > "$task_logs/resources.log" 2>&1 < /dev/null &
echo "$!" > "$task_logs/monitor.pid"
find gpu-common/src experiments/src emitter/src/native -type f -print0 | sort -z | xargs -0 sha256sum > "$task_logs/source-sha256.txt"
sha256sum "$task_build_root"/experiments/native/*ptx > "$task_logs/ptx-sha256.txt"
printf 'SEARCH_PID=%s\n' "$task_java_pid"
