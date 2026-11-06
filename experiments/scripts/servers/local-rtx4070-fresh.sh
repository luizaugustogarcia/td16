#!/usr/bin/env bash
# Fresh local TD(18) profile for this workstation:
# RTX 4070 Laptop GPU (8 GiB), Core Ultra 9 185H (22 threads), 32 GiB RAM.
#
# This launcher intentionally deletes all prior experiment data and logs. It
# takes no arguments so that the clean-run profile cannot be altered silently.
set -euo pipefail

if [[ $# -ne 0 ]]; then
  printf 'Usage: %s (no arguments)\n' "$0" >&2
  exit 2
fi

task_repo=$(cd "$(dirname "$0")/../../.." && pwd)
task_data_root="$task_repo/experiments/data"
task_data="$task_data_root/search"
task_log_root="$task_repo/experiments/logs"
task_log="$task_log_root/extension.log"
task_native="$task_repo/experiments/target/native"
cd "$task_repo"

# Do not erase a live run. The bracketed pattern prevents pgrep from matching
# its own command line.
if pgrep -f '[b]r\.unb\.cic\.tdp\.experiments\.ExperimentMain (diameter|count|extend)' >/dev/null; then
  printf 'An experiment JVM is already running; refusing to delete its data.\n' >&2
  exit 1
fi

task_gpu_name=$(nvidia-smi --query-gpu=name --format=csv,noheader,nounits -i 0 | tr -d '\r')
task_gpu_free=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits -i 0 | tr -dc '0-9')
if [[ "$task_gpu_name" != *"RTX 4070 Laptop GPU"* ]]; then
  printf 'This profile requires GPU 0 to be an RTX 4070 Laptop GPU; found: %s\n' "$task_gpu_name" >&2
  exit 1
fi
if [[ -z "$task_gpu_free" || "$task_gpu_free" -lt 6500 ]]; then
  if [[ -z "$task_gpu_free" ]]; then task_gpu_free=unknown; fi
  printf 'GPU 0 needs at least 6500 MiB free before launch; currently free: %s MiB\n' "$task_gpu_free" >&2
  exit 1
fi

task_free_bytes=$(df -PB1 "$task_repo" | awk 'NR==2 {print $4}')
if [[ ! "$task_free_bytes" =~ ^[0-9]+$ || "$task_free_bytes" -lt 536870912000 ]]; then
  printf 'The fresh run requires at least 500 GiB free on the workspace filesystem.\n' >&2
  exit 1
fi

# Obtain the build toolkit from the active environment, never from a versioned
# path in this script. If the shell does not select it explicitly, use the
# system's active CUDA alternative. CMake receives the same compiler selection.
task_nvcc=
if [[ -n ${CUDACXX:-} ]]; then
  task_nvcc=$(command -v -- "$CUDACXX" 2>/dev/null || true)
elif [[ -n ${CUDA_HOME:-} && -x $CUDA_HOME/bin/nvcc ]]; then
  task_nvcc=$CUDA_HOME/bin/nvcc
elif [[ -n ${CUDA_PATH:-} && -x $CUDA_PATH/bin/nvcc ]]; then
  task_nvcc=$CUDA_PATH/bin/nvcc
elif command -v nvcc >/dev/null 2>&1; then
  task_nvcc=$(command -v nvcc)
elif command -v update-alternatives >/dev/null 2>&1; then
  task_cuda_alternative=$(update-alternatives --query cuda 2>/dev/null |
    sed -n 's/^Value: //p' | head -n 1)
  if [[ -n $task_cuda_alternative && -x $task_cuda_alternative/bin/nvcc ]]; then
    task_nvcc=$task_cuda_alternative/bin/nvcc
  fi
fi
if [[ ! -x "$task_nvcc" ]]; then
  printf 'No CUDA compiler was selected by CUDACXX, CUDA_HOME, CUDA_PATH, PATH or the active system alternative.\n' >&2
  exit 1
fi
task_nvcc=$(readlink -f "$task_nvcc")
task_cuda_root=$(dirname "$(dirname "$task_nvcc")")
export CUDACXX="$task_nvcc"
export PATH="$(dirname "$task_nvcc"):$PATH"
task_cuda_version=$("$task_nvcc" --version | sed -nE 's/.*release ([0-9.]+).*/\1/p' | tail -n 1)
if [[ -z "$task_cuda_version" ]]; then
  printf 'Could not determine the CUDA toolkit version from %s.\n' "$task_nvcc" >&2
  exit 1
fi

# JCuda 12 loads libnvrtc.so.12. Prefer GPU_NVRTC_LIB when supplied. Otherwise
# discover the newest installed NVRTC ABI-12 library supported by the driver.
# The version is read from the library target, not encoded in this script.
task_driver_cuda=$(nvidia-smi | sed -nE 's/.*CUDA (UMD )?Version: *([0-9]+(\.[0-9]+)?).*/\2/p' | head -n 1)
task_nvrtc=${GPU_NVRTC_LIB:-}
if [[ -z "$task_nvrtc" ]]; then
  task_nvrtc_version=
  task_nvrtc_roots=("$task_cuda_root" "$task_repo/../new-td/build/gpu/cuda/usr/local")
  while IFS= read -r task_nvrtc_link; do
    task_candidate=$(dirname "$task_nvrtc_link")
    task_candidate_version=$(readlink -f "$task_nvrtc_link" |
      sed -nE 's/.*libnvrtc\.so\.([0-9]+\.[0-9]+).*/\1/p')
    [[ -n "$task_candidate_version" ]] || continue
    if [[ -n "$task_driver_cuda" &&
          $(printf '%s\n%s\n' "$task_candidate_version" "$task_driver_cuda" | sort -V | tail -n 1) != "$task_driver_cuda" ]]; then
      continue
    fi
    if [[ -z "$task_nvrtc_version" ||
          $(printf '%s\n%s\n' "$task_candidate_version" "$task_nvrtc_version" | sort -V | tail -n 1) == "$task_candidate_version" ]]; then
      task_nvrtc="$task_candidate"
      task_nvrtc_version="$task_candidate_version"
    fi
  done < <(find "${task_nvrtc_roots[@]}" -type l -name 'libnvrtc.so.12' -print 2>/dev/null)
fi
if [[ -z "$task_nvrtc" || ! -r "$task_nvrtc/libnvrtc.so.12" ||
      -z $(find "$task_nvrtc" -maxdepth 1 -name 'libnvrtc-builtins.so*' -print -quit) ]]; then
  printf 'No driver-compatible NVRTC ABI-12 library was found through the CUDA environment.\n' >&2
  exit 1
fi
export GPU_NVRTC_LIB="$task_nvrtc"
task_nvrtc_version=$(readlink -f "$GPU_NVRTC_LIB/libnvrtc.so.12" |
  sed -nE 's/.*libnvrtc\.so\.([0-9]+\.[0-9]+).*/\1/p')

printf 'Building clean Ada (compute_89) and Blackwell (compute_120) PTX with CUDA %s...\n' "$task_cuda_version"
mvn -q -pl experiments -am -Pcuda-native -Dtdp.cudaArchitectures='89;120' \
  -DskipTests clean package dependency:copy-dependencies

# These two directories are the documented runtime-only locations. Validate
# the resolved paths before the requested destructive cleanup.
if [[ "$task_data_root" != "$task_repo/experiments/data" ||
      "$task_log_root" != "$task_repo/experiments/logs" ]]; then
  printf 'Refusing cleanup because a runtime path resolved unexpectedly.\n' >&2
  exit 1
fi
printf 'Deleting previous run data: %s\nDeleting previous run logs: %s\n' \
  "$task_data_root" "$task_log_root"
rm -rf -- "$task_data_root" "$task_log_root"
mkdir -p "$task_data" "$task_log_root"

{
  date -u '+START_UTC=%Y-%m-%dT%H:%M:%SZ'
  uname -a
  java -version 2>&1
  mvn -version
  printf 'CUDA_BUILD_VERSION=%s\nCUDACXX=%s\n' "$task_cuda_version" "$CUDACXX"
  printf 'CUDA_DRIVER_VERSION=%s\n' "${task_driver_cuda:-unknown}"
  printf 'NVRTC_VERSION=%s\nGPU_NVRTC_LIB=%s\n' "$task_nvrtc_version" "$GPU_NVRTC_LIB"
  ls -l "$GPU_NVRTC_LIB"/libnvrtc.so* "$GPU_NVRTC_LIB"/libnvrtc-builtins.so*
  lscpu
  free -h
  df -h "$task_repo"
  nvidia-smi
} > "$task_log_root/machine.txt"
find gpu-common/src experiments/src emitter/src/native -type f -print0 | sort -z | \
  xargs -0 sha256sum > "$task_log_root/source-sha256.txt"
find "$task_native" -maxdepth 1 -type f -name '*.ptx' -print0 | sort -z | \
  xargs -0 -r sha256sum > "$task_log_root/ptx-sha256.txt"
for task_arch in 89 120; do
  task_ptx_count=$(find "$task_native" -maxdepth 1 -type f -name "*-compute_$task_arch.ptx" | wc -l)
  if [[ "$task_ptx_count" -lt 4 ]]; then
    printf 'Expected four compute_%s PTX files; found %s.\n' "$task_arch" "$task_ptx_count" >&2
    exit 1
  fi
done

# Four independent slots keep the laptop GPU occupied without fragmenting its
# 8 GiB VRAM. The JVM keeps 15 GiB outside its heap for RocksDB, CUDA pinned
# buffers and the filesystem cache. Host and RocksDB concurrency leave several
# logical CPUs for the driver, compaction and the desktop.
export JAVA_TOOL_OPTIONS="-Xms2g -Xmx16g -XX:ActiveProcessorCount=22 -XX:+UseG1GC -XX:ParallelGCThreads=8 -XX:ConcGCThreads=2 -XX:+ExitOnOutOfMemoryError -Xlog:gc:file=experiments/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=32M"

task_search_pid=
task_monitor_pid=
task_stop_children() {
  if [[ -n "$task_monitor_pid" ]] && kill -0 "$task_monitor_pid" 2>/dev/null; then
    kill "$task_monitor_pid" 2>/dev/null || true
    wait "$task_monitor_pid" 2>/dev/null || true
  fi
}
task_forward_signal() {
  if [[ -n "$task_search_pid" ]] && kill -0 "$task_search_pid" 2>/dev/null; then
    kill -TERM "$task_search_pid" 2>/dev/null || true
  fi
  exit 130
}
trap task_stop_children EXIT
trap task_forward_signal INT TERM

printf 'Starting a fresh TD(18) run on %s.\nData: %s\nLog: %s\n' \
  "$task_gpu_name" "$task_data" "$task_log"
printf 'Follow progress with: tail -f %q\n' "$task_log"

bash "$task_repo/experiments/run.sh" diameter --no-build \
  --data-dir "$task_data" --max-n 18 --pattern-horizon 17 \
  --cpu-threads 18 --batch-records 16384 \
  --write-buffer-mib 4096 --write-aggregate-mib 512 \
  --write-native-batch-mib 4 --write-flush-ms 2000 \
  --rocks-write-threads 12 --rocks-background-jobs 6 --rocks-flush-threads 3 \
  --rocks-memtable-mib 256 --rocks-sst-mib 256 --rocks-cache-mib 512 \
  --gpu-devices 0 --gpu-slots 4 --gpu-slot-mib 0 --vram-fraction 0.90 \
  --gpu-batch-records 262144 --gpu-queue 32 --gpu-parent-batch 4096 \
  --gpu-quantum 65536 \
  --log-file "$task_log" &
task_search_pid=$!
printf '%s\n' "$task_search_pid" > "$task_log_root/extension.pid"

bash "$task_repo/experiments/scripts/servers/monitor.sh" "$task_search_pid" \
  > "$task_log_root/resources.log" 2>&1 &
task_monitor_pid=$!
printf '%s\n' "$task_monitor_pid" > "$task_log_root/monitor.pid"

set +e
wait "$task_search_pid"
task_status=$?
set -e
task_search_pid=
task_stop_children
rm -f -- "$task_log_root/extension.pid" "$task_log_root/monitor.pid"
if [[ $task_status -eq 0 ]]; then
  printf 'TD(18) run completed successfully. Result: %s/td-18.properties\n' "$task_data"
else
  printf 'TD(18) run failed with status %d; inspect %s\n' "$task_status" "$task_log" >&2
fi
exit "$task_status"
