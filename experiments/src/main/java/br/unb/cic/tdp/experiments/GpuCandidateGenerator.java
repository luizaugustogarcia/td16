package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.*;
import jcuda.Pointer;
import jcuda.driver.*;
import static jcuda.driver.JCudaDriver.*;

/** All insertion, orbit, canonicalization and deletion mathematics run in CUDA. */
final class GpuCandidateGenerator implements AutoCloseable {
    record Output(byte[] packed,byte[] deletionKeys,byte[] rootMoveKeys,byte[] rootMoveFlags,int count){}
    final GpuStreamingCounter gpu;
    final ExtensionResolver resolver;
    final CanonicalLayerStore parents;
    final int n,floor,parentFloor,capacity,rawCapacity,payloadBytes,outBytes;
    final boolean dense,canonicalBloom,rootMoveStorePrepass;
    final int lookupKind;
    final CUstream stream=new CUstream();
    final CUfunction prepare=new CUfunction(),generate=new CUfunction(),prepareRootMoves=new CUfunction();
    final CUevent start=new CUevent(),end=new CUevent(),completion=new CUevent();
    final CUdeviceptr rows=new CUdeviceptr(),members=new CUdeviceptr(),output=new CUdeviceptr(),keys=new CUdeviceptr(),counts=new CUdeviceptr(),cuts=new CUdeviceptr(),rootMoveKeys=new CUdeviceptr(),rootMoveFlags=new CUdeviceptr();
    final Pointer hostRows=new Pointer(),hostOutput=new Pointer(),hostKeys=new Pointer(),hostCounts=new Pointer(),hostRootMoveKeys=new Pointer(),hostRootMoveFlags=new Pointer();
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
        canonicalBloom=!dense&&gpu.canonicalBloomCovers(n-1,parentFloor,parents.manifest.maxDistance);
        rootMoveStorePrepass=gpu.rootMovePrepass(parentFloor)&&parents.manifest.floor<=parentFloor&&parents.manifest.maxDistance==RetentionPlan.upper(n-1);
        try {
            cuCtxSetCurrent(gpu.context.context);cuStreamCreate(stream,CUstream_flags.CU_STREAM_NON_BLOCKING);streamReady=true;
            cuEventCreate(start,0);eventCount=1;cuEventCreate(end,0);eventCount=2;
            cuEventCreate(completion,CUevent_flags.CU_EVENT_BLOCKING_SYNC|CUevent_flags.CU_EVENT_DISABLE_TIMING);eventCount=3;
            cuModuleGetFunction(prepare,gpu.module,"prepare_parent_members");cuModuleGetFunction(generate,gpu.module,"generate_extensions");
            cuModuleGetFunction(prepareRootMoves,gpu.module,"prepare_root_move_keys");
            allocate(rows,(long)capacity*parents.rowBytes);allocate(members,(long)capacity*2*n*8);
            allocate(output,(long)rawCapacity*outBytes);allocate(counts,24);
            allocate(keys,dense?1:(long)rawCapacity*n*parents.payloadBytes);
            int[] moveCuts=ExactSorter.cuts(n);allocate(cuts,moveCuts.length*4L);cuMemcpyHtoD(cuts,Pointer.to(moveCuts),moveCuts.length*4L);
            allocate(rootMoveKeys,rootMoveStorePrepass?(long)rawCapacity*parents.payloadBytes:1);allocate(rootMoveFlags,rootMoveStorePrepass?rawCapacity:1);
            pin(hostRows,(long)capacity*parents.rowBytes);pin(hostOutput,(long)rawCapacity*outBytes);pin(hostCounts,24);
            if(!dense)pin(hostKeys,(long)rawCapacity*n*parents.payloadBytes);
            if(rootMoveStorePrepass){pin(hostRootMoveKeys,(long)rawCapacity*parents.payloadBytes);pin(hostRootMoveFlags,rawCapacity);}
            System.out.printf("GPU_GENERATOR device=%d n=%d parent_batch=%d orbit_expansion=GPU canonicality=GPU deletion_canonicality=GPU lookups=%s root_move_store_prepass=%s%n",gpu.context.deviceOrdinal,n,capacity,lookupKind==2?"GPU-membership-bitmap":dense?"GPU-resident-exact-table":canonicalBloom?"GPU-Bloom-negative+RocksDB-positive":"RocksDB-batched-IO",rootMoveStorePrepass);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    private void allocate(CUdeviceptr pointer,long bytes){cuMemAlloc(pointer,bytes);deviceAllocations.add(pointer);}
    private void pin(Pointer pointer,long bytes){cuMemAllocHost(pointer,bytes);hostAllocations.add(pointer);}
    static long deviceBytes(int n,int capacity,boolean dense) {
        long payload=PackedPermutation5.bytes(n+1),parentPayload=PackedPermutation5.bytes(n),raw=Math.multiplyExact((long)capacity,2L*n*n);
        long rootMoves=raw*(parentPayload+1);
        return Math.addExact((long)capacity*(parentPayload+1+2L*n*8)+24+4L*(n+1)*n*(n-1)/6,
            Math.addExact(raw*(payload+10)+rootMoves,dense?1:raw*n*parentPayload));
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
            GpuStreamingCounter.integer(lookupKind),Pointer.to(gpu.table),Pointer.to(gpu.offsets),Pointer.to(gpu.membershipBits()),GpuStreamingCounter.integer(gpu.membershipDistance()),
            Pointer.to(gpu.canonicalBloomBits()),GpuStreamingCounter.number(gpu.canonicalBloomMask()),Pointer.to(output),Pointer.to(keys),Pointer.to(counts)),null);
        cuEventRecord(end,stream);cuMemcpyDtoHAsync(hostCounts,counts,24,stream);synchronize();
        float[] elapsed={0};cuEventElapsedTime(elapsed,start,end);kernelMillis+=elapsed[0];batches++;
        int[] totals=new int[6];hostCounts.getByteBuffer(0,24).order(ByteOrder.nativeOrder()).asIntBuffer().get(totals);
        if(totals[5]!=0)throw new IOException("GPU extension bounds or complete parent index inconsistent");
        stats.parents.addAndGet(records);stats.generated.addAndGet(totals[4]);stats.canonical.addAndGet(totals[1]);stats.rejected.addAndGet(totals[2]);stats.determined.addAndGet(totals[3]);
        int count=totals[0];if(count==0)return null;
        byte[] moveKeys=null,moveFlags=null;
        if(rootMoveStorePrepass) {
            int cutCount=ExactSorter.cuts(n).length;
            cuLaunchKernel(prepareRootMoves,(count+127)/128,1,1,128,1,1,0,stream,Pointer.to(GpuStreamingCounter.integer(count),GpuStreamingCounter.integer(n),
                GpuStreamingCounter.integer(outBytes),Pointer.to(output),Pointer.to(cuts),GpuStreamingCounter.integer(cutCount),Pointer.to(rootMoveKeys),Pointer.to(rootMoveFlags)),null);
            moveKeys=new byte[Math.multiplyExact(count,parents.payloadBytes)];moveFlags=new byte[count];
            cuMemcpyDtoHAsync(hostRootMoveKeys,rootMoveKeys,moveKeys.length,stream);cuMemcpyDtoHAsync(hostRootMoveFlags,rootMoveFlags,count,stream);
        }
        byte[] packed=new byte[Math.multiplyExact(count,outBytes)];
        cuMemcpyDtoHAsync(hostOutput,output,packed.length,stream);
        byte[] deletionKeys=null;
        if(!dense){deletionKeys=new byte[Math.multiplyExact(count,n*parents.payloadBytes)];cuMemcpyDtoHAsync(hostKeys,keys,deletionKeys.length,stream);}
        synchronize();hostOutput.getByteBuffer(0,packed.length).get(packed);
        if(!dense)hostKeys.getByteBuffer(0,deletionKeys.length).get(deletionKeys);
        if(rootMoveStorePrepass){hostRootMoveKeys.getByteBuffer(0,moveKeys.length).get(moveKeys);hostRootMoveFlags.getByteBuffer(0,count).get(moveFlags);}
        return new Output(packed,deletionKeys,moveKeys,moveFlags,count);
    }
    /** CPU work is buffer handling and persistent-index I/O; no mathematical search. */
    void consume(Output packet,ExtensionRun.Stats stats,AsyncCanonicalWriter writer,ExtensionResolver resolver) throws IOException {
        // Large MultiGets pin too many SST blocks. Consume bounded slices instead of constructing millions of keys.
        final int chunk=Integer.getInteger("extension.lookupChunk",512);
        if(chunk<1||chunk>2048)throw new IllegalArgumentException("extension.lookupChunk must be 1..2048");
        if(packet.rootMoveFlags!=null)for(byte flag:packet.rootMoveFlags)
            if(flag==2)throw new IOException("Qualifying root move did not create a contractible bond");
        try(AsyncCanonicalWriter.Buffer stored=writer.buffer();ExtensionResolver.Pending unknown=resolver.buffer(null)) {
            for(int first=0;first<packet.count&&!writer.hasMatch();first+=chunk)
                consumeSlice(packet,first,Math.min(packet.count,first+chunk),stats,writer,resolver,stored,unknown);
        }
    }
    private boolean[] resolveRootMoves(Output packet,int[] eligible,int count) throws IOException {
        boolean[] settled=new boolean[count];if(count==0)return settled;
        for(int budget=Math.max(parentFloor,parents.manifest.floor);budget<=RetentionPlan.upper(n-1);budget++) {
            int queriesCount=0;for(int i=0;i<count;i++)if((packet.packed[eligible[i]*outBytes+8]&15)==budget)queriesCount++;
            if(queriesCount==0)continue;
            byte[][] queries=new byte[queriesCount][];int[] positions=new int[queriesCount];int q=0;
            for(int i=0;i<count;i++)if((packet.packed[eligible[i]*outBytes+8]&15)==budget) {
                int offset=eligible[i]*parents.payloadBytes;queries[q]=Arrays.copyOfRange(packet.rootMoveKeys,offset,offset+parents.payloadBytes);positions[q++]=i;
            }
            int[] found=parents.distances(queries,budget);
            for(int i=0;i<found.length;i++)if(found[i]<0)settled[positions[i]]=true;
        }
        return settled;
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
                byte[][] queries=new byte[remaining][];int[] queried=new int[remaining];int queryCount=0,kept=0;
                for(int i=0;i<remaining;i++) {
                    int candidate=active[i],record=candidates[candidate],offset=(record*n+omitted)*parents.payloadBytes;
                    if((deletionKeys[offset]&255)==255) {
                        answers[candidate*n+omitted]=packed[record*outBytes+8]&15;active[kept++]=candidate;
                    }else {
                        queries[queryCount]=Arrays.copyOfRange(deletionKeys,offset,offset+parents.payloadBytes);queried[queryCount++]=candidate;
                    }
                }
                if(queryCount>0) {
                    if(queryCount<queries.length)queries=Arrays.copyOf(queries,queryCount);
                    int[] found=parents.distances(queries,parentFloor);
                    for(int i=0;i<queryCount;i++){int candidate=queried[i];answers[candidate*n+omitted]=found[i];if(found[i]>=0)active[kept++]=candidate;}
                }
                remaining=kept;
            }
        }
            int answer=0,eligibleCount=0;int[] eligible=new int[end-first],eligibleDistance=new int[end-first];
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
                    else if(packet.rootMoveFlags!=null&&packet.rootMoveFlags[i]==1){eligible[eligibleCount]=i;eligibleDistance[eligibleCount++]=maximum;}
                    else unknown.appendRank(rank,packed,offset+9,maximum,weight);
                }else if((metadata&0x80)!=0) {
                    int budget=metadata&15;
                    if(packet.rootMoveFlags!=null&&packet.rootMoveFlags[i]==1){eligible[eligibleCount]=i;eligibleDistance[eligibleCount++]=budget;}
                    else unknown.appendRank(rank,packed,offset+9,budget,weight);
                }
                else stored.appendPacked(packed,offset+9,metadata,weight);
            }
            boolean[] rootMoveSettled=resolveRootMoves(packet,eligible,eligibleCount);
            for(int e=0;e<eligibleCount;e++) {
                int i=eligible[e],offset=i*outBytes,distance=eligibleDistance[e],weight=packed[offset+outBytes-1]&255;
                if(rootMoveSettled[e]) {
                    if(distance>=floor)stored.appendPacked(packed,offset+9,distance,weight);
                    stats.determined.incrementAndGet();stats.rootMoveResolved.incrementAndGet();
                }else {
                    long rank=0;for(int b=0;b<8;b++)rank|=(long)(packed[offset+b]&255)<<(8*b);
                    unknown.appendRank(rank,packed,offset+9,distance,weight);
                }
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
