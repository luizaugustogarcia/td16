package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.*;
import jcuda.Pointer;
import jcuda.driver.*;
import static jcuda.driver.JCudaDriver.*;

/** All insertion, orbit, canonicalization and deletion mathematics run in CUDA. */
final class GpuCandidateGenerator implements AutoCloseable {
    record Output(byte[] packed,byte[] deletionKeys,int count){}
    final GpuStreamingCounter gpu;
    final ExtensionResolver resolver;
    final CanonicalLayerStore parents;
    final int n,floor,parentFloor,capacity,rawCapacity,payloadBytes,outBytes;
    final boolean dense;
    final int lookupKind;
    final CUstream stream=new CUstream();
    final CUfunction prepare=new CUfunction(),generate=new CUfunction();
    final CUevent start=new CUevent(),end=new CUevent(),completion=new CUevent();
    final CUdeviceptr rows=new CUdeviceptr(),members=new CUdeviceptr(),output=new CUdeviceptr(),keys=new CUdeviceptr(),counts=new CUdeviceptr();
    final Pointer hostRows=new Pointer(),hostOutput=new Pointer(),hostKeys=new Pointer(),hostCounts=new Pointer();
    final List<CUdeviceptr> deviceAllocations=new ArrayList<>();
    final List<Pointer> hostAllocations=new ArrayList<>();
    boolean streamReady,closed;
    int eventCount;
    long batches;double kernelMillis;
    GpuCandidateGenerator(ExtensionResolver resolver,CanonicalLayerStore parents,int n,int floor,int capacity) {
        this(resolver,parents,n,floor,capacity,0);
    }
    GpuCandidateGenerator(ExtensionResolver resolver,CanonicalLayerStore parents,int n,int floor,int capacity,int deviceIndex) {
        this.resolver=resolver;this.parents=parents;this.n=n;this.floor=floor;this.capacity=capacity;parentFloor=Math.max(0,floor-1);
        payloadBytes=PackedPermutation5.bytes(n+1);outBytes=payloadBytes+10;rawCapacity=Math.multiplyExact(capacity,2*n*n);
        this.gpu=resolver.retainDevice(deviceIndex);
        lookupKind=gpu.parentLookupKind(n-1,parentFloor);dense=lookupKind!=0;
        try {
            cuCtxSetCurrent(gpu.context.context);cuStreamCreate(stream,CUstream_flags.CU_STREAM_NON_BLOCKING);streamReady=true;
            cuEventCreate(start,0);eventCount=1;cuEventCreate(end,0);eventCount=2;
            cuEventCreate(completion,CUevent_flags.CU_EVENT_BLOCKING_SYNC|CUevent_flags.CU_EVENT_DISABLE_TIMING);eventCount=3;
            cuModuleGetFunction(prepare,gpu.module,"prepare_parent_members");cuModuleGetFunction(generate,gpu.module,"generate_extensions");
            allocate(rows,(long)capacity*parents.rowBytes);allocate(members,(long)capacity*2*n*8);
            allocate(output,(long)rawCapacity*outBytes);allocate(counts,24);
            allocate(keys,dense?1:(long)rawCapacity*n*parents.payloadBytes);
            pin(hostRows,(long)capacity*parents.rowBytes);pin(hostOutput,(long)rawCapacity*outBytes);pin(hostCounts,24);
            if(!dense)pin(hostKeys,(long)rawCapacity*n*parents.payloadBytes);
            System.out.printf("GPU_GENERATOR device=%d n=%d parent_batch=%d orbit_expansion=GPU canonicality=GPU deletion_canonicality=GPU lookups=%s%n",gpu.context.deviceOrdinal,n,capacity,lookupKind==2?"GPU-membership-bitmap":dense?"GPU-resident-exact-table":"RocksDB-batched-IO");
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    private void allocate(CUdeviceptr pointer,long bytes){cuMemAlloc(pointer,bytes);deviceAllocations.add(pointer);}
    private void pin(Pointer pointer,long bytes){cuMemAllocHost(pointer,bytes);hostAllocations.add(pointer);}
    static long deviceBytes(int n,int capacity,boolean dense) {
        long payload=PackedPermutation5.bytes(n+1),parentPayload=PackedPermutation5.bytes(n),raw=Math.multiplyExact((long)capacity,2L*n*n);
        return Math.addExact((long)capacity*(parentPayload+1+2L*n*8)+24,Math.addExact(raw*(payload+10),dense?1:raw*n*parentPayload));
    }
    private void synchronize(){if(eventCount>2){cuEventRecord(completion,stream);cuEventSynchronize(completion);}else cuStreamSynchronize(stream);}
    Output produce(byte[] input,ExtensionRun.Stats stats,ExtensionResolver resolver) throws IOException {
        gpu.activate();
        resolver.check();int records=input.length/parents.rowBytes;if(records>capacity)throw new IllegalArgumentException("Parent batch exceeds GPU capacity");
        hostRows.getByteBuffer(0,input.length).put(input);cuMemcpyHtoDAsync(rows,hostRows,input.length,stream);cuMemsetD32Async(counts,0,6,stream);
        cuEventRecord(start,stream);
        cuLaunchKernel(prepare,records,1,1,64,1,1,0,stream,Pointer.to(GpuStreamingCounter.integer(records),GpuStreamingCounter.integer(n-1),GpuStreamingCounter.integer(parents.rowBytes),Pointer.to(rows),Pointer.to(members),Pointer.to(counts)),null);
        int raw=records*2*n*n;
        cuLaunchKernel(generate,(raw+127)/128,1,1,128,1,1,0,stream,Pointer.to(GpuStreamingCounter.integer(records),GpuStreamingCounter.integer(n),GpuStreamingCounter.integer(floor),
            GpuStreamingCounter.integer(RetentionPlan.upper(n)),GpuStreamingCounter.integer(parentFloor),GpuStreamingCounter.integer(parents.rowBytes),Pointer.to(rows),Pointer.to(members),
            GpuStreamingCounter.integer(lookupKind),Pointer.to(gpu.table),Pointer.to(gpu.offsets),Pointer.to(gpu.membershipBits()),GpuStreamingCounter.integer(gpu.membershipDistance()),Pointer.to(output),Pointer.to(keys),Pointer.to(counts)),null);
        cuEventRecord(end,stream);cuMemcpyDtoHAsync(hostCounts,counts,24,stream);synchronize();
        float[] elapsed={0};cuEventElapsedTime(elapsed,start,end);kernelMillis+=elapsed[0];batches++;
        int[] totals=new int[6];hostCounts.getByteBuffer(0,24).order(ByteOrder.nativeOrder()).asIntBuffer().get(totals);
        if(totals[5]!=0)throw new IOException("GPU extension bounds or complete parent index inconsistent");
        stats.parents.addAndGet(records);stats.generated.addAndGet(totals[4]);stats.canonical.addAndGet(totals[1]);stats.rejected.addAndGet(totals[2]);stats.determined.addAndGet(totals[3]);
        int count=totals[0];if(count==0)return null;
        byte[] packed=new byte[Math.multiplyExact(count,outBytes)];
        cuMemcpyDtoHAsync(hostOutput,output,packed.length,stream);
        byte[] deletionKeys=null;
        if(!dense){deletionKeys=new byte[Math.multiplyExact(count,n*parents.payloadBytes)];cuMemcpyDtoHAsync(hostKeys,keys,deletionKeys.length,stream);}
        synchronize();hostOutput.getByteBuffer(0,packed.length).get(packed);
        if(!dense)hostKeys.getByteBuffer(0,deletionKeys.length).get(deletionKeys);
        return new Output(packed,deletionKeys,count);
    }
    /** CPU work is buffer handling and persistent-index I/O; no mathematical search. */
    void consume(Output packet,ExtensionRun.Stats stats,AsyncCanonicalWriter writer,ExtensionResolver resolver) throws IOException {
        // Large MultiGets pin too many SST blocks. Consume bounded slices instead of constructing millions of keys.
        final int chunk=128;
        try(AsyncCanonicalWriter.Buffer stored=writer.buffer();ExtensionResolver.Pending unknown=resolver.buffer(null)) {
            for(int first=0;first<packet.count&&!writer.hasMatch();first+=chunk)consumeSlice(packet,first,Math.min(packet.count,first+chunk),stats,writer,resolver,stored,unknown);
        }
    }
    private void consumeSlice(Output packet,int first,int end,ExtensionRun.Stats stats,AsyncCanonicalWriter writer,ExtensionResolver resolver,
                              AsyncCanonicalWriter.Buffer stored,ExtensionResolver.Pending unknown) throws IOException {
        resolver.check();byte[] packed=packet.packed,deletionKeys=packet.deletionKeys;
        int[] answers=null;int unresolved=0;
        if(!dense) {
            for(int i=first;i<end;i++)if((packed[i*outBytes+8]&0x40)!=0)unresolved++;
            answers=new int[unresolved*n];Arrays.fill(answers,-1);
            int[] candidates=new int[unresolved],active=new int[unresolved];int q=0;
            for(int i=first;i<end;i++)if((packed[i*outBytes+8]&0x40)!=0){candidates[q]=i;active[q]=q;q++;}
            // A single missing deletion rejects a child. Query further deletions only for surviving children.
            for(int omitted=0,remaining=unresolved;omitted<n&&remaining>0;omitted++) {
                byte[][] queries=new byte[remaining][];
                for(int i=0;i<remaining;i++) {
                    int offset=(candidates[active[i]]*n+omitted)*parents.payloadBytes;
                    queries[i]=Arrays.copyOfRange(deletionKeys,offset,offset+parents.payloadBytes);
                }
                int[] found=parents.distances(queries,parentFloor);int kept=0;
                for(int i=0;i<remaining;i++){int candidate=active[i];answers[candidate*n+omitted]=found[i];if(found[i]>=0)active[kept++]=candidate;}
                remaining=kept;
            }
        }
            int answer=0;
            for(int i=first;i<end;i++) {
                int offset=i*outBytes,metadata=packed[offset+8]&255,weight=packed[offset+outBytes-1]&255;
                long rank=0;for(int b=0;b<8;b++)rank|=(long)(packed[offset+b]&255)<<(8*b);
                if((metadata&0x40)!=0) {
                    int minimum=metadata&15,maximum=minimum;boolean rejected=false;
                    for(int j=0;j<n;j++){int distance=answers[answer++];if(distance<0)rejected=true;else{minimum=Math.min(minimum,distance);maximum=Math.max(maximum,distance);}}
                    if(rejected){if(parentFloor==0&&!writer.exact)throw new IOException("Missing member of complete parent set");stats.rejected.incrementAndGet();continue;}
                    int upper=Math.min(minimum+1,RetentionPlan.upper(n));if(maximum>upper)throw new IOException("Inconsistent deletion interval");
                    if(upper<floor)continue;
                    if(maximum==upper){stored.appendPacked(packed,offset+9,maximum,weight);stats.determined.incrementAndGet();}
                    else unknown.appendRank(rank,packed,offset+9,maximum,weight);
                }else if((metadata&0x80)!=0)unknown.appendRank(rank,packed,offset+9,metadata&15,weight);
                else stored.appendPacked(packed,offset+9,metadata,weight);
            }
    }
    public void close() {
        if(closed)return;closed=true;
        try {
        gpu.activate();if(streamReady)synchronize();
        for(Pointer pointer:hostAllocations)cuMemFreeHost(pointer);hostAllocations.clear();
        for(CUdeviceptr pointer:deviceAllocations)cuMemFree(pointer);deviceAllocations.clear();
        if(eventCount>0)cuEventDestroy(start);if(eventCount>1)cuEventDestroy(end);if(eventCount>2)cuEventDestroy(completion);eventCount=0;
        if(streamReady){cuStreamDestroy(stream);streamReady=false;}
        if(batches>0)System.out.printf(java.util.Locale.ROOT,"GPU_GENERATION_PROFILE device=%d n=%d batches=%d kernel_ms=%.3f%n",gpu.context.deviceOrdinal,n,batches,kernelMillis);
        }finally{resolver.releaseDevice();}
    }
}
