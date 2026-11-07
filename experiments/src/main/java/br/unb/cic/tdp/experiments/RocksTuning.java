package br.unb.cic.tdp.experiments;

import org.rocksdb.*;

/** Workload settings for the temporary rank construction database. */
final class RocksTuning {
    static int writeThreads(){return positive("rocks.writeThreads",Math.max(2,Runtime.getRuntime().availableProcessors()));}
    static int backgroundJobs(){return positive("rocks.backgroundJobs",Math.max(2,Runtime.getRuntime().availableProcessors()/2));}
    static int flushThreads(){return positive("rocks.flushThreads",Math.max(1,backgroundJobs()/4));}
    static int subcompactions(){return positive("rocks.subcompactions",backgroundJobs());}
    static int maxMemtables(){return positive("rocks.maxMemtables",6);}
    static int maxOpenFiles(){int value=Integer.getInteger("rocks.maxOpenFiles",1024);if(value<1&&value!=-1)throw new IllegalArgumentException("rocks.maxOpenFiles must be positive or -1");return value;}
    static long memtableBytes(){return mib("rocks.memtableMiB",256);}
    static long sstBytes(){return mib("rocks.sstMiB",256);}
    static long cacheBytes(){return mib("rocks.cacheMiB",512);}
    private static int positive(String name,int fallback){int value=Integer.getInteger(name,fallback);if(value<1)throw new IllegalArgumentException(name+" must be positive");return value;}
    private static long mib(String name,int fallback){return Math.multiplyExact((long)positive(name,fallback),1L<<20);}
    static BlockBasedTableConfig table(BloomFilter filter,Cache cache){return new BlockBasedTableConfig().setFilterPolicy(filter)
        .setBlockCache(cache).setBlockSize(32<<10).setIndexType(IndexType.kTwoLevelIndexSearch)
        .setPartitionFilters(true).setMetadataBlockSize(4<<10)
        .setCacheIndexAndFilterBlocks(true).setPinTopLevelIndexAndFilter(true);}
    static Options writerOptions(BloomFilter filter,Cache cache){
        // The flush pool is separate from the low-priority compaction pool.
        Env.getDefault().incBackgroundThreadsIfNeeded(flushThreads(),Priority.HIGH);
        return new Options().setCreateIfMissing(true)
        .setIncreaseParallelism(backgroundJobs()).setMaxBackgroundJobs(backgroundJobs()).setMaxBackgroundFlushes(flushThreads()).setMaxSubcompactions(subcompactions())
        .setWriteBufferSize(memtableBytes()).setMaxWriteBufferNumber(maxMemtables()).setMinWriteBufferNumberToMerge(1)
        .setTargetFileSizeBase(sstBytes()).setMaxBytesForLevelBase(8*sstBytes()).setLevelCompactionDynamicLevelBytes(true)
        .setLevelZeroFileNumCompactionTrigger(8).setLevelZeroSlowdownWritesTrigger(32).setLevelZeroStopWritesTrigger(64)
        .setAllowConcurrentMemtableWrite(true).setEnablePipelinedWrite(true)
        .setCompressionType(CompressionType.LZ4_COMPRESSION).setMaxOpenFiles(maxOpenFiles()).setBytesPerSync(4L<<20)
        .setCompactionReadaheadSize(4L<<20).setTableFormatConfig(table(filter,cache));}
    static void log(){System.out.printf("ROCKSDB phase=construction write_threads=%d background_jobs=%d flush_threads=%d subcompactions=%d memtable_MiB=%d max_memtables=%d SST_target_MiB=%d cache_MiB=%d max_open_files=%d WAL=false compression=LZ4 index=two-level partitioned_filters=true metadata_block_KiB=4%n",
        writeThreads(),backgroundJobs(),flushThreads(),subcompactions(),memtableBytes()>>20,maxMemtables(),sstBytes()>>20,cacheBytes()>>20,maxOpenFiles());}
}
