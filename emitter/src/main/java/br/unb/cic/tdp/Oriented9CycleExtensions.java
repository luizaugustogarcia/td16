package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CommonOperations;
import br.unb.cic.tdp.base.CyclicTargetCandidate;
import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.SortingSearch;
import br.unb.cic.tdp.base.StandardizedEncodingWord;
import br.unb.cic.tdp.base.TwistedBraceletKey;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.*;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


/**
 * Generates extensions of the exceptional oriented 9-cycle
 * gamma=(0,5,1,6,2,7,3,8,4), beta=(0,1,2,3,4,5,6,7,8), by adding a variable
 * number of unoriented companion cycles.
 *
 * <p>The 9-cycle is kept fixed.  Its ranked symbols form a prescribed subsequence
 * of the circular beta word, and the identifiers of the companion cycles are
 * interleaved in every possible way.  Equal companions are named by first
 * occurrence, and completed words are deduplicated under extended-toric symmetry.
 */
@Slf4j
public class Oriented9CycleExtensions {

    private static final int[] GAMMA = {0, 5, 1, 6, 2, 7, 3, 8, 4};
    private static final int[] BETA = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    // Ranks in GAMMA encountered in the induced BETA order.
    private static final int[] GAMMA_RANK_ORDER = {0, 2, 4, 6, 8, 1, 3, 5, 7};
    private static final int EXTENSION_SIZE = 17;

    static CyclicTargetCandidate exceptionalCandidate() {
        return new CyclicTargetCandidate(
                new MulticyclePermutation(Cycle.of(Arrays.copyOf(GAMMA, GAMMA.length))),
                Cycle.of(Arrays.copyOf(BETA, BETA.length)));
    }

    // Cycle types: the sizes of the unoriented companion cycles to add.
    private static final int[][] CYCLE_TYPES = {
            {6, 2},  // [9, 6, 2]
            {5, 3},  // [9, 5, 3]
            {4, 4},  // [9, 4, 4]
            {2, 2, 2, 2},  // [9, 2, 2, 2, 2]
    };

    // Each extension is searched with a (9,x)-sequence, where x is the
    // 3-norm of its cycle partition.
    private static final int MAX_MOVES = 9;

