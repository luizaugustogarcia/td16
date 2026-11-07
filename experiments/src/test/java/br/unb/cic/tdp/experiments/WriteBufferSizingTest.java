package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WriteBufferSizingTest {
    @TempDir Path root;
    @Test void rtx5090ProfileAccountsForAllWritersAndAggregateCopiesWithinItsStagingBudget() {
        for(int degree=1;degree<=18;degree++) {
            WriteAggregation aggregation=WriteAggregation.of(degree,4L<<30,2000,8L<<20);
            WriteBufferSizing plan=WriteBufferSizing.calculate(degree,65536,8,128,176,32L<<30,aggregation);
            assertEquals(256,plan.inFlightBatches());assertTrue(plan.pendingBatches()>256);
            assertTrue(plan.estimatedMaximumBytes()<=(32L<<30));
        }
    }
    @Test void epycRtx5090ProfileAccountsFor256WritersAndLargerNativeBatches() {
        for(int degree=1;degree<=18;degree++) {
            WriteAggregation aggregation=WriteAggregation.of(degree,8L<<30,1000,16L<<20);
            WriteBufferSizing plan=WriteBufferSizing.calculate(degree,65536,8,256,304,48L<<30,aggregation);
            assertEquals(512,plan.inFlightBatches());assertTrue(plan.pendingBatches()>256);
            assertTrue(plan.estimatedMaximumBytes()<=(48L<<30));
        }
    }
    @Test void serverMemoryBudgetSupportsMuchMoreThan256BatchesWithoutAnUnboundedRecyclePool() {
        long budget=8L<<30;
        WriteBufferSizing plan=WriteBufferSizing.calculate(14,32768,128,64,128,budget);
        assertEquals(229376,plan.batchBytes());assertTrue(plan.pendingBatches()>20000);
        assertEquals(128,plan.inFlightBatches());assertTrue(plan.recycledBatches()<=256);
        assertTrue(plan.estimatedMaximumBytes()<=budget);
        WriteBufferSizing wider=WriteBufferSizing.calculate(18,32768,128,64,128,budget);
        assertTrue(wider.pendingBatches()<plan.pendingBatches(),"Wider records must reduce capacity under the same byte budget");
    }
    @Test void zeroByteBudgetPreservesExplicitBatchLimits() {
        WriteBufferSizing plan=WriteBufferSizing.calculate(14,32768,128,64,0,0);
        assertEquals(128,plan.pendingBatches());assertEquals(128,plan.inFlightBatches());assertEquals(258,plan.recycledBatches());
    }
    @Test void gigabyteSharedAggregatesReserveParallelEncodersAndNativeCopiesWithinTheEightGiBBudget() {
        long budget=8L<<30;
        WriteAggregation aggregation=WriteAggregation.of(15,1L<<30,5000,8L<<20);
        WriteBufferSizing plan=WriteBufferSizing.calculate(15,32768,128,64,128,budget,aggregation);
        assertTrue(aggregation.targetBytes()>=(1L<<30)-12);
        assertTrue(aggregation.nativeEncodedBytes()<=(8L<<20));
        assertTrue(plan.pendingBatches()>10000);
        assertEquals(128,plan.inFlightBatches());assertTrue(plan.estimatedMaximumBytes()<=budget);
        assertThrows(IllegalArgumentException.class,()->WriteBufferSizing.calculate(15,32768,128,64,128,2L<<30,aggregation));
        assertThrows(IllegalArgumentException.class,()->WriteBufferSizing.calculate(15,32768,128,64,128,0,aggregation));
    }
    @Test void aggregationRejectsInvalidSizesAndIntervalsBeforeStartingAWritingThread() {
        assertThrows(IllegalArgumentException.class,()->WriteAggregation.of(15,1L<<30,0,8L<<20));
        assertThrows(IllegalArgumentException.class,()->WriteAggregation.of(15,1L<<30,5000,16));
        assertThrows(IllegalArgumentException.class,()->WriteAggregation.of(15,1L<<30,5000,1L<<31));
        assertThrows(IllegalArgumentException.class,()->WriteAggregation.of(15,1,5000,8L<<20));
    }
    @Test void aBudgetTooSmallForStagingIsRejectedRatherThanSilentlyExceeded() {
        assertThrows(IllegalArgumentException.class,()->WriteBufferSizing.calculate(14,32768,128,64,128,1L<<20));
        assertThrows(ArithmeticException.class,()->WriteBufferSizing.calculate(18,Integer.MAX_VALUE,1,1,1,8L<<30));
    }
    @Test void byteBudgetOverridesTinyBatchLimitsAndPreservesCompleteExactSets() throws Exception {
        configured(()->{
            WriteBufferSizing plan=WriteBufferSizing.system(8,64,2);assertTrue(plan.pendingBatches()>1000);
            ExtensionRun.run(ExtensionRunTest.config(root,8,false));ExtensionRunTest.verify(root,8);
        });
    }
    @Test void oversizedExternalArraysCannotBypassTheBatchMemoryBound() throws Exception {
        configured(()->{
            try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,3,2,3,4,1,true)) {
                assertThrows(IllegalArgumentException.class,()->writer.submit(new byte[writer.rowBytes*writer.batchRecords+1],1));
            }
        });
    }
    @Test void backgroundFailuresStillPreventPublicationWithALargeQueue() throws Exception {
        configured(()->{
            AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,3,2,3,4,1,true);
            try {
                byte[] malformed=new byte[writer.rowBytes];malformed[0]=99;malformed[malformed.length-1]=1;
                assertThrows(IOException.class,()->{writer.submit(malformed,1);writer.finish();});
                assertFalse(Files.exists(writer.target.resolve("manifest.properties")));
            }finally{assertThrows(IOException.class,writer::close);}
        });
    }
    private void configured(GpuSettingsTest.Checked action) throws Exception {
        GpuSettingsTest.configured(Map.of("extension.writeBufferMiB","1","rocks.writeThreads","2","count.cpuThreads","3","count.gpu","false"),action);
    }
}
