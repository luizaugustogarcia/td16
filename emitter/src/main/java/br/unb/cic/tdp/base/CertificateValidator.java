package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import br.unb.cic.tdp.permutation.PermutationGroups;

import java.util.List;

/** Replays a witness and verifies only intrinsic certificate obligations. */
public final class CertificateValidator {
    private CertificateValidator() {
    }

    public static void validate(final CyclicTargetPair canonicalPair,
                                final List<Cycle> moves,
                                final int maximumMoves) {
        if (canonicalPair != canonicalPair.canonicalRepresentative()) {
            throw new IllegalArgumentException("Certificates must be validated from the canonical representative");
        }
        if (moves.isEmpty() || moves.size() > maximumMoves) {
            throw new IllegalArgumentException("Certificate has " + moves.size()
                    + " moves; the required range is 1.." + maximumMoves);
        }

        final var target = canonicalPair.getRho();
        Cycle beta = canonicalPair.getBeta();
        MulticyclePermutation omega = canonicalPair.getOmega();
        for (final var move : moves) {
            requireApplicable(beta, move);
            omega = PermutationGroups.computeProduct(omega, move.getInverse());
            beta = CommonOperations.applyTranspositionOptimized(beta, move);
            final var currentTarget = PermutationGroups.computeProduct(true, beta.size(), omega, beta);
            if (!currentTarget.equals(new MulticyclePermutation(target))) {
                throw new IllegalArgumentException("Certificate move does not preserve the cyclic target: " + move);
            }
        }
        if (!omega.isIdentity()) {
            throw new IllegalArgumentException("Certificate does not sort the algebraic permutation");
        }
    }

    private static void requireApplicable(final Cycle beta, final Cycle move) {
        if (move.size() != 3 || move.get(0) == move.get(1) || move.get(1) == move.get(2)
                || move.get(0) == move.get(2)) {
            throw new IllegalArgumentException("A move must contain three distinct symbols");
        }
        final int first = beta.indexOf(move.get(0));
        final int second = beta.indexOf(move.get(1));
        final int third = beta.indexOf(move.get(2));
        if (first < 0 || second < 0 || third < 0 || !(first < second && second < third)) {
            throw new IllegalArgumentException("Move is not an ordered triple in the current cyclic order: " + move);
        }
    }
}
