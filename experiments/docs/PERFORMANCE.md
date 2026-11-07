# Fresh compact archive comparison

Measured on 7 November 2026, sequentially on the same RTX 4070 Laptop GPU
(8 GiB), with NVRTC 12.6 and temporary empty input directories. The previous
implementation was HEAD bbe2f92 in an isolated temporary checkout. The compact
implementation used Maven's isolated-build profile. Neither run used native
PTX or precomputed layers.

Both ran GpuExtensionTest#persistsEveryRequiredLayerThroughElevenOnGpu with
automatic caches, four GPU slots, 512-parent batches, 32,768 search batches,
quantum 4,096 and the test's identical RocksDB settings. These are single runs,
so small kernel-time differences may reflect laptop clock variation.

| Measurement | Previous | Compact | Previous / compact |
| --- | ---: | ---: | ---: |
| Complete test, including independent verification | 35.80 s | 9.407 s | 3.81× |
| Sum of 28 computation stages, including support backfills | 31.912 s | 7.081 s | 4.51× |
| D(11) computation and publication | 1.514 s | 0.660 s | 2.29× |
| Sum of generation kernel time | 60.238 ms | 53.138 ms | 1.13× |
| D(11) completed membership payload | 6,078,593 SST bytes | 594,704 archive bytes | 10.22× |

Stage timing includes GPU setup, computation, queue drain, flush and publication.
The old implementation recompiled its CUDA source for every degree. The new
process caches PTX by exact source, architecture and options. Avoiding repeated
compilation supplies much of the complete-run gain. Smaller generation packets,
rank-only queues and rank-ordered construction also reduce transport and
staging work. The new archive adds finalization and checksum work.

Both computations produced the same degree-1-through-11 diametral counts:

    1, 1, 1, 1, 31, 45, 1513, 2836, 114327, 255053, 12537954

D(11) has 525,015 canonical records and 12,537,954 ordinary permutations.
Tests independently checked BFS membership through degree 9 and reconstructed
degree-10/11 result and support orbit counts. Completed compact layers contain
the archive and manifest with no construction database.

The clean full check passed 100 experiment tests (99 executed, one physical
two-GPU check skipped because this machine has one GPU), plus three gpu-common
tests. Compact CUDA tests force canonical compressed lookup below the default
degree threshold, independently of the small dense caches used in 1–11.

The standalone comparison was run with:

    mvn -q -pl experiments -am -Pisolated-build -DgpuTests=true '-Dtest=GpuExtensionTest#persistsEveryRequiredLayerThroughElevenOnGpu' -Dsurefire.failIfNoSpecifiedTests=false verify

The baseline used the same command without the new isolated-build profile.
The 1–11 test now requires -DgpuExtendedTests=true; routine validation stops at 9.
The full clean check was:

    mvn -q -pl experiments -am -Pisolated-build -DgpuTests=true clean verify

The tests used a driver-compatible NVRTC library selected by LD_LIBRARY_PATH.
All computation data was temporary. No production run was started.

Higher-degree density calculations are in [COMPACT_SETS.md](COMPACT_SETS.md).
At the known D(14) cardinality the canonical archive is 40.55 times smaller
than the former ordinary bitmap. Large-degree generation/DFS throughput has
not been measured by this bounded test and depends on target hardware and
whether the useful compact tail replaces host lookups or an existing dense
table.

## Parallel archive finalization

The rank-range builder was compared with the previous serial compact builder
on the same prepared RocksDB source: 12,000,000 synthetic, uniformly spaced
degree-14 ranks in an exact layer. This is a storage benchmark, not a distance
computation. Each setting ran in a separate JVM with a 512 MiB heap and two
consecutive builds. The source fits in the filesystem cache; timings include
database opening, compression, assembly, indexes, checksum and archive flush.
The production computation had exited before these measurements.

| Builder | First build | Second build | Mean | Speedup over previous mean |
| --- | ---: | ---: | ---: | ---: |
| Previous serial compact builder | 2.715 s | 2.519 s | 2.617 s | 1.00× |
| New serial builder, reused key/value buffers | 2.012 s | 1.959 s | 1.986 s | 1.32× |
| New rank-range builder, four workers | 0.856 s | 0.662 s | 0.759 s | 3.45× |
| New rank-range builder, eight workers | 0.766 s | 0.480 s | 0.623 s | 4.20× |
| New rank-range builder, all 22 available cores | 0.822 s | 0.393 s | 0.608 s | 4.31× |

All ten builds produced the same 24,011,184-byte archive and full-file
SHA-256:

    99d53aa689276055405632ed3fe0ae0a2203159a9bd67117f46cbe60896a1273

These short runs include JVM warmup and laptop clock variation. Multi-billion
record finalization can also be limited by disk bandwidth, shard balance and
the remaining serial index/checksum passes; these speedups are not an ETA
multiplier for a production computation. Temporary shard files require roughly
one extra compressed payload.

The default automatically uses all CPU cores available to the JVM for at least
1,000,000 records; smaller layers stay sequential. The runner accepts
`--compact-build-threads N` and `--compact-parallel-threshold N`; one worker forces
sequential finalization.
Encoding, merging, index construction and checksum report progress every two
seconds.

The full CPU suite passed. The fresh 1–11 GPU computation also passed with
parallel finalization forced on every eligible layer (four workers, threshold
zero), producing the same known counts and a 594,704-byte D(11) archive.
A subsequent fresh 1–11 GPU computation and all 12 focused archive tests passed
with automatic worker selection and threshold zero. The JVM detected all 22
available cores; archives with sufficient records used 22 workers.
Focused tests compare complete sequential/parallel archive bytes for exact and
labeled sets, sparse 53-bit ranks, every bit alignment, shared boundary words
and failed-build cleanup.

The manual storage benchmark is
`br.unb.cic.tdp.experiments.CompactArchiveBenchmark` in the test classes:

    java -Xmx512m --enable-native-access=ALL-UNNAMED -cp "$BENCHMARK_CLASSPATH" br.unb.cic.tdp.experiments.CompactArchiveBenchmark prepare /tmp/compact-benchmark-source 12000000
    java -Xmx512m --enable-native-access=ALL-UNNAMED -cp "$BENCHMARK_CLASSPATH" br.unb.cic.tdp.experiments.CompactArchiveBenchmark build /tmp/compact-benchmark-source 8 2

`BENCHMARK_CLASSPATH` must include the selected implementation JAR, isolated
experiment test classes and copied dependency JARs. The utility only rebuilds
directories marked as synthetic benchmark sources. The sequential baseline
used the unchanged production JAR; the new implementation and all checks used
`-Pisolated-build -Dtdp.isolatedBuildRoot=/tmp/td16-parallel-build`.
