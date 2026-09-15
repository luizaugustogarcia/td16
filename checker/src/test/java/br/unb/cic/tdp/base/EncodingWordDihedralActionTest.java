package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncodingWordDihedralActionTest {

    @Test
    void distinguishedTwistedReflectionMatchesThePaperExample() {
        final var word = StandardizedEncodingWord.of(
                new int[]{1, 2, 1, 2, 2, 1, 2, 2},
                new int[]{0, 1, 0, 3, 5, 0, 2, 4});

        final var original = CyclicTargetCandidate.fromEncodingWord(word);
        assertEquals(new MulticyclePermutation("(0 5 2)(1 6 3 7 4)"),
                original.getOmega());
        assertEquals(new MulticyclePermutation("(0 6 4 2 7 5 3 1)"),
                original.getProduct());

        final var reflectedWord = word.transform(0, true);
        assertEquals("[1,2_0,2_2,1,2_4,2_1,1,2_3]", reflectedWord.toString());
        final var reflected = CyclicTargetCandidate.fromEncodingWord(reflectedWord);
        assertEquals(new MulticyclePermutation("(0 6 3)(1 5 2 7 4)"),
                reflected.getOmega());
        assertEquals(new MulticyclePermutation("(0 5 3 1 7 6 4 2)"),
                reflected.getProduct());
        assertEquals(word, reflectedWord.transform(0, true));
    }

    @Test
    void implementationUsesThePaperDihedralIndexingAndDecoderAction() {
        final var word = StandardizedEncodingWord.of(
                new int[]{1, 2, 1, 2, 2, 1, 2, 2},
                new int[]{0, 1, 0, 3, 5, 0, 2, 4});
        final var omega = CyclicTargetCandidate.fromEncodingWord(word).getOmega();
        final var inverse = omega.getInverse();
        final var n = word.length();

        for (var shift = 0; shift < n; shift++) {
            final var directReflectedRotation = word.transform(shift, true);
            assertEquals(directReflectedRotation,
                    word.transform(0, true).transform(shift, false),
                    "transform(shift, true) must implement r_shift s");

            final var groupRelation = word.transform(0, true)
                    .transform(shift, false)
                    .transform(0, true);
            assertEquals(word.transform(-shift, false), groupRelation,
                    "The implementation must satisfy s r_shift s = r_-shift");

            final var transformedOmega = CyclicTargetCandidate
                    .fromEncodingWord(directReflectedRotation)
                    .getOmega();
            for (var symbol = 0; symbol < n; symbol++) {
                final var reflectedSymbol = Math.floorMod(-shift - symbol, n);
                final var expected = Math.floorMod(
                        -shift - inverse.image(reflectedSymbol), n);
                assertEquals(expected, transformedOmega.image(symbol),
                        "Decoder equivariance failed for reflected shift " + shift
                                + " at symbol " + symbol);
            }
        }
    }
}
