package br.unb.cic.tdp.experiments;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.rocksdb.*;

/** Manual bounded storage benchmark; synthetic sorted ranks measure encoding, not permutation distances. */
public final class CompactArchiveBenchmark {
    public static void main(String[] args) throws Exception {
        if(args.length<2)throw new IllegalArgumentException("prepare DIR [records] or build DIR [threads] [repeats]");
        RocksDB.loadLibrary();Path directory=Path.of(args[1]);
        if(args[0].equals("prepare")) {
            int records=args.length>2?Integer.parseInt(args[2]):8_000_000;
            if(records<1)throw new IllegalArgumentException("Positive record count required");
            Files.createDirectory(directory);Files.writeString(directory.resolve("benchmark-source"),"synthetic sorted rank records; not a distance computation"+System.lineSeparator());
            try(Options options=new Options().setCreateIfMissing(true).setWriteBufferSize(32L<<20).setMaxWriteBufferNumber(2).setDisableAutoCompactions(true).setCompressionType(CompressionType.LZ4_COMPRESSION);
                RocksDB database=RocksDB.open(options,directory.resolve("db").toString());WriteOptions writes=new WriteOptions().setDisableWAL(true)) {
                byte[] key=new byte[PermutationRank.bytes(14)],value={8,1};long universe=SmallTables.FACTORIAL[14];
                for(int first=0;first<records;first+=4096)try(WriteBatch batch=new WriteBatch()) {
                    for(int i=first;i<Math.min(records,first+4096);i++){long rank=universe/records*i+universe%records*i/records;PermutationRank.encode(rank,14,key,0);batch.put(key,value);}
                    database.write(writes,batch);
                }
                try(FlushOptions flush=new FlushOptions().setWaitForFlush(true)){database.flush(flush);}
            }
            long[] canonical=new long[12],weighted=new long[12];canonical[8]=weighted[8]=records;
            new LayerManifest(14,8,14,canonical,weighted,true).write(directory);
            System.out.printf("COMPACT_BENCHMARK_SOURCE records=%d directory=%s synthetic=true%n",records,directory);return;
        }
        if(!args[0].equals("build")||!Files.isRegularFile(directory.resolve("benchmark-source")))throw new IllegalArgumentException("Only a prepared benchmark directory may be rebuilt");
        int threads=args.length>2?Integer.parseInt(args[2]):1,repeats=args.length>3?Integer.parseInt(args[3]):1;
        System.setProperty("extension.compactBuildThreads",Integer.toString(threads));System.setProperty("extension.compactParallelThreshold","0");
        LayerManifest manifest=LayerManifest.read(directory);String expected=null;
        for(int repeat=0;repeat<repeats;repeat++) {
            long began=System.nanoTime();CompactRankArchive.build(directory,manifest);double seconds=(System.nanoTime()-began)/1e9;
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];
            try(var input=Files.newInputStream(directory.resolve(CompactRankArchive.FILE))){int bytes;while((bytes=input.read(buffer))>=0)digest.update(buffer,0,bytes);}
            String hash=HexFormat.of().formatHex(digest.digest());
            if(expected!=null&&!expected.equals(hash))throw new AssertionError("Repeated archive bytes changed");expected=hash;
            System.out.printf(Locale.ROOT,"COMPACT_BENCHMARK records=%d threads=%d repeat=%d seconds=%.3f full_archive_sha256=%s%n",manifest.canonicalSize(),threads,repeat,seconds,hash);
        }
    }
}
