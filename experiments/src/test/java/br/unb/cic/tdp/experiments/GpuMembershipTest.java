package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import jcuda.Pointer;
import jcuda.driver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static jcuda.driver.JCudaDriver.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="gpuTests",matches="true")
class GpuMembershipTest {
    @TempDir Path root;
    @Test void allocationSizesRemain64Bit() {
        assertEquals(4,GpuMembershipTable.bytesForDegree(1));
        assertEquals(92,GpuMembershipTable.bytesForDegree(6));
        assertEquals(10_897_286_400L,GpuMembershipTable.bytesForDegree(14));
        assertEquals(800_296_713_216_000L,GpuMembershipTable.bytesForDegree(18));
        assertThrows(IllegalArgumentException.class,()->GpuMembershipTable.bytesForDegree(0));
    }
    @Test void packedRootValidationRejectsRankKeyMismatchesAbove32Bits() throws Exception {
        int n=14,width=PackedPermutation5.bytes(n+1);byte[] word=new byte[n],omega=new byte[n+1],keys=new byte[2*width+3];
        word[0]=14;for(int i=1;i<n;i++)word[i]=(byte)i;
        long rank=SmallTables.rank(word,n);assertTrue(rank>0xffff_ffffL);
        ToricOrbit.omegaFromWord(word,n,omega);PackedPermutation5.encode(omega,n+1,keys,3);
        for(int i=0;i<n;i++)word[i]=(byte)(i+1);
        ToricOrbit.omegaFromWord(word,n,omega);PackedPermutation5.encode(omega,n+1,keys,3+width);
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,64)) {
            CUstream stream=new CUstream();cuStreamCreate(stream,CUstream_flags.CU_STREAM_NON_BLOCKING);
            try(GpuStreamingCounter.Batch batch=gpu.new Batch(n,2,2,16,stream)) {
                assertArrayEquals(new byte[]{0,0},batch.classifyRanks(new long[]{rank,0},2,null,keys,3));
                assertThrows(AssertionError.class,()->batch.classifyRanks(new long[]{0,0},2,null,keys,3));
                assertArrayEquals(new byte[]{0,0},batch.classifyRanks(new long[]{rank,0},2,null,keys,3));
            }finally{cuStreamDestroy(stream);}
        }
    }
    @Test void orbitExpansionMatchesEveryOrdinaryPermutationIncludingStabilizers() throws Exception {
        ExtensionRun.run(ExtensionRunTest.config(root,7,false));
        for(int n:new int[]{3,5,6,7})try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,64)) {
            gpu.membership=GpuMembershipTable.load(gpu,root,n,0);assertNotNull(gpu.membership);
            byte[] actual=new byte[(int)gpu.membership.bytes];cuMemcpyDtoH(Pointer.to(actual),gpu.membership.bits,actual.length);
            byte[] exact=DiametralExtensionAudit.bfs(n);
            for(int rank=0;rank<exact.length;rank++)assertEquals(exact[rank]==RetentionPlan.upper(n),
                (actual[rank>>3]&(1<<(rank&7)))!=0,"n="+n+" rank="+rank);
            for(int rank=exact.length;rank<actual.length*8;rank++)assertEquals(0,actual[rank>>3]&(1<<(rank&7)),"Padding bit");
        }
    }
    @Test void coarseNonmembershipBoundsStaySafeForAllBudgetsAndLargerContractedStates() throws Exception {
        ExtensionRun.run(ExtensionRunTest.config(root,6,false));
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(new SmallTables(5),64)) {
            gpu.membership=GpuMembershipTable.load(gpu,root,6,0);assertNotNull(gpu.membership);
            assertEquals(2,gpu.parentLookupKind(6,4));
            assertEquals(0,gpu.parentLookupKind(6,3),"Nonmembership does not distinguish distance 3 from smaller distances");
            for(int n=6;n<=8;n++) {
                byte[] exact=DiametralExtensionAudit.bfs(n);long[] ranks=new long[exact.length];for(int i=0;i<ranks.length;i++)ranks[i]=i;
                for(int q=0;q<=RetentionPlan.upper(n);q++)try(GpuStreamingCounter.Batch batch=gpu.new Batch(n,q+1,q+1,ranks.length)) {
                    byte[] decisions=batch.classifyRanks(ranks,ranks.length);
                    for(int i=0;i<ranks.length;i++)assertEquals(exact[i]<=q?0:1,decisions[i],"n="+n+" rank="+i+" budget="+q);
                }
            }
        }
    }
    @Test void completedBitmapFeedsTheNextDiameterJumpEntirelyOnGpu() throws Exception {
        Path source=root.resolve("source"),output=root.resolve("output");ExtensionRun.run(ExtensionRunTest.config(source,7,false));
        GpuSettingsTest.configured(Map.of("extension.gpuDevices","0","extension.gpuSlots","2",
            "extension.gpuMembershipDegree","7","extension.gpuPatternMaxDegree","6"),()->{
            try(CanonicalLayerStore parents=new LayerCatalog(source).open(7,4,4);
                AsyncCanonicalWriter writer=new AsyncCanonicalWriter(output,8,5,8,64,2,true);
                ExtensionResolver resolver=new ExtensionResolver(8,5,writer,new SmallTables(0),true,128,2,0.25,source);
                GpuCandidateGenerator generator=new GpuCandidateGenerator(resolver,parents,8,5,8);
                CanonicalLayerStore.BatchCursor cursor=parents.cursor(4)) {
                assertEquals(2,generator.lookupKind);assertTrue(generator.dense);
                ExtensionRun.Stats stats=new ExtensionRun.Stats();byte[] rows;
                while((rows=cursor.nextBatch(8))!=null) {
                    GpuCandidateGenerator.Output packet=generator.produce(rows,stats,resolver);
                    if(packet!=null)generator.consume(packet,stats,writer,resolver);
                }
                generator.close();resolver.finish();assertEquals(0,resolver.cpuQueries.get());
                assertEquals(8L*1513,stats.generated.get());assertEquals(2836,writer.finish().weighted[5]);
            }
        });
        ExtensionRunTest.verify(output,8);
    }
    @Test void copiedBitmapIsIndependentOfTheSourceContext() throws Exception {
        ExtensionRun.run(ExtensionRunTest.config(root,7,false));
        int targetOrdinal=GpuDiametralFilter.deviceCount()>1?1:0;
        try(GpuStreamingCounter source=new GpuStreamingCounter(new SmallTables(5),64,0);
            GpuStreamingCounter target=new GpuStreamingCounter(new SmallTables(5),64,targetOrdinal)) {
            source.membership=GpuMembershipTable.load(source,root,7,0);assertTrue(target.copyMembershipFrom(source,0));source.close();
            byte[] exact=DiametralExtensionAudit.bfs(7);long[] ranks=new long[exact.length];for(int i=0;i<ranks.length;i++)ranks[i]=i;
            for(int q=0;q<=4;q++)try(GpuStreamingCounter.Batch batch=target.new Batch(7,q+1,q+1,ranks.length)) {
                byte[] decisions=batch.classifyRanks(ranks,ranks.length);
                for(int i=0;i<ranks.length;i++)assertEquals(exact[i]<=q?0:1,decisions[i]);
            }
        }
    }
    @Test void aSameDegreeSupportLayerReusesTheCompletedDiametralBitmap() throws Exception {
        Path source=root.resolve("source"),output=root.resolve("output");ExtensionRun.run(ExtensionRunTest.config(source,7,false));
        GpuSettingsTest.configured(Map.of("extension.gpuDevices","0","extension.gpuSlots","2",
            "extension.gpuMembershipDegree","6","extension.gpuPatternMaxDegree","5"),()->{
            try(CanonicalLayerStore parents=new LayerCatalog(source).open(5,2,3);
                AsyncCanonicalWriter writer=new AsyncCanonicalWriter(output,6,3,6,64,2,true);
                ExtensionResolver resolver=new ExtensionResolver(6,3,writer,new SmallTables(0),true,128,2,0.25,source);
                GpuCandidatePool pool=new GpuCandidatePool(resolver,parents,6,3,8);
                CanonicalLayerStore.BatchCursor cursor=parents.cursor(2)) {
                assertEquals(6,resolver.device().membershipDegree());
                ExtensionRun.Stats stats=new ExtensionRun.Stats();byte[] rows;
                while((rows=cursor.nextBatch(8))!=null)pool.process(rows,stats,writer,resolver);
                pool.close();resolver.finish();assertEquals(0,resolver.cpuQueries.get());assertEquals(380,writer.finish().weighted[3]);
            }
        });
        ExtensionRunTest.verify(output,6);
    }
    @Test void incompleteMissingOrUnaffordableSetsNeverBecomeMembershipProofs() throws Exception {
        Files.createDirectories(root.resolve("n-06/.distance-4-test.partial"));
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,64)) {
            assertNull(GpuMembershipTable.load(gpu,root,6,0));
            ExtensionRun.run(ExtensionRunTest.config(root,6,false));
            assertNull(GpuMembershipTable.load(gpu,root,6,Long.MAX_VALUE));assertNull(gpu.membership);
        }
    }
    @Test void sourceCoverageAndBitmapCardinalityAreCheckedBeforePublication() throws Exception {
        ExtensionRun.run(ExtensionRunTest.config(root,6,false));Path source=new LayerCatalog(root).find(6,4);
        LayerManifest manifest=LayerManifest.read(source);
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,64)) {
            manifest.canonical[4]++;manifest.write(source);
            assertThrows(IOException.class,()->GpuMembershipTable.load(gpu,root,6,0));assertNull(gpu.membership);
            manifest.canonical[4]--;manifest.weighted[4]++;manifest.write(source);
            assertThrows(IOException.class,()->GpuMembershipTable.load(gpu,root,6,0));assertNull(gpu.membership);
        }
    }
}
