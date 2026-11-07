#!/usr/bin/env bash
# RTX 5090 32 GiB, Threadripper 3990X (128 logical CPUs), 256 GB RAM.
# Build first with rtx5090-build.sh. TDP_DATA_DIR selects the attached volume.
# Completed layers are reused; options after the profile override its defaults.
set -euo pipefail
task_repo=$(cd "$(dirname "$0")/../../.." && pwd)
task_build_root=${TDP_EXPERIMENT_BUILD_ROOT:-${TMPDIR:-/tmp}/td16-rtx5090-build}
task_cpus=${TDP_CPU_THREADS:-$(nproc)}
task_logs=${TDP_LOG_DIR:-$task_repo/experiments/logs}
task_data=${TDP_DATA_DIR:?Set TDP_DATA_DIR to a directory on the attached 256 GB volume}
task_write_mib=${TDP_WRITE_BUFFER_MIB:-32768}
task_aggregate_mib=${TDP_WRITE_AGGREGATE_MIB:-4096}
task_native_mib=${TDP_WRITE_NATIVE_BATCH_MIB:-8}
task_write_flush_ms=${TDP_WRITE_FLUSH_MS:-2000}
task_memtable_mib=${TDP_ROCKS_MEMTABLE_MIB:-1024}
task_sst_mib=${TDP_ROCKS_SST_MIB:-1024}
task_parent_batch=${TDP_GPU_PARENT_BATCH:-4096}
task_dry_run=false
task_extra=()
for task_argument in "$@"; do
  if [[ $task_argument == --dry-run ]]; then task_dry_run=true
  else task_extra+=("$task_argument"); fi
done
if [[ ! $task_cpus =~ ^[1-9][0-9]*$ ]]; then
  printf 'TDP_CPU_THREADS must be positive.\n' >&2; exit 2
fi
task_background=$((task_cpus / 2)); if ((task_background < 2)); then task_background=2; fi
task_flush=$((task_background / 4)); if ((task_flush < 1)); then task_flush=1; fi
task_command=(bash "$task_repo/experiments/run.sh" diameter --no-build
  --data-dir "$task_data" --max-n 18 --pattern-horizon 17
  --cpu-threads "$task_cpus" --batch-records 65536
  --write-buffer-mib "$task_write_mib" --write-aggregate-mib "$task_aggregate_mib"
  --write-native-batch-mib "$task_native_mib" --write-flush-ms "$task_write_flush_ms"
  --rocks-write-threads "$task_cpus" --rocks-background-jobs "$task_background"
  --rocks-flush-threads "$task_flush" --rocks-subcompactions "$task_background"
  --rocks-memtable-mib "$task_memtable_mib" --rocks-max-memtables 12
  --rocks-sst-mib "$task_sst_mib" --rocks-cache-mib 8192 --rocks-max-open-files -1
  --gpu-devices 0 --gpu-slots 16 --gpu-generation-streams 2 --gpu-slot-mib 0 --vram-fraction 0.95
  --gpu-batch-records 524288 --gpu-queue 256 --gpu-parent-batch "$task_parent_batch"
  --gpu-producer-batch 8192 --gpu-min-batch-records 32768
  --gpu-quantum 16384 --lookup-chunk 1048576
  --cuda-ptx-dir "$task_build_root/experiments/native"
  --log-file "$task_logs/extension.log" "${task_extra[@]}")
if [[ $task_dry_run == true ]]; then
  printf 'CPU_THREADS=%s GPU_SLOTS=16 GPU_GENERATION_STREAMS=2 HEAP_GiB=128 WRITE_STAGING_MiB=%s\n' "$task_cpus" "$task_write_mib"
  printf 'ROCKS_BACKGROUND=%s ROCKS_FLUSH=%s ROCKS_SUBCOMPACTIONS=%s\n' "$task_background" "$task_flush" "$task_background"
  printf 'BUILD_ROOT=%s\nDATA_DIR=%s\nLOG_DIR=%s\n' "$task_build_root" "$task_data" "$task_logs"
  printf 'COMMAND'; printf ' %q' "${task_command[@]}"; printf '\n'
  exit 0
fi
task_gpu=$(nvidia-smi --query-gpu=name --format=csv,noheader,nounits -i 0 | tr -d '\r')
if [[ $task_gpu != *'RTX 5090'* ]]; then
  printf 'This profile requires RTX 5090 on GPU 0; found: %s\n' "$task_gpu" >&2; exit 1
fi
if [[ ! -s $task_build_root/experiments/experiments-1.0.jar ]]; then
  printf 'Build the experiment first with rtx5090-build.sh.\n' >&2; exit 1
fi
for task_kernel in diametral-filter streaming-count-soa streaming-count-aos emitter-delta-audit; do
  if [[ ! -s $task_build_root/experiments/native/$task_kernel-compute_120.ptx ]]; then
    printf 'Build native Blackwell kernels first with rtx5090-build.sh.\n' >&2; exit 1
  fi
done
mkdir -p "$task_data" "$task_logs"
ulimit -S -n "$(ulimit -H -n)"
exec 9>"$task_data/.run.lock"
if ! flock -n 9; then printf 'A run already owns %s.\n' "$task_data" >&2; exit 1; fi
export TDP_EXPERIMENT_BUILD_ROOT="$task_build_root"
export JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS:-"-Xms8g -Xmx128g -XX:ActiveProcessorCount=$task_cpus -XX:+UseG1GC -XX:+UseNUMA -XX:+ExitOnOutOfMemoryError -Xlog:gc:file=\"$task_logs/gc.log\":time,uptime,level,tags:filecount=5,filesize=32M"}
# Use JVM-selected GC concurrency; CPU and archive concurrency follow the detected allocation.
# Parent batches bound transient host fallback packets while filling many SMs.
{
  date -u '+START_UTC=%Y-%m-%dT%H:%M:%SZ'
  printf 'CPU_THREADS=%s\nDATA_DIR=%s\nBUILD_ROOT=%s\n' "$task_cpus" "$task_data" "$task_build_root"
  lscpu
  free -h
  df -h "$task_data"
  nvidia-smi
} > "$task_logs/machine.txt"
exec "${task_command[@]}"
