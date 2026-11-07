#!/usr/bin/env bash
# Reuse the Blackwell compiler checks and all detected build CPUs.
set -euo pipefail
task_repo=$(cd "$(dirname "$0")/../../.." && pwd)
export TDP_EXPERIMENT_BUILD_ROOT=${TDP_EXPERIMENT_BUILD_ROOT:-/workspace/td16-build/rtx5090-epyc}
exec bash "$task_repo/experiments/scripts/servers/rtx5090-build.sh" "$@"
