package br.unb.cic.tdp.experiments;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.rocksdb.*;
import static org.junit.jupiter.api.Assertions.*;

class RocksTuningTest {
    @Test void serverSettingsReachNativeOptionsWithoutSmallMachineCaps() throws Exception {
        RocksDB.loadLibrary();
        GpuSettingsTest.configured(Map.ofEntries(
            Map.entry("rocks.writeThreads","128"),Map.entry("rocks.backgroundJobs","64"),
            Map.entry("rocks.flushThreads","16"),Map.entry("rocks.subcompactions","64"),
            Map.entry("rocks.memtableMiB","1024"),Map.entry("rocks.maxMemtables","12"),
            Map.entry("rocks.sstMiB","1024"),Map.entry("rocks.maxOpenFiles","-1")),()->{
            try(BloomFilter filter=new BloomFilter(10,false);Cache cache=new LRUCache(1L<<20);
                Options options=RocksTuning.writerOptions(filter,cache)) {
                assertEquals(128,RocksTuning.writeThreads());
                assertEquals(64,options.maxBackgroundJobs());assertEquals(16,options.maxBackgroundFlushes());
                assertEquals(64,options.maxSubcompactions());assertEquals(12,options.maxWriteBufferNumber());
                assertEquals(1L<<30,options.writeBufferSize());assertEquals(1L<<30,options.targetFileSizeBase());
                assertEquals(-1,options.maxOpenFiles());
            }
        });
    }
    @Test void invalidResourceOverridesAreRejected() throws Exception {
        GpuSettingsTest.configured(Map.of("rocks.subcompactions","0"),()->assertThrows(IllegalArgumentException.class,RocksTuning::subcompactions));
        GpuSettingsTest.configured(Map.of("rocks.maxMemtables","0"),()->assertThrows(IllegalArgumentException.class,RocksTuning::maxMemtables));
        for(String invalid:new String[]{"0","-2"})GpuSettingsTest.configured(Map.of("rocks.maxOpenFiles",invalid),()->assertThrows(IllegalArgumentException.class,RocksTuning::maxOpenFiles));
    }
}
