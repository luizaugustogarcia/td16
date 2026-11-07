#!/usr/bin/env bash
# Log resource use without reading process command lines or other services' arguments.
set -euo pipefail
if [[ $# -lt 1 || $# -gt 2 || ! "$1" =~ ^[0-9]+$ ]]; then
  printf 'Usage: bash %s JAVA_PID [DATA_DIRECTORY]\n' "$0" >&2
  exit 2
fi
task_pid=$1
task_repo=$(cd "$(dirname "$0")/../../.." && pwd)
task_data=${2:-$task_repo/experiments/data}
while kill -0 "$task_pid" 2>/dev/null; do
  date -u '+RESOURCE_TIME %Y-%m-%dT%H:%M:%SZ'
  ps -p "$task_pid" -o pid=,comm=,pcpu=,pmem=,rss=,nlwp=,etime=
  nvidia-smi --query-gpu=index,utilization.gpu,utilization.memory,memory.used,memory.total,power.draw,power.limit,temperature.gpu --format=csv,noheader,nounits
  for task_metric in cpu.max cpu.stat memory.max memory.current memory.events io.stat; do
    if [[ -r "/sys/fs/cgroup/$task_metric" ]]; then
      printf 'CGROUP %s\n' "$task_metric"
      cat "/sys/fs/cgroup/$task_metric"
    fi
  done
  if [[ -r /proc/$task_pid/io ]]; then
    printf 'PROCESS_IO\n'
    cat "/proc/$task_pid/io"
  fi
  df -B1 "$task_data"
  sleep 10
done
