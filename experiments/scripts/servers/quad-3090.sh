#!/usr/bin/env bash
# Production profile: 4 x 24 GiB RTX 3090, 184 CPU quota, ~242 GiB RAM.
# Build with -Pcuda-native first. Extra run.sh options override this profile.
set -euo pipefail
task_repo=$(cd "$(dirname "$0")/../../.." && pwd)
mkdir -p "$task_repo/experiments/logs"
export JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS:-"-Xms8g -Xmx128g -XX:ActiveProcessorCount=184 -XX:+UseG1GC -XX:+UseNUMA -XX:ParallelGCThreads=64 -XX:ConcGCThreads=16 -XX:+ExitOnOutOfMemoryError -Xlog:gc:file=$task_repo/experiments/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=32M"}
exec bash "$task_repo/experiments/run.sh" diameter --no-build \
  --data-dir "$task_repo/experiments/data/search" --max-n 18 --pattern-horizon 17 \
  --cpu-threads 184 --batch-records 32768 \
  --write-buffer-mib 49152 --write-aggregate-mib 4096 \
  --write-native-batch-mib 8 --write-flush-ms 5000 \
  --rocks-write-threads 96 --rocks-background-jobs 48 --rocks-flush-threads 16 \
  --rocks-memtable-mib 2048 --rocks-sst-mib 2048 --rocks-cache-mib 16384 \
  --gpu-devices 0,1,2,3 --gpu-slots 8 --gpu-slot-mib 0 --vram-fraction 0.95 \
  --gpu-batch-records 2097152 --gpu-queue 128 --gpu-parent-batch 8192 \
  --gpu-quantum 4096 \
  --cuda-ptx-dir "$task_repo/experiments/target/native" \
  --log-file "$task_repo/experiments/logs/extension.log" "$@"
