package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CommonOperations;
import br.unb.cic.tdp.base.CyclicTargetCandidate;
import br.unb.cic.tdp.base.SortingSearch;
import br.unb.cic.tdp.base.TwistedBraceletKey;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Complete local classification for route O.
 *
 * <p>The reference cycle is normalized to
 * {@code beta=(0 1 ... k-1)}, and an algebraic {@code k}-cycle is written
 * uniquely as {@code (x0 x1 ... x(k-1))} with {@code x0=0}. The depth-first
 * search enumerates these writings directly. It prunes only conditions that
 * are necessary for the absence of a qualifying 2-move, so it cannot discard
 * an unresolved cycle that would reappear at a larger length.</p>
 */
@Slf4j
public class OddLengthOrientedCycles {

    record SearchResult(long visitedNodes,
                        long targetCandidateOrbits,
                        long solvedCandidateOrbits,
                        Set<CyclicTargetCandidate> residualClasses) {
        SearchResult {
            residualClasses = Set.copyOf(residualClasses);
        }
    }

    record CompleteEnumeration(long visitedNodes, List<int[]> cycles) {
        CompleteEnumeration {
            cycles = List.copyOf(cycles);
        }
    }

    static SearchResult search(final float maxApproximationRatio, final int cycleLength) {
        if (!(maxApproximationRatio > 0) || !Float.isFinite(maxApproximationRatio)) {
            throw new IllegalArgumentException("maxApproximationRatio must be finite and positive");
        }

        val enumeration = enumerateSurvivingCycles(cycleLength);
        val beta = Cycle.of(canonicalCycle(cycleLength));
        val candidates = new TreeMap<TwistedBraceletKey, CyclicTargetCandidate>();

        for (val symbols : enumeration.cycles()) {
            val cycle = Cycle.of(symbols);
            if (!CommonOperations.isOriented(beta, cycle)) {
                continue;
            }
            val candidate = new CyclicTargetCandidate(
                    new MulticyclePermutation(cycle), beta);
            candidates.putIfAbsent(candidate.twistedBraceletKey(), candidate);
        }

        val minRate = 2 / maxApproximationRatio;
        val maxMoves = (cycleLength + 1) / 2;
        val residuals = new HashSet<CyclicTargetCandidate>();

        for (val candidate : candidates.values()) {
            val moves = searchAndValidate(candidate, minRate, maxMoves);
            val meetsMinimumRate = meetsMinimumRate(
                    candidate.getOmega(), moves, minRate);
            if (!moves.isEmpty() && !meetsMinimumRate) {
                throw belowTargetRate(candidate, moves, minRate);
            }
            if (!meetsMinimumRate) {
                residuals.add(candidate);
            }
        }

        val result = new SearchResult(
                enumeration.visitedNodes(),
                candidates.size(),
                candidates.size() - residuals.size(),
                residuals);

        log.info("    complete oriented-cycle classification: cycleLength={}, "
                        + "visitedNodes={}, survivingCycles={}, "
                        + "orientedCandidateOrbits={}, sequenceSatisfiedCandidateOrbits={}, "
                        + "unsatisfiedCandidateOrbits={}",
                cycleLength, result.visitedNodes(), enumeration.cycles().size(),
                result.targetCandidateOrbits(), result.solvedCandidateOrbits(),
                result.residualClasses().size());
        result.residualClasses().stream()
                .sorted(Comparator.comparing(CyclicTargetCandidate::twistedBraceletKey))
                .forEach(pair -> log.info(
                        "      unsatisfied candidate orbit: omega={}, beta={}, braceletKey={}",
                        pair.getOmega(), pair.getBeta(), pair.twistedBraceletKey()));

        return result;
    }

    /**
     * Enumerates the normalized {@code k}-cycles that survive the necessary
     * conditions used by the route-O search. This retained set contains every
     * cycle with no qualifying 2-move and may a priori be larger. The node count
     * includes the root {@code x0=0} and every surviving partial writing.
     */
    static CompleteEnumeration enumerateSurvivingCycles(final int cycleLength) {
        if (cycleLength < 3 || cycleLength % 2 == 0) {
            throw new IllegalArgumentException("cycleLength must be odd and at least 3");
        }

        val path = new int[cycleLength];
        val used = new boolean[cycleLength];
        path[0] = 0;
        used[0] = true;

        val cycles = new ArrayList<int[]>();
        val visitedNodes = new long[1];
        enumerate(path, used, 1, cycles, visitedNodes);
        return new CompleteEnumeration(visitedNodes[0], cycles);
    }

