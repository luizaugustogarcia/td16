package br.unb.cic.tdp.experiments;

import br.unb.cic.tdp.base.GpuDeviceLayout;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuSettingsTest {
    static void configured(Map<String,String> values,Checked body) throws Exception {
        Set<String> keys=new LinkedHashSet<>(List.of("extension.gpuDevices","extension.gpuSlots","extension.gpuSlotMiB","tdp.gpuDevices","tdp.gpuSlots","tdp.gpuBudgetMB"));
        keys.addAll(values.keySet());
        Map<String,String> previous=new HashMap<>();for(String key:keys){previous.put(key,System.getProperty(key));System.clearProperty(key);}
        try{values.forEach(System::setProperty);body.run();}
        finally{for(String key:keys){String value=previous.get(key);if(value==null)System.clearProperty(key);else System.setProperty(key,value);}}
    }
    interface Checked {void run() throws Exception;}
    @Test void usesTheEmittersDeviceAndSlotsPerDeviceConventions() throws Exception {
        configured(Map.of("tdp.gpuDevices","2,5","tdp.gpuSlots","3","tdp.gpuBudgetMB","8"),()->{
            GpuSettings settings=GpuSettings.system();assertEquals(6,settings.layout().totalSlots());
            assertEquals(2,settings.layout().deviceForSlot(0));assertEquals(5,settings.layout().deviceForSlot(1));
            assertEquals(8L<<20,settings.slotMemoryBytes());
        });
    }
    @Test void experimentOptionsOverrideEmitterAliases() throws Exception {
        configured(Map.of("tdp.gpuDevices","1","tdp.gpuSlots","7","extension.gpuDevices","0","extension.gpuSlots","4","extension.gpuSlotMiB","2"),()->{
            GpuSettings settings=GpuSettings.system();assertArrayEquals(new int[]{0},settings.layout().devices());
            assertEquals(4,settings.layout().totalSlots());assertEquals(2L<<20,settings.slotMemoryBytes());
        });
    }
    @Test void memoryIsDividedAcrossSlotsOnEachDevice() {
        GpuSettings settings=new GpuSettings(new GpuDeviceLayout(new int[]{0,1},4),0);
        assertEquals(1600,settings.slotBudget(10000,2000,0.8));
        assertEquals(512,new GpuSettings(settings.layout(),512).slotBudget(10000,2000,0.8));
        assertThrows(IllegalArgumentException.class,()->settings.slotBudget(1000,2000,0.8));
        assertThrows(IllegalArgumentException.class,()->settings.validateAvailable(1));
    }
    @Test void rejectsInvalidSlotsDevicesAndMemory() throws Exception {
        configured(Map.of("extension.gpuDevices","0,0"),()->assertThrows(IllegalArgumentException.class,GpuSettings::system));
        configured(Map.of("extension.gpuSlots","0"),()->assertThrows(IllegalArgumentException.class,GpuSettings::system));
        configured(Map.of("extension.gpuSlotMiB","-1"),()->assertThrows(IllegalArgumentException.class,GpuSettings::system));
        configured(Map.of("extension.gpuSlotMiB",Long.toString(Long.MAX_VALUE)),()->assertThrows(IllegalArgumentException.class,GpuSettings::system));
    }
    @Test void manyGpuStreamsRespectMemoryBudgetsWithoutAnArbitrarySlotCeiling() throws Exception {
        configured(Map.of("extension.gpuDevices","0","extension.gpuSlots","128"),()->{
            GpuSettings settings=GpuSettings.system();assertEquals(128,settings.layout().totalSlots());
            assertEquals((long)((32L<<30)*0.95)/128,settings.slotBudget(33L<<30,1L<<30,0.95));
        });
        configured(Map.of("extension.gpuGenerationStreams","64"),()->assertEquals(64,GpuSettings.generationStreams()));
        configured(Map.of("extension.gpuGenerationStreams","0"),()->assertThrows(IllegalArgumentException.class,GpuSettings::generationStreams));
    }
    @Test void searchAndGeneratorBudgetCalculationsIncludeDeviceBuffers() {
        long one=GpuStreamingCounter.bytesPerJob(18,11),fixed=GpuStreamingCounter.fixedBatchBytes(18);
        assertEquals(11,GpuStreamingCounter.capacityForBudget(18,11,16384,fixed+11*one));
        assertThrows(IllegalArgumentException.class,()->GpuStreamingCounter.capacityForBudget(18,11,16384,fixed+one-1));
        assertTrue(GpuCandidateGenerator.deviceBytes(18,4096,false)>GpuCandidateGenerator.deviceBytes(18,4096,true));
    }
}
