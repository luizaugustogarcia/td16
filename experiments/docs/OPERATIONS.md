# Current production operations

The production project is installed at `/workspace/td` on
`ssh -p 38022 root@77.67.18.191`.

Its configuration is [scripts/servers/quad-3090.sh](../scripts/servers/quad-3090.sh):

- Four RTX 3090 GPUs, eight search slots per GPU.
- 184 host workers and 96 asynchronous write workers.
- 128 GiB maximum JVM heap with G1 and NUMA support.
- 48 GiB persistence staging budget and a 4 GiB shared aggregate.
- Native write batches up to 8 MiB; partial dispatch after five seconds.
- RocksDB WAL disabled, 2 GiB memtables/SST targets, 48 background jobs,
  16 flush workers and a 16 GiB cache per physical database.

The search uses `diameter --max-n 18 --pattern-horizon 17`. It determines
TD(18) by searching distance 11, using complete canonical parent/support
layers. Mathematical coverage and count checks are part of production.

Current paths:

```text
/workspace/td/experiments/data/search/          complete inputs and current output
/workspace/td/experiments/logs/extension.log    application progress
/workspace/td/experiments/logs/extension.pid    Java PID
/workspace/td/experiments/logs/resources.log    CPU, GPU, memory and disk samples
/workspace/td/experiments/logs/gc.log           JVM garbage collection
```

Read progress with:

```bash
ssh -p 38022 root@77.67.18.191 \
  'tail -f /workspace/td/experiments/logs/extension.log'
```

`EXTEND` identifies the exact output distance and parent distance range.
An `n` value alone does not distinguish a diametral layer from a support layer.
`EXTENSION_PROGRESS` reports parent coverage, settled candidates and write
backlog. `SET_COMPLETE` is emitted after output writes drain and the store is
published. `DIAMETER_RESULT` records the final degree-18 decision.

Parent throughput estimates the remaining traversal time only for the current
layer. Later layers have different input sizes, deletion rejection rates and
exact-search costs. CUDA activity and allocated VRAM are reported separately
in the resource log.

Build the native kernels with the installed CUDA toolkit:

```bash
mvn -pl experiments -am -Pcuda-native -Dtdp.cudaArchitectures=86 \
  -DskipTests package dependency:copy-dependencies
```

After deploying this checkout and building it, the detached launcher is
`bash experiments/scripts/servers/start-production.sh quad-3090.sh`. It
requires a fresh production data directory and application log. Existing
production inputs should be preserved when resuming through `run.sh`.
