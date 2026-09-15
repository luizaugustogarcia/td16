package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CommonOperations;
import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinusTwoMoveExtenderTest {

    @Test
    void evenPairParentAuditDetectsANonResidualControl() {
        val omega = new MulticyclePermutation(List.of(
                Cycle.of(0, 11, 13, 12, 2, 4, 9, 5, 7, 3, 14),
                Cycle.of(1, 10, 8, 15, 6)));
        val beta = Cycle.of(0, 6, 5, 15, 14, 8, 4, 9, 1, 12, 11, 13, 3, 2, 7, 10);
        val control = CyclicTargetPair.of(omega, beta);

        for (val cycle : omega) {
            assertTrue(CommonOperations.isOriented(beta, cycle),
                    "The control must have the same O/O annotation as a residual obstruction");
        }

        val audit = MinusTwoMoveExtender.auditEvenPairPreimages(List.of(control));

        assertEquals(1_040, audit.reconstructionApplications(),
                "A [11,5] pair has 16*(C(11,2)+C(5,2)) inverse supports");
        assertTrue(audit.preimageOrbits() > 0,
                "The audit must detect the known [6,6,5-U] even-pair preimage");
    }

    @Test
    void evenPairParentClassificationMatchesRootRouting() {
        val beta = Cycle.of(0, 1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16);
        val orientedNine = Cycle.of(0, 5, 1, 6, 2, 7, 3, 8, 4);
        val unorientedNine = Cycle.of(0, 8, 7, 6, 5, 4, 3, 2, 1);
        val orientedFour = Cycle.of(9, 10, 11, 12);
        val unorientedFourA = Cycle.of(9, 12, 11, 10);
        val unorientedFourB = Cycle.of(13, 16, 15, 14);

        assertTrue(MinusTwoMoveExtender.isEvenPairRootPreimage(
                new MulticyclePermutation(List.of(
                        orientedNine, orientedFour, unorientedFourB)), beta),
                "An oriented 9-cycle with an oriented even companion uses the even-pair route");
        assertFalse(MinusTwoMoveExtender.isEvenPairRootPreimage(
                new MulticyclePermutation(List.of(
                        orientedNine, unorientedFourA, unorientedFourB)), beta),
                "An oriented 9-cycle with only unoriented even companions is routed through A3");
        assertTrue(MinusTwoMoveExtender.isEvenPairRootPreimage(
                new MulticyclePermutation(List.of(
                        unorientedNine, orientedFour, unorientedFourB)), beta),
                "A root with no oriented odd-length cycle but an oriented even cycle uses the even-pair route");
        assertFalse(MinusTwoMoveExtender.isEvenPairRootPreimage(
                new MulticyclePermutation(List.of(
                        unorientedNine, unorientedFourA, unorientedFourB)), beta),
                "A fully unoriented root is discharged before the even-pair route");

        val orientedFive = Cycle.of(0, 1, 2, 3, 4);
        val firstSix = Cycle.of(5, 10, 9, 8, 7, 6);
        val secondSix = Cycle.of(11, 12, 13, 14, 15, 16);
        assertFalse(MinusTwoMoveExtender.isEvenPairRootPreimage(
                new MulticyclePermutation(List.of(orientedFive, firstSix, secondSix)), beta),
                "An oriented 5-cycle is routed through A1 before the even-pair route");
    }
}
