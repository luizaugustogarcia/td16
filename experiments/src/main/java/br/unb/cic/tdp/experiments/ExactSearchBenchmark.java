package br.unb.cic.tdp.experiments;

import java.util.Arrays;
import java.util.Locale;
import jcuda.driver.CUstream;
import static jcuda.driver.JCudaDriver.*;
import static jcuda.driver.CUstream_flags.CU_STREAM_NON_BLOCKING;

/** Controlled A/B benchmark for exact-search pruning, independent of degree. */
final class ExactSearchBenchmark {
    record Result(String name,byte[] decisions,double wallMillis,double kernelMillis) {}

    static Result measure(GpuStreamingCounter gpu,long[] ranks,int n,int budget,
                          boolean slack,boolean deletions,boolean moves,int warmups,int repeats) {
        CUstream stream=new CUstream();gpu.activate();cuStreamCreate(stream,CU_STREAM_NON_BLOCKING);
        try(GpuStreamingCounter.Batch batch=gpu.new Batch(n,budget+1,budget+1,ranks.length,stream,slack,deletions,moves)) {
            for(int i=0;i<warmups;i++)batch.classifyRanks(ranks,ranks.length);
            batch.launches=0;batch.activeJobs=0;batch.tinyWaves=0;batch.kernelMillis=0;
            double[] wall=new double[repeats],kernel=new double[repeats];byte[] decisions=null;
            for(int i=0;i<repeats;i++) {
                double beforeKernel=batch.kernelMillis;long began=System.nanoTime();
                decisions=batch.classifyRanks(ranks,ranks.length);
                wall[i]=(System.nanoTime()-began)/1e6;kernel[i]=batch.kernelMillis-beforeKernel;
            }
            String name=slack?"slack":"";
            if(deletions)name+=(name.isEmpty()?"":"+")+"deletions";
            if(moves)name+=(name.isEmpty()?"":"+")+"moves";
            if(name.isEmpty())name="baseline";
            return new Result(name,decisions,GpuDiametralFilter.median(wall),GpuDiametralFilter.median(kernel));
        } finally {gpu.activate();cuStreamDestroy(stream);}
    }

    static void run() throws Exception {
        int n=Integer.getInteger("benchmark.n",9);
        if(n<2||n>18)throw new IllegalArgumentException("benchmark.n must be 2..18");
        int budget=Integer.getInteger("benchmark.budget",Math.max(0,CountsRun.LOW[n]-1));
        int jobs=Integer.getInteger("benchmark.jobs",(int)Math.min(32768L,SmallTables.FACTORIAL[n]));
        int warmups=Integer.getInteger("benchmark.warmups",2),repeats=Integer.getInteger("benchmark.repeats",7);
        int tableDegree=Integer.getInteger("benchmark.tableDegree",Math.min(n-1,9));
        int deletionDistance=Integer.getInteger("benchmark.deletionDistance",-1);
        if(budget<0||jobs<1||jobs>SmallTables.FACTORIAL[n]||warmups<0||repeats<1||tableDegree<0||tableDegree>9||tableDegree>=n||
            deletionDistance<-1||deletionDistance>RetentionPlan.upper(n-1)||deletionDistance>=0&&tableDegree<n-1)
            throw new IllegalArgumentException("Invalid exact-search benchmark settings");
        long total=SmallTables.FACTORIAL[n];SmallTables tables=new SmallTables(tableDegree);
        long[] ranks=deletionDistance<0?evenRanks(total,jobs):deletionFilteredRanks(tables,n,deletionDistance,jobs);jobs=ranks.length;
        System.out.printf(Locale.ROOT,"EXACT_SEARCH_BENCHMARK gpu=%s n=%d budget=%d jobs=%d table_degree=%d deletion_distance=%d warmups=%d repeats=%d%n",
            "device0",n,budget,jobs,tableDegree,deletionDistance,warmups,repeats);
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(tables)) {
            Result[] results={
                measure(gpu,ranks,n,budget,false,false,false,warmups,repeats),
                measure(gpu,ranks,n,budget,true,false,false,warmups,repeats),
                measure(gpu,ranks,n,budget,false,true,false,warmups,repeats),
                measure(gpu,ranks,n,budget,false,false,true,warmups,repeats),
                measure(gpu,ranks,n,budget,false,true,true,warmups,repeats),
                measure(gpu,ranks,n,budget,true,true,false,warmups,repeats),
                measure(gpu,ranks,n,budget,true,false,true,warmups,repeats),
                measure(gpu,ranks,n,budget,true,true,true,warmups,repeats)
            };
            for(int i=1;i<results.length;i++)if(!Arrays.equals(results[0].decisions,results[i].decisions))
                throw new AssertionError(results[i].name+" decisions differ from baseline");
            for(Result result:results)System.out.printf(Locale.ROOT,
                "EXACT_SEARCH_RESULT variant=%s wall_ms=%.3f kernel_ms=%.3f wall_speedup=%.3fx kernel_speedup=%.3fx positives=%d%n",
                result.name,result.wallMillis,result.kernelMillis,results[0].wallMillis/result.wallMillis,
                results[0].kernelMillis/result.kernelMillis,count(result.decisions));
        }
    }
    private static long[] evenRanks(long total,int jobs) {
        long[] ranks=new long[jobs];for(int i=0;i<jobs;i++)ranks[i]=(long)i*total/jobs;return ranks;
    }
    private static long[] deletionFilteredRanks(SmallTables tables,int n,int distance,int requested) {
        long total=SmallTables.FACTORIAL[n],matches=0;byte[] word=new byte[n],deletion=new byte[n-1];
        for(long rank=0;rank<total;rank++)if(allDeletionsAt(tables,rank,n,distance,word,deletion))matches++;
        int jobs=(int)Math.min(requested,matches);if(jobs==0)throw new IllegalArgumentException("No ranks match the deletion-distance filter");
        long[] result=new long[jobs];long seen=0;int selected=0;
        for(long rank=0;rank<total&&selected<jobs;rank++)if(allDeletionsAt(tables,rank,n,distance,word,deletion)) {
            if(seen==(long)selected*matches/jobs)result[selected++]=rank;seen++;
        }
        if(selected!=jobs)throw new AssertionError("Deletion-filtered rank selection is incomplete");
        System.out.printf(Locale.ROOT,"EXACT_SEARCH_ROOT_FILTER all_deletions_distance=%d matches=%d selected=%d%n",distance,matches,jobs);
        return result;
    }
    private static boolean allDeletionsAt(SmallTables tables,long rank,int n,int distance,byte[] word,byte[] deletion) {
        SmallTables.unrank(rank,n,word,0);
        for(int omitted=0;omitted<n;omitted++) {
            int value=word[omitted];
            for(int i=0,j=0;i<n;i++)if(i!=omitted){int v=word[i];deletion[j++]=(byte)(v-(v>value?1:0));}
            int deletionRank=(int)SmallTables.rank(deletion,n-1);
            if(tables.distances[tables.offsets[n-1]+deletionRank]!=distance)return false;
        }
        return true;
    }
    private static int count(byte[] decisions){int result=0;for(byte decision:decisions)result+=decision;return result;}
}
