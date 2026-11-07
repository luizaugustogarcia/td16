package br.unb.cic.tdp.experiments;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuPatternLayoutTest {
    @Test void degreeFourteenOffsetsAndAllocationStayAboveThe32BitBoundary() {
        GpuPatternLayout layout=new GpuPatternLayout(14);
        assertEquals(6_749_977_114L,layout.offsets[14]);
        assertEquals(46_964_134_160L,layout.bytes);
        long last=layout.offsets[14]+SmallTables.FACTORIAL[14]-1;
        assertEquals(93_928_268_313L,last);
        assertTrue((last>>1)<layout.bytes);
        assertEquals(0,layout.bytes%Integer.BYTES);
        assertEquals(304,GpuPatternLayout.METADATA_BYTES);
    }
    @Test void smallBfsAndGpuLayoutsAddressTheSameEntries() {
        SmallTables small=new SmallTables(7);GpuPatternLayout layout=new GpuPatternLayout(7);
        for(int n=0;n<=7;n++)assertEquals(small.offsets[n],layout.offsets[n]);
        assertEquals(((small.distances.length+7L)/8)*4,layout.bytes);
        assertEquals(4,new GpuPatternLayout(0).bytes);
        assertEquals(3_374_988_560L,new GpuPatternLayout(13).bytes);
    }
    @Test void automaticSelectionCoversEverySupportedDegreeAndExplicitCapsRemainAvailable() throws Exception {
        GpuSettingsTest.configured(Map.of("extension.gpuPatternMaxDegree","auto"),()->assertEquals(18,GpuPatternLayout.configuredDegree()));
        GpuSettingsTest.configured(Map.of("extension.gpuPatternMaxDegree","13"),()->assertEquals(13,GpuPatternLayout.configuredDegree()));
        GpuSettingsTest.configured(Map.of("extension.gpuPatternMaxDegree","14"),()->assertEquals(14,GpuPatternLayout.configuredDegree()));
        for(int degree:new int[]{-1,19}) {
            assertThrows(IllegalArgumentException.class,()->new GpuPatternLayout(degree));
            GpuSettingsTest.configured(Map.of("extension.gpuPatternMaxDegree",Integer.toString(degree)),()->
                assertThrows(IllegalArgumentException.class,GpuPatternLayout::configuredDegree));
        }
    }
    @Test void cacheCapacityAdaptsToEachGpuAndProtectsTheNextOperationsWorkspaces() {
        long gib=1L<<30;GpuCacheBudget budget=new GpuCacheBudget(10*gib,0.90);
        long small=budget.available(24*gib,0),large=budget.available(141*gib,0);
        assertTrue(new GpuPatternLayout(13).bytes<=small);
        assertTrue(new GpuPatternLayout(14).bytes>small);
        assertTrue(new GpuPatternLayout(14).bytes<=large);
        assertTrue(new GpuPatternLayout(15).bytes>large);
        assertEquals(0,budget.available(gib,0));
        assertEquals(budget.available(20*gib,4*gib),budget.available(24*gib,0));
        assertFalse(budget.fits(1,gib,0));
        assertThrows(IllegalArgumentException.class,()->new GpuCacheBudget(-1,0.90));
    }
    @Test void automaticDenseCachesScaleWithVramAndExplicitOverridesRemainRespected() throws Exception {
        long gib=1L<<30;
        GpuSettingsTest.configured(Map.of("extension.compactSets","true","extension.gpuPatternMaxDegree","auto"),()->{
            assertEquals(12,GpuPatternLayout.denseLimit(8*gib));
            assertEquals(13,GpuPatternLayout.denseLimit(24*gib));
            assertEquals(14,GpuPatternLayout.denseLimit(141*gib));
        });
        GpuSettingsTest.configured(Map.of("extension.gpuPatternMaxDegree","14"),()->assertEquals(14,GpuPatternLayout.denseLimit(8*gib)));
        GpuSettingsTest.configured(Map.of("extension.gpuDenseMaxDegree","13"),()->assertEquals(13,GpuPatternLayout.denseLimit(8*gib)));
        GpuSettingsTest.configured(Map.of("extension.compactSets","false"),()->assertEquals(18,GpuPatternLayout.denseLimit(8*gib)));
    }
    @Test void aUsefulBitmapCanDisplaceASmallerDenseCacheWhenBothWouldNotFit() {
        long gib=1L<<30;GpuCacheBudget budget=new GpuCacheBudget(gib,0.90);
        long free=14*gib,bitmap=GpuMembershipTable.bytesForDegree(14);
        assertTrue(budget.fits(new GpuPatternLayout(13).bytes,free,0));
        assertTrue(budget.fits(bitmap,free,0));
        assertFalse(budget.fits(bitmap,free-new GpuPatternLayout(13).bytes,0));
        GpuCacheBudget patterns=budget.reserve(bitmap);
        assertFalse(patterns.fits(new GpuPatternLayout(13).bytes,free,0));
        assertTrue(patterns.fits(new GpuPatternLayout(12).bytes,free,0));
        assertTrue(budget.fits(bitmap,free-new GpuPatternLayout(12).bytes,0));
    }
}
