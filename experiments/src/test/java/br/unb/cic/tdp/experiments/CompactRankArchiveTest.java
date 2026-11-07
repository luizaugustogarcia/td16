package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CompactRankArchiveTest {
    @TempDir Path root;
    private Path fixture(int n,int floor,boolean exact) throws Exception {
        byte[] distances=DiametralExtensionAudit.bfs(n),word=new byte[n],omega=new byte[n+1];
        try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,n,floor,n,16,2,exact)) {
            try(var output=writer.buffer()) {for(int rank=0;rank<distances.length;rank++){SmallTables.unrank(rank,n,word,0);int weight=ToricOrbit.weight(word,n);if(weight==0||distances[rank]<floor)continue;ToricOrbit.omegaFromWord(word,n,omega);output.append(omega,distances[rank],weight);}}
            writer.finish();return writer.target;
        }
    }
    @Test void exactPrimaryArchivePreservesEveryBfsMembershipWithoutRocksDb() throws Exception {
        Path path=fixture(6,3,true);assertFalse(Files.exists(path.resolve("db")));assertTrue(Files.isRegularFile(path.resolve(CompactRankArchive.FILE)));
        byte[] distances=DiametralExtensionAudit.bfs(6),word=new byte[6],omega=new byte[7];
        try(CanonicalLayerStore store=new CanonicalLayerStore(path)) {
            assertNotNull(store.compact);
            for(int rank=0;rank<distances.length;rank++){SmallTables.unrank(rank,6,word,0);ToricOrbit.canonicalOmega(word,6,omega);assertEquals(distances[rank]==3?3:-1,store.distance(omega,3));}
            assertEquals(CompactRankArchive.bytesFor(store.manifest),Files.size(path.resolve(CompactRankArchive.FILE)));
        }
    }
    @Test void rankOrderedFinalizationPreservesTailLabels() throws Exception {
            Path path=fixture(6,0,false);byte[] distances=DiametralExtensionAudit.bfs(6),word=new byte[6],omega=new byte[7];
            try(CanonicalLayerStore store=new CanonicalLayerStore(path)) {for(int rank=0;rank<distances.length;rank++){SmallTables.unrank(rank,6,word,0);ToricOrbit.canonicalOmega(word,6,omega);assertEquals(distances[rank],store.distance(omega,0));}}
            try(var files=Files.list(path)){assertFalse(files.anyMatch(p->Files.isDirectory(p)&&p.getFileName().toString().startsWith(".compact-")));}
    }
    @Test void parallelRankTransportCoversEveryRecordOnceAndUsesLittleEndianRanks() throws Exception {
        Path path=fixture(7,0,false);
        GpuSettingsTest.configured(Map.of("extension.parallelReadThreshold","1"),()->{
            byte[] distances=DiametralExtensionAudit.bfs(7);
            try(CanonicalLayerStore store=new CanonicalLayerStore(path);var cursor=store.sourceRanks(0,17,4)) {
                Set<Long> found=new HashSet<>();byte[] batch,word=new byte[7];long weighted=0;
                while((batch=cursor.nextBatch(17))!=null)for(int offset=0;offset<batch.length;offset+=9){long rank=ByteBuffer.wrap(batch,offset+1,8).order(ByteOrder.LITTLE_ENDIAN).getLong();assertTrue(found.add(rank));SmallTables.unrank(rank,7,word,0);assertEquals(distances[(int)rank],batch[offset]);weighted+=ToricOrbit.weight(word,7);}
                assertEquals(store.manifest.canonicalSize(),found.size());assertEquals(SmallTables.FACTORIAL[7],weighted);
            }
        });
    }
    @Test void payloadCorruptionAndTruncationAreRejectedBeforeMembership() throws Exception {
        Path path=fixture(6,0,false),file=path.resolve(CompactRankArchive.FILE);byte[] original=Files.readAllBytes(file);
        byte[] bad=original.clone();bad[CompactRankArchive.HEADER_BYTES]^=1;Files.write(file,bad);assertThrows(IOException.class,()->new CanonicalLayerStore(path));
        Files.write(file,Arrays.copyOf(original,original.length-1));assertThrows(IOException.class,()->new CanonicalLayerStore(path));
        Files.write(file,original);try(CanonicalLayerStore ignored=new CanonicalLayerStore(path)){}
    }
    @Test void duplicateSourceRecordsCannotPublishAnArchive() throws Exception {
        AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,3,0,3,8,2,true);
        try{try(var output=writer.buffer()){output.append(new byte[]{0,1,2,3},0,1);output.append(new byte[]{0,1,2,3},0,1);}assertThrows(IOException.class,writer::finish);assertFalse(Files.exists(writer.target));}
        finally{assertThrows(IOException.class,writer::close);}
    }
    @Test void emptyArchiveAndDegreeEighteenRanksUseTheSameLayout() throws Exception {
        try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(root,18,11,18,16,2,true)){writer.finish();try(CanonicalLayerStore store=new CanonicalLayerStore(writer.target);var rows=store.cursor(11)){assertNull(rows.nextBatch(16));assertEquals(-1,store.compact.find(0));}}
        Path highRoot=root.resolve("high");Random random=new Random(20261107);Set<Long> expected=new TreeSet<>();byte[] word=new byte[18],omega=new byte[19],canonicalWord=new byte[18];
        try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(highRoot,18,10,18,16,2,true)) {
            try(var output=writer.buffer()){for(int i=0;i<64;i++){SmallTables.unrank(random.nextLong(SmallTables.FACTORIAL[18]),18,word,0);ToricOrbit.canonicalOmega(word,18,omega);ToricOrbit.wordFromOmega(omega,18,canonicalWord);long rank=SmallTables.rank(canonicalWord,18);if(expected.add(rank))output.append(omega,10,ToricOrbit.weight(canonicalWord,18));}}
            writer.finish();try(CanonicalLayerStore store=new CanonicalLayerStore(writer.target)){assertTrue(expected.stream().anyMatch(rank->rank>(1L<<48)));for(long rank:expected)assertEquals(10,store.compact.distance(rank));var ranks=store.compact.ranks();for(long rank:expected)assertEquals(rank,ranks.next());assertEquals(-1,ranks.next());}
        }
    }
    @Test void ordinaryOrbitImageHasExactCountsAndStandaloneOffsets() throws Exception {
        Path path=fixture(6,4,true);LayerManifest manifest=LayerManifest.read(path);
        try(CompactRankArchive archive=CompactRankArchive.open(path,manifest)) {
            byte[] image=archive.ordinaryImage(1000);assertNotNull(image);LongBuffer header=ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
            assertEquals(manifest.weighted[4],header.get(6));assertEquals(CompactRankArchive.ORDINARY,header.get(19));assertEquals(image.length,header.get(17));assertNull(archive.ordinaryImage(1));
        }
    }
}
