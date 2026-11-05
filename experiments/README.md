# Persistent diametral extension

The default `count` mode now implements the deletion/insertion extension from
persisted parents. It stores canonical instances of `D(n)` and the additional
exact-distance layers needed by subsequent degrees. After the degree-1 seed,
the generator does not scan independent symmetric groups.

## Module layout

```text
experiments/
  pom.xml                         Maven module and native build profile
  run.sh                          main command-line entrypoint
  docs/                           mathematics, operation and baseline guides
  scripts/
    jshell/                       standalone JShell audit
    servers/                      production profile, launcher and monitor
  src/main/java/                  Java implementation
  src/main/resources/cuda/        CUDA sources packaged for runtime compilation
  src/native/CMakeLists.txt      native PTX build using the same CUDA sources
  src/test/java/                  implementation tests
  target/                         generated Maven and native build artifacts
```

Runtime data, logs and profiles belong in `data/`, `logs/` and `profiles/`;
these directories are created on demand and ignored by Git. The packaged CUDA
resources also include the emitter source under `cuda/emitter/` for its audit.

## Running

```bash
mvn -pl experiments -am verify
bash experiments/run.sh extension-plan --max-n 18
bash experiments/run.sh diameter --max-n 18 \
  --data-dir experiments/data/extension \
  --log-file experiments/logs/extension.log
tail -f experiments/logs/extension.log
```

`diameter` determines TD(18), treating it as unknown. It searches distance 11,
stops at the first verified match, or concludes TD(18)=10 after exhaustive
exclusion. It never generates the distance-10 degree-18 set. A positive partial
search is never published as a complete exact set. The result is recorded in
`td-18.properties`, with the conclusion and enumeration counters.

Complete canonical parent/support sets through degree 17 remain inputs to this
extension algorithm. Their counts are incidental to the final diameter goal.
The proof obligations and certificate limits are described in
[TD18_PROOF.md](docs/TD18_PROOF.md).

`count` enumerates diametral sizes, including the distance-10 degree-18 set if
distance 11 is empty. `extend` is an alias of `count`. The old independent enumerator remains available
only as `scan-count`; its documentation is [SCAN_BASELINE.md](docs/SCAN_BASELINE.md).
No sorting sequences or witnesses are written by the extension.

## GPU prefix prototype

The `prefix-benchmark` mode compares the existing exact GPU sorter with a
short GPU trie search followed by the same exact sorter on unresolved roots.
The prototype is isolated from production enumeration and is not enabled by
`count` or `diameter`.

The trie contains sequences of adjacency gains 0, 1, 2, or 3, through three
moves. These are fixed adjacencies, distinct from cycle-count changes;
all block transpositions are considered. Each move acts on the current contracted permutation; sorted strips
are preserved. A node accepts only when `moves used + TD(contracted degree)
<= budget`. Branches that cannot meet that condition within the remaining
depth are omitted. Failure at a partial prefix continues the other viable
branches, including zero-gain steps. Exhausting the trie leaves the root
unresolved and invokes complete sorting. TD(18) is bounded by 11 here; its
unknown value is not assumed to be 10. Prefix misses never prove a distance.

```bash
bash experiments/run.sh prefix-benchmark
```

Benchmark settings are JVM properties: `benchmark.n` (12), `benchmark.budget`
(proven upper bound minus one), `benchmark.jobs` (128), `benchmark.rounds` (3),
`benchmark.prefixMoves` (3), `benchmark.tableDegree` (8),
`benchmark.timeoutSeconds` (45 per batch and path), and `benchmark.seed` (20261105).
`benchmark.batchJobs` (131072) bounds GPU workspaces and controls progress
granularity. `benchmark.warmupJobs` (4096) bounds warm-up work independently
of the sample size. Both are capped at the actual number of inputs.
Inputs are deterministic random permutations without sorted adjacencies by
default (`benchmark.irreducible=true`); `benchmark.unique=true` requests
sampling without replacement. Timings exclude setup and warm-up, include
transfers, host buffer compaction and exact fallback, and alternate measurement
order between batches and rounds. Each batch compares every decision and
logs cumulative times and certificate counts. A timeout aborts the comparison
without a conclusion.
These synthetic inputs and small pattern tables do not establish performance
on production extension candidates with larger resident GPU tables.

