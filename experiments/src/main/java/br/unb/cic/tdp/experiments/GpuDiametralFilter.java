package br.unb.cic.tdp.experiments;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import jcuda.Pointer;
import jcuda.driver.*;
import jcuda.nvrtc.*;
import static jcuda.driver.JCudaDriver.*;
import static jcuda.nvrtc.JNvrtc.*;

// Filter benchmark only: BFS supplies small-degree reference data, not GPU sorting.
class GpuDiametralFilter implements AutoCloseable {
    final CUcontext context = new CUcontext();
    final CUmodule module = new CUmodule();
    final CUfunction kernel = new CUfunction();
    boolean contextReady, moduleReady;
    String deviceName;
    String architecture;
    final int deviceOrdinal;

    GpuDiametralFilter() throws Exception {
        this(0);
    }
    static int deviceCount() {
        JCudaDriver.setExceptionsEnabled(true);cuInit(0);int[] count={0};cuDeviceGetCount(count);return count[0];
    }
    GpuDiametralFilter(int deviceOrdinal) throws Exception {
        this.deviceOrdinal=deviceOrdinal;
        JCudaDriver.setExceptionsEnabled(true);
        cuInit(0);
        CUdevice device = new CUdevice();
        int available=deviceCount();
        if(deviceOrdinal<0||deviceOrdinal>=available)throw new IllegalArgumentException("CUDA device "+deviceOrdinal+" is unavailable; visible devices="+available);
        cuDeviceGet(device, deviceOrdinal);
        cuCtxCreate(context, CUctx_flags.CU_CTX_SCHED_BLOCKING_SYNC, device);
        contextReady = true;
        try {
            byte[] name = new byte[256];
            cuDeviceGetName(name, name.length, device);
            int length = 0;
            while (length < name.length && name[length] != 0) length++;
            deviceName = new String(name, 0, length, StandardCharsets.UTF_8);
            int[] major = {0}, minor = {0};
            cuDeviceGetAttribute(major, CUdevice_attribute.CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR, device);
            cuDeviceGetAttribute(minor, CUdevice_attribute.CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR, device);
            architecture = "--gpu-architecture=compute_" + major[0] + minor[0];
            String ptx=ExperimentResources.ptx("diametral-filter",architecture,()->ExperimentResources.read("cuda/diametral-filter.cu"));
            cuModuleLoadData(module, ptx);
            moduleReady = true;
            cuModuleGetFunction(kernel, module, "filter_diametral_insertions");
        } catch (Exception | Error error) { close(); throw error; }
    }

    static int deletionRank(long word, int n, int omitted) {
        int deleted = (int)(word >>> (4 * omitted)) & 15;
        int rank = 0, used = 0, radix = n - 1;
        for (int i = 0; i < n; i++) {
            if (i == omitted) continue;
            int value = (int)(word >>> (4 * i)) & 15;
            if (value > deleted) value--;
            int smaller = value - 1 - Integer.bitCount(used & ((1 << (value - 1)) - 1));
            rank = rank * radix-- + smaller;
            used |= 1 << (value - 1);
        }
        return rank;
    }

    static void filterParent(long[] parents, int index, int n, byte[] distances,
                             int required, byte[] keep) {
        for (int position = 0; position < n; position++) {
            long word = DiametralExtensionAudit.insertMaximum(parents[index], n, position);
            byte accepted = 1;
            for (int omitted = 0; omitted < n; omitted++) {
                if (omitted == position) continue;
                if (distances[deletionRank(word, n, omitted)] != required) {
                    accepted = 0;
                    break;
                }
            }
            keep[index * n + position] = accepted;
        }
    }

    static void filterCpu(long[] parents, int n, byte[] distances, int required,
                          byte[] keep, ForkJoinPool pool) {
        if (pool == null) {
            for (int i = 0; i < parents.length; i++) filterParent(parents,i,n,distances,required,keep);
        } else {
            pool.submit(() -> IntStream.range(0, parents.length).parallel().forEach(
                i -> filterParent(parents,i,n,distances,required,keep))).join();
        }
    }

