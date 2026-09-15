package br.unb.cic.tdp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Independent fixed-set enumeration for the paper's 5O+3U+3U example. */
class TwistedBraceletBurnsideTest {

    private static final int N = 11;

    @Test
    void independentlyRecoversTheFiveThreeThreeBurnsideCount() {
        final var candidateCount = new int[1];
        final var realizableCount = new int[1];
        final var rotationFixed = new int[N];
        final var reflectionFixed = new int[N];

        for (var fiveMask = 0; fiveMask < 1 << N; fiveMask++) {
            if (Integer.bitCount(fiveMask) != 5) {
                continue;
            }
            final var five = symbols(fiveMask);
            final var remainingMask = ((1 << N) - 1) ^ fiveMask;

            for (var firstThreeMask = remainingMask;
                 firstThreeMask != 0;
                 firstThreeMask = (firstThreeMask - 1) & remainingMask) {
                if (Integer.bitCount(firstThreeMask) != 3) {
                    continue;
                }
                final var secondThreeMask = remainingMask ^ firstThreeMask;
                if (firstThreeMask > secondThreeMask) {
                    continue;
                }

                final var firstThree = symbols(firstThreeMask);
                final var secondThree = symbols(secondThreeMask);
                final var tail = Arrays.copyOfRange(five, 1, five.length);
                permute(tail, 0, orientedTail -> {
                    if (isUnorientedOrder(orientedTail, five)) {
                        return;
                    }

                    candidateCount[0]++;
                    final var omega = new int[N];
                    placeCycle(omega, prepend(five[0], orientedTail));
                    placeUnorientedCycle(omega, firstThree);
                    placeUnorientedCycle(omega, secondThree);

                    final var target = new int[N];
                    for (var symbol = 0; symbol < N; symbol++) {
                        target[symbol] = omega[(symbol + 1) % N];
                    }
                    if (!isNCycle(target)) {
                        return;
                    }

                    realizableCount[0]++;
                    final var inverse = inverse(omega);
                    for (var shift = 0; shift < N; shift++) {
                        if (fixedByRotation(omega, shift)) {
                            rotationFixed[shift]++;
                        }
                        if (fixedByReflectedInversion(omega, inverse, shift)) {
                            reflectionFixed[shift]++;
                        }
                    }
                });
            }
        }

        assertEquals(106_260, candidateCount[0]);
        assertEquals(9_702, realizableCount[0]);
        assertArrayEquals(new int[]{9_702, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                rotationFixed);
        assertArrayEquals(new int[]{76, 76, 76, 76, 76, 76, 76, 76, 76, 76, 76},
                reflectionFixed);
        assertEquals(479,
                (Arrays.stream(rotationFixed).sum()
                        + Arrays.stream(reflectionFixed).sum()) / (2 * N));
    }

    private static boolean fixedByRotation(final int[] omega, final int shift) {
        for (var symbol = 0; symbol < N; symbol++) {
            final var transformed = Math.floorMod(
                    omega[(symbol + shift) % N] - shift, N);
            if (transformed != omega[symbol]) {
                return false;
            }
        }
        return true;
    }

    private static boolean fixedByReflectedInversion(final int[] omega,
                                                     final int[] inverse,
                                                     final int shift) {
        for (var symbol = 0; symbol < N; symbol++) {
            final var reflectedSymbol = Math.floorMod(-shift - symbol, N);
            final var transformed = Math.floorMod(
                    -shift - inverse[reflectedSymbol], N);
            if (transformed != omega[symbol]) {
                return false;
            }
        }
        return true;
    }

    private static int[] inverse(final int[] permutation) {
        final var inverse = new int[permutation.length];
        for (var symbol = 0; symbol < permutation.length; symbol++) {
            inverse[permutation[symbol]] = symbol;
        }
        return inverse;
    }

    private static boolean isNCycle(final int[] permutation) {
        final var visited = new boolean[permutation.length];
        var current = 0;
        for (var step = 0; step < permutation.length; step++) {
            if (visited[current]) {
                return false;
            }
            visited[current] = true;
            current = permutation[current];
        }
        return current == 0;
    }

    private static void placeUnorientedCycle(final int[] permutation,
                                             final int[] support) {
        final var cycle = new int[support.length];
        cycle[0] = support[0];
        for (var index = 1; index < support.length; index++) {
            cycle[index] = support[support.length - index];
        }
        placeCycle(permutation, cycle);
    }

    private static void placeCycle(final int[] permutation, final int[] cycle) {
        for (var index = 0; index < cycle.length; index++) {
            permutation[cycle[index]] = cycle[(index + 1) % cycle.length];
        }
    }

    private static boolean isUnorientedOrder(final int[] tail, final int[] support) {
        for (var index = 0; index < tail.length; index++) {
            if (tail[index] != support[support.length - 1 - index]) {
                return false;
            }
        }
        return true;
    }

    private static int[] prepend(final int first, final int[] tail) {
        final var result = new int[tail.length + 1];
        result[0] = first;
        System.arraycopy(tail, 0, result, 1, tail.length);
        return result;
    }

    private static int[] symbols(final int mask) {
        final var result = new int[Integer.bitCount(mask)];
        var index = 0;
        for (var symbol = 0; symbol < N; symbol++) {
            if ((mask & 1 << symbol) != 0) {
                result[index++] = symbol;
            }
        }
        return result;
    }

    private static void permute(final int[] values,
                                final int index,
                                final java.util.function.Consumer<int[]> consumer) {
        if (index == values.length) {
            consumer.accept(values);
            return;
        }
        for (var candidate = index; candidate < values.length; candidate++) {
            swap(values, index, candidate);
            permute(values, index + 1, consumer);
            swap(values, index, candidate);
        }
    }

    private static void swap(final int[] values, final int left, final int right) {
        final var value = values[left];
        values[left] = values[right];
        values[right] = value;
    }
}
