# Compact canonical rank archives

Completed layers persist canonical permutation ranks in an immutable Elias–Fano
archive. The same layout supports memory-mapped CPU reads and exact CUDA
membership. RocksDB temporarily constructs sorted, unique ranks; its database
is removed after archive finalization, before publication.

This is a fresh format. Old omega-key stores are rejected. Start computations
in a fresh data directory; there is no conversion or legacy reader.

## Representation and correctness

The representative remains the least omega image vector under the existing
fixed-sigma toric/reflection action. Its anchored word is encoded with a
collision-free Lehmer rank in 0..n!-1. Through degree 18, complete ranks require
at most 53 bits. Degree-14 construction keys occupy 5 bytes instead of the
former 10-byte packed omega.

The archive splits sorted ranks into packed low fields and unary high fields.
Membership selects a high bucket and searches its complete low fields. Prefix
zero counts per eight high words and zero-select samples every 128 zeros
accelerate selection. Clustered inputs fall back to the prefix directory after
16 scanned words. Every accepting lookup compares a complete rank.

The 400-byte header records degree, distance coverage, cardinality, 64-bit
offsets, aggregate canonical/orbit counts and a SHA-256 payload digest. Exact
archives need no per-record distance or orbit weight. Tail archives retain
four-bit distance labels. CPU reads map 1 GiB segments with 64-bit indexes.

Only complete contiguous upper tails provide CUDA absence bounds: absence
proves distance below the retained floor. An isolated exact layer can only
rule out its own distance. Small GPU tails may expand ordinary orbit ranks to
avoid canonicalization; larger tails retain canonical rank compression.

## Density at higher degrees

Cardinalities below come from previous completed manifests. Sizes are
calculations of the production layout, including its header, padding, prefix
directory and zero-select samples. They are not fresh large-degree run results.
Runtime, generation and DFS allocations are excluded.

| Exact set | Canonical records | Previous ordinary bitmap | New archive | Reduction |
| --- | ---: | ---: | ---: | ---: |
| D(13) | 19 | 742.32 MiB | 504 bytes | 1,544,400× |
| D(14) | 174,944,393 | 10.15 GiB | 256.27 MiB | 40.55× |
| E(14,7) | 1,597,362,639 | 10.15 GiB | 1.65 GiB | 6.13× |
| D(15) | 2,036 | 152.23 GiB | 8.38 KiB | 19,042,322× |

E(14,7) and D(14) together require 2,045,077,624 bytes (1.90 GiB).
The former parent Bloom filter used 4 GiB and required host confirmation for
possible matches. The resident archives answer positive and negative queries
exactly.

## Construction and computation

Fixed-width big-endian construction keys make RocksDB iteration match archive
rank order. Finalization streams that order without an external sort or a
cardinality-sized Java array. It verifies strict order, distance/orbit metadata
and aggregate counts, flushes the archive and digest, and publishes atomically.
Only the archive and manifest survive publication by default.

Archives with at least one million records automatically use all CPU cores
available to the JVM. Approximate SST sizes balance disjoint rank ranges without
an extra record scan. Each worker reads its range once and produces compressed low/high
bit streams and optional labels. Workers then assemble those streams into the
unchanged archive layout; shared boundary words are joined after the workers
finish. Prefix directories, select samples and the SHA-256 digest follow.
Small layers retain the sequential path. Both paths reuse native key/value
buffers instead of allocating two arrays per record.

Worker buffers are bounded. Temporary range files require roughly one extra
compressed payload, and are removed during assembly or on failure. Progress
lines report encode records, merge bytes, index words and checksum bytes, so
finalization remains visible after generation counters stop.

GPU parents travel as a distance byte plus an eight-byte rank. Generation
packets are ten bytes: rank, decision metadata and orbit weight. Pending queues
and writers retain ranks without duplicate packed omega. Diagnostic validation
keys allocate their host/device buffers only when supplied. NVRTC compilation
is cached per source, architecture and options within the process; each CUDA
context owns its module and device allocations.

## Automatic GPU caches

Useful complete compact tails load before redundant Bloom or bitmap caches.
Dense four-bit tables retain fast direct lookup at hot degrees. Automatic dense
allocations are capped at 35% of total VRAM and by the existing workspace budget.
Typical ceilings are degree 12 on 8 GiB, degree 13 on 24 GiB and degree 14 on
141 GiB. Actual coverage depends on completed layers, free VRAM and reserved
workspaces. Dense tables release compact allocations whose tails they cover.

Numeric pattern/dense overrides retain their explicit cap and still obey
available-memory checks.

| Runner option | Meaning |
| --- | --- |
| --gpu-dense-max-degree N | Explicit dense-table ceiling, 0..18 |
| --gpu-compact-min-degree N | First compact GPU degree, 1..18; default 13 |
| --no-compact-cache | Disable compact CUDA caches; persistence stays compact |
| --compact-keep-rocks | Retain construction RocksDB for debugging |
| --compact-build-threads N | Positive archive worker count; default all available CPU cores; 1 forces sequential |
| --compact-parallel-threshold N | Minimum archive cardinality for parallel work; default 1,000,000 |

## Audit and validation

Audit completed fresh-format stores with:

    bash experiments/run.sh density-audit --data-dir /path/to/fresh/layers --density-max-records 100000

The audit reports production archive sizes and checks every rank, canonicality,
orbit counts and 10,000 deterministic membership probes for bounded small sets.
A zero record limit skips nonempty audits.

Tests cover exact/labeled-tail reads, parallel scans, corrupt/truncated archives,
duplicate rejection, empty sets, degree-18 fields, exhaustive rank encoding
through degree 8, CUDA membership, independent contexts, workspace budgets,
dense replacement and DFS against independent BFS. The consecutive fresh
1–11 computation checks every known diametral count, BFS membership through
9 and reconstructed orbit counts for degree-10/11 result and support sets.
Parallel-builder checks also compare complete archive bytes with the sequential
builder, exercise every bit alignment and shared-word boundary, verify sparse
53-bit ranks and check cleanup after publication rejection.
The routine fresh GPU computation now stops at degree 9. The extended 1–11
check requires `-DgpuExtendedTests=true` in addition to `-DgpuTests=true`.

With a driver-compatible NVRTC on the library path:

    mvn -pl experiments -am -Pisolated-build -DgpuTests=true clean verify

The isolated profile protects Maven output from IDE-generated classes. The
runner builds and executes the isolated packaged JAR. TDP_EXPERIMENT_BUILD_ROOT
can override its default temporary build directory.

Throughput beyond degree 11 requires a fresh computation on the target hardware.
See [the performance comparison](PERFORMANCE.md) for measured 1–11 results.
