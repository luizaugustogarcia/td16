package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {
    @TempDir Path root;
    @Test void aReferenceCountMismatchCannotPublishACompletedStore() throws Exception {
        AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,3,2,3,8,2,true);
        try {
            try(var buffer=writer.buffer()){buffer.append(new byte[]{3,2,1,0},2,1);}
            assertThrows(IOException.class,()->writer.finish(2));
            assertFalse(Files.exists(writer.target));assertFalse(Files.exists(writer.staging.resolve("manifest.properties")));
        }finally{assertThrows(IOException.class,writer::close);}
    }
    @Test void fiveBitFieldsRoundTripAcrossByteBoundaries() {
        Random random=new Random(20261103);
        for(int n=1;n<=19;n++)for(int sample=0;sample<100;sample++) {
            byte[] values=new byte[n];for(int i=0;i<n;i++)values[i]=(byte)i;
            for(int i=n-1;i>0;i--){int j=random.nextInt(i+1);byte v=values[i];values[i]=values[j];values[j]=v;}
            byte[] packed=PackedPermutation5.encode(values,n),decoded=new byte[n];
            assertEquals((5*n+7)/8,packed.length);PackedPermutation5.decode(packed,0,n,decoded);assertArrayEquals(values,decoded);
        }
        assertEquals(12,PackedPermutation5.bytes(19));
        assertThrows(IllegalArgumentException.class,()->PackedPermutation5.encode(new byte[]{0,0},2));
    }
    @Test void asynchronousBuffersPublishOnlyAfterDrainAndFlush() throws Exception {
        Path target=CanonicalLayerStore.path(root,5,0);
        try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,5,0,5,2,1)) {
            byte[] word=new byte[5],omega=new byte[6];byte[] exact=DiametralExtensionAudit.bfs(5);
            try(AsyncCanonicalWriter.Buffer buffer=writer.buffer()) {
                for(int rank=0;rank<120;rank++) {
                    SmallTables.unrank(rank,5,word,0);int weight=ToricOrbit.weight(word,5);if(weight==0)continue;
                    ToricOrbit.omegaFromWord(word,5,omega);buffer.append(omega,exact[rank],weight);
                }
            }
            assertFalse(Files.exists(target));assertNull(CanonicalLayerStore.find(root,5,0));
            LayerManifest manifest=writer.finish();assertEquals(20,manifest.canonicalSize());
            assertArrayEquals(new long[]{1,20,68,31,0,0,0,0,0,0,0,0},manifest.weighted);
        }
        try(CanonicalLayerStore store=new CanonicalLayerStore(target)) {
            assertFalse(store.manifest.legacy());assertEquals(store.payloadBytes+1,store.rowBytes);
            byte[] word=new byte[5],omega=new byte[6],exact=DiametralExtensionAudit.bfs(5);
            for(int rank=0;rank<120;rank++) {
                SmallTables.unrank(rank,5,word,0);ToricOrbit.canonicalOmega(word,5,omega);
                assertEquals(exact[rank],store.distance(omega,0));
                assertEquals(exact[rank]>=2?exact[rank]:-1,store.distance(omega,2));
            }
            // Inspect actual persisted bytes, rather than a reconstructed cursor row.
            int records=0;
            try(RocksIterator iterator=store.database.newIterator()) {
                for(iterator.seekToFirst();iterator.isValid();iterator.next()) {
                    byte[] key=iterator.key(),value=iterator.value();
                    assertEquals(PackedPermutation5.bytes(6),key.length);assertEquals(1,value.length);
                    assertEquals(0,value[0]&0xf0);
                    PackedPermutation5.decode(key,0,6,omega);ToricOrbit.wordFromOmega(omega,5,word);
                    assertEquals(exact[(int)SmallTables.rank(word,5)],value[0]);records++;
                }
                iterator.status();
            }
            assertEquals(20,records);
            try(CanonicalLayerStore.BatchCursor cursor=store.cursor(2)) {
                byte[] rows;int filtered=0;
                while((rows=cursor.nextBatch(3))!=null)for(int offset=0;offset<rows.length;offset+=store.rowBytes) {
                    assertTrue(rows[offset]>=2);filtered++;
                }
                assertEquals(store.manifest.canonical[2]+store.manifest.canonical[3],filtered);
            }
        }
    }
    @Test void legacyWeightStoresStillFeedNewDistanceStores() throws Exception {
        int n=5;Path target=CanonicalLayerStore.path(root,n,0);Files.createDirectories(target);
        byte[] exact=DiametralExtensionAudit.bfs(n),word=new byte[n],omega=new byte[n+1];
        long[] canonical=new long[12],weighted=new long[12];
        try(Options options=new Options().setCreateIfMissing(true);
            RocksDB database=RocksDB.open(options,target.resolve("db").toString());
            WriteOptions writes=new WriteOptions().setDisableWAL(true);
            FlushOptions flush=new FlushOptions().setWaitForFlush(true)) {
            for(int rank=0;rank<exact.length;rank++) {
                SmallTables.unrank(rank,n,word,0);int weight=ToricOrbit.weight(word,n);if(weight==0)continue;
                ToricOrbit.omegaFromWord(word,n,omega);byte[] key=new byte[PackedPermutation5.bytes(n+1)+1];
                key[0]=exact[rank];PackedPermutation5.encode(omega,n+1,key,1);
                database.put(writes,key,new byte[]{(byte)weight});canonical[exact[rank]]++;weighted[exact[rank]]+=weight;
            }
            database.flush(flush);
        }
        new LayerManifest(n,0,6,canonical,weighted).write(target);
        Properties properties=new Properties();Path manifest=target.resolve("manifest.properties");
        try(var input=Files.newInputStream(manifest)){properties.load(input);}
        properties.remove("storage"); // The original format had no layout discriminator.
        try(var output=Files.newOutputStream(manifest)){properties.store(output,"Legacy fixture");}
        try(CanonicalLayerStore store=new CanonicalLayerStore(target);CanonicalLayerStore.BatchCursor cursor=store.cursor(2)) {
            assertTrue(store.manifest.legacy());
            for(int rank=0;rank<exact.length;rank++) {
                SmallTables.unrank(rank,n,word,0);ToricOrbit.canonicalOmega(word,n,omega);
                assertEquals(exact[rank],store.distance(omega,0));
                assertEquals(exact[rank]>=2?exact[rank]:-1,store.distance(omega,2));
            }
            byte[] rows;long[] reconstructed=new long[12];
            while((rows=cursor.nextBatch(3))!=null)for(int offset=0;offset<rows.length;offset+=store.rowBytes) {
                int distance=rows[offset]&255;assertTrue(distance>=2);
                PackedPermutation5.decode(rows,offset+1,n+1,omega);ToricOrbit.wordFromOmega(omega,n,word);
                reconstructed[distance]+=ToricOrbit.weight(word,n);
            }
            assertEquals(weighted[2],reconstructed[2]);assertEquals(weighted[3],reconstructed[3]);
        }
        ExtensionRun.extend(ExtensionRunTest.config(root,6,false),new RetentionPlan(6),6,target,new SmallTables(6));
        try(CanonicalLayerStore store=new CanonicalLayerStore(CanonicalLayerStore.find(root,6,4))) {
            assertFalse(store.manifest.legacy());assertEquals(45,store.manifest.weighted[4]);
        }
    }
    @Test void exactStoresDiscardOtherDistancesAndPublishEmptySets() throws Exception {
        byte[] word=new byte[5],omega=new byte[6],exact=DiametralExtensionAudit.bfs(5);
        try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,5,2,5,16,2,true)) {
            try(AsyncCanonicalWriter.Buffer buffer=writer.buffer()) {
                for(int rank=0;rank<120;rank++) {
                    SmallTables.unrank(rank,5,word,0);int weight=ToricOrbit.weight(word,5);if(weight==0)continue;
                    ToricOrbit.omegaFromWord(word,5,omega);buffer.append(omega,exact[rank],weight);
                }
            }
            LayerManifest manifest=writer.finish();assertTrue(manifest.exact);assertEquals(68,manifest.weighted[2]);
            assertEquals(manifest.canonical[2],manifest.canonicalSize());
        }
        LayerCatalog catalog=new LayerCatalog(root);
        assertTrue(catalog.has(5,2));assertFalse(catalog.has(5,1));assertFalse(catalog.has(5,3));
        try(var store=catalog.open(5,2,2)) {
            byte[][] keys=new byte[4099][];
            for(int i=0;i<keys.length;i++) {
                SmallTables.unrank(i%120,5,word,0);ToricOrbit.canonicalOmega(word,5,omega);keys[i]=PackedPermutation5.encode(omega,6);
            }
            int[] distances=store.distances(keys,2);
            for(int i=0;i<keys.length;i++)assertEquals(exact[i%120]==2?2:-1,distances[i]);
        }
        try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,18,11,18,16,2,true)) {
            assertEquals(0,writer.finish().canonicalSize());
        }
        assertTrue(catalog.has(18,11));assertEquals(0,catalog.size(18,11));
        assertFalse(catalog.has(18,10));
        try(var store=catalog.open(18,11,11);var cursor=store.source(11,16,2)){assertNull(cursor.nextBatch(16));}
    }
    @Test void malformedDistanceValuesAreRejectedByLookupAndStreaming() throws Exception {
        byte[] omega={0,1};Path target=CanonicalLayerStore.path(root,1,0);
        try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,1,0,1,1,1)) {
            try(AsyncCanonicalWriter.Buffer buffer=writer.buffer()){buffer.append(omega,0,1);}writer.finish();
        }
        for(byte[] bad:new byte[][]{new byte[]{16},new byte[]{15},new byte[0]}) {
            try(Options options=new Options();RocksDB database=RocksDB.open(options,target.resolve("db").toString());
                WriteOptions writes=new WriteOptions().setDisableWAL(true);FlushOptions flush=new FlushOptions().setWaitForFlush(true)) {
                database.put(writes,PackedPermutation5.encode(omega,2),bad);database.flush(flush);
            }
            try(CanonicalLayerStore store=new CanonicalLayerStore(target);CanonicalLayerStore.BatchCursor cursor=store.cursor(0)) {
                assertThrows(IOException.class,()->store.distance(omega,0));assertThrows(IOException.class,()->cursor.nextBatch(1));
                String previous=System.getProperty("extension.parallelReadThreshold");System.setProperty("extension.parallelReadThreshold","0");
                try(CanonicalLayerStore.BatchCursor parallel=store.source(0,1,2)) {
                    assertThrows(IOException.class,()->parallel.nextBatch(1));
                }finally{if(previous==null)System.clearProperty("extension.parallelReadThreshold");else System.setProperty("extension.parallelReadThreshold",previous);}
            }
        }
    }
    @Test void backgroundFailureDoesNotPublishOrDeadlockProducers() throws Exception {
        AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,3,0,3,1,1);
        byte[] malformed=new byte[writer.rowBytes];malformed[0]=99;malformed[writer.rowBytes-1]=1;
        assertThrows(IOException.class,()->{writer.submit(malformed,1);writer.finish();});
        assertThrows(IOException.class,writer::close);
        assertFalse(Files.exists(writer.target));assertNull(CanonicalLayerStore.find(root,3,0));
    }
    @Test void parallelParentRangesCoverEveryRetainedCanonicalExactlyOnce() throws Exception {
        int n=6;byte[] word=new byte[n],omega=new byte[n+1],exact=DiametralExtensionAudit.bfs(n);
        Map<Long,Integer> expected=new HashMap<>();
        try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,n,0,n,16,2)) {
            try(AsyncCanonicalWriter.Buffer buffer=writer.buffer()) {
                for(int rank=0;rank<exact.length;rank++) {
                    SmallTables.unrank(rank,n,word,0);int weight=ToricOrbit.weight(word,n);if(weight==0)continue;
                    ToricOrbit.omegaFromWord(word,n,omega);buffer.append(omega,exact[rank],weight);expected.put((long)rank,(int)exact[rank]);
                }
            }
            writer.finish();
        }
        String previous=System.getProperty("extension.parallelReadThreshold");System.setProperty("extension.parallelReadThreshold","0");
        try(CanonicalLayerStore store=new CanonicalLayerStore(CanonicalLayerStore.find(root,n,0))) {
            for(int minimum=0;minimum<=4;minimum++) {
                Set<Long> actual=new HashSet<>();
                try(CanonicalLayerStore.BatchCursor source=store.source(minimum,7,3)) {
                    byte[] rows;while((rows=source.nextBatch(7))!=null)for(int offset=0;offset<rows.length;offset+=store.rowBytes) {
                        PackedPermutation5.decode(rows,offset+1,n+1,omega);ToricOrbit.wordFromOmega(omega,n,word);
                        long rank=SmallTables.rank(word,n);assertTrue(actual.add(rank),"Range overlap");
                        assertEquals(expected.get(rank).intValue(),rows[offset]);assertTrue(rows[offset]>=minimum);
                    }
                }
                Set<Long> retained=new HashSet<>();for(var entry:expected.entrySet())if(entry.getValue()>=minimum)retained.add(entry.getKey());
                assertEquals(retained,actual,"minimum="+minimum);
            }
        }finally{if(previous==null)System.clearProperty("extension.parallelReadThreshold");else System.setProperty("extension.parallelReadThreshold",previous);}
    }
    @Test void abortedDegreeIsNeverUsedAsACompleteParent() throws Exception {
        try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,1,0,1,2,1)) {
            try(AsyncCanonicalWriter.Buffer buffer=writer.buffer()){buffer.append(new byte[]{0,1},0,1);}
        }
        assertNull(CanonicalLayerStore.find(root,1,0));
    }
    @Test void backwardPlanRetainsTheRequiredAdditionalLayers() {
        RetentionPlan plan=new RetentionPlan(18);
        assertEquals(5,plan.floor[13]);assertEquals(6,plan.floor[14]);assertEquals(7,plan.floor[15]);
        assertEquals(8,plan.floor[16]);assertEquals(9,plan.floor[17]);assertEquals(10,plan.floor[18]);
        for(int n=1;n<18;n++)assertTrue(plan.floor[n]<=Math.max(0,plan.floor[n+1]-1));
        assertEquals(5,new RetentionPlan(10).floor[9]);
    }
}