For JShell, after building the module and exporting the CUDA library path:

```bash
task_classpath=$(printf '%s:' experiments/target/classes experiments/target/dependency/*.jar)
task_classpath=${task_classpath%:}
jshell --class-path "$task_classpath" -R--enable-native-access=ALL-UNNAMED \
  -R-Dbenchmark.n=12 -R-Dbenchmark.budget=6 -R-Dbenchmark.jobs=4096 \
  -R-Dbenchmark.tableDegree=9 experiments/scripts/jshell/prefix-benchmark.jsh
```

For a larger comparison, use `-R-Dbenchmark.jobs=2097152`,
`-R-Dbenchmark.unique=true`, `-R-Dbenchmark.rounds=1`, and
`-R-Dbenchmark.timeoutSeconds=1800`. Redirect JShell output to a log file
to follow batch completions and active-stage messages during long batches.

The native CMake profile also builds `prefix-search` PTX from the same source.

## What is retained

The runner requests `D(1)`, then `D(2)`, and so on. Each request generates just
one complete exact-distance set `E(n,d)`, represented by its canonical instances.
Deletion monotonicity and the insertion lemma give the exact dependency rule:

```text
E(n,d) requires E(n-1,d-1) and E(n-1,d), within the established diameter bounds.
```

Dependencies are requested recursively only when needed. Completed sets are
reused. Results at other distances are discarded rather than saved as
speculative byproducts. There is no eager backward closure from the horizon.

| Requested result | Newly needed support |
| --- | --- |
| D(15), distance 9 | D(14), distance 8, already completed |
| D(16), distance 9 | E(15,8), which requests E(14,7) when needed |
| D(17), distance 10 | D(16), distance 9, already completed |
| Degree 18, distance 11 | D(17), distance 10, already completed |
| Degree 18, distance 10, if distance 11 is empty | E(17,9), recursively reaching E(14,6) |

TD(18) is treated as unknown by this experiment. Distance 11 is searched first.
Only an exhaustively empty result requests the distance-10 branch. Empty exact
sets have complete manifests and are reused on restart. Only `count` requests
the distance-10 branch; `diameter` concludes the diameter without it. Large required sets
can still need substantial disk space.

`--through-n` stops after the requested diametral milestone. A horizon of 18
with `--through-n 9` generates the same sets as a run ending at 9. Further
support sets are generated when the run continues:

```bash
bash experiments/run.sh count --max-n 18 --through-n 9 \
  --data-dir experiments/data/extension
```

## Where the inputs live

```text
experiments/data/extension/
  n-09/distance-05/
    db/                      # RocksDB, canonical E(9,5) only
    manifest.properties      # scope=exact, distance, counts, completion
  n-10/distance-06/
    ...
```

The path is independent of the horizon. Logs show every requested set, its
parent distance range, and completion counts. Existing complete `floor-*`
tail stores remain readable; they can supply individual requested exact sets
without regeneration. New runs write only `distance-*` stores.

The database key is only the packed image vector of canonical `omega`. Its
value is the exact distance in the low four bits of one byte; the high four
bits are zero. RocksDB stores whole bytes, so the value occupies one byte.
Each permutation symbol occupies five bits, MSB first, with zero padding at
the end. These are binary bytes, without punctuation, delimiters, or text
digits. With 19 symbols, the key occupies 12 bytes; the logical key/value
record occupies 13 bytes before RocksDB's indexing and compression overhead.
`sigma=(0 1 ... n)` is fixed and recorded by the format rather than repeated
in every row. Each deletion lookup queries the relevant exact set stores.
RocksDB MultiGets are bounded to 2,048 keys; host packets are consumed in slices
of 128 candidates to limit pinned SST blocks and temporary key allocations.
Deletion queries stop for each candidate as soon as a missing deletion rejects it.

