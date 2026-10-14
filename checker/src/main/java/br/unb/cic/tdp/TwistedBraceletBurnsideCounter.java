package br.unb.cic.tdp;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Exact Burnside counting on algebraic permutations, after quotienting the
 * auxiliary word action. The contracted domain is encoded in {@code int}
 * bitmasks, so counting is exact for domains of at most 30 symbols.
 */
final class TwistedBraceletBurnsideCounter {
    private static final Map<Integer, int[][]> SUPPORTS = new ConcurrentHashMap<>();
    private static final Map<String, Map<Long, Long>> FIXED_SETS = new ConcurrentHashMap<>();

    private TwistedBraceletBurnsideCounter() { }

    static long count(final int[] partition, final boolean[] oriented,
                      final ExecutorService executor) {
        final var n = Arrays.stream(partition).sum();
        if (n > 30) {
            throw new IllegalArgumentException("Burnside counting supports at most 30 symbols");
        }
        if (Arrays.stream(partition).filter(k -> (k & 1) == 0).count() % 2 != 0) {
            return 0;
        }
        final var identity = identityFixed(partition, oriented, executor);
        if (identity.signum() == 0) {
            return 0;
        }
        final var key = annotatedKey(partition, oriented);
        var sum = identity;
        for (var d = 1; d < n; d++) {
            if (n % d == 0) {
                final var fixed = rotationFixed(n, d, executor).getOrDefault(key, 0L);
                sum = sum.add(BigInteger.valueOf(fixed).multiply(BigInteger.valueOf(totient(n / d))));
            }
        }
        final var firstReflection = reflectionFixed(n, 0, executor).getOrDefault(key, 0L);
        if ((n & 1) != 0) {
            sum = sum.add(BigInteger.valueOf(firstReflection).multiply(BigInteger.valueOf(n)));
        } else {
            final var secondReflection = reflectionFixed(n, 1, executor).getOrDefault(key, 0L);
            sum = sum.add(BigInteger.valueOf(firstReflection + secondReflection)
                    .multiply(BigInteger.valueOf(n / 2)));
        }
        return exactDivide(sum, BigInteger.valueOf(2L * n)).longValueExact();
    }

    static BigInteger identityFixed(final int[] partition, final boolean[] oriented,
                                    final ExecutorService executor) {
        final var n = Arrays.stream(partition).sum();
        var result = BigInteger.ZERO;
        for (final var term : terms(partition, oriented)) {
            final var moment = forcedMoment(n, term.forced, term.remaining, executor);
            result = result.add(BigInteger.valueOf(moment).multiply(term.weight));
        }
        if (result.signum() < 0) {
            throw new IllegalStateException("Negative annotated identity fixed-set count");
        }
        return result;
    }

    private record Term(int[] forced, int[] remaining, BigInteger weight) { }

    private static List<Term> terms(final int[] partition, final boolean[] oriented) {
        final var n = Arrays.stream(partition).sum();
        final var multiplicities = new int[n + 1];
        final var unoriented = new int[n + 1];
        for (var i = 0; i < partition.length; i++) {
            multiplicities[partition[i]]++;
            if (!oriented[i]) {
                unoriented[partition[i]]++;
            }
        }
        final var result = new ArrayList<Term>();
        collectTerms(n, multiplicities, unoriented, n, new int[n + 1],
                BigInteger.ONE, result);
        return result;
    }

