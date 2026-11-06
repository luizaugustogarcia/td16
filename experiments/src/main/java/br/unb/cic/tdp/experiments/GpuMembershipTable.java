package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.file.Path;
import jcuda.Pointer;
import jcuda.driver.*;
import static jcuda.driver.JCudaDriver.*;

/** Immutable, complete diametral membership; every rank and offset is 64-bit. */
final class GpuMembershipTable implements AutoCloseable {
    final GpuStreamingCounter owner;
    final CUdeviceptr bits=new CUdeviceptr();
    final int degree,distance;
    final long bytes,canonicalRecords,ordinaryMembers;
    private boolean allocated;

    static long bytesForDegree(int degree) {
        if(degree<1||degree>18)throw new IllegalArgumentException("Membership degree must be 1..18");
        return ((SmallTables.FACTORIAL[degree]+31)/32)*4;
    }
    private GpuMembershipTable(GpuStreamingCounter owner,int degree,int distance,long canonicalRecords,long ordinaryMembers) {
        this.owner=owner;this.degree=degree;this.distance=distance;this.canonicalRecords=canonicalRecords;this.ordinaryMembers=ordinaryMembers;
        bytes=bytesForDegree(degree);owner.activate();cuMemAlloc(bits,bytes);allocated=true;
    }
    private static boolean fits(GpuStreamingCounter owner,long bytes,GpuCacheBudget budget) {
        owner.activate();long[] free={0},total={0};cuMemGetInfo(free,total);
        boolean fits=budget.fits(bytes,free[0],0);
        if(!fits)System.out.printf("GPU_MEMBERSHIP_SKIP device=%d bitmap_bytes=%d free_bytes=%d reserved_bytes=%d reason=insufficient-VRAM%n",owner.context.deviceOrdinal,bytes,free[0],budget.reservedBytes());
        return fits;
    }
    static GpuMembershipTable load(GpuStreamingCounter owner,Path root,int degree,long reserve) throws Exception {
        return load(owner,root,degree,new GpuCacheBudget(reserve,0.95));
    }
    static GpuMembershipTable load(GpuStreamingCounter owner,Path root,int degree,GpuCacheBudget budget) throws Exception {
        int distance=RetentionPlan.upper(degree);LayerCatalog catalog=new LayerCatalog(root);
        if(!catalog.has(degree,distance)||!fits(owner,bytesForDegree(degree),budget))return null;
        long began=System.nanoTime();GpuMembershipTable result=null;
        try(CanonicalLayerStore store=catalog.open(degree,distance,distance);
            CanonicalLayerStore.BatchCursor cursor=store.source(distance,16384,Runtime.getRuntime().availableProcessors())) {
            result=new GpuMembershipTable(owner,degree,distance,store.manifest.canonical[distance],store.manifest.weighted[distance]);
            cuMemsetD32(result.bits,0,result.bytes/4);
            CUfunction populate=new CUfunction();cuModuleGetFunction(populate,owner.module,"populate_membership");
            CUdeviceptr rows=new CUdeviceptr(),errors=new CUdeviceptr();boolean rowsReady=false,errorsReady=false;
            try {
                cuMemAlloc(rows,16384L*store.rowBytes);rowsReady=true;
                cuMemAlloc(errors,8);errorsReady=true;cuMemsetD32(errors,0,2);
                long loaded=0;byte[] batch;
                while((batch=cursor.nextBatch(16384))!=null) {
                    int records=batch.length/store.rowBytes,jobs=records*2*(degree+1);loaded+=records;
                    cuMemcpyHtoD(rows,Pointer.to(batch),batch.length);
                    cuLaunchKernel(populate,(jobs+255)/256,1,1,256,1,1,0,null,Pointer.to(
                        GpuStreamingCounter.integer(records),GpuStreamingCounter.integer(degree),GpuStreamingCounter.integer(distance),
                        GpuStreamingCounter.integer(store.rowBytes),Pointer.to(rows),Pointer.to(result.bits),Pointer.to(errors)),null);
                }
                long[] malformed={0};cuMemcpyDtoH(Pointer.to(malformed),errors,8);
                if(malformed[0]!=0||loaded!=result.canonicalRecords)
                    throw new IOException("Membership source coverage mismatch: degree="+degree+" loaded="+loaded+" expected="+result.canonicalRecords+" malformed="+malformed[0]);
                result.verifyCardinality();
            }finally{if(errorsReady)cuMemFree(errors);if(rowsReady)cuMemFree(rows);}
            result.log("persisted-canonicals",began);return result;
        }catch(Exception|Error error){if(result!=null)result.close();throw error;}
    }
    GpuMembershipTable copyTo(GpuStreamingCounter target,long reserve) throws Exception {
        return copyTo(target,new GpuCacheBudget(reserve,0.95));
    }
    GpuMembershipTable copyTo(GpuStreamingCounter target,GpuCacheBudget budget) throws Exception {
        if(!fits(target,bytes,budget))return null;
        long began=System.nanoTime();GpuMembershipTable result=new GpuMembershipTable(target,degree,distance,canonicalRecords,ordinaryMembers);
        Pointer staging=new Pointer();boolean pinned=false;
        try {
            long chunk=Math.min(16L<<20,bytes);cuMemHostAlloc(staging,chunk,CU_MEMHOSTALLOC_PORTABLE);pinned=true;
            for(long offset=0;offset<bytes;offset+=chunk) {
                long count=Math.min(chunk,bytes-offset);
                owner.activate();cuMemcpyDtoH(staging,bits.withByteOffset(offset),count);
                target.activate();cuMemcpyHtoD(result.bits.withByteOffset(offset),staging,count);
            }
            result.verifyCardinality();result.log("copy-from-device-"+owner.context.deviceOrdinal,began);return result;
        }catch(Exception|Error error){result.close();throw error;}
        finally{target.activate();if(pinned)cuMemFreeHost(staging);}
    }
    private void verifyCardinality() throws IOException {
        owner.activate();CUdeviceptr count=new CUdeviceptr();cuMemAlloc(count,8);
        try {
            cuMemsetD32(count,0,2);CUfunction audit=new CUfunction();cuModuleGetFunction(audit,owner.module,"audit_membership");
            int blocks=(int)Math.min(4096,(bytes/4+255)/256);
            cuLaunchKernel(audit,blocks,1,1,256,1,1,0,null,Pointer.to(Pointer.to(bits),GpuStreamingCounter.number(bytes/4),Pointer.to(count)),null);
            long[] actual={0};cuMemcpyDtoH(Pointer.to(actual),count,8);
            if(actual[0]!=ordinaryMembers)throw new IOException("Membership cardinality mismatch: degree="+degree+" actual="+actual[0]+" expected="+ordinaryMembers);
        }finally{cuMemFree(count);}
    }
    private void log(String source,long began) {
        System.out.printf(java.util.Locale.ROOT,"GPU_MEMBERSHIP device=%d degree=%d distance=%d bytes=%d bits_per_permutation=1 canonicals=%d members=%d complete=true cardinality_verified=true build_seconds=%.3f source=%s%n",
            owner.context.deviceOrdinal,degree,distance,bytes,canonicalRecords,ordinaryMembers,(System.nanoTime()-began)/1e9,source);
    }
    public void close(){if(allocated){owner.activate();cuMemFree(bits);allocated=false;}}
}
