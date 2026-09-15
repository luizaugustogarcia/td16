package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static br.unb.cic.tdp.base.CommonOperations.*;
import static org.junit.jupiter.api.Assertions.*;

class CommonOperationsTest {
    final private Cycle alpha = Cycle.of("0 2 7");
    final private Cycle beta = Cycle.of("0 5 4 3 8 7 6 2 1");

    @Test
    void testSimplify() {
        assertEquals(Cycle.of("0 4 8 3 7 2 6 1 5 9 14 13 12 11 10"), simplify(Cycle.of("0 3 6 2 5 1 4 10 9 8 7")));
    }

    @Test
    void testApplyTransposition() {
        assertEquals(Cycle.of("0 1 2 3 4 5 6"), applyTranspositionOptimized(Cycle.of("0 4 5 6 1 2 3"), Cycle.of("0 4 1")));
    }

    @Test
    void testCycleIndex() {
        val beta = Cycle.of("0 5 4 3 2 1");
        val c0 = Cycle.of("0 2 4");
        val c1 = Cycle.of("1 3 5");

        val index = CommonOperations.cycleIndex(Arrays.asList(c0, c1), beta);
        assertArrayEquals(new Cycle[]{c0, c1, c0, c1, c0, c1}, index);
    }

    @Test
    void testAreSymbolsInCyclicOrder() {
        assertTrue(areSymbolsInCyclicOrder(beta.getInverse(), alpha.getSymbols()));
        assertFalse(areSymbolsInCyclicOrder(beta, alpha.getSymbols()));
    }

    @Test
    void testIsOriented() {
        assertTrue(isOriented(beta, alpha.getInverse()));
        assertFalse(isOriented(beta, alpha));
    }
}
