#!/usr/bin/env bash
# NOVA FCT / UNL, Concurso-GPU-16_2026_DL60_DII.pdf (pages 2, 4 and 7):
# H200 NVL 141 GB, 86 CPU cores, 512 GB RAM and two 3.84 TB NVMe SSDs.
# Build and run TD(18) with no arguments; completed exact sets are reused.
set -euo pipefail
task_repo=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$task_repo"
mkdir -p experiments/logs

# Build before setting the experiment's large heap and GC options so Maven
# keeps its normal memory settings. CMake detects the visible CUDA architecture.
printf 'Building the experiment and native CUDA kernels...\n'
mvn -q -pl experiments -am -Pcuda-native -DskipTests package dependency:copy-dependencies

# Leave RAM outside the heap for RocksDB (including its per-store caches),
# pinned CUDA buffers and the filesystem cache. Use the stated 86 cores;
# the processor description's "2P" does not establish two installed CPUs.
export JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS:-"-Xms16g -Xmx256g -XX:ActiveProcessorCount=86 -XX:+UseG1GC -XX:+UseNUMA -XX:ParallelGCThreads=32 -XX:ConcGCThreads=8 -XX:+ExitOnOutOfMemoryError -Xlog:gc:file=\"$task_repo/experiments/logs/gc.log\":time,uptime,level,tags:filecount=5,filesize=32M"}
printf 'Running TD(18). Data: %s/experiments/data/search\nLog: %s/experiments/logs/extension.log\n' "$task_repo" "$task_repo"
exec bash "$task_repo/experiments/run.sh" diameter --no-build \
  --data-dir "$task_repo/experiments/data/search" --max-n 18 --pattern-horizon 17 \
  --cpu-threads 86 --batch-records 32768 \
  --write-buffer-mib 98304 --write-aggregate-mib 8192 \
  --write-native-batch-mib 8 --write-flush-ms 5000 \
  --rocks-write-threads 64 --rocks-background-jobs 32 --rocks-flush-threads 8 \
  --rocks-memtable-mib 2048 --rocks-sst-mib 2048 --rocks-cache-mib 16384 \
  --gpu-devices 0 --gpu-slots 16 --gpu-slot-mib 0 --vram-fraction 0.90 \
  --gpu-batch-records 2097152 --gpu-queue 128 --gpu-parent-batch 8192 \
  --gpu-quantum 4096 \
  --cuda-ptx-dir "$task_repo/experiments/target/native" \
  --log-file "$task_repo/experiments/logs/extension.log" "$@"
