package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksIterator;
import static org.junit.jupiter.api.Assertions.*;

class WriteAggregationTest {
    @TempDir Path root;

    private AsyncCanonicalWriter writer(int degree,long targetBytes,long millis,long nativeBytes) throws IOException {
        WriteAggregation aggregation=WriteAggregation.of(degree,targetBytes,millis,nativeBytes);
        WriteBufferSizing sizing=WriteBufferSizing.calculate(degree,4,1,3,8,1L<<20,aggregation);
        return new AsyncCanonicalWriter(root,degree,0,degree,4,1,false,false,sizing);
    }

    @Test void concurrentShortLivedProducersCoalesceAcrossPacketsAndPreserveEveryPackedKeyAndDistance() throws Exception {
        int n=7;byte[] exact=DiametralExtensionAudit.bfs(n),word=new byte[n],omega=new byte[n+1];
        List<byte[]> keys=new ArrayList<>();List<Integer> distances=new ArrayList<>(),weights=new ArrayList<>();
        long[] expectedWeighted=new long[12];
        for(int rank=0;rank<exact.length;rank++) {
            SmallTables.unrank(rank,n,word,0);int weight=ToricOrbit.weight(word,n);if(weight==0)continue;
            ToricOrbit.omegaFromWord(word,n,omega);keys.add(PackedPermutation5.encode(omega,n+1));
            distances.add((int)exact[rank]);weights.add(weight);expectedWeighted[exact[rank]]+=weight;
        }
        Path target;
        try(AsyncCanonicalWriter writer=writer(n,1024,60000,256)) {
            target=writer.target;
            try(ExecutorService producers=Executors.newFixedThreadPool(8)) {
                List<Future<?>> done=new ArrayList<>();
                for(int i=0;i<keys.size();i++) {
                    int row=i;
                    done.add(producers.submit(()->{
                        // Closing each one-row packet used to produce one native write.
                        try(var buffer=writer.buffer()){buffer.appendPacked(keys.get(row),0,distances.get(row),weights.get(row));}
                        catch(IOException error){throw new CompletionException(error);}
                    }));
                }
                for(Future<?> future:done)future.get();
            }
            LayerManifest manifest=writer.finish();
            assertEquals(keys.size(),manifest.canonicalSize());assertArrayEquals(expectedWeighted,manifest.weighted);
            long targetRecords=writer.sizing.aggregation().targetRecords();
            assertEquals((keys.size()+targetRecords-1)/targetRecords,writer.aggregateFlushes());
            assertTrue(writer.nativeWrites()<keys.size()/10,"Separate producer packets must share native write calls");
            assertEquals(0,writer.queuedBatches());assertEquals(0,writer.queuedBytesEstimate());
        }
        try(CanonicalLayerStore store=new CanonicalLayerStore(target)) {
            for(int row=0;row<keys.size();row++)assertEquals(distances.get(row).intValue(),store.database.get(keys.get(row))[0]);
            int count=0;
            try(RocksIterator iterator=store.database.newIterator()) {
                for(iterator.seekToFirst();iterator.isValid();iterator.next()) {
                    assertEquals(PackedPermutation5.bytes(n+1),iterator.key().length);assertEquals(1,iterator.value().length);
                    assertEquals(0,iterator.value()[0]&0xf0);count++;
                }
                iterator.status();
            }
            assertEquals(keys.size(),count);
        }
    }

    @Test void timerFlushesSparseOutputWithoutWaitingForAnotherProducerOrDegreeCompletion() throws Exception {
        try(AsyncCanonicalWriter writer=writer(1,1024,20,256)) {
            try(var buffer=writer.buffer()){buffer.append(new byte[]{0,1},0,1);}
            await(()->writer.writtenRecords()==1&&writer.aggregateFlushes()==1);
            assertEquals(1,writer.aggregateFlushes());assertEquals(1,writer.nativeWrites());
            assertFalse(Files.exists(writer.target),"Timed insertion must not publish an incomplete degree");
            assertEquals(1,writer.finish(1).canonicalSize());
        }
    }

    @Test void finishFlushesAPartialAggregateAndChecksTheReferenceBeforePublication() throws Exception {
        AsyncCanonicalWriter writer=writer(1,1024,60000,256);
        try {
            try(var buffer=writer.buffer()){buffer.append(new byte[]{0,1},0,1);}
            assertThrows(IOException.class,()->writer.finish(2));
            assertEquals(1,writer.writtenRecords());assertEquals(1,writer.nativeWrites());
            assertFalse(Files.exists(writer.target));assertFalse(Files.exists(writer.staging.resolve("manifest.properties")));
        }finally{assertThrows(IOException.class,writer::close);}
    }

    @Test void anAbortDiscardsTheUnflushedAggregateWithoutPublishing() throws Exception {
        AsyncCanonicalWriter writer=writer(1,1024,60000,256);
        try {
            try(var buffer=writer.buffer()){buffer.append(new byte[]{0,1},0,1);}
            await(()->writer.aggregateBytes()>0);
        }finally{writer.close();}
        assertEquals(0,writer.writtenRecords());assertEquals(0,writer.nativeWrites());
        assertFalse(Files.exists(writer.target));assertEquals(0,writer.aggregateBytes());
    }

    @Test void backgroundEncodingFailuresStillPropagateAndNeverPublishAStore() throws Exception {
        AsyncCanonicalWriter writer=writer(3,128,20,64);
        try {
            byte[] malformed=new byte[writer.rowBytes];malformed[0]=99;malformed[malformed.length-1]=1;
            writer.submit(malformed,1);
            assertThrows(IOException.class,writer::finish);assertFalse(Files.exists(writer.target));
        }finally{assertThrows(IOException.class,writer::close);}
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>deadline)fail("Timed out waiting for asynchronous persistence");
            Thread.sleep(10);
        }
    }
}