    private static void collectTerms(final int n, final int[] multiplicities,
                                     final int[] unoriented, final int length,
                                     final int[] forced, final BigInteger weight,
                                     final List<Term> result) {
        if (length < 2) {
            final var selected = new ArrayList<Integer>();
            final var remaining = new ArrayList<Integer>();
            for (var k = n; k >= 2; k--) {
                for (var j = 0; j < forced[k]; j++) {
                    selected.add(k);
                }
                for (var j = forced[k]; j < multiplicities[k]; j++) {
                    remaining.add(k);
                }
            }
            result.add(new Term(selected.stream().mapToInt(Integer::intValue).toArray(),
                    remaining.stream().mapToInt(Integer::intValue).toArray(), weight));
            return;
        }
        // An unoriented support cannot contain neighbours in the current
        // cycle: they would force a fixed point of the target. In particular
        // its length cannot exceed floor(n/2).
        final var maximum = length > n / 2 ? 0 : multiplicities[length];
        for (var r = unoriented[length]; r <= maximum; r++) {
            forced[length] = r;
            var nextWeight = weight.multiply(binomial(r, unoriented[length]));
            if (((r - unoriented[length]) & 1) != 0) {
                nextWeight = nextWeight.negate();
            }
            collectTerms(n, multiplicities, unoriented, length - 1, forced, nextWeight, result);
        }
    }

    private static long forcedMoment(final int n, final int[] forced, final int[] remaining,
                                     final ExecutorService executor) {
        final var kernel = new CompletionKernel(remaining);
        if (forced.length == 0) {
            return kernel.count(partitionKey(new int[]{n}));
        }
        final var supports = SUPPORTS.computeIfAbsent(n, TwistedBraceletBurnsideCounter::independentSupports);
        final var tasks = new ArrayList<Callable<Long>>();
        for (final var mask : supports[forced[0]]) {
            tasks.add(() -> {
                final var counter = new SupportCounter(n, forced, supports, kernel);
                return counter.selectFirst(mask);
            });
        }
        var total = 0L;
        for (final var value : parallel(tasks, executor)) {
            total = Math.addExact(total, value);
        }
        return total;
    }

    private static int[][] independentSupports(final int n) {
        final List<List<Integer>> lists = new ArrayList<>();
        for (var k = 0; k <= n; k++) {
            lists.add(new ArrayList<>());
        }
        for (var mask = 1; mask < 1 << n; mask++) {
            if ((mask & ((mask << 1) | (mask >>> (n - 1)))) == 0) {
                lists.get(Integer.bitCount(mask)).add(mask);
            }
        }
        return lists.stream().map(list -> list.stream().mapToInt(Integer::intValue).toArray())
                .toArray(int[][]::new);
    }

    private static final class SupportCounter {
        private final int n;
        private final int[] forced;
        private final int[][] supports;
        private final CompletionKernel kernel;
        private final int[] omega;
        private final int[] parent;
        private final int[] size;
        private final int[] childTrail;
        private final int[] vertexTrail;
        private final int[] phi;
        private final int[] cycleLengths;
        private int edges;

        private SupportCounter(final int n, final int[] forced, final int[][] supports,
                               final CompletionKernel kernel) {
            this.n = n;
            this.forced = forced;
            this.supports = supports;
            this.kernel = kernel;
            omega = new int[n];
            parent = new int[n];
            size = new int[n];
            childTrail = new int[n];
            vertexTrail = new int[n];
            phi = new int[n];
            cycleLengths = new int[n];
            for (var v = 0; v < n; v++) {
                parent[v] = v;
                size[v] = 1;
            }
        }

        private long selectFirst(final int mask) {
            return addCycle(mask) ? recurse(1, ((1 << n) - 1) ^ mask, mask) : 0;
        }

        private long recurse(final int index, final int available, final int previousMask) {
            if (index == forced.length) {
                return complete(available);
            }
            var total = 0L;
            for (final var mask : supports[forced[index]]) {
                if ((mask & available) != mask
                        || (forced[index] == forced[index - 1] && mask <= previousMask)) {
                    continue;
                }
                final var before = edges;
                if (addCycle(mask)) {
                    total = Math.addExact(total, recurse(index + 1, available ^ mask, mask));
                }
                rollback(before);
            }
            return total;
        }