All stored instances are canonical for the fixed-target pair `(omega,sigma)`,
using `omega = sigma * beta^{-1}` and the left-composition convention from
`../td`. The exact ordinary-permutation count is the sum of orbit weights,
including nontrivial stabilizers. Orbit weights are not persisted per record:
they are computed in memory during generation and recovered by orbit expansion
when reading parents. The manifest retains only aggregate counts per distance.
Legacy stores with distance-prefixed keys and weight values remain readable;
all new stores use permutation keys and distance values.

## How the next degree is computed

1. Stream the required parent layers from their complete persisted store.
2. Expand each parent's distinct extended-toric orbit members on the GPU.
3. Insert the new largest symbol in every position. This has a unique ordinary
   parent obtained by deleting that largest symbol.
4. Reject noncanonical children. Look up deletion distances in the resident GPU
   table, or generate canonical deletion keys on the GPU for batched RocksDB I/O.
5. Use `max(deletion distances) <= d(child) <= 1+min(deletion distances)`.
   Mixed deletion distances determine the answer. Uniform distance `q` requires
   only the exact `CanSort(child,q)` decision, unless the known diameter bound
   already determines the answer.
6. Append only children at the requested exact distance to output buffers.
   Their orbit weights are transient count metadata.

Orbit expansion before insertion is required for completeness: inserting only
into the stored canonical parent can miss a canonical child. Each ordinary
parent is generated once, so each canonical child is emitted once. Neither a
full permutation table nor a global child deduplication set is required.

The production path performs orbit expansion, insertion, canonicality,
deletion canonicalization and exact DFS on the GPU. Each selected GPU has its
own context, generation stream and search slots. The emitter's device layout
is shared through the `gpu-common` Maven module. Requests are collected
by search budget into larger batches. A full queue applies backpressure;
GPU mode never falls back to CPU sorting. Every transposition remains
available, including `-2`, `0`, and `2` changes in odd-length-cycle count.

### Multiple GPUs and memory budgets

Build CUDA kernels with CMake and the installed CUDA toolkit, following the
emitter's Maven/CMake build workflow:

```bash
mvn -pl experiments -am -Pcuda-native -DgpuTests=true verify dependency:copy-dependencies
bash experiments/run.sh diameter --no-build \
  --cuda-ptx-dir "$PWD/experiments/target/native" --gpu-devices 0,1,2,3 \
  --log-file experiments/logs/extension.log
```

The profile detects the visible GPU architectures and compiles PTX with `nvcc`.
`-Dtdp.cudaArchitectures='89;120'` overrides detection for a different deployment.
Java loads the PTX using the CUDA Driver API through JCuda. This mode needs no
NVRTC runtime and supports CUDA 13 installations and compute capability 12.0.
Without `--no-build`, selecting `--cuda-ptx-dir` also enables the CMake profile.
Without a PTX directory, the existing NVRTC compilation path remains available.

```bash
bash experiments/run.sh diameter --max-n 18 \
  --gpu-devices 0,1 --gpu-slots 4 --gpu-slot-mib 512 \
  --gpu-batch-records 16384 --gpu-parent-batch 4096 \
  --data-dir experiments/data/extension \
  --log-file experiments/logs/extension.log
```

`--gpu-devices` is a comma-separated list of distinct CUDA-visible ordinals;
the default is `0`. As in the emitter, `--gpu-slots` means slots **per device**:
the example has eight search slots. Device existence is checked before opening
contexts. Each GPU has one generation stream, and available GPUs acquire
parent batches from the host pipeline. Search slots acquire batches from one
shared bounded queue, so faster devices can process more work. The writer and
membership index remain shared. A first match or failure stops all devices.

`--gpu-slot-mib` caps the total device workspace memory retained by each slot,
including cached workspaces for different search budgets. `0` (the default)
uses automatic sizing. Automatic budgets are calculated separately per GPU,
using `--vram-fraction` after the pattern table is allocated and generation
buffers are reserved. The explicit cap is limited by that available budget.
Old workspaces are evicted as needed; oversized requests are split into smaller
batches. The batch-record limit can keep actual allocation below the cap.
Shared pattern tables, generation buffers, CUDA runtime resources and pinned
host buffers are outside this per-slot cap.

