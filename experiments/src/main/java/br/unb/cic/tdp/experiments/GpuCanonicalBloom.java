package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.file.Path;
import jcuda.Pointer;
import jcuda.driver.*;
import static jcuda.driver.JCudaDriver.*;

/** GPU negative-membership filter for a complete union of canonical parent layers. */
final class GpuCanonicalBloom implements AutoCloseable {
    static final int BITS_PER_KEY=10,HASHES=7,BATCH_RECORDS=65536;
    final GpuStreamingCounter owner;
    final CUdeviceptr bits=new CUdeviceptr();
    final int degree,floor,ceiling;
    final long bitCount,bytes,mask,canonicalRecords;
    private boolean allocated;

    private GpuCanonicalBloom(GpuStreamingCounter owner,int degree,int floor,int ceiling,long canonicalRecords) {
        this.owner=owner;this.degree=degree;this.floor=floor;this.ceiling=ceiling;this.canonicalRecords=canonicalRecords;
        bitCount=bitsForRecords(canonicalRecords);bytes=bitCount/8;mask=bitCount-1;
        owner.activate();cuMemAlloc(bits,bytes);allocated=true;
    }
    static long bitsForRecords(long records) {
        if(records<1)throw new IllegalArgumentException("Bloom source must be nonempty");
        long wanted=Math.max(1024,Math.multiplyExact(records,(long)BITS_PER_KEY));
        long bits=Long.highestOneBit(wanted-1)<<1;
        if(bits<=0||bits>(1L<<38))throw new IllegalArgumentException("Canonical Bloom filter is too large");
        return bits;
    }
    static long reserveBytes(Path root,int degree,int floor,int ceiling) throws Exception {
        if(!Boolean.parseBoolean(System.getProperty("extension.gpuCanonicalBloom","true")))return 0;
        try(CanonicalLayerStore store=new LayerCatalog(root).open(degree,floor,ceiling)) {
            return bitsForRecords(store.manifest.canonicalSize())/8;
        }
    }
    static GpuCanonicalBloom load(GpuStreamingCounter owner,Path root,int degree,int floor,int ceiling,GpuCacheBudget budget) throws Exception {
        if(!Boolean.parseBoolean(System.getProperty("extension.gpuCanonicalBloom","true")))return null;
        long began=System.nanoTime();GpuCanonicalBloom result=null;
        try(CanonicalLayerStore store=new LayerCatalog(root).open(degree,floor,ceiling)) {
            long records=store.manifest.canonicalSize(),bytes=bitsForRecords(records)/8;
            owner.activate();long[] free={0},total={0};cuMemGetInfo(free,total);
            if(!budget.fits(bytes,free[0],0)) {
                System.out.printf("GPU_CANONICAL_BLOOM_SKIP device=%d degree=%d bytes=%d free_bytes=%d reserved_bytes=%d reason=insufficient-VRAM%n",
                    owner.context.deviceOrdinal,degree,bytes,free[0],budget.reservedBytes());
                return null;
            }
            result=new GpuCanonicalBloom(owner,degree,floor,ceiling,records);cuMemsetD32(result.bits,0,result.bytes/4);
            CUfunction populate=new CUfunction(),audit=new CUfunction();
            cuModuleGetFunction(populate,owner.module,"populate_canonical_bloom");cuModuleGetFunction(audit,owner.module,"audit_canonical_bloom");
            CUdeviceptr rows=new CUdeviceptr(),errors=new CUdeviceptr();boolean rowsReady=false,errorsReady=false;
            try(CanonicalLayerStore.BatchCursor cursor=store.source(floor,BATCH_RECORDS,Runtime.getRuntime().availableProcessors())) {
                cuMemAlloc(rows,(long)BATCH_RECORDS*store.rowBytes);rowsReady=true;cuMemAlloc(errors,8);errorsReady=true;cuMemsetD32(errors,0,2);
                long loaded=0;byte[] batch;
                while((batch=cursor.nextBatch(BATCH_RECORDS))!=null) {
                    int count=batch.length/store.rowBytes;loaded+=count;cuMemcpyHtoD(rows,Pointer.to(batch),batch.length);
                    Pointer args=Pointer.to(GpuStreamingCounter.integer(count),GpuStreamingCounter.integer(store.rowBytes),GpuStreamingCounter.integer(store.payloadBytes),
                        Pointer.to(rows),Pointer.to(result.bits),GpuStreamingCounter.number(result.mask));
                    cuLaunchKernel(populate,(count+255)/256,1,1,256,1,1,0,null,args,null);
                    cuLaunchKernel(audit,(count+255)/256,1,1,256,1,1,0,null,Pointer.to(
                        GpuStreamingCounter.integer(count),GpuStreamingCounter.integer(store.rowBytes),GpuStreamingCounter.integer(store.payloadBytes),
                        Pointer.to(rows),Pointer.to(result.bits),GpuStreamingCounter.number(result.mask),Pointer.to(errors)),null);
                }
                long[] missing={0};cuMemcpyDtoH(Pointer.to(missing),errors,8);
                if(loaded!=records||missing[0]!=0)throw new IOException("Canonical Bloom coverage mismatch: loaded="+loaded+" expected="+records+" missing="+missing[0]);
            }finally{if(errorsReady)cuMemFree(errors);if(rowsReady)cuMemFree(rows);}
            double falsePositive=Math.pow(1-Math.exp(-HASHES*(double)records/result.bitCount),HASHES);
            System.out.printf(java.util.Locale.ROOT,
                "GPU_CANONICAL_BLOOM device=%d degree=%d distances=%d..%d bytes=%d bits=%d hashes=%d canonicals=%d theoretical_false_positive=%.6f complete=true no_false_negatives_audited=true build_seconds=%.3f%n",
                owner.context.deviceOrdinal,degree,floor,ceiling,result.bytes,result.bitCount,HASHES,records,falsePositive,(System.nanoTime()-began)/1e9);
            return result;
        }catch(Exception|Error error){if(result!=null)result.close();throw error;}
    }
    public void close(){if(allocated){owner.activate();cuMemFree(bits);allocated=false;}}
}
