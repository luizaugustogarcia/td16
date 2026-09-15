package br.unb.cic.tdp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProofTD16Test {

    @Test
    void annotatedCasesDeduplicateEqualLengthCycles() {
        final var cases = ProofTD16.enumerateAnnotatedCases(new int[]{5, 5, 4, 2});

        assertEquals(6, cases.size());
        assertTrue(cases.stream().noneMatch(candidate ->
                candidate.orientedByPart()[0] && !candidate.orientedByPart()[1]));
    }

    @Test
    void annotatedCasesCanonicalizeThreeEqualCyclesWithUnorientedFirst() {
        final var cases = ProofTD16.enumerateAnnotatedCases(new int[]{4, 4, 4, 2});

        assertEquals(Set.of(
                        "[false, false, false, false]",
                        "[false, false, true, false]",
                        "[false, true, true, false]",
                        "[true, true, true, false]"),
                cases.stream()
                        .map(candidate -> Arrays.toString(candidate.orientedByPart()))
                        .collect(Collectors.toSet()));
    }

    @Test
    void annotationEnumeratorsAgreeForEveryValidSortedSuccessorPartition() {
        for (var nonFixedSize = 2; nonFixedSize < 17; nonFixedSize++) {
            for (final var reducedPartition : Partitions.generateValidPartitions(nonFixedSize)) {
                final var successorPartition = Arrays.copyOf(
                        reducedPartition, reducedPartition.length + 17 - nonFixedSize);
                Arrays.fill(successorPartition, reducedPartition.length, successorPartition.length, 1);

                final var proofAnnotations = ProofTD16.enumerateAnnotatedCases(successorPartition).stream()
                        .map(candidate -> annotationKey(candidate.partition(), candidate.orientedByPart()))
                        .collect(Collectors.toSet());
                final var solverAnnotations = RemainingSuccessorsSolver
                        .enumerateAnnotatedPartitions(successorPartition).stream()
                        .map(candidate -> annotationKey(candidate.partition(), candidate.orientedByPart()))
                        .collect(Collectors.toSet());

                assertEquals(proofAnnotations, solverAnnotations,
                        "annotation mismatch for " + Arrays.toString(successorPartition));
            }
        }
    }

    @Test
    void validRootPartitionsHaveExpectedMultisetAnnotationCount() {
        final var roots = Partitions.generateValidPartitions(17);

        assertEquals(33, roots.size());
        assertEquals(194, roots.stream()
                .mapToInt(partition -> ProofTD16.enumerateAnnotatedCases(partition).size())
                .sum());
    }

    @Test
    void initialTypeRoutingMatchesTheProofTable() {
        final var routeCounts = new EnumMap<ProofTD16.InitialTypeRoute, Integer>(
                ProofTD16.InitialTypeRoute.class);

        Partitions.generateValidPartitions(17).stream()
                .flatMap(partition -> ProofTD16.enumerateAnnotatedCases(partition).stream())
                .forEach(candidate -> routeCounts.merge(
                        ProofTD16.initialTypeRoute(candidate), 1, Integer::sum));

        assertEquals(33, routeCounts.get(ProofTD16.InitialTypeRoute.ALL_UNORIENTED));
        assertEquals(74, routeCounts.get(ProofTD16.InitialTypeRoute.ORIENTED_THREE));
        assertEquals(3, routeCounts.get(
                ProofTD16.InitialTypeRoute.SELECTED_NINE_WITH_ORIENTED_EVEN));
        assertEquals(47, routeCounts.get(ProofTD16.InitialTypeRoute.ORIENTED_ODD_REDUCTION));
        assertEquals(37, routeCounts.get(ProofTD16.InitialTypeRoute.REMAINING_EVEN_PAIR));
        assertEquals(194, routeCounts.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void exactlyThreeSelectedNineTypesReceiveEvenPairPrecedence() {
        final var exceptionalPrecedenceTypes = Partitions.generateValidPartitions(17).stream()
                .flatMap(partition -> ProofTD16.enumerateAnnotatedCases(partition).stream())
                .filter(candidate -> ProofTD16.initialTypeRoute(candidate)
                        == ProofTD16.InitialTypeRoute.SELECTED_NINE_WITH_ORIENTED_EVEN)
                .map(candidate -> annotationKey(
                        candidate.partition(), candidate.orientedByPart()))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                        "9-O|6-O|2-U",
                        "9-O|4-U|4-O",
                        "9-O|4-O|4-O"),
                exceptionalPrecedenceTypes);
    }

    @Test
    void selectedNineOddRouteLeavesExactlyTheFourUnorientedCompanionTypes() {
        final var selectedNineTypes = Partitions.generateValidPartitions(17).stream()
                .flatMap(partition -> ProofTD16.enumerateAnnotatedCases(partition).stream())
                .filter(candidate -> ProofTD16.initialTypeRoute(candidate)
                        == ProofTD16.InitialTypeRoute.ORIENTED_ODD_REDUCTION)
                .filter(candidate -> shortestSelectedOddLength(candidate) == 9)
                .map(candidate -> annotationKey(
                        candidate.partition(), candidate.orientedByPart()))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                        "9-O|6-U|2-U",
                        "9-O|5-U|3-U",
                        "9-O|4-U|4-U",
                        "9-O|2-U|2-U|2-U|2-U"),
                selectedNineTypes);
    }

    @Test
    void lemmaSuccessorsRegenerateEveryOrientationInsteadOfInheriting() {
        final var parent = new ProofTD16.AnnotatedCase(
                new int[]{13, 4}, new boolean[]{true, true});

        final var successor = ProofTD16.lemmaSuccessors(parent, 13).stream()
                .filter(candidate -> Arrays.equals(candidate.fullPartition(), new int[]{9, 4, 3, 1}))
                .findFirst()
                .orElseThrow();

        assertEquals(8, successor.annotatedSuccessors().size());
        assertTrue(successor.annotatedSuccessors().stream().anyMatch(candidate ->
                Arrays.equals(candidate.orientedByPart(), new boolean[]{false, false, false})));
        assertTrue(successor.annotatedSuccessors().stream().anyMatch(candidate ->
                Arrays.equals(candidate.orientedByPart(), new boolean[]{true, false, true})));
    }

    @Test
    void evenPairJoinSuccessorRegeneratesEveryOrientationInsteadOfInheriting() {
        final var parent = new ProofTD16.AnnotatedCase(
                new int[]{8, 6, 3}, new boolean[]{true, true, true});

        final var successor = ProofTD16.evenPairJoinSuccessor(parent);

        assertArrayEquals(new int[]{13, 3, 1}, successor.fullPartition());
        assertEquals(Set.of(
                        "13-U|3-U",
                        "13-U|3-O",
                        "13-O|3-U",
                        "13-O|3-O"),
                successor.annotatedSuccessors().stream()
                        .map(candidate -> annotationKey(candidate.partition(), candidate.orientedByPart()))
                        .collect(Collectors.toSet()));
    }

    private static String annotationKey(final int[] partition, final boolean[] orientedByPart) {
        final var key = new StringBuilder();
        for (var i = 0; i < partition.length; i++) {
            if (i > 0) {
                key.append('|');
            }
            key.append(partition[i])
                    .append('-')
                    .append(orientedByPart[i] ? 'O' : 'U');
        }
        return key.toString();
    }

    private static int shortestSelectedOddLength(final ProofTD16.AnnotatedCase candidate) {
        var shortest = Integer.MAX_VALUE;
        for (var i = 0; i < candidate.partition().length; i++) {
            final var length = candidate.partition()[i];
            if (candidate.orientedByPart()[i] && length >= 5 && length % 2 == 1) {
                shortest = Math.min(shortest, length);
            }
        }
        return shortest;
    }
}
