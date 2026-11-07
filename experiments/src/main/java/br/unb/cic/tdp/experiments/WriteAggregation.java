package br.unb.cic.tdp.experiments;

import java.util.concurrent.TimeUnit;

/** Shared aggregation target and the smaller independently encoded native write jobs. */
record WriteAggregation(long targetRecords,long flushNanos,int nativeRecords,int rowBytes,int payloadBytes) {
    static WriteAggregation disabled(int degree,int producerRecords) {
        int payload=PermutationRank.bytes(degree);
        return new WriteAggregation(0,0,producerRecords,payload+2,payload);
    }
    static WriteAggregation system(int degree,int producerRecords) {
        long mib=Long.parseLong(System.getProperty("extension.writeAggregateMiB","0"));
        if(mib<0)throw new IllegalArgumentException("Write aggregate MiB must be nonnegative");
        if(mib==0)return disabled(degree,producerRecords);
        long millis=Long.parseLong(System.getProperty("extension.writeFlushMillis","5000"));
        long nativeMiB=Long.parseLong(System.getProperty("extension.writeNativeBatchMiB","8"));
        return of(degree,Math.multiplyExact(mib,1L<<20),millis,Math.multiplyExact(nativeMiB,1L<<20));
    }
    static WriteAggregation of(int degree,long targetBytes,long flushMillis,long nativeBytes) {
        int payload=PermutationRank.bytes(degree),row=payload+2;
        if(targetBytes<row||flushMillis<1||nativeBytes<12L+payload+5||nativeBytes>Integer.MAX_VALUE-8)
            throw new IllegalArgumentException("Invalid write aggregation size, interval or native batch size");
        long target=targetBytes/row;
        int records=(int)Math.min(target,(nativeBytes-12)/(payload+5));
        return new WriteAggregation(target,Math.multiplyExact(flushMillis,TimeUnit.MILLISECONDS.toNanos(1)),records,row,payload);
    }
    boolean enabled(){return targetRecords>0;}
    long targetBytes(){return Math.multiplyExact(targetRecords,rowBytes);}
    long nativeRawBytes(){return Math.multiplyExact(nativeRecords,rowBytes);}
    long nativeEncodedBytes(){return Math.addExact(12,Math.multiplyExact((long)nativeRecords,payloadBytes+5));}
    long maximumAggregateBytes() {
        // The last segment can be partially full; include segment/list object overhead.
        long segments=Math.floorDiv(targetRecords+nativeRecords-1,nativeRecords);
        return Math.addExact(Math.multiplyExact(segments,nativeRawBytes()+128),256);
    }
}
