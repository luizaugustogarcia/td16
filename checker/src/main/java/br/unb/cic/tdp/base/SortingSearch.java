package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;

import java.util.List;
import java.util.Objects;

/** Certificate-backed replacement for every native search used by the proof. */
public final class SortingSearch {
    private static volatile CertificateStore certificates;

    private SortingSearch() {
    }

    public static void installCertificateStore(final CertificateStore store) {
        if (certificates != null) {
            throw new IllegalStateException("A certificate store is already installed");
        }
        certificates = Objects.requireNonNull(store);
    }

    public static List<Cycle> searchForSorting(final CyclicTargetPair pair,
                                               final int maxMoves) {
        return searchForSorting(new CyclicTargetPair[]{pair}, maxMoves).getFirst();
    }

    public static List<List<Cycle>> searchForSorting(final CyclicTargetPair[] pairs,
                                                      final int maxMoves) {
        final var store = certificates;
        if (store == null) {
            throw new IllegalStateException("The proof checker has no certificate store");
        }
        return store.find(pairs, maxMoves);
    }
}
