package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CommonOperations;
import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.ExtendedToricClassKey;
import br.unb.cic.tdp.base.SortingSearch;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import br.unb.cic.tdp.permutation.PermutationGroups;
import lombok.val;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Avoidance-preimage witness corpus for TD(16).
 *
 * <p>If contracting the fixed point created by a selected root 2-move produces a
 * residual obstruction, this class reverses both relevant root transitions: splitting
 * an odd-length cycle into smaller odd-length cycles and joining two even-length
 * cycles. It sorts one representative of every resulting avoidance-preimage class
 * independently within nine moves. The reconstruction cycle is used only to obtain the
 * preimage; it is not counted as part of the independently found sorting sequence.
 *
 * <p>For each contracted residual obstruction (omega, beta) on 16 symbols:
 * <ol>
 *   <li>Add a fixed point (symbol 16) to omega, creating omega' in S_17.</li>
 *   <li>Generate all possible beta' by inserting symbol 16 at each of the 16 positions of beta.</li>
 *   <li>For each (omega', beta'), enumerate both inverse-move families: merging the
 *       restored fixed point and the two nontrivial odd-length cycles, and splitting
 *       either residual odd-length cycle back into a pair of even-length cycles.</li>
 *   <li>Apply each inverse move, retain the preimages routed through the corresponding
 *       root branch, and deduplicate the union by extended-toric class key.</li>
 *   <li>GPU-sort one representative of each distinct avoidance-preimage class
 *       directly within nine moves.</li>
 * </ol>
 */
public final class MinusTwoMoveExtender {

    private static final int PREIMAGE_MOVE_BUDGET = 9;
    private static final int PREIMAGE_SYMBOL_COUNT = 17;

    private MinusTwoMoveExtender() {
    }

    record EvenPairPreimageAuditStats(long reconstructionApplications,
                                      int preimageOrbits) {
    }

    private record EvenPairPreimageAudit(
            long reconstructionApplications,
            ConcurrentHashMap<ExtendedToricClassKey, CyclicTargetPair> preimageRepresentatives) {
        private EvenPairPreimageAuditStats stats() {
            return new EvenPairPreimageAuditStats(
                    reconstructionApplications, preimageRepresentatives.size());
        }
    }

    record RestoredSuccessor(CyclicTargetPair pair,
                             int restoredFixedPoint,
                             int predecessor) {
    }

    /**
     * Produces witnesses for both avoidance-preimage families reconstructed
     * from the current in-memory native misses.  A miss has no durable form.
     */
    public static void emitAvoidancePreimages(
            final Collection<RemainingSuccessorsSolver.ResidualObstructionEntry> entries) {
        final var preimages = new ConcurrentHashMap<ExtendedToricClassKey, CyclicTargetPair>();
        for (final var entry : entries) {
            reconstructOddSplitPreimages(entry.pair(), preimages);
            preimages.putAll(reconstructEvenPairPreimages(List.of(entry.pair()))
                    .preimageRepresentatives());
        }
        emitAvoidanceWitnesses(preimages.values());
    }

    /**
     * Checks whether any contracted residual obstruction can be reached from the
     * even-pair branch of the root expansion.
     *
     * <p>After restoring the contracted fixed point in every cyclic gap, an inverse
     * of the even-pair join uses that fixed point and two symbols of one residual
     * odd-length cycle. For each unordered symbol pair, exactly one of the two
     * cyclic orientations is applicable. Applying it reconstructs a candidate preimage.
     * A candidate belongs to the even-pair route precisely when it is fixed-point-free,
     * has at least one pair of even-length cycles, and follows the same orientation
     * routing as the root expansion. The all-unoriented guard runs first, so
     * every retained root must have an oriented even-length cycle. Its odd-length
     * cycles are either all unoriented, or its shortest oriented odd-length cycle has
     * length 9. This precedence is decided at the annotated-type level; the local
     * 9-cycle need not be exceptional. Roots routed first to any other odd-cycle
     * reduction are excluded.
     */
    static EvenPairPreimageAuditStats auditEvenPairPreimages(
            final Collection<CyclicTargetPair> residuals) {
        return reconstructEvenPairPreimages(residuals).stats();
    }

    static Set<ExtendedToricClassKey> evenPairPreimageClassKeys(
            final Collection<CyclicTargetPair> residuals) {
        return Set.copyOf(reconstructEvenPairPreimages(residuals)
                .preimageRepresentatives().keySet());
    }