    private static void enumerate(
            final int[] path,
            final boolean[] used,
            final int depth,
            final List<int[]> cycles,
            final long[] visitedNodes
    ) {
        visitedNodes[0]++;
        val cycleLength = path.length;
        val halfTurn = (cycleLength + 1) / 2;

        if (depth == cycleLength) {
            if (forwardDistance(path[cycleLength - 1], path[0], cycleLength) < halfTurn
                    || violatesQualifyingCriterion(path)) {
                return;
            }
            cycles.add(path.clone());
            return;
        }

        val previous = path[depth - 1];
        for (var symbol = 1; symbol < cycleLength; symbol++) {
            if (used[symbol]
                    || forwardDistance(previous, symbol, cycleLength) < halfTurn) {
                continue;
            }

            path[depth] = symbol;
            if (violatesDeterminedCriterion(path, depth)) {
                continue;
            }

            used[symbol] = true;
            enumerate(path, used, depth + 1, cycles, visitedNodes);
            used[symbol] = false;
        }
    }

    /**
     * Tests the sufficient qualifying-move criterion. If some
     * {@code a, C(a), C^m(a)} occur in beta order for even
     * {@code m in [2,k-1]}, the criterion constructs a qualifying 2-move.
     */
    static boolean meetsQualifying2MoveCriterion(final Cycle cycle, final Cycle beta) {
        if (cycle.size() != beta.size() || cycle.size() % 2 == 0) {
            throw new IllegalArgumentException(
                    "cycle and beta must have the same odd length");
        }

        for (var index = 0; index < cycle.size(); index++) {
            val a = cycle.get(index);
            val b = cycle.get((index + 1) % cycle.size());
            for (var exponent = 2; exponent <= cycle.size() - 1; exponent += 2) {
                val c = cycle.get((index + exponent) % cycle.size());
                if (CommonOperations.areSymbolsInCyclicOrder(beta, a, b, c)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Tests criterion instances completed by the newly assigned path entry. */
    private static boolean violatesDeterminedCriterion(final int[] path, final int depth) {
        for (var exponent = 2; exponent <= depth; exponent += 2) {
            val start = depth - exponent;
            if (inCanonicalCyclicOrder(
                    path[start], path[start + 1], path[depth], path.length)) {
                return true;
            }
        }
        return false;
    }

    /** Tests all criterion instances, including those that wrap around x0. */
    private static boolean violatesQualifyingCriterion(final int[] path) {
        for (var start = 0; start < path.length; start++) {
            for (var exponent = 2; exponent <= path.length - 1; exponent += 2) {
                if (inCanonicalCyclicOrder(
                        path[start],
                        path[(start + 1) % path.length],
                        path[(start + exponent) % path.length],
                        path.length)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inCanonicalCyclicOrder(
            final int a,
            final int b,
            final int c,
            final int modulus
    ) {
        return forwardDistance(a, b, modulus) < forwardDistance(a, c, modulus);
    }

    private static int forwardDistance(final int from, final int to, final int modulus) {
        return Math.floorMod(to - from, modulus);
    }

    private static int[] canonicalCycle(final int cycleLength) {
        val symbols = new int[cycleLength];
        for (var symbol = 0; symbol < cycleLength; symbol++) {
            symbols[symbol] = symbol;
        }
        return symbols;
    }

    private static List<Cycle> searchAndValidate(
            final CyclicTargetCandidate candidate,
            final float minRate,
            final int maxMoves
    ) {
        return candidate.asCyclicTargetPair()
                .map(pair -> SortingSearch.searchForSorting(
                        pair.canonicalRepresentative(), minRate, maxMoves))
                .orElseGet(List::of);
    }

    static boolean meetsMinimumRate(
            final MulticyclePermutation omega,
            final List<Cycle> moves,
            final float minRate
    ) {
        if (moves.isEmpty()) {
            return false;
        }
        val oddCycleIncrease = omega.getNumberOfSymbols()
                - omega.getNumberOfEvenCycles();
        return oddCycleIncrease / (float) moves.size() >= minRate;
    }

    private static IllegalStateException belowTargetRate(
            final CyclicTargetCandidate candidate,
            final List<Cycle> moves,
            final float minRate
    ) {
        val omega = candidate.getOmega();
        val achievedRate = (omega.getNumberOfSymbols()
                - omega.getNumberOfEvenCycles()) / (float) moves.size();
        return new IllegalStateException("Target-length search result rate "
                + achievedRate + " is below the required rate " + minRate
                + " for omega=" + omega + ", beta=" + candidate.getBeta()
                + ", moves=" + moves);
    }
}
