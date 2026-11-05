# Independent rank-scan baseline

The legacy `scan-count` mode counts ordinary
permutations in `D(n)`, using exact extended-toric orbit weights. It writes no
witnesses, bracelet files, permutation lists, or checkpoints.

```bash
mvn -pl experiments -am verify
bash experiments/run.sh gpu-audit
bash experiments/run.sh gpu-benchmark
bash experiments/run.sh scan-count --max-n 18
```

`run.sh` builds the module and copies its Maven dependencies unless `--no-build`
is given. Java 21+ and an NVIDIA CUDA driver are required for GPU modes. CUDA
kernels compile with NVRTC at runtime; `nvcc` is not needed. Set `GPU_NVRTC_LIB`
to the directory containing `libnvrtc.so.12` from CUDA 12.6 if it is not already
on the library path. In this workspace the script reuses the existing runtime
under `../new-td/build/gpu/cuda/`; it does not modify that project.

Options for `scan-count`:

```bash
bash experiments/run.sh --no-build scan-count \
  --max-n 18 --cpu-threads 22 --vram-fraction 0.90 \
  --log-file experiments/logs/diametral-counts.log
tail -f experiments/logs/diametral-counts.log
```

`--log-file` appends stdout and stderr to the selected file, including a run/PID
header. Without this option output goes to the launching console.

The default uses every available CPU processor and up to 90% of free GPU memory
for canonical search jobs. Raw rank chunks first pass a GPU canonicality filter.
Only survivors enter a compact queue and receive DFS state. Raw chunks can be
larger than that queue; if an estimated chunk contains too many representatives,
it is bisected and retried before any sorting or progress is committed. No root
is lost because of queue capacity. Small degrees have too few representatives
to use the VRAM budget; memory is allocated according to the actual workload.
CPU workers and the GPU
receive disjoint rank chunks. Idle CPU workers can take unfinished jobs from
the GPU between kernel launches, without double counting. Each chunk is
discarded once its contributions have been counted. No witness is retained.

The console prints `FINAL n=... TD=... |D(n)|=...` when a degree is complete.
`PROGRESS` lines show provisional counts and processed rank coverage; `LOAD`
lines show CPU use and GPU compute/VRAM measurements when `nvidia-smi` is
available. A failed or interrupted search never produces a final count for the
unfinished degree. There is no restart checkpoint: a fresh run starts at 1.
`gpu_response_age` measures time since the last returned GPU search slice,
even when that slice did not finish any instances. Raw rank coverage includes
quick canonical rejections and is not a fraction of the total sorting time.

## Exactness and scope

The bounded CPU and GPU searches enumerate every block transposition, including
`-2`, `0`, and `2` changes in the odd-cycle count. They use the odd-cycle lower
bound, distance-preserving adjacency contraction, and exact small-degree tables.
Immediate reversal of the previous move is omitted because a shortest path
cannot contain this two-step return. The GPU preserves DFS continuations across
bounded kernel launches; a launch ending does not mean a search has failed.

The tables through degree 9 are computed by BFS in memory at startup. Larger
degrees are counted by scanning rank chunks, testing extended-toric
canonicality, sorting canonical representatives, and adding `2*(n+1)/stabilizer`
for each diametral representative. Exact orbit weights are necessary because
some representatives have nontrivial stabilizers. Degrees 10 through 14 are
checked against the published counts when they finish. Degree 18 is tested at
both distances 10 and 11, since its diameter is not yet fixed by the available
bounds. Only after all ranks finish can the program select the final count.

### Canonicality with a fixed target

Following the left-composition convention in `../td/docs/paper/main.tex`, let
`sigma = (0 1 ... n)` be the fixed target (called `rho` in the paper), let `beta`
be the current cycle, and let `omega = sigma * beta^{-1}`. The pair
`(omega, sigma)` determines `beta = omega^{-1} * sigma`, so canonicality can be
tested on omega's image vector with sigma held fixed.

Let `r(x) = -x mod (n+1)`. The comparison uses conjugates
`omega^(sigma^v)` and `(omega^{-1})^(sigma^(v+1) r)` for all `v=0,...,n`, where
`g^h = h*g*h^{-1}`. The reflection's extra target factor follows from
`sigma * (r*beta^{-1}*r)^{-1} = (sigma*r)*omega^{-1}*(sigma*r)`.
The instance enters sorting only when its image vector is lexicographically
least among these images. Its orbit weight is `2*(n+1)/stabilizer`.

This uses extended-toric equivalence with canonical ordering on omega's image
vector. Canonical rejection precedes DFS allocation and compacts the GPU queue.

This is a complete **streaming baseline**, not direct twisted-bracelet
generation. Rank coverage remains factorial even though expensive sorting is
performed only on canonical representatives. In particular `18!` is
6,402,373,705,728,000. This implementation does not establish that degree 18 is
practical on the available laptop; direct generation and additional structural
reductions are still needed for that objective. Its resource limits and console
progress make that cost observable without accumulating certificate data.

## Verification

Normal Maven tests independently compare complete CPU searches with BFS, check
contraction decisions, verify 64-bit rank endpoints through degree 18, exercise
the two-budget classification, and reconstruct every distance layer through
degree 9 with toric orbit weights. They also check the fixed-target
omega/beta round trip and compare the omega canonicality test with an explicit
extended-toric word-orbit calculation.

`gpu-audit` compiles the actual emitter cycle-change functions and compares their
answers with explicit recounts for every permutation and both orientations of
every triple through eight symbols. It additionally checks merging three
nontrivial odd cycles at nine symbols. It checks complete GPU counts through
degree 7 without pattern tables, degree 8 using only tables through degree 7,
degree 9 using only tables through degree 8, CPU/GPU job transfer, canonical
queue overflow recovery, and both outcomes of the two-budget protocol.
CPU/GPU canonical weights are compared for every instance from degree 1 through
9. It can also
run under Surefire with `-DgpuTests=true` when the NVRTC library path is set.
The GPU-enabled Maven tests additionally force one-, two-, and three-step
kernel launches to check root exhaustion and budget transitions at every yield
boundary. The continuation depth is signed: `-1` denotes an exhausted root and
must survive a kernel yield without becoming `255`.

The original deletion-extension audit remains available:

```bash
bash experiments/run.sh audit --max-n 10
jshell --class-path experiments/target/classes experiments/scripts/jshell/diametral-extension.jsh
```

The second command uses the default degree-9 audit after Maven compilation.
The derivation is in [DIAMETRAL_EXTENSION.md](DIAMETRAL_EXTENSION.md).

The GPU filter benchmark reports kernel timing separately from timing including
host/device transfers.
