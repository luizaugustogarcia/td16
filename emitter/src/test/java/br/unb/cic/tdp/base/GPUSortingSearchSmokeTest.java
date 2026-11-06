package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.OneLinePermutation;
import br.unb.cic.tdp.permutation.Cycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "tdp.gpuSmoke", matches = "true")
class GPUSortingSearchSmokeTest {

    @Test
    void sortsConcurrentThreeCyclesThroughTheBoundedDispatcher() throws Exception {
        GPUSortingSearch.init(new int[]{0}, 2, 32L * 1024L * 1024L);
        final var executor = Executors.newVirtualThreadPerTaskExecutor();
        final var futures = new ArrayList<Future<?>>();

        try {
            for (var search = 0; search < 32; search++) {
                futures.add(executor.submit(() -> {
                    final var moves = GPUSortingSearch.getInstance().search(
                            new byte[]{0, 1, 2},
                            new OneLinePermutation(new byte[]{1, 2, 0}),
                            1,
                            2.0f,
                            1,
                            true);

                    assertEquals(1, moves.size());
                    assertArrayEquals(new int[]{0, 1, 2}, moves.getFirst());
                }));
            }
            for (final var future : futures) {
                future.get();
            }

            final var beta = new byte[19];
            for (var symbol = 0; symbol < beta.length; symbol++) {
                beta[symbol] = (byte) symbol;
            }
            for (final var symbols : new int[][]{
                    {0, 10, 1, 11, 2, 12, 3, 13, 4, 14, 5, 15, 6, 16, 7, 17, 8, 18, 9},
                    {0, 16, 13, 10, 7, 4, 1, 17, 14, 11, 8, 5, 2, 18, 15, 12, 9, 6, 3}
            }) {
                final var omega = new byte[19];
                for (var index = 0; index < symbols.length; index++) {
                    omega[symbols[index]] = (byte) symbols[(index + 1) % symbols.length];
                }
                final var moves = GPUSortingSearch.getInstance().search(
                        beta, new OneLinePermutation(omega), 0, 18 / 10.0f, 10, true);
                assertEquals(10, moves.size(),
                        () -> "Expected a (10,9)-clearing sequence for " + Cycle.of(symbols));
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
