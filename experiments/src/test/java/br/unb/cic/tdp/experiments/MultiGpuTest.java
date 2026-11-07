package br.unb.cic.tdp.experiments;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="gpuTests",matches="true")
class MultiGpuTest {
    @TempDir Path root;
    // Two real CUDA contexts on the one local GPU exercise pointer ownership and cleanup.
    // This factory is test-only; production always validates the selected physical ordinals.
    static final class Contexts implements ExtensionResolver.DeviceFactory {
        final List<GpuStreamingCounter> opened=new ArrayList<>();
        boolean failSecond;
        public int available(){return 2;}
        public GpuStreamingCounter open(int ordinal,SmallTables tables,int quantum) throws Exception {
            if(failSecond&&ordinal==1)throw new IllegalStateException("Injected second-device initialization failure");
            GpuStreamingCounter gpu=new GpuStreamingCounter(tables,quantum,0);opened.add(gpu);return gpu;
        }
    }
    @Test void generationAndSortingAreCorrectAcrossIndependentContexts() throws Exception {
        GpuSettingsTest.configured(Map.of("extension.gpuDevices","0,1","extension.gpuSlots","2","extension.gpuSlotMiB","1"),()->runPipeline(false,new Contexts()));
    }
    @Test void multipleGenerationStreamsPerContextPreserveEveryDecisionAndReleaseTheirLeases() throws Exception {
        GpuSettingsTest.configured(Map.of("extension.gpuDevices","0,1","extension.gpuSlots","2","extension.gpuSlotMiB","1","extension.gpuGenerationStreams","2"),()->runPipeline(false,new Contexts()));
    }
    @Test void firstMatchStopsAllContextsWithoutPublishingAnIncompleteSet() throws Exception {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(40),()->
            GpuSettingsTest.configured(Map.of("extension.gpuDevices","0,1","extension.gpuSlots","2","extension.gpuSlotMiB","1","extension.gpuGenerationStreams","2"),()->runPipeline(true,new Contexts())));
    }
    private void runPipeline(boolean decision,ExtensionResolver.DeviceFactory factory) throws Exception {
        int n=decision?6:7;Path source=root.resolve("source"),output=root.resolve("output");
        ExtensionRun.run(ExtensionRunTest.config(source,decision?5:7,false));
        LayerCatalog catalog=new LayerCatalog(source);ExtensionRun.Stats stats=new ExtensionRun.Stats();
        try(CanonicalLayerStore parents=catalog.open(n-1,3,Math.min(4,RetentionPlan.upper(n-1)));
            AsyncCanonicalWriter writer=new AsyncCanonicalWriter(output,n,4,n,64,2,true,decision);
            ExtensionResolver resolver=factory==null?new ExtensionResolver(n,4,writer,new SmallTables(0),true,32,2,0.25,source):
                new ExtensionResolver(n,4,writer,new SmallTables(0),true,32,2,0.25,source,factory);
            GpuCandidatePool pool=new GpuCandidatePool(resolver,parents,n,4,2);
            CanonicalLayerStore.BatchCursor cursor=parents.cursor(3)) {
            assertEquals(2,resolver.deviceCount());
            ExecutorService hosts=Executors.newFixedThreadPool(4);List<Future<?>> work=new ArrayList<>();
            try {
                byte[] rows;
                // Serial first assignments make use of both generation contexts deterministic.
                for(int i=0;i<2&&(rows=cursor.nextBatch(2))!=null;i++)pool.process(rows,stats,writer,resolver);
                while((rows=cursor.nextBatch(2))!=null){byte[] batch=rows;work.add(hosts.submit(()->{pool.process(batch,stats,writer,resolver);return null;}));}
                for(Future<?> task:work)task.get();
            }finally{hosts.shutdownNow();assertTrue(hosts.awaitTermination(10,TimeUnit.SECONDS));}
            pool.close();resolver.finish();assertEquals(0,resolver.cpuQueries.get());
            assertEquals(resolver.gpuQueries.get(),resolver.deviceQueries().values().stream().mapToLong(Long::longValue).sum());
            if(decision) {
                assertTrue(writer.hasMatch());byte[] omega=new byte[n+1],word=new byte[n];
                PackedPermutation5.decode(writer.firstMatch(),0,n+1,omega);ToricOrbit.wordFromOmega(omega,n,word);
                assertEquals(4,DiametralExtensionAudit.bfs(n)[(int)SmallTables.rank(word,n)]);
                assertThrows(java.io.IOException.class,writer::finish);
                assertNull(new LayerCatalog(output).find(n,4));
            }else {
                for(GpuCandidateGenerator generator:pool.generators)assertTrue(generator.batches>0);
                assertEquals(2975,stats.generated.get());assertEquals(1513,writer.finish().weighted[4]);
            }
        }
        if(factory instanceof Contexts contexts)for(GpuStreamingCounter gpu:contexts.opened)assertFalse(gpu.context.contextReady);
        if(!decision)ExtensionRunTest.verify(output,7);
    }
    @Test void initializationFailureClosesDevicesAlreadyOpened() throws Exception {
        Contexts contexts=new Contexts();contexts.failSecond=true;Path source=root.resolve("source");
        ExtensionRun.run(ExtensionRunTest.config(source,3,false));
        GpuSettingsTest.configured(Map.of("extension.gpuDevices","0,1","extension.gpuSlots","2"),()->{
            try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root.resolve("failed"),4,3,4,16,1,true)) {
                assertThrows(java.io.IOException.class,()->new ExtensionResolver(4,3,writer,new SmallTables(0),true,32,1,0.25,source,contexts));
            }
        });
        assertEquals(1,contexts.opened.size());assertFalse(contexts.opened.getFirst().context.contextReady);
    }
    @Test void aSlotEvictsOldWorkspacesToRespectItsTotalBudget() throws Exception {
        Path source=root.resolve("source");ExtensionRun.run(ExtensionRunTest.config(source,7,false));
        GpuSettingsTest.configured(Map.of("extension.gpuDevices","0","extension.gpuSlots","1","extension.gpuSlotMiB","1"),()->{
            try(CanonicalLayerStore parents=new LayerCatalog(source).open(5,2,3);
                AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root.resolve("output"),6,3,6,64,2,true);
                ExtensionResolver resolver=new ExtensionResolver(6,3,writer,new SmallTables(0),true,16384,2,0.25,source);
                GpuCandidatePool pool=new GpuCandidatePool(resolver,parents,6,3,16);
                CanonicalLayerStore.BatchCursor cursor=parents.cursor(2)) {
                ExtensionRun.Stats stats=new ExtensionRun.Stats();byte[] rows;
                while((rows=cursor.nextBatch(16))!=null)pool.process(rows,stats,writer,resolver);
                pool.close();resolver.finish();assertEquals(380,writer.finish().weighted[3]);
                assertTrue(resolver.workspaceEvictions()>0);assertEquals(0,resolver.cpuQueries.get());
            }
        });
    }
    @Test void copiedPatternTablesSurviveSourceContextDestruction() throws Exception {
        Path stored=root.resolve("source");ExtensionRun.run(ExtensionRunTest.config(stored,6,false));
        try(GpuStreamingCounter source=new GpuStreamingCounter(new SmallTables(5),2);
            GpuStreamingCounter target=new GpuStreamingCounter(new SmallTables(0),2)) {
            assertTrue(target.copyPatternTablesFrom(source));source.close();
            byte[] exactFive=DiametralExtensionAudit.bfs(5);long[] ranksFive=new long[120];for(int i=0;i<120;i++)ranksFive[i]=i;
            try(GpuStreamingCounter.Batch batch=target.new Batch(5,3,3,120)) {
                byte[] decisions=batch.classifyRanks(ranksFive,120);
                for(int i=0;i<120;i++)assertEquals(exactFive[i]<=2?0:1,decisions[i]);
            }
            assertEquals(5,target.tableDegree);target.loadPersistedTables(stored,6);assertEquals(6,target.tableDegree);
            byte[] exact=DiametralExtensionAudit.bfs(6);long[] ranks=new long[720];for(int i=0;i<720;i++)ranks[i]=i;
            for(int q=0;q<=4;q++)try(GpuStreamingCounter.Batch batch=target.new Batch(6,q+1,q+1,720)) {
                byte[] decisions=batch.classifyRanks(ranks,ranks.length);
                for(int i=0;i<720;i++)assertEquals(exact[i]<=q?0:1,decisions[i]);
            }
        }
    }
    @Test void twoPhysicalDevicesWhenAvailable() throws Exception {
        Assumptions.assumeTrue(GpuDiametralFilter.deviceCount()>=2,"Requires two physical CUDA GPUs");
        GpuSettingsTest.configured(Map.of("extension.gpuDevices","0,1","extension.gpuSlots","2","extension.gpuSlotMiB","1"),()->runPipeline(false,null));
    }
}
