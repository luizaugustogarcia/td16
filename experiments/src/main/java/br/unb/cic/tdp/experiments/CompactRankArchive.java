package br.unb.cic.tdp.experiments;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Versioned, exact Elias-Fano archive shared by mapped CPU reads and CUDA. */
final class CompactRankArchive implements AutoCloseable {
    static final String FILE="canonical-ranks-v1.bin";
    static final long MAGIC=0x31524b4341504454L,VERSION=1;
    static final int HEADER_WORDS=50,HEADER_BYTES=HEADER_WORDS*8,BLOCK_WORDS=8,ZERO_SAMPLE_STRIDE=128;
    static final int ORDINARY=1;
    private static final int BUFFER_BYTES=1<<20;
    private record Stamp(Path path,Object key,long size,java.nio.file.attribute.FileTime modified){}
    private static final Set<Stamp> VERIFIED=ConcurrentHashMap.newKeySet();
    final Path path;
    final long[] header;
    final long count,universe,bytes;
    final int degree,floor,ceiling,lowBits;
    final boolean exact;
    private final MappedFile mapped;

    record Layout(long count,long universe,int lowBits,long lowWords,long highWords,long directoryWords,long labelWords,
                  long lowOffset,long highOffset,long directoryOffset,long labelOffset,long sampleOffset,long sampleWords,long bytes) {
        static Layout of(long universe,long count,boolean exact) {
            if(universe<1||count<0||count>universe)throw new IllegalArgumentException("Invalid compact rank universe/cardinality");
            int low=count==0?0:63-Long.numberOfLeadingZeros(universe/count);
            long lowWords=words(Math.multiplyExact(count,low));
            long highWords=count==0?0:words(Math.addExact(count,((universe-1)>>>low)+1));
            long directories=count==0?0:(highWords+BLOCK_WORDS-1)/BLOCK_WORDS+1;
            long labels=exact?0:words(Math.multiplyExact(count,4));
            long high=HEADER_BYTES+lowWords*8,directory=high+highWords*8,label=directory+directories*8;
            long sample=label+labels*8,buckets=count==0?0:((universe-1)>>>low)+1,samples=(buckets+ZERO_SAMPLE_STRIDE-1)/ZERO_SAMPLE_STRIDE;
            return new Layout(count,universe,low,lowWords,highWords,directories,labels,HEADER_BYTES,high,directory,label,sample,samples,Math.addExact(sample,samples*8));
        }
        private static long words(long bits){return bits/64+(bits%64==0?0:1);}
    }
    static boolean enabled(){return Boolean.parseBoolean(System.getProperty("extension.compactSets","true"));}
    static long bytesFor(LayerManifest manifest){return Layout.of(SmallTables.FACTORIAL[manifest.degree],manifest.canonicalSize(),manifest.exact).bytes;}
    static CompactRankArchive open(Path directory,LayerManifest manifest) throws IOException {
        return new CompactRankArchive(directory.resolve(FILE),manifest);
    }
    private CompactRankArchive(Path path,LayerManifest manifest) throws IOException {
        this.path=path;
        long[] fields=new long[HEADER_WORDS];
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.READ)) {
            ByteBuffer buffer=ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);readFully(file,buffer,0);buffer.flip();buffer.asLongBuffer().get(fields);
            Layout layout=Layout.of(SmallTables.FACTORIAL[manifest.degree],manifest.canonicalSize(),manifest.exact);
            long[] expected=header(manifest,layout,0);
            for(int i=0;i<fields.length;i++)if((i<18||i==19||i>=24)&&fields[i]!=expected[i])throw new IOException("Incompatible compact archive header: "+path+" field="+i);
            if(Files.isDirectory(path.getParent().resolve("db"))&&fields[18]!=sourceSignature(path.getParent()))throw new IOException("Compact archive source changed: "+path);
            if(file.size()!=layout.bytes)throw new IOException("Truncated or oversized compact archive: "+path);
            verifyDigest(path,file,fields);
        }catch(IllegalArgumentException|ArithmeticException error){throw new IOException("Malformed compact archive: "+path,error);}
        header=fields;count=fields[6];universe=fields[7];lowBits=(int)fields[8];bytes=fields[17];
        degree=(int)fields[2];floor=(int)fields[3];ceiling=(int)fields[4];exact=fields[5]==1;
        mapped=new MappedFile(path,bytes);
    }
    private static long[] header(LayerManifest manifest,Layout layout,int flags) {
        long[] h=new long[HEADER_WORDS];
        h[0]=MAGIC;h[1]=VERSION;h[2]=manifest.degree;h[3]=manifest.floor;h[4]=manifest.maxDistance;h[5]=manifest.exact?1:0;
        h[6]=layout.count;h[7]=layout.universe;h[8]=layout.lowBits;h[9]=layout.lowWords;h[10]=layout.highWords;h[11]=layout.directoryWords;h[12]=layout.labelWords;
        h[13]=layout.lowOffset/8;h[14]=layout.highOffset/8;h[15]=layout.directoryOffset/8;h[16]=layout.labelOffset/8;h[17]=layout.bytes;h[19]=flags;
        System.arraycopy(manifest.canonical,0,h,24,12);System.arraycopy(manifest.weighted,0,h,36,12);h[48]=layout.sampleOffset/8;h[49]=layout.sampleWords;return h;
    }
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException error){throw new AssertionError(error);}}
    private static long sourceSignature(Path directory) throws IOException {
        Path db=directory.resolve("db");if(!Files.isDirectory(db))return 0;long hash=0xcbf29ce484222325L;
        try(var paths=Files.list(db)) {
            for(Path file:paths.filter(p->p.getFileName().toString().endsWith(".sst")).sorted().toList()) {
                BasicFileAttributes a=Files.readAttributes(file,BasicFileAttributes.class);
                for(char c:file.getFileName().toString().toCharArray())hash=(hash^c)*0x100000001b3L;
                hash=(hash^a.size())*0x100000001b3L;hash=(hash^a.lastModifiedTime().toMillis())*0x100000001b3L;
            }
        }
        return hash;
    }
    private static byte[] digest(FileChannel file) throws IOException {return digest(file,null);}
    private static byte[] digest(FileChannel file,BuildProgress progress) throws IOException {
        MessageDigest digest=sha256();ByteBuffer buffer=ByteBuffer.allocateDirect(BUFFER_BYTES);long offset=HEADER_BYTES,size=file.size();
        while(offset<size) {buffer.clear();buffer.limit((int)Math.min(buffer.capacity(),size-offset));readFully(file,buffer,offset);int bytes=buffer.position();offset+=bytes;buffer.flip();digest.update(buffer);if(progress!=null)progress.add(bytes);}
        return digest.digest();
    }
    private static void verifyDigest(Path path,FileChannel file,long[] fields) throws IOException {
        BasicFileAttributes attributes=Files.readAttributes(path,BasicFileAttributes.class);
        Stamp stamp=new Stamp(path.toAbsolutePath(),attributes.fileKey(),attributes.size(),attributes.lastModifiedTime());
        if(VERIFIED.contains(stamp))return;
        ByteBuffer expected=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);for(int i=20;i<24;i++)expected.putLong(fields[i]);
        if(!MessageDigest.isEqual(expected.array(),digest(file)))throw new IOException("Compact archive checksum mismatch: "+path);
        VERIFIED.add(stamp);
    }
    private static void writeHeader(FileChannel file,long[] header) throws IOException {
        writeHeader(file,header,null);
    }
    private static void writeHeader(FileChannel file,long[] header,BuildProgress progress) throws IOException {
        if(progress!=null)progress.phase("checksum","bytes",file.size()-HEADER_BYTES);
        byte[] digest=digest(file,progress);ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(header,20,4);
        ByteBuffer buffer=ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);buffer.asLongBuffer().put(header);writeFully(file,buffer,0);file.force(true);
    }
    static void build(Path directory,LayerManifest manifest) throws IOException {
        Layout layout=Layout.of(SmallTables.FACTORIAL[manifest.degree],manifest.canonicalSize(),manifest.exact);
        int threads=buildThreads(layout.count);long began=System.nanoTime();Path temporary=Files.createTempFile(directory,".compact-",".partial");
        try {
            try(FileChannel file=FileChannel.open(temporary,StandardOpenOption.READ,StandardOpenOption.WRITE);
                org.rocksdb.Options options=new org.rocksdb.Options();
                org.rocksdb.RocksDB database=org.rocksdb.RocksDB.openReadOnly(options,directory.resolve("db").toString());
                BuildProgress progress=new BuildProgress(manifest,threads)) {
                resize(file,layout.bytes);progress.phase("encode","records",layout.count);
                if(threads>1)ParallelCompactBuilder.build(directory,file,database,manifest,layout,threads,progress);
                else buildSequential(file,database,manifest,layout,progress);
                long[] h=header(manifest,layout,0);h[18]=sourceSignature(directory);writeHeader(file,h,progress);
            }catch(org.rocksdb.RocksDBException|IllegalArgumentException error){throw new IOException("Compact rank finalization failed",error);}
            Files.move(temporary,directory.resolve(FILE),StandardCopyOption.ATOMIC_MOVE);
            System.out.printf(Locale.ROOT,"COMPACT_ARCHIVE n=%d floor=%d records=%d bytes=%d workers=%d sorting=rank-ordered-construction checksum=SHA-256 complete=true seconds=%.3f%n",
                manifest.degree,manifest.floor,layout.count,layout.bytes,threads,(System.nanoTime()-began)/1e9);
        }finally{Files.deleteIfExists(temporary);}
    }
    static int buildThreads(long count) {
        int threads=Integer.getInteger("extension.compactBuildThreads",Runtime.getRuntime().availableProcessors());
        long threshold=Long.getLong("extension.compactParallelThreshold",1_000_000L);
        if(threads<1||threshold<0)throw new IllegalArgumentException("Compact build threads must be positive and threshold nonnegative");
        return count<threshold||count<2?1:(int)Math.min(count,threads);
    }
    private static void buildSequential(FileChannel file,org.rocksdb.RocksDB database,LayerManifest manifest,Layout layout,BuildProgress progress) throws IOException,org.rocksdb.RocksDBException {
        long[] counts=new long[12],weights=new long[12];long loaded=0,previous=-1;
        byte[] key=new byte[PermutationRank.bytes(manifest.degree)],value=new byte[2];
        try(org.rocksdb.ReadOptions reads=new org.rocksdb.ReadOptions().setFillCache(false).setReadaheadSize(4L<<20);
            org.rocksdb.RocksIterator iterator=database.newIterator(reads)) {
            Encoder encoder=new Encoder(file,layout);
            for(iterator.seekToFirst();iterator.isValid();iterator.next()) {
                if((loaded&65535)==0)checkInterrupted();
                if(iterator.key(key)!=key.length||iterator.value(value)!=2)throw new IOException("Malformed rank construction record");
                long rank=PermutationRank.decode(key,manifest.degree);int distance=value[0]&255,weight=value[1]&255;
                validateRecord(manifest,rank,previous,distance,weight);
                encoder.append(rank,distance);previous=rank;loaded++;counts[distance]++;weights[distance]+=weight;
                if((loaded&65535)==0)progress.add(65536);
            }
            progress.add(loaded&65535);iterator.status();encoder.finish();
        }
        validateCounts(manifest,loaded,counts,weights);
    }
    static void checkInterrupted() throws IOException {if(Thread.currentThread().isInterrupted())throw new IOException("Interrupted compact finalization");}
    static void validateRecord(LayerManifest manifest,long rank,long previous,int distance,int weight) throws IOException {
        if(rank<=previous||distance<manifest.floor||distance>manifest.maxDistance||weight<1||2*(manifest.degree+1)%weight!=0)
            throw new IOException("Invalid rank/distance/orbit record during finalization");
    }
    static void validateCounts(LayerManifest manifest,long count,long[] counts,long[] weights) throws IOException {
        if(count!=manifest.canonicalSize()||!Arrays.equals(counts,manifest.canonical)||!Arrays.equals(weights,manifest.weighted))
            throw new IOException("Compact persisted canonical/orbit counts disagree with manifest");
    }
    static final class BuildProgress implements AutoCloseable {
        private record Phase(String name,String unit,long total,AtomicLong completed){}
        final LayerManifest manifest;final int threads;final long began=System.nanoTime();
        final ScheduledExecutorService reporter;
        private volatile Phase phase=new Phase("start","records",0,new AtomicLong());
        BuildProgress(LayerManifest manifest,int threads) {
            this.manifest=manifest;this.threads=threads;
            reporter=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("compact-progress-n"+manifest.degree).factory());
            reporter.scheduleAtFixedRate(this::report,2,2,TimeUnit.SECONDS);
        }
        void phase(String name,String unit,long total){phase=new Phase(name,unit,total,new AtomicLong());report();}
        void add(long amount){phase.completed.addAndGet(amount);}
        private void report() {
            Phase p=phase;long done=p.completed.get();
            System.out.printf(Locale.ROOT,"COMPACT_PROGRESS n=%d floor=%d phase=%s units=%s completed=%d total=%d percent=%.1f workers=%d elapsed=%.1fs%n",
                manifest.degree,manifest.floor,p.name,p.unit,done,p.total,p.total==0?100:100.0*done/p.total,threads,(System.nanoTime()-began)/1e9);
        }
        public void close(){reporter.shutdownNow();}
    }
    static final class WordOutput implements AutoCloseable {
        final FileChannel file;final ByteBuffer buffer=ByteBuffer.allocate(BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN);long offset;
        WordOutput(FileChannel file,long offset){this.file=file;this.offset=offset;}
        void put(long value) throws IOException {if(buffer.remaining()<8)flush();buffer.putLong(value);}
        void flush() throws IOException {buffer.flip();int bytes=buffer.remaining();writeFully(file,buffer,offset);offset+=bytes;buffer.clear();}
        public void close() throws IOException {flush();}
    }
    static final class Fields implements AutoCloseable {
        final WordOutput output;long word;int used;
        Fields(FileChannel file,long offset){output=new WordOutput(file,offset);}
        void append(long value,int bits) throws IOException {if(bits==0)return;word|=value<<used;int total=used+bits;if(total>=64){output.put(word);word=total==64?0:value>>>(64-used);used=total-64;}else used=total;}
        public void close() throws IOException {if(used>0)output.put(word);output.close();}
    }
    private static final class Encoder {
        final Layout layout;final Fields lows,labels;final WordOutput highs,directory,samples;long index,currentHighWord,highWord,zeroCount,nextSample;
        Encoder(FileChannel file,Layout layout) throws IOException {this.layout=layout;lows=new Fields(file,layout.lowOffset);labels=new Fields(file,layout.labelOffset);highs=new WordOutput(file,layout.highOffset);directory=new WordOutput(file,layout.directoryOffset);samples=new WordOutput(file,layout.sampleOffset);if(layout.count>0)directory.put(0);}
        void append(long rank,int distance) throws IOException {
            if(index>=layout.count||rank<0||rank>=layout.universe)throw new IOException("Invalid compact rank sequence");
            lows.append(rank&((1L<<layout.lowBits)-1),layout.lowBits);if(layout.labelWords>0)labels.append(distance,4);
            long bit=(rank>>>layout.lowBits)+index;while(currentHighWord<(bit>>>6))emitHigh();highWord|=1L<<(bit&63);index++;
        }
        private void emitHigh() throws IOException {
            long highBits=layout.count+((layout.universe-1)>>>layout.lowBits)+1;int valid=(int)Math.min(64,highBits-currentHighWord*64);
            long zeros=~highWord&(valid==64?-1L:(1L<<valid)-1),nextCount=zeroCount+Long.bitCount(zeros);
            while(nextSample<nextCount){long bits=zeros,k=nextSample-zeroCount;while(k-->0)bits&=bits-1;samples.put(currentHighWord*64+Long.numberOfTrailingZeros(bits));nextSample+=ZERO_SAMPLE_STRIDE;}
            highs.put(highWord);zeroCount=nextCount;highWord=0;currentHighWord++;
            if(currentHighWord%BLOCK_WORDS==0||currentHighWord==layout.highWords)directory.put(zeroCount);
        }
        void finish() throws IOException {if(index!=layout.count)throw new IOException("Incomplete compact rank sequence");while(currentHighWord<layout.highWords)emitHigh();lows.close();labels.close();highs.close();directory.close();samples.close();}
    }
    /** Build a small ordinary-orbit index for direct CUDA probes without canonicalization. */
    byte[] ordinaryImage(int maximumMembers) throws IOException {
        long members=0;for(int d=floor;d<=ceiling;d++)members+=header[36+d];if(members>maximumMembers)return null;
        long[] coded=new long[Math.toIntExact(members)];byte[] word=new byte[degree],transformed=new byte[degree];long[] orbit=new long[2*(degree+1)];int used=0;
        for(long i=0;i<count;i++){SmallTables.unrank(rankAt(i),degree,word,0);int size=ToricOrbit.orbitRanks(word,degree,orbit,transformed);for(int j=0;j<size;j++)coded[used++]=(orbit[j]<<4)|distanceAt(i);}
        if(used!=coded.length)throw new IOException("Ordinary orbit expansion count mismatch");Arrays.sort(coded);
        long[] canonical=new long[12],weighted=new long[12];for(long value:coded){canonical[(int)(value&15)]++;weighted[(int)(value&15)]++;}
        LayerManifest ordinary=new LayerManifest(degree,floor,degree,canonical,weighted,exact);Layout layout=Layout.of(universe,members,exact);
        Path temp=Files.createTempFile("tdp-ordinary-ranks-",".bin");
        try {
            try(FileChannel file=FileChannel.open(temp,StandardOpenOption.READ,StandardOpenOption.WRITE)) {resize(file,layout.bytes);Encoder encoder=new Encoder(file,layout);long previous=-1;for(long value:coded){long rank=value>>>4;if(rank<=previous)throw new IOException("Overlapping ordinary orbits");encoder.append(rank,(int)(value&15));previous=rank;}encoder.finish();writeHeader(file,header(ordinary,layout,ORDINARY));}
            return Files.readAllBytes(temp);
        }finally{Files.deleteIfExists(temp);}
    }
    private long word(long offset){return mapped.getLong(offset*8);}
    private long lowAt(long index) {if(lowBits==0)return 0;long bit=index*lowBits;int shift=(int)(bit&63);long value=word(header[13]+(bit>>>6))>>>shift;if(shift+lowBits>64)value|=word(header[13]+(bit>>>6)+1)<<(64-shift);return value&((1L<<lowBits)-1);}
    private long selectZero(long zero) {
        long sampled=word(header[48]+zero/ZERO_SAMPLE_STRIDE),remaining=zero%ZERO_SAMPLE_STRIDE;if(remaining==0)return sampled;
        long position=sampled>>>6;int shift=(int)(sampled&63);long bits=shift==63?0:~word(header[14]+position)&(-1L<<(shift+1));
        for(int visited=0;visited<16;visited++){int zeros=Long.bitCount(bits);if(remaining<=zeros){while(--remaining>0)bits&=bits-1;return position*64+Long.numberOfTrailingZeros(bits);}remaining-=zeros;bits=~word(header[14]+ ++position);}
        return selectZeroIndexed(zero);
    }
    private long selectZeroIndexed(long zero) {
        long first=0,limit=header[11]-1;while(first<limit){long middle=first+((limit-first)>>>1);if(word(header[15]+middle+1)<=zero)first=middle+1;else limit=middle;}
        long remaining=zero-word(header[15]+first),position=first*BLOCK_WORDS;
        while(true){long bits=~word(header[14]+position);int zeros=Long.bitCount(bits);if(remaining<zeros){while(remaining-->0)bits&=bits-1;return position*64+Long.numberOfTrailingZeros(bits);}remaining-=zeros;position++;}
    }
    private long bucketStart(long bucket){return bucket==0?0:selectZero(bucket-1)-bucket+1;}
    long find(long rank) {
        if(rank<0||rank>=universe||count==0)return -1;long bucket=rank>>>lowBits,start=bucketStart(bucket),end=selectZero(bucket)-bucket,bucketEnd=end,low=rank&((1L<<lowBits)-1);
        while(start<end){long middle=start+((end-start)>>>1);if(lowAt(middle)<low)start=middle+1;else end=middle;}
        return start<bucketEnd&&lowAt(start)==low?start:-1;
    }
    int distance(long rank){long index=find(rank);return index<0?-1:distanceAt(index);}
    int distanceAt(long index){if(index<0||index>=count)throw new IndexOutOfBoundsException();return exact?floor:(int)((word(header[16]+(index>>>4))>>>((index&15)*4))&15);}
    /** Sequential decoding uses the unary high vector; cursor cost is linear in archive size. */
    final class RankCursor {
        long index,highIndex,bits;
        RankCursor(long first) {
            index=first;
            if(first<count){long rank=rankAt(first),bit=(rank>>>lowBits)+first;highIndex=(bit>>>6)+1;bits=word(header[14]+(bit>>>6))&(-1L<<(bit&63));}
        }
        long next() {
            if(index==count)return -1;
            while(bits==0){bits=word(header[14]+highIndex);highIndex++;}
            long bit=(highIndex-1)*64+Long.numberOfTrailingZeros(bits);bits&=bits-1;long rank=((bit-index)<<lowBits)|lowAt(index);index++;return rank;
        }
    }
    RankCursor ranks(){return new RankCursor(0);}
    RankCursor ranks(long first){if(first<0||first>count)throw new IndexOutOfBoundsException();return new RankCursor(first);}
    long rankAt(long index) {
        if(index<0||index>=count)throw new IndexOutOfBoundsException();
        long first=0,limit=(universe-1)>>>lowBits;while(first<limit){long middle=first+((limit-first)>>>1);if(selectZero(middle)-middle<=index)first=middle+1;else limit=middle;}return (first<<lowBits)|lowAt(index);
    }
    private void auditEncoding() throws IOException {
        RankCursor cursor=ranks();long previous=-1;long[] counts=new long[12];
        for(long i=0;i<count;i++){long rank=cursor.next();int distance=distanceAt(i);if(rank<=previous||rank>=universe||distance<floor||distance>ceiling)throw new IOException("Invalid compact archive encoding");previous=rank;counts[distance]++;}
        for(int d=0;d<12;d++)if(counts[d]!=header[24+d])throw new IOException("Compact encoded distance count mismatch");
        if(count>0&&(find(previous)!=count-1||find(0)>=0&&distance(0)<floor))throw new IOException("Compact encoded bucket index mismatch");
    }
    public void close(){mapped.close();}
    static void readFully(FileChannel file,ByteBuffer buffer,long offset) throws IOException {while(buffer.hasRemaining()){int read=file.read(buffer,offset);if(read<0)throw new EOFException();offset+=read;}}
    static void writeFully(FileChannel file,ByteBuffer buffer,long offset) throws IOException {while(buffer.hasRemaining())offset+=file.write(buffer,offset);}
    private static void resize(FileChannel file,long bytes) throws IOException {if(bytes>0)writeFully(file,ByteBuffer.wrap(new byte[1]),bytes-1);}
    private static final class MappedFile implements AutoCloseable {
        static final long SEGMENT=1L<<30;final List<MappedByteBuffer> segments=new ArrayList<>();
        MappedFile(Path path,long bytes) throws IOException {try(FileChannel file=FileChannel.open(path,StandardOpenOption.READ)){for(long offset=0;offset<bytes;offset+=SEGMENT){MappedByteBuffer buffer=file.map(FileChannel.MapMode.READ_ONLY,offset,Math.min(SEGMENT,bytes-offset));buffer.order(ByteOrder.LITTLE_ENDIAN);segments.add(buffer);}}catch(IOException|RuntimeException error){close();throw error;}}
        long getLong(long offset){return segments.get((int)(offset/SEGMENT)).getLong((int)(offset%SEGMENT));}
        public void close(){for(MappedByteBuffer buffer:segments)unmap(buffer);segments.clear();}
        private static void unmap(ByteBuffer buffer) {
            try {Class<?> type=Class.forName("sun.misc.Unsafe");var field=type.getDeclaredField("theUnsafe");field.setAccessible(true);Object unsafe=field.get(null);type.getMethod("invokeCleaner",ByteBuffer.class).invoke(unsafe,buffer);}
            catch(ReflectiveOperationException|RuntimeException error){/* GC releases mappings on runtimes that restrict explicit cleaning. */}
        }
    }
}
