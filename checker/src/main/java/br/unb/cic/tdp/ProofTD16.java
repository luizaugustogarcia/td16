package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CertificateStore;
import br.unb.cic.tdp.base.CommonOperations;
import br.unb.cic.tdp.base.SortingSearch;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static br.unb.cic.tdp.base.CommonOperations.d;

/**
 * TD(16) = 9: exhaustive partition-expansion proof orchestrator.
 *
 * <p><b>Theorem.</b> The transposition diameter of S_16 is 9. Equivalently, every
 * permutation omega in A_17 (where omega = iota * beta^{-1}, always even) can be expressed
 * as a product of at most 9 applicable 3-cycles.
 *
 * <p>The proof first partitions the 194 fixed-point-free annotated root types
 * into five disjoint priority routes.  Two routes close directly; the other
 * three take one structural step and merge into a shared layer of contracted
 * one-fixed-point successors.  Residual successor obstructions are closed by
 * a common avoidance-preimage audit.
 *
 * <p>This class serves as the single entry point for the complete proof. It dispatches
 * to the existing computational classes ({@link OddLengthOrientedCycles},
 * {@link Oriented9CycleExtensions}, {@link UnorientedCycles}) and validates
 * that all valid partitions are accounted for.
 */
@Slf4j
public class ProofTD16 {

    // ==================== Inner types ====================

    enum CaseStatus {VERIFIED, WIP, TODO}

    enum InitialTypeRoute {
        ALL_UNORIENTED,
        ORIENTED_THREE,
        SELECTED_NINE_WITH_ORIENTED_EVEN,
        ORIENTED_ODD_REDUCTION,
        REMAINING_EVEN_PAIR
    }

    record CaseResult(String label, CaseStatus status, String notes) {
    }

    private record RoutedRootType(AnnotatedCase type, InitialTypeRoute route) {
    }

    private record RootRouting(List<int[]> rootPartitions,
                               List<RoutedRootType> annotatedTypes) {
        private RootRouting {
            rootPartitions = rootPartitions.stream().map(int[]::clone).toList();
            annotatedTypes = List.copyOf(annotatedTypes);
        }

        private List<AnnotatedCase> typesOn(final InitialTypeRoute route) {
            return annotatedTypes.stream()
                    .filter(routed -> routed.route() == route)
                    .map(RoutedRootType::type)
                    .toList();
        }

        private long count(final InitialTypeRoute route) {
            return annotatedTypes.stream()
                    .filter(routed -> routed.route() == route)
                    .count();
        }
    }

    // ==================== Partition data ====================

    /**
     * Partitions of 17 that are impossible with only unoriented cycles.
     * For each listed partition, the all-unoriented realizable extended-toric-class
     * count is zero.
     */
    static final int[][] IMPOSSIBLE_PARTITIONS = {
            {17},
            {13, 2, 2},
            {12, 3, 2},
            {11, 4, 2},
            {11, 3, 3},
            {10, 5, 2},
            {10, 4, 3},
            {9, 6, 2},
            {9, 5, 3},
            {9, 4, 4},
            {9, 2, 2, 2, 2},
            {8, 3, 2, 2, 2},
    };

    // ==================== Unoriented partitions ====================

    /* Number of extended-toric classes
    [8, 7, 2] -> 7
    [7, 4, 2, 2, 2] -> 4800
    [7, 6, 4] -> 185
    [6, 5, 2, 2, 2] -> 16500
    [6, 4, 3, 2, 2] -> 119000
    [6, 6, 5] -> 253
    [5, 5, 3, 2, 2] -> 99948
    [5, 4, 4, 2, 2] -> 155344
    [5, 2, 2, 2, 2, 2, 2] -> 65752
    [4, 4, 4, 3, 2] -> 254430
    [4, 3, 2, 2, 2, 2, 2] -> 996450
    [8, 6, 3] -> 21
    [8, 5, 4] -> 35
    [7, 7, 3] -> 37
    [7, 3, 3, 2, 2] -> 10896
    [7, 5, 5] -> 146
    [6, 3, 3, 3, 2] -> 61200
    [5, 4, 3, 3, 2] -> 485968
    [4, 4, 3, 3, 3] -> 199054
    [3, 3, 3, 2, 2, 2, 2] -> 1325494
    [5, 3, 3, 3, 3] -> 63364
     */

    // Partitions requiring a (9,8)-sequence (3-norm 8)
    static final int[][] SEQ_9_8_PARTITIONS = {
            {8, 7, 2},
            {7, 4, 2, 2, 2},
            {7, 6, 4},
            {6, 5, 2, 2, 2},
            {6, 4, 3, 2, 2},
            {6, 6, 5},
            {5, 4, 4, 2, 2},
            {5, 2, 2, 2, 2, 2, 2},
            {4, 4, 4, 3, 2},
            {4, 3, 2, 2, 2, 2, 2},
    };

    // Partitions requiring a (9,7)-sequence
    static final int[][] SEQ_9_7_PARTITIONS = {
            {8, 6, 3},
            {8, 5, 4},
            {7, 7, 3},
            {7, 3, 3, 2, 2},
            {7, 5, 5},
            {6, 3, 3, 3, 2},
            {5, 5, 3, 2, 2},
            {5, 4, 3, 3, 2},
            {4, 4, 3, 3, 3},
            {3, 3, 3, 2, 2, 2, 2},
    };

    // Partitions requiring a (9,6)-sequence (3-norm 6)
    static final int[][] SEQ_9_6_PARTITIONS = {
            {5, 3, 3, 3, 3},
    };

    // ==================== Entry point ====================

    public static void main(final String[] args) {
        val proofStartedAt = System.nanoTime();
        val proofStartedUtc = Instant.now();
        val rootExpansionStats = new RootExpansionStats();

        try (final CertificateStore certificates = new CertificateStore(Path.of(
                System.getProperty("tdp.certificateDb", "certificates")))) {
            SortingSearch.installCertificateStore(certificates);

            val results = new ArrayList<CaseResult>();

            log.info("=== TD(16) = 9: Proof by Partition Expansions ===");
            log.info("Permutations in S_17 (n+1=17), omega in A_17.");
            log.info("");

            // Build the disjoint coverage map before discharging any branch.
            log.info("== Coverage map: fixed-point-free annotated root types ==");
            val rootRouting = enumerateRootRouting();
            results.addAll(verifyRootRoutingCoverage(rootRouting));

            log.info("");
            log.info("== Branch U: all-unoriented root types ==");
            verifyAllUnorientedPartitionCoverage();
            results.addAll(auxiliaryUnorientedPartitionLemmas());

            log.info("");
            log.info("== Branch T: root types with an oriented 3-cycle ==");
            results.add(auxiliaryLemmaA0());

            log.info("");
            log.info("== Branch O: selected oriented odd-length cycles ==");
            results.addAll(auxiliaryOddLengthCycleLemmas());

            log.info("");
            log.info("== First-step branches and contracted successors ==");
            results.addAll(expandFirstStepBranches(rootRouting, rootExpansionStats));

            val prerequisitesComplete = results.stream()
                    .allMatch(result -> result.status() == CaseStatus.VERIFIED);

            // Avoid selected 2-moves whose contracted successors are residual obstructions.
            log.info("");
            log.info("== Residual obstructions: reconstruct and sort their avoidance preimages ==");
            results.addAll(residualObstructionAvoidance(prerequisitesComplete, rootExpansionStats));

            printSummary(results, System.nanoTime() - proofStartedAt);
        } catch (final RuntimeException | Error failure) {
            printFailureSummary(failure, System.nanoTime() - proofStartedAt);
            throw failure;
        } finally {
            DirectTwistedBraceletGenerator.shutdownExecutor();
        }
    }

