#!/usr/bin/env bash
# Native Blackwell build for RTX 5090; select CUDA 12.8+ through the environment.
set -euo pipefail
task_repo=$(cd "$(dirname "$0")/../../.." && pwd)
task_build_root=${TDP_EXPERIMENT_BUILD_ROOT:-${TMPDIR:-/tmp}/td16-rtx5090-build}
task_cpus=${TDP_CPU_THREADS:-$(nproc)}
if [[ ! $task_cpus =~ ^[1-9][0-9]*$ ]]; then
  printf 'TDP_CPU_THREADS must be positive.\n' >&2; exit 2
fi
task_nvcc=
if [[ -n ${CUDACXX:-} ]]; then
  task_nvcc=$(command -v -- "$CUDACXX" 2>/dev/null || true)
elif [[ -n ${CUDA_HOME:-} && -x $CUDA_HOME/bin/nvcc ]]; then
  task_nvcc=$CUDA_HOME/bin/nvcc
elif [[ -n ${CUDA_PATH:-} && -x $CUDA_PATH/bin/nvcc ]]; then
  task_nvcc=$CUDA_PATH/bin/nvcc
elif command -v nvcc >/dev/null 2>&1; then
  task_nvcc=$(command -v nvcc)
fi
if [[ ! -x $task_nvcc ]]; then
  printf 'Select a CUDA 12.8+ toolkit using CUDACXX, CUDA_HOME, CUDA_PATH or PATH.\n' >&2; exit 1
fi
task_nvcc=$(readlink -f "$task_nvcc")
task_version=$("$task_nvcc" --version | sed -nE 's/.*release ([0-9.]+).*/\1/p' | tail -n 1)
task_driver=$(nvidia-smi | sed -nE 's/.*CUDA (UMD )?Version: *([0-9]+(\.[0-9]+)?).*/\2/p' | head -n 1)
if [[ -z $task_version || $(printf '%s\n%s\n' 12.8 "$task_version" | sort -V | head -n 1) != 12.8 ]]; then
  printf 'RTX 5090 requires CUDA 12.8 or newer; selected toolkit: %s.\n' "$task_version" >&2; exit 1
fi
if [[ -n $task_driver && $(printf '%s\n%s\n' "$task_version" "$task_driver" | sort -V | tail -n 1) != "$task_driver" ]]; then
  printf 'CUDA toolkit %s exceeds driver support %s.\n' "$task_version" "$task_driver" >&2; exit 1
fi
export CUDACXX="$task_nvcc"
export TDP_EXPERIMENT_BUILD_ROOT="$task_build_root"
cd "$task_repo"
printf 'Building compute_120 PTX with CUDA %s; build workers=%s; output=%s\n' "$task_version" "$task_cpus" "$task_build_root"
mvn -q -pl experiments -am -Pisolated-build,cuda-native \
  -Dtdp.isolatedBuildRoot="$task_build_root" -Dtdp.cudaArchitectures=120 \
  -Dtdp.cudaBuildThreads="$task_cpus" -DskipTests package dependency:copy-dependencies "$@"
for task_kernel in diametral-filter streaming-count-soa streaming-count-aos emitter-delta-audit; do
  test -s "$task_build_root/experiments/native/$task_kernel-compute_120.ptx"
done
