package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import br.unb.cic.tdp.permutation.PermutationGroups;
import cern.colt.list.FloatArrayList;
import cern.colt.list.IntArrayList;
import lombok.val;

import java.io.Serializable;
import java.util.*;

import static br.unb.cic.tdp.permutation.PermutationGroups.computeProduct;

public class CommonOperations implements Serializable {

    public static final Cycle[] CANONICAL_BETA;

    static {
        CANONICAL_BETA = new Cycle[2000];
        for (var i = 1; i < 2000; i++) {
            val beta = new int[i];
            for (var j = 0; j < i; j++) {
                beta[j] = j;
            }
            CANONICAL_BETA[i] = Cycle.of(beta);
        }
    }

    /**
     * Assumes \rho=(0,1,2,...,n).
     */
    public static Cycle simplify(Cycle beta) {
        var betaPrime = new FloatArrayList();
        for (var i = 0; i < beta.getSymbols().length; i++) {
            betaPrime.add(beta.getSymbols()[i]);
        }

        var rho = new IntArrayList();
        for (var i = 0; i < betaPrime.size(); i++) {
            rho.add(i);
        }

        var rhoBetaInverse = computeProduct(Cycle.of(rho), beta.getInverse());

        Cycle bigCycle;
        while ((bigCycle = rhoBetaInverse.stream().filter(c -> c.size() > 3).findFirst().orElse(null)) != null) {
            val leftMostSymbol = leftMostSymbol(bigCycle, beta);
            val newSymbol = betaPrime.get(betaPrime.indexOf(leftMostSymbol) - 1) + 0.001F;
            betaPrime.beforeInsert(betaPrime.indexOf(bigCycle.pow(leftMostSymbol, -2)), newSymbol);

            val betaCopy = new FloatArrayList(Arrays.copyOf(betaPrime.elements(), betaPrime.size()));
            betaCopy.sort();

            val newBeta = new IntArrayList();
            for (var i = 0; i < betaCopy.size(); i++) {
                newBeta.add(betaCopy.indexOf(betaPrime.get(i)));
            }

            rho = new IntArrayList();
            for (var i = 0; i < newBeta.size(); i++) {
                rho.add(i);
            }

            rhoBetaInverse = computeProduct(Cycle.of(rho), Cycle.of(newBeta).getInverse());

            betaPrime = new FloatArrayList();
            for (var i = 0; i < newBeta.size(); i++) {
                betaPrime.add(newBeta.get(i));
            }
            beta = Cycle.of(newBeta);
        }

        return beta.startingBy(0);
    }

    private static int leftMostSymbol(final Cycle bigCycle, final Cycle beta) {
        for (var i = 1; i < beta.size(); i++)
            if (bigCycle.contains(beta.get(i)))
                return beta.get(i);
        return -1;
    }

    public static Cycle applyTranspositionOptimized(final Cycle beta, final Cycle move) {
        val a = move.get(0);
        val b = move.get(1);
        val c = move.get(2);

        val indexes = new int[3];
        for (var i = 0; i < beta.size(); i++) {
            if (beta.get(i) == a)
                indexes[0] = i;
            if (beta.get(i) == b)
                indexes[1] = i;
            if (beta.get(i) == c)
                indexes[2] = i;
        }

        Arrays.sort(indexes);

        val result = new int[beta.size()];
        System.arraycopy(beta.getSymbols(), 0, result, 0, indexes[0]);
        System.arraycopy(beta.getSymbols(), indexes[1], result, indexes[0], indexes[2] - indexes[1]);
        System.arraycopy(beta.getSymbols(), indexes[0], result, indexes[0] + (indexes[2] - indexes[1]), indexes[1] - indexes[0]);
        System.arraycopy(beta.getSymbols(), indexes[2], result, indexes[2], beta.size() - indexes[2]);

        return Cycle.of(result);
    }

    public static int mod(final int a, final int b) {
        var r = a % b;
        if (r < 0)
            r += b;
        return r;
    }

    /**
     * Creates an array where the cycles in <code>bigGamma</code> can be accessed by the symbols of <code>beta</code>
     * (being the indexes of the resulting array).
     */
    public static Cycle[] cycleIndex(final Collection<Cycle> bigGamma, final int[] beta) {
        return cyclesIndex(List.of(bigGamma), beta);
    }

    public static Cycle[] cyclesIndex(final List<Collection<Cycle>> components, final int[] beta) {
        val index = new Cycle[beta.length];

        components.forEach(component -> {
            for (val cycle : component) {
                for (final int symbol : cycle.getSymbols()) {
                    index[symbol] = cycle;
                }
            }
        });

        return index;
    }

