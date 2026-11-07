package br.unb.cic.tdp.experiments;

/** Estimated live persistence staging, excluding RocksDB caches and memtables. */
record WriteBufferSizing(int pendingBatches,int recycledBatches,int inFlightBatches,
                         long batchBytes,long budgetBytes,long estimatedMaximumBytes,WriteAggregation aggregation) {
    static WriteBufferSizing system(int degree,int batchRecords,int legacyBatches) {
        long mib=Long.parseLong(System.getProperty("extension.writeBufferMiB","0"));
        if(mib<0)throw new IllegalArgumentException("Write buffer MiB must be nonnegative");
        long budget=Math.multiplyExact(mib,1L<<20);
        if(budget>Runtime.getRuntime().maxMemory())throw new IllegalArgumentException("Write buffer budget exceeds the JVM maximum heap");
        int producers=0;
        if(budget>0) {
            producers=Integer.getInteger("count.cpuThreads",Runtime.getRuntime().availableProcessors());
            if(Boolean.parseBoolean(System.getProperty("count.gpu","true")))producers=Math.addExact(producers,GpuSettings.system().layout().totalSlots());
            // Allow additional callers and producer-owned batches waiting to enqueue.
            producers=Math.addExact(producers,32);
        }
        return calculate(degree,batchRecords,legacyBatches,RocksTuning.writeThreads(),producers,budget,WriteAggregation.system(degree,batchRecords));
    }
    static WriteBufferSizing calculate(int degree,int batchRecords,int legacyBatches,int writers,int producers,long budget) {
        return calculate(degree,batchRecords,legacyBatches,writers,producers,budget,WriteAggregation.disabled(degree,batchRecords));
    }
    static WriteBufferSizing calculate(int degree,int batchRecords,int legacyBatches,int writers,int producers,long budget,WriteAggregation aggregation) {
        if(batchRecords<1||legacyBatches<1||writers<1||producers<0||budget<0)throw new IllegalArgumentException("Invalid write buffer configuration");
        int payload=PermutationRank.bytes(degree);
        long raw=Math.multiplyExact(batchRecords,payload+2);
        int inFlight=Math.multiplyExact(2,writers);
        if(aggregation.rowBytes()!=payload+2||aggregation.nativeRecords()<1)throw new IllegalArgumentException("Incompatible write aggregation");
        if(budget==0) {
            if(aggregation.enabled())throw new IllegalArgumentException("Shared aggregation requires a positive --write-buffer-mib budget");
            return new WriteBufferSizing(legacyBatches,Math.addExact(legacyBatches,inFlight+2),inFlight,raw,0,0,aggregation);
        }
        int recycled=Math.max(8,Math.min(256,inFlight));
        long arrayBytes=Math.addExact(raw,32),encoded=aggregation.nativeEncodedBytes();
        // Full producer buffers, the dispatcher-held batch, queued/running workers,
        // and the bounded recycle pool can coexist. Each active writer also has
        // a Java encoder array and a native WriteBatch copy.
        long staging=Math.addExact(Math.addExact(Math.multiplyExact((long)producers+recycled+1,arrayBytes),
            Math.multiplyExact(inFlight,aggregation.nativeRawBytes()+128)),
            Math.multiplyExact(writers,Math.addExact(Math.multiplyExact(2,encoded+32),512)));
        if(aggregation.enabled())staging=Math.addExact(staging,aggregation.maximumAggregateBytes());
        long perPending=Math.addExact(arrayBytes,64); // Batch object and queue reference.
        if(budget-staging<perPending)throw new IllegalArgumentException("Write buffer budget must exceed staging reserve of "+staging+" bytes plus one batch of "+perPending+" bytes");
        int pending=(int)Math.min(Integer.MAX_VALUE-8,(budget-staging)/perPending);
        return new WriteBufferSizing(pending,recycled,inFlight,raw,budget,Math.addExact(staging,Math.multiplyExact(pending,perPending)),aggregation);
    }
}
