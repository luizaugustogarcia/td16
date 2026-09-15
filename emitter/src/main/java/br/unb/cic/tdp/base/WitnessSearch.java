package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.OneLinePermutation;

import java.util.List;
import java.util.Objects;

/** The only route from a native result to a persisted certificate. */
public final class WitnessSearch {
    private static volatile AsyncCertificateWriter certificateWriter;

    private WitnessSearch() {
    }

    public static void installWriter(final AsyncCertificateWriter writer) {
        if (certificateWriter != null) {
            throw new IllegalStateException("A proof-scoped certificate writer is already installed");
        }
        certificateWriter = Objects.requireNonNull(writer);
    }

    public static List<Cycle> search(final CyclicTargetPair pair,
                                     final float minimumRate,
                                     final int maximumMoves) {
        final var canonicalPair = pair.canonicalRepresentative();
        final var omega = canonicalPair.getOmega();
        final var beta = canonicalPair.getBeta();
        final var betaBytes = new byte[beta.size()];
        for (var index = 0; index < beta.size(); index++) {
            betaBytes[index] = (byte) beta.get(index);
        }
        final var moves = GPUSortingSearch.getInstance().search(betaBytes,
                        new OneLinePermutation(omega.getOneLineNotation()),
                        omega.getNumberOfEvenCycles(), minimumRate, maximumMoves, true)
                .stream().map(move -> Cycle.of(move[0], move[1], move[2])).toList();
        if (moves.isEmpty()) {
            return moves;
        }
        // The rate configures the GPU search; it is not a certificate obligation.
        CertificateValidator.validate(canonicalPair, moves, maximumMoves);
        final var writer = certificateWriter;
        if (writer == null) {
            throw new IllegalStateException("Native witness succeeded without a proof-scoped certificate writer");
        }
        writer.enqueue(canonicalPair, moves);
        writer.throwIfFailed();
        return moves;
    }
}
