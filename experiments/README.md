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

For a clean run on the local RTX 4070 Laptop GPU, use the no-argument profile:

```bash
bash experiments/scripts/servers/local-rtx4070-fresh.sh
```

This profile obtains CUDA from the active environment, emits PTX for both Ada
(`compute_89`) and Blackwell (`compute_120`), selects a driver-compatible NVRTC
library from the environment for the local run, and then deliberately deletes all
existing `experiments/data/` and `experiments/logs/` contents before starting
TD(18). It refuses to clean while another experiment JVM is active. Its fixed
settings are tuned for this machine's 8 GiB GPU, 22 CPU threads and 32 GiB RAM;
progress is written to `experiments/logs/extension.log`.
Resume the same local data without deleting completed layers with:

```bash
bash experiments/scripts/servers/local-rtx4070-fresh.sh --resume
```

An interrupted exact layer is regenerated from its beginning; completed layers
are reused.

When a complete parent layer no longer fits as an exact resident GPU table,
the extension stage builds a negative-only GPU Bloom filter from its canonical
keys. Definite misses are rejected in the generation kernel; possible matches
are still confirmed against the mapped exact archive, so Bloom false positives affect only
speed. Sparse exact-search roots are accumulated into useful GPU batches rather
than launched after every short host-queue timeout; a final underfilled batch
is sharded across the available streams so it can occupy multiple SMs. The
local profile uses a 65,536-step search quantum, selected on the RTX 4070 from
deterministic million-parent measurements.

For the NOVA University Lisbon server specified in
`Concurso-GPU-16_2026_DL60_DII.pdf` (pages 2, 4 and 7), run the
[NOVA H200 launcher](scripts/servers/nova-h200.sh) with no arguments:

```bash
bash experiments/scripts/servers/nova-h200.sh
```

It builds the experiment, copies its dependencies and compiles native CUDA PTX
before starting the TD(18) diameter search in the foreground. The machine needs
Linux x86_64, JDK 21 or newer, Maven 3.9+, CMake 3.24+, a C++ compiler, and an
installed CUDA toolkit and driver supporting the H200; no separate build or
environment-variable setup is required when these tools are on `PATH`.
The profile selects CUDA device `0` (H200 NVL, 141 GB), 16 search slots with
automatic workspace sizing using 90% of remaining VRAM, 86 host workers, a 256 GiB
maximum Java heap, a 96 GiB persistence staging budget and an 8 GiB aggregate.
GPU lookup caches are selected automatically from completed sets and available
VRAM. On the H200, the four-bit cache can grow through degree 14 (43.74 GiB).
These are starting defaults for the specified 512 GB RAM server, not measured
performance settings. The PDF lists 86 cores and does not establish that two
processors are installed. RocksDB uses 64 write workers, 32 background jobs,
eight flush workers, 2 GiB memtables/SST targets and a 16 GiB cache per store.

The launcher works from any current directory. Data goes to
`experiments/data/search/`, progress appends to `experiments/logs/extension.log`,
and GC logs go to `experiments/logs/gc.log`, all relative to the checkout.
Keep the checkout on the server's NVMe storage. Re-running the same command
reuses completed exact sets and regenerates an interrupted set; it does not
clear existing data. Follow progress with the `tail` command above. This
launcher includes its own build step, so invoke it directly rather than through
the existing `start-production.sh` launcher, which expects an already-built run.

`diameter` determines TD(18), treating it as unknown. It searches distance 11,
stops at the first verified match, or concludes TD(18)=10 after exhaustive
exclusion. It never generates the distance-10 degree-18 set. A positive partial
search is never published as a complete exact set. The result is recorded in
`td-18.properties`, with the conclusion and enumeration counters.

Complete canonical parent/support sets through degree 17 remain inputs to this
extension algorithm. Their counts are incidental to the final diameter goal.
The proof obligations and certificate limits are described in
[TD18_PROOF.md](docs/TD18_PROOF.md). The submission-oriented LaTeX draft is
in [docs/paper/main.tex](docs/paper/main.tex), with its bibliography and build
instructions in the same directory. It deliberately remains conditional until
the complete distance-11 run and verification artifacts are archived.

`count` enumerates diametral sizes, including the distance-10 degree-18 set if
distance 11 is empty. `extend` is an alias of `count`. The old independent enumerator remains available
only as `scan-count`; its documentation is [SCAN_BASELINE.md](docs/SCAN_BASELINE.md).
No sorting sequences or witnesses are written by the extension.

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
    canonical-ranks-v1.bin    # immutable compressed canonical ranks
    manifest.properties      # scope=exact, distance, counts, completion
  n-10/distance-06/
    ...