Each GPU owns its pattern table, requiring about 3.14 GiB per GPU through
degree 13. Tables are copied between contexts through a small host staging
buffer pinned with CUDA's [portable allocation flag](https://docs.nvidia.com/cuda/archive/12.6.0/cuda-driver-api/group__CUDA__MEM.html),
so every context can use it. Each device can grow its cache independently.
A device with insufficient memory builds a smaller table and continues
using the persistent index where necessary. Search and generation synchronize
through blocking CUDA completion events, following the emitter's slot design.

After the complete D(14) store is available, each sufficiently large GPU also
loads a one-bit membership table indexed by the ordinary permutation rank.
It occupies 10,897,286,400 bytes (10.15 GiB); together with the four-bit tables,
the lookup data occupies 13.29 GiB per GPU, before generation and DFS buffers.
CUDA expands the canonical source orbits. Both the number of source records
and the bitmap population count must agree with the completed manifest before
the table becomes available to the search. Additional devices copy the table
through portable pinned host memory and independently check its population.

D(15) uses this bitmap for its degree-14 deletion lookups on the GPU. The DFS
also queries it when distance-preserving adjacency contractions leave a
degree-14 state, so later degrees can benefit. A set bit means exact distance
8; an unset bit proves only distance at most 7. In particular, this does not
replace the distance-7 support store needed for E(15,8); its deletion lookups
still use the persistent index. No extra exact sets are generated for this cache.
When that degree-14 distance-7 support store is generated, however, the already
completed D(14) bitmap answers its `CanSort(7)` queries directly. The support
set still has to be enumerated and persisted, but those queries need no DFS.

`--gpu-membership-degree 14` is the default; `0` disables it. The smaller
degree override is useful for exhaustive checks. `--gpu-pattern-degree 13`
sets the maximum four-bit table degree. Membership tables are optional per
device: memory fitting reserves generation buffers, a full configured batch
for every slot, and a safety margin before allocation. Devices on which the
bitmap does not fit retain the existing lookup path. The bitmap is rebuilt
from the canonical stores when needed and is not persisted as another certificate.

Supplied-rank searches check on the GPU that every queued rank agrees with
its packed permutation key, and check that returned decision bytes agree
with the device counters. A failed known diameter count is rejected before
publishing a completed store. These errors stop the run.

The emitter properties `tdp.gpuDevices`, `tdp.gpuSlots` and `tdp.gpuBudgetMB`
are accepted as aliases; explicit experiment options take precedence. The
budget alias controls these DFS workspaces rather than the emitter's native
search queue. `GPU_DEVICE`, `GPU_SLOTS`, `GPU_DEVICE_WORK` and per-device query
counts in progress logs expose device assignment and completed work.
These controls apply to extension modes; the legacy independent scan remains
a separate baseline.

Completed stores also supply resident pattern tables for the exact search.
Their ordinary orbit members are expanded on the GPU into four-bit entries.
Tables through degree 13 occupy approximately 3.14 GiB.
The actual degree is limited by available VRAM. Missing members of a retained
tail supply a proven upper bound, which is used only when it settles the
current bounded search. Larger parent sets use the persisted canonical index.
The search tables accelerate decisions and do not replace extension from
persisted parents.

In `diameter --max-n 18`, the default `--pattern-horizon 17` prepares the
degree-13 cache from sets already required to construct D(17). It does this
before D(14), so the GPU has distance-6 degree-13 entries during its searches.
The degree-13 distance-5 set is not generated, and larger support sets retain
their lazy schedule. Preparing the cache changes when required work happens;
it adds no sets beyond the D(17) dependency closure. `--pattern-horizon 0`
disables this preparation. Current configuration and log locations are in
[OPERATIONS.md](docs/OPERATIONS.md).

CPU workers overlap record handling and persistent-index I/O with CUDA
generation and sorting. `--cpu-threads` controls this bounded host pipeline.
The explicit `--no-gpu` mode is retained for independent CPU verification;
`--base-degree` controls its small BFS oracle.

## Asynchronous persistence and restart

