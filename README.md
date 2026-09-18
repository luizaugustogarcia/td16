# TD(16) certificate computation

This repository contains the source code and reproduction instructions for the
computer-assisted portion of [*Twisted Bracelets for Sorting by
Transpositions: the Transposition Diameter of S_16*](https://arxiv.org/abs/2609.17493).
The paper proves that the transposition diameter of `S_16` is 9.

- `emitter` is the GPU-accelerated witness producer. It searches for sorting
  sequences and writes them to a RocksDB certificate database.
- `checker` is the CPU-only verifier. It independently reconstructs the case
  enumeration, reads the database, and replays every recorded witness without
  performing the emitter's search.

The certificate database is the only interface between the two programs. This
separation is intentional: regenerate it with the emitter when needed, then
verify it independently with the checker.

## Reproducibility

The complete emitted certificate can be downloaded from the
[certificate archive](https://td16.s3.us-east-2.amazonaws.com/certificate.tar),
which is approximately 90 GB. Ensure that sufficient disk space is available
for the download and extraction. Set `tdp.certificateDb` to the resulting
RocksDB directory when running the checker.

## Prerequisites

- Linux on x86_64 (the project uses the `linux64` RocksDB JNI artifact)
- JDK 21
- Maven 3.9 or newer
- For the emitter: CMake 3.24+, a C++17 compiler, the CUDA toolkit, and a
  CUDA-capable GPU supported by the toolkit

## Build

Build both modules from the repository root:

```bash
mvn verify
```

The emitter build configures and builds its CUDA JNI library as part of Maven's
`compile` phase. To build only the CPU checker, use:

```bash
mvn -pl checker verify
```

## Emit a certificate

Choose a writable directory for the RocksDB certificate (the default is
`./certificates` at the repository root), then run:

```bash
mvn -pl emitter compile exec:exec \
  -Dexec.mainClass=br.unb.cic.tdp.WitnessEmitter \
  -Dtdp.certificateDb="$PWD/certificates"
```

The default emitter configuration uses GPU device `0`, seven concurrent slots
per device, and a 1024 MiB GPU-memory budget per slot. For a machine with four
16 GiB GPUs, the following configuration uses devices `0` through `3`, 16
slots per device (64 total), an 850 MiB GPU-memory budget per slot, periodic
statistics every 10 minutes, and 240 twisted-bracelet workers:

```bash
mvn -pl emitter compile exec:exec \
  -Dexec.mainClass=br.unb.cic.tdp.WitnessEmitter \
  -Dtdp.certificateDb="$PWD/certificates" \
  -Dtdp.gpuDevices=0,1,2,3 \
  -Dtdp.gpuSlots=16 \
  -Dtdp.gpuBudgetMB=850 \
  -Dtdp.gpuStatsPeriodSeconds=600 \
  -Dtdp.gpuQueueCapacity=512 \
  -Dtdp.twistedBraceletWorkers=240
```

The database can be large and is intentionally ignored by Git. Do not run the
emitter against a certificate directory you need to preserve unless you intend
to update it.

## Check a certificate

Run the checker against an emitted or otherwise supplied certificate database:

```bash
mvn -pl checker compile exec:java \
  -Dexec.mainClass=br.unb.cic.tdp.ProofTD16 \
  -Dtdp.certificateDb="$PWD/certificates" \
  -Dtdp.twistedBraceletWorkers=240 \
  -Dtdp.certificateStatsPeriodSeconds=600 \
  -Dtdp.twistedBraceletBatchSize=500
```

The checker does not require CUDA. It fails if a required witness is missing or is invalid.
This is the configuration used for the observed CPU-only checking time below;
omit or adjust the worker and batch settings for other machines.

## Observed runtime

The following are approximate wall-clock times from one complete run; they are
provided for reproducibility planning rather than as a general benchmark.

| Task | Hardware and execution mode | Time |
| --- | --- | --- |
| Certificate generation | Four NVIDIA RTX 4080 SUPER GPUs (16 GiB each), AMD EPYC 7C13 CPU, and 128 GiB RAM; emitter configuration above | about 7 hours |
| Certificate checking | The same machine, CPU only | about 2 hours |

Actual times will vary with the software stack, storage performance, and
machine load.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
