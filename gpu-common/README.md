# Shared GPU device layout

`GpuDeviceLayout` was extracted unchanged from the emitter so that both the
emitter and the experiments use the same validation and round-robin mapping
of logical slots to selected CUDA device ordinals. Slot counts are per device.
This module has no CUDA or native dependencies. Each client validates actual
device availability and manages its own contexts, streams and memory.

```bash
mvn -pl gpu-common -am verify
```
