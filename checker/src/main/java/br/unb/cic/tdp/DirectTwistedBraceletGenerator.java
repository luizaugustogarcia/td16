package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Generates one raw fixed-content word from every realizable orbit under the
 * auxiliary cycle symmetries and the twisted dihedral action.
 *
 * <p>When the auxiliary action is trivial, the recursion is the
 * fixed-content bracelet recursion of Karim--Sawada--Alamgir--Husnine.  For a
 * nontrivial auxiliary action, it is Sawada's fixed-content necklace
 * recursion, with lazy comparisons rooted only at occurrences of the least
 * annotated type.  The alphabet places an auxiliary-affected type first,
 * choosing one of minimum total mass.  A part is affected if it is oriented
 * or another part has the same annotated type.  If the first type has mass
 * {@code M} and all affected parts sum to {@code S}, then {@code M <= S}.  Only the
 * two traversal directions at those {@code M} positions can produce an
 * auxiliary image starting with the least symbol; every other spatial image
 * is larger at its first symbol.  At most {@code 2M} comparisons are needed.
 * A comparison maps an encountered source cycle to the least still-unmatched
 * target cycle of the same annotated type and chooses the unique rank offset
 * that sends its first encountered rank to zero.  This constructs the least
 * auxiliary transform without enumerating the auxiliary group.</p>
 *
 * <p>A comparison is activated when its root bead is placed and advances when
 * its next source and target positions become known.  Consequently a
 * completed survivor is emitted directly, without a completed-word
 * canonicalization pass.  This directness is not a constant-amortized-time
 * claim: snapshots and scans cost {@code O(S)}, and wrapped comparisons can
 * advance through several offsets after one placement.  Along any root-to-leaf
 * path their cursors advance at most {@code O(N*S)} times in total, where
 * {@code N} is the word length.  Auxiliary cycle assignments and source-position
 * calculations take constant time.  Including the underlying recursion and
 * semantic checks, conservative bounds are {@code O(N*S + N)} per prefix
 * candidate and {@code O(N*S + N^2)} per root-to-leaf path.  Thus, for {@code T}
 * nonempty prefix candidates visited, including rejected candidates, total
 * sequential time is {@code O((N*S + N)*T)}, excluding caller callbacks and
 * parallel-frontier copying.  No comparison states are allocated when
 * {@code S = 0}.</p>
 */
final class DirectTwistedBraceletGenerator {

    private static final int REVERSE_SMALLER = -1;
    private static final int REVERSE_EQUAL = 0;
    private static final int REVERSE_LARGER = 1;
    private static final byte COMPARISON_LARGER = 1;
    private static final int FRONTIER_TASKS_PER_WORKER = 16;
    private static final String BATCH_SIZE_PROPERTY = "tdp.twistedBraceletBatchSize";
    private static final int DEFAULT_BATCH_SIZE = 256;
    private static final String WORKER_COUNT_PROPERTY = "tdp.twistedBraceletWorkers";
    private static final int WORKER_COUNT = workerCountFromVmOption();
    private static final Runnable NO_CANCELLATION_CHECK = () -> { };
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(WORKER_COUNT,
            Thread.ofPlatform().daemon().name("twisted-bracelet-", 0).factory());

    private DirectTwistedBraceletGenerator() {
    }

    /** Shuts down the shared executor after all concurrent generation has completed. */
    static void shutdownExecutor() {
        EXECUTOR.shutdown();
    }

    static void generateRepresentatives(final int[] partition,
                                        final boolean[] orientedByPart,
                                        final Consumer<CyclicTargetPair> consumer) {
        generateRepresentatives(
                partition, orientedByPart, NO_CANCELLATION_CHECK, consumer);
    }

    static void generateRepresentatives(final int[] partition,
                                        final boolean[] orientedByPart,
                                        final Runnable cancellationCheck,
                                        final Consumer<CyclicTargetPair> consumer) {
        validate(partition, orientedByPart);
        new Generator(normalizedSpecs(partition, orientedByPart), consumer, null,
                null, cancellationCheck).generate();
    }

    /** Counts realizable representatives without decoding completed words. */
    static long countRepresentatives(final int[] partition,
                                     final boolean[] orientedByPart) {
        validate(partition, orientedByPart);
        final long[] count = {0};
        new Generator(normalizedSpecs(partition, orientedByPart), null, null,
                () -> count[0]++, NO_CANCELLATION_CHECK).generate();
        return count[0];
    }

