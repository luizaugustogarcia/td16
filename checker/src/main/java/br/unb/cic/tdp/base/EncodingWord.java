package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;

import java.util.Arrays;

/**
 * Immutable internal form of a fixed-content encoding word.
 *
 * <p>Each position stores a positive cycle label and either rank zero for an
 * unranked color or a rank in {@code 1..m} for a ranked color of multiplicity
 * {@code m}. The paper uses ranks in {@code 0..m-1}; adding one internally keeps
 * rank zero available to distinguish unranked colors without floating-point
 * sentinels.
 */
public class EncodingWord implements Comparable<EncodingWord> {

    private final int[] entries;
    private final int rankBase;
    private final int hashCode;

    protected EncodingWord(final int[] cycleLabels, final int[] ranks) {
        if (cycleLabels.length == 0 || cycleLabels.length != ranks.length) {
            throw new IllegalArgumentException(
                    "Encoding-word labels and ranks must have the same positive length");
        }

        rankBase = cycleLabels.length + 1;
        entries = new int[cycleLabels.length];
        for (var i = 0; i < entries.length; i++) {
            validateEntry(cycleLabels[i], ranks[i], entries.length);
            entries[i] = Math.addExact(Math.multiplyExact(cycleLabels[i], rankBase), ranks[i]);
        }
        validateColors();
        hashCode = Arrays.hashCode(entries);
    }

    public static EncodingWord of(final int[] cycleLabels, final int[] ranks) {
        return new EncodingWord(cycleLabels, ranks);
    }

    public int length() {
        return entries.length;
    }

    public int cycleLabelAt(final int position) {
        return entries[position] / rankBase;
    }

    public int rankAt(final int position) {
        return entries[position] % rankBase;
    }

    public int[] entries() {
        return entries.clone();
    }

    MulticyclePermutation decodeAlgebraicPermutation() {
        var maxLabel = 0;
        for (var i = 0; i < length(); i++) {
            maxLabel = Math.max(maxLabel, cycleLabelAt(i));
        }

        final var sizes = new int[maxLabel + 1];
        for (var i = 0; i < length(); i++) {
            sizes[cycleLabelAt(i)]++;
        }

        final var symbolsByLabel = new int[maxLabel + 1][];
        final var fillByLabel = new int[maxLabel + 1];
        for (var label = 1; label <= maxLabel; label++) {
            if (sizes[label] > 0) {
                symbolsByLabel[label] = new int[sizes[label]];
            }
        }

        for (var position = length() - 1; position >= 0; position--) {
            final var label = cycleLabelAt(position);
            final var rank = rankAt(position);
            if (rank == 0) {
                symbolsByLabel[label][fillByLabel[label]++] = position;
            } else {
                symbolsByLabel[label][rank - 1] = position;
            }
        }

        final var omega = new MulticyclePermutation();
        for (var label = 1; label <= maxLabel; label++) {
            if (symbolsByLabel[label] != null) {
                omega.add(Cycle.of(symbolsByLabel[label]));
            }
        }
        return omega;
    }

    private static void validateEntry(final int label, final int rank, final int length) {
        if (label <= 0 || label > length) {
            throw new IllegalArgumentException(
                    "Cycle labels must belong to 1.." + length + ": " + label);
        }
        if (rank < 0 || rank > length) {
            throw new IllegalArgumentException(
                    "Cycle ranks must belong to 0.." + length + ": " + rank);
        }
    }

    private void validateColors() {
        final var counts = new int[length() + 1];
        final var rankedCounts = new int[length() + 1];
        final var rankStride = length() + 1;
        final var seenRanks = new boolean[rankStride * rankStride];

        for (var position = 0; position < length(); position++) {
            final var label = cycleLabelAt(position);
            final var rank = rankAt(position);
            counts[label]++;
            if (rank > 0) {
                final var rankIndex = label * rankStride + rank;
                if (seenRanks[rankIndex]) {
                    throw new IllegalArgumentException(
                            "Rank " + rank + " occurs more than once for cycle label " + label);
                }
                seenRanks[rankIndex] = true;
                rankedCounts[label]++;
            }
        }

        for (var label = 1; label < counts.length; label++) {
            if (counts[label] == 0) {
                continue;
            }
            if (rankedCounts[label] != 0 && rankedCounts[label] != counts[label]) {
                throw new IllegalArgumentException(
                        "A cycle color cannot mix ranked and unranked entries: " + label);
            }
            if (rankedCounts[label] > 0) {
                if (rankedCounts[label] < 3) {
                    throw new IllegalArgumentException(
                            "Only cycles of length at least three may be ranked: " + label);
                }
                for (var rank = 1; rank <= counts[label]; rank++) {
                    if (!seenRanks[label * rankStride + rank]) {
                        throw new IllegalArgumentException(
                                "Ranked cycle " + label + " is missing rank " + rank);
                    }
                }
            }
        }
    }

    @Override
    public int compareTo(final EncodingWord other) {
        return Arrays.compare(entries, other.entries);
    }

    @Override
    public boolean equals(final Object object) {
        return object instanceof EncodingWord other
                && Arrays.equals(entries, other.entries);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        final var result = new StringBuilder("[");
        for (var i = 0; i < length(); i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(cycleLabelAt(i));
            if (rankAt(i) > 0) {
                result.append('_').append(rankAt(i) - 1);
            }
        }
        return result.append(']').toString();
    }
}
