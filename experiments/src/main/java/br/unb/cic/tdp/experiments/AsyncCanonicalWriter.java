package br.unb.cic.tdp.experiments;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.rocksdb.*;

/** Bounded shared aggregation; parallel CPU workers encode and insert RocksDB batches. */
final class AsyncCanonicalWriter implements AutoCloseable {
    static {RocksDB.loadLibrary();}
    private record Batch(byte[] data,int records){}
    private static final Batch FINISH=new Batch(null,-1),ABORT=new Batch(null,-2);
    // Construction values retain the weight for validation; completed archives retain aggregate counts.
    final int degree,floor,ceiling,horizon,payloadBytes,rowBytes,batchRecords;
    final boolean exact;
    final boolean decision;
    final WriteBufferSizing sizing;
    private final AtomicReference<byte[]> firstMatch=new AtomicReference<>();
    final Path staging,target;
    private final ArrayBlockingQueue<Batch> pending;
    private final ArrayBlockingQueue<byte[]> recycled;
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    private final CompletableFuture<LayerManifest> completed=new CompletableFuture<>();
    private final Thread worker;
    private final AtomicLong written=new AtomicLong();
    private final AtomicLong enqueueWaitNanos=new AtomicLong();
    private final AtomicInteger inFlight=new AtomicInteger();
    private final AtomicLong aggregateBytes=new AtomicLong(),aggregateFlushes=new AtomicLong(),nativeWrites=new AtomicLong(),nativeWriteBytes=new AtomicLong();
    private final AtomicInteger aggregateSegments=new AtomicInteger();
    private volatile boolean finishing,closed;
    private volatile long expectedOutputCount=-1;
    AsyncCanonicalWriter(Path root,int degree,int floor,int horizon,int batchRecords,int queueBatches) throws IOException {
        this(root,degree,floor,horizon,batchRecords,queueBatches,false);
    }
    AsyncCanonicalWriter(Path root,int degree,int floor,int horizon,int batchRecords,int queueBatches,boolean exact) throws IOException {
        this(root,degree,floor,horizon,batchRecords,queueBatches,exact,false);
    }
    AsyncCanonicalWriter(Path root,int degree,int floor,int horizon,int batchRecords,int queueBatches,boolean exact,boolean decision) throws IOException {
        this(root,degree,floor,horizon,batchRecords,queueBatches,exact,decision,WriteBufferSizing.system(degree,batchRecords,queueBatches));
    }
    AsyncCanonicalWriter(Path root,int degree,int floor,int horizon,int batchRecords,int queueBatches,boolean exact,boolean decision,WriteBufferSizing sizing) throws IOException {
        if(batchRecords<1||queueBatches<1)throw new IllegalArgumentException("Buffers and queue must be positive");
        this.degree=degree;this.floor=floor;this.horizon=horizon;this.batchRecords=batchRecords;
        if(decision&&!exact)throw new IllegalArgumentException("Decision output must be exact");
        this.exact=exact;this.decision=decision;ceiling=exact?floor:RetentionPlan.upper(degree);
        payloadBytes=PermutationRank.bytes(degree);rowBytes=payloadBytes+2;
        this.sizing=sizing;
        if(sizing.batchBytes()!=Math.multiplyExact(batchRecords,rowBytes))throw new IllegalArgumentException("Incompatible write buffer sizing");
        pending=new ArrayBlockingQueue<>(sizing.pendingBatches());recycled=new ArrayBlockingQueue<>(sizing.recycledBatches());
        WriteAggregation aggregation=sizing.aggregation();
        System.out.printf("ASYNC_WRITE_BUFFER n=%d mode=%s budget_MiB=%d pending_capacity=%d in_flight_capacity=%d batch_records=%d batch_bytes=%d recycle_capacity=%d aggregate_target_bytes=%d native_batch_max_bytes=%d flush_interval_ms=%d estimated_staging_bytes=%d%n",
            degree,sizing.budgetBytes()==0?"batches":"bytes",sizing.budgetBytes()>>20,sizing.pendingBatches(),sizing.inFlightBatches(),batchRecords,sizing.batchBytes(),sizing.recycledBatches(),aggregation.targetBytes(),aggregation.nativeEncodedBytes(),TimeUnit.NANOSECONDS.toMillis(aggregation.flushNanos()),sizing.estimatedMaximumBytes());
        target=exact?LayerCatalog.path(root,degree,floor):CanonicalLayerStore.path(root,degree,floor);Files.createDirectories(target.getParent());
        if(Files.exists(target))throw new IOException("Complete target already exists: "+target);
        staging=Files.createDirectory(target.getParent().resolve("."+(exact?"distance-":"floor-")+floor+"-"+UUID.randomUUID()+".partial"));
        worker=Thread.ofPlatform().name("canonical-layer-writer-n"+degree).start(this::writeLoop);
    }
    long writtenRecords(){return written.get();}
    boolean hasMatch(){return firstMatch.get()!=null;}
    byte[] firstMatch(){byte[] match=firstMatch.get();return match==null?null:match.clone();}
    int queuedBatches(){return pending.size()+inFlight.get()+aggregateSegments.get();}
    long queuedBytesEstimate(){return pending.size()*sizing.batchBytes()+inFlight.get()*sizing.aggregation().nativeRawBytes()+aggregateBytes.get();}
    long aggregateBytes(){return aggregateBytes.get();}
    long aggregateTargetBytes(){return sizing.aggregation().targetBytes();}
    long aggregateFlushes(){return aggregateFlushes.get();}
    long nativeWrites(){return nativeWrites.get();}
    long nativeWriteBytes(){return nativeWriteBytes.get();}
    int pendingCapacity(){return sizing.pendingBatches();}
    double enqueueWaitSeconds(){return enqueueWaitNanos.get()/1e9;}
    void check() throws IOException {Throwable error=failure.get();if(error!=null)throw new IOException("Asynchronous canonical persistence failed",error);}
    private byte[] acquire(){byte[] result=recycled.poll();return result==null?new byte[Math.multiplyExact(batchRecords,rowBytes)]:result;}
    private void recycle(Batch batch){if(batch.data.length==sizing.batchBytes())recycled.offer(batch.data);}
    void submit(byte[] rows,int records) throws IOException {
        if(rows==null||records<1||records>batchRecords||rows.length<records*rowBytes||rows.length>sizing.batchBytes())throw new IllegalArgumentException("Invalid write buffer");
        if(finishing||closed)throw new IOException("Canonical writer is closed");
        enqueue(new Batch(rows,records));
    }
    private void enqueue(Batch batch) throws IOException {
        checkOpen(batch);if(pending.offer(batch)){check();return;}
        long began=System.nanoTime();
        try{while(true){checkOpen(batch);if(pending.offer(batch,100,TimeUnit.MILLISECONDS)){check();return;}}}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Interrupted while queuing canonical records",interrupted);}
        finally{if(batch.data!=null)enqueueWaitNanos.addAndGet(System.nanoTime()-began);}
    }
    private void checkOpen(Batch batch) throws IOException {
        check();if(batch.data!=null&&(finishing||closed))throw new IOException("Canonical writer is closed");
    }
    Buffer buffer(){return new Buffer();}
    final class Buffer implements AutoCloseable {
        private byte[] rows;
        private int count;
        void append(byte[] omega,int distance,int weight) throws IOException {
            appendRank(PermutationRank.ofOmega(omega,degree),distance,weight);
        }
        void appendRank(long rank,int distance,int weight) throws IOException {
            if(exact&&distance!=floor)return;
            if(rank<0||rank>=SmallTables.FACTORIAL[degree])throw new IllegalArgumentException("Invalid canonical output rank");
            if(decision){check();byte[] word=new byte[degree],omega=new byte[degree+1];SmallTables.unrank(rank,degree,word,0);ToricOrbit.omegaFromWord(word,degree,omega);firstMatch.compareAndSet(null,PackedPermutation5.encode(omega,degree+1));return;}
            check();if(rows==null)rows=acquire();
            int offset=count*rowBytes;rows[offset]=(byte)distance;
            PermutationRank.encode(rank,degree,rows,offset+1);rows[offset+rowBytes-1]=(byte)weight;
            if(++count==batchRecords)flush();
        }
        void appendPacked(byte[] packed,int offset,int distance,int weight) throws IOException {
            if(exact&&distance!=floor)return;appendRank(PermutationRank.ofPackedOmega(packed,offset,degree),distance,weight);
        }
        void flush() throws IOException {if(count>0){byte[] owned=rows;int records=count;rows=null;count=0;submit(owned,records);}}
        public void close() throws IOException {flush();}
    }
    LayerManifest finish() throws IOException {
        return finish(-1);
    }
    LayerManifest finish(long expectedOutputCount) throws IOException {
        if(closed)throw new IOException("Canonical writer is closed");
        if(decision&&hasMatch())throw new IOException("A positive decision is not a complete exact layer");
        if(!finishing){this.expectedOutputCount=expectedOutputCount;finishing=true;enqueue(FINISH);}
        try{return completed.get();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Interrupted while finishing canonical persistence",interrupted);}
        catch(ExecutionException error){throw new IOException("Cannot complete canonical persistence",error.getCause());}
    }
    private void writeLoop() {
        try {
            long[] canonical=new long[12],weighted=new long[12];boolean commit=false;
            try(Cache cache=new LRUCache(RocksTuning.cacheBytes());
                Options options=RocksTuning.writerOptions(cache);
                RocksDB database=RocksDB.open(options,staging.resolve("db").toString());
                WriteOptions writeOptions=new WriteOptions().setDisableWAL(true).setSync(false)) {
                try(WriteDispatcher dispatcher=new WriteDispatcher(database,writeOptions,canonical,weighted)) {
                    Aggregator aggregate=sizing.aggregation().enabled()?new Aggregator(dispatcher):null;
                    while(true) {
                        check();Batch batch=pending.poll(100,TimeUnit.MILLISECONDS);
                        if(batch==FINISH){if(aggregate!=null)aggregate.flush();commit=true;break;}if(batch==ABORT)break;
                        if(batch!=null) {
                            if(aggregate==null)dispatcher.submit(batch);
                            else try{aggregate.add(batch);}finally{recycle(batch);}
                        }
                        if(aggregate!=null)aggregate.flushIfDue();
                        dispatcher.pollCompleted();
                    }
                    dispatcher.drain();
                    if(commit) {
                        try(FlushOptions flush=new FlushOptions().setWaitForFlush(true)){database.flush(flush);}
                        System.out.printf("ROCKSDB_FINAL n=%d records=%d aggregate_flushes=%d native_write_calls=%d native_write_bytes=%d SST_bytes=%d pending_compaction_bytes=%d%n",degree,written.get(),aggregateFlushes.get(),nativeWrites.get(),nativeWriteBytes.get(),
                            database.getLongProperty("rocksdb.total-sst-files-size"),database.getLongProperty("rocksdb.estimate-pending-compaction-bytes"));
                    }
                }
            }
            if(!commit){completed.completeExceptionally(new IOException("Canonical store construction aborted"));return;}
            if(expectedOutputCount>=0&&weighted[ceiling]!=expectedOutputCount)
                throw new IOException("Reference count mismatch before publication: n="+degree+" distance="+ceiling+" actual="+weighted[ceiling]+" expected="+expectedOutputCount);
            LayerManifest manifest=new LayerManifest(degree,floor,horizon,canonical,weighted,exact);
            manifest.write(staging);
            CompactRankArchive.build(staging,manifest);
            if(!Boolean.parseBoolean(System.getProperty("extension.compactKeepRocksDb","false")))
                try(var files=Files.walk(staging.resolve("db"))){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
            Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE);completed.complete(manifest);
        }catch(Throwable error){failure.compareAndSet(null,error);completed.completeExceptionally(error);}
        finally{aggregateBytes.set(0);aggregateSegments.set(0);}
    }
    /** One shared, segmented aggregate; producers retain their small transport buffers. */
    private final class Aggregator {
        private final WriteDispatcher dispatcher;
        private final WriteAggregation policy=sizing.aggregation();
        private final ArrayList<Batch> segments=new ArrayList<>();
        private byte[] current;
        private int currentRecords;
        private long records,started;
        Aggregator(WriteDispatcher dispatcher){this.dispatcher=dispatcher;}
        void add(Batch source) throws Exception {
            int offset=0;
            while(offset<source.records) {
                if(records==0)started=System.nanoTime();
                if(current==null) {
                    int capacity=(int)Math.min(policy.nativeRecords(),policy.targetRecords()-records);
                    current=new byte[Math.multiplyExact(capacity,rowBytes)];currentRecords=0;
                    aggregateBytes.addAndGet(current.length);aggregateSegments.incrementAndGet();
                }
                int copy=Math.min(source.records-offset,current.length/rowBytes-currentRecords);
                System.arraycopy(source.data,offset*rowBytes,current,currentRecords*rowBytes,copy*rowBytes);
                offset+=copy;currentRecords+=copy;records+=copy;
                if(currentRecords==current.length/rowBytes){segments.add(new Batch(current,currentRecords));current=null;currentRecords=0;}
                if(records==policy.targetRecords())flush();
            }
        }
        void flushIfDue() throws Exception {if(records>0&&System.nanoTime()-started>=policy.flushNanos())flush();}
        void flush() throws Exception {
            if(records==0)return;
            if(current!=null){segments.add(new Batch(current,currentRecords));current=null;currentRecords=0;}
            for(int i=0;i<segments.size();i++) {
                Batch batch=segments.get(i);dispatcher.submit(batch);segments.set(i,null);
                aggregateBytes.addAndGet(-batch.data.length);aggregateSegments.decrementAndGet();
            }
            segments.clear();records=0;aggregateFlushes.incrementAndGet();
        }
    }
    private final class WriteDispatcher implements AutoCloseable {
        private final ExecutorService writers=Executors.newFixedThreadPool(sizing.inFlightBatches()/2,Thread.ofPlatform().name("rocks-write-n"+degree+"-",0).factory());
        private final CompletionService<Void> done=new ExecutorCompletionService<>(writers);
        private final RocksDB database;
        private final WriteOptions options;
        private final long[] canonical,weighted;
        private int outstanding;
        WriteDispatcher(RocksDB database,WriteOptions options,long[] canonical,long[] weighted){this.database=database;this.options=options;this.canonical=canonical;this.weighted=weighted;}
        void submit(Batch batch) throws Exception {
            check();pollCompleted();
            if(outstanding>=sizing.inFlightBatches()){done.take().get();outstanding--;}
            inFlight.incrementAndGet();
            done.submit(()->{
                try{check();writeBatch(database,options,batch,canonical,weighted);return null;}
                catch(Throwable error){failure.compareAndSet(null,error);throw error;}
                finally{if(!sizing.aggregation().enabled())recycle(batch);inFlight.decrementAndGet();}
            });outstanding++;
        }
        void pollCompleted() throws Exception {Future<Void> finished;while((finished=done.poll())!=null){finished.get();outstanding--;}}
        void drain() throws Exception {while(outstanding>0){done.take().get();outstanding--;}}
        public void close() {
            // Let every submitted task release its buffers before closing the native database.
            writers.shutdown();boolean interrupted=Thread.interrupted();
            while(!writers.isTerminated())try{writers.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException ignored){interrupted=true;}
            if(interrupted)Thread.currentThread().interrupt();
        }
    }
    private void writeBatch(RocksDB database,WriteOptions options,Batch batch,long[] canonical,long[] weighted) throws Exception {
        // Native WriteBatch wire format: fixed64 sequence, fixed32 count, then Put(key,value) records.
        // Keys and values are <=127 bytes, so every length fits a single varint byte.
        byte[] encoded=new byte[Math.addExact(12,Math.multiplyExact(batch.records,payloadBytes+5))];
        for(int b=0;b<4;b++)encoded[8+b]=(byte)(batch.records>>>(8*b));
        long[] counts=new long[12],weights=new long[12];int out=12;
        for(int i=0;i<batch.records;i++) {
            int offset=i*rowBytes,distance=batch.data[offset]&255,weight=batch.data[offset+rowBytes-1]&255;
            if(distance<floor||distance>ceiling||weight<1||2*(degree+1)%weight!=0)
                throw new IOException("Invalid distance/orbit metadata in asynchronous buffer");
            encoded[out++]=1;encoded[out++]=(byte)payloadBytes;
            System.arraycopy(batch.data,offset+1,encoded,out,payloadBytes);out+=payloadBytes;
            encoded[out++]=2;encoded[out++]=(byte)(distance&15);encoded[out++]=(byte)weight;counts[distance]++;weights[distance]+=weight;
        }
        try(WriteBatch writes=new WriteBatch(encoded)){database.write(options,writes);}
        nativeWrites.incrementAndGet();
        nativeWriteBytes.addAndGet(encoded.length);
        synchronized(canonical){for(int d=0;d<12;d++){canonical[d]+=counts[d];weighted[d]+=weights[d];}}
        written.addAndGet(batch.records);
    }
    public void close() throws IOException {
        if(closed)return;closed=true;
        if(!completed.isDone()) {
            pending.clear();
            try{enqueue(ABORT);}catch(IOException error){worker.interrupt();}
        }
        try{worker.join();}catch(InterruptedException interrupted){worker.interrupt();Thread.currentThread().interrupt();throw new IOException("Interrupted closing canonical writer",interrupted);}
        check();
    }
}
