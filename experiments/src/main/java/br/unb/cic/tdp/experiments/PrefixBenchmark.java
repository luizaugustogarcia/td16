package br.unb.cic.tdp.experiments;

import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;

/** Local comparison: same roots, same exact fallback, no persisted witnesses. */
final class PrefixBenchmark {
    record Timed(byte[] decisions,double seconds,int[] certificates,long trials) {}

    static void run() throws Exception {
        int n=Integer.getInteger("benchmark.n",12),budget=Integer.getInteger("benchmark.budget",RetentionPlan.upper(n)-1);
        int jobs=Integer.getInteger("benchmark.jobs",128),rounds=Integer.getInteger("benchmark.rounds",3);
        int moves=Integer.getInteger("benchmark.prefixMoves",3),tableDegree=Integer.getInteger("benchmark.tableDegree",8);
        double timeout=Double.parseDouble(System.getProperty("benchmark.timeoutSeconds","45"));
        if(n<2||n>18||jobs<1||rounds<1||tableDegree<0||tableDegree>9||budget<0||timeout<=0)
            throw new IllegalArgumentException("Invalid prefix benchmark settings");
        boolean irreducible=Boolean.parseBoolean(System.getProperty("benchmark.irreducible","true"));
        long[] ranks=sample(n,jobs,irreducible);
        SmallTables tables=new SmallTables(tableDegree);
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(tables,Integer.getInteger("benchmark.quantum",4096));
            GpuStreamingCounter.Batch exact=gpu.new Batch(n,budget+1,budget+1,jobs);
            GpuPrefixSearch prefix=new GpuPrefixSearch(gpu,n,budget,jobs,moves)) {
            System.out.printf(Locale.ROOT,"PREFIX_BENCHMARK device=%s n=%d budget=%d jobs=%d moves=%d pattern_degree=%d irreducible=%s input=deterministic-synthetic%n",
                gpu.context.deviceName,n,budget,jobs,moves,tableDegree,irreducible);
            // Both paths are warmed up with the complete input, outside timings.
            Timed reference=exact(exact,ranks,timeout),warm=combined(exact,prefix,ranks,timeout);
            check(reference.decisions,warm.decisions);
            double[] baseline=new double[rounds],proposed=new double[rounds];
            for(int r=0;r<rounds;r++) {
                Timed a,b;
                if((r&1)==0){a=exact(exact,ranks,timeout);b=combined(exact,prefix,ranks,timeout);}
                else {b=combined(exact,prefix,ranks,timeout);a=exact(exact,ranks,timeout);}
                check(reference.decisions,a.decisions);check(a.decisions,b.decisions);
                baseline[r]=a.seconds;proposed[r]=b.seconds;
                System.out.printf(Locale.ROOT,"PREFIX_ROUND round=%d exact_seconds=%.6f prefix_fallback_seconds=%.6f speedup=%.3f certified_by_depth=%s fallback=%d trials=%d mismatches=0%n",
                    r+1,a.seconds,b.seconds,a.seconds/b.seconds,Arrays.toString(b.certificates),jobs-Arrays.stream(b.certificates).sum(),b.trials);
            }
            double a=GpuDiametralFilter.median(baseline),b=GpuDiametralFilter.median(proposed);
            System.out.printf(Locale.ROOT,"PREFIX_RESULT n=%d budget=%d jobs=%d exact_median_seconds=%.6f prefix_fallback_median_seconds=%.6f speedup=%.3f mismatches=0%n",n,budget,jobs,a,b,a/b);
            System.out.println("PREFIX_SCOPE local synthetic inputs; no persisted degree-13 patterns or degree-14 membership bitmap; remote production unchanged");
        }
    }

    private static Timed exact(GpuStreamingCounter.Batch batch,long[] ranks,double timeout) {
        long start=System.nanoTime();
        byte[] result=batch.classifyRanks(ranks,ranks.length,deadline(start,timeout));
        return new Timed(result,(System.nanoTime()-start)/1e9,null,0);
    }

    private static Timed combined(GpuStreamingCounter.Batch exact,GpuPrefixSearch prefix,long[] ranks,double timeout) {
        long start=System.nanoTime();Runnable cancellation=deadline(start,timeout);
        GpuPrefixSearch.Result result=prefix.search(ranks,ranks.length,cancellation);
        int[] counts=new int[4];long[] remaining=new long[ranks.length];int pending=0;
        for(int i=0;i<ranks.length;i++)if(result.depths()[i]<0)remaining[pending++]=ranks[i];else counts[result.depths()[i]]++;
        byte[] fallback=pending==0?new byte[0]:exact.classifyRanks(remaining,pending,cancellation);
        byte[] decisions=new byte[ranks.length];
        for(int i=0,j=0;i<ranks.length;i++)if(result.depths()[i]<0)decisions[i]=fallback[j++];
        return new Timed(decisions,(System.nanoTime()-start)/1e9,counts,result.trials());
    }

    private static Runnable deadline(long start,double seconds) {
        return ()->{if((System.nanoTime()-start)/1e9>seconds)throw new java.util.concurrent.CancellationException("Benchmark timed out; comparison incomplete (increase benchmark.timeoutSeconds or reduce benchmark.jobs)");};
    }
    private static void check(byte[] expected,byte[] actual){if(!Arrays.equals(expected,actual))throw new AssertionError("Prefix+fallback decisions differ from exact search");}

    private static long[] sample(int n,int jobs,boolean irreducible) {
        SplittableRandom random=new SplittableRandom(Long.getLong("benchmark.seed",20261105L));
        long[] ranks=new long[jobs];byte[] p=new byte[n];
        for(int i=0;i<jobs;) {
            long rank=random.nextLong(SmallTables.FACTORIAL[n]);SmallTables.unrank(rank,n,p,0);
            boolean fixed=p[0]==1||p[n-1]==n;
            for(int j=1;j<n;j++)fixed|=p[j]==p[j-1]+1;
            if(!irreducible||!fixed)ranks[i++]=rank;
        }
        return ranks;
    }
}
