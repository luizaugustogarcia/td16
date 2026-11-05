package br.unb.cic.tdp.experiments;

import java.util.ArrayList;
import java.util.List;
import jcuda.Pointer;
import jcuda.driver.*;
import static jcuda.driver.JCudaDriver.*;
import static br.unb.cic.tdp.experiments.GpuStreamingCounter.integer;

/** Resumable GPU trie search; 255 means unresolved, never an exact rejection. */
final class GpuPrefixSearch implements AutoCloseable {
    record Result(byte[] depths,long trials,long launches) {
        int certified(){int count=0;for(byte d:depths)if(d>=0)count++;return count;}
    }
    private final GpuStreamingCounter gpu;
    private final CUmodule module=new CUmodule();
    private final CUfunction initialize=new CUfunction(),advance=new CUfunction();
    private final List<CUdeviceptr> allocations=new ArrayList<>();
    private final int n,capacity,quantum;
    private CUdeviceptr roots,trie,cuts,cutOffsets,ranks,words,nodes,cursors,depths,certificates,trials,pending;
    private boolean loaded;

    GpuPrefixSearch(GpuStreamingCounter gpu,int n,int budget,int capacity,int moves) throws Exception {
        this.gpu=gpu;this.n=n;this.capacity=capacity;quantum=gpu.quantum;
        gpu.activate();
        try {
            String ptx=ExperimentResources.ptx("prefix-search",gpu.context.architecture,
                ()->ExperimentResources.read("cuda/prefix-search.cu"));
            cuModuleLoadData(module,ptx);loaded=true;
            cuModuleGetFunction(initialize,module,"initialize_prefix");cuModuleGetFunction(advance,module,"advance_prefix");
            PrefixTrie patterns=new PrefixTrie(n,budget,moves);
            roots=upload(patterns.roots);trie=upload(patterns.records);
            int[] starts=new int[n+2];List<int[]> byDegree=new ArrayList<>();int count=0;
            for(int m=0;m<=n;m++){starts[m]=count;int[] row=ExactSorter.cuts(m);byDegree.add(row);count+=row.length;}
            starts[n+1]=count;int[] all=new int[count];
            for(int m=0;m<=n;m++)System.arraycopy(byDegree.get(m),0,all,starts[m],byDegree.get(m).length);
            cuts=upload(all);cutOffsets=upload(starts);
            ranks=allocate(capacity*8L);words=allocate(capacity*4L*18);
            nodes=allocate(capacity*4L*4);cursors=allocate(capacity*4L*4);
            depths=allocate(capacity);certificates=allocate(capacity);trials=allocate(capacity*8L);pending=allocate(4);
            cuCtxSynchronize();
        }catch(Exception|Error error){close();throw error;}
    }

    private CUdeviceptr allocate(long bytes){CUdeviceptr p=new CUdeviceptr();cuMemAlloc(p,Math.max(4,bytes));allocations.add(p);return p;}
    private CUdeviceptr upload(int[] values){CUdeviceptr p=allocate(values.length*4L);if(values.length>0)cuMemcpyHtoD(p,Pointer.to(values),values.length*4L);return p;}

    Result search(long[] input,int jobs,Runnable cancellationCheck) {
        if(jobs<1||jobs>capacity||jobs>input.length)throw new IllegalArgumentException("Invalid prefix batch");
        gpu.activate();cuMemcpyHtoD(ranks,Pointer.to(input),jobs*8L);
        cuLaunchKernel(initialize,(jobs+127)/128,1,1,128,1,1,0,null,
            Pointer.to(integer(jobs),integer(n),Pointer.to(ranks),Pointer.to(roots),Pointer.to(trie),Pointer.to(words),
                Pointer.to(nodes),Pointer.to(cursors),Pointer.to(depths),Pointer.to(certificates),Pointer.to(trials)),null);
        long launches=0;int[] active={jobs};
        while(active[0]>0) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("GPU prefix search interrupted");
            if(cancellationCheck!=null)cancellationCheck.run();
            cuMemsetD32(pending,0,1);
            cuLaunchKernel(advance,(jobs+127)/128,1,1,128,1,1,0,null,
                Pointer.to(integer(jobs),integer(quantum),Pointer.to(trie),Pointer.to(cuts),Pointer.to(cutOffsets),
                    Pointer.to(words),Pointer.to(nodes),Pointer.to(cursors),Pointer.to(depths),Pointer.to(certificates),Pointer.to(trials),Pointer.to(pending)),null);
            cuMemcpyDtoH(Pointer.to(active),pending,4);launches++;
        }
        byte[] output=new byte[jobs];long[] visits=new long[jobs];
        cuMemcpyDtoH(Pointer.to(output),certificates,jobs);cuMemcpyDtoH(Pointer.to(visits),trials,jobs*8L);
        long total=0;for(int i=0;i<jobs;i++) {
            if(output[i]==(byte)254)throw new AssertionError("Prefix gain/contraction mismatch at input "+i);
            if(output[i]!=(byte)255&&(output[i]<0||output[i]>3))throw new AssertionError("Invalid prefix certificate");
            total+=visits[i];
        }
        return new Result(output,total,launches);
    }

    @Override public void close(){gpu.activate();for(int i=allocations.size()-1;i>=0;i--)cuMemFree(allocations.get(i));allocations.clear();if(loaded){cuModuleUnload(module);loaded=false;}}
}