    private static EvenPairPreimageAudit reconstructEvenPairPreimages(
            final Collection<CyclicTargetPair> residuals) {
        val candidatePreimages =
                new ConcurrentHashMap<ExtendedToricClassKey, CyclicTargetPair>();
        long reconstructionApplications = 0;

        for (val residual : residuals) {
            for (val restored : restoreFixedPointInEveryGap(residual)) {
                val omegaPrime = restored.pair().getOmega();
                val betaPrime = restored.pair().getBeta();
                val restoredFixedPoint = restored.restoredFixedPoint();

                for (val joinedCycle : residual.getOmega()) {
                    val symbols = joinedCycle.getSymbols();
                    for (var first = 0; first < symbols.length - 1; first++) {
                        for (var second = first + 1; second < symbols.length; second++) {
                            val a = symbols[first];
                            val b = symbols[second];
                            final Cycle inverseMove;
                            if (CommonOperations.areSymbolsInCyclicOrder(
                                    betaPrime, a, b, restoredFixedPoint)) {
                                inverseMove = Cycle.of(a, b, restoredFixedPoint);
                            } else {
                                inverseMove = Cycle.of(a, restoredFixedPoint, b);
                            }

                            val preimage = reconstructPreimage(restored, inverseMove);
                            reconstructionApplications++;

                            if (isEvenPairRootPreimage(preimage.getOmega(), preimage.getBeta())) {
                                candidatePreimages.putIfAbsent(
                                        preimage.extendedToricClassKey(), preimage);
                            }
                        }
                    }
                }
            }
        }

        return new EvenPairPreimageAudit(reconstructionApplications, candidatePreimages);
    }

    static boolean isEvenPairRootPreimage(final MulticyclePermutation omega,
                                          final Cycle beta) {
        if (omega.stream().anyMatch(cycle -> cycle.size() == 1)) {
            return false;
        }

        val evenLengthCycleCount = omega.stream()
                .filter(cycle -> cycle.size() % 2 == 0)
                .count();
        if (evenLengthCycleCount < 2) {
            return false;
        }

        var hasOrientedThree = false;
        var hasOrientedEven = false;
        var hasOrientation = false;
        var selectedOddLength = Integer.MAX_VALUE;
        for (final var cycle : omega) {
            final boolean oriented = cycle.size() > 2 && CommonOperations.isOriented(beta, cycle);
            hasOrientation |= oriented;
            hasOrientedThree |= oriented && cycle.size() == 3;
            hasOrientedEven |= oriented && cycle.size() % 2 == 0;
            if (oriented && cycle.size() > 3 && cycle.size() % 2 == 1
                    && (cycle.size() == 5 || cycle.size() == 7 || cycle.size() == 9
                    || cycle.size() == 11 || cycle.size() == 13 || cycle.size() == 17)) {
                selectedOddLength = Math.min(selectedOddLength, cycle.size());
            }
        }
        return hasOrientation && !hasOrientedThree
                && ((selectedOddLength == 9 && hasOrientedEven)
                || selectedOddLength == Integer.MAX_VALUE);
    }

    private static Cycle insertAfter(final Cycle beta,
                                     final int predecessor,
                                     final int fixedPoint) {
        if (!beta.contains(predecessor) || beta.contains(fixedPoint)) {
            throw new IllegalArgumentException(
                    "Expected a predecessor in beta and a fresh fixed-point symbol");
        }
        val betaPrimeSymbols = new int[beta.size() + 1];
        var dest = 0;
        for (var i = 0; i < beta.size(); i++) {
            betaPrimeSymbols[dest++] = beta.get(i);
            if (beta.get(i) == predecessor) {
                betaPrimeSymbols[dest++] = fixedPoint;
            }
        }
        return Cycle.of(betaPrimeSymbols);
    }

    /**
     * Restores the contracted fixed point in every current-cycle gap. Each result is
     * a cyclic-target pair, and the restored symbol is fixed by its algebraic
     * permutation while the predecessor-to-fixed-point edge is common to its current
     * and target cycles.
     */
    static List<RestoredSuccessor> restoreFixedPointInEveryGap(
            final CyclicTargetPair residual) {
        val omega = residual.getOmega();
        val beta = residual.getBeta();
        if (!residual.formsCyclicTargetPair()) {
            throw new IllegalArgumentException("Residual obstruction must be a cyclic-target pair");
        }
        if (omega.getNumberOfSymbols() != beta.size()) {
            throw new IllegalArgumentException(
                    "Residual algebraic permutation and current cycle have different domains");
        }

        val restoredFixedPoint = beta.size();
        for (var symbol = 0; symbol < restoredFixedPoint; symbol++) {
            if (!beta.contains(symbol)) {
                throw new IllegalArgumentException(
                        "Restoration requires residual symbols 0 through n-1");
            }
        }
        val omegaPrime = new MulticyclePermutation(omega);
        omegaPrime.add(Cycle.of(restoredFixedPoint));
        val restored = new ArrayList<RestoredSuccessor>(beta.size());

        for (val predecessor : beta.getSymbols()) {
            val betaPrime = insertAfter(beta, predecessor, restoredFixedPoint);
            val pair = CyclicTargetPair.of(omegaPrime, betaPrime);
            val rhoPrime = pair.getRho();
            if (rhoPrime.size() != betaPrime.size()
                    || omegaPrime.image(restoredFixedPoint) != restoredFixedPoint
                    || betaPrime.image(predecessor) != restoredFixedPoint
                    || rhoPrime.image(predecessor) != restoredFixedPoint) {
                throw new IllegalStateException(
                        "Restoring a contraction gap did not produce the required fixed-point edge");
            }
            restored.add(new RestoredSuccessor(
                    pair, restoredFixedPoint, predecessor));
        }
        return List.copyOf(restored);
    }

