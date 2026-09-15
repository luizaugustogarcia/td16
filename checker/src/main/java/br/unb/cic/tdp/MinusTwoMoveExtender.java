package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CommonOperations;
import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.ExtendedToricClassKey;
import br.unb.cic.tdp.base.SortingSearch;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import br.unb.cic.tdp.permutation.PermutationGroups;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Obstruction-avoidance branch of the TD(16) proof.
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
 *   <li>verify one certificate for each distinct avoidance-preimage class
 *       directly within nine moves.</li>
 * </ol>
 */
@Slf4j
public final class MinusTwoMoveExtender {

    static final int REFERENCE_RESIDUAL_OBSTRUCTION_COUNT = 2_036;
    static final int REFERENCE_ELEVEN_FIVE_OBSTRUCTION_COUNT = 495;
    static final int REFERENCE_NINE_SEVEN_OBSTRUCTION_COUNT = 1_541;
    static final int REFERENCE_ODD_SPLIT_PREIMAGE_ORBIT_COUNT = 1_339_101;
    static final long REFERENCE_EVEN_PAIR_RECONSTRUCTION_APPLICATION_COUNT = 1_920_192;
    static final int REFERENCE_EVEN_PAIR_PREIMAGE_ORBIT_COUNT = 178_439;
    static final int REFERENCE_PREIMAGE_ORBIT_OVERLAP = 0;
    static final int REFERENCE_PREIMAGE_ORBIT_UNION = 1_517_540;

    private static final int RESIDUAL_SYMBOL_COUNT = 16;
    private static final int RESIDUAL_MOVE_BUDGET = 8;
    private static final int PREIMAGE_MOVE_BUDGET = 9;

    private MinusTwoMoveExtender() {
    }

