package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {
    @TempDir Path root;
    @Test void aReferenceCountMismatchCannotPublishACompletedStore() throws Exception {
        AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,3,2,3,8,2,true);
        try {
            byte[] omega=new byte[4];ToricOrbit.canonicalOmega(new byte[]{3,2,1},3,omega);
            try(var buffer=writer.buffer()){buffer.append(omega,2,1);}
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
            assertNotNull(store.compact);assertEquals(store.payloadBytes+1,store.rowBytes);
            byte[] word=new byte[5],omega=new byte[6],exact=DiametralExtensionAudit.bfs(5);
            for(int rank=0;rank<120;rank++) {
                SmallTables.unrank(rank,5,word,0);ToricOrbit.canonicalOmega(word,5,omega);
                assertEquals(exact[rank],store.distance(omega,0));
                assertEquals(exact[rank]>=2?exact[rank]:-1,store.distance(omega,2));
            }
            var ranks=store.compact.ranks();int records=0;long rank;
            while((rank=ranks.next())>=0){assertEquals(exact[(int)rank],store.compact.distanceAt(records));records++;}
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
    @Test void compositeLookupsPreserveInputOrderWhileSkippingResolvedKeys() throws Exception {
        int n=5;byte[] exact=DiametralExtensionAudit.bfs(n),word=new byte[n],omega=new byte[n+1];
        for(int layer=2;layer<=3;layer++)try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,n,layer,n,16,2,true)) {
            try(AsyncCanonicalWriter.Buffer buffer=writer.buffer()) {
                for(int rank=0;rank<exact.length;rank++) {
                    SmallTables.unrank(rank,n,word,0);int weight=ToricOrbit.weight(word,n);if(weight==0)continue;
                    ToricOrbit.omegaFromWord(word,n,omega);buffer.append(omega,exact[rank],weight);
                }
            }
            writer.finish();
        }
        byte[][] keys=new byte[4099][];
        for(int i=0;i<keys.length;i++) {
            SmallTables.unrank(i%exact.length,n,word,0);ToricOrbit.canonicalOmega(word,n,omega);keys[i]=PackedPermutation5.encode(omega,n+1);
        }
        try(CanonicalLayerStore store=new LayerCatalog(root).open(n,2,3)) {
            int[] distances=store.distances(keys,2);
            for(int i=0;i<keys.length;i++)assertEquals(exact[i%exact.length]>=2?exact[i%exact.length]:-1,distances[i]);
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
