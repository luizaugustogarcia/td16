package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.OneLinePermutation;
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
        } finally {
            executor.shutdownNow();
        }
    }
}