    public record AvoidanceStats(int residualObstructions,
                                 long oddSplitReconstructionApplications,
                                 int oddSplitPreimageOrbits,
                                 long evenPairReconstructionApplications,
                                 int evenPairPreimageOrbits,
                                 int overlappingPreimageOrbits,
                                 int distinctPreimageOrbits) {
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
     * Verifies the structural invariants of the residual-obstruction corpus and checks
     * that one representative of every avoidance-preimage class reconstructed from either
     * root-transition family has an independent sorting sequence within nine moves.
     * Historical corpus sizes are reported as reference drift, not treated as proof
     * invariants, so a corrected exhaustive run can reach the sorting stage.
     *
     * @param entries the residual obstructions found with the eight-move tail budget
     */
    public static AvoidanceStats verifyObstructionAvoidance(
            final List<RemainingSuccessorsSolver.ResidualObstructionEntry> entries) {
        val residualExtendedToricClasses = new HashSet<ExtendedToricClassKey>();
        var elevenFiveCount = 0;
        var nineSevenCount = 0;
        for (val entry : entries) {
            validateResidualObstruction(entry);
            val sizes = entry.pair().getOmega().stream()
                    .mapToInt(Cycle::size)
                    .sorted()
                    .toArray();
            if (Arrays.equals(sizes, new int[]{5, 11})) {
                elevenFiveCount++;
            } else {
                nineSevenCount++;
            }
            if (!residualExtendedToricClasses.add(entry.pair().extendedToricClassKey())) {
                throw new IllegalStateException(
                        "PROOF FAILURE: duplicate residual-obstruction extended-toric class, key="
                                + entry.pair().extendedToricClassKey());
            }
        }
        reportReferenceCount("residual-obstruction classes",
                REFERENCE_RESIDUAL_OBSTRUCTION_COUNT, entries.size());
        reportReferenceCount("[11-O,5-O] residual-obstruction classes",
                REFERENCE_ELEVEN_FIVE_OBSTRUCTION_COUNT, elevenFiveCount);
        reportReferenceCount("[9-O,7-O] residual-obstruction classes",
                REFERENCE_NINE_SEVEN_OBSTRUCTION_COUNT, nineSevenCount);

        val evenPairPreimageAudit = reconstructEvenPairPreimages(entries.stream()
                .map(RemainingSuccessorsSolver.ResidualObstructionEntry::pair)
                .toList());
        reportReferenceCount("applicable even-pair reconstruction supports",
                REFERENCE_EVEN_PAIR_RECONSTRUCTION_APPLICATION_COUNT,
                evenPairPreimageAudit.reconstructionApplications());
        val evenPairPreimageOrbits = evenPairPreimageAudit.preimageRepresentatives().size();
        reportReferenceCount("even-pair avoidance-preimage classes",
                REFERENCE_EVEN_PAIR_PREIMAGE_ORBIT_COUNT,
                evenPairPreimageOrbits);
        log.info("  {} applicable even-pair reconstruction supports yielded {} avoidance-preimage classes",
                evenPairPreimageAudit.reconstructionApplications(),
                evenPairPreimageOrbits);

        log.info("  Reconstructing avoidance preimages of {} residual-obstruction classes",
                entries.size());

        // Phase 1: uncontract each obstruction and reconstruct its odd-split preimages.
        // The map is global across the corpus and stores an arbitrary representative under
        // the immutable key of its extended-toric class.
        val avoidancePreimages = new ConcurrentHashMap<ExtendedToricClassKey, CyclicTargetPair>();
        val oddSplitReconstructionApplications = new AtomicLong();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            val tasks = new ArrayList<Future<?>>();
            for (val entry : entries) {
                tasks.add(executor.submit(() -> {
                    val generated = reconstructOddSplitPreimages(entry.pair(), avoidancePreimages);
                    if (generated == 0) {
                        throw new IllegalStateException(
                                "PROOF FAILURE: residual obstruction produced no odd-split preimages, key="
                                        + entry.pair().extendedToricClassKey());
                    }
                    oddSplitReconstructionApplications.addAndGet(generated);
                }));
            }
            awaitAll(tasks, "odd-split preimage reconstruction");
        }

        val oddSplitPreimageOrbits = avoidancePreimages.size();
        reportReferenceCount("odd-split avoidance-preimage classes",
                REFERENCE_ODD_SPLIT_PREIMAGE_ORBIT_COUNT, oddSplitPreimageOrbits);
        log.info("  {} odd-split reconstruction applications yielded {} avoidance-preimage classes",
                oddSplitReconstructionApplications.get(), oddSplitPreimageOrbits);

        var overlappingPreimageOrbits = 0;
        for (val entry : evenPairPreimageAudit.preimageRepresentatives().entrySet()) {
            if (avoidancePreimages.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                overlappingPreimageOrbits++;
            }
        }
        val distinctPreimageOrbits = avoidancePreimages.size();
        // The representatives now live in the union map. Release the duplicate map
        // structure before the potentially long-running sorting phase.
        evenPairPreimageAudit.preimageRepresentatives().clear();
        reportReferenceCount("overlap between odd-split and even-pair preimage classes",
                REFERENCE_PREIMAGE_ORBIT_OVERLAP, overlappingPreimageOrbits);
        reportReferenceCount("distinct avoidance-preimage classes",
                REFERENCE_PREIMAGE_ORBIT_UNION, distinctPreimageOrbits);
        log.info("  avoidance-preimage union: {} odd-split + {} even-pair - {} overlap = {} classes",
                oddSplitPreimageOrbits,
                evenPairPreimageOrbits,
                overlappingPreimageOrbits,
                distinctPreimageOrbits);

        // Phase 2: independently sort one representative of every extended-toric class.
        solveAvoidancePreimages(avoidancePreimages);
        return new AvoidanceStats(entries.size(), oddSplitReconstructionApplications.get(), oddSplitPreimageOrbits,
                evenPairPreimageAudit.reconstructionApplications(),
                evenPairPreimageOrbits,
                overlappingPreimageOrbits, distinctPreimageOrbits);
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

        return ProofTD16.usesEvenPairReduction(
                ProofTD16.initialTypeRoute(omega, beta));
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
                                || preimageOmega.getNumberOfSymbols() != RESIDUAL_SYMBOL_COUNT + 1) {
                            throw new IllegalStateException(
                                    "PROOF FAILURE: odd-split reconstruction did not produce a 17-cycle preimage");
                        }

