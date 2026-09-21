package br.unb.cic.tdp.base;

import java.util.Arrays;
import java.util.Objects;

/** Immutable value key identifying one twisted-bracelet orbit. */
public final class TwistedBraceletKey implements Comparable<TwistedBraceletKey> {

    private final StandardizedEncodingWord canonicalWord;

    private TwistedBraceletKey(final StandardizedEncodingWord canonicalWord) {
        this.canonicalWord = canonicalWord;
    }

    /** Builds a key from a word already proved least in its bracelet orbit. */
    public static TwistedBraceletKey ofCanonicalWord(
            final StandardizedEncodingWord canonicalWord
    ) {
        return new TwistedBraceletKey(Objects.requireNonNull(canonicalWord));
    }

    public static TwistedBraceletKey of(final CyclicTargetCandidate candidate) {
        final var least = candidate.dihedralOrbitWords()
                .map(DihedralOrbitWord::word)
                .min(TwistedBraceletKey::compareInFixedColorOrder)
                .orElseThrow();
        return new TwistedBraceletKey(least);
    }

    /**
     * Compares words in the fixed alphabet used for persistent orbit keys.
     *
     * <p>Standardized encoding labels record global first occurrence.  That
     * convention is useful for an intrinsic representation, but it is not the
     * fixed color order: after a rotation, cycles belonging to two different
     * annotated parts can exchange their first-occurrence labels.  The fixed
     * alphabet orders colors by decreasing size, then puts unoriented colors
     * before oriented colors of the same size, and uses first occurrence only
     * to order indistinguishable colors.  Oriented ranks follow their color.
     */
    private static int compareInFixedColorOrder(
            final StandardizedEncodingWord left,
            final StandardizedEncodingWord right
    ) {
        final var leftSymbols = symbolsInFixedColorOrder(left);
        final var rightSymbols = symbolsInFixedColorOrder(right);
        return Arrays.compare(leftSymbols, rightSymbols);
    }

    private static int[] symbolsInFixedColorOrder(
            final StandardizedEncodingWord word
    ) {
        final var length = word.length();
        final var sizes = new int[length + 1];
        final var oriented = new boolean[length + 1];
        var colorCount = 0;
        for (var position = 0; position < length; position++) {
            final var label = word.cycleLabelAt(position);
            if (sizes[label]++ == 0) {
                colorCount++;
            }
            oriented[label] |= word.rankAt(position) != 0;
        }

        final var colors = new Integer[colorCount];
        for (var label = 1; label <= colorCount; label++) {
            colors[label - 1] = label;
        }
        Arrays.sort(colors, (left, right) -> {
            final var sizeComparison = Integer.compare(sizes[right], sizes[left]);
            if (sizeComparison != 0) {
                return sizeComparison;
            }
            final var orientationComparison = Boolean.compare(
                    oriented[left], oriented[right]);
            return orientationComparison != 0
                    ? orientationComparison
                    : Integer.compare(left, right);
        });

        final var firstSymbolByLabel = new int[colorCount + 1];
        var nextSymbol = 0;
        for (final var label : colors) {
            firstSymbolByLabel[label] = nextSymbol;
            nextSymbol += oriented[label] ? sizes[label] : 1;
        }

        final var symbols = new int[length];
        for (var position = 0; position < length; position++) {
            final var label = word.cycleLabelAt(position);
            symbols[position] = firstSymbolByLabel[label]
                    + (oriented[label] ? word.rankAt(position) - 1 : 0);
        }
        return symbols;
    }

    public StandardizedEncodingWord canonicalWord() {
        return canonicalWord;
    }

    public CyclicTargetCandidate canonicalRepresentative() {
        return CyclicTargetCandidate.fromEncodingWord(canonicalWord);
    }

    @Override
    public int compareTo(final TwistedBraceletKey other) {
        return canonicalWord.compareTo(other.canonicalWord);
    }

    @Override
    public boolean equals(final Object object) {
        return object instanceof TwistedBraceletKey other
                && canonicalWord.equals(other.canonicalWord);
    }

    @Override
    public int hashCode() {
        return canonicalWord.hashCode();
    }

    @Override
    public String toString() {
        return canonicalWord.toString();
    }
}
