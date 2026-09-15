package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.SortingSearch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Native witness corpus for the prescribed one-fixed-point successors. */
final class RemainingSuccessorsSolver {
    private static final int FIXED_POINT = 1;
    private static final int TWO_CYCLE = 2;

    private static final List<ResidualObstructionEntry> nativeMisses =
            Collections.synchronizedList(new ArrayList<>());

    record ResidualObstructionEntry(CyclicTargetPair pair, int maxMoves) {
    }

    private RemainingSuccessorsSolver() {
    }

    static List<ResidualObstructionEntry> getAndClearResidualObstructions() {
        final var result = new ArrayList<>(nativeMisses);
        nativeMisses.clear();
        return result;
    }

    /**
     * Enumerates only the native one-fixed-point obligations.  An annotation
     * with an oriented 3-cycle has no native certificate obligation here.
     */
    static void emitNativeSuccessorPartition(final int[] successorPartition, final int maxMoves) {
        validateSuccessorPartition(successorPartition);
        if (maxMoves <= 0) {
            throw new IllegalArgumentException("Expected a positive move budget");
        }
        final var reduced = removeFixedPoints(successorPartition);
        final float minRate = 2 / (maxMoves / (float) threeNorm(reduced));
        for (final var annotation : enumerateAnnotatedPartitions(successorPartition)) {
            if (!containsOrientedThree(annotation)) {
                emitAnnotation(annotation, minRate, maxMoves);
            }
        }
    }

    private static void emitAnnotation(final AnnotatedPartition annotation,
                                       final float minRate,
                                       final int maxMoves) {
        DirectTwistedBraceletGenerator.generateRepresentativesConcurrently(
                annotation.partition(), annotation.orientedByPart(), () -> { }, pair -> {
                    final var moves = SortingSearch.searchForSorting(pair, minRate, maxMoves);
                    if (moves.isEmpty()) {
                        nativeMisses.add(new ResidualObstructionEntry(pair, maxMoves));
                    }
                });
    }

    private static List<AnnotatedPartition> enumerateAnnotatedPartitions(final int[] successorPartition) {
        final var reduced = removeFixedPoints(successorPartition);
        final var annotations = new ArrayList<AnnotatedPartition>();
        enumerateOrientations(reduced, new boolean[reduced.length], 0, annotations);
        return annotations;
    }

    static int nativeAnnotationCount(final int[] successorPartition) {
        return Math.toIntExact(enumerateAnnotatedPartitions(successorPartition).stream()
                .filter(annotation -> !containsOrientedThree(annotation))
                .count());
    }

    private static void enumerateOrientations(final int[] partition,
                                              final boolean[] orientedByPart,
                                              final int index,
                                              final List<AnnotatedPartition> result) {
        if (index == partition.length) {
            result.add(new AnnotatedPartition(partition, orientedByPart));
            return;
        }
        if (partition[index] == TWO_CYCLE) {
            orientedByPart[index] = false;
            enumerateOrientations(partition, orientedByPart, index + 1, result);
            return;
        }
        final boolean mayBeUnoriented = index == 0
                || partition[index] != partition[index - 1]
                || !orientedByPart[index - 1];
        if (mayBeUnoriented) {
            orientedByPart[index] = false;
            enumerateOrientations(partition, orientedByPart, index + 1, result);
        }
        orientedByPart[index] = true;
        enumerateOrientations(partition, orientedByPart, index + 1, result);
    }

    private static boolean containsOrientedThree(final AnnotatedPartition annotation) {
        for (var index = 0; index < annotation.partition().length; index++) {
            if (annotation.partition()[index] == 3 && annotation.orientedByPart()[index]) {
                return true;
            }
        }
        return false;
    }

    private static int[] removeFixedPoints(final int[] partition) {
        final int fixedPointCount = (int) Arrays.stream(partition)
                .filter(part -> part == FIXED_POINT).count();
        final var reduced = new int[partition.length - fixedPointCount];
        var destination = 0;
        for (final var part : partition) {
            if (part != FIXED_POINT) {
                reduced[destination++] = part;
            }
        }
        return reduced;
    }

    private static int threeNorm(final int[] partition) {
        return Arrays.stream(partition).map(part -> part / 2).sum();
    }

    private static void validateSuccessorPartition(final int[] partition) {
        if (Arrays.stream(partition).sum() != 17
                || Arrays.stream(partition).filter(part -> part == FIXED_POINT).count() != 1
                || Arrays.stream(partition).anyMatch(part -> part <= 0)) {
            throw new IllegalArgumentException("Expected a one-fixed-point partition of 17: "
                    + Arrays.toString(partition));
        }
    }

    private record AnnotatedPartition(int[] partition, boolean[] orientedByPart) {
        private AnnotatedPartition {
            partition = partition.clone();
            orientedByPart = orientedByPart.clone();
        }
    }
}
