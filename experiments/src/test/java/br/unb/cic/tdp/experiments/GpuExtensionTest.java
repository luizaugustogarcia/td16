package br.unb.cic.tdp.experiments;

import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="gpuTests",matches="true")
class GpuExtensionTest {
    @TempDir Path root;
    @Test void persistsEveryRequiredLayerThroughElevenOnGpu() throws Exception {
        int threads=Math.min(8,Runtime.getRuntime().availableProcessors());
        GpuSettingsTest.configured(Map.ofEntries(
            Map.entry("extension.gpuDevices","0"),Map.entry("extension.gpuSlots","4"),
            Map.entry("extension.gpuPatternMaxDegree","auto"),Map.entry("extension.gpuMembershipDegree","auto"),
            Map.entry("extension.gpuParentBatch","512"),Map.entry("extension.gpuQuantum","4096"),
            Map.entry("extension.verifyGpuPatterns","true"),Map.entry("extension.forceDiskLookups","false"),
            Map.entry("extension.through","11"),Map.entry("extension.diameterOnly","false"),
            Map.entry("benchmark.patternHorizon","0"),Map.entry("count.cpuThreads",Integer.toString(threads)),
            Map.entry("rocks.writeThreads","4"),Map.entry("rocks.backgroundJobs","2"),Map.entry("rocks.flushThreads","2"),
            Map.entry("rocks.memtableMiB","64"),Map.entry("rocks.sstMiB","64"),Map.entry("rocks.cacheMiB","64")),()->{
            try(var files=Files.list(root)){assertEquals(0,files.count(),"Start without precomputed sets");}
            ExtensionConfig config=new ExtensionConfig(root,11,threads,0,4096,8,32768,16,true,0.50);
            // One uninterrupted GPU run from the seed, including lazy support backfills.
            // GPU mode rejects CPU sorting internally; baseDegree=0 supplies no BFS oracle.
            ExtensionRun.run(config);
            long[] counts={0,1,1,1,1,31,45,1513,2836,114327,255053,12537954};
            int[] diameters={0,0,1,2,3,3,4,4,5,5,6,6};
            LayerCatalog catalog=new LayerCatalog(root);
            for(int n=1;n<=11;n++) {
                Path path=catalog.find(n,diameters[n]);assertNotNull(path,"Missing D("+n+")");
                LayerManifest manifest=LayerManifest.read(path);
                assertTrue(manifest.exact);assertEquals(n,manifest.degree);assertEquals(11,manifest.horizon);
                assertEquals(diameters[n],manifest.floor);assertEquals(counts[n],manifest.weighted[diameters[n]],"|D("+n+")|");
            }
            // Independent exhaustive membership checks remain feasible through degree 9.
            ExtensionRunTest.verify(root,9);
            // Reopen every degree-10/11 result and support set, and reconstruct its
            // ordinary count from the stored permutations rather than trusting metadata.
            for(int n=10;n<=11;n++)for(int distance=0;distance<=diameters[n];distance++) {
                if(!catalog.has(n,distance))continue;
                try(CanonicalLayerStore store=catalog.open(n,distance,distance);
                    CanonicalLayerStore.BatchCursor cursor=store.cursor(distance)) {
                    long records=0,ordinary=0;byte[] rows,omega=new byte[n+1],word=new byte[n];
                    while((rows=cursor.nextBatch(4096))!=null)for(int offset=0;offset<rows.length;offset+=store.rowBytes) {
                        assertEquals(distance,rows[offset]&255);
                        PackedPermutation5.decode(rows,offset+1,n+1,omega);ToricOrbit.wordFromOmega(omega,n,word);
                        int weight=ToricOrbit.weight(word,n);assertTrue(weight>0,"Stored permutation must be canonical");
                        records++;ordinary+=weight;
                    }
                    assertEquals(store.manifest.canonicalSize(),records,"Canonical coverage n="+n+" d="+distance);
                    assertEquals(store.manifest.weighted[distance],ordinary,"Orbit-weighted coverage n="+n+" d="+distance);
                }
            }
            try(var files=Files.walk(root)) {
                assertFalse(files.anyMatch(path->path.getFileName().toString().endsWith(".partial")),"No incomplete stores after success");
            }
        });
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
        ExtensionRun.Stats[] measured={null};
        GpuSettingsTest.configured(Map.of("extension.rootMovePrepass","true"),
            ()->measured[0]=ExtensionRun.extend(config,plan,10,parents,new SmallTables(9)));
        ExtensionRun.Stats stats=measured[0];
        assertEquals(10L*114327,stats.generated.get());
        assertTrue(stats.rootMoveResolved.get()>0);
        assertTrue(stats.gpuQueries>0);
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
    @Test void realizableOddUnicyclesUseTheGeneralClearingRule() throws Exception {
        byte[][] words={
            {4,3,2,1},
            {3,6,2,5,1,4},
            {8,7,6,5,4,3,2,1},
            {7,14,2,9,16,4,11,18,6,13,1,8,15,3,10,17,5,12},
            {9,18,8,17,7,16,6,15,5,14,4,13,3,12,2,11,1,10}
        };
        int[] clearing={3,4,5,10,10};
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,1)) {
            for(int i=0;i<words.length;i++) {
                int n=words[i].length;assertTrue(gpu.oddCyclePrepass(n));
                long rank=SmallTables.rank(words[i],n);
                if(n<=8)assertEquals(clearing[i],DiametralExtensionAudit.bfs(n)[(int)rank]);
                try(GpuStreamingCounter.Batch batch=gpu.new Batch(n,clearing[i]+1,clearing[i]+1,1,null,false,false,false,true)) {
                    assertArrayEquals(new byte[]{0},batch.classifyRanks(new long[]{rank},1),"N="+(n+1));
                    assertEquals(1,batch.oddCycleResolved,"N="+(n+1));
                }
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
