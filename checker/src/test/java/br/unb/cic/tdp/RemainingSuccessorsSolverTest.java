package br.unb.cic.tdp;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemainingSuccessorsSolverTest {

    @Test
    void enumerateAnnotatedPartitionsRemovesFixedPointsAndExpandsOrientationChoices() {
        final var annotatedPartitions = RemainingSuccessorsSolver.enumerateAnnotatedPartitions(new int[]{13, 3, 1, 1});

        assertEquals(4, annotatedPartitions.size());
        assertArrayEquals(new int[]{13, 3}, RemainingSuccessorsSolver.removeFixedPoints(new int[]{13, 3, 1, 1}));

        final var actual = annotatedPartitions.stream()
                .map(RemainingSuccessorsSolver::formatAnnotatedPartition)
                .collect(Collectors.toSet());

        assertEquals(Set.of("[13-O, 3-O]", "[13-O, 3-U]", "[13-U, 3-O]", "[13-U, 3-U]"), actual);
    }

    @Test
    void enumerateAnnotatedPartitionsKeepsTwoCyclesUnoriented() {
        final var annotatedPartitions = RemainingSuccessorsSolver.enumerateAnnotatedPartitions(new int[]{7, 5, 2, 1});

        assertEquals(4, annotatedPartitions.size());
        assertTrue(annotatedPartitions.stream().allMatch(partition -> {
            final int[] sizes = partition.partition();
            final boolean[] oriented = partition.orientedByPart();
            for (var i = 0; i < sizes.length; i++) {
                if (sizes[i] == 2 && oriented[i]) {
                    return false;
                }
            }
            return true;
        }));
    }

    @Test
    void enumerateAnnotatedPartitionsDeduplicatesEqualLengthCycles() {
        final var annotatedPartitions = RemainingSuccessorsSolver.enumerateAnnotatedPartitions(
                new int[]{5, 5, 4, 2, 1});

        assertEquals(6, annotatedPartitions.size());
        final var actual = annotatedPartitions.stream()
                .map(RemainingSuccessorsSolver::formatAnnotatedPartition)
                .collect(Collectors.toSet());

        assertTrue(actual.contains("[5-U, 5-O, 4-O, 2-U]"));
        assertTrue(actual.contains("[5-U, 5-O, 4-U, 2-U]"));
        assertTrue(actual.stream().noneMatch(value -> value.startsWith("[5-O, 5-U")));
    }

    @Test
    void enumerateAnnotatedPartitionsCanonicalizesThreeEqualCyclesWithUnorientedFirst() {
        final var annotatedPartitions = RemainingSuccessorsSolver.enumerateAnnotatedPartitions(
                new int[]{4, 4, 4, 2, 1, 1, 1});

        assertEquals(Set.of(
                        "[4-U, 4-U, 4-U, 2-U]",
                        "[4-U, 4-U, 4-O, 2-U]",
                        "[4-U, 4-O, 4-O, 2-U]",
                        "[4-O, 4-O, 4-O, 2-U]"),
                annotatedPartitions.stream()
                        .map(RemainingSuccessorsSolver::formatAnnotatedPartition)
                        .collect(Collectors.toSet()));
    }

    @Test
    void containsOrientedThreeDetectsImmediateC3Resolutions() {
        final var annotatedPartitions = RemainingSuccessorsSolver.enumerateAnnotatedPartitions(new int[]{13, 3, 1});
        final long immediate = annotatedPartitions.stream()
                .filter(RemainingSuccessorsSolver::containsOrientedThree)
                .count();

        assertEquals(2, immediate);
    }

    @Test
    void threeNormMatchesTheExpectedTarget() {
        assertEquals(7, RemainingSuccessorsSolver.threeNorm(new int[]{13, 3}));
        assertEquals(7, RemainingSuccessorsSolver.threeNorm(new int[]{8, 3, 3, 2}));
        assertEquals(6, RemainingSuccessorsSolver.threeNorm(new int[]{5, 3, 3, 3, 3}));
    }
}
