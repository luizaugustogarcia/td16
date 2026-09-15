package br.unb.cic.tdp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnorientedCyclesTest {

    @Test
    void impossibleRootPartitionsAreVerifiedByGeneration() {
        for (final var partition : ProofTD16.IMPOSSIBLE_PARTITIONS) {
            assertEquals(0, UnorientedCycles.countGenerated(partition));
        }
    }

    @Test
    void detectsPartitionsThatForceAnOpenGate() {
        assertTrue(UnorientedCycles.hasForcedTargetFixedPoint(new int[]{3, 1}));
        assertFalse(UnorientedCycles.hasForcedTargetFixedPoint(new int[]{2, 2}));
    }
}