    private static RootRouting enumerateRootRouting() {
        val roots = Partitions.generateValidPartitions(17).stream()
                .filter(partition -> countOccurrences(partition, 1) == 0)
                .sorted(ProofTD16::comparePartitions)
                .toList();
        val routedTypes = roots.stream()
                .flatMap(partition -> enumerateAnnotatedCases(partition).stream())
                .map(type -> new RoutedRootType(type, initialTypeRoute(type)))
                .toList();
        return new RootRouting(roots, routedTypes);
    }

    private static List<CaseResult> verifyRootRoutingCoverage(
            final RootRouting routing) {
        requireCoverageCount("fixed-point-free partitions",
                33, routing.rootPartitions().size());
        requireCoverageCount("annotated root types",
                194, routing.annotatedTypes().size());
        requireCoverageCount("all-unoriented routes",
                33, routing.count(InitialTypeRoute.ALL_UNORIENTED));
        requireCoverageCount("oriented-3 routes",
                74, routing.count(InitialTypeRoute.ORIENTED_THREE));
        requireCoverageCount("selected-9/even precedence routes",
                3, routing.count(InitialTypeRoute.SELECTED_NINE_WITH_ORIENTED_EVEN));
        requireCoverageCount("oriented odd-length-cycle routes",
                47, routing.count(InitialTypeRoute.ORIENTED_ODD_REDUCTION));
        requireCoverageCount("remaining even-pair routes",
                37, routing.count(InitialTypeRoute.REMAINING_EVEN_PAIR));

        log.info("  33 partitions -> 194 annotated types");
        log.info("  U:  all-unoriented                                      33");
        log.info("  T:  contains an oriented 3-cycle                       74");
        log.info("  E9: selected oriented 9-cycle + oriented even cycle     3");
        log.info("  O:  remaining selected oriented odd-length cycle        47");
        log.info("  E:  remaining even-pair route                           37");
        log.info("  coverage identity: 33 + 74 + 3 + 47 + 37 = 194");

        return List.of(
                new CaseResult(
                        "C0: 33 fixed-point-free partitions and 194 annotated root types",
                        CaseStatus.VERIFIED,
                        "Every admissible fixed-point-free partition and canonical O/U annotation is generated once"),
                new CaseResult(
                        "C1: root routing 33 + 74 + 3 + 47 + 37 = 194",
                        CaseStatus.VERIFIED,
                        "The five priority routes are disjoint and cover every annotated root type"));
    }