    /** Parallel generation for a thread-safe consumer. */
    static void generateRepresentativesConcurrently(
            final int[] partition,
            final boolean[] orientedByPart,
            final Runnable cancellationCheck,
            final Consumer<CyclicTargetPair> consumer) {
        validate(partition, orientedByPart);
        final var specs = normalizedSpecs(partition, orientedByPart);
        final var failure = new AtomicReference<Throwable>();
        final Runnable branchCancellationCheck = () -> {
            if (failure.get() != null) {
                throw BranchStopped.INSTANCE;
            }
            cancellationCheck.run();
        };
        final var targetTaskCount = (int) Math.min(
                Integer.MAX_VALUE,
                (long) WORKER_COUNT * FRONTIER_TASKS_PER_WORKER);
        final var branches = buildFrontier(
                specs, consumer, branchCancellationCheck, targetTaskCount);
        if (branches.size() <= 1) {
            branches.forEach(GenerationTask::runToCompletion);
            return;
        }

        final var futures = new ArrayList<Future<?>>(branches.size());
        for (final var branch : branches) {
            futures.add(EXECUTOR.submit(() -> {
                try {
                    branch.runToCompletion();
                } catch (final BranchStopped ignored) {
                    // Another branch failed or generation was cancelled.
                } catch (final Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            }));
        }
        try {
            for (final var future : futures) {
                future.get();
            }
        } catch (final InterruptedException exception) {
            for (final var future : futures) {
                future.cancel(true);
            }
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Interrupted while generating twisted-bracelet representatives", exception);
        } catch (final java.util.concurrent.ExecutionException exception) {
            failure.compareAndSet(null, exception.getCause());
        }
        rethrow(failure.get());
    }

    /**
     * Parallel generation which delivers each worker's representatives in
     * bounded batches.  The final batch from every worker may be smaller.
     */
    static void generateRepresentativeBatchesConcurrently(
            final int[] partition,
            final boolean[] orientedByPart,
            final Runnable cancellationCheck,
            final Consumer<CyclicTargetPair[]> consumer) {
        final var batchingConsumer = new BatchingConsumer(batchSizeFromVmOption(), consumer);
        try {
            generateRepresentativesConcurrently(partition, orientedByPart,
                    cancellationCheck, batchingConsumer);
        } finally {
            batchingConsumer.flushCurrentThread();
        }
    }

    private static int batchSizeFromVmOption() {
        final var configured = System.getProperty(BATCH_SIZE_PROPERTY,
                Integer.toString(DEFAULT_BATCH_SIZE));
        try {
            final var parsed = Integer.parseInt(configured);
            if (parsed < 1) {
                throw new IllegalArgumentException(
                        BATCH_SIZE_PROPERTY + " must be a positive integer: " + configured);
            }
            return parsed;
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException(
                    BATCH_SIZE_PROPERTY + " must be a positive integer: " + configured,
                    exception);
        }
    }

    private static int workerCountFromVmOption() {
        final var configured = System.getProperty(WORKER_COUNT_PROPERTY);
        if (configured == null || configured.equals("auto")) {
            return Math.max(1, Runtime.getRuntime().availableProcessors());
        }
        try {
            final var parsed = Integer.parseInt(configured);
            if (parsed < 1) {
                throw new IllegalArgumentException(
                        WORKER_COUNT_PROPERTY + " must be a positive integer: " + configured);
            }
            return parsed;
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException(
                    WORKER_COUNT_PROPERTY + " must be a positive integer or auto: " + configured,
                    exception);
        }
    }

    private static final class BatchingConsumer implements Consumer<CyclicTargetPair> {
        private final int batchSize;
        private final Consumer<CyclicTargetPair[]> consumer;
        private final ThreadLocal<ArrayList<CyclicTargetPair>> batches = ThreadLocal.withInitial(ArrayList::new);

        private BatchingConsumer(final int batchSize,
                                 final Consumer<CyclicTargetPair[]> consumer) {
            this.batchSize = batchSize;
            this.consumer = consumer;
        }

        @Override
        public void accept(final CyclicTargetPair pair) {
            final var batch = batches.get();
            batch.add(pair);
            if (batch.size() == batchSize) {
                flush(batch);
            }
        }

        private void flushCurrentThread() {
            try {
                flush(batches.get());
            } finally {
                batches.remove();
            }
        }

        private void flush(final ArrayList<CyclicTargetPair> batch) {
            if (batch.isEmpty()) {
                return;
            }
            final var emitted = batch.toArray(CyclicTargetPair[]::new);
            batch.clear();
            consumer.accept(emitted);
        }
    }

    /**
     * Expands the exact generation tree breadth first until it contains enough
     * accepted continuations to keep a larger worker pool balanced.
     */
    private static List<GenerationTask> buildFrontier(
            final CycleSpec[] specs,
            final Consumer<CyclicTargetPair> consumer,
            final Runnable cancellationCheck,
            final int targetTaskCount) {
        final var generator = new Generator(
                specs, consumer, null, null, cancellationCheck);
        final var initialTask = generator.initialTask();
        if (initialTask == null) {
            return List.of();
        }

        final var frontier = new ArrayDeque<GenerationTask>();
        frontier.add(initialTask);
        final var maximumExpansions = (long) targetTaskCount
                * (Arrays.stream(specs).mapToInt(CycleSpec::size).sum() + 1);
        var expansions = 0L;
        while (frontier.size() < targetTaskCount
                && expansions++ < maximumExpansions) {
            final var branch = frontier.pollFirst();
            if (branch == null) {
                break;
            }
            branch.expand(frontier::addLast);
        }
        return new ArrayList<>(frontier);
    }

    /** Test hook that emits raw orbit-minimal words before target realizability. */
    static void generateWords(final int[] partition,
                              final boolean[] orientedByPart,
                              final Consumer<int[]> consumer) {
        validate(partition, orientedByPart);
        new Generator(normalizedSpecs(partition, orientedByPart), null, consumer,
                null, NO_CANCELLATION_CHECK).generate();
    }

    private static void validate(final int[] partition,
                                 final boolean[] orientedByPart) {
        if (partition.length != orientedByPart.length) {
            throw new IllegalArgumentException(
                    "Partition and orientation arrays must have the same length");
        }
        if (partition.length == 0) {
            throw new IllegalArgumentException("The annotated partition must be nonempty");
        }
        for (var index = 0; index < partition.length; index++) {
            if (partition[index] < 2) {
                throw new IllegalArgumentException(
                        "Every cycle must have length at least two: "
                                + Arrays.toString(partition));
            }
            if (orientedByPart[index] && partition[index] < 3) {
                throw new IllegalArgumentException(
                        "Only cycles of length at least three can be oriented: "
                                + Arrays.toString(partition));
            }
        }
    }

    private static CycleSpec[] normalizedSpecs(final int[] partition,
                                               final boolean[] orientedByPart) {
        final var specs = new CycleSpec[partition.length];
        for (var index = 0; index < partition.length; index++) {
            specs[index] = new CycleSpec(partition[index], orientedByPart[index]);
        }
        Arrays.sort(specs, Comparator
                .comparingInt(CycleSpec::size)
                .reversed()
                .thenComparing(CycleSpec::oriented));

        // Any spatial image rooted outside the first annotated type starts
        // above symbol zero.  Give that type the smallest affected mass, so
        // the two comparisons per occurrence are bounded by 2S, even when
        // much larger unaffected parts are present.  Preserve whole groups
        // and the previous relative order of every other annotated type.
        var firstAffectedStart = -1;
        var firstAffectedEnd = -1;
        var leastAffectedMass = Long.MAX_VALUE;
        for (var start = 0; start < specs.length; ) {
            var end = start + 1;
            while (end < specs.length && specs[end].equals(specs[start])) {
                end++;
            }
            final var mass = (long) specs[start].size * (end - start);
            if ((specs[start].oriented || end - start > 1)
                    && mass < leastAffectedMass) {
                firstAffectedStart = start;
                firstAffectedEnd = end;
                leastAffectedMass = mass;
            }
            start = end;
        }
        if (firstAffectedStart > 0) {
            final var firstGroup = Arrays.copyOfRange(
                    specs, firstAffectedStart, firstAffectedEnd);
            System.arraycopy(specs, 0, specs, firstGroup.length, firstAffectedStart);
            System.arraycopy(firstGroup, 0, specs, 0, firstGroup.length);
        }
        return specs;
    }

    private record CycleSpec(int size, boolean oriented) {
    }

    private record GenerationTask(Generator generator,
                                  boolean necklace,
                                  boolean forcedMaximumTail,
                                  int t,
                                  int period,
                                  int reflectionPrefix,
                                  int maximumRunStart,
                                  int reflectionBlocks,
                                  boolean reverseSuffixSmaller,
                                  int finalPeriod) {

        private static GenerationTask regular(
                final Generator generator,
                final int t,
                final int period,
                final int reflectionPrefix,
                final int maximumRunStart,
                final int reflectionBlocks,
                final boolean reverseSuffixSmaller) {
            return new GenerationTask(generator, false, false, t, period,
                    reflectionPrefix, maximumRunStart, reflectionBlocks,
                    reverseSuffixSmaller, 0);
        }

        private static GenerationTask necklace(final Generator generator,
                                               final int t,
                                               final int period) {
            return new GenerationTask(generator, true, false, t, period,
                    0, 0, 0, false, 0);
        }

        private static GenerationTask forced(
                final Generator generator,
                final int t,
                final int period,
                final int reflectionPrefix,
                final int maximumRunStart,
                final int reflectionBlocks,
                final boolean reverseSuffixSmaller,
                final int finalPeriod) {
            return new GenerationTask(generator, false, true, t, period,
                    reflectionPrefix, maximumRunStart, reflectionBlocks,
                    reverseSuffixSmaller, finalPeriod);
        }

        private void expand(final Consumer<GenerationTask> collector) {
            generator.continuationCollector = collector;
            try {
                runContinuation();
            } finally {
                generator.continuationCollector = null;
            }
        }

        private void runToCompletion() {
            try {
                runContinuation();
            } finally {
                if (generator.consumer instanceof BatchingConsumer batchingConsumer) {
                    batchingConsumer.flushCurrentThread();
                }
            }
        }

        private void runContinuation() {
            if (necklace) {
                generator.generateNecklaces(t, period);
            } else if (forcedMaximumTail) {
                generator.continueForcedMaximumTail(t, period, reflectionPrefix,
                        maximumRunStart, reflectionBlocks, reverseSuffixSmaller,
                        finalPeriod);
            } else {
                generator.generateBracelets(t, period, reflectionPrefix,
                        maximumRunStart, reflectionBlocks, reverseSuffixSmaller);
            }
        }
    }

    private static final class Generator {
        private final CycleSpec[] specs;
        private final Consumer<CyclicTargetPair> consumer;
        private final Consumer<int[]> wordConsumer;
        private final Runnable countConsumer;
        private final Runnable cancellationCheck;
        private final boolean pruneTargetCycles;
        private final boolean auxiliaryActionTrivial;
        private final int n;
        private final int numberOfSymbols;

        // BraceletFC state.  The word is one-based, as in the published
        // recurrence; positions used by semantic state are zero-based.
        private final int[] word;
        private final int[] remaining;
        private final int[] run;
        private final int[] next;
        private final int[] previous;
        private final int[] blockSymbols;
        private final int[] blockLengths;
        private int availableHead;
        private int blockCount;

        // Alphabet and annotated-cycle metadata.
        private final int[] symbolCycle;
        private final int[] symbolRank;
        private final int[] firstSymbolByCycle;
        private final int[] groupByCycle;
        private final int[][] cyclesByGroup;

        // Origin-independent orientation state.
        private final int[] placedByCycle;
        private final int[] lastRankByCycle;
        private final int[] previousRankByPosition;
        private final boolean[] followsReverseOrder;
        private final boolean[] previousReverseOrderByPosition;

        // Incremental target-cycle state.
        private final int[] positionBySymbol;
        private final int[] firstPositionByCycle;
        private final int[] lastPositionByCycle;
        private final int[] previousLastPositionByPosition;
        private final int[] targetSuccessor;
        private final int[] targetPredecessor;
        // Rollback union--find for the weak components of the forced arcs.
        // Union by size bounds finds to O(log N); path compression is omitted
        // so each removed arc can undo its union in constant time.
        private final int[] targetComponentParent;
        private final int[] targetComponentSize;
        private final int[] targetUnionChildBySource;
        private final int[] firstTargetSourceByPosition;
        private final int[] secondTargetSourceByPosition;
        private int targetEdgeCount;

        // Each placed group-zero bead activates adjacent forward/backward
        // states, rooted at its position.  Thus state zero is always the
        // forward identity comparison.  Mapping arrays are flattened by state.
        private final int comparisonCount;
        private final int[] comparisonMapBase;
        private final int[] comparisonStartPosition;
        private final int[] comparisonStateByMapIndex;
        private int activeComparisonCount;
        private final int[] comparisonOffset;
        private final byte[] comparisonStatus;
        private final int[] comparisonSourceToTarget;
        private final int[] comparisonRankOffset;

        // Within each annotated-type group, targets are assigned in array
        // order.  Keeping the next index makes a first encounter O(1).
        private final int[] comparisonNextTargetByGroup;

        // Offsets and statuses are small enough to snapshot.  New lazy-map
        // entries are trailed, so backtracking does not copy all maps at every
        // node of the generation tree.
        private final int[][] comparisonOffsetStack;
        private final byte[][] comparisonStatusStack;
        private final int[] activeComparisonCountStack;
        private final int[] comparisonMapTrail;
        private final int[] comparisonMapTrailMarker;
        private int comparisonMapTrailSize;
        private Consumer<GenerationTask> continuationCollector;

        private Generator(final CycleSpec[] specs,
                          final Consumer<CyclicTargetPair> consumer,
                          final Consumer<int[]> wordConsumer,
                          final Runnable countConsumer,
                          final Runnable cancellationCheck) {
            this.specs = specs;
            this.consumer = consumer;
            this.wordConsumer = wordConsumer;
            this.countConsumer = countConsumer;
            this.cancellationCheck = cancellationCheck;
            this.pruneTargetCycles = consumer != null || countConsumer != null;
            this.auxiliaryActionTrivial = hasTrivialAuxiliaryAction(specs);
            this.n = Arrays.stream(specs).mapToInt(CycleSpec::size).sum();
            this.numberOfSymbols = Arrays.stream(specs)
                    .mapToInt(spec -> spec.oriented ? spec.size : 1)
                    .sum();

            this.word = new int[n + 1];
            this.remaining = new int[numberOfSymbols];
            this.run = new int[n + 2];
            this.next = new int[numberOfSymbols];
            this.previous = new int[numberOfSymbols];
            this.blockSymbols = new int[n + 1];
            this.blockLengths = new int[n + 1];

            this.symbolCycle = new int[numberOfSymbols];
            this.symbolRank = new int[numberOfSymbols];
            this.firstSymbolByCycle = new int[specs.length];
            this.groupByCycle = new int[specs.length];
            this.cyclesByGroup = buildCycleGroups(specs, groupByCycle);

            this.placedByCycle = new int[specs.length];
            this.lastRankByCycle = new int[specs.length];
            this.previousRankByPosition = new int[n];
            this.followsReverseOrder = new boolean[specs.length];
            this.previousReverseOrderByPosition = new boolean[n];

            this.positionBySymbol = new int[numberOfSymbols];
            this.firstPositionByCycle = new int[specs.length];
            this.lastPositionByCycle = new int[specs.length];
            this.previousLastPositionByPosition = new int[n];
            this.targetSuccessor = new int[n];
            this.targetPredecessor = new int[n];
            this.targetComponentParent = new int[n];
            this.targetComponentSize = new int[n];
            this.targetUnionChildBySource = new int[n];
            this.firstTargetSourceByPosition = new int[n];
            this.secondTargetSourceByPosition = new int[n];

            // With no auxiliary color permutation or rank-origin choice,
            // BraceletFC already enforces every required spatial comparison.
            this.comparisonCount = auxiliaryActionTrivial ? 0
                    : 2 * specs[0].size * cyclesByGroup[0].length;
            final var comparisonMapLength = comparisonCount * specs.length;
            this.comparisonMapBase = new int[comparisonCount];
            this.comparisonStartPosition = new int[comparisonCount];
            this.comparisonStateByMapIndex = new int[comparisonMapLength];
            this.comparisonOffset = new int[comparisonCount];
            this.comparisonStatus = new byte[comparisonCount];
            this.comparisonSourceToTarget = new int[comparisonMapLength];
            this.comparisonRankOffset = new int[comparisonMapLength];
            this.comparisonNextTargetByGroup =
                    new int[comparisonCount * cyclesByGroup.length];
            this.comparisonOffsetStack = new int[n][comparisonCount];
            this.comparisonStatusStack = new byte[n][comparisonCount];
            this.activeComparisonCountStack = new int[n];
            this.comparisonMapTrail = new int[comparisonMapLength];
            this.comparisonMapTrailMarker = new int[n];

            Arrays.fill(lastRankByCycle, -1);
            Arrays.fill(positionBySymbol, -1);
            Arrays.fill(firstPositionByCycle, -1);
            Arrays.fill(lastPositionByCycle, -1);
            Arrays.fill(targetSuccessor, -1);
            Arrays.fill(targetPredecessor, -1);
            Arrays.fill(targetComponentSize, 1);
            Arrays.fill(targetUnionChildBySource, -1);
            Arrays.fill(firstTargetSourceByPosition, -1);
            Arrays.fill(secondTargetSourceByPosition, -1);
            Arrays.fill(comparisonSourceToTarget, -1);
            for (var vertex = 0; vertex < n; vertex++) {
                targetComponentParent[vertex] = vertex;
            }
            initializeComparisonIndexes();
            initializeAlphabet();
        }

        private Generator(final Generator source) {
            this(source.specs, source.consumer, source.wordConsumer, source.countConsumer,
                    source.cancellationCheck);

            copy(source.word, word);
            copy(source.remaining, remaining);
            copy(source.run, run);
            copy(source.next, next);
            copy(source.previous, previous);
            copy(source.blockSymbols, blockSymbols);
            copy(source.blockLengths, blockLengths);
            availableHead = source.availableHead;
            blockCount = source.blockCount;

            copy(source.placedByCycle, placedByCycle);
            copy(source.lastRankByCycle, lastRankByCycle);
            copy(source.previousRankByPosition, previousRankByPosition);
            copy(source.followsReverseOrder, followsReverseOrder);
            copy(source.previousReverseOrderByPosition,
                    previousReverseOrderByPosition);

            copy(source.positionBySymbol, positionBySymbol);
            copy(source.firstPositionByCycle, firstPositionByCycle);
            copy(source.lastPositionByCycle, lastPositionByCycle);
            copy(source.previousLastPositionByPosition,
                    previousLastPositionByPosition);
            copy(source.targetSuccessor, targetSuccessor);
            copy(source.targetPredecessor, targetPredecessor);
            copy(source.targetComponentParent, targetComponentParent);
            copy(source.targetComponentSize, targetComponentSize);
            copy(source.targetUnionChildBySource, targetUnionChildBySource);
            copy(source.firstTargetSourceByPosition,
                    firstTargetSourceByPosition);
            copy(source.secondTargetSourceByPosition,
                    secondTargetSourceByPosition);
            targetEdgeCount = source.targetEdgeCount;

            copy(source.comparisonStartPosition, comparisonStartPosition);
            activeComparisonCount = source.activeComparisonCount;
            copy(source.comparisonOffset, comparisonOffset);
            copy(source.comparisonStatus, comparisonStatus);
            copy(source.comparisonSourceToTarget, comparisonSourceToTarget);
            copy(source.comparisonRankOffset, comparisonRankOffset);
            copy(source.comparisonNextTargetByGroup,
                    comparisonNextTargetByGroup);
            for (var position = 0; position < n; position++) {
                copy(source.comparisonOffsetStack[position],
                        comparisonOffsetStack[position]);
                copy(source.comparisonStatusStack[position],
                        comparisonStatusStack[position]);
            }
            copy(source.activeComparisonCountStack, activeComparisonCountStack);
            copy(source.comparisonMapTrail, comparisonMapTrail);
            copy(source.comparisonMapTrailMarker, comparisonMapTrailMarker);
            comparisonMapTrailSize = source.comparisonMapTrailSize;
        }

        private static void copy(final int[] source, final int[] target) {
            System.arraycopy(source, 0, target, 0, source.length);
        }

        private static void copy(final byte[] source, final byte[] target) {
            System.arraycopy(source, 0, target, 0, source.length);
        }

        private static void copy(final boolean[] source, final boolean[] target) {
            System.arraycopy(source, 0, target, 0, source.length);
        }

        private void initializeAlphabet() {
            var symbol = 0;
            for (var cycle = 0; cycle < specs.length; cycle++) {
                final var spec = specs[cycle];
                firstSymbolByCycle[cycle] = symbol;
                if (spec.oriented) {
                    for (var rank = 0; rank < spec.size; rank++) {
                        remaining[symbol] = 1;
                        symbolCycle[symbol] = cycle;
                        symbolRank[symbol] = rank;
                        symbol++;
                    }
                } else {
                    remaining[symbol] = spec.size;
                    symbolCycle[symbol] = cycle;
                    symbolRank[symbol] = -1;
                    symbol++;
                }
            }
        }

        private void initializeComparisonIndexes() {
            for (var state = 0; state < comparisonCount; state++) {
                final var mapBase = state * specs.length;
                comparisonMapBase[state] = mapBase;
                Arrays.fill(comparisonStateByMapIndex,
                        mapBase, mapBase + specs.length, state);
            }
        }

        private void generate() {
            cancellationCheck.run();
            if (!beginGeneration()) {
                return;
            }

            if (auxiliaryActionTrivial) {
                generateBracelets(2, 1, 1, 2, 1, false);
            } else {
                generateNecklaces(2, 1);
            }
            endGeneration();
        }

        private GenerationTask initialTask() {
            cancellationCheck.run();
            if (!beginGeneration()) {
                return null;
            }
            return auxiliaryActionTrivial
                    ? GenerationTask.regular(this, 2, 1, 1, 2, 1, false)
                    : GenerationTask.necklace(this, 2, 1);
        }

        private boolean beginGeneration() {
            if (n == 0 || numberOfSymbols == 0 || remaining[0] == 0) {
                return false;
            }

            Arrays.fill(word, numberOfSymbols - 1);
            initializeAvailableSymbols();

            word[1] = 0;
            remaining[0]--;
            if (remaining[0] == 0) {
                listRemove(0);
            }
            appendBlock(0);

            if (recordPlacement(0, 0)) {
                if (recordTargetArcs(0, 0)) {
                    saveComparisons(0);
                    if (advanceComparisons(1)) {
                        return true;
                    }
                    restoreComparisons(0);
                }
                removePlacement(0, 0);
            }

            removeBlock();
            if (remaining[0] == 0) {
                listAdd(0);
            }
            remaining[0]++;
            return false;
        }

        private void endGeneration() {
            restoreComparisons(0);
            removePlacement(0, 0);
            removeBlock();
            if (remaining[0] == 0) {
                listAdd(0);
            }
            remaining[0]++;
        }

        /**
         * BraceletFC(t,p,r,z,b,RS), extended by semantic placement and lazy
         * comparisons modulo the auxiliary group.
         */
        private void generateBracelets(final int t,
                                       final int period,
                                       final int reflectionPrefix,
                                       final int maximumRunStart,
                                       final int reflectionBlocks,
                                       boolean reverseSuffixSmaller) {
            cancellationCheck.run();
            reverseSuffixSmaller = updateReverseSuffixComparison(
                    t, reflectionPrefix, reverseSuffixSmaller);

            if (t > n) {
                if (!reverseSuffixSmaller && n % period == 0) {
                    emitDirectly();
                }
                return;
            }

            final var remainingSlots = n - t + 1;
            final var maximumSymbol = numberOfSymbols - 1;
            if (remaining[maximumSymbol] == remainingSlots) {
                final var finalPeriod = remainingSlots > run[t - period] ? n : period;
                appendForcedMaximumTail(t, period, reflectionPrefix,
                        maximumRunStart, reflectionBlocks, reverseSuffixSmaller,
                        finalPeriod);
                return;
            }
            if (remaining[0] == remainingSlots) {
                return;
            }

            var symbol = availableHead;
            final var lowerBound = word[t - period];
            while (symbol != -1 && symbol < lowerBound) {
                symbol = listNext(symbol);
            }
            while (symbol != -1) {
                if (!identityComparisonAllows(symbol)) {
                    symbol = listNext(symbol);
                    continue;
                }
                run[maximumRunStart] = t - maximumRunStart;
                appendBlock(symbol);
                remaining[symbol]--;
                if (remaining[symbol] == 0) {
                    listRemove(symbol);
                }
                word[t] = symbol;

                final var position = t - 1;
                if (recordPlacement(position, symbol)) {
                    if (recordTargetArcs(position, symbol)) {
                        final var reflectionComparison = checkTwistedReverse(t);
                        if (reflectionComparison != REVERSE_SMALLER) {
                            saveComparisons(position);
                            if (advanceComparisons(t)) {
                                recurseAfterReflectionCheck(t, period,
                                        reflectionPrefix, maximumRunStart,
                                        reflectionBlocks, reverseSuffixSmaller,
                                        symbol, reflectionComparison);
                            }
                            restoreComparisons(position);
                        }
                    }
                    removePlacement(position, symbol);
                }

                if (remaining[symbol] == 0) {
                    listAdd(symbol);
                }
                remaining[symbol]++;
                removeBlock();
                symbol = listNext(symbol);
            }

            word[t] = maximumSymbol;
        }

        /**
         * Fixed-content necklace recursion for nontrivial auxiliary actions.
         * The lazy comparisons enforce every rotation and twisted reflection,
         * so this path needs no separate reflected-prefix state.
         */
        private void generateNecklaces(final int t, final int period) {
            cancellationCheck.run();
            if (t > n) {
                if (n % period == 0) {
                    emitDirectly();
                }
                return;
            }

            var symbol = availableHead;
            final var lowerBound = word[t - period];
            while (symbol != -1 && symbol < lowerBound) {
                symbol = listNext(symbol);
            }
            while (symbol != -1) {
                if (!identityComparisonAllows(symbol)) {
                    symbol = listNext(symbol);
                    continue;
                }
                remaining[symbol]--;
                if (remaining[symbol] == 0) {
                    listRemove(symbol);
                }
                word[t] = symbol;

                final var position = t - 1;
                if (recordPlacement(position, symbol)) {
                    if (recordTargetArcs(position, symbol)) {
                        saveComparisons(position);
                        if (advanceComparisons(t)) {
                            final var nextPeriod = symbol == word[t - period]
                                    ? period : t;
                            descendNecklaces(t + 1, nextPeriod);
                        }
                        restoreComparisons(position);
                    }
                    removePlacement(position, symbol);
                }

                if (remaining[symbol] == 0) {
                    listAdd(symbol);
                }
                remaining[symbol]++;
                symbol = listNext(symbol);
            }

            word[t] = numberOfSymbols - 1;
        }

        private void descendNecklaces(final int t, final int period) {
            if (continuationCollector == null) {
                generateNecklaces(t, period);
                return;
            }
            continuationCollector.accept(GenerationTask.necklace(
                    new Generator(this), t, period));
        }

        /** The fixed maximum-symbol tail has one continuation and no color loop. */
        private void appendForcedMaximumTail(final int t,
                                             final int period,
                                             final int reflectionPrefix,
                                             final int maximumRunStart,
                                             final int reflectionBlocks,
                                             final boolean reverseSuffixSmaller,
                                             final int finalPeriod) {
            final var symbol = numberOfSymbols - 1;
            run[maximumRunStart] = t - maximumRunStart;
            appendBlock(symbol);
            remaining[symbol]--;
            if (remaining[symbol] == 0) {
                listRemove(symbol);
            }
            word[t] = symbol;

            final var position = t - 1;
            if (recordPlacement(position, symbol)) {
                if (recordTargetArcs(position, symbol)) {
                    final var reflectionComparison = checkTwistedReverse(t);
                    if (reflectionComparison != REVERSE_SMALLER) {
                        saveComparisons(position);
                        if (advanceComparisons(t)) {
                            recurseForcedTailAfterReflectionCheck(t, period,
                                    reflectionPrefix, maximumRunStart,
                                    reflectionBlocks, reverseSuffixSmaller, symbol,
                                    finalPeriod, reflectionComparison);
                        }
                        restoreComparisons(position);
                    }
                }
                removePlacement(position, symbol);
            }

            if (remaining[symbol] == 0) {
                listAdd(symbol);
            }
            remaining[symbol]++;
            removeBlock();
        }

        private void recurseForcedTailAfterReflectionCheck(
                final int t,
                final int period,
                final int reflectionPrefix,
                final int maximumRunStart,
                final int reflectionBlocks,
                final boolean reverseSuffixSmaller,
                final int symbol,
                final int finalPeriod,
                final int reflectionComparison) {
            final var nextPeriod = symbol == word[t - period] ? period : t;
            if (reflectionComparison == REVERSE_EQUAL) {
                descendForcedMaximumTail(t + 1, nextPeriod, t,
                        maximumRunStart, blockCount, false, finalPeriod);
            } else if (reflectionComparison == REVERSE_LARGER) {
                descendForcedMaximumTail(t + 1, nextPeriod, reflectionPrefix,
                        maximumRunStart, reflectionBlocks,
                        reverseSuffixSmaller, finalPeriod);
            }
        }

        private void descendForcedMaximumTail(
                final int t,
                final int period,
                final int reflectionPrefix,
                final int maximumRunStart,
                final int reflectionBlocks,
                final boolean reverseSuffixSmaller,
                final int finalPeriod) {
            if (continuationCollector == null) {
                continueForcedMaximumTail(t, period, reflectionPrefix,
                        maximumRunStart, reflectionBlocks, reverseSuffixSmaller,
                        finalPeriod);
                return;
            }
            continuationCollector.accept(GenerationTask.forced(
                    new Generator(this), t, period, reflectionPrefix,
                    maximumRunStart, reflectionBlocks, reverseSuffixSmaller,
                    finalPeriod));
        }

        private void continueForcedMaximumTail(
                final int t,
                final int period,
                final int reflectionPrefix,
                final int maximumRunStart,
                final int reflectionBlocks,
                boolean reverseSuffixSmaller,
                final int finalPeriod) {
            cancellationCheck.run();
            reverseSuffixSmaller = updateReverseSuffixComparison(
                    t, reflectionPrefix, reverseSuffixSmaller);
            if (t > n) {
                // BraceletFC's maximum-tail shortcut determines divisibility
                // from finalPeriod.  The period replayed above is only the
                // ordinary recurrence state and need not equal finalPeriod.
                if (!reverseSuffixSmaller && n % finalPeriod == 0) {
                    emitDirectly();
                }
                return;
            }
            appendForcedMaximumTail(t, period, reflectionPrefix,
                    maximumRunStart, reflectionBlocks, reverseSuffixSmaller,
                    finalPeriod);
        }

        private void recurseAfterReflectionCheck(final int t,
                                                 final int period,
                                                 final int reflectionPrefix,
                                                 final int maximumRunStart,
                                                 final int reflectionBlocks,
                                                 final boolean reverseSuffixSmaller,
                                                 final int symbol,
                                                 final int reflectionComparison) {
            final var maximumSymbol = numberOfSymbols - 1;
            final var nextMaximumRunStart = symbol == maximumSymbol
                    ? maximumRunStart : t + 1;
            final var nextPeriod = symbol == word[t - period] ? period : t;
            if (reflectionComparison == REVERSE_EQUAL) {
                descendNormally(t + 1, nextPeriod, t,
                        nextMaximumRunStart, blockCount, false);
            } else if (reflectionComparison == REVERSE_LARGER) {
                descendNormally(t + 1, nextPeriod, reflectionPrefix,
                        nextMaximumRunStart, reflectionBlocks,
                        reverseSuffixSmaller);
            }
        }

        private void descendNormally(final int t,
                                     final int period,
                                     final int reflectionPrefix,
                                     final int maximumRunStart,
                                     final int reflectionBlocks,
                                     final boolean reverseSuffixSmaller) {
            if (continuationCollector == null) {
                generateBracelets(t, period, reflectionPrefix, maximumRunStart,
                        reflectionBlocks, reverseSuffixSmaller);
                return;
            }
            continuationCollector.accept(GenerationTask.regular(
                    new Generator(this), t, period, reflectionPrefix,
                    maximumRunStart, reflectionBlocks, reverseSuffixSmaller));
        }

        /** Compares the assigned prefix with its rank-inverting reversal. */
        private int checkTwistedReverse(final int length) {
            var leftBlock = 1;
            var rightBlock = blockCount;
            var leftRemaining = blockLengths[leftBlock];
            var rightRemaining = blockLengths[rightBlock];
            var compared = 0;
            while (compared < length) {
                final var current = blockSymbols[leftBlock];
                final var candidate = reflectedSymbol(blockSymbols[rightBlock]);
                if (candidate < current) {
                    return REVERSE_SMALLER;
                }
                if (candidate > current) {
                    return REVERSE_LARGER;
                }

                final var consumed = Math.min(leftRemaining, rightRemaining);
                compared += consumed;
                leftRemaining -= consumed;
                rightRemaining -= consumed;
                if (leftRemaining == 0 && compared < length) {
                    leftBlock++;
                    leftRemaining = blockLengths[leftBlock];
                }
                if (rightRemaining == 0 && compared < length) {
                    rightBlock--;
                    rightRemaining = blockLengths[rightBlock];
                }
            }
            return REVERSE_EQUAL;
        }

        /** Maintains BraceletFC's comparison beyond its longest reflected prefix. */
        private boolean updateReverseSuffixComparison(final int t,
                                                      final int reflectionPrefix,
                                                      final boolean previousResult) {
            if (t - 1 <= (n - reflectionPrefix) / 2 + reflectionPrefix) {
                return previousResult;
            }
            final var mirroredPosition = n - t + 2 + reflectionPrefix;
            final var candidate = reflectedSymbol(word[t - 1]);
            final var current = word[mirroredPosition];
            if (candidate < current) {
                return true;
            }
            if (candidate > current) {
                return false;
            }
            return previousResult;
        }

        private int reflectedSymbol(final int symbol) {
            final var cycle = symbolCycle[symbol];
            if (!specs[cycle].oriented) {
                return symbol;
            }
            final var rank = symbolRank[symbol] == 0
                    ? 0 : specs[cycle].size - symbolRank[symbol];
            return firstSymbolByCycle[cycle] + rank;
        }

        private void appendBlock(final int symbol) {
            if (blockCount > 0 && blockSymbols[blockCount] == symbol) {
                blockLengths[blockCount]++;
            } else {
                blockCount++;
                blockSymbols[blockCount] = symbol;
                blockLengths[blockCount] = 1;
            }
        }

        private void removeBlock() {
            if (blockLengths[blockCount] == 1) {
                blockSymbols[blockCount] = 0;
                blockLengths[blockCount] = 0;
                blockCount--;
            } else {
                blockLengths[blockCount]--;
            }
        }

        private void initializeAvailableSymbols() {
            availableHead = -1;
            var prior = -1;
            for (var symbol = 0; symbol < numberOfSymbols; symbol++) {
                if (remaining[symbol] == 0) {
                    next[symbol] = -1;
                    previous[symbol] = -1;
                    continue;
                }
                if (availableHead == -1) {
                    availableHead = symbol;
                }
                previous[symbol] = prior;
                if (prior != -1) {
                    next[prior] = symbol;
                }
                prior = symbol;
            }
            if (prior != -1) {
                next[prior] = -1;
            }
        }

        private void listRemove(final int symbol) {
            final var prior = previous[symbol];
            final var following = next[symbol];
            if (prior == -1) {
                availableHead = following;
            } else {
                next[prior] = following;
            }
            if (following != -1) {
                previous[following] = prior;
            }
        }

        private void listAdd(final int symbol) {
            final var prior = previous[symbol];
            final var following = next[symbol];
            if (prior == -1) {
                availableHead = symbol;
            } else {
                next[prior] = symbol;
            }
            if (following != -1) {
                previous[following] = symbol;
            }
        }

        private int listNext(final int symbol) {
            return next[symbol];
        }

        /**
         * The forward zero-shift state compares a prefix with the least member
         * of its own auxiliary orbit.  Its next source and target are the bead
         * about to be placed, so an impossible strict mismatch can be rejected
         * before any semantic or rollback state is touched.
         */
        private boolean identityComparisonAllows(final int symbol) {
            if (auxiliaryActionTrivial) {
                return true;
            }
            final var sourceCycle = symbolCycle[symbol];
            final var mapIndex = sourceCycle;
            var targetCycle = comparisonSourceToTarget[mapIndex];
            if (targetCycle == -1) {
                final var groupIndex = groupByCycle[sourceCycle];
                final var group = cyclesByGroup[groupIndex];
                targetCycle = group[comparisonNextTargetByGroup[groupIndex]];
                return symbol == firstSymbolByCycle[targetCycle];
            }
            if (!specs[sourceCycle].oriented) {
                return symbol == firstSymbolByCycle[targetCycle];
            }
            var rank = symbolRank[symbol] + comparisonRankOffset[mapIndex];
            if (rank < 0) {
                rank += specs[sourceCycle].size;
            }
            return symbol == firstSymbolByCycle[targetCycle] + rank;
        }

        /**
         * Saves active comparisons before the bead at {@code position} can
         * activate a new pair or advance existing comparisons.
         */
        private void saveComparisons(final int position) {
            System.arraycopy(comparisonOffset, 0,
                    comparisonOffsetStack[position], 0, activeComparisonCount);
            System.arraycopy(comparisonStatus, 0,
                    comparisonStatusStack[position], 0, activeComparisonCount);
            activeComparisonCountStack[position] = activeComparisonCount;
            comparisonMapTrailMarker[position] = comparisonMapTrailSize;
        }

        private void restoreComparisons(final int position) {
            final var savedCount = activeComparisonCountStack[position];
            System.arraycopy(comparisonOffsetStack[position], 0,
                    comparisonOffset, 0, savedCount);
            System.arraycopy(comparisonStatusStack[position], 0,
                    comparisonStatus, 0, savedCount);
            final var marker = comparisonMapTrailMarker[position];
            while (comparisonMapTrailSize > marker) {
                final var mapIndex = comparisonMapTrail[--comparisonMapTrailSize];
                final var state = comparisonStateByMapIndex[mapIndex];
                final var sourceCycle = mapIndex - comparisonMapBase[state];
                comparisonSourceToTarget[mapIndex] = -1;
                comparisonRankOffset[mapIndex] = 0;
                comparisonNextTargetByGroup[state * cyclesByGroup.length
                        + groupByCycle[sourceCycle]]--;
            }
            // A newly activated pair has no state before this placement.
            // All its map entries were removed by the same trail rollback.
            for (var state = savedCount; state < activeComparisonCount; state++) {
                comparisonStartPosition[state] = 0;
                comparisonOffset[state] = 0;
                comparisonStatus[state] = 0;
            }
            activeComparisonCount = savedCount;
        }

        /**
         * Activates the two traversals rooted at a new group-zero bead, then
         * advances every comparison through its now-known consecutive pairs.
         * Roots of other types cannot map to symbol zero, so their transforms
         * already exceed the candidate word at its first position.
         */
        private boolean advanceComparisons(final int prefixLength) {
            final var position = prefixLength - 1;
            if (!auxiliaryActionTrivial
                    && groupByCycle[symbolCycle[word[prefixLength]]] == 0) {
                comparisonStartPosition[activeComparisonCount++] = position;
                comparisonStartPosition[activeComparisonCount++] = position;
            }
            for (var state = 0; state < activeComparisonCount; state++) {
                if (comparisonStatus[state] == COMPARISON_LARGER) {
                    continue;
                }
                final var reflected = (state & 1) != 0;
                final var startPosition = comparisonStartPosition[state];
                var offset = comparisonOffset[state];
                while (offset < n && offset < prefixLength) {
                    var sourcePosition = reflected
                            ? startPosition - offset : startPosition + offset;
                    if (sourcePosition < 0) {
                        sourcePosition += n;
                    } else if (sourcePosition >= n) {
                        sourcePosition -= n;
                    }
                    if (sourcePosition >= prefixLength) {
                        break;
                    }

                    final var sourceSymbol = word[sourcePosition + 1];
                    final var candidate = leastMappedSymbol(
                            state, sourceSymbol, reflected);
                    final var current = word[offset + 1];
                    if (candidate < current) {
                        return false;
                    }
                    offset++;
                    comparisonOffset[state] = offset;
                    if (candidate > current) {
                        comparisonStatus[state] = COMPARISON_LARGER;
                        break;
                    }
                }
            }
            return true;
        }

        /**
         * Lazily constructs the lexicographically least auxiliary image for one
         * spatial transform.  First encounters choose the least unused equal
         * cycle and the rank shift that produces rank zero.
         */
        private int leastMappedSymbol(final int state,
                                      final int sourceSymbol,
                                      final boolean reflected) {
            final var mapBase = comparisonMapBase[state];
            final var sourceCycle = symbolCycle[sourceSymbol];
            final var mapIndex = mapBase + sourceCycle;
            var targetCycle = comparisonSourceToTarget[mapIndex];
            if (targetCycle == -1) {
                final var groupIndex = groupByCycle[sourceCycle];
                final var nextTargetIndex = state * cyclesByGroup.length + groupIndex;
                targetCycle = cyclesByGroup[groupIndex][
                        comparisonNextTargetByGroup[nextTargetIndex]++];
                comparisonSourceToTarget[mapIndex] = targetCycle;
                comparisonMapTrail[comparisonMapTrailSize++] = mapIndex;

                if (specs[sourceCycle].oriented) {
                    final var rank = symbolRank[sourceSymbol];
                    comparisonRankOffset[mapIndex] = reflected ? rank : -rank;
                }
            }

            if (!specs[sourceCycle].oriented) {
                return firstSymbolByCycle[targetCycle];
            }
            final var size = specs[sourceCycle].size;
            final var transformedRank = (reflected ? -symbolRank[sourceSymbol]
                    : symbolRank[sourceSymbol]) + comparisonRankOffset[mapIndex];
            return firstSymbolByCycle[targetCycle]
                    + (transformedRank < 0 ? transformedRank + size : transformedRank);
        }

        /** Records orientation information for a placement. */
        private boolean recordPlacement(final int position, final int symbol) {
            final var cycle = symbolCycle[symbol];
            previousRankByPosition[position] = lastRankByCycle[cycle];
            previousReverseOrderByPosition[position] = followsReverseOrder[cycle];
            previousLastPositionByPosition[position] = lastPositionByCycle[cycle];
            firstTargetSourceByPosition[position] = -1;
            secondTargetSourceByPosition[position] = -1;

            if (specs[cycle].oriented) {
                if (placedByCycle[cycle] == 0) {
                    followsReverseOrder[cycle] = true;
                } else {
                    final var precedingRank = lastRankByCycle[cycle] == 0
                            ? specs[cycle].size - 1
                            : lastRankByCycle[cycle] - 1;
                    followsReverseOrder[cycle] &= symbolRank[symbol] == precedingRank;
                }
                lastRankByCycle[cycle] = symbolRank[symbol];
            }
            placedByCycle[cycle]++;

            if (specs[cycle].oriented
                    && placedByCycle[cycle] == specs[cycle].size
                    && followsReverseOrder[cycle]) {
                removePlacement(position, symbol);
                return false;
            }

            return true;
        }

        /**
         * Records every target arc determined by a placement.  This is kept
         * separate so the constant-time orientation check runs first.
         */
        private boolean recordTargetArcs(final int position, final int symbol) {
            // generateWords exposes all orbit-minimal words, including those
            // whose forced target is not a single cycle.
            if (!pruneTargetCycles) {
                return true;
            }

            final var cycle = symbolCycle[symbol];
            final boolean accepted;
            if (specs[cycle].oriented) {
                positionBySymbol[symbol] = position;
                accepted = addOrientedTargetArcs(position, symbol);
            } else {
                accepted = addUnorientedTargetArcs(position, cycle);
            }
            return accepted;
        }

        private void removePlacement(final int position, final int symbol) {
            final var cycle = symbolCycle[symbol];
            if (pruneTargetCycles) {
                removeTargetArcs(position);
                if (specs[cycle].oriented) {
                    positionBySymbol[symbol] = -1;
                } else {
                    lastPositionByCycle[cycle] = previousLastPositionByPosition[position];
                    if (placedByCycle[cycle] == 1) {
                        firstPositionByCycle[cycle] = -1;
                    }
                }
            }
            placedByCycle[cycle]--;
            if (specs[cycle].oriented) {
                lastRankByCycle[cycle] = previousRankByPosition[position];
                followsReverseOrder[cycle] = previousReverseOrderByPosition[position];
            }
        }

        private boolean addOrientedTargetArcs(final int position, final int symbol) {
            final var cycle = symbolCycle[symbol];
            final var size = specs[cycle].size;
            final var rank = symbolRank[symbol];
            final var firstSymbol = firstSymbolByCycle[cycle];

            final var predecessorSymbol = firstSymbol
                    + (rank == 0 ? size - 1 : rank - 1);
            final var predecessorPosition = positionBySymbol[predecessorSymbol];
            if (predecessorPosition != -1
                    && !addTargetArc(position,
                    predecessorPosition == 0 ? n - 1 : predecessorPosition - 1,
                    position)) {
                return false;
            }

            final var successorSymbol = firstSymbol
                    + (rank + 1 == size ? 0 : rank + 1);
            final var successorPosition = positionBySymbol[successorSymbol];
            return successorPosition == -1
                    || addTargetArc(position,
                    position == 0 ? n - 1 : position - 1, successorPosition);
        }

        private boolean addUnorientedTargetArcs(final int position, final int cycle) {
            final var occurrence = placedByCycle[cycle] - 1;
            if (occurrence == 0) {
                firstPositionByCycle[cycle] = position;
            } else if (!addTargetArc(position, position - 1, lastPositionByCycle[cycle])) {
                return false;
            }

            lastPositionByCycle[cycle] = position;
            return occurrence + 1 < specs[cycle].size
                    || addTargetArc(position,
                    firstPositionByCycle[cycle] == 0
                            ? n - 1 : firstPositionByCycle[cycle] - 1,
                    position);
        }

        private boolean addTargetArc(final int placementPosition,
                                     final int source,
                                     final int target) {
            final var sourceRoot = findTargetComponent(source);
            final var targetRoot = findTargetComponent(target);
            final var closesCycle = sourceRoot == targetRoot;
            if (closesCycle && targetEdgeCount + 1 < n) {
                return false;
            }

            // Before the final arc closes the target cycle, indegree and
            // outdegree at most one make every component a directed path.
            // Joining two vertices in one weak component therefore closes a
            // directed cycle; otherwise this union records the joined paths.
            targetUnionChildBySource[source] = -1;
            if (!closesCycle) {
                var parentRoot = sourceRoot;
                var childRoot = targetRoot;
                if (targetComponentSize[parentRoot] < targetComponentSize[childRoot]) {
                    final var temporary = parentRoot;
                    parentRoot = childRoot;
                    childRoot = temporary;
                }
                targetComponentParent[childRoot] = parentRoot;
                targetComponentSize[parentRoot] += targetComponentSize[childRoot];
                targetUnionChildBySource[source] = childRoot;
            }

            targetSuccessor[source] = target;
            targetPredecessor[target] = source;
            targetEdgeCount++;
            if (firstTargetSourceByPosition[placementPosition] == -1) {
                firstTargetSourceByPosition[placementPosition] = source;
            } else if (secondTargetSourceByPosition[placementPosition] == -1) {
                secondTargetSourceByPosition[placementPosition] = source;
            }
            return true;
        }

        /** Finds a forced-target component in O(log N) worst-case time. */
        private int findTargetComponent(final int vertex) {
            var root = vertex;
            while (targetComponentParent[root] != root) {
                root = targetComponentParent[root];
            }
            return root;
        }

        private void removeTargetArcs(final int placementPosition) {
            removeTargetArc(secondTargetSourceByPosition[placementPosition]);
            removeTargetArc(firstTargetSourceByPosition[placementPosition]);
            firstTargetSourceByPosition[placementPosition] = -1;
            secondTargetSourceByPosition[placementPosition] = -1;
        }

        private void removeTargetArc(final int source) {
            if (source == -1) {
                return;
            }
            final var unionChild = targetUnionChildBySource[source];
            if (unionChild != -1) {
                final var unionParent = targetComponentParent[unionChild];
                targetComponentSize[unionParent] -= targetComponentSize[unionChild];
                targetComponentParent[unionChild] = unionChild;
                targetUnionChildBySource[source] = -1;
            }
            final var target = targetSuccessor[source];
            targetSuccessor[source] = -1;
            targetPredecessor[target] = -1;
            targetEdgeCount--;
        }

        private void emitDirectly() {
            if (wordConsumer != null) {
                final var result = new int[n];
                System.arraycopy(word, 1, result, 0, n);
                wordConsumer.accept(result);
                return;
            }
            if (countConsumer != null) {
                countConsumer.run();
                return;
            }
            final var pair = decodeWord();
            consumer.accept(pair);
        }

        private CyclicTargetPair decodeWord() {
            final var symbolsByCycle = new int[specs.length][];
            final var fillByCycle = new int[specs.length];
            for (var cycle = 0; cycle < specs.length; cycle++) {
                symbolsByCycle[cycle] = new int[specs[cycle].size];
            }

            for (var position = n - 1; position >= 0; position--) {
                final var symbol = word[position + 1];
                final var cycle = symbolCycle[symbol];
                final var cyclePosition = specs[cycle].oriented
                        ? symbolRank[symbol]
                        : fillByCycle[cycle]++;
                symbolsByCycle[cycle][cyclePosition] = position;
            }

            final var omega = new MulticyclePermutation(specs.length, n);
            for (final var cycleSymbols : symbolsByCycle) {
                omega.add(Cycle.of(cycleSymbols));
            }
            final var betaSymbols = new int[n];
            final var rhoSymbols = new int[n];
            for (var symbol = 0; symbol < n; symbol++) {
                betaSymbols[symbol] = symbol;
            }
            for (var index = 1; index < n; index++) {
                rhoSymbols[index] = targetSuccessor[rhoSymbols[index - 1]];
            }
            return CyclicTargetPair.ofKnownTargetOwned(
                    omega, Cycle.of(betaSymbols), Cycle.of(rhoSymbols));
        }

        private static int[][] buildCycleGroups(final CycleSpec[] specs,
                                                final int[] groupByCycle) {
            final List<int[]> groups = new ArrayList<>();
            for (var start = 0; start < specs.length; ) {
                var end = start + 1;
                while (end < specs.length && specs[end].equals(specs[start])) {
                    end++;
                }
                final var group = new int[end - start];
                final var groupIndex = groups.size();
                for (var cycle = start; cycle < end; cycle++) {
                    group[cycle - start] = cycle;
                    groupByCycle[cycle] = groupIndex;
                }
                groups.add(group);
                start = end;
            }
            return groups.toArray(int[][]::new);
        }

        private static boolean hasTrivialAuxiliaryAction(final CycleSpec[] specs) {
            for (var cycle = 0; cycle < specs.length; cycle++) {
                if (specs[cycle].oriented) {
                    return false;
                }
                if (cycle > 0 && specs[cycle - 1].size == specs[cycle].size) {
                    return false;
                }
            }
            return true;
        }
    }

    private static void rethrow(final Throwable throwable) {
        if (throwable == null) {
            return;
        }
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(throwable);
    }

    private static final class BranchStopped extends RuntimeException {
        private static final BranchStopped INSTANCE = new BranchStopped();

        private BranchStopped() {
            super(null, null, false, false);
        }
    }
}