        private boolean addCycle(final int mask) {
            var previous = 31 - Integer.numberOfLeadingZeros(mask);
            for (var bits = mask; bits != 0; bits &= bits - 1) {
                final var vertex = Integer.numberOfTrailingZeros(bits);
                final var source = vertex == 0 ? n - 1 : vertex - 1;
                var first = root(source);
                var second = root(previous);
                if (first == second && edges + 1 < n) {
                    return false;
                }
                var child = -1;
                if (first != second) {
                    if (size[first] < size[second]) {
                        final var temporary = first;
                        first = second;
                        second = temporary;
                    }
                    child = second;
                    parent[child] = first;
                    size[first] += size[child];
                }
                childTrail[edges] = child;
                vertexTrail[edges++] = vertex;
                omega[vertex] = previous;
                previous = vertex;
            }
            return true;
        }

        private int root(final int vertex) {
            var current = vertex;
            while (parent[current] != current) {
                current = parent[current];
            }
            return current;
        }

        private void rollback(final int before) {
            while (edges > before) {
                final var child = childTrail[--edges];
                if (child >= 0) {
                    size[parent[child]] -= size[child];
                    parent[child] = child;
                }
                omega[vertexTrail[edges]] = 0;
            }
        }

        private long complete(final int available) {
            if (available == 0) {
                return 1; // Only the final arc was allowed to close a cycle.
            }
            for (var bits = available; bits != 0; bits &= bits - 1) {
                final var vertex = Integer.numberOfTrailingZeros(bits);
                var next = (vertex + 1) % n;
                while ((available & (1 << next)) == 0) {
                    next = (omega[next] + 1) % n;
                }
                phi[vertex] = next;
            }
            var unvisited = available;
            var cycles = 0;
            while (unvisited != 0) {
                final var first = Integer.numberOfTrailingZeros(unvisited);
                var current = first;
                var length = 0;
                do {
                    unvisited ^= 1 << current;
                    length++;
                    current = phi[current];
                } while (current != first);
                cycleLengths[cycles++] = length;
            }
            Arrays.sort(cycleLengths, 0, cycles);
            var key = 0L;
            var singletons = 0;
            for (var i = 0; i < cycles; i++) {
                if (cycleLengths[i] == 1) {
                    singletons++;
                } else {
                    key = (key << 5) | cycleLengths[i];
                }
            }
            return kernel.count((key << 5) | singletons);
        }
    }

    /** Counts completions sigma with cycle type lambda for which sigma*phi is a full cycle. */
    private static final class CompletionKernel {
        private final int n;
        private final long[] characters;
        private final BigInteger classSize;
        private final BigInteger denominator;
        private final BigInteger[] scaledReciprocals;
        private final Map<Long, Long> counts = new ConcurrentHashMap<>();

        private CompletionKernel(final int[] lambda) {
            n = Arrays.stream(lambda).sum();
            characters = hookCharacters(lambda);
            var centralizer = BigInteger.ONE;
            final var repetitions = new HashMap<Integer, Integer>();
            for (final var k : lambda) {
                final var occurrence = repetitions.merge(k, 1, Integer::sum);
                centralizer = centralizer.multiply(BigInteger.valueOf((long) k * occurrence));
            }
            classSize = factorial(n).divide(centralizer);
            var common = BigInteger.ONE;
            for (var j = 0; j < n; j++) {
                final var degree = binomial(n - 1, j);
                common = common.divide(common.gcd(degree)).multiply(degree);
            }
            denominator = common.multiply(BigInteger.valueOf(n));
            scaledReciprocals = new BigInteger[n];
            for (var j = 0; j < n; j++) {
                scaledReciprocals[j] = common.divide(binomial(n - 1, j));
            }
        }

        private long count(final long mu) {
            return counts.computeIfAbsent(mu, key -> {
                final var other = hookCharacters(decodePartition(key));
                var numerator = BigInteger.ZERO;
                for (var j = 0; j < n; j++) {
                    var product = BigInteger.valueOf(characters[j]).multiply(BigInteger.valueOf(other[j]))
                            .multiply(scaledReciprocals[j]);
                    if ((j & 1) != 0) {
                        product = product.negate();
                    }
                    numerator = numerator.add(product);
                }
                return exactDivide(classSize.multiply(numerator), denominator).longValueExact();
            });
        }
    }