```

The path is independent of the horizon. Logs show every requested set, its
parent distance range, and completion counts. Fresh-format `floor-*` tails
can supply individual exact sets; normal runs write `distance-*` stores.
Old omega/RocksDB formats are rejected: use a fresh data directory.

The existing canonical omega representative is encoded as its anchored-word
Lehmer rank. Complete ranks require at most 53 bits through degree 18.
Fixed-width big-endian rank keys provide sorted construction in temporary
RocksDB; finalization streams them into a checksummed Elias–Fano archive.
Exact-distance archives omit per-record distance and orbit values. CPU reads
map the archive directly; complete CUDA tails use the same exact rank layout.
GPU parents carry ranks, and ten-byte generation packets contain rank, decision
metadata and weight. Deletion queries stop when one missing deletion rejects
the candidate. See [compact archives](docs/COMPACT_SETS.md) for format and sizes.

All stored instances are canonical for the fixed-target pair `(omega,sigma)`,
using `omega = sigma * beta^{-1}` and the left-composition convention from
`../td`. The exact ordinary-permutation count is the sum of orbit weights,
including nontrivial stabilizers. Orbit weights are not persisted per record:
they are computed in memory during generation and recovered by orbit expansion
when reading parents. The manifest retains only aggregate counts per distance.
The archive and manifest are the completed store; construction RocksDB is
removed by default. `--compact-keep-rocks` retains it for debugging.

## How the next degree is computed

1. Stream the required parent layers from their complete persisted store.
2. Expand each parent's distinct extended-toric orbit members on the GPU.
3. Insert the new largest symbol in every position. This has a unique ordinary
   parent obtained by deleting that largest symbol.
4. Reject noncanonical children. Look up deletion distances in the resident GPU
   dense/compact table, or query canonical deletion keys in the mapped archive.
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

The bounded DFS uses four proved reductions. At a state with
lower-bound slack `s`, it admits only `+2` moves for `s=0`, `+2/0` moves for
`s=1`, and every move for `s>=2`. The move delta is computed before building
the child with the emitter's audited cycle-delta routine. At each root it also
queries every standardized one-symbol deletion when a degree-`n-1` pattern
table, compact tail or completed membership bitmap is resident. A deletion sortable within
`r-1` proves the root sortable within `r`; an exact deletion above `r` rejects
the root.

A second root prepass scans qualifying `+2` moves that create a fixed point,
contracts the resulting bond without changing distance, and queries the
degree-`n-1` exact data. The resident in-kernel path scans all qualifying moves.
The host archive path emits only the first qualifying contracted key; if that lookup
is inconclusive, complete DFS still settles the root. The default
`-Dextension.rootMovePrepass=auto` enables this at budgets at least 8; `true` or
`false` forces it, and `-Dextension.rootMoveMinBudget=...` changes the automatic
threshold. This scan places no prior restriction on the three symbols' cycle
provenance, sizes or parities: it computes the actual `+2` delta and relies on
the contracted-child bound for the one-sided decision.

Finally, the route-O oriented-odd-cycle reduction applies to every odd support
size `N>=3`. A realizable oriented unicycle either admits the qualifying
criterion or has a support-clearing sequence of `(N+1)/2` moves. The kernel
checks this rule at roots of every supported degree and accepts only when the
computed clearing cost fits the remaining budget. The largest support needed
here, `N=19`, also has an independent exhaustive audit: the normalized-cycle
search visited 9,304,845 nodes, retained three cycles, and left two realizable
oriented orbits, both with verified ten-move clearing sequences. The reduction
is controlled by `-Dextension.oddCyclePrepass=false`. The kernel reports
resolutions as `oddCycleResolved`; qualifying lookups are included in
`root_move_resolved`.

The reproducible four-way A/B benchmark verifies identical decisions for the
baseline, each optimization alone and both together:

```bash
bash experiments/run.sh search-benchmark --benchmark-n 9 \
  --benchmark-budget 4 --benchmark-jobs 32768 \
  --benchmark-table-degree 8 --benchmark-repeats 9
