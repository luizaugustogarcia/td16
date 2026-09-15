package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CommonOperations;
import br.unb.cic.tdp.base.CyclicTargetCandidate;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OddLengthOrientedCyclesTest {

    @Test
    void intermediateCertificateCanSortWithinItsBoundWithoutMeetingTheBranchRate() {
        final var omega = new MulticyclePermutation(Cycle.of("0 3 1 4 2"));
        final var moves = List.of(
                Cycle.of(0, 3, 4),
                Cycle.of(0, 1, 2),
                Cycle.of(1, 2, 4));

        assertTrue(OddLengthOrientedCycles.meetsMinimumRate(
                omega, moves, 4 / (float) 3));
        assertFalse(OddLengthOrientedCycles.meetsMinimumRate(
                omega, moves, 8 / (float) 5));
    }

    @ParameterizedTest
    @CsvSource({
            "5, 14, 2, 1",
            "7, 80, 2, 1",
            "9, 506, 3, 2",
            "11, 3391, 2, 1",
            "13, 23619, 4, 3",
            "17, 1243098, 4, 3"
    })
    void survivingEnumerationMatchesRouteOClassification(
            final int cycleLength,
            final long expectedVisitedNodes,
            final int expectedCycles,
            final long expectedOrientedOrbits
    ) {
        final var result = OddLengthOrientedCycles
                .enumerateSurvivingCycles(cycleLength);
        final var beta = canonicalCycle(cycleLength);
        final var orientedOrbits = result.cycles().stream()
                .map(Cycle::of)
                .filter(cycle -> CommonOperations.isOriented(beta, cycle))
                .map(cycle -> new CyclicTargetCandidate(
                        new MulticyclePermutation(cycle), beta).twistedBraceletKey())
                .distinct()
                .count();

        assertEquals(expectedVisitedNodes, result.visitedNodes());
        assertEquals(expectedCycles, result.cycles().size());
        assertEquals(expectedOrientedOrbits, orientedOrbits);
        assertTrue(result.cycles().stream()
                .map(Cycle::of)
                .noneMatch(cycle -> OddLengthOrientedCycles
                        .meetsQualifying2MoveCriterion(cycle, beta)));
    }

    @Test
    void lengthThirteenRetainsAllThreeOrientedExceptions() {
        final var beta = canonicalCycle(13);
        final var orientedCycles = OddLengthOrientedCycles
                .enumerateSurvivingCycles(13).cycles().stream()
                .map(Cycle::of)
                .filter(cycle -> CommonOperations.isOriented(beta, cycle))
                .map(Cycle::toString)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "(0 7 1 8 2 9 3 10 4 11 5 12 6)",
                "(0 10 7 4 1 11 8 5 2 12 9 6 3)",
                "(0 11 9 7 5 3 1 12 10 8 6 4 2)"), orientedCycles);
    }

    @Test
    void qualifyingCriterionSeparatesOrdinaryCycleFromException() {
        final var beta = canonicalCycle(5);

        assertTrue(OddLengthOrientedCycles.meetsQualifying2MoveCriterion(beta, beta));
        assertFalse(OddLengthOrientedCycles.meetsQualifying2MoveCriterion(
                Cycle.of("0 3 1 4 2"), beta));
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 7, 9})
    void qualifyingCriterionMatchesMoveByMoveEnumeration(final int cycleLength) {
        final var beta = canonicalCycle(cycleLength);
        final var tail = IntStream.range(1, cycleLength).toArray();

        do {
            final var symbols = new int[cycleLength];
            System.arraycopy(tail, 0, symbols, 1, tail.length);
            final var cycle = Cycle.of(symbols);
            assertEquals(admitsQualifyingMoveByTriples(cycle, beta),
                    OddLengthOrientedCycles.meetsQualifying2MoveCriterion(cycle, beta),
                    () -> "criterion mismatch for cycle " + cycle);
        } while (nextPermutation(tail));
    }

    @Test
    void survivingEnumerationRejectsUnsupportedLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> OddLengthOrientedCycles.enumerateSurvivingCycles(2));
        assertThrows(IllegalArgumentException.class,
                () -> OddLengthOrientedCycles.enumerateSurvivingCycles(6));
    }

    private static Cycle canonicalCycle(final int cycleLength) {
        return Cycle.of(IntStream.range(0, cycleLength).toArray());
    }

    private static boolean admitsQualifyingMoveByTriples(
            final Cycle cycle,
            final Cycle beta
    ) {
        for (var i = 0; i < cycle.size() - 2; i++) {
            for (var j = i + 1; j < cycle.size() - 1; j++) {
                for (var k = j + 1; k < cycle.size(); k++) {
                    final var a = cycle.get(i);
                    final var b = cycle.get(j);
                    final var c = cycle.get(k);
                    if (!CommonOperations.areSymbolsInCyclicOrder(beta, a, b, c)) {
                        continue;
                    }

                    final var ab = cycle.getK(a, b);
                    final var bc = cycle.getK(b, c);
                    final var ca = cycle.getK(c, a);
                    if (ab % 2 == 1 && bc % 2 == 1 && ca % 2 == 1
                            && (ab == 1 || bc == 1 || ca == 1)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean nextPermutation(final int[] values) {
        var pivot = values.length - 2;
        while (pivot >= 0 && values[pivot] >= values[pivot + 1]) {
            pivot--;
        }
        if (pivot < 0) {
            return false;
        }

        var successor = values.length - 1;
        while (values[successor] <= values[pivot]) {
            successor--;
        }
        final var swap = values[pivot];
        values[pivot] = values[successor];
        values[successor] = swap;

        for (int left = pivot + 1, right = values.length - 1;
             left < right;
             left++, right--) {
            final var reversed = values[left];
            values[left] = values[right];
            values[right] = reversed;
        }
        return true;
    }
}
