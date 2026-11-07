package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import jcuda.Pointer;
import jcuda.driver.*;
import static jcuda.driver.JCudaDriver.*;

/** Complete contiguous distance tails in immutable, exact CUDA rank indexes. */
final class GpuCompactTables implements AutoCloseable {
    static final int DEGREES=19,DISTANCES=12,WORDS=2*DEGREES+DEGREES*DISTANCES;
    final GpuStreamingCounter owner;
    final CUdeviceptr metadata=new CUdeviceptr();
    final long[] descriptors=new long[WORDS];
    private record DeviceArchive(CUdeviceptr pointer,long bytes){}
    final Map<Integer,List<DeviceArchive>> allocations=new LinkedHashMap<>();
    long bytes=WORDS*8L;
    private boolean closed;
    private static final class Address extends CUdeviceptr {long address(){return getNativePointer();}}
    GpuCompactTables(GpuStreamingCounter owner) {
        this.owner=owner;Arrays.fill(descriptors,0,DEGREES,255);owner.activate();cuMemAlloc(metadata,bytes);uploadMetadata();
    }
    int floor(int degree){return (int)descriptors[degree];}
    boolean covers(int degree,int floor,int ceiling){return degree>=1&&degree<DEGREES&&floor(degree)<=floor&&descriptors[DEGREES+degree]>=ceiling;}
    private void uploadMetadata(){cuMemcpyHtoD(metadata,Pointer.to(descriptors),descriptors.length*8L);}
    boolean load(Path root,int degree,int floor,int ceiling,GpuCacheBudget budget) throws Exception {
        if(degree>=1&&ceiling!=RetentionPlan.upper(degree))throw new IllegalArgumentException("GPU compact bounds require a complete upper tail");
        if(degree<1||covers(degree,floor,ceiling))return degree>=1;
        if(floor(degree)!=255)return false;
        LayerCatalog catalog=new LayerCatalog(root);Map<Path,LayerManifest> sources=new LinkedHashMap<>();
        for(int d=floor;d<=ceiling;d++) {Path path=catalog.find(degree,d);if(path==null)return false;sources.putIfAbsent(path,LayerManifest.read(path));}
        long wanted=0;for(LayerManifest manifest:sources.values())wanted=Math.addExact(wanted,CompactRankArchive.bytesFor(manifest));
        owner.activate();long[] free={0},total={0};cuMemGetInfo(free,total);if(!budget.fits(wanted,free[0],0))return false;
        long began=System.nanoTime(),loadedBytes=0;Map<Path,Address> pointers=new LinkedHashMap<>();List<DeviceArchive> staged=new ArrayList<>();
        try {
            for(var entry:sources.entrySet())try(CompactRankArchive archive=CompactRankArchive.open(entry.getKey(),entry.getValue())) {
                byte[] ordinary=archive.ordinaryImage(Integer.getInteger("extension.gpuCompactOrdinaryMembers",100_000));
                long size=ordinary==null?archive.bytes:ordinary.length;owner.activate();cuMemGetInfo(free,total);
                if(!budget.fits(size,free[0],0)){for(DeviceArchive p:staged)cuMemFree(p.pointer);return false;}
                Address pointer=new Address();cuMemAlloc(pointer,size);staged.add(new DeviceArchive(pointer,size));
                if(ordinary!=null)cuMemcpyHtoD(pointer,Pointer.to(ordinary),ordinary.length);else upload(archive.path,pointer,size);
                pointers.put(entry.getKey(),pointer);loadedBytes+=size;
            }
            for(int d=floor;d<=ceiling;d++)descriptors[2*DEGREES+degree*DISTANCES+d]=pointers.get(catalog.find(degree,d)).address();
            descriptors[degree]=floor;descriptors[DEGREES+degree]=ceiling;uploadMetadata();cuCtxSynchronize();allocations.put(degree,staged);bytes+=loadedBytes;
            System.out.printf(Locale.ROOT,"GPU_COMPACT_TABLE device=%d n=%d distances=%d..%d bytes=%d complete=true lookup=exact-bucketed-ranks build_seconds=%.3f%n",
                owner.context.deviceOrdinal,degree,floor,ceiling,loadedBytes,(System.nanoTime()-began)/1e9);
            return true;
        }catch(Exception|Error error){for(DeviceArchive archive:staged)cuMemFree(archive.pointer);throw error;}
    }
    void release(int degree) {
        List<DeviceArchive> removed=allocations.remove(degree);if(removed==null)return;
        owner.activate();descriptors[degree]=255;descriptors[DEGREES+degree]=0;
        Arrays.fill(descriptors,2*DEGREES+degree*DISTANCES,2*DEGREES+(degree+1)*DISTANCES,0);
        uploadMetadata();cuCtxSynchronize();
        for(DeviceArchive archive:removed){cuMemFree(archive.pointer);bytes-=archive.bytes;}
    }
    private void upload(Path path,CUdeviceptr target,long size) throws IOException {
        Pointer staging=new Pointer();int capacity=(int)Math.min(8L<<20,size);cuMemHostAlloc(staging,capacity,CU_MEMHOSTALLOC_PORTABLE);
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.READ)) {
            for(long offset=0;offset<size;offset+=capacity){int bytes=(int)Math.min(capacity,size-offset);ByteBuffer buffer=staging.getByteBuffer(0,bytes);CompactRankArchive.readFully(file,buffer,offset);cuMemcpyHtoD(target.withByteOffset(offset),staging,bytes);}
        }finally{cuMemFreeHost(staging);}
    }
    int[] query(int degree,long[] ranks,boolean canonical) {
        owner.activate();CUdeviceptr input=new CUdeviceptr(),output=new CUdeviceptr();cuMemAlloc(input,Math.max(8,ranks.length*8L));
        try {
            cuMemAlloc(output,Math.max(4,ranks.length*4L));
            try {
                if(ranks.length==0)return new int[0];cuMemcpyHtoD(input,Pointer.to(ranks),ranks.length*8L);CUfunction query=new CUfunction();cuModuleGetFunction(query,owner.module,"query_compact_distances");
                cuLaunchKernel(query,(ranks.length+255)/256,1,1,256,1,1,0,null,Pointer.to(GpuStreamingCounter.integer(degree),GpuStreamingCounter.integer(ranks.length),Pointer.to(input),Pointer.to(metadata),GpuStreamingCounter.integer(canonical?1:0),Pointer.to(output)),null);
                int[] distances=new int[ranks.length];cuMemcpyDtoH(Pointer.to(distances),output,ranks.length*4L);return distances;
            }finally{cuMemFree(output);}
        }finally{cuMemFree(input);}
    }
    public void close(){if(closed)return;closed=true;owner.activate();for(var degree:allocations.values())for(DeviceArchive archive:degree)cuMemFree(archive.pointer);allocations.clear();cuMemFree(metadata);}
}
