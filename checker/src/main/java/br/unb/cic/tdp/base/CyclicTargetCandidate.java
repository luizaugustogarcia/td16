package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import br.unb.cic.tdp.permutation.PermutationGroups;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Unchecked realizability candidate {@code (omega, beta)}.
 *
 * <p>The current cycle {@code beta} is an {@code N}-cycle and {@code omega} is
 * a permutation on the same domain, represented by disjoint explicit cycles;
 * omitted symbols are algebraic fixed points. The candidate becomes a
 * cyclic-target pair exactly when {@code omega * beta} is also an
 * {@code N}-cycle.
 */
public sealed class CyclicTargetCandidate permits CyclicTargetPair {

    private final MulticyclePermutation omega;
    private final Cycle beta;
    private volatile int[] omegaImages;
    private volatile int structuralHashCode;
    private volatile boolean structuralHashCodeComputed;

    private volatile MulticyclePermutation product;
    private volatile StandardizedEncodingWord standardizedWord;
    private volatile TwistedBraceletKey twistedBraceletKey;
    private volatile CyclicTargetCandidate canonicalRepresentative;

    public CyclicTargetCandidate(final MulticyclePermutation omega, final Cycle beta) {
        this(omega, beta, false);
    }

    /**
     * Constructs from either borrowed inputs or freshly decoded owned inputs.
     * The owned path is reserved for factories in this package so that a word
     * need not be decoded and then copied immediately.
     */
    protected CyclicTargetCandidate(
            final MulticyclePermutation omega,
            final Cycle beta,
            final boolean takeOwnership
    ) {
        this(omega, beta, takeOwnership, true);
    }

    /** Internal construction path for objects already proved well formed. */
    protected CyclicTargetCandidate(
            final MulticyclePermutation omega,
            final Cycle beta,
            final boolean takeOwnership,
            final boolean validate
    ) {
        this.beta = takeOwnership ? beta : copy(beta);
        this.omega = takeOwnership ? omega : copy(omega);
        if (validate) {
            validatePermutationRepresentation(this.omega, this.beta);
        }

    }

    public CyclicTargetCandidate(final MulticyclePermutation omega) {
        this(omega, canonicalCycle(omega.getNumberOfSymbols()));
    }

    public CyclicTargetCandidate(final String omega) {
        this(new MulticyclePermutation(omega));
    }

    public static CyclicTargetCandidate fromEncodingWord(final EncodingWord word) {
        final var beta = canonicalCycle(word.length());
        final var candidate = new CyclicTargetCandidate(
                word.decodeAlgebraicPermutation(), beta, true);
        candidate.validateEncodedOrientations(word);
        return candidate;
    }

    public MulticyclePermutation getOmega() {
        return omega;
    }

    public Cycle getBeta() {
        return beta;
    }

    /** Returns {@code omega * beta}; it is a target cycle only after validation. */
    public MulticyclePermutation getProduct() {
        var result = product;
        if (result == null) {
            synchronized (this) {
                result = product;
                if (result == null) {
                    result = PermutationGroups.computeProduct(
                            true, beta.size(), omega, beta);
                    product = result;
                }
            }
        }
        return result;
    }

    public boolean formsCyclicTargetPair() {
        final var rho = getProduct();
        return rho.size() == 1 && rho.asNCycle().size() == beta.size();
    }

    public Optional<CyclicTargetPair> asCyclicTargetPair() {
        return CyclicTargetPair.from(this);
    }

    public CyclicTargetPair requireCyclicTargetPair() {
        return asCyclicTargetPair().orElseThrow(() -> new IllegalStateException(
                "The candidate product omega * beta is not an N-cycle: " + this));
    }

    public StandardizedEncodingWord standardizedEncodingWord() {
        var result = standardizedWord;
        if (result == null) {
            synchronized (this) {
                result = standardizedWord;
                if (result == null) {
                    result = StandardizedEncodingWord.from(omega, beta);
                    standardizedWord = result;
                }
            }
        }
        return result;
    }

    public Stream<DihedralOrbitWord> dihedralOrbitWords() {
        final var word = standardizedEncodingWord();
        return IntStream.range(0, word.length()).boxed().flatMap(shift -> Stream.of(
                new DihedralOrbitWord(word.transform(shift, false), shift, false),
                new DihedralOrbitWord(word.transform(shift, true), shift, true)));
    }

    public TwistedBraceletKey twistedBraceletKey() {
        var result = twistedBraceletKey;
        if (result == null) {
            synchronized (this) {
                result = twistedBraceletKey;
                if (result == null) {
                    result = TwistedBraceletKey.of(this);
                    twistedBraceletKey = result;
                }
            }
        }
        return result;
    }

