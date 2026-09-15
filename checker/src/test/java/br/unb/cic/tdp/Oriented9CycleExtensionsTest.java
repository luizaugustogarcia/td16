package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CommonOperations;
import br.unb.cic.tdp.base.CyclicTargetCandidate;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Oriented9CycleExtensionsTest {

    @Test
    void exceptionalCandidateIsGammaNine() {
        final var exceptional = Oriented9CycleExtensions.exceptionalCandidate();

        assertEquals(new MulticyclePermutation(Cycle.of("0 5 1 6 2 7 3 8 4")),
                exceptional.getOmega());
        assertEquals(Cycle.of("0 1 2 3 4 5 6 7 8"), exceptional.getBeta());
        assertFalse(exceptional.formsCyclicTargetPair(),
                "The exceptional local pattern becomes realizable only after adding companions");
    }

    @Test
    void fixedGammaInterleavingsAreExhaustiveForTwoCompanions() {
        final var sixTwoExtensions = assertExtension(
                new int[]{6, 2}, 20_160, 19_633, 527);
        assertExtension(new int[]{5, 3}, 40_040, 37_944, 2_096);
        assertExtension(new int[]{4, 4}, 25_410, 23_767, 1_643);

        // This cyclic target cannot be obtained by inserting the 6-cycle first:
        // deleting the 2-cycle makes two occurrences of the 6-cycle adjacent.
        // Direct word interleaving must nevertheless generate its class.
        final var formerlyMissing = new CyclicTargetCandidate(
                new MulticyclePermutation(
                        "(0 7 1 9 2 10 3 13 5)(4 16 14 11 8 6)(12 15)"),
                Cycle.of("0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16"));
        assertTrue(sixTwoExtensions.stream().anyMatch(candidate ->
                candidate.twistedBraceletKey().equals(formerlyMissing.twistedBraceletKey())));
    }

    @Test
    void fixedGammaInterleavingsAreExhaustiveForFourEqualCompanions() {
        assertExtension(new int[]{2, 2, 2, 2}, 75_950, 63_218, 12_732);
    }

    @Test
    void searchUsesNineMovesAndTheThreeNormOfEachPartition() {
        assertRequirement(new int[]{6, 2}, 8);
        assertRequirement(new int[]{5, 3}, 7);
        assertRequirement(new int[]{4, 4}, 8);
        assertRequirement(new int[]{2, 2, 2, 2}, 8);
    }

    private static Set<CyclicTargetCandidate> assertExtension(final int[] companions,
                                                      final int expectedConfigurations,
                                                      final long expectedNonCyclicTargets,
                                                      final long expectedCyclicTargets) {
        final Set<CyclicTargetCandidate> candidates = Oriented9CycleExtensions.generate(companions);
        final long cyclicTargets = candidates.stream()
                .filter(CyclicTargetCandidate::formsCyclicTargetPair)
                .count();

        assertEquals(expectedConfigurations, candidates.size());
        assertEquals(expectedCyclicTargets, cyclicTargets);
        assertEquals(expectedNonCyclicTargets, candidates.size() - cyclicTargets);
        candidates.forEach(candidate -> candidate.getOmega().stream()
                .filter(cycle -> cycle.size() != 9)
                .forEach(cycle -> assertFalse(
                        CommonOperations.isOriented(candidate.getBeta(), cycle))));
        return candidates;
    }

    private static void assertRequirement(final int[] companions, final int expectedThreeNorm) {
        final var requirement = Oriented9CycleExtensions.searchRequirement(companions);

        assertEquals(9, requirement.maxMoves());
        assertEquals(expectedThreeNorm, requirement.requiredIncrease());
    }
}