Each producer owns a packed output transport buffer. Full buffers and partial
buffers at packet completion are transferred to a bounded queue. An optional
shared accumulator combines these packets across producers before parallel
background workers encode binary RocksDB batches and insert them with the WAL
disabled. Encoding a whole batch avoids one JNI call per record. Backpressure
bounds memory use when storage is slower than generation. Native write failures
propagate to the host pipeline and GPU slots.

`--write-buffer-mib 8192` sizes the persistence backlog from an 8 GiB staging
budget instead of a small batch count. A positive budget overrides
`--write-queue`; zero preserves the explicit legacy batch limit. Capacity is
calculated separately for each degree using its packed row width. The estimate
reserves space for producer-owned buffers, shared aggregation segments,
queued/running writer jobs, Java encoding arrays, native WriteBatch copies and
a bounded recycle pool. It does not include RocksDB caches/memtables or the
other pipeline stages.

Buffers are allocated on demand. An empty queue does not reserve the staging
budget, and the recycle pool is bounded. The JVM heap must accommodate the
configured budget. Shared aggregation reserves space for larger write jobs
before assigning the remaining capacity to queued producer packets.

For a shared **1 GiB aggregation target** within the **8 GiB total staging
budget**, use:

```sh
--write-buffer-mib 8192 --write-aggregate-mib 1024 \
--write-native-batch-mib 8 --write-flush-ms 5000
```

The accumulator copies packed rows into segments and dispatches when it has
1 GiB of useful rows, or when its oldest row has waited five seconds. Closing
a producer packet no longer forces a database call. The aggregate is segmented
rather than allocated as a single 1 GiB Java array. Each parallel worker encodes
at most 8 MiB of native WriteBatch data per call, retaining concurrency across
64 writers without allocating 64 separate 1 GiB batches. All limits account for
whole records, so effective byte counts can be slightly below their targets.
These sizes and the interval are configurable; `--write-aggregate-mib 0`
preserves the original one-packet-per-write behavior. Aggregation requires a
positive staging budget, and configurations too small for its memory reserves
are rejected before starting the writer.

A timed dispatch inserts into RocksDB's memtable; it does not force an SST flush
every five seconds. The existing large memtable/SST settings still control disk
flushing. At completion, the last partial aggregate is dispatched and every
native write finishes before the store can be published.

Startup logs show `ASYNC_WRITE_BUFFER` with the effective capacities, aggregation
target, maximum native batch size, interval and staging estimate. Progress logs
include `queued_bytes_estimate`, `write_queue_capacity`, `write_queue_wait_seconds`
(the cumulative time producers spend waiting for the write queue),
`aggregate_bytes` (allocated shared segment bytes), `aggregate_target_bytes`,
`aggregate_flushes` and `native_write_calls`. The byte estimate covers input,
shared and in-flight raw buffers; encoder/native copies are additionally reserved
in the startup staging budget. Backpressure still applies once the budget fills.
It can absorb longer bursts but does not increase sustained storage throughput
or remove the final publication barrier.

The defaults use 256 MiB memtables (up to six), 256 MiB target SST files, LZ4,
concurrent memtable insertion, pipelined writes, and parallel flush/compaction.
On the 22-thread machine, 22 write workers and up to 11 native background jobs
are available, with four flush workers in the separate high-priority pool.
A 512 MiB block cache supports point lookups. New SSTs use a two-level index
and partitioned Bloom filters with 4 KiB metadata blocks. Read-only stores keep
top-level metadata outside that cache; legacy whole-file filters stay resident
in their table readers. This avoids repeatedly loading large filter blocks.
Sequential parent
scans use readahead and bypass that cache. Large parent sets use parallel
iterators over disjoint key ranges, balanced with RocksDB's range-size estimates.
Full memtables flush automatically;
small periodic forced flushes are avoided. Settings are workload-dependent,
and the run logs their values and final SST/compaction statistics.

At degree completion, all GPU decisions finish, all output buffers drain, the
database completes a blocking flush to SST files. The closed store and its
manifest are then published by an atomic directory rename. Only these complete
stores can become parents for the next degree.

