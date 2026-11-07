package br.unb.cic.tdp.experiments;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;
import static org.junit.jupiter.api.Assertions.*;

class RocksTuningTest {
    @TempDir Path root;
    @Test void serverSettingsReachNativeOptionsWithoutSmallMachineCaps() throws Exception {
        RocksDB.loadLibrary();
        GpuSettingsTest.configured(Map.ofEntries(
            Map.entry("rocks.writeThreads","128"),Map.entry("rocks.backgroundJobs","64"),
            Map.entry("rocks.flushThreads","16"),Map.entry("rocks.subcompactions","64"),
            Map.entry("rocks.memtableMiB","1024"),Map.entry("rocks.maxMemtables","12"),
            Map.entry("rocks.sstMiB","1024"),Map.entry("rocks.maxOpenFiles","-1")),()->{
            try(Cache cache=new LRUCache(1L<<20);Options options=RocksTuning.writerOptions(cache)) {
                assertEquals(128,RocksTuning.writeThreads());
                assertEquals(64,options.maxBackgroundJobs());assertEquals(16,options.maxBackgroundFlushes());
                assertEquals(64,options.maxSubcompactions());assertEquals(12,options.maxWriteBufferNumber());
                assertEquals(1L<<30,options.writeBufferSize());assertEquals(1L<<30,options.targetFileSizeBase());
                assertEquals(-1,options.maxOpenFiles());
                assertTrue(options.disableAutoCompactions());
                assertEquals(0,options.softPendingCompactionBytesLimit());assertEquals(0,options.hardPendingCompactionBytesLimit());
                assertEquals(-1,options.levelZeroSlowdownWritesTrigger());assertEquals(Integer.MAX_VALUE,options.levelZeroStopWritesTrigger());
                assertEquals(1L<<30,options.delayedWriteRate());
            }
        });
    }
    @Test void overlappingUncompactedRunsPassTheOldFileLimitAndMergeExactlyInParallel() throws Exception {
        RocksDB.loadLibrary();
        GpuSettingsTest.configured(Map.of("rocks.backgroundJobs","4","rocks.flushThreads","4","extension.compactBuildThreads","1"),()->{
            int degree=8;byte[] distances=DiametralExtensionAudit.bfs(degree),word=new byte[degree],omega=new byte[degree+1];
            long[] canonical=new long[12],weighted=new long[12];List<long[]> records=new ArrayList<>();
            for(int rank=0;rank<distances.length;rank++) {
                SmallTables.unrank(rank,degree,word,0);int weight=ToricOrbit.weight(word,degree);if(weight==0)continue;
                ToricOrbit.omegaFromWord(word,degree,omega);long canonicalRank=PermutationRank.ofOmega(omega,degree);int distance=distances[rank];
                records.add(new long[]{canonicalRank,distance,weight});canonical[distance]++;weighted[distance]+=weight;
            }
            Collections.shuffle(records,new Random(719));Path directory=root.resolve("uncompacted");Files.createDirectories(directory);
            try(Cache cache=new LRUCache(1L<<20);Options options=RocksTuning.writerOptions(cache);
                RocksDB database=RocksDB.open(options,directory.resolve("db").toString());
                WriteOptions writes=new WriteOptions().setDisableWAL(true);FlushOptions flush=new FlushOptions().setWaitForFlush(true)) {
                for(int first=0;first<records.size();first+=16) {
                    try(WriteBatch batch=new WriteBatch()) {
                        for(int i=first;i<Math.min(records.size(),first+16);i++) {
                            long[] row=records.get(i);byte[] key=new byte[PermutationRank.bytes(degree)];PermutationRank.encode(row[0],degree,key,0);
                            batch.put(key,new byte[]{(byte)row[1],(byte)row[2]});
                        }
                        database.write(writes,batch);
                    }
                    database.flush(flush);
                }
                assertTrue(Long.parseLong(database.getProperty("rocksdb.num-files-at-level0"))>64,"Overlapping sorted runs must exceed the former stop threshold without compaction");
                assertEquals(0,database.getLongProperty("rocksdb.num-running-compactions"));
            }
            LayerManifest manifest=new LayerManifest(degree,0,degree,canonical,weighted,false);manifest.write(directory);
            CompactRankArchive.build(directory,manifest);byte[] sequential=Files.readAllBytes(directory.resolve(CompactRankArchive.FILE));
            GpuSettingsTest.configured(Map.of("extension.compactBuildThreads","4","extension.compactParallelThreshold","0"),()->CompactRankArchive.build(directory,manifest));
            assertArrayEquals(sequential,Files.readAllBytes(directory.resolve(CompactRankArchive.FILE)));
            assertEquals(SmallTables.FACTORIAL[degree],Arrays.stream(weighted).sum());
            records.sort(Comparator.comparingLong(row->row[0]));
            try(CompactRankArchive archive=CompactRankArchive.open(directory,manifest)) {
                var ranks=archive.ranks();
                for(long[] row:records){assertEquals(row[0],ranks.next());assertEquals(row[1],archive.distance(row[0]));}
                assertEquals(-1,ranks.next());
            }
        });
    }
    @Test void invalidResourceOverridesAreRejected() throws Exception {
        GpuSettingsTest.configured(Map.of("rocks.subcompactions","0"),()->assertThrows(IllegalArgumentException.class,RocksTuning::subcompactions));
        GpuSettingsTest.configured(Map.of("rocks.maxMemtables","0"),()->assertThrows(IllegalArgumentException.class,RocksTuning::maxMemtables));
        for(String invalid:new String[]{"0","-2"})GpuSettingsTest.configured(Map.of("rocks.maxOpenFiles",invalid),()->assertThrows(IllegalArgumentException.class,RocksTuning::maxOpenFiles));
    }
}
