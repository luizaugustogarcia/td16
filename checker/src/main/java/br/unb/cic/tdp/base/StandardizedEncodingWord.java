package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;

import java.util.IdentityHashMap;

import static br.unb.cic.tdp.base.CommonOperations.areSymbolsInCyclicOrder;
import static br.unb.cic.tdp.base.CommonOperations.cycleIndex;

/**
 * Encoding word with auxiliary color names and oriented rank origins fixed by
 * first occurrence.
 */
public final class StandardizedEncodingWord extends EncodingWord {

    private StandardizedEncodingWord(final int[] cycleLabels, final int[] ranks) {
        super(cycleLabels, ranks);
        validateStandardization();
    }

    public static StandardizedEncodingWord of(
            final int[] cycleLabels,
            final int[] ranks
    ) {
        return new StandardizedEncodingWord(cycleLabels, ranks);
    }

    public static StandardizedEncodingWord from(
            final MulticyclePermutation omega,
            final Cycle beta
    ) {
        for (final var cycle : omega) {
            if (cycle.size() == 1) {
                throw new IllegalStateException(
                        "The fixed-content encoding is defined for fixed-point-free algebraic permutations");
            }
        }

        final var cycleBySymbol = cycleIndex(omega, beta);
        final var labelByCycle = new IdentityHashMap<Cycle, Integer>();
        final var orientedByCycle = new IdentityHashMap<Cycle, Boolean>();
        final var rankBySymbolByCycle = new IdentityHashMap<Cycle, int[]>();
        final var betaPosition = new int[beta.size()];
        final var labels = new int[beta.size()];
        final var ranks = new int[beta.size()];
        final var betaInverse = beta.getInverse();
        var nextLabel = 1;

        for (var position = 0; position < beta.size(); position++) {
            betaPosition[beta.get(position)] = position;
        }

        for (var position = 0; position < beta.size(); position++) {
            final var symbol = beta.get(position);
            final var cycle = cycleBySymbol[symbol];
            if (cycle == null) {
                throw new IllegalStateException(
                        "The fixed-content encoding requires every algebraic fixed point to be absent");
            }

            var label = labelByCycle.get(cycle);
            if (label == null) {
                label = nextLabel++;
                labelByCycle.put(cycle, label);
            }
            labels[position] = label;

            final var oriented = orientedByCycle.computeIfAbsent(cycle,
                    candidate -> !areSymbolsInCyclicOrder(
                            betaInverse, candidate.getSymbols()));
            if (oriented) {
                final var rankBySymbol = rankBySymbolByCycle.computeIfAbsent(cycle,
                        candidate -> ranksFromFirstOccurrence(candidate, betaPosition, beta.size()));
                ranks[position] = rankBySymbol[symbol];
            }
        }

        return new StandardizedEncodingWord(labels, ranks);
    }

    /**
     * Applies {@code r_shift}, or {@code r_shift s} when reflected, and
     * restandardizes auxiliary color names and oriented-rank origins.
     * The distinguished reflection is the paper's {@code s(j) = -j}.
     */
    public StandardizedEncodingWord transform(final int shift, final boolean reflected) {
        final var cycleSizes = new int[length() + 1];
        for (var position = 0; position < length(); position++) {
            cycleSizes[cycleLabelAt(position)]++;
        }

        final var newLabelByOldLabel = new int[length() + 1];
        final var rankOriginByOldLabel = new int[length() + 1];
        final var originSet = new boolean[length() + 1];
        final var labels = new int[length()];
        final var ranks = new int[length()];
        var nextLabel = 1;

        for (var offset = 0; offset < length(); offset++) {
            final var position = reflected
                    ? Math.floorMod(-shift - offset, length())
                    : Math.floorMod(shift + offset, length());
            final var oldLabel = cycleLabelAt(position);
            if (newLabelByOldLabel[oldLabel] == 0) {
                newLabelByOldLabel[oldLabel] = nextLabel++;
            }
            labels[offset] = newLabelByOldLabel[oldLabel];

            final var oldRank = rankAt(position);
            if (oldRank == 0) {
                continue;
            }
            final var size = cycleSizes[oldLabel];
            final var zeroBasedRank = oldRank - 1;
            final var transformedRank = reflected
                    ? Math.floorMod(-zeroBasedRank, size)
                    : zeroBasedRank;
            if (!originSet[oldLabel]) {
                rankOriginByOldLabel[oldLabel] = transformedRank;
                originSet[oldLabel] = true;
            }
            ranks[offset] = Math.floorMod(
                    transformedRank - rankOriginByOldLabel[oldLabel], size) + 1;
        }
        return new StandardizedEncodingWord(labels, ranks);
    }

    private static int[] ranksFromFirstOccurrence(
            final Cycle cycle,
            final int[] betaPosition,
            final int domainSize
    ) {
        final var ranks = new int[domainSize];
        final var symbols = cycle.getSymbols();
        var firstCycleIndex = 0;
        var firstPosition = Integer.MAX_VALUE;
        for (var cycleIndex = 0; cycleIndex < symbols.length; cycleIndex++) {
            final var position = betaPosition[symbols[cycleIndex]];
            if (position < firstPosition) {
                firstPosition = position;
                firstCycleIndex = cycleIndex;
            }
        }
        for (var offset = 0; offset < symbols.length; offset++) {
            ranks[symbols[(firstCycleIndex + offset) % symbols.length]] = offset + 1;
        }
        return ranks;
    }

    private void validateStandardization() {
        final var firstRankByLabel = new int[length() + 1];
        final var seenLabel = new boolean[length() + 1];
        var nextLabel = 1;
        for (var position = 0; position < length(); position++) {
            final var label = cycleLabelAt(position);
            if (!seenLabel[label]) {
                if (label != nextLabel++) {
                    throw new IllegalArgumentException(
                            "Standardized cycle labels must be named by first occurrence");
                }
                seenLabel[label] = true;
                firstRankByLabel[label] = rankAt(position);
            }
        }
        for (var label = 1; label < firstRankByLabel.length; label++) {
            if (firstRankByLabel[label] != 0 && firstRankByLabel[label] != 1) {
                throw new IllegalArgumentException(
                        "A standardized oriented rank origin must be the first occurrence");
            }
        }
    }
}
