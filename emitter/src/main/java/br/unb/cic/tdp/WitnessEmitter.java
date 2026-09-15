package br.unb.cic.tdp;

import br.unb.cic.tdp.base.AsyncCertificateWriter;
import br.unb.cic.tdp.base.GPUSortingSearch;
import br.unb.cic.tdp.base.GpuDeviceLayout;
import br.unb.cic.tdp.base.WitnessSearch;

import java.nio.file.Path;
import java.util.List;

/**
 * Staged native witness producer.  Its durable output is only the RocksDB
 * certificate format; native misses are retained just long enough to drive
 * structural extension and avoidance-preimage generation.
 */
public final class WitnessEmitter {
    private static final int[][] P2_RATE_EIGHT = {
            {8, 7, 2}, {7, 4, 2, 2, 2}, {7, 6, 4}, {6, 5, 2, 2, 2},
            {6, 4, 3, 2, 2}, {6, 6, 5}, {5, 4, 4, 2, 2},
            {5, 2, 2, 2, 2, 2, 2}, {4, 4, 4, 3, 2}, {4, 3, 2, 2, 2, 2, 2}
    };
    private static final int[][] P2_RATE_SEVEN = {
            {8, 6, 3}, {8, 5, 4}, {7, 7, 3}, {7, 3, 3, 2, 2}, {7, 5, 5},
            {6, 3, 3, 3, 2}, {5, 5, 3, 2, 2}, {5, 4, 3, 3, 2},
            {4, 4, 3, 3, 3}, {3, 3, 3, 2, 2, 2, 2}
    };
    private static final int[][] P2_RATE_SIX = {{5, 3, 3, 3, 3}};

    private WitnessEmitter() {
    }

    public static void main(final String[] args) {
        try {
            initializeGpu();
            final Path database = Path.of(System.getProperty("tdp.certificateDb", "certificates"));
            final long flushSeconds = Long.parseLong(
                    System.getProperty("tdp.certificateFlushSeconds", "5"));
            try (final var writer = new AsyncCertificateWriter(database, flushSeconds)) {
                WitnessSearch.installWriter(writer);
                emitUnorientedP2();
                emitOrientedOddCycleCorpus();
                Oriented9CycleExtensions.main(args);
                emitOneFixedPointSuccessorCorpus();
                MinusTwoMoveExtender.emitAvoidancePreimages(
                        RemainingSuccessorsSolver.getAndClearResidualObstructions());
                writer.throwIfFailed();
            }
        } finally {
            DirectTwistedBraceletGenerator.shutdownExecutor();
        }
    }

    private static void initializeGpu() {
        final var devices = GpuDeviceLayout.parseDevices(
                System.getProperty("tdp.gpuDevices", "0"));
        final int slots = Integer.parseInt(System.getProperty("tdp.gpuSlots", "7"));
        final long budgetMiB = Long.parseLong(System.getProperty("tdp.gpuBudgetMB", "1024"));
        GPUSortingSearch.init(devices, slots, Math.multiplyExact(budgetMiB, 1024L * 1024L));
    }

    private static void emitUnorientedP2() {
        UnorientedCycles.processPartitions(P2_RATE_EIGHT, 8, 9);
        UnorientedCycles.processPartitions(P2_RATE_SEVEN, 7, 9);
        UnorientedCycles.processPartitions(P2_RATE_SIX, 6, 9);
    }

    private static void emitOrientedOddCycleCorpus() {
        OddLengthOrientedCycles.search(3 / (float) 2, 5);
        OddLengthOrientedCycles.search(4 / (float) 3, 7);
        OddLengthOrientedCycles.search(5 / (float) 4, 9);
        OddLengthOrientedCycles.search(6 / (float) 5, 11);
        OddLengthOrientedCycles.search(7 / (float) 6, 13);
        OddLengthOrientedCycles.search(9 / (float) 8, 17);
    }

    /**
     * The predecessor phase's native misses are accumulated only in this process.
     * Each prescribed first move leaves two nontrivial odd-length cycles beside
     * the fixed point, so all-even reduced partitions are not successor cases.
     */
    private static void emitOneFixedPointSuccessorCorpus() {
        prescribedReducedSuccessorPartitions().parallelStream()
                .forEach(reducedPartition -> {
                    final var successor = new int[reducedPartition.length + 1];
                    successor[0] = 1;
                    System.arraycopy(reducedPartition, 0, successor, 1, reducedPartition.length);
                    RemainingSuccessorsSolver.emitNativeSuccessorPartition(successor, 8);
                });
    }

    static List<int[]> prescribedReducedSuccessorPartitions() {
        return Partitions.generateValidPartitions(16).stream()
                .filter(WitnessEmitter::containsOddLengthCycle)
                .toList();
    }

    private static boolean containsOddLengthCycle(final int[] partition) {
        for (final var cycleLength : partition) {
            if (cycleLength % 2 != 0) {
                return true;
            }
        }
        return false;
    }
}
