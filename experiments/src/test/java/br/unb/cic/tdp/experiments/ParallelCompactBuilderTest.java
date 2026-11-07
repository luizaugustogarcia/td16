package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ParallelCompactBuilderTest {
    @TempDir Path root;
    @Test void parallelExactAndLabeledArchivesAreByteIdenticalToSequentialArchives() throws Exception {
        byte[] distances=DiametralExtensionAudit.bfs(8),word=new byte[8],omega=new byte[9];
        for(boolean exact:new boolean[]{false,true}) {
            Path[] path={null};int floor=exact?5:0;
            GpuSettingsTest.configured(Map.of("extension.compactKeepRocksDb","true","extension.compactBuildThreads","1"),()->{
                try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,8,floor,8,1024,2,exact)) {
                    try(var out=writer.buffer()){for(int rank=0;rank<distances.length;rank++){SmallTables.unrank(rank,8,word,0);int weight=ToricOrbit.weight(word,8);if(weight==0)continue;ToricOrbit.omegaFromWord(word,8,omega);out.append(omega,distances[rank],weight);}}
                    writer.finish();path[0]=writer.target;
                }
            });
            byte[] sequential=Files.readAllBytes(path[0].resolve(CompactRankArchive.FILE));LayerManifest manifest=LayerManifest.read(path[0]);
            for(int threads:new int[]{2,4,8})GpuSettingsTest.configured(Map.of("extension.compactBuildThreads",Integer.toString(threads),"extension.compactParallelThreshold","0"),()->{
                CompactRankArchive.build(path[0],manifest);
                assertArrayEquals(sequential,Files.readAllBytes(path[0].resolve(CompactRankArchive.FILE)),"threads="+threads+" exact="+exact);
                try(CanonicalLayerStore store=new CanonicalLayerStore(path[0])){for(int rank=0;rank<distances.length;rank++){SmallTables.unrank(rank,8,word,0);ToricOrbit.canonicalOmega(word,8,omega);assertEquals(exact&&distances[rank]!=floor?-1:distances[rank],store.distance(omega,floor));}}
                try(var files=Files.list(path[0])){assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith(".compact-")));}
            });
        }
    }
    @Test void parallelSparseDegreeEighteenFieldsPreserveFullRanksAndEmptyRanges() throws Exception {
        Set<Long> ranks=new TreeSet<>();Random random=new Random(713);byte[] word=new byte[18],omega=new byte[19],canonical=new byte[18];Path[] path={null};
        GpuSettingsTest.configured(Map.of("extension.compactKeepRocksDb","true","extension.compactBuildThreads","1"),()->{
            try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,18,10,18,128,2,false)) {
                try(var out=writer.buffer()){for(int i=0;i<128;i++){SmallTables.unrank(random.nextLong(SmallTables.FACTORIAL[18]),18,word,0);ToricOrbit.canonicalOmega(word,18,omega);ToricOrbit.wordFromOmega(omega,18,canonical);long rank=PermutationRank.ofOmega(omega,18);if(ranks.add(rank))out.appendRank(rank,10+(i&1),ToricOrbit.weight(canonical,18));}}
                writer.finish();path[0]=writer.target;
            }
        });
        byte[] sequential=Files.readAllBytes(path[0].resolve(CompactRankArchive.FILE));LayerManifest manifest=LayerManifest.read(path[0]);
        GpuSettingsTest.configured(Map.of("extension.compactBuildThreads","8","extension.compactParallelThreshold","0"),()->{
            CompactRankArchive.build(path[0],manifest);assertArrayEquals(sequential,Files.readAllBytes(path[0].resolve(CompactRankArchive.FILE)));
            try(CanonicalLayerStore store=new CanonicalLayerStore(path[0])){var cursor=store.compact.ranks();for(long rank:ranks)assertEquals(rank,cursor.next());assertEquals(-1,cursor.next());}
        });
    }
    @Test void parallelBitAssemblyHandlesEveryAlignmentAndMultipleSharedWordBoundaries() throws Exception {
        Random random=new Random(829);ExecutorService workers=Executors.newFixedThreadPool(8);
        try {
            for(int alignment=0;alignment<64;alignment++) {
                BitSet expected=new BitSet();long first=alignment;List<Callable<Void>> jobs=new ArrayList<>();Map<Long,Long> boundaries=new ConcurrentHashMap<>();
                Path target=root.resolve("target-"+alignment);FileChannel file=FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
                try(file) {
                    for(int part=0;part<32;part++) {
                        int bits=part%5==0?0:random.nextInt(137)+1;BitSet source=new BitSet();
                        for(int bit=0;bit<bits;bit++)if(random.nextBoolean()){source.set(bit);expected.set(Math.toIntExact(first)+bit);}
                        Path input=root.resolve(alignment+"-"+part);Files.write(input,Arrays.copyOf(source.toByteArray(),(bits+63)/64*8));
                        long offset=first;int length=bits;jobs.add(()->{ParallelCompactBuilder.mergeBits(input,length,file,16,offset,boundaries,null);return null;});first+=bits;
                    }
                    int bytes=Math.toIntExact((first+63)/64*8);CompactRankArchive.writeFully(file,ByteBuffer.wrap(new byte[1]),16+bytes-1);
                    for(Future<Void> done:workers.invokeAll(jobs))done.get();ParallelCompactBuilder.writeBoundaryWords(file,boundaries);
                    byte[] actual=Files.readAllBytes(target);assertArrayEquals(Arrays.copyOf(expected.toByteArray(),bytes),Arrays.copyOfRange(actual,16,16+bytes),"alignment="+alignment);
                }
            }
        }finally{workers.shutdownNow();}
    }
    @Test void duplicateRecordsFailParallelFinalizationAndRemoveTemporarySegments() throws Exception {
        GpuSettingsTest.configured(Map.of("extension.compactBuildThreads","4","extension.compactParallelThreshold","0"),()->{
            AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,3,0,3,8,2,true);
            try {
                try(var out=writer.buffer()){out.append(new byte[]{0,1,2,3},0,1);out.append(new byte[]{0,1,2,3},0,1);}
                assertThrows(IOException.class,writer::finish);assertFalse(Files.exists(writer.target));
                try(var files=Files.list(writer.staging)){assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith(".compact-")));}
            }finally{assertThrows(IOException.class,writer::close);}
        });
    }
    @Test void automaticParallelismProtectsSmallLayersAndExplicitLimitsAreValidated() throws Exception {
        GpuSettingsTest.configured(Map.of("extension.compactBuildThreads","1","extension.compactParallelThreshold","1000000"),()->{
            System.clearProperty("extension.compactBuildThreads");
            assertEquals(Runtime.getRuntime().availableProcessors(),CompactRankArchive.buildThreads(1000000));
            assertEquals(Runtime.getRuntime().availableProcessors(),CompactRankArchive.buildThreads(Long.MAX_VALUE));
            assertEquals(1,CompactRankArchive.buildThreads(999999));
        });
        GpuSettingsTest.configured(Map.of("extension.compactBuildThreads","8","extension.compactParallelThreshold","1000000"),()->{assertEquals(1,CompactRankArchive.buildThreads(999999));assertEquals(8,CompactRankArchive.buildThreads(1000000));});
        GpuSettingsTest.configured(Map.of("extension.compactBuildThreads","184"),()->assertEquals(184,CompactRankArchive.buildThreads(1000000)));
        GpuSettingsTest.configured(Map.of("extension.compactBuildThreads","1","extension.compactParallelThreshold","0"),()->assertEquals(1,CompactRankArchive.buildThreads(Long.MAX_VALUE)));
        GpuSettingsTest.configured(Map.of("extension.compactBuildThreads","0"),()->assertThrows(IllegalArgumentException.class,()->CompactRankArchive.buildThreads(10)));
        GpuSettingsTest.configured(Map.of("extension.compactParallelThreshold","-1"),()->assertThrows(IllegalArgumentException.class,()->CompactRankArchive.buildThreads(10)));
    }
}
