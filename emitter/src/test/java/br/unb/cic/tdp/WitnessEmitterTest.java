package br.unb.cic.tdp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WitnessEmitterTest {

    @Test
    void successorCorpusMatchesProofTD16() {
        final var partitions = WitnessEmitter.prescribedReducedSuccessorPartitions();
        final var partitionKeys = partitions.stream()
                .map(Arrays::toString)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "[13, 3]",
                "[11, 5]",
                "[9, 7]",
                "[9, 3, 2, 2]",
                "[8, 3, 3, 2]",
                "[7, 5, 2, 2]",
                "[7, 4, 3, 2]",
                "[7, 3, 3, 3]",
                "[6, 5, 3, 2]",
                "[6, 4, 3, 3]",
                "[5, 5, 4, 2]",
                "[5, 5, 3, 3]",
                "[5, 4, 4, 3]",
                "[5, 3, 2, 2, 2, 2]",
                "[4, 3, 3, 2, 2, 2]",
                "[3, 3, 3, 3, 2, 2]"), partitionKeys);
        assertEquals(16, partitions.size());
        assertEquals(52, partitions.stream()
                .map(WitnessEmitterTest::withFixedPoint)
                .mapToInt(RemainingSuccessorsSolver::nativeAnnotationCount)
                .sum());
    }

    private static int[] withFixedPoint(final int[] reducedPartition) {
        final var successor = Arrays.copyOf(reducedPartition, reducedPartition.length + 1);
        successor[successor.length - 1] = 1;
        return successor;
    }
}
