package br.unb.cic.tdp.experiments;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Counts only, with disjoint CPU/GPU rank chunks and no permutation output. */
final class CountsRun {
    static final int[] LOW={0,0,1,2,3,3,4,4,5,5,6,6,7,8,8,9,9,10,10};
    static void run(int maxN,int threads,double fraction,boolean gpuEnabled) throws Exception {
        if(maxN<1||maxN>18||threads<1||fraction<=0||fraction>0.95)throw new IllegalArgumentException("Invalid count configuration");
        System.out.printf(Locale.ROOT,"Counts only: n=1..%d; CPU workers=%d; GPU=%s; VRAM budget=%.0f%% of free memory when enough live jobs exist.%n",maxN,threads,gpuEnabled,fraction*100);
        System.out.println("No witnesses, bracelet files, or checkpoints are written. Progress counts are provisional until FINAL. Degree 18 is tested at distances 10 and 11.");
        System.out.println("Canonicality uses (omega,sigma) with fixed sigma=(0 1 ... n); only canonical instances enter sorting, with exact orbit weights.");
        SmallTables bases=new SmallTables(Math.min(9,maxN));
        for(int n=1;n<=Math.min(9,maxN);n++)System.out.printf("FINAL n=%d TD=%d |D(%d)|=%d%n",n,LOW[n],n,bases.diametralCounts[n]);
        if(maxN<=9)return;
        try(GpuStreamingCounter gpu=gpuEnabled?new GpuStreamingCounter(bases):null) {
            for(int n=10;n<=maxN;n++)countDegree(n,threads,fraction,bases,gpu);
        }
    }
    static void countDegree(int n,int threads,double fraction,SmallTables bases,GpuStreamingCounter gpu) throws Exception {
        int low=LOW[n],high=n==18?11:low;
        long total=SmallTables.FACTORIAL[n],started=System.nanoTime();
        AtomicLong cursor=new AtomicLong(),cpuDone=new AtomicLong(),cpuLow=new AtomicLong(),cpuHigh=new AtomicLong();
        AtomicLong gpuDone=new AtomicLong(),gpuLow=new AtomicLong(),gpuHigh=new AtomicLong();
        AtomicLong gpuReturnedAt=new AtomicLong(System.nanoTime());
        AtomicReference<Throwable> failure=new AtomicReference<>();
        AtomicBoolean gpuFinished=new AtomicBoolean(gpu==null);
        AtomicInteger idleWorkers=new AtomicInteger();
        ConcurrentLinkedQueue<Long> stolen=new ConcurrentLinkedQueue<>();
        ExecutorService workers=Executors.newFixedThreadPool(threads);
        List<Future<?>> futures=new ArrayList<>();
        ScheduledExecutorService logger=Executors.newSingleThreadScheduledExecutor();
        Runnable log=()->{
            long done=cpuDone.get()+gpuDone.get();double elapsed=(System.nanoTime()-started)/1e9;
            System.out.printf(Locale.ROOT,"PROGRESS n=%d processed=%d/%d (%.6f%%) distance_%d=%d%s CPU=%d GPU=%d elapsed=%.1fs gpu_response_age=%.1fs%n",
                n,done,total,100.0*done/total,low,cpuLow.get()+gpuLow.get(),
                low==high?"":" distance_"+high+"="+(cpuHigh.get()+gpuHigh.get()),cpuDone.get(),gpuDone.get(),elapsed,
                gpu==null||gpuFinished.get()?0:(System.nanoTime()-gpuReturnedAt.get())/1e9);
            var os=java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if(os instanceof com.sun.management.OperatingSystemMXBean measured)
                System.out.printf(Locale.ROOT,"LOAD n=%d process_CPU=%.1f%% of machine capacity idle_workers=%d%n",n,100*measured.getProcessCpuLoad(),idleWorkers.get());
            if(gpu!=null) {
                Process monitor=null;
                try {
                    monitor=new ProcessBuilder("nvidia-smi","--query-gpu=utilization.gpu,memory.used,memory.total","--format=csv,noheader").redirectErrorStream(true).start();
                    if(monitor.waitFor(1,TimeUnit.SECONDS)&&monitor.exitValue()==0)
                        System.out.println("LOAD GPU compute,VRAM used,total: "+new String(monitor.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim());
                }catch(java.io.IOException ignored){/* CUDA counting does not require nvidia-smi. */}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                finally{if(monitor!=null&&monitor.isAlive())monitor.destroyForcibly();}
            }
        };
        logger.scheduleAtFixedRate(log,2,2,TimeUnit.SECONDS);
        try {
            int gpuCapacity=gpu==null?0:(int)Math.min(gpu.capacity(n,high,fraction),Math.max(1,total*3/4));
            int gpuScan=gpu==null?0:(int)Math.min(Math.min(Integer.MAX_VALUE-262144,(long)gpuCapacity*2*(n+1)),Math.max(1,total*3/4));
            long gpuFirst=gpu==null?-1:cursor.getAndAdd(gpuScan);
            for(int worker=0;worker<threads;worker++)futures.add(workers.submit(()->{
                ExactSorter sorter=new ExactSorter(bases);
                byte[] root=new byte[n];
                try {
                    while(failure.get()==null&&!Thread.currentThread().isInterrupted()) {
                        Long stolenRank=stolen.poll();
                        if(stolenRank!=null) {
                            SmallTables.unrank(stolenRank,n,root,0);int weight=ToricOrbit.weight(root,n);
                            int result=weight==0?0:sorter.classify(stolenRank,n,low,high);
                            if(result==1)cpuLow.addAndGet(weight);if(result==2)cpuHigh.addAndGet(weight);cpuDone.incrementAndGet();
                            continue;
                        }
                        long first=cursor.getAndAdd(64);
                        if(first>=total) {
                            if(gpuFinished.get()&&stolen.isEmpty())break;
                            idleWorkers.incrementAndGet();
                            try {java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);}
                            finally {idleWorkers.decrementAndGet();}
                            continue;
                        }
                        int count=(int)Math.min(64,total-first);long cLow=0,cHigh=0;
                        for(int i=0;i<count;i++) {
                            SmallTables.unrank(first+i,n,root,0);int weight=ToricOrbit.weight(root,n);
                            if(weight==0)continue;
                            int result=sorter.classify(first+i,n,low,high);if(result==1)cLow+=weight;if(result==2)cHigh+=weight;
                        }
                        cpuLow.addAndGet(cLow);cpuHigh.addAndGet(cHigh);cpuDone.addAndGet(count);
                    }
                }catch(Throwable error){failure.compareAndSet(null,error);throw error;}
            }));
            if(gpu!=null) {
                try(GpuStreamingCounter.Batch batch=gpu.new Batch(n,low,high,gpuCapacity)) {
                    System.out.printf(Locale.ROOT,"GPU n=%d canonical_capacity=%d raw_chunk=%d root_buffers=%.1f MiB%n",n,gpuCapacity,gpuScan,batch.allocatedBytes/1048576.0);
                    long first=gpuFirst;
                    while(failure.get()==null&&first<total) {
                        int count=(int)Math.min(gpuScan,total-first);
                        long beforeDone=gpuDone.get(),beforeLow=gpuLow.get(),beforeHigh=gpuHigh.get();
                        batch.count(first,count,partial->{gpuReturnedAt.set(System.nanoTime());gpuDone.set(beforeDone+partial[0]);gpuLow.set(beforeLow+partial[1]);gpuHigh.set(beforeHigh+partial[2]);},idleWorkers::get,stolen::add);
                        first=cursor.getAndAdd(gpuScan);
                    }
                }
                gpuFinished.set(true);
            }
            for(Future<?> future:futures)future.get();
            if(failure.get()!=null)throw new IllegalStateException("CPU worker failed",failure.get());
            long done=cpuDone.get()+gpuDone.get();if(done!=total)throw new AssertionError("Lost/duplicated rank chunks: "+done+" vs "+total);
            long countLow=cpuLow.get()+gpuLow.get(),countHigh=cpuHigh.get()+gpuHigh.get();
            long expected=n<=14?DiametralExpected.count(n):-1;
            long answer=countHigh>0?countHigh:countLow;int diameter=countHigh>0?high:low;
            if(expected>=0&&answer!=expected)throw new AssertionError("D("+n+") mismatch: "+answer+" vs "+expected);
            System.out.printf(Locale.ROOT,"FINAL n=%d TD=%d |D(%d)|=%d CPU=%d GPU=%d elapsed=%.3fs%n",n,diameter,n,answer,cpuDone.get(),gpuDone.get(),(System.nanoTime()-started)/1e9);
        }finally{
            gpuFinished.set(true);
            logger.shutdownNow();workers.shutdownNow();
            if(!workers.awaitTermination(10,TimeUnit.SECONDS))System.err.println("CPU workers still cancelling; current degree is incomplete.");
        }
    }
    static final class DiametralExpected {
        static long count(int n){return switch(n){case 10->255053;case 11->12537954;case 12->31599601;case 13->427;case 14->5246800005L;default->-1;};}
        static long layerCount(int n,int distance) {
            if(n<=9)return distance<DiametralExtensionAudit.EXPECTED[n].length?DiametralExtensionAudit.EXPECTED[n][distance]:0;
            if(n==14&&distance==7)return 47_916_472_532L;
            return distance==RetentionPlan.upper(n)?count(n):-1;
        }
    }
}
