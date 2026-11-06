package br.unb.cic.tdp.experiments;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuCanonicalBloomTest {
    @Test void roundsToAPowerOfTwoWithoutUnderallocating() {
        assertEquals(1024,GpuCanonicalBloom.bitsForRecords(1));
        assertEquals(1024,GpuCanonicalBloom.bitsForRecords(102));
        assertEquals(2048,GpuCanonicalBloom.bitsForRecords(103));
        assertEquals(1L<<31,GpuCanonicalBloom.bitsForRecords(174_944_393));
    }

    @Test void rejectsEmptyAndUnreasonablyLargeSources() {
        assertThrows(IllegalArgumentException.class,()->GpuCanonicalBloom.bitsForRecords(0));
        assertThrows(IllegalArgumentException.class,()->GpuCanonicalBloom.bitsForRecords(1L<<38));
    }
}
