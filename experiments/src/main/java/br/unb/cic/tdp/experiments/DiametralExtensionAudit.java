package br.unb.cic.tdp.experiments;

import java.util.*;

// Small-degree exhaustive reference experiment, not the large-degree solver.
// Shared reference implementation loaded by the JShell experiment runners.
// Optional: -R-Daudit.maxN=10 (default: 9; supported range: 2..10).
public final class DiametralExtensionAudit {
    static final int[] FACT = {1, 1, 2, 6, 24, 120, 720, 5040, 40320, 362880, 3628800};
    static final int[][] EXPECTED = {
        {}, {1}, {1,1}, {1,4,1}, {1,10,12,1}, {1,20,68,31},
        {1,35,259,380,45}, {1,56,770,2700,1513},
        {1,84,1932,13467,22000,2836},
        {1,120,4284,52512,191636,114327},
        {1,165,8646,170907,1183457,2010571,255053}
    };

    // One nibble per entry; all experiments below have n <= 10.
    static long mask(int entries) { return (1L << (4 * entries)) - 1; }

    static int rank(long word, int n) {
        int result = 0, used = 0;
        for (int i = 0; i < n; i++) {
            int v = (int)(word >>> (4 * i)) & 15;
            int smallerUnused = v - 1 - Integer.bitCount(used & ((1 << (v - 1)) - 1));
            result = result * (n - i) + smallerUnused;
            used |= 1 << (v - 1);
        }
        return result;
    }

    static long unrank(int rank, int n) {
        long word = 0;
        int available = (1 << n) - 1;
        for (int i = 0; i < n; i++) {
            int index = rank / FACT[n - 1 - i];
            rank %= FACT[n - 1 - i];
            int remaining = available;
            for (int j = 0; j < index; j++) remaining &= remaining - 1;
            int bit = Integer.numberOfTrailingZeros(remaining);
            word |= (long)(bit + 1) << (4 * i);
            available &= ~(1 << bit);
        }
        return word;
    }

    record Move(int a, int b, int c) {
        long apply(long word) {
            return (word & mask(a))
                | (((word >>> (4 * b)) & mask(c - b)) << (4 * a))
                | (((word >>> (4 * a)) & mask(b - a)) << (4 * (a + c - b)))
                | (word & ~mask(c));
        }
    }

    // Independent BFS uses only block transpositions, never deletion lemmas.
    static byte[] bfs(int n) {
        var moves = new ArrayList<Move>();
        for (int a = 0; a < n; a++)
            for (int b = a + 1; b < n; b++)
                for (int c = b + 1; c <= n; c++) moves.add(new Move(a,b,c));
        byte[] distance = new byte[FACT[n]];
        Arrays.fill(distance, (byte)-1);
        int[] queue = new int[FACT[n]];
        int head = 0, tail = 1;
        distance[0] = 0; // identity has Lehmer rank zero
        while (head < tail) {
            int currentRank = queue[head++];
            long word = unrank(currentRank, n);
            for (Move move : moves) {
                int nextRank = rank(move.apply(word), n);
                if (distance[nextRank] < 0) {
                    distance[nextRank] = (byte)(distance[currentRank] + 1);
                    queue[tail++] = nextRank;
                }
            }
        }
        if (tail != FACT[n]) throw new AssertionError("BFS incomplete at n=" + n);
        return distance;
    }

    static long insertMaximum(long parent, int n, int position) {
        return (parent & mask(position)) | ((long)n << (4 * position))
            | ((parent & ~mask(position)) << 4);
    }

    static long deletePosition(long word, int n, int position) {
        int deleted = (int)(word >>> (4 * position)) & 15;
        long result = 0;
        for (int i = 0, j = 0; i < n; i++) {
            if (i == position) continue;
            int v = (int)(word >>> (4 * i)) & 15;
            result |= (long)(v > deleted ? v - 1 : v) << (4 * j++);
        }
        return result;
    }

    // Exact threshold recurrence, T(n,k) = {p : d(p) >= k}.
    // BFS is the independent exact CanSort oracle in this SMALL audit only.
    static BitSet extend(byte[] previous, int n, int k, byte[] oracle) {
        BitSet result = new BitSet(FACT[n]);
        for (int parentRank = 0; parentRank < previous.length; parentRank++) {
            if (previous[parentRank] < k - 1) continue;
            long parent = unrank(parentRank, n - 1);
            for (int position = 0; position < n; position++) {
                long word = insertMaximum(parent, n, position);
                boolean possible = true, inherited = false;
                for (int i = 0; i < n; i++) {
                    int d = previous[rank(deletePosition(word, n, i), n - 1)];
                    if (d < k - 1) { possible = false; break; }
                    if (d >= k) inherited = true;
                }
                if (!possible) continue;
                int wordRank = rank(word, n);
                if (inherited || oracle[wordRank] > k - 1) result.set(wordRank);
            }
        }
        return result;
    }

    static int diameter(byte[] distances) {
        int result = 0;
        for (byte d : distances) result = Math.max(result, d);
        return result;
    }

    static void run(int maxN) {
        if (maxN < 2 || maxN > 10) throw new IllegalArgumentException("audit.maxN must be 2..10");
        long start = System.nanoTime();
        byte[] previous = {0};
        System.out.println("n,TD,diametrals,maxInsertionCandidates,allDeletionSurvivors,inherited,primitive,seconds");
        for (int n = 2; n <= maxN; n++) {
            long stepStart = System.nanoTime();
            byte[] exact = bfs(n);
            int r = diameter(exact), oldR = diameter(previous);
            int[] histogram = new int[r + 1];
            for (byte d : exact) histogram[d]++;
            if (!Arrays.equals(histogram, EXPECTED[n]))
                throw new AssertionError("Histogram mismatch at n=" + n + ": " + Arrays.toString(histogram));

            // Compare complete generated sets, not just their cardinalities.
            for (int k = 1; k <= r + 1; k++) {
                BitSet generated = extend(previous, n, k, exact);
                for (int id = 0; id < exact.length; id++)
                    if (generated.get(id) != (exact[id] >= k))
                        throw new AssertionError("Extension mismatch at n=" + n + ", k=" + k + ", rank=" + id);
            }

            int inherited = 0, primitive = 0, survivors = 0, candidates = 0;
            for (byte d : previous) if (d >= r - 1) candidates += n;
            for (int id = 0; id < exact.length; id++) {
                long word = unrank(id, n);
                int min = Integer.MAX_VALUE, max = -1;
                for (int i = 0; i < n; i++) {
                    int d = previous[rank(deletePosition(word, n, i), n - 1)];
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                }
                if (!(max <= exact[id] && exact[id] <= min + 1))
                    throw new AssertionError("Deletion bound fails at n=" + n + ", rank=" + id);
                if (min >= r - 1) survivors++;
                if (exact[id] == r) {
                    if (max == r) inherited++; else primitive++;
                    if (r == oldR + 1 && min != oldR)
                        throw new AssertionError("Jump condition fails at n=" + n);
                }
            }
            System.out.printf(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d,%.3f%n", n,r,histogram[r],
                candidates,survivors,inherited,primitive,(System.nanoTime()-stepStart)/1e9);
            previous = exact;
        }
        System.out.printf(Locale.ROOT,
            "PASS: all histograms, all threshold sets, and every deletion bound through n=%d (%.3f s).%n",
            maxN,(System.nanoTime()-start)/1e9);
    }
}
