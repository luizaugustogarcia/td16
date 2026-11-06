package br.unb.cic.tdp.experiments;

import java.io.*;
import java.nio.file.*;
import jcuda.Pointer;
import jcuda.driver.*;
import static jcuda.driver.JCudaDriver.*;

/** Diagnostic keys only; these samples never establish exhaustiveness. */
final class MembershipDiagnostics {
    static void generation() throws Exception {
        int n=Integer.getInteger("verify.n",14),width=PackedPermutation5.bytes(n+1),floor=RetentionPlan.upper(n);
        Path root=ExtensionConfig.system().dataDirectory();byte[] keys=Files.readAllBytes(Path.of(System.getProperty("sample.file")));
        int jobs=keys.length/width;Path temporary=Files.createTempDirectory(root,".generation-audit-");
        try(CanonicalLayerStore parents=new LayerCatalog(root).open(n-1,floor-1,Math.min(floor,RetentionPlan.upper(n-1)));
            AsyncCanonicalWriter writer=new AsyncCanonicalWriter(temporary,n,floor,n,64,2,true);
            ExtensionResolver resolver=new ExtensionResolver(n,floor,writer,new SmallTables(0),true,64,2,0.25,root);
            GpuCandidateGenerator generator=new GpuCandidateGenerator(resolver,parents,n,floor,jobs)) {
            byte[] rows=new byte[jobs*parents.rowBytes],omega=new byte[n+1],word=new byte[n],deletion=new byte[n-1],canonical=new byte[n];
            for(int i=0;i<jobs;i++) {
                PackedPermutation5.decode(keys,i*width,n+1,omega);ToricOrbit.wordFromOmega(omega,n,word);
                for(int j=0,k=0;j<n;j++)if(word[j]!=n)deletion[k++]=word[j];
                ToricOrbit.canonicalOmega(deletion,n-1,canonical);rows[i*parents.rowBytes]=(byte)parents.distance(canonical,floor-1);
                PackedPermutation5.encode(canonical,n,rows,i*parents.rowBytes+1);
                System.out.printf("SAMPLE_PARENT key=%s distance=%d%n",java.util.HexFormat.of().formatHex(keys,i*width,(i+1)*width),rows[i*parents.rowBytes]);
            }
            GpuCandidateGenerator.Output packet=generator.produce(rows,new ExtensionRun.Stats(),resolver);
            if(packet!=null)for(int j=0;j<packet.count();j++)for(int i=0;i<jobs;i++) {
                int offset=j*generator.outBytes;boolean same=true;for(int b=0;b<width;b++)if(keys[i*width+b]!=packet.packed()[offset+9+b])same=false;
                if(same) {
                    long rank=0;for(int b=0;b<8;b++)rank|=(long)(packet.packed()[offset+b]&255)<<(8*b);
                    System.out.printf("SAMPLE_GENERATION key=%s metadata=%d rank=%d%n",java.util.HexFormat.of().formatHex(keys,i*width,(i+1)*width),packet.packed()[offset+8]&255,rank);
                }
            }
        }
    }
    static void classify() throws Exception {
        int n=Integer.getInteger("verify.n",14),budget=Integer.getInteger("sample.budget",RetentionPlan.upper(n)-1),width=PackedPermutation5.bytes(n+1);
        byte[] keys=Files.readAllBytes(Path.of(System.getProperty("sample.file")));int jobs=keys.length/width;
        if(jobs<1||keys.length%width!=0)throw new IOException("Malformed sample key file");
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,Integer.getInteger("extension.gpuQuantum",4096))) {
            gpu.loadPersistedTables(ExtensionConfig.system().dataDirectory(),n-1);
            CUdeviceptr input=new CUdeviceptr(),output=new CUdeviceptr();cuMemAlloc(input,keys.length);
            try {
                cuMemAlloc(output,jobs*8L);
                try {
                    cuMemcpyHtoD(input,Pointer.to(keys),keys.length);CUfunction decode=new CUfunction();cuModuleGetFunction(decode,gpu.module,"decode_sample_ranks");
                    cuLaunchKernel(decode,(jobs+255)/256,1,1,256,1,1,0,null,Pointer.to(GpuStreamingCounter.integer(jobs),GpuStreamingCounter.integer(n),Pointer.to(input),Pointer.to(output)),null);
                    long[] ranks=new long[jobs];cuMemcpyDtoH(Pointer.to(ranks),output,jobs*8L);
                    int capacity=Math.max(jobs,Integer.getInteger("sample.capacity",jobs));long[] supplied=new long[capacity];
                    System.arraycopy(ranks,0,supplied,capacity-jobs,jobs);CUstream stream=new CUstream();cuStreamCreate(stream,CUstream_flags.CU_STREAM_NON_BLOCKING);
                    try(GpuStreamingCounter.Batch batch=gpu.new Batch(n,budget+1,budget+1,capacity,stream)) {
                        long began=System.nanoTime();byte[] decisions=batch.classifyRanks(supplied,capacity);
                        for(int i=0;i<jobs;i++)System.out.printf("SAMPLE_DECISION n=%d key=%s rank=%d budget=%d can_sort=%s pattern_degree=%d pattern_floor=%d%n",
                            n,java.util.HexFormat.of().formatHex(keys,i*width,(i+1)*width),ranks[i],budget,decisions[capacity-jobs+i]==0,gpu.tableDegree,gpu.patternFloors[gpu.tableDegree]);
                        System.out.printf(java.util.Locale.ROOT,"SAMPLE_SECONDS %.3f%n",(System.nanoTime()-began)/1e9);
                    }finally{cuStreamDestroy(stream);}
                }finally{cuMemFree(output);}
            }finally{cuMemFree(input);}
        }
    }
    static void sample() throws Exception {
        int n=Integer.getInteger("verify.n",14),jobs=Integer.getInteger("sample.records",2_000_000),width=PackedPermutation5.bytes(n+1);
        if(jobs<1)throw new IllegalArgumentException("Positive sample size required");
        Path root=ExtensionConfig.system().dataDirectory(),file=Path.of(System.getProperty("sample.file","experiments/logs/membership-sample.keys"));
        Files.createDirectories(file.toAbsolutePath().getParent());
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,64,GpuSettings.system().layout().devices()[0])) {
            gpu.membership=GpuMembershipTable.load(gpu,root,n,0);
            if(gpu.membership==null||gpu.membership.ordinaryMembers==0)throw new IOException("Nonempty complete membership table required");
            CUfunction sample=new CUfunction();cuModuleGetFunction(sample,gpu.module,"sample_membership");
            CUdeviceptr output=new CUdeviceptr(),errors=new CUdeviceptr();cuMemAlloc(output,Math.multiplyExact((long)jobs,width));
            try {
                cuMemAlloc(errors,4);
                try {
                    cuMemsetD32(errors,0,1);
                    cuLaunchKernel(sample,(jobs+255)/256,1,1,256,1,1,0,null,Pointer.to(GpuStreamingCounter.integer(jobs),GpuStreamingCounter.integer(n),
                        GpuStreamingCounter.number(0x51ab3e425d9L),Pointer.to(gpu.membership.bits),Pointer.to(output),Pointer.to(errors)),null);
                    int[] failed={0};cuMemcpyDtoH(Pointer.to(failed),errors,4);if(failed[0]!=0)throw new IOException("Sampling attempt limit exceeded for "+failed[0]+" jobs");
                    byte[] data=new byte[Math.multiplyExact(jobs,width)];cuMemcpyDtoH(Pointer.to(data),output,data.length);Files.write(file,data);
                    System.out.printf("MEMBERSHIP_SAMPLE n=%d records=%d key_bytes=%d file=%s exhaustive=false%n",n,jobs,width,file);
                }finally{cuMemFree(errors);}
            }finally{cuMemFree(output);}
        }
    }
}