The [quad RTX 3090 profile](scripts/servers/quad-3090.sh) targets four 24 GiB GPUs,
184 CPUs of quota and approximately 242 GiB RAM. It selects eight slots per
device, 2,097,152-record maximum search batches, 184 host workers, 96 write
workers, a 128 GiB heap, a 48 GiB persistence staging budget and a 4 GiB shared
aggregate. Native writes remain 8 MiB with a five-second partial dispatch.
RocksDB uses 2 GiB memtables/SST targets, 48 background jobs, 16 flush workers
and 16 GiB caches per physical store. G1 uses NUMA allocation support on the
dual-socket host. This profile starts production directly without a preflight
or separate validation run. After a successful native build, launch it detached
with `bash experiments/scripts/servers/start-production.sh quad-3090.sh`; this requires
a fresh production data directory/log and also starts the resource monitor.

Re-running a command reuses completed exact sets. Later milestones may request
lower parent layers; these become additional `distance-*` stores, without
recomputing completed sets. Incomplete stores remain under
`.distance-*.partial` directories and are never read as complete inputs.
Restart is at exact-set boundaries; an interrupted current set is regenerated.

Useful controls:

```bash
bash experiments/run.sh count --max-n 18 \
  --cpu-threads 22 --vram-fraction 0.90 \
  --batch-records 16384 --write-queue 8 \
  --gpu-batch-records 16384 --gpu-queue 8 --gpu-slots 4 \
  --gpu-parent-batch 4096 --gpu-quantum 4096 \
  --rocks-write-threads 22 --rocks-background-jobs 11 --rocks-flush-threads 4 \
  --rocks-memtable-mib 256 --rocks-sst-mib 256 --rocks-cache-mib 512 \
  --data-dir experiments/data/extension \
  --log-file experiments/logs/extension.log
```

`--no-gpu` selects CPU-only exact queries. `--base-degree` supports 0 through 9.
`--no-build` reuses the already-built module and copied dependencies.
`--log-file` appends console output, including a run/PID header.
Progress includes a rolling `settled_per_minute` rate for canonical instances;
the `FINAL` line reports the ordinary count `|D(n)|` after all writes finish.

GPU and CPU occupancy depend on the limiting stage. Worker limits enable
parallel useful work; they do not guarantee 100% utilization of both processors
at every instant. Current configuration and log locations are in
[OPERATIONS.md](docs/OPERATIONS.md).

GPU modes require a CUDA driver and the CUDA 12.6 NVRTC libraries, but not
`nvcc`. Set `GPU_NVRTC_LIB` to the directory containing `libnvrtc.so.12` if
needed. This workspace reuses the existing runtime under
`../new-td/build/gpu/cuda/`; that project is not modified.

## Verification

CPU tests compare every persisted canonical layer and ordinary membership
answer with independent BFS through degree 9. They cover five-bit packing,
bounded asynchronous buffers, writer failure, incomplete-store rejection,
resume, lazy support backfilling, empty sets, and the absence of speculative
sets. Scheduler tests check the precise D(15), D(16), and conditional D(18)
dependencies.
Decision tests also cover first-match cancellation, empty-set publication,
parent coverage mismatches, and the absence of the degree-18 distance-10 branch.

GPU-enabled tests additionally check the supplied-rank result ordering, kernel
yield boundaries, GPU generation with resident and disk deletion lookups,
sparse pattern bounds, shared-context cancellation, persistence through 9,
and the genuine `D(9)->D(10)`
jump: 1,143,270 generated candidates recover 255,053 diametral permutations.
The configured GPU library path is needed for these tests:

```bash
mvn -pl experiments -am -DgpuTests=true verify
```

The mathematical derivation and audit procedure are in
[DIAMETRAL_EXTENSION.md](docs/DIAMETRAL_EXTENSION.md). The standalone audit remains
available with `bash experiments/run.sh audit --max-n 10`.

Multi-GPU tests exercise two independent CUDA contexts on one physical GPU,
parallel generation, shared search scheduling, pattern-copy lifetime,
per-slot workspace eviction, partial initialization failure and global
first-match cancellation. A separate physical two-device test runs when two
CUDA devices are available.
