package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;

import java.util.Optional;

/** Valid cyclic-target pair {@code (omega, beta)} with target {@code rho}. */
public final class CyclicTargetPair extends CyclicTargetCandidate {

    private final Cycle rho;
    private volatile ExtendedToricClassKey extendedToricClassKey;
    private volatile CyclicTargetPair canonicalRepresentative;

    private CyclicTargetPair(
            final MulticyclePermutation omega,
            final Cycle beta,
            final Cycle rho
    ) {
        this(omega, beta, rho, false, true);
    }

    private CyclicTargetPair(
            final MulticyclePermutation omega,
            final Cycle beta,
            final Cycle rho,
            final boolean takeOwnership,
            final boolean validate
    ) {
        super(omega, beta, takeOwnership, validate);
        this.rho = takeOwnership ? rho : Cycle.of(rho.getSymbols().clone());
    }

    private CyclicTargetPair(
            final MulticyclePermutation omega,
            final Cycle beta,
            final boolean takeOwnership
    ) {
        super(omega, beta, takeOwnership);
        final var product = getProduct();
        if (product.size() != 1 || product.asNCycle().size() != beta.size()) {
            throw new IllegalStateException(
                    "The product omega * beta is not an N-cycle");
        }
        this.rho = product.asNCycle();
    }

    public static CyclicTargetPair of(
            final MulticyclePermutation omega,
            final Cycle beta
    ) {
        return new CyclicTargetPair(omega, beta, false);
    }

    /**
     * Takes ownership of freshly constructed permutation objects and validates
     * that they form a cyclic-target pair. Callers must retain no mutable alias.
     */
    public static CyclicTargetPair ofOwned(
            final MulticyclePermutation omega,
            final Cycle beta
    ) {
        return new CyclicTargetPair(omega, beta, true);
    }

    /**
     * Takes ownership of a pair and its already-constructed target.  The
     * caller guarantees that the three permutations have the same domain and
     * satisfy {@code rho = omega * beta} with {@code beta} and {@code rho}
     * both full cycles.
     */
    public static CyclicTargetPair ofKnownTargetOwned(
            final MulticyclePermutation omega,
            final Cycle beta,
            final Cycle rho
    ) {
        return new CyclicTargetPair(omega, beta, rho, true, false);
    }

    /** Decodes and validates a realizable fixed-content encoding in one pass. */
    public static CyclicTargetPair fromEncodingWord(final EncodingWord word) {
        final var pair = new CyclicTargetPair(
                word.decodeAlgebraicPermutation(),
                CyclicTargetCandidate.canonicalCycle(word.length()),
                true);
        pair.validateEncodedOrientations(word);
        return pair;
    }

    static Optional<CyclicTargetPair> from(final CyclicTargetCandidate candidate) {
        if (!candidate.formsCyclicTargetPair()) {
            return Optional.empty();
        }
        return Optional.of(new CyclicTargetPair(
                candidate.getOmega(), candidate.getBeta(),
                candidate.getProduct().asNCycle()));
    }

    public Cycle getRho() {
        return rho;
    }

    @Override
    public boolean formsCyclicTargetPair() {
        return true;
    }

    @Override
    public Optional<CyclicTargetPair> asCyclicTargetPair() {
        return Optional.of(this);
    }

    @Override
    public CyclicTargetPair requireCyclicTargetPair() {
        return this;
    }

    public ExtendedToricClassKey extendedToricClassKey() {
        var result = extendedToricClassKey;
        if (result == null) {
            synchronized (this) {
                result = extendedToricClassKey;
                if (result == null) {
                    result = ExtendedToricClassKey.of(this);
                    extendedToricClassKey = result;
                }
            }
        }
        return result;
    }

    @Override
    public CyclicTargetPair canonicalRepresentative() {
        var result = canonicalRepresentative;
        if (result == null) {
            synchronized (this) {
                result = canonicalRepresentative;
                if (result == null) {
                    result = CyclicTargetPair.fromEncodingWord(
                            twistedBraceletKey().canonicalWord());
                    result.canonicalRepresentative = result;
                    canonicalRepresentative = result;
                }
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return "CyclicTargetPair(omega=" + getOmega()
                + ", beta=" + getBeta() + ", rho=" + rho + ')';
    }
}