    /**
     * Creates an array where the cycles in <code>bigGamma</code> can be accessed by the symbols of <code>beta</code>
     * (being the indexes of the resulting array).
     */
    public static Cycle[] cycleIndex(final Collection<Cycle> bigGamma, final Cycle beta) {
        return cyclesIndex(List.of(bigGamma), beta);
    }

    public static Cycle[] cyclesIndex(final List<Collection<Cycle>> components, final Cycle beta) {
        val index = new Cycle[beta.getMaxSymbol() + 1];

        components.forEach(component -> {
            for (val cycle : component) {
                for (final int symbol : cycle.getSymbols()) {
                    index[symbol] = cycle;
                }
            }
        });

        return index;
    }

    public static boolean areSymbolsInCyclicOrder(final Cycle cycle, int... symbols) {
        val symbolIndexes = cycle.getSymbolIndexes();

        var leap = false;
        for (int i = 0; i < symbols.length; i++) {
            if (symbolIndexes[symbols[i]] > symbolIndexes[symbols[(i + 1) % symbols.length]]) {
                if (!leap) {
                    leap = true;
                } else {
                    return false;
                }
            }
        }

        return true;
    }

    public static int d(int n) {
        return switch (n) {
            case 1 -> 0;
            case 2 -> 1;
            case 3 -> 2;
            case 4, 5 -> 3;
            case 6, 7 -> 4;
            case 8, 9 -> 5;
            case 10, 11 -> 6;
            case 12 -> 7;
            case 13, 14 -> 8;
            case 15 -> 9;
            default -> throw new IllegalArgumentException("TD not known");
        };
    }

    /**
     * Find a sorting sequence whose approximation ratio is at most <code>maxRatio</code>.
     */
    public static List<Cycle> searchForSortingSeq(final int[] beta, final MulticyclePermutation bigGamma, final Stack<Cycle> moves,
                                                  final int initialNumberOfEvenCycles, final float maxRatio, final int maxMoves) {
        val numberOfEvenCycles = bigGamma.getNumberOfEvenCycles();
        val lowerBound = Math.ceil((beta.length - numberOfEvenCycles) / 2.0);

        if (moves.size() + lowerBound > maxMoves) {
            return Collections.emptyList();
        }

        val minAchievableRatio = (float) (moves.size() + lowerBound) / (float) ((beta.length - initialNumberOfEvenCycles) / 2);

        // Do not allow it to exceed the max ratio
        if (minAchievableRatio <= maxRatio) {
            val delta = (numberOfEvenCycles - initialNumberOfEvenCycles);
            val instantRatio = delta > 0
                    ? (float) (moves.size() * 2) / (numberOfEvenCycles - initialNumberOfEvenCycles)
                    : 0;
            if (moves.size() >= 8 && instantRatio <= maxRatio) {
                return moves;
            } else {
                val ci = cycleIndex(bigGamma, beta);

                for (var i = 0; i < beta.length - 2; i++) {
                    val a = beta[i];
                    if (ci[a].size() == 1) continue;

                    for (var j = i + 1; j < beta.length - 1; j++) {
                        val b = beta[j];
                        if (ci[b].size() == 1) continue;

                        for (var k = j + 1; k < beta.length; k++) {
                            val c = beta[k];
                            if (ci[c].size() == 1) continue;

                            if (isMinusTwoMove(ci, a, b, c))
                                continue;

                            val d = getDelta(ci, a, b, c);

                            if (d == 0 || d == 2) {
                                val move = Cycle.of(a, b, c);
                                val bigGammaPrime = PermutationGroups.computeProduct(bigGamma, move.getInverse());
                                moves.push(move);
                                val sorting = searchForSortingSeq(apply(beta, i, j, k),
                                        bigGammaPrime, moves, initialNumberOfEvenCycles, maxRatio, maxMoves);
                                if (!sorting.isEmpty()) {
                                    return moves;
                                }
                                moves.pop();
                            }
                        }
                    }
                }
            }
        }

        return Collections.emptyList();
    }

    public static int[] apply(final int[] beta, final int i, final int j, final int k) {
        val result = Arrays.copyOf(beta, beta.length);

        System.arraycopy(beta, j, result, i, k - j);
        System.arraycopy(beta, i, result, i + (k - j), j - i);

        return result;
    }

