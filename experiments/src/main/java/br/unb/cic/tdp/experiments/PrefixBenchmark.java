package br.unb.cic.tdp.experiments;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.SplittableRandom;
import java.time.Instant;

/** Local comparison: same roots, same exact fallback, no persisted witnesses. */
final class PrefixBenchmark {
    record Timed(byte[] decisions,double seconds,int[] certificates,long trials,double prefixSeconds,double fallbackSeconds) {}

    static void run() throws Exception {
        int n=Integer.getInteger("benchmark.n",12),budget=Integer.getInteger("benchmark.budget",RetentionPlan.upper(n)-1);
        int jobs=Integer.getInteger("benchmark.jobs",128),rounds=Integer.getInteger("benchmark.rounds",3);
        int moves=Integer.getInteger("benchmark.prefixMoves",3),tableDegree=Integer.getInteger("benchmark.tableDegree",8);
        int batchJobs=Math.min(jobs,Integer.getInteger("benchmark.batchJobs",131072));
        int warmupJobs=Math.min(batchJobs,Integer.getInteger("benchmark.warmupJobs",4096));
        double timeout=Double.parseDouble(System.getProperty("benchmark.timeoutSeconds","45"));
        if(n<2||n>18||jobs<1||rounds<1||batchJobs<1||warmupJobs<1||tableDegree<0||tableDegree>9||budget<0||timeout<=0)
            throw new IllegalArgumentException("Invalid prefix benchmark settings");
        boolean irreducible=Boolean.parseBoolean(System.getProperty("benchmark.irreducible","true"));
        boolean unique=Boolean.parseBoolean(System.getProperty("benchmark.unique","false"));
        System.out.printf(Locale.ROOT,"PREFIX_SETUP time=%s n=%d budget=%d jobs=%d batch_jobs=%d warmup_jobs=%d rounds=%d unique=%s%n",Instant.now(),n,budget,jobs,batchJobs,warmupJobs,rounds,unique);
        long[] ranks=sample(n,jobs,irreducible,unique);
        SmallTables tables=new SmallTables(tableDegree);
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(tables,Integer.getInteger("benchmark.quantum",4096));
            GpuStreamingCounter.Batch exact=gpu.new Batch(n,budget+1,budget+1,batchJobs);
            GpuPrefixSearch prefix=new GpuPrefixSearch(gpu,n,budget,batchJobs,moves)) {
            System.out.printf(Locale.ROOT,"PREFIX_BENCHMARK device=%s n=%d budget=%d jobs=%d moves=%d pattern_degree=%d irreducible=%s input=deterministic-synthetic%n",
                gpu.context.deviceName,n,budget,jobs,moves,tableDegree,irreducible);
            // Warm-up stays bounded even when millions of roots are requested.
            long[] warmup=Arrays.copyOf(ranks,warmupJobs);
            Timed reference=exact(exact,warmup,timeout,"warmup-exact"),warm=combined(exact,prefix,warmup,timeout,"warmup-prefix");
            check(reference.decisions,warm.decisions);
            System.out.printf("PREFIX_WARMUP time=%s jobs=%d mismatches=0%n",Instant.now(),warmupJobs);
            double[] baseline=new double[rounds],proposed=new double[rounds];
            for(int r=0;r<rounds;r++) {
                int[] certificates=new int[4];long trials=0;double prefixSeconds=0,fallbackSeconds=0;
                for(int first=0,batch=0;first<jobs;batch++) {
                    int count=Math.min(batchJobs,jobs-first);long[] input=Arrays.copyOfRange(ranks,first,first+count);
                    String label="round-"+(r+1)+"-batch-"+(batch+1);
                    System.out.printf("PREFIX_BATCH_START time=%s round=%d batch=%d first=%d jobs=%d%n",Instant.now(),r+1,batch+1,first,count);
                    Timed a,b;
                    if(((r+batch)&1)==0){a=exact(exact,input,timeout,label+"-exact");b=combined(exact,prefix,input,timeout,label+"-prefix");}
                    else {b=combined(exact,prefix,input,timeout,label+"-prefix");a=exact(exact,input,timeout,label+"-exact");}
                    check(a.decisions,b.decisions);
                    baseline[r]+=a.seconds;proposed[r]+=b.seconds;trials+=b.trials;
                    prefixSeconds+=b.prefixSeconds;fallbackSeconds+=b.fallbackSeconds;
                    for(int d=0;d<4;d++)certificates[d]+=b.certificates[d];
                    first+=count;
                    System.out.printf(Locale.ROOT,"PREFIX_BATCH time=%s round=%d batch=%d completed=%d total=%d exact_seconds=%.6f prefix_fallback_seconds=%.6f cumulative_exact_seconds=%.6f cumulative_prefix_fallback_seconds=%.6f speedup=%.3f certified_by_depth=%s fallback=%d trials=%d mismatches=0%n",
                        Instant.now(),r+1,batch+1,first,jobs,a.seconds,b.seconds,baseline[r],proposed[r],baseline[r]/proposed[r],Arrays.toString(certificates),first-Arrays.stream(certificates).sum(),trials);
                }
                System.out.printf(Locale.ROOT,"PREFIX_ROUND round=%d jobs=%d exact_seconds=%.6f prefix_fallback_seconds=%.6f prefix_seconds=%.6f fallback_seconds=%.6f exact_per_minute=%.1f proposed_per_minute=%.1f speedup=%.3f certified_by_depth=%s fallback=%d trials=%d mismatches=0%n",
                    r+1,jobs,baseline[r],proposed[r],prefixSeconds,fallbackSeconds,60*jobs/baseline[r],60*jobs/proposed[r],baseline[r]/proposed[r],Arrays.toString(certificates),jobs-Arrays.stream(certificates).sum(),trials);
            }
            double a=GpuDiametralFilter.median(baseline),b=GpuDiametralFilter.median(proposed);
            System.out.printf(Locale.ROOT,"PREFIX_RESULT n=%d budget=%d jobs=%d exact_median_seconds=%.6f prefix_fallback_median_seconds=%.6f speedup=%.3f mismatches=0%n",n,budget,jobs,a,b,a/b);
            System.out.println("PREFIX_SCOPE local synthetic inputs; no persisted degree-13 patterns or degree-14 membership bitmap; remote production unchanged");
        }
    }

    private static Timed exact(GpuStreamingCounter.Batch batch,long[] ranks,double timeout,String label) {
        long start=System.nanoTime();
        byte[] result=batch.classifyRanks(ranks,ranks.length,deadline(start,timeout,label));
        double seconds=(System.nanoTime()-start)/1e9;
        return new Timed(result,seconds,null,0,0,seconds);
    }

    private static Timed combined(GpuStreamingCounter.Batch exact,GpuPrefixSearch prefix,long[] ranks,double timeout,String label) {
        long start=System.nanoTime();Runnable cancellation=deadline(start,timeout,label);
        GpuPrefixSearch.Result result=prefix.search(ranks,ranks.length,cancellation);
        double prefixSeconds=(System.nanoTime()-start)/1e9;
        int[] counts=new int[4];long[] remaining=new long[ranks.length];int pending=0;
        for(int i=0;i<ranks.length;i++)if(result.depths()[i]<0)remaining[pending++]=ranks[i];else counts[result.depths()[i]]++;
        long fallbackStart=System.nanoTime();
        byte[] fallback=pending==0?new byte[0]:exact.classifyRanks(remaining,pending,cancellation);
        double fallbackSeconds=(System.nanoTime()-fallbackStart)/1e9;
        byte[] decisions=new byte[ranks.length];
        for(int i=0,j=0;i<ranks.length;i++)if(result.depths()[i]<0)decisions[i]=fallback[j++];
        return new Timed(decisions,(System.nanoTime()-start)/1e9,counts,result.trials(),prefixSeconds,fallbackSeconds);
    }

    private static Runnable deadline(long start,double seconds,String label) {
        long[] last={start};
        return ()->{long now=System.nanoTime();
            if((now-start)/1e9>seconds)throw new java.util.concurrent.CancellationException("Benchmark timed out at "+label+"; comparison incomplete (increase benchmark.timeoutSeconds or reduce benchmark.batchJobs)");
            if(now-last[0]>=15_000_000_000L){last[0]=now;System.out.printf(Locale.ROOT,"PREFIX_ACTIVE time=%s stage=%s elapsed_seconds=%.1f%n",Instant.now(),label,(now-start)/1e9);}
        };
    }
    private static void check(byte[] expected,byte[] actual){if(!Arrays.equals(expected,actual))throw new AssertionError("Prefix+fallback decisions differ from exact search");}

    private static long[] sample(int n,int jobs,boolean irreducible,boolean unique) {
        if(unique&&jobs>SmallTables.FACTORIAL[n])throw new IllegalArgumentException("Unique sample exceeds n!");
        SplittableRandom random=new SplittableRandom(Long.getLong("benchmark.seed",20261105L));
        HashSet<Long> seen=unique?HashSet.newHashSet(jobs):null;
        long[] ranks=new long[jobs];byte[] p=new byte[n];
        long draws=0;
        for(int i=0;i<jobs;) {
            if(++draws>Math.max(1_000_000L,jobs*1000L))throw new IllegalArgumentException("Requested sample cannot be filled; reduce benchmark.jobs or disable benchmark.unique");
            long rank=random.nextLong(SmallTables.FACTORIAL[n]);SmallTables.unrank(rank,n,p,0);
            boolean fixed=p[0]==1||p[n-1]==n;
            for(int j=1;j<n;j++)fixed|=p[j]==p[j-1]+1;
            if((!irreducible||!fixed)&&(!unique||seen.add(rank)))ranks[i++]=rank;
        }
        return ranks;
    }
}
