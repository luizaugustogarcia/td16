package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.math.BigInteger;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.rocksdb.*;

/** Exact membership and streaming iteration over persisted canonical layers. */
final class CanonicalLayerStore implements AutoCloseable {
    static {RocksDB.loadLibrary();}
    final Path directory;
    final LayerManifest manifest;
    final int payloadBytes,rowBytes;
    final Options options;
    final BloomFilter filter;
    final Cache cache;
    final RocksDB database;
    record Part(CanonicalLayerStore store,int mask){}
    private final List<Part> parts;
    private final ThreadLocal<byte[][]> lookup;
    CanonicalLayerStore(Path directory) throws IOException {
        parts=null;
        this.directory=directory;manifest=LayerManifest.read(directory);
        payloadBytes=PackedPermutation5.bytes(manifest.degree+1);rowBytes=payloadBytes+1;
        lookup=ThreadLocal.withInitial(()->new byte[][]{new byte[payloadBytes+(manifest.legacy()?1:0)],new byte[1]});
        filter=new BloomFilter(10,false);
        cache=new LRUCache(RocksTuning.cacheBytes());
        // Keep whole legacy filters resident in the table reader rather than thrashing the data cache.
        // Partitioned SSTs always cache their small partitions; only their tiny top levels stay outside it.
        options=new Options().setMaxOpenFiles(1024).setTableFormatConfig(RocksTuning.table(filter,cache).setCacheIndexAndFilterBlocks(false));
        try{database=RocksDB.openReadOnly(options,directory.resolve("db").toString());}
        catch(RocksDBException error){options.close();filter.close();cache.close();throw new IOException("Cannot open canonical layers "+directory,error);}
    }
    /** A disjoint union of selected, complete distance layers; owns its physical stores. */
    CanonicalLayerStore(Path root,int degree,int floor,Map<Path,Integer> selected) throws IOException {
        directory=root;database=null;options=null;filter=null;cache=null;lookup=null;
        payloadBytes=PackedPermutation5.bytes(degree+1);rowBytes=payloadBytes+1;
        parts=new ArrayList<>();long[] canonical=new long[12],weighted=new long[12];int mask=0,horizon=degree;
        try {
            for(var entry:selected.entrySet()) {
                CanonicalLayerStore store=new CanonicalLayerStore(entry.getKey());parts.add(new Part(store,entry.getValue()));
                if((mask&entry.getValue())!=0)throw new IOException("Overlapping exact layer providers");
                mask|=entry.getValue();horizon=Math.max(horizon,store.manifest.horizon);
                for(int d=0;d<12;d++)if((entry.getValue()&(1<<d))!=0){canonical[d]=store.manifest.canonical[d];weighted[d]=store.manifest.weighted[d];}
            }
            boolean empty=Arrays.stream(weighted).sum()==0;
            manifest=new LayerManifest(degree,floor,horizon,canonical,weighted,empty||Integer.bitCount(mask)==1);
        }catch(IOException|RuntimeException error){for(Part part:parts)part.store.close();throw error;}
    }
    static Path path(Path root,int degree,int floor){return root.resolve(String.format(Locale.ROOT,"n-%02d/floor-%02d",degree,floor));}
    static Path find(Path root,int degree,int requiredFloor) throws IOException {
        Path parent=root.resolve(String.format(Locale.ROOT,"n-%02d",degree));
        if(!Files.isDirectory(parent))return null;
        Path best=null;int bestFloor=-1;
        try(var directories=Files.list(parent)) {
            for(Path directory:directories.toList()) {
                if(!directory.getFileName().toString().startsWith("floor-")||!Files.isRegularFile(directory.resolve("manifest.properties")))continue;
                LayerManifest manifest=LayerManifest.read(directory);
                if(manifest.degree!=degree)throw new IOException("Layer directory has wrong degree: "+directory);
                if(manifest.floor<=requiredFloor&&manifest.floor>bestFloor){best=directory;bestFloor=manifest.floor;}
            }
        }
        return best;
    }
    int distance(byte[] canonicalOmega,int requiredFloor) throws IOException {
        if(requiredFloor<manifest.floor)throw new IllegalArgumentException("Requested distance layer was not retained");
        if(parts!=null) {
            for(Part part:parts){int d=part.store.distance(canonicalOmega,Math.max(requiredFloor,part.store.manifest.floor));if(d>=0&&(part.mask&(1<<d))!=0)return d;}
            return -1;
        }
        byte[][] local=lookup.get();byte[] key=local[0],value=local[1];
        PackedPermutation5.encode(canonicalOmega,manifest.degree+1,key,manifest.legacy()?1:0);
        try {
            if(manifest.legacy()) {
                for(int d=requiredFloor;d<=manifest.maxDistance;d++) {
                    key[0]=(byte)d;int size=database.get(key,value);
                    if(size==RocksDB.NOT_FOUND)continue;
                    if(size!=1||value[0]<=0)throw new IOException("Malformed legacy canonical layer record");
                    return d;
                }
                return -1;
            }
            int size=database.get(key,value);
            if(size==RocksDB.NOT_FOUND)return -1;
            if(size!=1)throw new IOException("Malformed canonical distance value");
            int distance=checkedDistance(value[0]);return distance>=requiredFloor?distance:-1;
        }catch(RocksDBException error){throw new IOException("Canonical membership lookup failed",error);}
    }
    private int checkedDistance(byte value) throws IOException {
        int distance=value&255;
        if((distance&0xf0)!=0||distance<manifest.floor||distance>manifest.maxDistance)
            throw new IOException("Malformed four-bit canonical distance");
        return distance;
    }
    int[] distances(byte[][] keys,int requiredFloor) throws IOException {
        if(requiredFloor<manifest.floor)throw new IllegalArgumentException("Requested distance layer was not retained");
        int[] distances=new int[keys.length];Arrays.fill(distances,-1);
        if(parts!=null) {
            int unresolved=keys.length;int[] positions=new int[unresolved];for(int i=0;i<unresolved;i++)positions[i]=i;
            for(Part part:parts) {
                if(unresolved==0)break;
                byte[][] pending=new byte[unresolved][];for(int i=0;i<unresolved;i++)pending[i]=keys[positions[i]];
                int[] found=part.store.distances(pending,Math.max(requiredFloor,part.store.manifest.floor));int remaining=0;
                for(int i=0;i<found.length;i++) {
                    int position=positions[i],distance=found[i];
                    if(distance>=0&&(part.mask&(1<<distance))!=0)distances[position]=distance;
                    else positions[remaining++]=position;
                }
                unresolved=remaining;
            }
            return distances;
        }
        try {
            if(manifest.legacy()) {
                byte[] key=new byte[payloadBytes+1],value=new byte[1];
                for(int i=0;i<keys.length;i++) {
                    System.arraycopy(keys[i],0,key,1,payloadBytes);
                    for(int d=requiredFloor;d<=manifest.maxDistance;d++){key[0]=(byte)d;if(database.get(key,value)!=RocksDB.NOT_FOUND){distances[i]=d;break;}}
                }
            }else {
                // Bound block pins per JNI call, independently of the producer packet size.
                final int chunk=RocksTuning.multiGetKeys();List<byte[]> input=Arrays.asList(keys);
                for(int first=0;first<keys.length;first+=chunk) {
                    List<byte[]> values=database.multiGetAsList(input.subList(first,Math.min(keys.length,first+chunk)));
                    for(int i=0;i<values.size();i++){byte[] value=values.get(i);if(value==null)continue;if(value.length!=1)throw new IOException("Malformed canonical distance value");
                        int d=checkedDistance(value[0]);if(d>=requiredFloor)distances[first+i]=d;}
                }
            }
            return distances;
        }catch(RocksDBException error){throw new IOException("Batched canonical membership lookup failed",error);}
    }
    BatchCursor cursor(int minimumDistance) throws IOException {return parts==null?new Cursor(minimumDistance):source(minimumDistance,0,1);}
    interface BatchCursor extends AutoCloseable {
        byte[] nextBatch(int records) throws IOException;
        @Override void close() throws IOException;
    }
    BatchCursor source(int minimumDistance,int batchRecords,int threads) throws IOException {
        if(parts!=null)return new BatchCursor() {
            int index;BatchCursor active;
            public byte[] nextBatch(int records) throws IOException {
                while(index<parts.size()) {
                    Part part=parts.get(index);
                    if(active==null)active=part.store.source(minimumDistance,records,threads);
                    byte[] input=active.nextBatch(records);
                    if(input==null){active.close();active=null;index++;continue;}
                    int out=0;
                    for(int offset=0;offset<input.length;offset+=rowBytes)if((part.mask&(1<<(input[offset]&255)))!=0) {
                        if(offset!=out)System.arraycopy(input,offset,input,out,rowBytes);out+=rowBytes;
                    }
                    if(out>0)return out==input.length?input:Arrays.copyOf(input,out);
                }
                return null;
            }
            public void close() throws IOException {if(active!=null)active.close();index=parts.size();}
        };
        if(threads<=1||manifest.canonicalSize()<Long.getLong("extension.parallelReadThreshold",1000000L))return cursor(minimumDistance);
        return new ParallelCursor(minimumDistance,batchRecords,threads);
    }
    final class Cursor implements BatchCursor {
        private final ReadOptions reads;
        private final RocksIterator iterator;
        private final Slice upperSlice;
        private final byte[] upper;
        private final int minimumDistance;
        Cursor(int minimumDistance){this(minimumDistance,null,null);}
        Cursor(int minimumDistance,byte[] lower,byte[] upper){
            this.minimumDistance=Math.max(minimumDistance,manifest.floor);
            this.upper=upper;upperSlice=upper==null?null:new Slice(upper);
            reads=new ReadOptions().setFillCache(false).setReadaheadSize(4L<<20);
            if(upperSlice!=null)reads.setIterateUpperBound(upperSlice);
            iterator=database.newIterator(reads);
            if(lower!=null)iterator.seek(lower);else if(manifest.legacy())iterator.seek(new byte[]{(byte)this.minimumDistance});else iterator.seekToFirst();
        }
        public byte[] nextBatch(int records) throws IOException {
            byte[] rows=new byte[Math.multiplyExact(records,rowBytes)];int count=0,scanned=0;
            while(count<records&&iterator.isValid()) {
                if((scanned++&4095)==0&&Thread.currentThread().isInterrupted())throw new IOException("Interrupted scanning canonical rows");
                byte[] key=iterator.key(),value=iterator.value();
                if(upper!=null&&Arrays.compareUnsigned(key,upper)>=0)break;
                if(key.length!=payloadBytes+(manifest.legacy()?1:0)||value.length!=1)throw new IOException("Malformed stored row");
                int distance;
                if(manifest.legacy()) {
                    if(value[0]<=0)throw new IOException("Malformed legacy orbit weight");
                    distance=checkedDistance(key[0]);
                }else distance=checkedDistance(value[0]);
                if(distance>=minimumDistance) {
                    int offset=count*rowBytes;rows[offset]=(byte)distance;
                    System.arraycopy(key,manifest.legacy()?1:0,rows,offset+1,payloadBytes);count++;
                }
                iterator.next();
            }
            try{iterator.status();}catch(RocksDBException error){throw new IOException("Layer iteration failed",error);}
            return count==0?null:count==records?rows:Arrays.copyOf(rows,count*rowBytes);
        }
        public void close(){iterator.close();reads.close();if(upperSlice!=null)upperSlice.close();}
    }
    private record Segment(BigInteger lower,BigInteger upper,long bytes){}
    private byte[] fixedKey(BigInteger value,int width) {
        byte[] result=new byte[width],raw=value.toByteArray();int take=Math.min(raw.length,width);
        System.arraycopy(raw,raw.length-take,result,width-take,take);return result;
    }
    private long approximate(BigInteger lower,BigInteger upper,int width) {
        try(Slice start=new Slice(fixedKey(lower,width));Slice limit=new Slice(fixedKey(upper,width))) {
            return database.getApproximateSizes(List.of(new Range(start,limit)),SizeApproximationFlag.INCLUDE_FILES)[0];
        }
    }
    private final class ParallelCursor implements BatchCursor {
        final ArrayBlockingQueue<byte[]> ready;
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicInteger remaining;
        final ExecutorService readers;
        final int batchRecords;
        volatile boolean closed;
        ParallelCursor(int minimum,int batchRecords,int threads) throws IOException {
            this.batchRecords=batchRecords;int width=payloadBytes+(manifest.legacy()?1:0);BigInteger first,last;
            try(RocksIterator bounds=database.newIterator()) {
                bounds.seekToFirst();if(!bounds.isValid())throw new IOException("Complete parent store is empty");first=new BigInteger(1,bounds.key());
                bounds.seekToLast();last=new BigInteger(1,bounds.key()).add(BigInteger.ONE);bounds.status();
            }catch(RocksDBException error){throw new IOException("Cannot read parent range bounds",error);}
            List<Segment> ranges=new ArrayList<>();ranges.add(new Segment(first,last,approximate(first,last,width)));
            while(ranges.size()<threads*8) {
                long nonempty=ranges.stream().filter(r->r.bytes>0).count();if(nonempty>=threads*2L)break;
                Segment largest=ranges.stream().filter(r->r.upper.subtract(r.lower).compareTo(BigInteger.ONE)>0)
                    .max(Comparator.comparingLong(Segment::bytes).thenComparing(r->r.upper.subtract(r.lower))).orElse(null);
                if(largest==null)break;BigInteger middle=largest.lower.add(largest.upper).shiftRight(1);ranges.remove(largest);
                ranges.add(new Segment(largest.lower,middle,approximate(largest.lower,middle,width)));
                ranges.add(new Segment(middle,largest.upper,approximate(middle,largest.upper,width)));
            }
            int count=Math.min(threads,ranges.size());remaining=new AtomicInteger(ranges.size());ready=new ArrayBlockingQueue<>(2*count);
            readers=Executors.newFixedThreadPool(count,Thread.ofPlatform().name("parent-io-n"+manifest.degree+"-",0).factory());
            System.out.printf("PARENT_READ n=%d io_workers=%d disjoint_ranges=%d batch_records=%d%n",manifest.degree,count,ranges.size(),batchRecords);
            for(Segment range:ranges)readers.submit(()->{
                try(Cursor cursor=new Cursor(minimum,fixedKey(range.lower,width),fixedKey(range.upper,width))) {
                    byte[] rows;while(!closed&&(rows=cursor.nextBatch(batchRecords))!=null) {
                        while(!closed&&!ready.offer(rows,100,TimeUnit.MILLISECONDS))if(failure.get()!=null)return;
                    }
                }catch(Throwable error){if(!closed)failure.compareAndSet(null,error);}
                finally{remaining.decrementAndGet();}
            });
        }
        public byte[] nextBatch(int records) throws IOException {
            if(records!=batchRecords)throw new IllegalArgumentException("Parallel parent batch size is fixed");
            try {
                while(!closed) {
                    Throwable error=failure.get();if(error!=null)throw new IOException("Parallel parent read failed",error);
                    byte[] rows=ready.poll(100,TimeUnit.MILLISECONDS);if(rows!=null)return rows;
                    if(remaining.get()==0){
                        error=failure.get();if(error!=null)throw new IOException("Parallel parent read failed",error);
                        // A final producer can enqueue after the timed poll expires
                        // and before it decrements remaining. Observe that row before EOF.
                        return ready.poll();
                    }
                }
                return null;
            }catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("Interrupted reading parent ranges",error);}
        }
        public void close() {
            closed=true;readers.shutdownNow();boolean interrupted=Thread.interrupted();
            while(!readers.isTerminated())try{readers.awaitTermination(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){interrupted=true;}
            ready.clear();if(interrupted)Thread.currentThread().interrupt();
        }
    }
    public void close(){if(parts!=null){for(Part part:parts)part.store.close();return;}database.close();options.close();filter.close();cache.close();lookup.remove();}
}
