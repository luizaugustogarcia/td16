package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;

import java.util.List;

/** Compatibility facade for the one native-witness boundary. */
public final class SortingSearch {
    private SortingSearch() {
    }

    /**
     * Searches the canonical representative and, when successful, validates
     * and enqueues its certificate before returning it.
     */
    public static List<Cycle> searchForSorting(final CyclicTargetPair pair,
                                               final float minRate,
                                               final int maxMoves) {
        return WitnessSearch.search(pair, minRate, maxMoves);
    }
}
