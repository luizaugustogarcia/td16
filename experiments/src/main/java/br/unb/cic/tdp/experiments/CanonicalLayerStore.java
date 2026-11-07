package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exact mapped membership and bounded streaming over completed canonical rank archives. */
final class CanonicalLayerStore implements AutoCloseable {
    final Path directory;
    final LayerManifest manifest;
    final int payloadBytes,rowBytes;
    final CompactRankArchive compact;
    record Part(CanonicalLayerStore store,int mask){}
    private final List<Part> parts;
    private final ThreadLocal<byte[]> lookupOmega=ThreadLocal.withInitial(()->new byte[19]);
    CanonicalLayerStore(Path directory) throws IOException {
        this.directory=directory;manifest=LayerManifest.read(directory);parts=null;
        payloadBytes=PackedPermutation5.bytes(manifest.degree+1);rowBytes=payloadBytes+1;
        compact=CompactRankArchive.open(directory,manifest);
    }
    /** Owns a disjoint union of complete selected distance providers. */
    CanonicalLayerStore(Path root,int degree,int floor,Map<Path,Integer> selected) throws IOException {
        directory=root;compact=null;payloadBytes=PackedPermutation5.bytes(degree+1);rowBytes=payloadBytes+1;
        parts=new ArrayList<>();long[] canonical=new long[12],weighted=new long[12];int mask=0,horizon=degree;
        try {
            for(var entry:selected.entrySet()) {
                CanonicalLayerStore store=new CanonicalLayerStore(entry.getKey());parts.add(new Part(store,entry.getValue()));
                if(store.manifest.degree!=degree||(mask&entry.getValue())!=0)throw new IOException("Overlapping or incompatible exact layer providers");
                mask|=entry.getValue();horizon=Math.max(horizon,store.manifest.horizon);
                for(int d=0;d<12;d++)if((entry.getValue()&(1<<d))!=0){canonical[d]=store.manifest.canonical[d];weighted[d]=store.manifest.weighted[d];}
            }
            boolean empty=Arrays.stream(weighted).sum()==0;
            manifest=new LayerManifest(degree,floor,horizon,canonical,weighted,empty||Integer.bitCount(mask)==1);
        }catch(IOException|RuntimeException error){for(Part part:parts)part.store.close();throw error;}
    }
    static Path path(Path root,int degree,int floor){return root.resolve(String.format(Locale.ROOT,"n-%02d/floor-%02d",degree,floor));}
    static Path find(Path root,int degree,int requiredFloor) throws IOException {
        Path parent=root.resolve(String.format(Locale.ROOT,"n-%02d",degree));if(!Files.isDirectory(parent))return null;
        Path best=null;int bestFloor=-1;
        try(var directories=Files.list(parent)) {
            for(Path directory:directories.toList()) {
                if(!directory.getFileName().toString().startsWith("floor-")||!Files.isRegularFile(directory.resolve("manifest.properties")))continue;
                LayerManifest manifest=LayerManifest.read(directory);if(manifest.degree!=degree)throw new IOException("Layer directory has wrong degree: "+directory);
                if(manifest.floor<=requiredFloor&&manifest.floor>bestFloor){best=directory;bestFloor=manifest.floor;}
            }
        }
        return best;
    }
    int distance(byte[] canonicalOmega,int requiredFloor) throws IOException {
        if(requiredFloor<manifest.floor)throw new IllegalArgumentException("Requested distance was not retained");
        return distanceRank(PermutationRank.ofOmega(canonicalOmega,manifest.degree),requiredFloor);
    }
    int distanceRank(long rank,int requiredFloor) {
        if(parts!=null){for(Part part:parts){int d=part.store.distanceRank(rank,Math.max(requiredFloor,part.store.manifest.floor));if(d>=0&&(part.mask&(1<<d))!=0)return d;}return -1;}
        int distance=compact.distance(rank);return distance>=requiredFloor?distance:-1;
    }
    int[] distances(byte[][] keys,int requiredFloor) throws IOException {
        if(requiredFloor<manifest.floor)throw new IllegalArgumentException("Requested distance was not retained");
        int[] distances=new int[keys.length];byte[] omega=lookupOmega.get();
        for(int i=0;i<keys.length;i++){PackedPermutation5.decode(keys[i],0,manifest.degree+1,omega);distances[i]=distanceRank(PermutationRank.ofOmega(omega,manifest.degree),requiredFloor);}
        return distances;
    }
    interface BatchCursor extends AutoCloseable {
        byte[] nextBatch(int records) throws IOException;
        @Override void close() throws IOException;
    }
    BatchCursor cursor(int minimumDistance) throws IOException {return source(minimumDistance,0,1,false);}
    BatchCursor sourceRanks(int minimumDistance,int batchRecords,int threads) throws IOException {return source(minimumDistance,batchRecords,threads,true);}
    BatchCursor source(int minimumDistance,int batchRecords,int threads) throws IOException {return source(minimumDistance,batchRecords,threads,false);}
    private BatchCursor source(int minimumDistance,int batchRecords,int threads,boolean rankRows) throws IOException {
        if(parts!=null)return new BatchCursor() {
            int index;BatchCursor active;
            public byte[] nextBatch(int records) throws IOException {
                while(index<parts.size()) {
                    Part part=parts.get(index);if(active==null)active=part.store.source(minimumDistance,records,threads,rankRows);
                    byte[] input=active.nextBatch(records);if(input==null){active.close();active=null;index++;continue;}
                    int out=0,width=rankRows?9:rowBytes;
                    for(int offset=0;offset<input.length;offset+=width)if((part.mask&(1<<(input[offset]&255)))!=0){if(offset!=out)System.arraycopy(input,offset,input,out,width);out+=width;}
                    if(out>0)return out==input.length?input:Arrays.copyOf(input,out);
                }
                return null;
            }
            public void close() throws IOException {if(active!=null)active.close();index=parts.size();}
        };
        if(compact.count==0||threads<=1||compact.count<Long.getLong("extension.parallelReadThreshold",1000000L))return compactCursor(minimumDistance,0,compact.count,rankRows);
        return new ParallelCursor(minimumDistance,batchRecords,threads,rankRows);
    }
    private BatchCursor compactCursor(int minimumDistance,long first,long end,boolean rankRows) {
        return new BatchCursor() {
            final CompactRankArchive.RankCursor ranks=compact.ranks(first);
            final byte[] word=new byte[manifest.degree],omega=new byte[manifest.degree+1];long index=first;boolean closed;
            public byte[] nextBatch(int records) throws IOException {
                int width=rankRows?9:rowBytes;byte[] rows=new byte[Math.multiplyExact(records,width)];int count=0;
                while(!closed&&index<end&&count<records) {
                    if((index&4095)==0&&Thread.currentThread().isInterrupted())throw new IOException("Interrupted compact parent scan");
                    long rank=ranks.next();int distance=compact.distanceAt(index++);if(distance<minimumDistance)continue;
                    int offset=count++*width;rows[offset]=(byte)distance;
                    if(rankRows){for(int b=0;b<8;b++)rows[offset+1+b]=(byte)(rank>>>(8*b));}
                    else{SmallTables.unrank(rank,manifest.degree,word,0);ToricOrbit.omegaFromWord(word,manifest.degree,omega);PackedPermutation5.encode(omega,manifest.degree+1,rows,offset+1);}
                }
                return count==0?null:count==records?rows:Arrays.copyOf(rows,count*width);
            }
            public void close(){closed=true;}
        };
    }
    private final class ParallelCursor implements BatchCursor {
        final ArrayBlockingQueue<byte[]> ready;final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicInteger remaining;final ExecutorService readers;final int batchRecords;volatile boolean closed;
        ParallelCursor(int minimum,int batchRecords,int threads,boolean rankRows) {
            this.batchRecords=batchRecords;int ranges=(int)Math.min(compact.count,threads*2L),workers=Math.min(threads,ranges);
            ready=new ArrayBlockingQueue<>(2*workers);remaining=new AtomicInteger(ranges);readers=Executors.newFixedThreadPool(workers,Thread.ofPlatform().name("compact-parent-n"+manifest.degree+"-",0).factory());
            for(int range=0;range<ranges;range++){
                long first=compact.count/ranges*range+compact.count%ranges*range/ranges,end=compact.count/ranges*(range+1)+compact.count%ranges*(range+1)/ranges;
                readers.submit(()->{try(BatchCursor cursor=compactCursor(minimum,first,end,rankRows)){byte[] rows;while(!closed&&(rows=cursor.nextBatch(batchRecords))!=null)while(!closed&&!ready.offer(rows,100,TimeUnit.MILLISECONDS))if(failure.get()!=null)return;}
                    catch(Throwable error){if(!closed)failure.compareAndSet(null,error);}finally{remaining.decrementAndGet();}});
            }
        }
        public byte[] nextBatch(int records) throws IOException {
            if(records!=batchRecords)throw new IllegalArgumentException("Parallel compact batch size is fixed");
            try{while(!closed){Throwable error=failure.get();if(error!=null)throw new IOException("Parallel compact read failed",error);byte[] rows=ready.poll(100,TimeUnit.MILLISECONDS);if(rows!=null)return rows;
                if(remaining.get()==0){error=failure.get();if(error!=null)throw new IOException("Parallel compact read failed",error);return ready.poll();}}return null;}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("Interrupted compact parent read",error);}
        }
        public void close(){closed=true;readers.shutdownNow();boolean interrupted=Thread.interrupted();while(!readers.isTerminated())try{readers.awaitTermination(100,TimeUnit.MILLISECONDS);}catch(InterruptedException error){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
    }
    public void close(){if(parts!=null)for(Part part:parts)part.store.close();else compact.close();lookupOmega.remove();}
}