    static CyclicTargetPair reconstructPreimage(
            final RestoredSuccessor restored,
            final Cycle reconstructionMove) {
        val preimageOmega = PermutationGroups.computeProduct(
                restored.pair().getOmega(), reconstructionMove.getInverse());
        val preimageBeta = CommonOperations.applyTranspositionOptimized(
                restored.pair().getBeta(), reconstructionMove);
        return CyclicTargetPair.of(preimageOmega, preimageBeta);
    }

    /**
     * Uncontracts a residual obstruction in every cyclic gap and enumerates every
     * reconstruction cycle relevant to the selected odd-cycle split.
     */
    static long reconstructOddSplitPreimages(
            final CyclicTargetPair residual,
            final ConcurrentHashMap<ExtendedToricClassKey, CyclicTargetPair> resultMap) {
        long generated = 0;

        for (val restored : restoreFixedPointInEveryGap(residual)) {
            generated += applyOddSplitReconstructionCycles(restored, resultMap);
        }
        return generated;
    }

    /**
     * Enumerates the inverse moves of the selected 2-move that split one odd-length
     * preimage cycle into three odd-length successor cycles.
     *
     * <p>The inverse must use the restored fixed point and one symbol from each of
     * two distinct nontrivial odd-length cycles. For each such symbol triple, exactly
     * one of its two cyclic orientations is applicable.
     */
    private static long applyOddSplitReconstructionCycles(
            final RestoredSuccessor restored,
            final ConcurrentHashMap<ExtendedToricClassKey, CyclicTargetPair> resultMap) {
        val omega = restored.pair().getOmega();
        val beta = restored.pair().getBeta();
        val restoredFixedPoint = restored.restoredFixedPoint();
        val mergeableCycles = omega.stream()
                .filter(cycle -> !cycle.contains(restoredFixedPoint))
                .filter(cycle -> cycle.size() > 1 && cycle.size() % 2 == 1)
                .toList();
        long generated = 0;

        for (var ci = 0; ci < mergeableCycles.size(); ci++) {
            val cycleA = mergeableCycles.get(ci);
            for (var cj = ci + 1; cj < mergeableCycles.size(); cj++) {
                val cycleB = mergeableCycles.get(cj);

                for (val a : cycleA.getSymbols()) {
                    for (val b : cycleB.getSymbols()) {
                        final Cycle inverseMove;
                        if (CommonOperations.areSymbolsInCyclicOrder(
                                beta, a, b, restoredFixedPoint)) {
                            inverseMove = Cycle.of(a, b, restoredFixedPoint);
                        } else {
                            inverseMove = Cycle.of(a, restoredFixedPoint, b);
                        }

                        val preimage = reconstructPreimage(restored, inverseMove);
                        val preimageOmega = preimage.getOmega();

                        if (preimageOmega.size() != 1
                                || preimageOmega.getNumberOfSymbols() != PREIMAGE_SYMBOL_COUNT) {
                            throw new IllegalStateException(
                                    "Odd-split reconstruction did not produce a 17-cycle preimage");
                        }

                        resultMap.putIfAbsent(preimage.extendedToricClassKey(), preimage);
                        generated++;
                    }
                }
            }
        }
        return generated;
    }


    private static void emitAvoidanceWitnesses(final Collection<CyclicTargetPair> preimages) {
        try (final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = new ArrayList<Future<?>>();
            for (final var preimage : preimages) {
                tasks.add(executor.submit(() -> {
                    final int target = threeNorm(preimage.getOmega());
                    SortingSearch.searchForSorting(preimage,
                            2 / (PREIMAGE_MOVE_BUDGET / (float) target),
                            PREIMAGE_MOVE_BUDGET);
                }));
            }
            awaitAll(tasks, "avoidance-preimage witness production");
        }
    }


    /**
     * Computes the 3-norm of a permutation: sum of floor(cycleSize / 2) for each cycle.
     */
    private static int threeNorm(final MulticyclePermutation omega) {
        var norm = 0;
        for (val cycle : omega) {
            norm += cycle.size() / 2;
        }
        return norm;
    }

    private static void awaitAll(final List<? extends Future<?>> futures,
                                 final String operation) {
        try {
            for (final var future : futures) {
                future.get();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            for (final var future : futures) {
                future.cancel(true);
            }
            throw new RuntimeException("Interrupted during " + operation, e);
        } catch (final ExecutionException e) {
            for (final var future : futures) {
                future.cancel(true);
            }
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(operation + " failed", e.getCause());
        }
    }
}
