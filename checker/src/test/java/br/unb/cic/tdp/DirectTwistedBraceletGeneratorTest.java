package br.unb.cic.tdp;

import br.unb.cic.tdp.base.CyclicTargetCandidate;
import br.unb.cic.tdp.base.CyclicTargetPair;
import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.PermutationGroups;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectTwistedBraceletGeneratorTest {

    @Test
    void generatesKnownSmallMixedFamiliesWithoutDuplicates() {
        assertGeneratedFamilyEqualsBruteForce(
                new int[]{3, 3}, new boolean[]{false, false});
        assertGeneratedFamilyEqualsBruteForce(
                new int[]{3, 3}, new boolean[]{true, false});
        assertGeneratedFamilyEqualsBruteForce(
                new int[]{3, 3}, new boolean[]{true, true});
        assertGeneratedFamilyEqualsBruteForce(
                new int[]{5}, new boolean[]{true});
        assertGeneratedFamilyEqualsBruteForce(
                new int[]{6}, new boolean[]{true});
        assertGeneratedFamilyEqualsBruteForce(
                new int[]{5, 3}, new boolean[]{true, false});
    }

    @Test
    void rejectsInvalidAnnotatedPartitions() {
        assertThrows(IllegalArgumentException.class, () ->
                DirectTwistedBraceletGenerator.generateRepresentatives(
                        new int[]{}, new boolean[]{}, ignored -> { }));
        assertThrows(IllegalArgumentException.class, () ->
                DirectTwistedBraceletGenerator.generateRepresentatives(
                        new int[]{3}, new boolean[]{true, false}, ignored -> { }));
        assertThrows(IllegalArgumentException.class, () ->
                DirectTwistedBraceletGenerator.generateRepresentatives(
                        new int[]{0}, new boolean[]{false}, ignored -> { }));
        assertThrows(IllegalArgumentException.class, () ->
                DirectTwistedBraceletGenerator.generateRepresentatives(
                        new int[]{1}, new boolean[]{false}, ignored -> { }));
        assertThrows(IllegalArgumentException.class, () ->
                DirectTwistedBraceletGenerator.generateRepresentatives(
                        new int[]{2}, new boolean[]{true}, ignored -> { }));
    }

    @Test
    void concurrentGenerationMatchesSequentialGeneration() {
        assertConcurrentGenerationMatchesSequentialGeneration(
                new int[]{5, 3}, new boolean[]{true, false});
    }

    @Test
    void sparseComparisonBranchesMatchSequentialGeneration() {
        assertConcurrentGenerationMatchesSequentialGeneration(
                new int[]{5, 3, 3}, new boolean[]{false, false, false});
        assertConcurrentGenerationMatchesSequentialGeneration(
                new int[]{5, 3, 3}, new boolean[]{false, true, false});
    }

    private static void assertConcurrentGenerationMatchesSequentialGeneration(
            final int[] partition, final boolean[] orientedByPart) {
        final var expected = generatedCanonicals(partition, orientedByPart);
        assertTrue(!expected.isEmpty());
        final Set<String> concurrent = ConcurrentHashMap.newKeySet();
        final var emitted = new AtomicInteger();

        DirectTwistedBraceletGenerator.generateRepresentativesConcurrently(
                partition, orientedByPart, () -> { }, pair -> {
                    emitted.incrementAndGet();
                    concurrent.add(classKey(pair));
                });

        assertEquals(expected, concurrent);
        assertEquals(concurrent.size(), emitted.get());
    }

    @Test
    void checksCancellationInsideSequentialRecursion() {
        final var checks = new AtomicInteger();
        assertThrows(CancellationException.class, () ->
                DirectTwistedBraceletGenerator.generateRepresentatives(
                        new int[]{5, 3, 3}, new boolean[]{true, false, false}, () -> {
                            if (checks.incrementAndGet() >= 20) {
                                throw new CancellationException("test cancellation");
                            }
                        }, ignored -> { }));
    }

    @Test
    void checksCancellationInsideConcurrentBranches() {
        final var checks = new AtomicInteger();
        assertThrows(CancellationException.class, () ->
                DirectTwistedBraceletGenerator.generateRepresentativesConcurrently(
                        new int[]{5, 3, 3}, new boolean[]{true, false, false}, () -> {
                            if (checks.incrementAndGet() >= 20) {
                                throw new CancellationException("test cancellation");
                            }
                        }, ignored -> { }));
    }

    @Test
    void generatesEveryStrictFiveThreeThreeConfigurationClass() {
        final List<CyclicTargetPair> generated = new ArrayList<>();
        DirectTwistedBraceletGenerator.generateRepresentatives(
                new int[]{5, 3, 3}, new boolean[]{true, false, false},
                generated::add);

        final var distinct = generated.stream()
                .map(CyclicTargetPair::extendedToricClassKey)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(generated.size(), distinct.size(),
                "The generator must emit at most one representative per class");
        assertEquals(479, generated.size());
        assertEquals(479, DirectTwistedBraceletGenerator.countRepresentatives(
                new int[]{5, 3, 3}, new boolean[]{true, false, false}));
    }

    @Test
    void constructedTargetMatchesAlgebraicProduct() {
        DirectTwistedBraceletGenerator.generateRepresentatives(
                new int[]{5, 3}, new boolean[]{true, false}, pair ->
                        assertEquals(pair.getProduct().asNCycle(), pair.getRho()));
    }

    @Test
    void preservesSmallS17ProofCorpusCounts() {
        assertEquals(7, generatedCanonicals(
                new int[]{8, 7, 2}, new boolean[]{false, false, false}).size());
        assertEquals(21, generatedCanonicals(
                new int[]{8, 6, 3}, new boolean[]{false, false, false}).size());
        assertEquals(35, generatedCanonicals(
                new int[]{8, 5, 4}, new boolean[]{false, false, false}).size());
        assertEquals(37, generatedCanonicals(
                new int[]{7, 7, 3}, new boolean[]{false, false, false}).size());
    }

    @Test
    void emitsNothingForTheUnrealizableSingleTwoCycleFamily() {
        assertTrue(generatedCanonicals(
                new int[]{2}, new boolean[]{false}).isEmpty());
    }

    @Test
    void twistedReflectionIdentifiesDistinctOrientedSixNecklaces() {
        final List<int[]> words = new ArrayList<>();
        DirectTwistedBraceletGenerator.generateWords(
                new int[]{6}, new boolean[]{true}, words::add);

        assertEquals(19, words.size());
        assertTrue(words.stream().noneMatch(word -> Arrays.equals(
                        word, new int[]{0, 5, 4, 3, 2, 1})),
                "The recursion must not generate the unoriented rank order");
    }

    @Test
    void rejectsTheGeneralizedBoothCounterexample() {
        final var candidate = new int[]{0, 0, 1, 1, 0, 1};
        final List<int[]> words = new ArrayList<>();
        DirectTwistedBraceletGenerator.generateWords(
                new int[]{3, 3}, new boolean[]{false, false}, words::add);

        assertTrue(words.stream().noneMatch(word -> Arrays.equals(word, candidate)));
    }

    @Test
    void directGenerationHandlesLongRepetitiveWords() {
        final List<int[]> words = new ArrayList<>();
        DirectTwistedBraceletGenerator.generateWords(
                new int[]{24}, new boolean[]{false}, words::add);

        assertEquals(1, words.size());
        assertTrue(Arrays.stream(words.getFirst()).allMatch(symbol -> symbol == 0));
    }

    @Test
    void rawWordsMatchExhaustiveOrbitsWhenUnaffectedPartsAreLargest() {
        assertRawWordsMatchBruteForce(new int[]{5, 3}, new boolean[]{false, true},
                List.of(new Annotation(3, true), new Annotation(5, false)));
        assertRawWordsMatchBruteForce(new int[]{5, 2, 2}, new boolean[]{false, false, false},
                List.of(new Annotation(2, false), new Annotation(2, false),
                        new Annotation(5, false)));
        // This annotated type has no realizable target, so a representative-only
        // oracle would not exercise its comparison rollback and slot reuse.
        assertRawWordsMatchBruteForce(new int[]{4, 2, 2}, new boolean[]{false, false, false},
                List.of(new Annotation(2, false), new Annotation(2, false),
                        new Annotation(4, false)));
        assertRawWordsMatchBruteForce(new int[]{2, 5, 2}, new boolean[]{false, false, false},
                List.of(new Annotation(2, false), new Annotation(2, false),
                        new Annotation(5, false)));
    }

    @Test
    void rawWordsMatchExhaustiveOrbitsWithCompetingAffectedGroups() {
        assertRawWordsMatchBruteForce(new int[]{4, 3}, new boolean[]{true, true},
                List.of(new Annotation(3, true), new Annotation(4, true)));
        assertRawWordsMatchBruteForce(new int[]{3, 2, 2}, new boolean[]{true, false, false},
                List.of(new Annotation(3, true), new Annotation(2, false),
                        new Annotation(2, false)));
        // Equal total group masses retain descending-size priority.
        assertRawWordsMatchBruteForce(new int[]{2, 4, 2}, new boolean[]{false, true, false},
                List.of(new Annotation(4, true), new Annotation(2, false),
                        new Annotation(2, false)));
    }

    @Test
    void matchesEveryFixedPointFreeAnnotatedFamilyOnEightSymbols() {
        assertMatchesEveryFixedPointFreeAnnotatedFamily(8, 17);
    }

    @Test
    void matchesEveryFixedPointFreeAnnotatedFamilyOnElevenSymbols() {
        assertMatchesEveryFixedPointFreeAnnotatedFamily(11, 50);
    }

    private static void assertMatchesEveryFixedPointFreeAnnotatedFamily(
            final int n,
            final int expectedAnnotatedTypeCount) {
        final Map<List<Annotation>, Set<String>> expected = new HashMap<>();
        final var target = canonicalCycle(n);

        forEachNCycle(n, current -> {
            final var omega = PermutationGroups.computeProduct(
                    false, target, current.getInverse());
            if (omega.getNumberOfSymbols() != n) {
                return;
            }

            final var candidate = new CyclicTargetCandidate(omega, current);
            final var type = actualAnnotations(candidate);
            expected.computeIfAbsent(type, ignored -> new HashSet<>())
                    .add(classKey(candidate));
        });

        assertTrue(!expected.isEmpty());
        final var everyType = everyFixedPointFreeAnnotatedType(n);
        assertEquals(expectedAnnotatedTypeCount, everyType.size(),
                "Unexpected number of fixed-point-free annotated types in S_" + n);
        assertTrue(everyType.containsAll(expected.keySet()));
        for (final var type : everyType) {
            final var partition = new int[type.size()];
            final var orientedByPart = new boolean[type.size()];
            for (var index = 0; index < type.size(); index++) {
                partition[index] = type.get(index).size();
                orientedByPart[index] = type.get(index).oriented();
            }
            assertEquals(expected.getOrDefault(type, Set.of()),
                    generatedCanonicals(partition, orientedByPart),
                    "Mismatch in S_" + n + " for annotated type " + type);
        }
    }

    private static Set<List<Annotation>> everyFixedPointFreeAnnotatedType(final int n) {
        final Set<List<Annotation>> result = new HashSet<>();
        enumeratePartitions(n, n, new ArrayList<>(), result);
        return result;
    }

    private static void enumeratePartitions(final int remaining,
                                            final int maximumPart,
                                            final List<Integer> partition,
                                            final Set<List<Annotation>> result) {
        if (remaining == 0) {
            enumerateAnnotations(partition, 0, new ArrayList<>(), result);
            return;
        }

        for (var part = Math.min(remaining, maximumPart); part >= 2; part--) {
            partition.add(part);
            enumeratePartitions(remaining - part, part, partition, result);
            partition.removeLast();
        }
    }

    private static void enumerateAnnotations(final List<Integer> partition,
                                             final int start,
                                             final List<Annotation> annotations,
                                             final Set<List<Annotation>> result) {
        if (start == partition.size()) {
            result.add(List.copyOf(annotations));
            return;
        }

        final var size = partition.get(start);
        var end = start + 1;
        while (end < partition.size() && partition.get(end) == size) {
            end++;
        }
        final var multiplicity = end - start;
        final var maximumOriented = size == 2 ? 0 : multiplicity;

        for (var oriented = 0; oriented <= maximumOriented; oriented++) {
            final var previousSize = annotations.size();
            for (var index = 0; index < multiplicity - oriented; index++) {
                annotations.add(new Annotation(size, false));
            }
            for (var index = 0; index < oriented; index++) {
                annotations.add(new Annotation(size, true));
            }
            enumerateAnnotations(partition, end, annotations, result);
            annotations.subList(previousSize, annotations.size()).clear();
        }
    }

    private static void assertGeneratedFamilyEqualsBruteForce(
            final int[] partition,
            final boolean[] orientedByPart) {
        final var generated = generatedCanonicals(partition, orientedByPart);
        final var expected = bruteForceCanonicals(
                Arrays.stream(partition).sum(), partition, orientedByPart);
        assertEquals(expected, generated);
    }

    private static void assertRawWordsMatchBruteForce(
            final int[] partition,
            final boolean[] orientedByPart,
            final List<Annotation> alphabetOrder) {
        final Set<String> generated = new HashSet<>();
        final var emitted = new AtomicInteger();
        DirectTwistedBraceletGenerator.generateWords(partition, orientedByPart, word -> {
            emitted.incrementAndGet();
            generated.add(Arrays.toString(word));
        });

        final var expected = bruteForceRawWords(alphabetOrder);
        assertTrue(!expected.isEmpty());
        assertEquals(expected, generated,
                "Raw orbit mismatch for alphabet " + alphabetOrder);
        assertEquals(generated.size(), emitted.get(), "Duplicate raw representatives");
    }

    /**
     * Enumerates every fixed-content word, then minimizes every complete spatial
     * transform independently. No generation-prefix state or realizability filter
     * is shared with the implementation under test. The expected alphabet order
     * is supplied explicitly so its choice of anchor group is tested as well.
     */
    private static Set<String> bruteForceRawWords(final List<Annotation> alphabetOrder) {
        final var length = alphabetOrder.stream().mapToInt(Annotation::size).sum();
        final var symbolCount = alphabetOrder.stream()
                .mapToInt(type -> type.oriented() ? type.size() : 1).sum();
        final var symbolCycle = new int[symbolCount];
        final var symbolRank = new int[symbolCount];
        final var firstSymbol = new int[alphabetOrder.size()];
        final var remaining = new int[symbolCount];
        var symbol = 0;
        for (var cycle = 0; cycle < alphabetOrder.size(); cycle++) {
            final var type = alphabetOrder.get(cycle);
            firstSymbol[cycle] = symbol;
            for (var rank = 0; rank < (type.oriented() ? type.size() : 1); rank++) {
                symbolCycle[symbol] = cycle;
                symbolRank[symbol] = rank;
                remaining[symbol++] = type.oriented() ? 1 : type.size();
            }
        }

        final Set<String> expected = new HashSet<>();
        forEachMultisetWord(remaining, new int[length], 0, word -> {
            for (var cycle = 0; cycle < alphabetOrder.size(); cycle++) {
                final var type = alphabetOrder.get(cycle);
                if (!type.oriented()) {
                    continue;
                }
                var previousRank = -1;
                var reverseOrder = true;
                for (final var bead : word) {
                    if (symbolCycle[bead] == cycle) {
                        if (previousRank >= 0
                                && (symbolRank[bead] + 1) % type.size() != previousRank) {
                            reverseOrder = false;
                        }
                        previousRank = symbolRank[bead];
                    }
                }
                if (reverseOrder) {
                    return;
                }
            }

            int[] minimum = null;
            for (var start = 0; start < length; start++) {
                for (final var direction : new int[]{1, -1}) {
                    final var transformed = standardizedTraversal(word, start, direction,
                            alphabetOrder, symbolCycle, symbolRank, firstSymbol);
                    if (minimum == null || Arrays.compare(transformed, minimum) < 0) {
                        minimum = transformed;
                    }
                }
            }
            expected.add(Arrays.toString(minimum));
        });
        return expected;
    }

    private static int[] standardizedTraversal(
            final int[] word, final int start, final int direction,
            final List<Annotation> alphabetOrder,
            final int[] symbolCycle, final int[] symbolRank, final int[] firstSymbol) {
        final var targetByCycle = new int[alphabetOrder.size()];
        Arrays.fill(targetByCycle, -1);
        final var targetUsed = new boolean[alphabetOrder.size()];
        final var rankOrigin = new int[alphabetOrder.size()];
        final var result = new int[word.length];
        for (var offset = 0; offset < word.length; offset++) {
            final var bead = word[Math.floorMod(start + direction * offset, word.length)];
            final var cycle = symbolCycle[bead];
            final var type = alphabetOrder.get(cycle);
            if (targetByCycle[cycle] == -1) {
                for (var target = 0; target < alphabetOrder.size(); target++) {
                    if (!targetUsed[target] && alphabetOrder.get(target).equals(type)) {
                        targetByCycle[cycle] = target;
                        targetUsed[target] = true;
                        break;
                    }
                }
                rankOrigin[cycle] = symbolRank[bead];
            }
            // A reflected traversal also inverts ranks before choosing origins.
            final var rank = Math.floorMod(
                    direction * (symbolRank[bead] - rankOrigin[cycle]), type.size());
            result[offset] = firstSymbol[targetByCycle[cycle]] + rank;
        }
        return result;
    }

    private static void forEachMultisetWord(final int[] remaining,
                                            final int[] word,
                                            final int position,
                                            final Consumer<int[]> consumer) {
        if (position == word.length) {
            consumer.accept(word);
            return;
        }
        for (var symbol = 0; symbol < remaining.length; symbol++) {
            if (remaining[symbol] > 0) {
                remaining[symbol]--;
                word[position] = symbol;
                forEachMultisetWord(remaining, word, position + 1, consumer);
                remaining[symbol]++;
            }
        }
    }

    private static Set<String> generatedCanonicals(final int[] partition,
                                                   final boolean[] orientedByPart) {
        final Set<String> generated = new HashSet<>();
        final int[] emitted = {0};
        DirectTwistedBraceletGenerator.generateRepresentatives(
                partition, orientedByPart, pair -> {
                    emitted[0]++;
                    generated.add(classKey(pair));
                });
        assertEquals(emitted[0], generated.size(),
                "The generator emitted duplicate extended-toric classes");
        return generated;
    }

    private static Set<String> bruteForceCanonicals(final int n,
                                                    final int[] partition,
                                                    final boolean[] orientedByPart) {
        final Set<String> expected = new HashSet<>();
        final var target = canonicalCycle(n);

        forEachNCycle(n, current -> {
            final var omega = PermutationGroups.computeProduct(
                    false, target, current.getInverse());
            if (omega.getNumberOfSymbols() != n) {
                return;
            }

            final var candidate = new CyclicTargetCandidate(omega, current);
            if (hasAnnotatedType(candidate, partition, orientedByPart)) {
                expected.add(classKey(candidate));
            }
        });
        return expected;
    }

    private static boolean hasAnnotatedType(final CyclicTargetCandidate candidate,
                                            final int[] partition,
                                            final boolean[] orientedByPart) {
        final var expected = annotations(partition, orientedByPart);
        return expected.equals(actualAnnotations(candidate));
    }

    private static List<Annotation> actualAnnotations(final CyclicTargetCandidate candidate) {
        final var word = candidate.standardizedEncodingWord();
        var maximumLabel = 0;
        for (var position = 0; position < word.length(); position++) {
            maximumLabel = Math.max(maximumLabel, word.cycleLabelAt(position));
        }

        final var sizes = new int[maximumLabel + 1];
        final var oriented = new boolean[maximumLabel + 1];
        for (var position = 0; position < word.length(); position++) {
            final var label = word.cycleLabelAt(position);
            sizes[label]++;
            oriented[label] |= word.rankAt(position) != 0;
        }

        final List<Annotation> actual = new ArrayList<>();
        for (var label = 1; label <= maximumLabel; label++) {
            if (sizes[label] != 0) {
                actual.add(new Annotation(sizes[label], oriented[label]));
            }
        }
        actual.sort(Annotation.ORDER);
        return List.copyOf(actual);
    }

    private static List<Annotation> annotations(final int[] partition,
                                                final boolean[] orientedByPart) {
        final List<Annotation> result = new ArrayList<>();
        for (var index = 0; index < partition.length; index++) {
            result.add(new Annotation(partition[index], orientedByPart[index]));
        }
        result.sort(Annotation.ORDER);
        return result;
    }

    private static String classKey(final CyclicTargetCandidate candidate) {
        return candidate.twistedBraceletKey().toString();
    }

    private static Cycle canonicalCycle(final int size) {
        final var symbols = new int[size];
        for (var index = 0; index < size; index++) {
            symbols[index] = index;
        }
        return Cycle.of(symbols);
    }

    private static void forEachNCycle(final int size,
                                      final Consumer<Cycle> consumer) {
        final var suffix = new int[size - 1];
        for (var index = 1; index < size; index++) {
            suffix[index - 1] = index;
        }
        permuteSuffix(suffix, 0, consumer);
    }

    private static void permuteSuffix(final int[] suffix,
                                      final int index,
                                      final Consumer<Cycle> consumer) {
        if (index == suffix.length) {
            final var symbols = new int[suffix.length + 1];
            symbols[0] = 0;
            System.arraycopy(suffix, 0, symbols, 1, suffix.length);
            consumer.accept(Cycle.of(symbols));
            return;
        }

        for (var candidate = index; candidate < suffix.length; candidate++) {
            swap(suffix, index, candidate);
            permuteSuffix(suffix, index + 1, consumer);
            swap(suffix, index, candidate);
        }
    }

    private static void swap(final int[] values, final int left, final int right) {
        final var value = values[left];
        values[left] = values[right];
        values[right] = value;
    }

    private record Annotation(int size, boolean oriented) {
        private static final java.util.Comparator<Annotation> ORDER = java.util.Comparator
                .comparingInt(Annotation::size)
                .reversed()
                .thenComparing(Annotation::oriented);
    }
}