    private static long[] hookCharacters(final int[] partition) {
        final var n = Arrays.stream(partition).sum();
        final var numerator = new long[n + 1];
        numerator[0] = 1;
        var degree = 0;
        for (final var k : partition) {
            final var sign = (k & 1) == 0 ? -1 : 1;
            for (var j = degree; j >= 0; j--) {
                numerator[j + k] += sign * numerator[j];
            }
            degree += k;
        }
        final var result = new long[n];
        for (var j = 0; j < n; j++) {
            result[j] = numerator[j] - (j == 0 ? 0 : result[j - 1]);
        }
        return result;
    }

    static Map<Long, Long> rotationFixed(final int n, final int d, final ExecutorService executor) {
        return FIXED_SETS.computeIfAbsent("r:" + n + ":" + d,
                ignored -> countRotations(n, d, executor));
    }

    private static Map<Long, Long> countRotations(final int n, final int d,
                                                final ExecutorService executor) {
        final var tasks = new ArrayList<Callable<LongCounts>>();
        final var order = new int[d];
        order[0] = 0;
        rotationFrontier(n, d, order, 1, 1, Math.min(d, 4), tasks);
        return merge(parallel(tasks, executor));
    }

    private static void rotationFrontier(final int n, final int d, final int[] order,
                                          final int placed, final int used, final int depth,
                                          final List<Callable<LongCounts>> tasks) {
        if (placed == depth) {
            final var prefix = order.clone();
            tasks.add(() -> new RotationCounter(n, d, prefix).orders(placed, used));
            return;
        }
        for (var v = 1; v < d; v++) {
            if ((used & (1 << v)) == 0) {
                order[placed] = v;
                rotationFrontier(n, d, order, placed + 1, used | (1 << v), depth, tasks);
            }
        }
    }

    private static final class RotationCounter {
        private final int n;
        private final int d;
        private final int length;
        private final int[] order;
        private final int[] quotient;
        private final int[] phase;
        private final int[] omega;
        private final int[] codes;
        private final LongCounts counts = new LongCounts();

        private RotationCounter(final int n, final int d, final int[] order) {
            this.n = n;
            this.d = d;
            this.length = n / d;
            this.order = order;
            quotient = new int[d];
            phase = new int[d];
            omega = new int[n];
            codes = new int[n];
        }

        private LongCounts orders(final int placed, final int used) {
            if (placed == d) {
                for (var i = 0; i < d; i++) {
                    quotient[order[i]] = order[(i + 1) % d];
                }
                phases(0, 0);
            } else {
                for (var v = 1; v < d; v++) {
                    if ((used & (1 << v)) == 0) {
                        order[placed] = v;
                        orders(placed + 1, used | (1 << v));
                    }
                }
            }
            return counts;
        }

        private void phases(final int index, final int sum) {
            for (var t = 0; t < length; t++) {
                phase[index] = t;
                if (index + 1 < d) {
                    phases(index + 1, sum + t);
                } else if (gcd(sum + t, length) == 1) {
                    // rho is a full cycle in the centralizer of beta^d.
                    // omega = rho*beta^{-1} retains the same rotational symmetry.
                    for (var v = 0; v < n; v++) {
                        final var previous = v == 0 ? n - 1 : v - 1;
                        final var residue = previous % d;
                        omega[v] = quotient[residue]
                                + d * ((previous / d + phase[residue]) % length);
                    }
                    counts.increment(classify(omega, codes));
                }
            }
        }
    }

    static Map<Long, Long> reflectionFixed(final int n, final int shift,
                                         final ExecutorService executor) {
        return FIXED_SETS.computeIfAbsent("s:" + n + ":" + shift,
                ignored -> countReflections(n, shift, executor));
    }