    private static int getDelta(final Cycle[] ci, final int a, final int b, final int c) {
        Cycle cycle = ci[a], cb = ci[b], cc = ci[c];

        int delta, afterEven, beforeEven;

        // case when {ca,cb,cc} are two distinct cycles
        if (!((cycle == cb && cb == cc) || (cycle != cb && cb != cc && cycle != cc))) {
            // Determine which cycle appears twice
            Cycle doubleCycle;
            int sym1, sym2, singleSym;

            if (cycle == cb) {
                doubleCycle = cycle;
                sym1 = a;
                sym2 = b;
                singleSym = c;
            } else if (cycle == cc) {
                doubleCycle = cycle;
                sym1 = a;
                sym2 = c;
                singleSym = b;
            } else { // cb == cc
                doubleCycle = cb;
                sym1 = b;
                sym2 = c;
                singleSym = a;
            }

            val singleCycle = ci[singleSym];

            // Count the initial number of odd-length cycles
            beforeEven = (doubleCycle.isEven() ? 1 : 0) + (singleCycle.isEven() ? 1 : 0);

            // After applying (a b c)^{-1}, the double cycle splits into two parts
            // One part merges with the single cycle, the other remains separate
            // So we still have 2 cycles total

            // Calculate the distance splitSize between sym1 and sym2 in the double cycle
            val splitSize = doubleCycle.getK(sym1, sym2);

            // Determine which segment merges with singleCycle based on position of singleSym
            // The transposition (a b c)^{-1} determines which part connects
            int cycle1Size, cycle2Size;

            // Check if singleSym comes between sym1 and sym2 in the sequence a,b,c
            // ca == cc means sym1=a, sym2=c, singleSym=b (b comes between a and c)
            val singleSymBetween = (cycle == cc);

            if (singleSymBetween) {
                // The segment between sym1 and sym2 merges with singleCycle
                cycle1Size = splitSize + singleCycle.size();
                cycle2Size = doubleCycle.size() - splitSize;
            } else {
                // The segment NOT between sym1 and sym2 merges with singleCycle
                cycle1Size = (doubleCycle.size() - splitSize) + singleCycle.size();
                cycle2Size = splitSize;
            }

            // The parity of a cycle of size n is: even if n is odd, odd if n is even
            afterEven = ((cycle1Size % 2 == 1) ? 1 : 0) + ((cycle2Size % 2 == 1) ? 1 : 0);
        } else { // all are the same cycle (all are different was filtered before)
            // All three symbols are in the same cycle
            beforeEven = cycle.isEven() ? 1 : 0;

            // After applying (a b c)^{-1}, the cycle splits into 3 cycles
            int kab = cycle.getK(a, b);
            int kbc = cycle.getK(b, c);
            int kca = cycle.getK(c, a);

            // The three resulting cycles have sizes kab, kbc, and kca
            afterEven = ((kab % 2 == 1) ? 1 : 0) + ((kbc % 2 == 1) ? 1 : 0) + ((kca % 2 == 1) ? 1 : 0);
        }

        delta = afterEven - beforeEven;

        return delta;
    }

    private static boolean isMinusTwoMove(final Cycle[] ci, final int a, final int b, final int c) {
        // skip (-2)-moves
        return ci[a] != ci[b] && ci[b] != ci[c] && ci[a] != ci[c];
    }

    /**
     * Tells if a <code>cycle</code> is oriented or not having <code>beta</code> as reference.
     */
    public static boolean isOriented(final Cycle beta, final Cycle cycle) {
        return !areSymbolsInCyclicOrder(beta.getInverse(), cycle.getSymbols());
    }

    public static Set<Integer> getOpenGates(final Collection<Cycle> omega, final Cycle beta) {
        val openGates = new HashSet<Integer>();

        for (val epsilon : omega) {
            if (epsilon.size() == 1) continue;

            for (int i = 0; i < epsilon.size(); i++) {
                int a = epsilon.get(i);
                int b = epsilon.image(a);

                var intersecting = false;
                var orientedTriple = false;

                openGate:
                for (Cycle gamma : omega) {
                    if (gamma.size() == 1) continue;

                    if (epsilon != gamma) {
                        for (int j = 0; j < gamma.size(); j++) {
                            int c = gamma.get(j);
                            int d = gamma.image(c);

                            if (areSymbolsInCyclicOrder(beta.getInverse(), a, c, b, d)) {
                                intersecting = true;
                                break openGate;
                            }
                        }
                    } else {
                        for (int j = 0; j < epsilon.size(); j++) {
                            int c = epsilon.get(j);
                            if (c != a && c != b && areSymbolsInCyclicOrder(beta, a, b, c)) {
                                orientedTriple = true;
                                break openGate;
                            }
                        }
                    }
                }

                if (!intersecting && !orientedTriple) {
                    openGates.add(a);
                }
            }
        }

        return openGates;
    }
}