    public static void main(final String[] args) {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("oriented-nine-gpu-", 0).factory())) {
            for (val companionCycleSizes : CYCLE_TYPES) {
                val candidates = generate(companionCycleSizes);
                val requirement = searchRequirement(companionCycleSizes);
                val stats = solveCandidates(candidates, requirement, executor);

                log.info("    partition {}: generatedExtensions={}, cyclicTargets={}, solved={}, "
                                + "residual={}, sequence=({},{})",
                        formatFullPartition(companionCycleSizes), candidates.size(),
                        stats.cyclicTargets(), stats.solved(), stats.residual(),
                        requirement.maxMoves(), requirement.requiredIncrease());
            }
        }
    }

    /**
     * Starts each CPU-side search in a virtual thread. The shared GPU dispatcher
     * bounds queued and native work.
     */
    private static SearchStats solveCandidates(
            final Set<CyclicTargetCandidate> candidates,
            final SearchRequirement requirement,
            final ExecutorService executor
    ) {
        val completion = new ExecutorCompletionService<Void>(executor);
        val futures = new ArrayList<Future<Void>>();
        var cyclicTargets = 0;
        candidates.stream()
                .flatMap(candidate -> candidate.asCyclicTargetPair().stream())
                .forEach(pair -> {
                    futures.add(submitSearch(completion, pair, requirement));
                });
        cyclicTargets = futures.size();

        var solved = 0;
        var completedNormally = false;
        try {
            while (solved < cyclicTargets) {
                val completed = completion.take();
                completed.get();
                solved++;
            }
            completedNormally = true;
            return new SearchStats(cyclicTargets, solved, cyclicTargets - solved);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (final ExecutionException e) {
            rethrow(e.getCause());
            throw new AssertionError("unreachable");
        } finally {
            if (!completedNormally) {
                for (val future : futures) {
                    future.cancel(true);
                }
            }
        }
    }

    private record SearchStats(int cyclicTargets, int solved, int residual) {
    }

    record SearchRequirement(int maxMoves, int requiredIncrease, float minRate) {
    }

    private static Future<Void> submitSearch(
            final CompletionService<Void> completion,
            final CyclicTargetPair pair,
            final SearchRequirement requirement
    ) {
        return completion.submit(() -> {
            val moves = searchForSorting(pair, requirement);
            if (moves.isEmpty()) {
                throw new IllegalStateException("UNSOLVED omega=" + pair.getOmega()
                        + " beta=" + pair.getBeta()
                        + " — proof invalid: no (" + requirement.maxMoves() + ","
                        + requirement.requiredIncrease() + ")-sequence found for this pair");
            }
            return null;
        });
    }

    private static void rethrow(final Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(throwable);
    }

    /**
     * Searches for the required (9,x)-sequence for the given pair using the GPU.
     *
     * @return the list of moves if found, empty list otherwise
     */
    private static List<Cycle> searchForSorting(final CyclicTargetPair pair,
                                                final SearchRequirement requirement) {
        return SortingSearch.searchForSorting(pair.canonicalRepresentative(), requirement.minRate(), requirement.maxMoves());
    }

    static SearchRequirement searchRequirement(final int... companionCycleSizes) {
        validateCompanionCycleSizes(companionCycleSizes);
        val requiredIncrease = threeNorm(fullPartition(companionCycleSizes));
        return new SearchRequirement(MAX_MOVES, requiredIncrease,
                2 / (MAX_MOVES / (float) requiredIncrease));
    }

    static int threeNorm(final int[] partition) {
        return Arrays.stream(partition).map(cycleSize -> cycleSize / 2).sum();
    }

    /**
     * Generates one candidate representative per twisted bracelet obtained by interleaving the
     * exceptional 9-cycle with unoriented companion cycles of the given sizes.
     * Both cyclic and noncyclic reconstructed targets are returned; callers that
     * need ordinary SBT pairs must apply the cyclic-target test.
     */
    public static Set<CyclicTargetCandidate> generate(final int... companionCycleSizes) {
        validateCompanionCycleSizes(companionCycleSizes);

        val candidatesByBracelet = new HashMap<TwistedBraceletKey, CyclicTargetCandidate>();
        new FixedGammaInterleavingGenerator(
                companionCycleSizes, candidatesByBracelet).generate();

        // Consume the temporary key map while building the public set.  Removing each
        // entry as it is transferred avoids retaining both complete hash structures —
        // and all temporary keys — while building the returned structural set.
        val result = HashSet.<CyclicTargetCandidate>newHashSet(candidatesByBracelet.size());
        val candidates = candidatesByBracelet.values().iterator();
        while (candidates.hasNext()) {
            result.add(candidates.next());
            candidates.remove();
        }
        verifyCompanionCyclesAreUnoriented(result);
        return result;
    }

    /**
     * Enumerates fixed-Gamma words directly.  Rotating a circular word until
     * Gamma rank 1 is first fixes position 0, and membership in the exceptional
     * class fixes the order of the other eight Gamma ranks.  Filling every other
     * position with the prescribed companion-cycle identifiers is therefore
     * exhaustive.
     */
    private static final class FixedGammaInterleavingGenerator {
        private final int[] companionCycleSizes;
        private final int[] remaining;
        private final int[] placed;
        private final int[] cycleAtPosition = new int[EXTENSION_SIZE];
        private final int[] gammaRankAtPosition = new int[EXTENSION_SIZE];
        private final Map<TwistedBraceletKey, CyclicTargetCandidate> candidatesByBracelet;

        private FixedGammaInterleavingGenerator(
                final int[] companionCycleSizes,
                final Map<TwistedBraceletKey, CyclicTargetCandidate> candidatesByBracelet
        ) {
            this.companionCycleSizes = companionCycleSizes.clone();
            this.remaining = companionCycleSizes.clone();
            this.placed = new int[companionCycleSizes.length];
            this.candidatesByBracelet = candidatesByBracelet;
            Arrays.fill(gammaRankAtPosition, -1);
        }

        private void generate() {
            cycleAtPosition[0] = 0;
            gammaRankAtPosition[0] = GAMMA_RANK_ORDER[0];
            generatePosition(1, 1);
        }

        private void generatePosition(final int position, final int nextGammaIndex) {
            if (position == EXTENSION_SIZE) {
                val braceletKey = twistedBraceletKey(
                        cycleAtPosition, gammaRankAtPosition, companionCycleSizes.length);
                candidatesByBracelet.computeIfAbsent(braceletKey,
                        ignored -> buildCandidate(
                                cycleAtPosition, gammaRankAtPosition, companionCycleSizes));
                return;
            }

            if (nextGammaIndex < GAMMA_RANK_ORDER.length) {
                cycleAtPosition[position] = 0;
                gammaRankAtPosition[position] = GAMMA_RANK_ORDER[nextGammaIndex];
                generatePosition(position + 1, nextGammaIndex + 1);
                gammaRankAtPosition[position] = -1;
            }

            for (var companionIndex = 0;
                 companionIndex < companionCycleSizes.length;
                 companionIndex++) {
                if (remaining[companionIndex] == 0
                        || !respectsEqualCompanionOrder(companionIndex)) {
                    continue;
                }

                cycleAtPosition[position] = companionIndex + 1;
                remaining[companionIndex]--;
                placed[companionIndex]++;
                generatePosition(position + 1, nextGammaIndex);
                placed[companionIndex]--;
                remaining[companionIndex]++;
            }
        }

        /**
         * Equal-size unoriented companions are interchangeable.  Naming them in
         * order of first occurrence removes only this auxiliary name symmetry.
         */
        private boolean respectsEqualCompanionOrder(final int companionIndex) {
            if (placed[companionIndex] != 0) {
                return true;
            }
            for (var earlier = 0; earlier < companionIndex; earlier++) {
                if (companionCycleSizes[earlier] == companionCycleSizes[companionIndex]
                        && placed[earlier] == 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private static void verifyCompanionCyclesAreUnoriented(
            final Set<CyclicTargetCandidate> candidates
    ) {
        for (val candidate : candidates) {
            for (val cycle : candidate.getOmega()) {
                if (cycle.size() != GAMMA.length
                        && CommonOperations.isOriented(candidate.getBeta(), cycle)) {
                    throw new IllegalStateException(
                            "Generated an oriented companion cycle: " + candidate);
                }
            }
        }
    }

    private static CyclicTargetCandidate buildCandidate(
            final int[] cycleAtPosition,
            final int[] gammaRankAtPosition,
            final int[] companionCycleSizes
    ) {
        val gammaPositionsByRank = new int[GAMMA.length];
        val companionPositions = new int[companionCycleSizes.length][];
        val companionFill = new int[companionCycleSizes.length];
        for (var companionIndex = 0;
             companionIndex < companionCycleSizes.length;
             companionIndex++) {
            companionPositions[companionIndex] = new int[companionCycleSizes[companionIndex]];
        }

        for (var position = 0; position < cycleAtPosition.length; position++) {
            val cycle = cycleAtPosition[position];
            if (cycle == 0) {
                gammaPositionsByRank[gammaRankAtPosition[position]] = position;
            } else {
                val companionIndex = cycle - 1;
                companionPositions[companionIndex][companionFill[companionIndex]++] = position;
            }
        }

        val cycles = new ArrayList<Cycle>(companionCycleSizes.length + 1);
        cycles.add(Cycle.of(gammaPositionsByRank));
        for (val positions : companionPositions) {
            val cycle = new int[positions.length];
            cycle[0] = positions[0];
            for (var index = 1; index < positions.length; index++) {
                cycle[index] = positions[positions.length - index];
            }
            cycles.add(Cycle.of(cycle));
        }
        val omega = new MulticyclePermutation(cycles);
        val beta = Cycle.of(buildIdentity(EXTENSION_SIZE));
        return new CyclicTargetCandidate(omega, beta);
    }

    private static void validateCompanionCycleSizes(final int[] companionCycleSizes) {
        if (companionCycleSizes.length == 0) {
            throw new IllegalArgumentException("Expected at least one companion cycle");
        }

        var totalSize = GAMMA.length;
        for (val cycleSize : companionCycleSizes) {
            if (cycleSize < 2) {
                throw new IllegalArgumentException(
                        "Companion cycles must have length at least 2: "
                                + Arrays.toString(companionCycleSizes));
            }
            totalSize += cycleSize;
        }
        if (totalSize != EXTENSION_SIZE) {
            throw new IllegalArgumentException(
                    "Expected a partition of " + EXTENSION_SIZE + " with leading part 9: "
                            + formatFullPartition(companionCycleSizes));
        }
    }

    private static String formatFullPartition(final int[] companionCycleSizes) {
        return Arrays.toString(fullPartition(companionCycleSizes));
    }

    private static int[] fullPartition(final int[] companionCycleSizes) {
        val fullPartition = new int[companionCycleSizes.length + 1];
        fullPartition[0] = GAMMA.length;
        System.arraycopy(companionCycleSizes, 0, fullPartition, 1, companionCycleSizes.length);
        return fullPartition;
    }

    /**
     * Computes the canonical standardized word of a completed extension without
     * constructing all of its rotated and reflected candidate pairs.
     */
    private static TwistedBraceletKey twistedBraceletKey(
            final int[] cycleAtPosition,
            final int[] gammaRankAtPosition,
            final int companionCount
    ) {
        val least = new short[cycleAtPosition.length];
        val candidate = new short[cycleAtPosition.length];
        val leastFixed = new short[cycleAtPosition.length];
        val candidateFixed = new short[cycleAtPosition.length];
        val labelByCycle = new int[companionCount + 1];
        val cycleSizes = new int[companionCount + 1];
        for (final var cycle : cycleAtPosition) {
            cycleSizes[cycle]++;
        }
        val firstSymbolBySize = new int[cycleAtPosition.length + 1];
        var nextSymbol = GAMMA.length;
        for (var size = cycleAtPosition.length; size >= 2; size--) {
            firstSymbolBySize[size] = nextSymbol;
            for (var cycle = 1; cycle <= companionCount; cycle++) {
                if (cycleSizes[cycle] == size) {
                    nextSymbol++;
                }
            }
        }
        val nextSymbolBySize = new int[firstSymbolBySize.length];
        val fixedSymbolByCycle = new int[companionCount + 1];

        var initialized = false;
        for (var start = 0; start < cycleAtPosition.length; start++) {
            normalizedWord(cycleAtPosition, gammaRankAtPosition,
                    labelByCycle, cycleSizes, firstSymbolBySize,
                    nextSymbolBySize, fixedSymbolByCycle,
                    start, false, candidate, candidateFixed);
            if (!initialized || Arrays.compare(candidateFixed, leastFixed) < 0) {
                System.arraycopy(candidate, 0, least, 0, candidate.length);
                System.arraycopy(candidateFixed, 0, leastFixed, 0,
                        candidateFixed.length);
                initialized = true;
            }

            normalizedWord(cycleAtPosition, gammaRankAtPosition,
                    labelByCycle, cycleSizes, firstSymbolBySize,
                    nextSymbolBySize, fixedSymbolByCycle,
                    start, true, candidate, candidateFixed);
            if (Arrays.compare(candidateFixed, leastFixed) < 0) {
                System.arraycopy(candidate, 0, least, 0, candidate.length);
                System.arraycopy(candidateFixed, 0, leastFixed, 0,
                        candidateFixed.length);
            }
        }
        final var labels = new int[least.length];
        final var ranks = new int[least.length];
        for (var position = 0; position < least.length; position++) {
            labels[position] = least[position] / 100;
            ranks[position] = least[position] % 100;
        }
        return TwistedBraceletKey.ofCanonicalWord(
                StandardizedEncodingWord.of(labels, ranks));
    }

    private static void normalizedWord(
            final int[] cycleAtPosition,
            final int[] gammaRankAtPosition,
            final int[] labelByCycle,
            final int[] cycleSizes,
            final int[] firstSymbolBySize,
            final int[] nextSymbolBySize,
            final int[] fixedSymbolByCycle,
            final int start,
            final boolean reflected,
            final short[] entries,
            final short[] fixedEntries
    ) {
        Arrays.fill(labelByCycle, 0);
        System.arraycopy(firstSymbolBySize, 0, nextSymbolBySize, 0,
                firstSymbolBySize.length);
        Arrays.fill(fixedSymbolByCycle, -1);
        var nextLabel = 1;
        var firstGammaRank = -1;

        for (var offset = 0; offset < cycleAtPosition.length; offset++) {
            val position = reflected
                    ? Math.floorMod(-start - offset, cycleAtPosition.length)
                    : (start + offset) % cycleAtPosition.length;
            val cycle = cycleAtPosition[position];
            if (labelByCycle[cycle] == 0) {
                labelByCycle[cycle] = nextLabel++;
            }

            var orientedRank = 0;
            if (cycle == 0) {
                val gammaRank = gammaRankAtPosition[position];
                if (firstGammaRank == -1) {
                    firstGammaRank = gammaRank;
                }
                orientedRank = reflected
                        ? Math.floorMod(firstGammaRank - gammaRank, GAMMA.length) + 1
                        : Math.floorMod(gammaRank - firstGammaRank, GAMMA.length) + 1;
                fixedEntries[offset] = (short) (orientedRank - 1);
            } else {
                if (fixedSymbolByCycle[cycle] == -1) {
                    fixedSymbolByCycle[cycle] =
                            nextSymbolBySize[cycleSizes[cycle]]++;
                }
                fixedEntries[offset] = (short) fixedSymbolByCycle[cycle];
            }
            entries[offset] = (short) (100 * labelByCycle[cycle] + orientedRank);
        }
    }

    private static int[] buildIdentity(final int n) {
        val identity = new int[n];
        for (int i = 0; i < n; i++) {
            identity[i] = i;
        }
        return identity;
    }
}
