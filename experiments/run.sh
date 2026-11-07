#!/usr/bin/env bash
set -euo pipefail
task_repo=$(cd "$(dirname "$0")/.." && pwd)
cd "$task_repo"
task_mode=audit
task_build=true
task_log_file=
task_native=false
task_build_root=${TDP_EXPERIMENT_BUILD_ROOT:-${TMPDIR:-/tmp}/td16-isolated-build}
task_options=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    audit|density-audit|gpu-benchmark|gpu-audit|search-benchmark|count|diameter|extend|extension-plan|extension-benchmark|verify-store|verify-membership|sample-membership|classify-sample|audit-generation|scan-count) task_mode=$1; shift ;;
    --no-build) task_build=false; shift ;;
    --max-n) task_options+=("-Dcount.maxN=$2" "-Daudit.maxN=$2"); shift 2 ;;
    --cpu-threads) task_options+=("-Dcount.cpuThreads=$2"); shift 2 ;;
    --vram-fraction) task_options+=("-Dcount.vramFraction=$2"); shift 2 ;;
    --no-gpu) task_options+=("-Dcount.gpu=false"); shift ;;
    --data-dir) task_options+=("-Dextension.dataDir=$2"); shift 2 ;;
    --through-n) task_options+=("-Dextension.through=$2"); shift 2 ;;
    --pattern-horizon) task_options+=("-Dextension.patternHorizon=$2"); shift 2 ;;
    --base-degree) task_options+=("-Dextension.baseDegree=$2"); shift 2 ;;
    --density-max-records) task_options+=("-Ddensity.maxRecords=$2"); shift 2 ;;
    --no-compact-cache) task_options+=("-Dextension.compactSets=false"); shift ;;
    --compact-keep-rocks) task_options+=("-Dextension.compactKeepRocksDb=true"); shift ;;
    --compact-build-threads) task_options+=("-Dextension.compactBuildThreads=$2"); shift 2 ;;
    --compact-parallel-threshold) task_options+=("-Dextension.compactParallelThreshold=$2"); shift 2 ;;
    --gpu-dense-max-degree) task_options+=("-Dextension.gpuDenseMaxDegree=$2"); shift 2 ;;
    --gpu-compact-min-degree) task_options+=("-Dextension.gpuCompactMinDegree=$2"); shift 2 ;;
    --batch-records) task_options+=("-Dextension.batchRecords=$2"); shift 2 ;;
    --write-queue) task_options+=("-Dextension.writeQueueBatches=$2"); shift 2 ;;
    --write-buffer-mib) task_options+=("-Dextension.writeBufferMiB=$2"); shift 2 ;;
    --write-aggregate-mib) task_options+=("-Dextension.writeAggregateMiB=$2"); shift 2 ;;
    --write-flush-ms) task_options+=("-Dextension.writeFlushMillis=$2"); shift 2 ;;
    --write-native-batch-mib) task_options+=("-Dextension.writeNativeBatchMiB=$2"); shift 2 ;;
    --gpu-batch-records) task_options+=("-Dextension.gpuBatchRecords=$2"); shift 2 ;;
    --gpu-producer-batch) task_options+=("-Dextension.gpuProducerBatchRecords=$2"); shift 2 ;;
    --gpu-min-batch-records) task_options+=("-Dextension.gpuMinBatchRecords=$2"); shift 2 ;;
    --gpu-queue) task_options+=("-Dextension.gpuQueueBatches=$2"); shift 2 ;;
    --gpu-slots) task_options+=("-Dextension.gpuSlots=$2"); shift 2 ;;
    --gpu-generation-streams) task_options+=("-Dextension.gpuGenerationStreams=$2"); shift 2 ;;
    --gpu-devices) task_options+=("-Dextension.gpuDevices=$2"); shift 2 ;;
    --gpu-slot-mib) task_options+=("-Dextension.gpuSlotMiB=$2"); shift 2 ;;
    --gpu-membership-degree) task_options+=("-Dextension.gpuMembershipDegree=$2"); shift 2 ;;
    --gpu-pattern-degree) task_options+=("-Dextension.gpuPatternMaxDegree=$2"); shift 2 ;;
    --cuda-ptx-dir) task_options+=("-Dextension.cudaPtxDir=$2"); task_native=true; shift 2 ;;
    --gpu-quantum) task_options+=("-Dextension.gpuQuantum=$2"); shift 2 ;;
    --gpu-parent-batch) task_options+=("-Dextension.gpuParentBatch=$2"); shift 2 ;;
    --lookup-chunk) task_options+=("-Dextension.lookupChunk=$2"); shift 2 ;;
    --benchmark-n) task_options+=("-Dbenchmark.n=$2"); shift 2 ;;
    --benchmark-distance) task_options+=("-Dbenchmark.distance=$2"); shift 2 ;;
    --benchmark-budget) task_options+=("-Dbenchmark.budget=$2"); shift 2 ;;
    --benchmark-jobs) task_options+=("-Dbenchmark.jobs=$2"); shift 2 ;;
    --benchmark-repeats) task_options+=("-Dbenchmark.repeats=$2"); shift 2 ;;
    --benchmark-table-degree) task_options+=("-Dbenchmark.tableDegree=$2"); shift 2 ;;
    --benchmark-deletion-distance) task_options+=("-Dbenchmark.deletionDistance=$2"); shift 2 ;;
    --benchmark-skip) task_options+=("-Dbenchmark.skip=$2"); shift 2 ;;
    --benchmark-parents) task_options+=("-Dbenchmark.parents=$2"); shift 2 ;;
    --verify-n) task_options+=("-Dverify.n=$2"); shift 2 ;;
    --rocks-write-threads) task_options+=("-Drocks.writeThreads=$2"); shift 2 ;;
    --rocks-background-jobs) task_options+=("-Drocks.backgroundJobs=$2"); shift 2 ;;
    --rocks-flush-threads) task_options+=("-Drocks.flushThreads=$2"); shift 2 ;;
    --rocks-subcompactions) task_options+=("-Drocks.subcompactions=$2"); shift 2 ;;
    --rocks-max-memtables) task_options+=("-Drocks.maxMemtables=$2"); shift 2 ;;
    --rocks-max-open-files) task_options+=("-Drocks.maxOpenFiles=$2"); shift 2 ;;
    --rocks-memtable-mib) task_options+=("-Drocks.memtableMiB=$2"); shift 2 ;;
    --rocks-sst-mib) task_options+=("-Drocks.sstMiB=$2"); shift 2 ;;
    --rocks-cache-mib) task_options+=("-Drocks.cacheMiB=$2"); shift 2 ;;
    --log-file) task_log_file=$2; shift 2 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done
