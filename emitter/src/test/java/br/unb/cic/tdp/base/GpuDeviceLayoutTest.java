package br.unb.cic.tdp.base;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GpuDeviceLayoutTest {

    @Test
    void parsesWhitespaceAndMapsEqualSlotsRoundRobin() {
        final var devices = GpuDeviceLayout.parseDevices(" 2, 5 ");
        final var layout = new GpuDeviceLayout(devices, 3);

        assertArrayEquals(new int[]{2, 5}, layout.devices());
        assertEquals(6, layout.totalSlots());
        assertEquals(2, layout.deviceForSlot(0));
        assertEquals(5, layout.deviceForSlot(1));
        assertEquals(2, layout.deviceForSlot(2));
        assertEquals(5, layout.deviceForSlot(5));
    }

    @Test
    void rejectsDuplicateOrMalformedDeviceLists() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDeviceLayout(new int[]{0, 0}, 7));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDeviceLayout(new int[]{-1}, 7));
        assertThrows(IllegalArgumentException.class,
                () -> GpuDeviceLayout.parseDevices("0,,1"));
        assertThrows(IllegalArgumentException.class,
                () -> GpuDeviceLayout.parseDevices("gpu0"));
        assertThrows(IllegalArgumentException.class,
                () -> GpuDeviceLayout.parseDevices(" "));
    }

    @Test
    void rejectsInvalidOrOverflowingSlotCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDeviceLayout(new int[]{0}, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDeviceLayout(new int[]{0, 1}, Integer.MAX_VALUE));
    }
}