    private static void requireCoverageCount(final String label,
                                             final long expected,
                                             final long actual) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Expected " + expected + " " + label + ", found " + actual);
        }
    }

    /**
     * Expands exactly the three root routes that require a selected first move.
     * The all-unoriented and oriented-3 routes have already closed directly.
     * Every expansion creates at least one fixed point; after direct diameter
     * checks, each unresolved successor has exactly one fixed point and is sent
     * to an exhaustive eight-move search.
     */
    private static Collection<CaseResult> expandFirstStepBranches(
            final RootRouting routing,
            final RootExpansionStats stats) {
        val results = new ArrayList<CaseResult>();
        val exhaustiveCheckedSuccessors = new HashSet<String>();
        val resolvedAnnotatedSuccessors = new HashSet<String>();

        stats.rootPartitions = routing.rootPartitions().size();
        stats.annotatedRoots = routing.annotatedTypes().size();
        stats.rootDirectByPartitionLemma = Math.toIntExact(
                routing.count(InitialTypeRoute.ALL_UNORIENTED));
        stats.rootDirectByA0 = Math.toIntExact(
                routing.count(InitialTypeRoute.ORIENTED_THREE));

        log.info("  O: expanding {} oriented odd-length-cycle root types",
                routing.count(InitialTypeRoute.ORIENTED_ODD_REDUCTION));
        for (val annotatedRoot : routing.typesOn(InitialTypeRoute.ORIENTED_ODD_REDUCTION)) {
            val lemmaSize = nextOddLengthOrientedLemma(annotatedRoot);
            if (lemmaSize == -1) {
                throw new IllegalStateException(
                        "Oriented odd-length-cycle route has no eligible cycle: "
                                + formatAnnotatedCase(annotatedRoot));
            }
            applyLemmaForks(annotatedRoot, lemmaSize, 0, stats,
                    exhaustiveCheckedSuccessors, resolvedAnnotatedSuccessors, "    ");
        }

        log.info("  E9: expanding {} selected-9/even precedence root types",
                routing.count(InitialTypeRoute.SELECTED_NINE_WITH_ORIENTED_EVEN));
        for (val annotatedRoot : routing.typesOn(
                InitialTypeRoute.SELECTED_NINE_WITH_ORIENTED_EVEN)) {
            applyEvenPairJoin(annotatedRoot, 0, stats,
                    exhaustiveCheckedSuccessors, resolvedAnnotatedSuccessors, "    ");
        }

        log.info("  E: expanding {} remaining even-pair root types",
                routing.count(InitialTypeRoute.REMAINING_EVEN_PAIR));
        for (val annotatedRoot : routing.typesOn(InitialTypeRoute.REMAINING_EVEN_PAIR)) {
            applyEvenPairJoin(annotatedRoot, 0, stats,
                    exhaustiveCheckedSuccessors, resolvedAnnotatedSuccessors, "    ");
        }

        val residualConfigurations = stats.generatedConfigurations
                - stats.solvedConfigurations;
        results.add(new CaseResult(
                "C2: " + stats.rootDirectByPartitionLemma
                        + " all-unoriented and " + stats.rootDirectByA0
                        + " oriented-3 root types closed directly",
                CaseStatus.VERIFIED,
                "The two direct root routes contain 107 of the 194 annotated types"));
        results.add(new CaseResult(
                "C3: " + stats.firstOddLemmaApplications
                        + " odd-cycle expansions and " + stats.firstEvenPairJoins
                        + " even-pair expansions",
                CaseStatus.VERIFIED,
                "The expanded routes contain the remaining 87 annotated root types; "
                        + "the even-pair count is 3 precedence routes plus 37 remaining routes"));
        results.add(new CaseResult(
                "C4: every first-step successor enters a direct closure or the shared tail",
                CaseStatus.VERIFIED,
                "At least two fixed points and oriented 3-cycles close directly; every remaining annotation enters a one-fixed-point successor search"));
        results.add(new CaseResult(
                "C5: " + stats.exhaustiveSuccessorChecks
                        + " distinct one-fixed-point successor searches",
                CaseStatus.VERIFIED,
                "The searches generated " + stats.generatedConfigurations
                        + " extended-toric classes: " + stats.solvedConfigurations
                        + " sorted within eight moves and " + residualConfigurations
                        + " retained as residual obstructions"));
        results.add(new CaseResult(
                "C6: " + stats.deferredAfterFirstExpansion
                        + " deferred annotated cases after one expansion",
                stats.deferredAfterFirstExpansion == 0 ? CaseStatus.VERIFIED : CaseStatus.TODO,
                stats.deferredAfterFirstExpansion == 0
                        ? "Every selected first move reaches a direct closure or the shared one-fixed-point tail"
                        : "Deferred cases still have no fixed points and are not yet sent to exhaustive checking"));

        return results;
    }

    // ==================== Residual obstruction closure ====================

    /**
     * Handles the residual obstructions encountered after a selected 2-move.
     *
     * <p>The branch does not try to sort the contracted obstruction. Instead, it
     * uncontracts the fixed point in every cyclic gap and reverses both ways in which
     * the root expansion can reach the residual types: splitting one odd-length root
     * cycle, and joining a pair of even-length root cycles. One representative of every
     * resulting avoidance-preimage class is then sorted independently within nine moves,
     * proving that the selected 2-move leading to the obstruction can be avoided.
     */
    private static List<CaseResult> residualObstructionAvoidance(
            final boolean prerequisitesComplete,
            final RootExpansionStats rootExpansionStats) {
        val results = new ArrayList<CaseResult>();
        val residualObstructions = RemainingSuccessorsSolver.getAndClearResidualObstructions();

        if (!prerequisitesComplete) {
            log.info("  Obstruction-avoidance enumeration not run: prerequisite proof stages are incomplete "
                    + "({} partial residual obstructions collected).", residualObstructions.size());
            results.add(new CaseResult(
                    "C7: obstruction-avoidance enumeration not run in an incomplete proof",
                    CaseStatus.TODO,
                    "The partial residual collection contains "
                            + residualObstructions.size()
                            + " entries; structural corpus invariants and preimage sorting "
                            + "are checked only after every prerequisite stage is verified"));
            return results;
        }

        log.info("  {} residual obstructions collected from the eight-move successor searches",
                residualObstructions.size());

        val observedResiduals = rootExpansionStats.generatedConfigurations
                - rootExpansionStats.solvedConfigurations;
        if (observedResiduals != residualObstructions.size()) {
            throw new IllegalStateException(
                    "Exhaustive-search totals report " + observedResiduals
                            + " residuals, but the collected obstruction corpus contains "
                            + residualObstructions.size());
        }

        val avoidance = MinusTwoMoveExtender.verifyObstructionAvoidance(residualObstructions);

        results.add(new CaseResult(
                "C7: " + avoidance.residualObstructions()
                        + " residual obstructions avoided through "
                        + avoidance.distinctPreimageOrbits()
                        + " distinct avoidance-preimage classes",
                CaseStatus.VERIFIED,
                "The preimage union contains " + avoidance.oddSplitPreimageOrbits()
                        + " odd-cycle-split classes and "
                        + avoidance.evenPairPreimageOrbits()
                        + " even-pair-join classes, with "
                        + avoidance.overlappingPreimageOrbits()
                        + " classes in both sets; one representative of every distinct class "
                        + "has an independent sorting sequence of length at most nine"));

        return results;
    }

    // ==================== Auxiliary Oriented Odd-Length-Cycle Lemmas ====================

    /**
     * Local reduction checks for selected oriented odd-length cycles.
     */
    private static List<CaseResult> auxiliaryOddLengthCycleLemmas() {
        val results = new ArrayList<CaseResult>();
        results.add(auxiliaryLemmaA1());
        results.add(auxiliaryLemmaA2());
        results.add(auxiliaryLemmaA3_0());
        results.add(auxiliaryLemmaA3());
        results.add(auxiliaryLemmaA4());
        results.add(auxiliaryLemmaA5());
        results.add(auxiliaryLemmaA6());
        results.add(auxiliaryLemmaA7());
        return results;
    }

    private static CaseResult auxiliaryLemmaA0() {
        log.info("  A0: Oriented 3-cycle admits a 2-move → S_14, TD(13)=8, total=9 [trivial]");
        return new CaseResult("A0: oriented 3-cycle → 2-move, reduce to S_14",
                CaseStatus.VERIFIED,
                "Trivial: 2-move fixes 3 symbols, S_17 → S_14; TD(13)=8, total 1+8=9");
    }

    private static CaseResult auxiliaryLemmaA1() {
        log.info("  A1: Oriented 5-cycle has either a qualifying 2-move or a (3,2)-sequence → total=9 [trivial]");
        requireNoOrientedCycleResiduals("A1",
                OddLengthOrientedCycles.search(3 / (float) 2, 5));
        return new CaseResult("A1: oriented 5-cycle → 2-move or (3,2)-seq",
                CaseStatus.VERIFIED,
                "Trivial: either a 2-move fixes two symbols or a (3,2)-sequence fixes all 5 symbols; in either case the total is at most 9");
    }

    private static CaseResult auxiliaryLemmaA2() {
        log.info("  A2: Oriented 7-cycle has either a qualifying 2-move or a (4,3)-sequence → total=9");
        requireNoOrientedCycleResiduals("A2",
                OddLengthOrientedCycles.search(4 / (float) 3, 7));
        return new CaseResult("A2: oriented 7-cycle → 2-move or (4,3)-seq",
                CaseStatus.VERIFIED, "Certificate replay verifies every surviving candidate; the direct sequence fixes 7 symbols, so the total is at most 9");
    }

    private static CaseResult auxiliaryLemmaA3_0() {
        log.info("  A3.0: Classify surviving oriented 9-cycle candidates without "
                + "a (5,4)-sequence");
        val result = OddLengthOrientedCycles.search(5 / (float) 4, 9);

        if (result.residualClasses().size() != 1) {
            throw new IllegalStateException("A3.0 expected exactly one residual oriented "
                    + "9-cycle candidate orbit, found " + result.residualClasses().size());
        }

        val residual = result.residualClasses().iterator().next();
        val expected = Oriented9CycleExtensions.exceptionalCandidate();
        if (!residual.twistedBraceletKey().equals(expected.twistedBraceletKey())) {
            throw new IllegalStateException("A3.0 residual is not the expected exceptional "
                    + "9-cycle candidate orbit: residual=" + residual.twistedBraceletKey()
                    + ", expected=" + expected.twistedBraceletKey());
        }

        val canonical = residual.canonicalRepresentative();
        log.info("    A3.0 unique exceptional candidate orbit verified: omega={}, beta={}",
                canonical.getOmega(), canonical.getBeta());
        return new CaseResult("A3.0: unique exceptional oriented 9-cycle",
                CaseStatus.VERIFIED,
                "Independent target-9 search leaves exactly one residual candidate orbit, Gamma_9");
    }

    private static CaseResult auxiliaryLemmaA3() {
        // A3: If there is an oriented 9-cycle.
        // There is only one exceptional oriented 9-cycle witness, not admitting a
        // (5,4)-sequence.
        // All others either admit a 2-move fixing at least one symbol or a (5,4)-sequence.
        // The priority routing assigns every type with an oriented companion to
        // another first-step branch: route T for an oriented 3-cycle, an earlier
        // selected odd cycle in route O, or route E9 for an oriented even-length
        // companion.  Thus the selected-9 extension receives only unoriented
        // companions.  It finds a sorting sequence of length at most nine and full
        // 3-norm increase for every realizable exceptional cyclic-target pair.
        log.info("  A3: Exceptional oriented 9-cycle with unoriented companions: "
                + "[9-O,6-U,2-U], [9-O,5-U,3-U], [9-O,4-U,4-U], "
                + "[9-O,2-U,2-U,2-U,2-U]");
        Oriented9CycleExtensions.main(null);
        return new CaseResult("A3: exceptional 9-cycle with all valid unoriented companion partitions",
                CaseStatus.VERIFIED, "Oriented9CycleExtensions");
    }

    private static CaseResult auxiliaryLemmaA4() {
        log.info("  A4: Oriented 11-cycle has either a qualifying 2-move or a (6,5)-sequence → total=9");
        requireNoOrientedCycleResiduals("A4",
                OddLengthOrientedCycles.search(6 / (float) 5, 11));
        return new CaseResult("A4: oriented 11-cycle → 2-move or (6,5)-seq",
                CaseStatus.VERIFIED, "Certificate replay verifies every surviving candidate; the direct sequence fixes 11 symbols, so the total is at most 9");
    }

    private static CaseResult auxiliaryLemmaA5() {
        log.info("  A5: Oriented 13-cycle has either a qualifying 2-move or a (7,6)-sequence → total=9");
        requireNoOrientedCycleResiduals("A5",
                OddLengthOrientedCycles.search(7 / (float) 6, 13));
        return new CaseResult("A5: oriented 13-cycle → 2-move or (7,6)-seq",
                CaseStatus.VERIFIED, "Certificate replay verifies every surviving candidate; the direct sequence fixes 13 symbols, so the total is at most 9");
    }

    private static CaseResult auxiliaryLemmaA6() {
        log.info("  A6: No valid partition of 17 has a 15-cycle [parity: [15,2] has 1 even part]");
        return new CaseResult("A6: no oriented 15-cycle in A_17",
                CaseStatus.VERIFIED,
                "Parity: [15,2] has 1 even part (odd count), violates A_17 constraint");
    }

    private static CaseResult auxiliaryLemmaA7() {
        log.info("  A7: Oriented 17-cycle has either a qualifying 2-move or a (9,8)-sequence → total=9");
        requireNoOrientedCycleResiduals("A7",
                OddLengthOrientedCycles.search(9 / (float) 8, 17));
        return new CaseResult("A7: oriented 17-cycle → 2-move or (9,8)-seq",
                CaseStatus.VERIFIED, "Certificate replay verifies every surviving candidate; the direct sequence fixes all 17 symbols, so the total is 9");
    }

    private static void requireNoOrientedCycleResiduals(
            final String lemma,
            final OddLengthOrientedCycles.SearchResult result
    ) {
        if (!result.residualClasses().isEmpty()) {
            throw new IllegalStateException(lemma + " left "
                    + result.residualClasses().size()
                    + " unresolved candidate orbit(s)");
        }
    }

    private static List<CaseResult> auxiliaryUnorientedPartitionLemmas() {
        val results = new ArrayList<CaseResult>();
        results.add(auxiliaryLemmaP1());
        results.add(auxiliaryLemmaP2());
        return results;
    }

    private static CaseResult auxiliaryLemmaP1() {
        log.info("  P1: Partitions impossible with only unoriented cycles ({} cases)",
                IMPOSSIBLE_PARTITIONS.length);
        for (val partition : IMPOSSIBLE_PARTITIONS) {
            val generated = UnorientedCycles.countGenerated(partition);
            if (generated != 0) {
                throw new IllegalStateException("Expected no all-unoriented realizable extended-toric classes for "
                        + Arrays.toString(partition) + ", but generated " + generated);
            }
            log.info("    {} — verified 0 all-unoriented realizable extended-toric classes",
                    Arrays.toString(partition));
        }
        return new CaseResult(
                "P1: " + IMPOSSIBLE_PARTITIONS.length + " impossible unoriented partitions",
                CaseStatus.VERIFIED,
                "The all-unoriented realizable extended-toric-class count is zero");
    }

    private static CaseResult auxiliaryLemmaP2() {
        val count = SEQ_9_8_PARTITIONS.length + SEQ_9_7_PARTITIONS.length + SEQ_9_6_PARTITIONS.length;
        log.info("  P2: Unoriented partitions verified by extended-toric-class enumeration + certificate replay ({} cases)", count);

        log.info("    (9,8)-sequence partitions:");
        for (val partition : SEQ_9_8_PARTITIONS) {
            log.info("      {}", Arrays.toString(partition));
        }
        UnorientedCycles.processPartitions(SEQ_9_8_PARTITIONS, 8, 9);

        log.info("    (9,7)-sequence partitions:");
        for (val partition : SEQ_9_7_PARTITIONS) {
            log.info("      {}", Arrays.toString(partition));
        }
        UnorientedCycles.processPartitions(SEQ_9_7_PARTITIONS, 7, 9);

        log.info("    (9,6)-sequence partitions:");
        for (val partition : SEQ_9_6_PARTITIONS) {
            log.info("      {}", Arrays.toString(partition));
        }
        UnorientedCycles.processPartitions(SEQ_9_6_PARTITIONS, 6, 9);

        return new CaseResult(
                "P2: " + count + " unoriented partitions",
                CaseStatus.VERIFIED,
                "UnorientedCycles (extended-toric classes + certificate replay)");
    }

    private static void applyLemmaForks(final AnnotatedCase annotatedCase,
                                        final int lemmaSize,
                                        final int spentForkMoves,
                                        final RootExpansionStats stats,
                                        final Set<String> exhaustiveCheckedSuccessors,
                                        final Set<String> resolvedAnnotatedSuccessors,
                                        final String indent) {
        if (spentForkMoves == 0) {
            stats.firstOddLemmaApplications++;
        }

        val lemmaIndex = lemmaIndexForCycleLength(lemmaSize);
        val sequenceResolution = sequenceForkResolution(annotatedCase, lemmaSize, spentForkMoves);
        val successors = lemmaSuccessors(annotatedCase, lemmaSize);

        log.info("{}{} -> apply A{}", indent, formatAnnotatedCase(annotatedCase), lemmaIndex);
        if (sequenceResolution.totalMoves() > 9) {
            throw new IllegalStateException(
                    "Support-clearing branch exceeds nine moves for "
                            + formatAnnotatedCase(annotatedCase)
                            + " [" + sequenceResolution.summary() + "]");
        } else if (lemmaSize == 9) {
            log.info("{}  [nonexceptional sequence] resolved by A3 [{}]", indent,
                    sequenceResolution.summary());
            log.info("{}  [exceptional class] resolved by the A3 extension verification", indent);
        } else {
            log.info("{}  [sequence] resolved by A{} [{}]", indent, lemmaIndex, sequenceResolution.summary());
        }
        log.info("{}  [2-move successors] {} case(s)", indent, successors.size());

        for (val successor : successors) {
            log.info("{}    successor {}: canonicalAnnotations={}", indent,
                    Arrays.toString(successor.fullPartition()),
                    successor.annotatedSuccessors().size());
            log.debug("{}      annotations={}", indent,
                    formatAnnotatedCases(successor.annotatedSuccessors()));
            processSuccessorPartition(successor, spentForkMoves + 1, stats,
                    exhaustiveCheckedSuccessors, resolvedAnnotatedSuccessors, indent + "      ");
        }
    }

    /**
     * Applies the strengthened even-pair join used by this proof.  Proposition 17
     * of Silva et al. guarantees a suitable 2-move; the stronger construction used
     * here chooses one whose cycle-type effect is (x,y) -> (x+y-1,1).
     */
    private static void applyEvenPairJoin(final AnnotatedCase annotatedCase,
                                          final int spentForkMoves,
                                          final RootExpansionStats stats,
                                          final Set<String> exhaustiveCheckedSuccessors,
                                          final Set<String> resolvedAnnotatedSuccessors,
                                          final String indent) {
        val successor = evenPairJoinSuccessor(annotatedCase);
        if (spentForkMoves == 0) {
            stats.firstEvenPairJoins++;
        }

        log.info("{}{} -> apply strengthened even-pair join", indent,
                formatAnnotatedCase(annotatedCase));
        log.info("{}  [even-pair successor] {}: canonicalAnnotations={}", indent,
                Arrays.toString(successor.fullPartition()), successor.annotatedSuccessors().size());
        log.debug("{}    annotations={}", indent,
                formatAnnotatedCases(successor.annotatedSuccessors()));
        processSuccessorPartition(successor, spentForkMoves + 1, stats,
                exhaustiveCheckedSuccessors, resolvedAnnotatedSuccessors, indent + "      ");
    }

    /**
     * Builds the successor of the strengthened move joining the first two
     * even-length cycles into a cycle of length x+y-1 and one fixed point. Its
     * orientation annotations are generated afresh: orientation is not inherited
     * across a move.
     */
    static SuccessorExpansion evenPairJoinSuccessor(final AnnotatedCase annotatedCase) {
        val partition = annotatedCase.partition();
        int firstEvenIdx = -1;
        int secondEvenIdx = -1;
        for (var i = 0; i < partition.length; i++) {
            if (partition[i] % 2 != 0) {
                continue;
            }
            if (firstEvenIdx == -1) {
                firstEvenIdx = i;
            } else {
                secondEvenIdx = i;
                break;
            }
        }

        if (secondEvenIdx == -1) {
            throw new IllegalStateException("No even-length pair found in " + formatAnnotatedCase(annotatedCase)
                    + " — expected at least two even parts when no oriented odd-length cycle is available");
        }

        val joinedLength = partition[firstEvenIdx] + partition[secondEvenIdx] - 1;
        val reduced = new ArrayList<Integer>();
        for (var i = 0; i < partition.length; i++) {
            if (i == firstEvenIdx || i == secondEvenIdx) {
                continue;
            }
            reduced.add(partition[i]);
        }

        // A move changes the current cyclic order and can therefore change the
        // orientation of cycles disjoint from its support.  Build only the successor
        // cycle type here; all of its admissible annotations are enumerated below.
        val successorPartition = new int[reduced.size() + 2]; // +1 for joined cycle, +1 for fixed point
        for (var i = 0; i < reduced.size(); i++) {
            successorPartition[i] = reduced.get(i);
        }
        successorPartition[reduced.size()] = joinedLength;
        successorPartition[reduced.size() + 1] = 1;
        Arrays.sort(successorPartition);
        // Reverse to descending order
        for (int i = 0, j = successorPartition.length - 1; i < j; i++, j--) {
            val tmp = successorPartition[i];
            successorPartition[i] = successorPartition[j];
            successorPartition[j] = tmp;
        }

        return new SuccessorExpansion(successorPartition, enumerateAnnotatedCases(successorPartition));
    }

    private static void processSuccessorPartition(final SuccessorExpansion successor,
                                                  final int spentForkMoves,
                                                  final RootExpansionStats stats,
                                                  final Set<String> exhaustiveCheckedSuccessors,
                                                  final Set<String> resolvedAnnotatedSuccessors,
                                                  final String indent) {
        if (spentForkMoves != 1) {
            throw new IllegalArgumentException(
                    "The coverage proof sends successors to the tail after exactly one move, found "
                            + spentForkMoves);
        }

        val successorPartition = successor.fullPartition();
        val partitionKey = Arrays.toString(successorPartition);
        val fixedPointCount = countOccurrences(successorPartition, 1);
        if (fixedPointCount >= 2) {
            val resolution = fixedPointsResolution(successorPartition, spentForkMoves);
            if (resolution.totalMoves() > 9) {
                throw new IllegalStateException(
                        "A successor with at least two fixed points exceeds nine moves: "
                                + partitionKey + " [" + resolution.summary() + "]");
            }
            val unresolvedAnnotations = successor.annotatedSuccessors().stream()
                    .filter(annotatedSuccessor -> !resolvedAnnotatedSuccessors.contains(
                            annotatedSuccessorKey(successorPartition, annotatedSuccessor, spentForkMoves)))
                    .toList();
            if (unresolvedAnnotations.isEmpty()) {
                log.info("{}successor {}: all {} annotations previously resolved",
                        indent, partitionKey, successor.annotatedSuccessors().size());
                return;
            }
            log.info("{}direct: at least two fixed points [{}]", indent, resolution.summary());
            for (val annotatedSuccessor : unresolvedAnnotations) {
                resolvedAnnotatedSuccessors.add(
                        annotatedSuccessorKey(successorPartition, annotatedSuccessor, spentForkMoves));
            }
            return;
        }

        if (fixedPointCount == 0) {
            stats.deferredAfterFirstExpansion++;
            log.info("{}successor {}: deferred after one expansion", indent, partitionKey);
            return;
        }

        val pendingAnnotations = successor.annotatedSuccessors().stream()
                .filter(annotatedSuccessor -> !resolvedAnnotatedSuccessors.contains(
                        annotatedSuccessorKey(successorPartition, annotatedSuccessor, spentForkMoves)))
                .toList();
        log.info("{}successor {}: canonicalAnnotations={}, pending={}, previouslyResolved={}",
                indent,
                partitionKey,
                successor.annotatedSuccessors().size(),
                pendingAnnotations.size(),
                successor.annotatedSuccessors().size() - pendingAnnotations.size());
        for (val annotatedSuccessor : pendingAnnotations) {
            val successorKey = annotatedSuccessorKey(
                    successorPartition, annotatedSuccessor, spentForkMoves);
            log.info("{}  case {}", indent, formatAnnotatedCase(annotatedSuccessor));
            resolveAnnotatedSuccessorExhaustively(successorPartition, annotatedSuccessor,
                    spentForkMoves, stats, exhaustiveCheckedSuccessors, indent + "    ");
            resolvedAnnotatedSuccessors.add(successorKey);
        }
    }

    private static void resolveAnnotatedSuccessorExhaustively(final int[] successorPartition,
                                                              final AnnotatedCase annotatedSuccessor,
                                                              final int spentForkMoves,
                                                              final RootExpansionStats stats,
                                                              final Set<String> exhaustiveCheckedSuccessors,
                                                              final String indent) {
        if (containsOrientedThree(annotatedSuccessor)) {
            val resolution = directA0Resolution(annotatedSuccessor, spentForkMoves);
            if (resolution.totalMoves() <= 9) {
                log.info("{}direct: A0 [{}]", indent, resolution.summary());
                return;
            }
        }

        val maxMoves = remainingMoveBudget(spentForkMoves);
        log.info("{}exhaustive successor check (maxMoves={})", indent, maxMoves);

        val key = Arrays.toString(successorPartition) + "|" + formatAnnotatedCase(annotatedSuccessor) + "|" + maxMoves;
        if (exhaustiveCheckedSuccessors.add(key)) {
            val processingStats = RemainingSuccessorsSolver.processAnnotatedSuccessor(
                    successorPartition, annotatedSuccessor.partition(),
                    annotatedSuccessor.orientedByPart(), null, indent + "  ", maxMoves);
            stats.recordExhaustiveSearch(processingStats);
            stats.exhaustiveSuccessorChecks++;
        }

    }

    private static int remainingMoveBudget(final int spentForkMoves) {
        val remaining = 9 - spentForkMoves;
        if (remaining <= 0) {
            throw new IllegalArgumentException("No remaining move budget after spending " + spentForkMoves + " moves");
        }
        return remaining;
    }

    private static Resolution directA0Resolution(final AnnotatedCase annotatedCase,
                                                 final int spentForkMoves) {
        return resolutionByFixingSymbols(spentForkMoves + 1, droppedFixedPoints(annotatedCase.partition()) + 3);
    }

    private static Resolution sequenceForkResolution(final AnnotatedCase annotatedCase,
                                                     final int lemmaSize,
                                                     final int spentForkMoves) {
        return resolutionByFixingSymbols(spentForkMoves + sequenceForkMoveCost(lemmaSize),
                droppedFixedPoints(annotatedCase.partition()) + lemmaSize);
    }

    private static Resolution fixedPointsResolution(final int[] partition,
                                                    final int spentForkMoves) {
        return resolutionByFixingSymbols(spentForkMoves, countOccurrences(partition, 1));
    }

    private static Resolution resolutionByFixingSymbols(final long spentMoves,
                                                        final long fixedSymbols) {
        val remainingSymbols = 17 - fixedSymbols;
        if (remainingSymbols < 0) {
            throw new IllegalArgumentException("Too many fixed symbols: " + fixedSymbols);
        }

        final long tailBound;
        final String tailLabel;
        if (remainingSymbols <= 1) {
            tailBound = 0;
            tailLabel = "tail=0";
        } else {
            val tailN = (int) remainingSymbols - 1;
            tailBound = d(tailN);
            tailLabel = "TD(" + tailN + ")=" + tailBound;
        }

        return new Resolution(spentMoves, tailBound,
                spentMoves + " + " + tailLabel + " = " + (spentMoves + tailBound));
    }

    private static long droppedFixedPoints(final int[] partition) {
        long sum = 0;
        for (val part : partition) {
            sum += part;
        }
        if (sum > 17) {
            throw new IllegalArgumentException("Partition sum exceeds 17: " + Arrays.toString(partition));
        }
        return 17 - sum;
    }

    private static int sequenceForkMoveCost(final int lemmaSize) {
        return switch (lemmaSize) {
            case 5 -> 3;
            case 7 -> 4;
            case 9 -> 5;
            case 11 -> 6;
            case 13 -> 7;
            case 17 -> 9;
            default -> throw new IllegalArgumentException("No sequence-fork move cost for cycle length " + lemmaSize);
        };
    }

    static List<AnnotatedCase> enumerateAnnotatedCases(final int[] partition) {
        val nonFixedPartition = Arrays.stream(partition)
                .filter(part -> part != 1)
                .toArray();
        val annotatedCases = new ArrayList<AnnotatedCase>();
        enumerateAnnotatedCases(nonFixedPartition, new boolean[nonFixedPartition.length], 0, annotatedCases);
        return annotatedCases;
    }

    private static void enumerateAnnotatedCases(final int[] partition,
                                                final boolean[] orientedByPart,
                                                final int index,
                                                final List<AnnotatedCase> annotatedCases) {
        if (index == partition.length) {
            annotatedCases.add(new AnnotatedCase(partition, orientedByPart));
            return;
        }

        if (partition[index] <= 2) {
            orientedByPart[index] = false;
            enumerateAnnotatedCases(partition, orientedByPart, index + 1, annotatedCases);
            return;
        }

        // Equal-length cycles are indistinguishable in an annotated cycle type.
        // Require their orientations to appear in U-before-O order, matching the
        // paper's canonical ordering and eliminating permutations of one multiset.
        final boolean mayBeUnoriented = index == 0
                || partition[index] != partition[index - 1]
                || !orientedByPart[index - 1];
        if (mayBeUnoriented) {
            orientedByPart[index] = false;
            enumerateAnnotatedCases(partition, orientedByPart, index + 1, annotatedCases);
        }

        orientedByPart[index] = true;
        enumerateAnnotatedCases(partition, orientedByPart, index + 1, annotatedCases);
    }

    private static boolean allUnoriented(final AnnotatedCase annotatedCase) {
        for (val oriented : annotatedCase.orientedByPart()) {
            if (oriented) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsOrientedThree(final AnnotatedCase annotatedCase) {
        for (var i = 0; i < annotatedCase.partition().length; i++) {
            if (annotatedCase.partition()[i] == 3 && annotatedCase.orientedByPart()[i]) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsOrientedEvenCycle(final AnnotatedCase annotatedCase) {
        for (var i = 0; i < annotatedCase.partition().length; i++) {
            if (annotatedCase.partition()[i] % 2 == 0 && annotatedCase.orientedByPart()[i]) {
                return true;
            }
        }
        return false;
    }

    private static int nextOddLengthOrientedLemma(final AnnotatedCase annotatedCase) {
        var best = Integer.MAX_VALUE;
        for (var i = 0; i < annotatedCase.partition().length; i++) {
            val part = annotatedCase.partition()[i];
            if (!annotatedCase.orientedByPart()[i] || part % 2 == 0 || part <= 3) {
                continue;
            }
            if (part == 5 || part == 7 || part == 9 || part == 11 || part == 13 || part == 17) {
                best = Math.min(best, part);
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    /**
     * Applies the five-way priority routing of the 194 fixed-point-free annotated
     * root types.  The selected-9/even route is determined at the annotated-type
     * level, before the local 9-cycle is known to be exceptional or nonexceptional.
     */
    static InitialTypeRoute initialTypeRoute(final AnnotatedCase annotatedCase) {
        if (droppedFixedPoints(annotatedCase.partition()) != 0
                || Arrays.stream(annotatedCase.partition()).anyMatch(part -> part <= 1)) {
            throw new IllegalArgumentException(
                    "Initial type must be fixed-point-free on 17 symbols: "
                            + formatAnnotatedCase(annotatedCase));
        }
        if (allUnoriented(annotatedCase)) {
            return InitialTypeRoute.ALL_UNORIENTED;
        }
        if (containsOrientedThree(annotatedCase)) {
            return InitialTypeRoute.ORIENTED_THREE;
        }

        val selectedOddLength = nextOddLengthOrientedLemma(annotatedCase);
        if (selectedOddLength == 9 && containsOrientedEvenCycle(annotatedCase)) {
            return InitialTypeRoute.SELECTED_NINE_WITH_ORIENTED_EVEN;
        }
        if (selectedOddLength == -1) {
            return InitialTypeRoute.REMAINING_EVEN_PAIR;
        }
        return InitialTypeRoute.ORIENTED_ODD_REDUCTION;
    }

    static boolean usesEvenPairReduction(final InitialTypeRoute route) {
        return route == InitialTypeRoute.SELECTED_NINE_WITH_ORIENTED_EVEN
                || route == InitialTypeRoute.REMAINING_EVEN_PAIR;
    }

    static InitialTypeRoute initialTypeRoute(
            final MulticyclePermutation omega,
            final Cycle beta) {
        if (beta.size() != 17 || omega.getNumberOfSymbols() != beta.size()
                || omega.stream().anyMatch(cycle -> cycle.size() <= 1)) {
            throw new IllegalArgumentException(
                    "Initial pair must have a fixed-point-free algebraic permutation on 17 symbols");
        }

        val cycles = omega.stream().toList();
        val partition = cycles.stream().mapToInt(Cycle::size).toArray();
        val orientedByPart = new boolean[cycles.size()];
        for (var i = 0; i < cycles.size(); i++) {
            val cycle = cycles.get(i);
            orientedByPart[i] = cycle.size() > 2
                    && CommonOperations.isOriented(beta, cycle);
        }
        return initialTypeRoute(new AnnotatedCase(partition, orientedByPart));
    }

    private static int lemmaIndexForCycleLength(final int cycleLength) {
        return switch (cycleLength) {
            case 5 -> 1;
            case 7 -> 2;
            case 9 -> 3;
            case 11 -> 4;
            case 13 -> 5;
            case 17 -> 7;
            default -> throw new IllegalArgumentException("No lemma index for cycle length " + cycleLength);
        };
    }

    /**
     * Encodes the qualifying-2-move branch for an oriented odd-length cycle.
     * Replacements whose total size is cycleLength - 2 are the subcases fixing 2 symbols;
     * replacements whose total size is cycleLength - 1 are the subcases fixing 1 symbol.
     * The 3-fixed-symbol case belongs only to the oriented 3-cycle lemma A0 and does
     * not appear here.
     */
    static List<SuccessorExpansion> lemmaSuccessors(final AnnotatedCase annotatedCase,
                                                    final int cycleLength) {
        val partition = annotatedCase.partition();
        val replacements = switch (cycleLength) {
            case 5 -> new int[][]{{3}};
            case 7 -> new int[][]{{5}, {3, 3}};
            case 9 -> new int[][]{{7}, {5, 3}};
            case 11 -> new int[][]{{9}, {7, 3}, {5, 5}};
            case 13 -> new int[][]{{11}, {9, 3}, {7, 5}};
            case 17 -> new int[][]{{15}, {13, 3}, {11, 5}, {9, 7}};
            default -> throw new IllegalArgumentException("No successor rule for cycle length " + cycleLength);
        };

        val successors = new ArrayList<SuccessorExpansion>();
        val seen = new HashSet<String>();
        for (val replacement : replacements) {
            val reduced = replacePart(partition, cycleLength, replacement);
            val successor = expandWithFixedPoints(reduced,
                    (int) (droppedFixedPoints(annotatedCase.partition()) + cycleLength - Arrays.stream(replacement).sum()));
            val key = Arrays.toString(successor);
            if (seen.add(key)) {
                successors.add(new SuccessorExpansion(successor, enumerateAnnotatedCases(successor)));
            }
        }

        successors.sort((left, right) -> comparePartitions(left.fullPartition(), right.fullPartition()));
        return successors;
    }

    private static String formatAnnotatedCase(final AnnotatedCase annotatedCase) {
        val formatted = new StringBuilder("[");
        for (var i = 0; i < annotatedCase.partition().length; i++) {
            if (i > 0) {
                formatted.append(", ");
            }
            formatted.append(annotatedCase.partition()[i])
                    .append('-')
                    .append(annotatedCase.orientedByPart()[i] ? 'O' : 'U');
        }
        return formatted.append(']').toString();
    }

    private static String formatAnnotatedCases(final List<AnnotatedCase> annotatedCases) {
        val formatted = new StringBuilder("[");
        for (var i = 0; i < annotatedCases.size(); i++) {
            if (i > 0) {
                formatted.append(", ");
            }
            formatted.append(formatAnnotatedCase(annotatedCases.get(i)));
        }
        return formatted.append(']').toString();
    }

    private static String annotatedSuccessorKey(final int[] successorPartition,
                                                final AnnotatedCase annotatedSuccessor,
                                                final int spentForkMoves) {
        return Arrays.toString(successorPartition)
                + "|" + formatAnnotatedCase(annotatedSuccessor)
                + "|maxMoves=" + remainingMoveBudget(spentForkMoves);
    }

    record AnnotatedCase(int[] partition, boolean[] orientedByPart) {
        AnnotatedCase {
            partition = partition.clone();
            orientedByPart = orientedByPart.clone();
        }
    }

    record SuccessorExpansion(int[] fullPartition, List<AnnotatedCase> annotatedSuccessors) {
        SuccessorExpansion {
            fullPartition = fullPartition.clone();
            annotatedSuccessors = List.copyOf(annotatedSuccessors);
        }
    }

    private record Resolution(long spentMoves, long tailBound, String summary) {
        private long totalMoves() {
            return spentMoves + tailBound;
        }
    }

    static final class RootExpansionStats {
        private int rootPartitions;
        private int annotatedRoots;
        private int rootDirectByPartitionLemma;
        private int rootDirectByA0;
        private int firstOddLemmaApplications;
        private int firstEvenPairJoins;
        private int exhaustiveSuccessorChecks;
        private int deferredAfterFirstExpansion;
        private long generatedConfigurations;
        private long solvedConfigurations;

        private void recordExhaustiveSearch(
                final RemainingSuccessorsSolver.ProcessingStats processingStats) {
            generatedConfigurations += processingStats.generatedConfigurations();
            solvedConfigurations += processingStats.solvedConfigurations();
        }
    }

    record RunMetadata(Instant startedUtc,
                       String runId,
                       String revision,
                       String machine,
                       int logicalProcessors) {
    }

    // ==================== Completeness verification ====================

    /**
     * Verifies that the union of the partition buckets exactly covers
     * all valid partitions of 17 (parts >= 2, even number of even parts).
     *
     * @throws IllegalStateException if coverage is incomplete or has duplicates
     */
    static void verifyAllUnorientedPartitionCoverage() {
        val allValid = Partitions.generateValidPartitions(17);
        val validSet = allValid.stream()
                .map(Arrays::toString)
                .collect(Collectors.toSet());

        val covered = new HashSet<String>();
        Stream.of(IMPOSSIBLE_PARTITIONS, SEQ_9_8_PARTITIONS, SEQ_9_7_PARTITIONS, SEQ_9_6_PARTITIONS)
                .flatMap(Arrays::stream)
                .map(Arrays::toString)
                .forEach(p -> {
                    if (!covered.add(p)) {
                        throw new IllegalStateException("Duplicate partition: " + p);
                    }
                });

        val missing = new HashSet<>(validSet);
        missing.removeAll(covered);

        val extra = new HashSet<>(covered);
        extra.removeAll(validSet);

        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new IllegalStateException(
                    "Partition coverage gap! Missing: " + missing + ", Extra: " + extra);
        }

        log.info("All-unoriented partition-table coverage verified: {} valid partitions of 17, all accounted for.",
                allValid.size());
    }

    // ==================== Summary ====================

    private static void printSummary(final List<CaseResult> results,
                                     final long elapsedNanos) {
        log.info("");
        log.info("=== PROOF SUMMARY ===");
        val verified = results.stream().filter(r -> r.status() == CaseStatus.VERIFIED).count();
        val wip = results.stream().filter(r -> r.status() == CaseStatus.WIP).count();
        val todo = results.stream().filter(r -> r.status() == CaseStatus.TODO).count();
        log.info("Stage checks: {} verified, {} WIP, {} TODO", verified, wip, todo);
        results.forEach(result -> log.info("  [{}] {} — {}",
                result.status(), result.label(), result.notes()));

        if (wip + todo == 0) {
            log.info("ALL CASES RESOLVED. TD(16) = 9. QED.");
        } else {
            log.info("PROOF INCOMPLETE. See the non-verified stage checks above.");
        }
        log.info("Proof elapsed time: {}", formatElapsedTime(elapsedNanos));
    }

    private static void printFailureSummary(final Throwable failure,
                                            final long elapsedNanos) {
        log.error("=== PROOF FAILED ===", failure);
        log.info("Proof elapsed time: {}", formatElapsedTime(elapsedNanos));
    }

    private static String formatElapsedTime(final long elapsedNanos) {
        val totalMillis = Math.max(0, elapsedNanos / 1_000_000);
        val hours = totalMillis / 3_600_000;
        val minutes = totalMillis / 60_000 % 60;
        val seconds = totalMillis / 1_000 % 60;
        val millis = totalMillis % 1_000;
        return String.format(Locale.ROOT, "%dh %02dm %02d.%03ds", hours, minutes, seconds, millis);
    }

    private static int[] expandWithFixedPoints(final int[] partition, final int fixedPointCount) {
        val expanded = new int[partition.length + fixedPointCount];
        System.arraycopy(partition, 0, expanded, 0, partition.length);
        Arrays.fill(expanded, partition.length, expanded.length, 1);
        return expanded;
    }

    private static long countOccurrences(final int[] partition, final int part) {
        return Arrays.stream(partition).filter(value -> value == part).count();
    }

    private static int[] replacePart(final int[] partition,
                                     final int replacedPart,
                                     final int[] replacement) {
        val transformed = new ArrayList<Integer>();
        var removed = false;

        for (val part : partition) {
            if (!removed && part == replacedPart) {
                removed = true;
                continue;
            }
            transformed.add(part);
        }

        if (!removed) {
            throw new IllegalArgumentException("Partition does not contain part " + replacedPart + ": "
                    + Arrays.toString(partition));
        }

        for (val part : replacement) {
            transformed.add(part);
        }

        transformed.sort(Comparator.reverseOrder());
        return transformed.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int comparePartitions(final int[] left, final int[] right) {
        for (var i = 0; i < Math.min(left.length, right.length); i++) {
            if (left[i] != right[i]) {
                return Integer.compare(right[i], left[i]);
            }
        }
        return Integer.compare(left.length, right.length);
    }
}