    private static Map<Long, Long> countReflections(final int n, final int shift,
                                                  final ExecutorService executor) {
        final var f = new int[n];
        final var first = new ArrayList<Integer>();
        final var second = new ArrayList<Integer>();
        final var endpoints = new ArrayList<Integer>();
        for (var v = 0; v < n; v++) {
            f[v] = Math.floorMod(shift - v, n);
            final var h = Math.floorMod(shift - 1 - v, n); // h = f*beta
            if (v < h) {
                first.add(v);
                second.add(h);
            } else if (v == h) {
                endpoints.add(v);
            }
        }
        final var pairs = new int[first.size()][2];
        for (var i = 0; i < pairs.length; i++) {
            pairs[i] = new int[]{first.get(i), second.get(i)};
        }
        final var start = endpoints.isEmpty() ? -1 : endpoints.getFirst();
        final var end = endpoints.size() == 2 ? endpoints.getLast() : -1;
        final var tasks = new ArrayList<Callable<LongCounts>>();
        reflectionFrontier(f, pairs, end, new int[n], 0, start, -1,
                Math.min(2, pairs.length), tasks);
        return merge(parallel(tasks, executor));
    }

    private static void reflectionFrontier(final int[] f, final int[][] pairs, final int end,
                                            final int[] q, final int used, final int last,
                                            final int first, final int depth,
                                            final List<Callable<LongCounts>> tasks) {
        if (Integer.bitCount(used) == depth) {
            final var prefix = q.clone();
            tasks.add(() -> new ReflectionCounter(f, pairs, end, prefix).paths(used, last, first));
            return;
        }
        for (var i = 0; i < pairs.length; i++) {
            if ((used & (1 << i)) != 0) {
                continue;
            }
            for (var direction = 0; direction < 2; direction++) {
                final var entry = pairs[i][direction];
                final var exit = pairs[i][1 - direction];
                q[entry] = last < 0 ? entry : last;
                if (last >= 0) {
                    q[last] = entry;
                }
                reflectionFrontier(f, pairs, end, q, used | (1 << i), exit,
                        last < 0 ? entry : first, depth, tasks);
            }
        }
    }

    private static final class ReflectionCounter {
        private final int[] f;
        private final int[][] pairs;
        private final int end;
        private final int[] q;
        private final int[] omega;
        private final int[] codes;
        private final LongCounts counts = new LongCounts();

        private ReflectionCounter(final int[] f, final int[][] pairs, final int end, final int[] q) {
            this.f = f;
            this.pairs = pairs;
            this.end = end;
            this.q = q;
            omega = new int[f.length];
            codes = new int[f.length];
        }

        private LongCounts paths(final int used, final int last, final int first) {
            if (Integer.bitCount(used) == pairs.length) {
                // With no h-fixed endpoint, reverse traversals describe the
                // same q. Select exactly one of them.
                if (first >= 0 && first > last) {
                    return counts;
                }
                q[last] = end < 0 ? last : end;
                if (end >= 0) {
                    q[end] = last;
                }
                // q and h form one alternating path, hence q*h is a full
                // cycle. omega=q*f is fixed by reflected inversion.
                for (var v = 0; v < f.length; v++) {
                    omega[v] = q[f[v]];
                }
                counts.increment(classify(omega, codes));
                return counts;
            }
            for (var i = 0; i < pairs.length; i++) {
                if ((used & (1 << i)) != 0) {
                    continue;
                }
                for (var direction = 0; direction < 2; direction++) {
                    final var entry = pairs[i][direction];
                    final var exit = pairs[i][1 - direction];
                    q[entry] = last < 0 ? entry : last;
                    if (last >= 0) {
                        q[last] = entry;
                    }
                    paths(used | (1 << i), exit, last < 0 ? entry : first);
                }
            }
            return counts;
        }
    }

    static long annotatedKey(final int[] partition, final boolean[] oriented) {
        final var codes = new int[partition.length];
        for (var i = 0; i < codes.length; i++) {
            codes[i] = 2 * partition[i] + (oriented[i] ? 1 : 0);
        }
        Arrays.sort(codes);
        var key = 0L;
        for (final var code : codes) {
            key = (key << 6) | code;
        }
        return key;
    }