                        resultMap.putIfAbsent(preimage.extendedToricClassKey(), preimage);
                        generated++;
                    }
                }
            }
        }
        return generated;
    }

    /**
     * Independently verifies one certificate for every avoidance-preimage
     * class within nine moves.
     */
    private static void solveAvoidancePreimages(
            final ConcurrentHashMap<ExtendedToricClassKey, CyclicTargetPair> preimages) {
        val solved = new AtomicInteger();
        val preimageRepresentatives = preimages.values().toArray(CyclicTargetPair[]::new);
        val nextParent = new AtomicInteger();
        val stop = new AtomicBoolean();
        val workerCount = Math.min(preimageRepresentatives.length,
                Math.max(1, Runtime.getRuntime().availableProcessors()));
        val executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("obstruction-avoidance-gpu-", 0).factory());
        val futures = new ArrayList<Future<Void>>(workerCount);
        var completedNormally = false;

        try {
            for (var worker = 0; worker < workerCount; worker++) {
                futures.add(executor.submit(() -> {
                    try {
                        while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                            val parentIndex = nextParent.getAndIncrement();
                            if (parentIndex >= preimageRepresentatives.length) {
                                return null;
                            }
                            val preimage = preimageRepresentatives[parentIndex];

                            val moves = SortingSearch.searchForSorting(
                                    preimage, PREIMAGE_MOVE_BUDGET);
                            if (!moves.isEmpty()) {
                                solved.incrementAndGet();
                            } else {
                                throw new IllegalStateException(
                                        "PROOF FAILURE: avoidance preimage has no nine-move sorting, key="
                                                + preimage.extendedToricClassKey());
                            }
                        }
                        return null;
                    } catch (final RuntimeException | Error throwable) {
                        stop.set(true);
                        throw throwable;
                    }
                }));
            }

            awaitAll(futures, "avoidance-preimage sorting");
            completedNormally = true;
        } finally {
            if (completedNormally) {
                executor.shutdown();
            } else {
                stop.set(true);
                for (final var future : futures) {
                    future.cancel(true);
                }
                executor.shutdownNow();
            }
        }
        log.info("  All {} avoidance-preimage classes represented and sorted "
                        + "independently within nine moves",
                solved.get());
    }

    private static void reportReferenceCount(final String label,
                                             final long reference,
                                             final long actual) {
        if (actual == reference) {
            log.info("  {}: {} (matches reference corpus)", label, actual);
        } else {
            log.warn("  {} changed from reference {} to {}; continuing with the observed corpus",
                    label, reference, actual);
        }
    }

    private static void validateResidualObstruction(
            final RemainingSuccessorsSolver.ResidualObstructionEntry entry) {
        val residual = entry.pair();
        val omega = residual.getOmega();
        val beta = residual.getBeta();

        if (entry.maxMoves() != RESIDUAL_MOVE_BUDGET) {
            throw new IllegalStateException(
                    "Residual obstruction must come from a single selected 2-move and have budget "
                            + RESIDUAL_MOVE_BUDGET + ", got " + entry.maxMoves());
        }
        if (beta.size() != RESIDUAL_SYMBOL_COUNT
                || omega.getNumberOfSymbols() != RESIDUAL_SYMBOL_COUNT) {
            throw new IllegalStateException(
                    "Expected a contracted residual on 16 symbols");
        }
        if (!residual.formsCyclicTargetPair()) {
            throw new IllegalStateException(
                    "Residual obstruction must have a cyclic target");
        }
        if (omega.size() != 2) {
            throw new IllegalStateException(
                    "Expected exactly two nontrivial cycles in a residual obstruction");
        }

        val sizes = omega.stream().mapToInt(Cycle::size).sorted().toArray();
        if (!Arrays.equals(sizes, new int[]{5, 11})
                && !Arrays.equals(sizes, new int[]{7, 9})) {
            throw new IllegalStateException(
                    "Expected residual cycle type [11,5] or [9,7], got "
                            + Arrays.toString(sizes));
        }
        for (val cycle : omega) {
            if (!CommonOperations.isOriented(beta, cycle)) {
                throw new IllegalStateException(
                        "Expected both cycles of a residual obstruction to be oriented");
            }
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
