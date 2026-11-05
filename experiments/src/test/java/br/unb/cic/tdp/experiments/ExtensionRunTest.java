package br.unb.cic.tdp.experiments;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionRunTest {
    @TempDir Path root;
    static ExtensionConfig config(Path root,int maxN,boolean gpu) {
        return new ExtensionConfig(root,maxN,3,7,64,2,32,2,gpu,0.25);
    }
    @Test void persistedExtensionMatchesEveryCanonicalLayerThroughNine() throws Exception {
        ExtensionRun.run(config(root,9,false));verify(root,9);
        Path finalStore=new LayerCatalog(root).find(9,5);var modified=Files.getLastModifiedTime(finalStore.resolve("manifest.properties"));
        ExtensionRun.run(config(root,9,false));
        assertEquals(modified,Files.getLastModifiedTime(finalStore.resolve("manifest.properties")));
    }
    @Test void aLongerHorizonBackfillsMissingParentLayers() throws Exception {
        ExtensionRun.run(config(root,4,false));
        LayerCatalog catalog=new LayerCatalog(root);
        assertNotNull(catalog.find(4,3));assertNull(catalog.find(4,2));
        var modified=Files.getLastModifiedTime(catalog.find(4,3).resolve("manifest.properties"));
        ExtensionRun.run(config(root,5,false));verify(root,5);
        assertNotNull(catalog.find(4,2));
        assertEquals(modified,Files.getLastModifiedTime(catalog.find(4,3).resolve("manifest.properties")));
    }
    @Test void aDistantHorizonDoesNotGenerateSpeculativeSupports() throws Exception {
        System.setProperty("extension.through","4");
        try{ExtensionRun.run(config(root,18,false));}finally{System.clearProperty("extension.through");}
        LayerCatalog catalog=new LayerCatalog(root);
        for(int n=2;n<=4;n++)assertNull(catalog.find(n,CountsRun.LOW[n]-1));
    }
    @Test void aPositiveDecisionNeverPublishesAnIncompleteParentSet() throws Exception {
        ExtensionRun.run(config(root,5,false));LayerCatalog catalog=new LayerCatalog(root);
        ExtensionRun.Stats stats=ExtensionRun.decideExact(config(root,6,false),6,4,catalog,new SmallTables(6));
        assertNotNull(stats.firstMatch);assertFalse(catalog.has(6,4));assertFalse(catalog.has(6,3));
        byte[] omega=new byte[7],word=new byte[6];PackedPermutation5.decode(stats.firstMatch,0,7,omega);ToricOrbit.wordFromOmega(omega,6,word);
        assertEquals(4,DiametralExtensionAudit.bfs(6)[(int)SmallTables.rank(word,6)]);
    }
    @Test void aNegativeDecisionPublishesAnExhaustivelyEmptySet() throws Exception {
        ExtensionRun.run(config(root,4,false));LayerCatalog catalog=new LayerCatalog(root);
        ExtensionRun.Stats stats=ExtensionRun.decideExact(config(root,5,false),5,4,catalog,new SmallTables(5));
        assertNull(stats.firstMatch);assertTrue(catalog.has(5,4));assertEquals(0,catalog.size(5,4));
        assertFalse(catalog.has(5,3));
    }
    @Test void aParentCoverageMismatchCannotBecomeAnEmptySearchResult() throws Exception {
        ExtensionRun.run(config(root,4,false));LayerCatalog catalog=new LayerCatalog(root);Path parent=catalog.find(4,3);
        LayerManifest manifest=LayerManifest.read(parent);manifest.canonical[3]++;manifest.weighted[3]++;manifest.write(parent);
        assertThrows(java.io.IOException.class,()->ExtensionRun.decideExact(config(root,5,false),5,4,catalog,new SmallTables(5)));
        assertFalse(catalog.has(5,4));
    }
    static void verify(Path root,int maxN) throws Exception {
        LayerCatalog catalog=new LayerCatalog(root);
        for(int n=1;n<=maxN;n++) {
          for(int layer=0;layer<=RetentionPlan.upper(n);layer++) {
            if(!catalog.has(n,layer))continue;
            byte[] exact=DiametralExtensionAudit.bfs(n),word=new byte[n],omega=new byte[n+1];
            Set<Long> expected=new HashSet<>();long[] weighted=new long[12];
            for(int rank=0;rank<exact.length;rank++)if(exact[rank]==layer) {
                weighted[exact[rank]]++;
                SmallTables.unrank(rank,n,word,0);if(ToricOrbit.weight(word,n)>0)expected.add((long)rank);
            }
            try(CanonicalLayerStore store=catalog.open(n,layer,layer);
                CanonicalLayerStore.BatchCursor cursor=store.cursor(layer)) {
                Set<Long> actual=new HashSet<>();long[] actualWeighted=new long[12];byte[] rows;
                while((rows=cursor.nextBatch(37))!=null)for(int offset=0;offset<rows.length;offset+=store.rowBytes) {
                    PackedPermutation5.decode(rows,offset+1,n+1,omega);ToricOrbit.wordFromOmega(omega,n,word);
                    long rank=SmallTables.rank(word,n);assertTrue(actual.add(rank),"Duplicate canonical");
                    assertEquals(exact[(int)rank],rows[offset]);int weight=ToricOrbit.weight(word,n);
                    assertTrue(weight>0);actualWeighted[rows[offset]&255]+=weight;
                }
                assertEquals(expected,actual,"n="+n);
                assertArrayEquals(weighted,store.manifest.weighted,"n="+n);
                assertArrayEquals(weighted,actualWeighted,"Recomputed orbit counts, n="+n);
                // Check exact membership of every ordinary permutation, not
                // just canonical rows or the final diametral cardinality.
                for(int rank=0;rank<exact.length;rank++) {
                    SmallTables.unrank(rank,n,word,0);ToricOrbit.canonicalOmega(word,n,omega);
                    assertEquals(exact[rank]==layer?exact[rank]:-1,store.distance(omega,layer),"n="+n+" rank="+rank);
                }
            }
          }
        }
    }
}