```

On an RTX 4070 Laptop GPU, using 32,768 evenly spaced ranks, two warmups and
the median of eleven runs, the native CUDA kernel produced the following
results. The table degree is `n-1`; all four variants returned identical
decision arrays.

| `n` | budget | baseline kernel ms | slack only | deletions only | both | both speedup |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 8 | 4 | 6.089 | 2.854 | 5.765 | 2.666 | 2.283x |
| 9 | 4 | 0.975 | 0.715 | 0.626 | 0.288 | 3.388x |
| 10 | 5 | 30.952 | 13.117 | 30.401 | 13.030 | 2.375x |

These are bounded-search microbenchmarks, not a projected TD(18) wall time.
The degree-18 benefit depends on the actual surviving roots and on which
degree-17 completed membership layer fits in VRAM.

The odd-cycle quantity is the 3-cycle length
`ell_3(omega)=(N-c_odd(omega))/2` of Mühle and Nadeau's
[*A Poset Structure on the Alternating Group Generated by 3-Cycles*](https://doi.org/10.5802/alco.83);
their cover characterization gives the three move deltas. Bóna and Pittel's
[*Permutations with restricted cycle lengths*](https://doi.org/10.5802/alco.449),
Section 4, restates the resulting lower bound. The slack rule is the direct
one-move consequence `s' = s - 1 + delta/2`; neither paper states this
implementation rule.
The extended-toric/dihedral quotient follows Korchmaros's
[thesis](https://arxiv.org/abs/1504.02157) and the Cayley-graph automorphism
result of [Korchmaros and Kovács](https://arxiv.org/abs/1511.07268). The
deletion prepass uses Lemma 2.4 of João Araújo, Wolfram Bentz, Peter J. Cameron,
and Luiz Silva, *Minimal Excluded Permutations in a Model of Genome Evolution*
(unpublished manuscript, 14 October 2026), as derived in
[DIAMETRAL_EXTENSION.md](docs/DIAMETRAL_EXTENSION.md). The qualifying and
support-clearing reductions extend the route-O analysis of
[Silva et al.](https://arxiv.org/abs/2609.17493). These symmetry results
justify the existing root quotient; they do not by themselves prune moves
inside one representative's DFS.

### Multiple GPUs and memory budgets

Build CUDA kernels with CMake and the installed CUDA toolkit, following the
emitter's Maven/CMake build workflow:

```bash
mvn -pl experiments -am -Pisolated-build,cuda-native -DgpuTests=true verify dependency:copy-dependencies
bash experiments/run.sh diameter --no-build \
  --cuda-ptx-dir "${TMPDIR:-/tmp}/td16-isolated-build/experiments/native" --gpu-devices 0,1,2,3 \
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
contexts. Each GPU has one generation stream by default; `--gpu-generation-streams`
selects independently buffered streams per device. Available streams acquire
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

Before each computation, every GPU loads useful completed distance sets. Compact
complete tails load first, with generation/search workspaces and headroom
reserved. Covered Bloom and bitmap caches are skipped. Beyond the default
compact minimum degree 13, sparse exact archives can fit when factorial-sized
ordinary tables cannot. Missing device coverage uses the memory-mapped host
archive.

Dense four-bit tables retain direct lookup for hot degrees. Automatic dense
allocation uses at most 35% of total VRAM and respects the workspace budget:
typically degree 12 on an 8 GiB GPU, degree 13 on 24 GiB and degree 14 on
141 GiB. Explicit numeric pattern or dense limits override this automatic
ceiling while retaining memory fitting. Dense coverage releases duplicate
compact allocations. Every context owns its device pointers; dense tables
can be copied through portable pinned staging buffers.

Four-bit tables require about 3.14 GiB through degree 13 and 43.74 GiB through
14. The compact D(14) archive instead needs about 256 MiB at its known
cardinality, compared with a 10.15 GiB ordinary bitmap. Compact tails support
both deletion generation and DFS contractions. Only complete upper tails
provide absence bounds; absent members have distance below the retained floor.

The pattern and membership degree controls default to auto. Numeric values cap
pattern tables or select a membership degree; zero disables that cache.
--gpu-dense-max-degree sets an explicit dense ceiling, --gpu-compact-min-degree
sets the first compact degree, and --no-compact-cache disables compact CUDA
caches while retaining compact persistence. Bitmaps and Bloom filters remain
fallback caches when complete compact coverage cannot fit.

NVRTC modules compile once per source, architecture and compiler options within
the process. Device allocations and modules remain independent per context.

Supplied-rank searches check that returned decision bytes agree with device
counters. Diagnostic packed keys, when supplied, are checked against ranks
on the GPU. A failed known diameter count is rejected before
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
The degree-14 cache covers all ordinary ranks, retains exact distances from
completed upper-tail stores, and uses a proven upper bound for absent entries.
It does not claim an exact distance for every lower-distance permutation.
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

`GPU_DEVICE` logs report each device's selected `pattern_degree`,
`pattern_bytes`, `membership_degree`, `membership_bytes` and workspace reserve.
After updating from the old 32-bit pattern layout, rebuild native PTX before
using `--no-build`; stale kernels are rejected at startup. The NOVA launcher
performs this build automatically.

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
are available, with two flush workers in the separate high-priority pool.
A 512 MiB cache supports the temporary construction database. Completed stores
use mapped compressed ranks, with no RocksDB point lookups or resident SST
block caches. Parallel parent scans split archives by record index and stream
through bounded queues. Full memtables flush automatically; small periodic
forced flushes are avoided.

At degree completion, all decisions and buffers drain and construction RocksDB
flushes to SST files. Finalization streams sorted ranks into the archive,
verifies counts and writes its checksum. The database is removed by default;
the archive and manifest are published by an atomic directory rename. Only
complete published stores can become parents.

Archive finalization automatically uses all CPU cores available to the JVM for
sets with at least one million records. Workers compress disjoint sorted rank
ranges and assemble their bit streams into the same archive format. `--compact-build-threads`
sets the worker count; `1` forces sequential finalization. This is separate
from the RocksDB insertion worker count. `COMPACT_PROGRESS` reports encoding,
assembly, index construction and checksum progress. Temporary encoded streams
use bounded buffers and are removed after assembly.


The [RTX 5090 profile](scripts/servers/rtx5090.sh) defaults to the full TD(18)
search. It detects all CPU threads, uses two generation
streams and 16 search slots, and selects a 128 GiB heap with 32 GiB persistence
staging. [rtx5090-build.sh](scripts/servers/rtx5090-build.sh) builds native
Blackwell PTX with CUDA 12.8+ within the driver's supported version. Set
`TDP_DATA_DIR` and optionally `TDP_LOG_DIR` to the attached volume. The detached
launcher accepts this profile and `--resume` to reuse completed layers.

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
  --lookup-chunk 262144 \
  --rocks-write-threads 22 --rocks-background-jobs 11 --rocks-flush-threads 4 \
  --rocks-memtable-mib 256 --rocks-sst-mib 256 --rocks-cache-mib 512 \
  --data-dir experiments/data/extension \
  --log-file experiments/logs/extension.log
```

`--no-gpu` selects CPU-only exact queries. `--base-degree` supports 0 through 9.
`--lookup-chunk` selects any positive host candidate slice size.
`--gpu-producer-batch` controls producer request sizes (default up to 4,096,
bounded by the search batch); `--gpu-min-batch-records` controls sparse dispatch.
`--rocks-subcompactions`, `--rocks-max-memtables` and `--rocks-max-open-files`
configure construction resources; -1 selects unlimited open-file caching.
`--no-build` reuses the already-built module and copied dependencies.
`--log-file` appends console output, including a run/PID header.
Progress includes a rolling `settled_per_minute` rate for canonical instances;
the `FINAL` line reports the ordinary count `|D(n)|` after all writes finish.

GPU and CPU occupancy depend on the limiting stage. Worker limits enable
parallel useful work; they do not guarantee 100% utilization of both processors
at every instant. Current configuration and log locations are in
[OPERATIONS.md](docs/OPERATIONS.md).

GPU modes require a CUDA driver and a driver-compatible NVRTC installation
exposing `libnvrtc.so.12`, but not `nvcc`. Set `GPU_NVRTC_LIB` to that library
directory when the system linker does not already expose it. Server launchers
derive the installed toolkit and NVRTC versions from the selected environment;
they do not encode a CUDA release. This workspace may reuse the existing
runtime under `../new-td/build/gpu/cuda/`; that project is not modified.

## Verification

The [compact archive and density audit](docs/COMPACT_SETS.md) describe the
production rank format for persistence, RAM and VRAM. Run `density-audit`
against completed fresh-format stores to report sizes and verify membership
and orbit counts for bounded small sets.

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
sparse pattern bounds, shared-context cancellation, and a consecutive GPU run
from an empty directory through degree 11 with automatic caches. That run
checks every diametral count (including 255,053 at degree 10 and 12,537,954 at
degree 11), independent BFS membership through degree 9, and reconstructed
orbit-weighted counts for every degree-10/11 result and support set. A separate
`D(9)->D(10)` test exercises the diameter jump: 1,143,270 generated candidates
recover 255,053 diametral permutations; with the qualifying prepass forced,
494 roots are resolved by the persisted-store lookup. General clearing tests
check `N=5,7,9` against exact small-degree distances. The `N=19` tests verify
the complete survivor/orbit classification, realizability of both oriented
representatives, their ten-move native clearing witnesses, and resolution by
the same production prepass.
The configured GPU library path is needed for these tests. Use the isolated
profile to protect Maven output from IDE-generated classes:

```bash
mvn -pl experiments -am -Pisolated-build -DgpuTests=true clean verify
```

Run only the consecutive degree-1-through-11 test with:

```bash
mvn -pl experiments -am -DgpuTests=true \
  '-Dtest=GpuExtensionTest#persistsEveryRequiredLayerThroughElevenOnGpu' \
  -Dsurefire.failIfNoSpecifiedTests=false verify
```

The mathematical derivation and audit procedure are in
[DIAMETRAL_EXTENSION.md](docs/DIAMETRAL_EXTENSION.md). The standalone audit remains
available with `bash experiments/run.sh audit --max-n 10`.

Multi-GPU tests exercise two independent CUDA contexts on one physical GPU,
parallel generation, shared search scheduling, pattern-copy lifetime,
per-slot workspace eviction, partial initialization failure and global
first-match cancellation. A separate physical two-device test runs when two
CUDA devices are available.
