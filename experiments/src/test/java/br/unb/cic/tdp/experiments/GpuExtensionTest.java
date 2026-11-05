package br.unb.cic.tdp.experiments;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="gpuTests",matches="true")
class GpuExtensionTest {
    @TempDir Path root;
    @Test void persistsEveryRequiredLayerThroughNineOnGpuAndCpu() throws Exception {
        ExtensionRun.run(ExtensionRunTest.config(root,9,true));ExtensionRunTest.verify(root,9);
    }
    @Test void decisionStopsGpuSlotsWithoutPublishingAPartialExactSet() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(30),()->{
            ExtensionRun.run(ExtensionRunTest.config(root,5,false));LayerCatalog catalog=new LayerCatalog(root);
            ExtensionRun.Stats stats=ExtensionRun.decideExact(ExtensionRunTest.config(root,6,true),6,4,catalog,new SmallTables(0));
            assertNotNull(stats.firstMatch);assertEquals(0,stats.cpuQueries);assertFalse(catalog.has(6,4));assertFalse(catalog.has(6,3));
            byte[] omega=new byte[7],word=new byte[6];PackedPermutation5.decode(stats.firstMatch,0,7,omega);ToricOrbit.wordFromOmega(omega,6,word);
            assertEquals(4,DiametralExtensionAudit.bfs(6)[(int)SmallTables.rank(word,6)]);
        });
    }
    @Test void gpuNegativeDecisionPublishesOnlyTheEmptySearchedLayer() throws Exception {
        ExtensionRun.run(ExtensionRunTest.config(root,4,false));LayerCatalog catalog=new LayerCatalog(root);
        ExtensionRun.Stats stats=ExtensionRun.decideExact(ExtensionRunTest.config(root,5,true),5,4,catalog,new SmallTables(0));
        assertNull(stats.firstMatch);assertEquals(0,stats.cpuQueries);assertTrue(catalog.has(5,4));assertEquals(0,catalog.size(5,4));
        assertFalse(catalog.has(5,3));
    }
    @Test void diameterJumpUsesPersistedD9RatherThanScanningS10() throws Exception {
        ExtensionRun.run(ExtensionRunTest.config(root,9,false));
        ExtensionConfig config=new ExtensionConfig(root,10,3,9,128,2,64,2,true,0.25);
        RetentionPlan plan=new RetentionPlan(10);Path parents=new LayerCatalog(root).find(9,5);
        ExtensionRun.Stats stats=ExtensionRun.extend(config,plan,10,parents,new SmallTables(9));
        assertEquals(10L*114327,stats.generated.get());assertTrue(stats.gpuQueries>0);
        assertEquals(0,stats.cpuQueries,"GPU mode must never fall back to CPU sorting");
        try(CanonicalLayerStore store=new CanonicalLayerStore(CanonicalLayerStore.find(root,10,6))) {
            assertEquals(255053,store.manifest.weighted[6]);assertEquals(6,store.manifest.floor);
        }
    }
    @Test void suppliedRankDecisionsKeepInputOrderAcrossOneStepYields() throws Exception {
        byte[] exact=DiametralExtensionAudit.bfs(5);long[] ranks=new long[120];
        for(int i=0;i<120;i++)ranks[i]=(37L*i)%120;
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,1)) {
            for(int budget=0;budget<=3;budget++)try(GpuStreamingCounter.Batch batch=gpu.new Batch(5,budget+1,budget+1,120)) {
                byte[] decisions=batch.classifyRanks(ranks,120);
                for(int i=0;i<120;i++)assertEquals(exact[(int)ranks[i]]<=budget?0:1,decisions[i]);
            }
        }
    }
    @Test void gpuCanonicalDeletionKeysFeedDiskLookupsWithoutCpuSorting() throws Exception {
        String previous=System.getProperty("extension.forceDiskLookups");
        System.setProperty("extension.forceDiskLookups","true");
        try {ExtensionRun.run(ExtensionRunTest.config(root,9,true));ExtensionRunTest.verify(root,9);}
        finally{if(previous==null)System.clearProperty("extension.forceDiskLookups");else System.setProperty("extension.forceDiskLookups",previous);}
    }
    @Test void retainedTailBoundsAreSafeForEverySmallerSearchBudget() throws Exception {
        ExtensionRun.run(ExtensionRunTest.config(root,6,true));byte[] exact=DiametralExtensionAudit.bfs(6);
        long[] ranks=new long[720];for(int i=0;i<ranks.length;i++)ranks[i]=i;
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,1)) {
            gpu.loadPersistedTables(root,6);
            try(var store=new LayerCatalog(root).openTail(6)){gpu.verifyStoredHistogram(store.manifest);}
            for(int q=0;q<=4;q++)try(GpuStreamingCounter.Batch batch=gpu.new Batch(6,q+1,q+1,720)) {
                byte[] decisions=batch.classifyRanks(ranks,720);
                for(int i=0;i<ranks.length;i++)assertEquals(exact[i]<=q?0:1,decisions[i],"rank="+i+" budget="+q);
            }
        }
    }
    @Test void writerFailureStopsSlotsBeforeDestroyingLeasedContext() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(20),()->{
            Path source=root.resolve("source");ExtensionRun.run(ExtensionRunTest.config(source,3,true));
            try(CanonicalLayerStore parents=new CanonicalLayerStore(new LayerCatalog(source).find(3,2))) {
                AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root.resolve("failed"),4,3,4,1,1);
                ExtensionResolver resolver=new ExtensionResolver(4,3,writer,new SmallTables(0),true,32,1,0.25,source);
                try(GpuCandidateGenerator generator=new GpuCandidateGenerator(resolver,parents,4,3,1)) {
                    byte[] bad=new byte[writer.rowBytes];bad[0]=99;bad[writer.rowBytes-1]=1;
                    assertThrows(java.io.IOException.class,()->{writer.submit(bad,1);writer.finish();});
                }finally{resolver.close();assertThrows(java.io.IOException.class,writer::close);}
            }
        });
    }
}
