package br.unb.cic.tdp.experiments;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="gpuTests",matches="true")
class GpuPatternTableTest {
    @TempDir Path root;

    @Test void patternsUseAvailableStoresAndAvoidARedundantMembershipAllocation() throws Exception {
        ExtensionRun.run(ExtensionRunTest.config(root,6,false));
        GpuSettingsTest.configured(Map.of("extension.gpuPatternMaxDegree","auto","extension.gpuMembershipDegree","auto"),()->{
            try(GpuStreamingCounter source=new GpuStreamingCounter(null,1);
                GpuStreamingCounter target=new GpuStreamingCounter(null,1)) {
                source.membership=GpuMembershipTable.load(source,root,6,0);assertNotNull(source.membership);
                target.loadPersistedTables(root,18,new GpuCacheBudget(Long.MAX_VALUE,0.90));assertEquals(0,target.tableDegree);
                Files.move(root.resolve("n-04"),root.resolve("hidden-n-04"));
                target.loadPersistedTables(root,18);assertEquals(6,target.tableDegree);
                assertEquals(0,target.parentLookupKind(4,RetentionPlan.upper(4)),"Absent lower stores must not become exact lookup proofs");
                assertFalse(target.copyMembershipFrom(source,0));target.loadMembershipTable(root,14,0);assertNull(target.membership);
                assertEquals(1,target.parentLookupKind(6,RetentionPlan.upper(6)));
                byte[] exact=DiametralExtensionAudit.bfs(6);long[] ranks=new long[exact.length];for(int i=0;i<ranks.length;i++)ranks[i]=i;
                for(int budget=0;budget<=4;budget++)try(GpuStreamingCounter.Batch batch=target.new Batch(6,budget+1,budget+1,ranks.length)) {
                    byte[] actual=batch.classifyRanks(ranks,ranks.length);
                    for(int i=0;i<ranks.length;i++)assertEquals(exact[i]<=budget?0:1,actual[i],"rank="+i+" budget="+budget);
                }
            }
        });
    }
    @Test void automaticMembershipUsesTheNearestCompletedDegreeWithoutAFixedGpuProfile() throws Exception {
        ExtensionRun.run(ExtensionRunTest.config(root,7,false));
        GpuSettingsTest.configured(Map.of("extension.gpuPatternMaxDegree","5","extension.gpuMembershipDegree","auto"),()->{
            try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,64)) {
                gpu.loadPersistedTables(root,18);assertEquals(5,gpu.tableDegree);
                assertEquals(GpuMembershipTable.bytesForDegree(7),gpu.membershipReserve(root,6,8,new GpuCacheBudget(0,0.90)));
                gpu.loadMembershipTable(root,8,new GpuCacheBudget(Long.MAX_VALUE,0.90));assertNull(gpu.membership);
                gpu.loadMembershipTable(root,8,0);assertEquals(7,gpu.membershipDegree());
                assertEquals(2,gpu.parentLookupKind(7,4));
                byte[] exact=DiametralExtensionAudit.bfs(7);long[] ranks=new long[exact.length];for(int i=0;i<ranks.length;i++)ranks[i]=i;
                for(int budget=0;budget<=4;budget++)try(GpuStreamingCounter.Batch batch=gpu.new Batch(7,budget+1,budget+1,ranks.length)) {
                    byte[] actual=batch.classifyRanks(ranks,ranks.length);
                    for(int i=0;i<ranks.length;i++)assertEquals(exact[i]<=budget?0:1,actual[i]);
                }
            }
        });
    }
}