    private static long classify(final int[] omega, final int[] codes) {
        var unseen = (1 << omega.length) - 1;
        var cycles = 0;
        while (unseen != 0) {
            final var first = Integer.numberOfTrailingZeros(unseen);
            var v = first;
            var length = 0;
            var oriented = false;
            do {
                unseen ^= 1 << v;
                final var next = omega[v];
                oriented |= v != first && next > v;
                v = next;
                length++;
            } while (v != first);
            if (length == 1) {
                return 0;
            }
            final var code = 2 * length + (oriented ? 1 : 0);
            var index = cycles++;
            while (index > 0 && codes[index - 1] > code) {
                codes[index] = codes[index - 1];
                index--;
            }
            codes[index] = code;
        }
        var key = 0L;
        for (var i = 0; i < cycles; i++) {
            key = (key << 6) | codes[i];
        }
        return key;
    }

    /** Primitive accumulation avoids one allocation per fixed permutation. */
    private static final class LongCounts {
        private final long[] keys = new long[2048];
        private final long[] values = new long[2048];

        private void increment(final long key) {
            if (key == 0) {
                return;
            }
            var index = (int) ((key ^ (key >>> 32)) * 0x9E3779B9L) & (keys.length - 1);
            while (keys[index] != 0 && keys[index] != key) {
                index = (index + 1) & (keys.length - 1);
            }
            keys[index] = key;
            values[index]++;
        }
    }

    private static Map<Long, Long> merge(final List<LongCounts> results) {
        final var merged = new HashMap<Long, Long>();
        for (final var result : results) {
            for (var i = 0; i < result.keys.length; i++) {
                if (result.keys[i] != 0) {
                    merged.merge(result.keys[i], result.values[i], Math::addExact);
                }
            }
        }
        return Map.copyOf(merged);
    }

    private static <T> List<T> parallel(final List<Callable<T>> tasks, final ExecutorService executor) {
        try {
            final var futures = executor.invokeAll(tasks);
            final var result = new ArrayList<T>(futures.size());
            for (final var future : futures) {
                result.add(future.get());
            }
            return result;
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while counting Burnside fixed sets", exception);
        } catch (final java.util.concurrent.ExecutionException exception) {
            throw new RuntimeException("Burnside fixed-set counting failed", exception.getCause());
        }
    }

    private static long partitionKey(final int[] partition) {
        final var sorted = partition.clone();
        Arrays.sort(sorted);
        var key = 0L;
        var singletons = 0;
        for (final var k : sorted) {
            if (k == 1) {
                singletons++;
            } else {
                key = (key << 5) | k;
            }
        }
        return (key << 5) | singletons;
    }

    private static int[] decodePartition(long key) {
        final var values = new ArrayList<Integer>();
        final var singletons = (int) (key & 31);
        for (var i = 0; i < singletons; i++) {
            values.add(1);
        }
        key >>>= 5;
        while (key != 0) {
            values.add((int) (key & 31));
            key >>>= 5;
        }
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static BigInteger factorial(final int n) {
        var result = BigInteger.ONE;
        for (var k = 2; k <= n; k++) {
            result = result.multiply(BigInteger.valueOf(k));
        }
        return result;
    }

    private static BigInteger binomial(final int n, final int k) {
        var result = BigInteger.ONE;
        for (var j = 1; j <= k; j++) {
            result = result.multiply(BigInteger.valueOf(n - j + 1)).divide(BigInteger.valueOf(j));
        }
        return result;
    }

    private static BigInteger exactDivide(final BigInteger numerator, final BigInteger denominator) {
        final var result = numerator.divideAndRemainder(denominator);
        if (result[1].signum() != 0) {
            throw new IllegalStateException("Nonintegral Burnside/character count");
        }
        return result[0];
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            final var remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    private static int totient(final int n) {
        var result = 0;
        for (var k = 1; k <= n; k++) {
            if (gcd(k, n) == 1) {
                result++;
            }
        }
        return result;
    }
}
