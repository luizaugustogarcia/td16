package br.unb.cic.tdp.experiments;

import org.rocksdb.*;

/** Workload settings for the temporary rank construction database. */
final class RocksTuning {
    static int writeThreads(){return positive("rocks.writeThreads",Math.max(2,Runtime.getRuntime().availableProcessors()));}
    static int backgroundJobs(){return positive("rocks.backgroundJobs",Math.max(2,Runtime.getRuntime().availableProcessors()));}
    static int flushThreads(){return positive("rocks.flushThreads",Math.max(1,Runtime.getRuntime().availableProcessors()));}
    static int subcompactions(){return positive("rocks.subcompactions",backgroundJobs());}
    static int maxMemtables(){return positive("rocks.maxMemtables",6);}
    static int maxOpenFiles(){int value=Integer.getInteger("rocks.maxOpenFiles",1024);if(value<1&&value!=-1)throw new IllegalArgumentException("rocks.maxOpenFiles must be positive or -1");return value;}
    static long memtableBytes(){return mib("rocks.memtableMiB",256);}
    static long sstBytes(){return mib("rocks.sstMiB",256);}
    static long cacheBytes(){return mib("rocks.cacheMiB",512);}
    private static int positive(String name,int fallback){int value=Integer.getInteger(name,fallback);if(value<1)throw new IllegalArgumentException(name+" must be positive");return value;}
    private static long mib(String name,int fallback){return Math.multiplyExact((long)positive(name,fallback),1L<<20);}
    // Construction only serves range scans. Bloom filters add CPU, disk and cache
    // overhead to every flushed run without helping the final merge.
    static BlockBasedTableConfig table(Cache cache){return new BlockBasedTableConfig()
        .setBlockCache(cache).setBlockSize(32<<10).setIndexType(IndexType.kTwoLevelIndexSearch)
        .setPartitionFilters(true).setMetadataBlockSize(4<<10)
        .setCacheIndexAndFilterBlocks(true).setPinTopLevelIndexAndFilter(true);}
    static Options writerOptions(Cache cache){
        // The flush pool is separate from the low-priority compaction pool.
        Env.getDefault().incBackgroundThreadsIfNeeded(flushThreads(),Priority.HIGH);
        return new Options().setCreateIfMissing(true)
        .setIncreaseParallelism(backgroundJobs()).setMaxBackgroundJobs(backgroundJobs()).setMaxBackgroundFlushes(flushThreads()).setMaxSubcompactions(subcompactions())
        .setWriteBufferSize(memtableBytes()).setMaxWriteBufferNumber(maxMemtables()).setMinWriteBufferNumberToMerge(1)
        // Keep flushed runs immutable until the parallel archive range scans
        // merge them. Repeated leveled compaction serializes large constructions.
        .setDisableAutoCompactions(true)
        .setSoftPendingCompactionBytesLimit(0).setHardPendingCompactionBytesLimit(0)
        .setLevelZeroFileNumCompactionTrigger(-1).setLevelZeroSlowdownWritesTrigger(-1).setLevelZeroStopWritesTrigger(Integer.MAX_VALUE)
        .setDelayedWriteRate(1L<<30).setTargetFileSizeBase(sstBytes())
        .setAllowConcurrentMemtableWrite(true).setEnablePipelinedWrite(true)
        .setCompressionType(CompressionType.LZ4_COMPRESSION).setMaxOpenFiles(maxOpenFiles()).setBytesPerSync(4L<<20)
        .setTableFormatConfig(table(cache));}
    static Options archiveReadOptions(Cache cache){return new Options().setMaxOpenFiles(maxOpenFiles()).setTableFormatConfig(table(cache));}
    static long archiveScanReadahead(RocksDB database,int workers){
        // Each range iterator merges all overlapping runs. Budget two native
        // prefetch buffers per run instead of multiplying 4 MiB by that fan-in.
        long runs=Math.max(1,database.getLiveFilesMetaData().size()),streams=2L*workers*runs;
        long bytes=Math.max(4096,Math.min(4L<<20,cacheBytes()/4/streams)&~4095L);
        System.out.printf("COMPACT_READ runs=%d workers=%d readahead_bytes_per_run=%d shared_index_cache_bytes=%d%n",runs,workers,bytes,cacheBytes());
        return bytes;
    }
    static void log(){System.out.printf("ROCKSDB phase=construction write_threads=%d background_jobs=%d flush_threads=%d subcompactions=%d memtable_MiB=%d max_memtables=%d SST_target_MiB=%d cache_MiB=%d max_open_files=%d WAL=false compression=LZ4 auto_compaction=false pending_compaction_limits=disabled L0_write_limits=disabled delayed_write_MiB_per_second=1024 final_merge=parallel-rank-ranges index=two-level Bloom_filters=false metadata_block_KiB=4%n",
        writeThreads(),backgroundJobs(),flushThreads(),subcompactions(),memtableBytes()>>20,maxMemtables(),sstBytes()>>20,cacheBytes()>>20,maxOpenFiles());}
}
