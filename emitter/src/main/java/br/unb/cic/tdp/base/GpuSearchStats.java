package br.unb.cic.tdp.base;

import java.util.Arrays;

/**
 * Windowed GPU-slot statistics. All updates and snapshots are synchronized so
 * counts and durations cannot be split across adjacent reporting windows.
 */
final class GpuSearchStats {

    record DeviceSnapshot(
            int device,
            long started,
            long completed,
            long failed,
            long queueWaitNanos,
            long busyNanos
    ) {
    }

    record Snapshot(
            long elapsedNanos,
            long started,
            long completed,
            long failed,
            long queueWaitNanos,
            long busyNanos,
            int totalSlots,
            DeviceSnapshot[] devices
    ) {
        Snapshot {
            devices = devices.clone();
        }

        @Override
        public DeviceSnapshot[] devices() {
            return devices.clone();
        }
    }

    private final GpuDeviceLayout layout;
    private final long[] slotActiveSinceNanos;
    private final long[] deviceStarted;
    private final long[] deviceCompleted;
    private final long[] deviceFailed;
    private final long[] deviceQueueWaitNanos;
    private final long[] deviceBusyNanos;

    private long windowStartedNanos;

    GpuSearchStats(final GpuDeviceLayout layout, final long startedNanos) {
        this.layout = layout;
        this.windowStartedNanos = startedNanos;
        this.slotActiveSinceNanos = new long[layout.totalSlots()];
        this.deviceStarted = new long[layout.deviceCount()];
        this.deviceCompleted = new long[layout.deviceCount()];
        this.deviceFailed = new long[layout.deviceCount()];
        this.deviceQueueWaitNanos = new long[layout.deviceCount()];
        this.deviceBusyNanos = new long[layout.deviceCount()];
        Arrays.fill(slotActiveSinceNanos, -1L);
    }

    synchronized void acquired(final int slot, final long queuedAtNanos, final long acquiredAtNanos) {
        if (slotActiveSinceNanos[slot] >= 0) {
            throw new IllegalStateException("GPU slot " + slot + " was acquired while already active");
        }
        final int deviceIndex = layout.deviceIndexForSlot(slot);
        deviceStarted[deviceIndex]++;
        deviceQueueWaitNanos[deviceIndex] += Math.max(0, acquiredAtNanos - queuedAtNanos);
        slotActiveSinceNanos[slot] = acquiredAtNanos;
    }

    synchronized void released(final int slot, final long releasedAtNanos, final boolean failed) {
        final long activeSince = slotActiveSinceNanos[slot];
        if (activeSince < 0) {
            throw new IllegalStateException("GPU slot " + slot + " was released while inactive");
        }
        final int deviceIndex = layout.deviceIndexForSlot(slot);
        deviceBusyNanos[deviceIndex] += Math.max(0, releasedAtNanos - activeSince);
        deviceCompleted[deviceIndex]++;
        if (failed) {
            deviceFailed[deviceIndex]++;
        }
        slotActiveSinceNanos[slot] = -1L;
    }

    synchronized Snapshot snapshot(final long nowNanos) {
        final long elapsedNanos = Math.max(1, nowNanos - windowStartedNanos);

        for (int slot = 0; slot < slotActiveSinceNanos.length; slot++) {
            final long activeSince = slotActiveSinceNanos[slot];
            if (activeSince >= 0) {
                final int deviceIndex = layout.deviceIndexForSlot(slot);
                deviceBusyNanos[deviceIndex] += Math.max(0, nowNanos - activeSince);
                slotActiveSinceNanos[slot] = nowNanos;
            }
        }

        long started = 0;
        long completed = 0;
        long failed = 0;
        long queueWaitNanos = 0;
        long busyNanos = 0;
        final int[] devices = layout.devices();
        final var deviceSnapshots = new DeviceSnapshot[devices.length];

        for (int deviceIndex = 0; deviceIndex < devices.length; deviceIndex++) {
            started += deviceStarted[deviceIndex];
            completed += deviceCompleted[deviceIndex];
            failed += deviceFailed[deviceIndex];
            queueWaitNanos += deviceQueueWaitNanos[deviceIndex];
            busyNanos += deviceBusyNanos[deviceIndex];
            deviceSnapshots[deviceIndex] = new DeviceSnapshot(
                    devices[deviceIndex],
                    deviceStarted[deviceIndex],
                    deviceCompleted[deviceIndex],
                    deviceFailed[deviceIndex],
                    deviceQueueWaitNanos[deviceIndex],
                    deviceBusyNanos[deviceIndex]);
        }

        Arrays.fill(deviceStarted, 0);
        Arrays.fill(deviceCompleted, 0);
        Arrays.fill(deviceFailed, 0);
        Arrays.fill(deviceQueueWaitNanos, 0);
        Arrays.fill(deviceBusyNanos, 0);
        windowStartedNanos = nowNanos;

        return new Snapshot(elapsedNanos, started, completed, failed, queueWaitNanos,
                busyNanos, layout.totalSlots(), deviceSnapshots);
    }
}
