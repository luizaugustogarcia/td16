#!/usr/bin/env bash
# RTX 5090 + EPYC 7742, 256 logical CPUs, 256 GB RAM, fast NVMe.
# Full TD(18) search; data, logs and builds use the attached /workspace volume.
set -euo pipefail
task_repo=$(cd "$(dirname "$0")/../../.." && pwd)
export TDP_EXPERIMENT_BUILD_ROOT=${TDP_EXPERIMENT_BUILD_ROOT:-/workspace/td16-build/rtx5090-epyc}
export TDP_DATA_DIR=${TDP_DATA_DIR:-/workspace/td16-data/rtx5090-epyc}
export TDP_LOG_DIR=${TDP_LOG_DIR:-/workspace/td16-logs/rtx5090-epyc}
export TDP_WRITE_BUFFER_MIB=${TDP_WRITE_BUFFER_MIB:-49152}
export TDP_WRITE_AGGREGATE_MIB=${TDP_WRITE_AGGREGATE_MIB:-8192}
export TDP_WRITE_NATIVE_BATCH_MIB=${TDP_WRITE_NATIVE_BATCH_MIB:-16}
export TDP_WRITE_FLUSH_MS=${TDP_WRITE_FLUSH_MS:-1000}
export TDP_ROCKS_MEMTABLE_MIB=${TDP_ROCKS_MEMTABLE_MIB:-2048}
export TDP_ROCKS_SST_MIB=${TDP_ROCKS_SST_MIB:-2048}
# Twice the host concurrency on the same RAM: halve the generation packet size.
export TDP_GPU_PARENT_BATCH=${TDP_GPU_PARENT_BATCH:-2048}
exec bash "$task_repo/experiments/scripts/servers/rtx5090.sh" "$@"