if [[ -n "$task_log_file" ]]; then
  mkdir -p "$(dirname "$task_log_file")"
  exec >>"$task_log_file" 2>&1
  printf 'RUN PID=%s mode=%s\n' "$$" "$task_mode"
fi
if [[ "$task_build" == true ]]; then
  task_profiles=isolated-build
  if [[ "$task_native" == true ]]; then task_profiles+=,cuda-native; fi
  mvn -q -pl experiments -am -P"$task_profiles" -Dtdp.isolatedBuildRoot="$task_build_root" -DskipTests package dependency:copy-dependencies
fi
# CUDA/NVRTC selection belongs to the calling environment. Server launchers
# may discover and export GPU_NVRTC_LIB, but this generic runner never embeds
# a toolkit version or silently substitutes a different installation.
task_library_path=${LD_LIBRARY_PATH:-}
if [[ -n ${GPU_NVRTC_LIB:-} ]]; then
  task_library_path="$GPU_NVRTC_LIB${task_library_path:+:$task_library_path}"
fi
if [[ -d /usr/lib/wsl/lib ]]; then
  task_library_path="/usr/lib/wsl/lib${task_library_path:+:$task_library_path}"
fi
export LD_LIBRARY_PATH="$task_library_path"
exec java --enable-native-access=ALL-UNNAMED "${task_options[@]}" -cp "$task_build_root/experiments/experiments-1.0.jar:$task_build_root/experiments/dependency/*" \
  br.unb.cic.tdp.experiments.ExperimentMain "$task_mode"