    static double median(double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    void auditMoveDeltas() throws Exception {
        String ptx=ExperimentResources.ptx("emitter-delta-audit",architecture,()->{
        String emitter = ExperimentResources.read("cuda/emitter/sorting_search.cu");
        int from = emitter.indexOf("    __device__ __forceinline__ int count_even_cycles");
        int to = emitter.indexOf("    // ─── Pruning logic",from);
        if (from < 0 || to < 0) throw new IllegalStateException("Cannot locate emitter cycle functions");
        return "typedef unsigned char uint8_t;\ntypedef unsigned long long uint64_t;\n#define INT_MIN (-2147483647-1)\n"
            + emitter.substring(from,to) + ExperimentResources.read("cuda/cycle-delta-audit.cu");
        });
        CUmodule auditModule = new CUmodule();
        cuModuleLoadData(auditModule,ptx);
        try {
            CUfunction auditKernel = new CUfunction();
            cuModuleGetFunction(auditKernel,auditModule,"audit_cycle_deltas");
            System.out.println("Actual emitter delta audit: n,permutations,queries,minus_two,zero,plus_two,errors");
            for (int n = 3; n <= 9; n++) {
                // Exhaustive permutations through n=8; a nontrivial 3-cycle
                // triple at n=9 checks a merge of three non-fixed odd cycles.
                long[] words = new long[n == 9 ? 1 : DiametralExtensionAudit.FACT[n]];
                if (n == 9) {
                    int[] omega = {1,2,0,4,5,3,7,8,6};
                    for (int i=0;i<n;i++) words[0] |= (long)omega[i] << (4*i);
                } else for (int rank=0;rank<words.length;rank++) {
                    long permutation = DiametralExtensionAudit.unrank(rank,n);
                    for (int i=0;i<n;i++) words[rank] |= (((permutation >>> (4*i)) & 15)-1) << (4*i);
                }
                int[] triples = new int[n*(n-1)*(n-2)/6];
                for (int a=0,j=0;a<n;a++) for(int b=a+1;b<n;b++) for(int c=b+1;c<n;c++)
                    triples[j++] = a | (b<<8) | (c<<16);
                CUdeviceptr dWords=new CUdeviceptr(),dTriples=new CUdeviceptr(),dCounters=new CUdeviceptr();
                cuMemAlloc(dWords,words.length*8L);
                try {
                    cuMemAlloc(dTriples,triples.length*4L);
                    try {
                        cuMemAlloc(dCounters,16);
                        try {
                            cuMemcpyHtoD(dWords,Pointer.to(words),words.length*8L);
                            cuMemcpyHtoD(dTriples,Pointer.to(triples),triples.length*4L);
                            cuMemsetD32(dCounters,0,4);
                            int queries=words.length*triples.length*2;
                            Pointer args=Pointer.to(Pointer.to(dWords),Pointer.to(new int[]{words.length}),
                                Pointer.to(new int[]{n}),Pointer.to(dTriples),Pointer.to(new int[]{triples.length}),Pointer.to(dCounters));
                            cuLaunchKernel(auditKernel,(queries+255)/256,1,1,256,1,1,0,null,args,null);
                            int[] counts=new int[4];
                            cuMemcpyDtoH(Pointer.to(counts),dCounters,16);
                            if(counts[0]!=0 || counts[1]+counts[2]+counts[3]!=queries)
                                throw new AssertionError("Emitter delta mismatch at n="+n);
                            if(n==9 && counts[1]==0) throw new AssertionError("Missing three-cycle -2 case");
                            System.out.printf("%d,%d,%d,%d,%d,%d,%d%n",n,words.length,queries,counts[1],counts[2],counts[3],counts[0]);
                        } finally { cuMemFree(dCounters); }
                    } finally { cuMemFree(dTriples); }
                } finally { cuMemFree(dWords); }
            }
            System.out.println("PASS: emitter delta equals explicit recount for every audited oriented triple.");
        } finally { cuModuleUnload(auditModule); }
    }

    void benchmarkCase(int n, ForkJoinPool pool) {
        byte[] previous = DiametralExtensionAudit.bfs(n - 1);
        int required = DiametralExtensionAudit.diameter(previous), parentCount = 0;
        for (byte d : previous) if (d == required) parentCount++;
        long[] parents = new long[parentCount];
        for (int rank = 0, j = 0; rank < previous.length; rank++)
            if (previous[rank] == required) parents[j++] = DiametralExtensionAudit.unrank(rank, n - 1);
        int candidates = Math.multiplyExact(parentCount, n);
        byte[] cpu = new byte[candidates], parallel = new byte[candidates], gpu = new byte[candidates];

        filterCpu(parents,n,previous,required,cpu,null);
        // Independently check rank-with-omission against explicit standardization.
        for (int p = 0; p < parents.length; p++) {
            for (int position = 0; position < n; position++) {
                long word = DiametralExtensionAudit.insertMaximum(parents[p], n, position);
                boolean accepted = true;
                for (int omitted = 0; omitted < n; omitted++) {
                    long deletion = DiametralExtensionAudit.deletePosition(word,n,omitted);
                    if (previous[DiametralExtensionAudit.rank(deletion,n-1)] != required) { accepted=false; break; }
                }
                if ((cpu[p*n+position] != 0) != accepted) throw new AssertionError("CPU baseline mismatch");
            }
        }

        CUdeviceptr dParents = new CUdeviceptr(), dPrevious = new CUdeviceptr(), dKeep = new CUdeviceptr();
        CUevent start = new CUevent(), end = new CUevent();
        boolean allocatedParents = false, allocatedPrevious = false, allocatedKeep = false;
        boolean startReady = false, endReady = false;
        try {
            cuMemAlloc(dParents, parents.length * 8L); allocatedParents = true;
            cuMemAlloc(dPrevious, previous.length); allocatedPrevious = true;
            cuMemAlloc(dKeep, candidates); allocatedKeep = true;
            cuEventCreate(start,0); startReady = true;
            cuEventCreate(end,0); endReady = true;
            Pointer parameters = Pointer.to(Pointer.to(dParents), Pointer.to(new int[]{parentCount}),
                Pointer.to(new int[]{n}), Pointer.to(dPrevious), Pointer.to(new int[]{required}), Pointer.to(dKeep));
            cuMemcpyHtoD(dParents,Pointer.to(parents),parents.length*8L);
            cuMemcpyHtoD(dPrevious,Pointer.to(previous),previous.length);
            for (int warmup = 0; warmup < 3; warmup++) {
                filterCpu(parents,n,previous,required,cpu,null);
                filterCpu(parents,n,previous,required,parallel,pool);
                cuLaunchKernel(kernel,(candidates+255)/256,1,1,256,1,1,0,null,parameters,null);
                cuCtxSynchronize();
            }
            final int repeats = 7;
            double[] singleMs = new double[repeats], parallelMs = new double[repeats];
            double[] kernelMs = new double[repeats], pipelineMs = new double[repeats];
            for (int repeat = 0; repeat < repeats; repeat++) {
                long t = System.nanoTime();
                filterCpu(parents,n,previous,required,cpu,null);
                singleMs[repeat] = (System.nanoTime()-t)/1e6;
                t = System.nanoTime();
                filterCpu(parents,n,previous,required,parallel,pool);
                parallelMs[repeat] = (System.nanoTime()-t)/1e6;
                cuEventRecord(start,null);
                cuLaunchKernel(kernel,(candidates+255)/256,1,1,256,1,1,0,null,parameters,null);
                cuEventRecord(end,null);
                cuEventSynchronize(end);
                float[] elapsed = {0};
                cuEventElapsedTime(elapsed,start,end);
                kernelMs[repeat] = elapsed[0];
                t = System.nanoTime();
                cuMemcpyHtoD(dParents,Pointer.to(parents),parents.length*8L);
                cuMemcpyHtoD(dPrevious,Pointer.to(previous),previous.length);
                cuLaunchKernel(kernel,(candidates+255)/256,1,1,256,1,1,0,null,parameters,null);
                cuMemcpyDtoH(Pointer.to(gpu),dKeep,candidates);
                cuCtxSynchronize();
                pipelineMs[repeat] = (System.nanoTime()-t)/1e6;
            }
            if (!Arrays.equals(cpu, parallel)) throw new AssertionError("Parallel CPU mismatch at n="+n);
            if (!Arrays.equals(cpu, gpu)) throw new AssertionError("GPU mismatch at n="+n);
            int survivors = 0;
            for (byte flag : gpu) survivors += flag;
            int expected = switch(n) { case 6 -> 61; case 8 -> 3229; case 10 -> 277290; default -> -1; };
            if (survivors != expected) throw new AssertionError("Unexpected survivor count at n="+n);
            System.out.printf(Locale.ROOT,"%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.2f%n", n,parentCount,
                candidates,survivors,median(singleMs),median(parallelMs),median(kernelMs),median(pipelineMs),
                median(parallelMs)/median(pipelineMs));
        } finally {
            if (endReady) cuEventDestroy(end);
            if (startReady) cuEventDestroy(start);
            if (allocatedKeep) cuMemFree(dKeep);
            if (allocatedPrevious) cuMemFree(dPrevious);
            if (allocatedParents) cuMemFree(dParents);
        }
    }

    static void benchmark() throws Exception {
        int threads = Integer.getInteger("gpu.cpuThreads",Math.min(8,Runtime.getRuntime().availableProcessors()));
        try (GpuDiametralFilter gpu = new GpuDiametralFilter(); ForkJoinPool pool = new ForkJoinPool(threads)) {
            System.out.println("GPU: " + gpu.deviceName + "; parallel CPU threads: " + threads);
            gpu.auditMoveDeltas();
            System.out.println("Filter only; medians of 7 runs after warmup. GPU pipeline includes input upload and flag download; startup, compilation, BFS, allocations, exact sorting, and disk output excluded.");
            System.out.println("n,parents,candidates,survivors,cpu_single_ms,cpu_parallel_ms,gpu_kernel_ms,gpu_pipeline_ms,speedup_vs_parallel");
            for (int n : new int[]{6,8,10}) gpu.benchmarkCase(n,pool);
            System.out.println("PASS: every GPU candidate flag matches CPU, independent standardized-deletion checks, and audited survivor counts.");
        }
    }

    public void close() {
        if(contextReady)cuCtxSetCurrent(context);
        if (moduleReady) { cuModuleUnload(module); moduleReady = false; }
        if (contextReady) { cuCtxDestroy(context); contextReady = false; }
    }
}
