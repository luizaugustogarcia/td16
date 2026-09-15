package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.SortingSearch;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class RemainingSuccessorsSolver {

    private static final int FIXED_POINT = 1;
    private static final int TWO_CYCLE = 2;
    private static final int ORIENTED_THREE_CYCLE = 3;

    private static final List<ResidualObstructionEntry> residualObstructions =
            Collections.synchronizedList(new ArrayList<>());

    /**
     * A contracted successor for which the exhaustive search found no sorting
     * sequence within the remaining move budget.
     */
    public record ResidualObstructionEntry(CyclicTargetPair pair, int maxMoves) {
    }

    private RemainingSuccessorsSolver() {
    }

    public static List<ResidualObstructionEntry> getAndClearResidualObstructions() {
        val result = new ArrayList<>(residualObstructions);
        residualObstructions.clear();
        return result;
    }

    static ProcessingStats processAnnotatedSuccessor(final int[] successorPartition,
                                                     final int[] reducedPartition,
                                                     final boolean[] orientedByPart,
                                                     final String provenance,
                                                     final String indent,
                                                     final int maxMoves) {
        validateSuccessorPartition(successorPartition);
        validateReducedPartition(successorPartition, reducedPartition, orientedByPart);
        validateMaxMoves(maxMoves);

        val requiredIncrease = threeNorm(reducedPartition);
        val successorLabel = formatSuccessorPartition(successorPartition, provenance);
        val annotatedPartition = new AnnotatedPartition(reducedPartition, orientedByPart);

        log.info("{}successor {}: fixedPoints={}, reduced={}", indent, successorLabel,
                countOccurrences(successorPartition, FIXED_POINT), Arrays.toString(reducedPartition));

        if (containsOrientedThree(annotatedPartition)) {
            log.info("{}  case {}: status=resolved, method=A0",
                    indent, formatAnnotatedPartition(annotatedPartition));
            return new ProcessingStats(1, 1, 0, 0);
        }

        val annotationStats = processAnnotatedPartition(annotatedPartition, maxMoves);
        val residualConfigurations = annotationStats.generatedConfigurations()
                - annotationStats.solvedConfigurations();
        log.info("{}  case {}: extendedToricClasses={}, solved={}, residual={}, requiredIncrease={}, maxMoves={}",
                indent,
                formatAnnotatedPartition(annotatedPartition),
                annotationStats.generatedConfigurations(),
                annotationStats.solvedConfigurations(),
                residualConfigurations,
                requiredIncrease,
                maxMoves);
        return new ProcessingStats(1, 0,
                annotationStats.generatedConfigurations(),
                annotationStats.solvedConfigurations());
    }

    static ProcessingStats processPartition(final int[] successorPartition,
                                            final String provenance,
                                            final String indent,
                                            final int maxMoves) {
        validateSuccessorPartition(successorPartition);
        validateMaxMoves(maxMoves);

        val reducedPartition = removeFixedPoints(successorPartition);
        val requiredIncrease = threeNorm(reducedPartition);
        val annotatedPartitions = enumerateAnnotatedPartitions(successorPartition);
        val successorLabel = formatSuccessorPartition(successorPartition, provenance);

        log.info("{}successor {}: fixedPoints={}, reduced={}", indent, successorLabel,
                countOccurrences(successorPartition, FIXED_POINT), Arrays.toString(reducedPartition));

        var immediateResolutions = 0;
        long generatedConfigurations = 0;
        long solvedConfigurations = 0;

        for (val annotatedPartition : annotatedPartitions) {
            if (containsOrientedThree(annotatedPartition)) {
                immediateResolutions++;
                log.info("{}  case {}: status=resolved, method=A0",
                        indent, formatAnnotatedPartition(annotatedPartition));
                continue;
            }

            val annotationStats = processAnnotatedPartition(annotatedPartition, maxMoves);

            generatedConfigurations += annotationStats.generatedConfigurations();
            solvedConfigurations += annotationStats.solvedConfigurations();

            log.info("{}  case {}: extendedToricClasses={}, solved={}, residual={}, requiredIncrease={}, maxMoves={}",
                    indent,
                    formatAnnotatedPartition(annotatedPartition),
                    annotationStats.generatedConfigurations(),
                    annotationStats.solvedConfigurations(),
                    annotationStats.generatedConfigurations() - annotationStats.solvedConfigurations(),
                    requiredIncrease,
                    maxMoves);
        }

        log.info("{}summary {}: annotations={}, immediateA0={}, extendedToricClasses={}, solved={}, residual={}",
                indent,
                successorLabel,
                annotatedPartitions.size(),
                immediateResolutions,
                generatedConfigurations,
                solvedConfigurations,
                generatedConfigurations - solvedConfigurations);

        return new ProcessingStats(annotatedPartitions.size(), immediateResolutions,
                generatedConfigurations, solvedConfigurations);
    }

    private static String formatSuccessorPartition(final int[] successorPartition,
                                                   final String provenance) {
        val partition = Arrays.toString(successorPartition);
        if (provenance == null || provenance.isBlank()) {
            return partition;
        }
        return partition + " [" + provenance + "]";
    }

    static List<AnnotatedPartition> enumerateAnnotatedPartitions(final int[] successorPartition) {
        val reducedPartition = removeFixedPoints(successorPartition);
        val annotatedPartitions = new ArrayList<AnnotatedPartition>();
        enumerateOrientations(reducedPartition, new boolean[reducedPartition.length], 0, annotatedPartitions);
        return annotatedPartitions;
    }

    static int[] removeFixedPoints(final int[] successorPartition) {
        val fixedPointCount = countOccurrences(successorPartition, FIXED_POINT);
        if (fixedPointCount == 0) {
            throw new IllegalArgumentException("Expected at least one fixed point in " + Arrays.toString(successorPartition));
        }

        val reduced = new int[successorPartition.length - fixedPointCount];
        var targetIndex = 0;
        for (val part : successorPartition) {
            if (part == FIXED_POINT) {
                continue;
            }
            reduced[targetIndex++] = part;
        }
        return reduced;
    }

    static boolean containsOrientedThree(final AnnotatedPartition annotatedPartition) {
        for (var i = 0; i < annotatedPartition.partition().length; i++) {
            if (annotatedPartition.partition()[i] == ORIENTED_THREE_CYCLE && annotatedPartition.orientedByPart()[i]) {
                return true;
            }
        }
        return false;
    }

    static int threeNorm(final int[] partition) {
        var norm = 0;
        for (val part : partition) {
            norm += part / 2;
        }
        return norm;
    }

    private static int countOccurrences(final int[] partition, final int part) {
        var count = 0;
        for (val value : partition) {
            if (value == part) {
                count++;
            }
        }
        return count;
    }

    static String formatAnnotatedPartition(final AnnotatedPartition annotatedPartition) {
        val formatted = new StringBuilder("[");
        for (var i = 0; i < annotatedPartition.partition().length; i++) {
            if (i > 0) {
                formatted.append(", ");
            }
            formatted.append(annotatedPartition.partition()[i])
                    .append('-')
                    .append(annotatedPartition.orientedByPart()[i] ? 'O' : 'U');
        }
        return formatted.append(']').toString();
    }

    private static AnnotatedPartitionStats processAnnotatedPartition(final AnnotatedPartition annotatedPartition,
                                                                     final int maxMoves) {
        val generatedConfigurations = new AtomicLong();
        val solvedConfigurations = new AtomicLong();
        DirectTwistedBraceletGenerator.generateRepresentativeBatchesConcurrently(
                annotatedPartition.partition(), annotatedPartition.orientedByPart(), () -> { }, pairs -> {
                    generatedConfigurations.addAndGet(pairs.length);
                    val movesByPair = SortingSearch.searchForSorting(pairs, maxMoves);
                    for (var index = 0; index < pairs.length; index++) {
                        if (!movesByPair.get(index).isEmpty()) {
                            solvedConfigurations.incrementAndGet();
                        } else {
                            residualObstructions.add(new ResidualObstructionEntry(pairs[index], maxMoves));
                        }
                    }
                });
        return new AnnotatedPartitionStats(
                generatedConfigurations.get(), solvedConfigurations.get());
    }

    private static void validateMaxMoves(final int maxMoves) {
        if (maxMoves <= 0) {
            throw new IllegalArgumentException("Expected a positive maxMoves, got " + maxMoves);
        }
    }

    private static void enumerateOrientations(final int[] partition,
                                              final boolean[] orientedByPart,
                                              final int index,
                                              final List<AnnotatedPartition> annotatedPartitions) {
        if (index == partition.length) {
            annotatedPartitions.add(new AnnotatedPartition(partition, orientedByPart));
            return;
        }

        if (partition[index] == TWO_CYCLE) {
            orientedByPart[index] = false;
            enumerateOrientations(partition, orientedByPart, index + 1, annotatedPartitions);
            return;
        }

        // Equal-length cycles are indistinguishable.  Canonicalize their O/U
        // multiset in the paper's U-before-O order within each equal-size run.
        final boolean mayBeUnoriented = index == 0
                || partition[index] != partition[index - 1]
                || !orientedByPart[index - 1];
        if (mayBeUnoriented) {
            orientedByPart[index] = false;
            enumerateOrientations(partition, orientedByPart, index + 1, annotatedPartitions);
        }

        orientedByPart[index] = true;
        enumerateOrientations(partition, orientedByPart, index + 1, annotatedPartitions);
    }

    private static void validateSuccessorPartition(final int[] successorPartition) {
        if (successorPartition.length < 2) {
            throw new IllegalArgumentException("Expected a nontrivial successor partition: " + Arrays.toString(successorPartition));
        }

        var fixedPointCount = 0;
        var sum = 0;
        for (val part : successorPartition) {
            if (part <= 0) {
                throw new IllegalArgumentException("Partition entries must be positive: " + Arrays.toString(successorPartition));
            }
            fixedPointCount += part == FIXED_POINT ? 1 : 0;
            sum += part;
        }

        if (fixedPointCount < 1) {
            throw new IllegalArgumentException("Expected at least one fixed point in " + Arrays.toString(successorPartition));
        }

        if (sum != 17) {
            throw new IllegalArgumentException("Expected a successor partition of 17 symbols: " + Arrays.toString(successorPartition));
        }
    }

    private static void validateReducedPartition(final int[] successorPartition,
                                                 final int[] reducedPartition,
                                                 final boolean[] orientedByPart) {
        if (reducedPartition.length != orientedByPart.length) {
            throw new IllegalArgumentException("Mismatched reduced partition/orientation lengths");
        }

        val expectedReduced = removeFixedPoints(successorPartition);
        if (!Arrays.equals(expectedReduced, reducedPartition)) {
            throw new IllegalArgumentException("Reduced partition does not match successor partition: expected "
                    + Arrays.toString(expectedReduced) + ", got " + Arrays.toString(reducedPartition));
        }
    }

    record AnnotatedPartition(int[] partition, boolean[] orientedByPart) {
        AnnotatedPartition {
            partition = partition.clone();
            orientedByPart = orientedByPart.clone();
        }
    }

    record ProcessingStats(int annotatedPartitions,
                           int immediateResolutions,
                           long generatedConfigurations,
                           long solvedConfigurations) {
    }

    private record AnnotatedPartitionStats(long generatedConfigurations,
                                           long solvedConfigurations) {
    }

}
