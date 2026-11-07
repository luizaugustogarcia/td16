package br.unb.cic.tdp.experiments;

import org.rocksdb.*;

/** Workload settings shared by the asynchronous writer and immutable parent stores. */
final class RocksTuning {
    static int writeThreads(){return positive("rocks.writeThreads",Math.max(2,Runtime.getRuntime().availableProcessors()));}
    static int backgroundJobs(){return positive("rocks.backgroundJobs",Math.max(2,Math.min(12,Runtime.getRuntime().availableProcessors()/2)));}
    static int flushThreads(){return positive("rocks.flushThreads",Math.min(4,backgroundJobs()));}
    static long memtableBytes(){return mib("rocks.memtableMiB",256);}
    static long sstBytes(){return mib("rocks.sstMiB",256);}
    static long cacheBytes(){return mib("rocks.cacheMiB",512);}
    static int multiGetKeys(){int value=Integer.getInteger("rocks.multiGetKeys",262144);if(value<1||value>262144)throw new IllegalArgumentException("rocks.multiGetKeys must be 1..262144");return value;}
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
        .setIncreaseParallelism(backgroundJobs()).setMaxBackgroundJobs(backgroundJobs()).setMaxBackgroundFlushes(flushThreads()).setMaxSubcompactions(Math.min(4,backgroundJobs()))
        .setWriteBufferSize(memtableBytes()).setMaxWriteBufferNumber(6).setMinWriteBufferNumberToMerge(1)
        .setTargetFileSizeBase(sstBytes()).setMaxBytesForLevelBase(8*sstBytes()).setLevelCompactionDynamicLevelBytes(true)
        .setLevelZeroFileNumCompactionTrigger(8).setLevelZeroSlowdownWritesTrigger(32).setLevelZeroStopWritesTrigger(64)
        .setAllowConcurrentMemtableWrite(true).setEnablePipelinedWrite(true)
        .setCompressionType(CompressionType.LZ4_COMPRESSION).setMaxOpenFiles(1024).setBytesPerSync(4L<<20)
        .setCompactionReadaheadSize(4L<<20).setTableFormatConfig(table(filter,cache));}
    static void log(){System.out.printf("ROCKSDB write_threads=%d background_jobs=%d flush_threads=%d memtable_MiB=%d max_memtables=6 SST_target_MiB=%d cache_MiB=%d WAL=false compression=LZ4 index=two-level partitioned_filters=true metadata_block_KiB=4 max_multiget_keys=%d%n",
        writeThreads(),backgroundJobs(),flushThreads(),memtableBytes()>>20,sstBytes()>>20,cacheBytes()>>20,multiGetKeys());}
}
