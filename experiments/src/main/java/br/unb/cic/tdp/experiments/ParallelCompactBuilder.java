package br.unb.cic.tdp.experiments;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.rocksdb.*;

/** One sorted database pass per disjoint range, followed by parallel compressed-bit assembly. */
final class ParallelCompactBuilder {
    private static final int BUFFER_BYTES=1<<20;
    private record Range(long firstBucket,long endBucket){}
    private record Shard(Range range,long count,long[] canonical,long[] weighted,Path lows,Path highs,Path labels){}
    private record Indexed<T>(int index,T value){}

    static void build(Path directory,FileChannel target,RocksDB database,LayerManifest manifest,
                      CompactRankArchive.Layout layout,int threads,CompactRankArchive.BuildProgress progress) throws IOException {
        Path work=Files.createTempDirectory(directory,".compact-work-");
        ExecutorService workers=Executors.newFixedThreadPool(threads,Thread.ofPlatform().name("compact-build-n"+manifest.degree+"-",0).factory());
        try {
            List<Range> ranges=ranges(database,manifest.degree,layout,threads);
            List<Callable<Shard>> scans=new ArrayList<>();
            for(int i=0;i<ranges.size();i++){int index=i;Range range=ranges.get(i);scans.add(()->encode(work,index,range,database,manifest,layout,progress));}
            List<Shard> shards=execute(workers,scans);
            long count=0,bytes=0;long[] canonical=new long[12],weighted=new long[12];
            for(Shard shard:shards) {
                count=Math.addExact(count,shard.count);
                for(int d=0;d<12;d++){canonical[d]=Math.addExact(canonical[d],shard.canonical[d]);weighted[d]=Math.addExact(weighted[d],shard.weighted[d]);}
                if(shard.count>0)bytes=Math.addExact(bytes,Files.size(shard.lows)+Files.size(shard.highs)+Files.size(shard.labels));
            }
            CompactRankArchive.validateCounts(manifest,count,canonical,weighted);
            progress.phase("merge","bytes",bytes);
            Map<Long,Long> boundaries=new ConcurrentHashMap<>();List<Callable<Void>> merges=new ArrayList<>();long prefix=0;
            for(Shard shard:shards) {
                long first=prefix;prefix+=shard.count;
                if(shard.count==0)continue;
                merges.add(()->{
                    mergeBits(shard.lows,shard.count*layout.lowBits(),target,layout.lowOffset(),first*layout.lowBits(),boundaries,progress);
                    mergeBits(shard.highs,shard.count+shard.range.endBucket-shard.range.firstBucket,target,layout.highOffset(),first+shard.range.firstBucket,boundaries,progress);
                    if(layout.labelWords()>0)mergeBits(shard.labels,shard.count*4,target,layout.labelOffset(),first*4,boundaries,progress);
                    Files.delete(shard.lows);Files.delete(shard.highs);Files.delete(shard.labels);return null;
                });
            }
            execute(workers,merges);
            // Only the first/last partial words are shared. Workers leave them for this bounded join.
            writeBoundaryWords(target,boundaries);
            progress.phase("index","words",layout.highWords());buildIndex(target,layout,progress);
        }finally {
            workers.shutdownNow();boolean interrupted=Thread.interrupted();
            while(!workers.isTerminated())try{workers.awaitTermination(100,TimeUnit.MILLISECONDS);}catch(InterruptedException error){interrupted=true;}
            try(var files=Files.walk(work)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
            finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    static void writeBoundaryWords(FileChannel target,Map<Long,Long> boundaries) throws IOException {
        ByteBuffer word=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        for(var entry:new TreeMap<>(boundaries).entrySet()){word.clear();word.putLong(entry.getValue()).flip();CompactRankArchive.writeFully(target,word,entry.getKey());}
    }

    /** Approximate SST bytes balance ranges without a preliminary record scan. */
    private static List<Range> ranges(RocksDB database,int degree,CompactRankArchive.Layout layout,int threads) {
        long buckets=((layout.universe()-1)>>>layout.lowBits())+1;
        int bins=(int)Math.min(buckets,Math.min(2048L,threads*32L)),wanted=(int)Math.min(bins,threads*4L);
        long[] bounds=new long[bins+1];List<org.rocksdb.Range> probes=new ArrayList<>();List<Slice> slices=new ArrayList<>();
        long[] sizes;
        try {
            for(int i=0;i<=bins;i++)bounds[i]=buckets/bins*i+buckets%bins*i/bins;
            for(int i=0;i<bins;i++) {
                Slice lower=new Slice(key(bounds[i]<<layout.lowBits(),degree));
                Slice upper=new Slice(key(Math.min(layout.universe(),bounds[i+1]<<layout.lowBits()),degree));
                slices.add(lower);slices.add(upper);probes.add(new org.rocksdb.Range(lower,upper));
            }
            sizes=database.getApproximateSizes(probes,SizeApproximationFlag.INCLUDE_FILES);
        }finally{for(Slice slice:slices)slice.close();}
        long total=0;for(long size:sizes)total=Math.addExact(total,size);
        List<Long> cuts=new ArrayList<>();cuts.add(0L);
        if(total==0)for(int i=1;i<wanted;i++)cuts.add(buckets/wanted*i+buckets%wanted*i/wanted);
        else {
            int bin=0;long cumulative=0;
            for(int i=1;i<wanted;i++) {
                long target=total/wanted*i+total%wanted*i/wanted;
                while(bin<bins&&cumulative<target)cumulative+=sizes[bin++];
                long cut=bounds[bin];if(cut>cuts.get(cuts.size()-1)&&cut<buckets)cuts.add(cut);
            }
        }
        cuts.add(buckets);List<Range> result=new ArrayList<>();
        for(int i=1;i<cuts.size();i++)result.add(new Range(cuts.get(i-1),cuts.get(i)));
        return result;
    }
    // End bounds may equal n!, unlike keys of actual stored permutations.
    private static byte[] key(long rank,int degree) {
        byte[] key=new byte[PermutationRank.bytes(degree)];for(int i=key.length-1;i>=0;i--){key[i]=(byte)rank;rank>>>=8;}return key;
    }
    private static Shard encode(Path work,int index,Range range,RocksDB database,LayerManifest manifest,
                                CompactRankArchive.Layout layout,CompactRankArchive.BuildProgress progress) throws IOException,RocksDBException {
        byte[] lower=key(range.firstBucket<<layout.lowBits(),manifest.degree);
        byte[] upper=key(Math.min(layout.universe(),range.endBucket<<layout.lowBits()),manifest.degree);
        long[] canonical=new long[12],weighted=new long[12];long count=0,previous=-1;
        Path lows=work.resolve(index+"-low.bin"),highs=work.resolve(index+"-high.bin"),labels=work.resolve(index+"-labels.bin");
        try(Slice bound=new Slice(upper);
            ReadOptions reads=new ReadOptions().setIterateUpperBound(bound).setFillCache(false).setReadaheadSize(4L<<20);
            RocksIterator iterator=database.newIterator(reads)) {
            iterator.seek(lower);
            if(!iterator.isValid()){iterator.status();return new Shard(range,0,canonical,weighted,lows,highs,labels);}
            byte[] key=new byte[lower.length],value=new byte[2];
            try(FileChannel lowFile=create(lows);FileChannel highFile=create(highs);FileChannel labelFile=create(labels)) {
                ShardEncoder encoder=new ShardEncoder(lowFile,highFile,labelFile,layout,range);
                for(;iterator.isValid();iterator.next()) {
                    if((count&65535)==0)CompactRankArchive.checkInterrupted();
                    if(iterator.key(key)!=key.length||iterator.value(value)!=2)throw new IOException("Malformed rank construction record");
                    long rank=PermutationRank.decode(key,manifest.degree);int distance=value[0]&255,weight=value[1]&255;
                    if((rank>>>layout.lowBits())<range.firstBucket||(rank>>>layout.lowBits())>=range.endBucket)
                        throw new IOException("Compact range scan escaped its bounds");
                    CompactRankArchive.validateRecord(manifest,rank,previous,distance,weight);
                    encoder.append(rank,distance);previous=rank;count++;canonical[distance]++;weighted[distance]+=weight;
                    if((count&65535)==0)progress.add(65536);
                }
                iterator.status();encoder.finish();progress.add(count&65535);
            }
        }
        return new Shard(range,count,canonical,weighted,lows,highs,labels);
    }
    private static FileChannel create(Path path) throws IOException {return FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
    private static final class ShardEncoder {
        final CompactRankArchive.Layout layout;final Range range;
        final CompactRankArchive.Fields lows,labels;final CompactRankArchive.WordOutput highs;
        long count,position,word;
        ShardEncoder(FileChannel lowFile,FileChannel highFile,FileChannel labelFile,CompactRankArchive.Layout layout,Range range) {
            this.layout=layout;this.range=range;lows=new CompactRankArchive.Fields(lowFile,0);labels=new CompactRankArchive.Fields(labelFile,0);highs=new CompactRankArchive.WordOutput(highFile,0);
        }
        void append(long rank,int distance) throws IOException {
            lows.append(rank&((1L<<layout.lowBits())-1),layout.lowBits());if(layout.labelWords()>0)labels.append(distance,4);
            long bit=(rank>>>layout.lowBits())-range.firstBucket+count;
            while(position<(bit>>>6)){highs.put(word);word=0;position++;}word|=1L<<(bit&63);count++;
        }
        void finish() throws IOException {
            long words=(count+range.endBucket-range.firstBucket+63)/64;
            while(position<words){highs.put(word);word=0;position++;}lows.close();labels.close();highs.close();
        }
    }
    private static final class WordInput {
        final FileChannel file;final ByteBuffer buffer=ByteBuffer.allocate(BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final CompactRankArchive.BuildProgress progress;long offset,remaining;
        WordInput(FileChannel file,long offset,long words,CompactRankArchive.BuildProgress progress){this.file=file;this.offset=offset;remaining=words;this.progress=progress;buffer.limit(0);}
        long next() throws IOException {
            if(remaining==0)throw new EOFException("Truncated compressed shard");
            if(!buffer.hasRemaining()) {
                CompactRankArchive.checkInterrupted();buffer.clear();int bytes=(int)Math.min(buffer.capacity(),remaining*8);buffer.limit(bytes);
                CompactRankArchive.readFully(file,buffer,offset);offset+=bytes;buffer.flip();if(progress!=null)progress.add(bytes);
            }
            remaining--;return buffer.getLong();
        }
    }
    static void mergeBits(Path source,long bits,FileChannel target,long offset,long firstBit,
                          Map<Long,Long> boundaries,CompactRankArchive.BuildProgress progress) throws IOException {
        if(bits==0)return;
        int shift=(int)(firstBit&63);long sourceWords=(bits+63)/64,outputWords=(shift+bits+63)/64,end=firstBit+bits;
        long start=offset+(firstBit>>>6)*8,carry=0;
        try(FileChannel file=FileChannel.open(source,StandardOpenOption.READ);
            CompactRankArchive.WordOutput output=new CompactRankArchive.WordOutput(target,start+(shift==0?0:8))) {
            if(file.size()!=sourceWords*8)throw new IOException("Malformed compressed shard length");
            WordInput input=new WordInput(file,0,sourceWords,progress);
            for(long i=0;i<outputWords;i++) {
                long word=i<sourceWords?input.next():0;
                if(i==sourceWords-1&&(bits&63)!=0)word&=(1L<<(bits&63))-1;
                long value=carry|(word<<shift);carry=shift==0?0:word>>>(64-shift);
                if(i==0&&shift!=0||i==outputWords-1&&(end&63)!=0)boundaries.merge(start+i*8,value,(a,b)->a|b);
                else output.put(value);
            }
        }
    }
    private static void buildIndex(FileChannel file,CompactRankArchive.Layout layout,CompactRankArchive.BuildProgress progress) throws IOException {
        long buckets=((layout.universe()-1)>>>layout.lowBits())+1,highBits=layout.count()+buckets,zeros=0,nextSample=0;
        WordInput input=new WordInput(file,layout.highOffset(),layout.highWords(),null);
        try(CompactRankArchive.WordOutput directory=new CompactRankArchive.WordOutput(file,layout.directoryOffset());
            CompactRankArchive.WordOutput samples=new CompactRankArchive.WordOutput(file,layout.sampleOffset())) {
            directory.put(0);
            for(long index=0;index<layout.highWords();index++) {
                int valid=(int)Math.min(64,highBits-index*64);long bits=~input.next()&(valid==64?-1L:(1L<<valid)-1),end=zeros+Long.bitCount(bits);
                while(nextSample<end){long selected=bits,k=nextSample-zeros;while(k-->0)selected&=selected-1;samples.put(index*64+Long.numberOfTrailingZeros(selected));nextSample+=CompactRankArchive.ZERO_SAMPLE_STRIDE;}
                zeros=end;if((index+1)%CompactRankArchive.BLOCK_WORDS==0||index+1==layout.highWords())directory.put(zeros);
                if((index&4095)==4095)progress.add(4096);
            }
            progress.add(layout.highWords()&4095);
            if(zeros!=buckets)throw new IOException("Assembled compact unary cardinality mismatch");
        }
    }
    private static <T> List<T> execute(ExecutorService workers,List<Callable<T>> tasks) throws IOException {
        CompletionService<Indexed<T>> completed=new ExecutorCompletionService<>(workers);List<Future<Indexed<T>>> futures=new ArrayList<>();
        List<T> results=new ArrayList<>(Collections.nCopies(tasks.size(),null));
        try {
            for(int i=0;i<tasks.size();i++){int index=i;Callable<T> task=tasks.get(i);futures.add(completed.submit(()->new Indexed<>(index,task.call())));}
            for(int i=0;i<tasks.size();i++){Indexed<T> result=completed.take().get();results.set(result.index,result.value);}
            return results;
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("Interrupted parallel compact finalization",error);}
        catch(ExecutionException error){throw new IOException("Parallel compact finalization failed",error.getCause());}
        finally{for(Future<?> future:futures)if(!future.isDone())future.cancel(true);}
    }
}
