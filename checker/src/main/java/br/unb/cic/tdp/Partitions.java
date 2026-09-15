package br.unb.cic.tdp;

import java.util.ArrayList;
import java.util.List;

public class Partitions {

    /**
     * Generates all valid partitions of n into parts >= 2 with an even number
     * of even parts (parity constraint: omega must be in A_n).
     */
    public static List<int[]> generateValidPartitions(final int n) {
        var result = new ArrayList<int[]>();
        generatePartitions(n, n, new ArrayList<>(), 0, result);
        return result;
    }

    /**
     * Generates all partitions of n into parts >= minPart, with no parity filter.
     */
    public static List<int[]> generatePartitions(final int n, final int minPart) {
        var result = new ArrayList<int[]>();
        generatePartitionsWithoutParity(n, n, minPart, new ArrayList<>(), result);
        return result;
    }

    private static void generatePartitions(int remaining,
                                           int maxPart,
                                           List<Integer> partition,
                                           int evenPartCount,
                                           List<int[]> result) {

        if (remaining == 0) {
            if (evenPartCount % 2 == 0) {
                result.add(partition.stream().mapToInt(Integer::intValue).toArray());
            }
            return;
        }

        for (int part = Math.min(maxPart, remaining); part >= 2; part--) {

            partition.add(part);

            generatePartitions(
                    remaining - part,
                    part, // ensures non-increasing order
                    partition,
                    evenPartCount + (part % 2 == 0 ? 1 : 0),
                    result
            );

            partition.remove(partition.size() - 1);
        }
    }

    private static void generatePartitionsWithoutParity(int remaining,
                                                        int maxPart,
                                                        int minPart,
                                                        List<Integer> partition,
                                                        List<int[]> result) {

        if (remaining == 0) {
            result.add(partition.stream().mapToInt(Integer::intValue).toArray());
            return;
        }

        for (int part = Math.min(maxPart, remaining); part >= minPart; part--) {
            partition.add(part);

            generatePartitionsWithoutParity(
                    remaining - part,
                    part,
                    minPart,
                    partition,
                    result
            );

            partition.remove(partition.size() - 1);
        }
    }
}
