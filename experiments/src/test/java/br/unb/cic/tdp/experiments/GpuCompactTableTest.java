package br.unb.cic.tdp.experiments;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="gpuTests",matches="true")
class GpuCompactTableTest {
    @TempDir Path root;
    private void fixture(int n,int floor) throws Exception {
        byte[] distances=DiametralExtensionAudit.bfs(n),word=new byte[n],omega=new byte[n+1];
        try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,n,floor,n,1024,2,false)) {try(var output=writer.buffer()){for(int rank=0;rank<distances.length;rank++){if(distances[rank]<floor)continue;SmallTables.unrank(rank,n,word,0);int weight=ToricOrbit.weight(word,n);if(weight==0)continue;ToricOrbit.omegaFromWord(word,n,omega);output.append(omega,distances[rank],weight);}}writer.finish();}
    }
    @Test void canonicalCudaBucketsMatchEveryOrdinaryBfsPermutation() throws Exception {
        fixture(8,2);byte[] distances=DiametralExtensionAudit.bfs(8);long[] ranks=new long[distances.length];Arrays.setAll(ranks,i->i);
        GpuSettingsTest.configured(Map.of("extension.gpuCompactOrdinaryMembers","0"),()->{try(GpuStreamingCounter gpu=new GpuStreamingCounter(null)){
            gpu.compactTables=new GpuCompactTables(gpu);assertTrue(gpu.compactTables.load(root,8,2,5,new GpuCacheBudget(0,0.9)));int[] found=gpu.compactTables.query(8,ranks,false);
            for(int i=0;i<found.length;i++)assertEquals(distances[i]>=2?distances[i]:-3,found[i],"rank="+i);
        }});
    }
    @Test void ordinarySparseCudaIndexesPreserveExactMembershipBounds() throws Exception {
        fixture(7,4);byte[] distances=DiametralExtensionAudit.bfs(7);long[] ranks=new long[distances.length];Arrays.setAll(ranks,i->i);
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null)){gpu.compactTables=new GpuCompactTables(gpu);assertTrue(gpu.compactTables.load(root,7,4,4,new GpuCacheBudget(0,0.9)));int[] found=gpu.compactTables.query(7,ranks,false);
            for(int i=0;i<found.length;i++)assertEquals(distances[i]==4?4:-5,found[i]);}
    }
    @Test void compactTablesSupportIndependentContextsAndCannotUseIncompleteTailsAsBounds() throws Exception {
        fixture(6,0);try(GpuStreamingCounter source=new GpuStreamingCounter(null);GpuStreamingCounter target=new GpuStreamingCounter(null)) {
            source.compactTables=new GpuCompactTables(source);target.compactTables=new GpuCompactTables(target);
            assertThrows(IllegalArgumentException.class,()->source.compactTables.load(root,6,0,3,new GpuCacheBudget(0,0.9)));
            assertFalse(source.compactTables.load(root,6,0,4,new GpuCacheBudget(Long.MAX_VALUE,0.9)));
            assertTrue(source.compactTables.load(root,6,0,4,new GpuCacheBudget(0,0.9)));assertTrue(target.compactTables.load(root,6,0,4,new GpuCacheBudget(0,0.9)));source.close();
            assertEquals(0,target.compactTables.query(6,new long[]{0},false)[0]);
        }
    }
    @Test void denseTablesReleaseDuplicateCompactAllocationsWithoutLosingParentCoverage() throws Exception {
        fixture(6,0);try(GpuStreamingCounter gpu=new GpuStreamingCounter(null)) {
            gpu.compactTables=new GpuCompactTables(gpu);
            assertTrue(gpu.compactTables.load(root,6,0,4,new GpuCacheBudget(0,0.9)));
            assertTrue(gpu.compactTables.bytes>GpuCompactTables.WORDS*8L);
            gpu.loadPersistedTables(root,6);
            assertEquals(GpuCompactTables.WORDS*8L,gpu.compactTables.bytes);
            assertFalse(gpu.compactCovers(6,0,4));assertEquals(1,gpu.parentLookupKind(6,0));
            byte[] distances=DiametralExtensionAudit.bfs(7);long[] ranks=new long[distances.length];Arrays.setAll(ranks,i->i);
            try(var batch=gpu.new Batch(7,4,4,ranks.length)) {
                byte[] decisions=batch.classifyRanks(ranks,ranks.length);
                for(int i=0;i<decisions.length;i++)assertEquals(distances[i]>3?1:0,decisions[i]);
            }
        }
    }
    @Test void compactResidentDfsMatchesIndependentBfsAtEveryBudget() throws Exception {
        fixture(6,0);byte[] distances=DiametralExtensionAudit.bfs(7);long[] ranks=new long[distances.length];Arrays.setAll(ranks,i->i);
        GpuSettingsTest.configured(Map.of("extension.gpuCompactOrdinaryMembers","0"),()->{try(GpuStreamingCounter gpu=new GpuStreamingCounter(new SmallTables(3),4096)){gpu.compactTables=new GpuCompactTables(gpu);assertTrue(gpu.compactTables.load(root,6,0,4,new GpuCacheBudget(0,0.9)));
            for(int budget=0;budget<=4;budget++)try(var batch=gpu.new Batch(7,budget+1,budget+1,ranks.length,null)){byte[] decisions=batch.classifyRanks(ranks,ranks.length);for(int i=0;i<decisions.length;i++)assertEquals(distances[i]>budget?1:0,decisions[i],"budget="+budget+" rank="+i);}
        }});
    }
}
