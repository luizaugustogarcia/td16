package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.base.SortingSearch;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * <p>For each partition [k1, k2, ..., km] of 17, this class enumerates one
 * representative of every extended-toric class of all-unoriented encoding words
 * where:
 * <ul>
 *   <li>All cycle sizes match the partition</li>
 *   <li>All cycles are unoriented with respect to beta</li>
 *   <li>The reconstructed target {@code omega * beta} is a single cycle of length 17</li>
 * </ul>
 *
 * <p>The twisted-bracelet generator represents each cycle by a bead
 * color, with cyclic ranks attached to oriented-cycle beads. It rejects a
 * branch as soon as the partial target closes a proper cycle and emits one
 * representative of each realizable extended-toric class.
 *
 * <p>After enumeration, each pair representative is searched for the required sorting
 * sequence by certificate lookup and independent replay.
 */
@Slf4j
public class UnorientedCycles {

    public static void processPartitions(final int[][] partitions, final int target, final int maxMoves) {
        for (val partition : partitions) {
            processPartition(partition, target, maxMoves);
        }
    }

    private static void processPartition(final int[] partition,
                                         final int target,
                                         final int maxMoves) {
        log.info("unoriented partition {}: status=started, requiredIncrease={}, maxMoves={}",
                Arrays.toString(partition), target, maxMoves);

        if (hasForcedTargetFixedPoint(partition)) {
            log.info("unoriented partition {}: status=verified, extendedToricClasses=0, residual=0",
                    Arrays.toString(partition));
            return;
        }

        val solved = new AtomicInteger();
        DirectTwistedBraceletGenerator.generateRepresentativeBatchesConcurrently(
                partition, new boolean[partition.length], () -> { }, pairs -> {
                    val movesByPair = SortingSearch.searchForSorting(pairs, maxMoves);
                    for (var index = 0; index < pairs.length; index++) {
                        if (movesByPair.get(index).isEmpty()) {
                            throw new IllegalStateException(unsolvedMessage(
                                    partition, target, maxMoves, pairs[index]));
                        }
                    }
                    solved.addAndGet(pairs.length);
                });

        log.info("unoriented partition {}: status=verified, extendedToricClasses={}, residual={}",
                Arrays.toString(partition), solved.get(), 0);
    }

    private static String unsolvedMessage(final int[] partition,
                                          final int target,
                                          final int maxMoves,
                                          final CyclicTargetPair pair) {
        return "Unsolved cyclic-target pair for partition " + Arrays.toString(partition)
                + ", target=" + target
                + ", maxMoves=" + maxMoves
                + ", classKey=" + pair.extendedToricClassKey()
                + ", omega=" + pair.getOmega()
                + ", beta=" + pair.getBeta();
    }

    /**
     * Exhausts the realizable representative generator without invoking the sorting
     * search.  The proof driver uses this to verify, rather than merely assert,
     * that an all-unoriented partition has no realizable representative.
     */
    public static long countGenerated(final int[] partition) {
        if (hasForcedTargetFixedPoint(partition)) {
            return 0;
        }
        return DirectTwistedBraceletGenerator.countRepresentatives(
                partition, new boolean[partition.length]);
    }

    static boolean hasForcedTargetFixedPoint(final int[] counts) {
        val n = Arrays.stream(counts).sum();
        val max = Arrays.stream(counts).max().orElse(0);
        return n > 1 && max > n - max;
    }
}
