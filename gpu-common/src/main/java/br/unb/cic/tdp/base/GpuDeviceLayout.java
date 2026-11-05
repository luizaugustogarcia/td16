package br.unb.cic.tdp.base;

import java.util.Arrays;
import java.util.HashSet;

/**
 * Validated, equal-capacity mapping from logical search slots to CUDA devices.
 */
public final class GpuDeviceLayout {

    private final int[] devices;
    private final int slotsPerDevice;
    private final int totalSlots;

    public GpuDeviceLayout(final int[] devices, final int slotsPerDevice) {
        if (devices == null || devices.length == 0) {
            throw new IllegalArgumentException("devices must contain at least one CUDA device ordinal");
        }
        if (slotsPerDevice <= 0) {
            throw new IllegalArgumentException("slotsPerDevice must be positive");
        }

        validateDistinctDevices(devices);
        this.devices = devices.clone();
        this.slotsPerDevice = slotsPerDevice;
        try {
            this.totalSlots = Math.multiplyExact(devices.length, slotsPerDevice);
        } catch (final ArithmeticException e) {
            throw new IllegalArgumentException("total GPU slot count is too large", e);
        }
    }

    public static int[] parseDevices(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GPU device list must not be blank");
        }
        try {
            return Arrays.stream(value.split(",", -1))
                    .map(String::trim)
                    .mapToInt(token -> {
                        if (token.isEmpty()) {
                            throw new IllegalArgumentException("GPU device list contains an empty ordinal");
                        }
                        return Integer.parseInt(token);
                    })
                    .toArray();
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("GPU device list contains a non-integer ordinal: " + value, e);
        }
    }

    private static void validateDistinctDevices(final int[] devices) {
        final var seen = new HashSet<Integer>(devices.length);
        for (final var device : devices) {
            if (device < 0) {
                throw new IllegalArgumentException(
                        "devices must contain only non-negative CUDA device ordinals");
            }
            if (!seen.add(device)) {
                throw new IllegalArgumentException(
                        "devices must contain distinct CUDA device ordinals; duplicate: " + device);
            }
        }
    }

    public int[] devices() {
        return devices.clone();
    }

    public int deviceCount() {
        return devices.length;
    }

    public int slotsPerDevice() {
        return slotsPerDevice;
    }

    public int totalSlots() {
        return totalSlots;
    }

    public int deviceForSlot(final int slot) {
        checkSlot(slot);
        return devices[slot % devices.length];
    }

    int deviceIndexForSlot(final int slot) {
        checkSlot(slot);
        return slot % devices.length;
    }

    private void checkSlot(final int slot) {
        if (slot < 0 || slot >= totalSlots) {
            throw new IllegalArgumentException("GPU slot is outside the configured range: " + slot);
        }
    }
}