    public CyclicTargetCandidate canonicalRepresentative() {
        var result = canonicalRepresentative;
        if (result == null) {
            synchronized (this) {
                result = canonicalRepresentative;
                if (result == null) {
                    final var key = twistedBraceletKey();
                    result = key.canonicalRepresentative();
                    result.twistedBraceletKey = key;
                    result.canonicalRepresentative = result;
                    canonicalRepresentative = result;
                }
            }
        }
        return result;
    }

    /** Returns whether the candidate product has no fixed point. */
    public boolean hasFixedPointFreeProduct() {
        return getProduct().stream().noneMatch(cycle -> cycle.size() == 1);
    }

    public int getThreeNorm() {
        return omega.get3Norm();
    }

    public Set<Integer> getOpenGates() {
        return CommonOperations.getOpenGates(omega, beta);
    }

    public int getNumberOfOpenGates() {
        return getOpenGates().size();
    }

    final void validateEncodedOrientations(final EncodingWord word) {
        final var cycleBySymbol = CommonOperations.cycleIndex(omega, beta);
        for (var position = 0; position < word.length(); position++) {
            final var cycle = cycleBySymbol[position];
            final var encodedAsOriented = word.rankAt(position) > 0;
            final var isOriented = CommonOperations.isOriented(beta, cycle);
            if (encodedAsOriented != isOriented) {
                throw new IllegalArgumentException(
                        "Encoding rank status disagrees with the decoded cycle orientation");
            }
        }
    }

    private static void validatePermutationRepresentation(
            final MulticyclePermutation omega,
            final Cycle beta
    ) {
        if (beta.size() == 0) {
            throw new IllegalArgumentException("The current cycle must not be empty");
        }
        final var domain = new boolean[beta.size()];
        for (final var symbol : beta.getSymbols()) {
            if (symbol < 0 || symbol >= beta.size() || domain[symbol]) {
                throw new IllegalArgumentException(
                        "The current cycle must contain each symbol in 0..N-1 exactly once");
            }
            domain[symbol] = true;
        }

        final var movedOrExplicit = new boolean[beta.size()];
        for (final var cycle : omega) {
            if (cycle.size() == 0) {
                throw new IllegalArgumentException("Algebraic cycles must not be empty");
            }
            for (final var symbol : cycle.getSymbols()) {
                if (symbol < 0 || symbol >= beta.size()) {
                    throw new IllegalArgumentException(
                            "Algebraic cycle symbol lies outside the current-cycle domain: " + symbol);
                }
                if (movedOrExplicit[symbol]) {
                    throw new IllegalArgumentException(
                            "Algebraic cycles must have disjoint supports: " + symbol);
                }
                movedOrExplicit[symbol] = true;
            }
        }
    }

    private int[] omegaImages() {
        var result = omegaImages;
        if (result == null) {
            synchronized (this) {
                result = omegaImages;
                if (result == null) {
                    result = new int[beta.size()];
                    for (var symbol = 0; symbol < result.length; symbol++) {
                        result[symbol] = omega.image(symbol);
                    }
                    omegaImages = result;
                }
            }
        }
        return result;
    }

    static Cycle canonicalCycle(final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("The current-cycle domain must be nonempty");
        }
        final var symbols = new int[size];
        for (var symbol = 0; symbol < size; symbol++) {
            symbols[symbol] = symbol;
        }
        return Cycle.of(symbols);
    }

    private static Cycle copy(final Cycle cycle) {
        return Cycle.of(cycle.getSymbols().clone());
    }

    private static MulticyclePermutation copy(final MulticyclePermutation permutation) {
        final var result = new MulticyclePermutation();
        for (final var cycle : permutation) {
            result.add(copy(cycle));
        }
        return result;
    }

    @Override
    public boolean equals(final Object object) {
        return object instanceof CyclicTargetCandidate other
                && beta.equals(other.beta)
                && Arrays.equals(omegaImages(), other.omegaImages());
    }

    @Override
    public int hashCode() {
        if (!structuralHashCodeComputed) {
            synchronized (this) {
                if (!structuralHashCodeComputed) {
                    structuralHashCode = 31 * beta.hashCode()
                            + Arrays.hashCode(omegaImages());
                    structuralHashCodeComputed = true;
                }
            }
        }
        return structuralHashCode;
    }

    @Override
    public String toString() {
        return "CyclicTargetCandidate(omega=" + omega + ", beta=" + beta + ')';
    }
}
