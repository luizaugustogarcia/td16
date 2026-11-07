package br.unb.cic.tdp.experiments;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PermutationRankTest {
    @Test void anchoredRankAndSortedKeysPreserveEveryPermutationThroughEight() {
        for(int n=1;n<=8;n++) {
            byte[] word=new byte[n],omega=new byte[n+1],packed=new byte[PackedPermutation5.bytes(n+1)];
            byte[] previous=null;
            for(long rank=0;rank<SmallTables.FACTORIAL[n];rank++) {
                SmallTables.unrank(rank,n,word,0);ToricOrbit.omegaFromWord(word,n,omega);
                assertEquals(rank,PermutationRank.ofOmega(omega,n));
                PackedPermutation5.encode(omega,n+1,packed,0);
                assertEquals(rank,PermutationRank.ofPackedOmega(packed,0,n));
                byte[] key=new byte[PermutationRank.bytes(n)];PermutationRank.encode(rank,n,key,0);
                assertEquals(rank,PermutationRank.decode(key,n));
                if(previous!=null)assertTrue(Arrays.compareUnsigned(previous,key)<0);
                previous=key;
            }
        }
    }
    @Test void fullDegreeEighteenRanksRetainAllFiftyThreeBits() {
        assertEquals(5,PermutationRank.bytes(14));assertEquals(7,PermutationRank.bytes(18));
        byte[] word=new byte[18],omega=new byte[19],key=new byte[7];
        long rank=SmallTables.FACTORIAL[18]-1;
        SmallTables.unrank(rank,18,word,0);ToricOrbit.omegaFromWord(word,18,omega);
        assertEquals(rank,PermutationRank.ofOmega(omega,18));
        PermutationRank.encode(rank,18,key,0);assertEquals(rank,PermutationRank.decode(key,18));
    }
    @Test void invalidAndUnrealizableOmegaAndOutOfUniverseKeysAreRejected() {
        assertThrows(IllegalArgumentException.class,()->PermutationRank.ofOmega(new byte[]{0,0,2,3},3));
        assertThrows(IllegalArgumentException.class,()->PermutationRank.ofOmega(new byte[]{1,2,3,0},3));
        assertThrows(IllegalArgumentException.class,()->PermutationRank.ofOmega(new byte[]{0,1},3));
        assertThrows(IllegalArgumentException.class,()->PermutationRank.bytes(0));
        assertThrows(IllegalArgumentException.class,()->PermutationRank.bytes(19));
        assertThrows(IllegalArgumentException.class,()->PermutationRank.decode(new byte[]{6},3));
        assertThrows(IllegalArgumentException.class,()->PermutationRank.encode(-1,3,new byte[1],0));
    }
    @Test void productionArchiveSizingIncludesFastBucketSamplesAndTailLabels() {
        var exact=CompactRankArchive.Layout.of(SmallTables.FACTORIAL[14],174_944_393,true);
        var tail=CompactRankArchive.Layout.of(SmallTables.FACTORIAL[14],174_944_393,false);
        assertEquals(400,CompactRankArchive.Layout.of(1,0,true).bytes());
        assertEquals(0,exact.labelWords());assertTrue(exact.sampleWords()>0);
        assertEquals(exact.bytes()+tail.labelWords()*8,tail.bytes());
        assertTrue(CompactRankArchive.Layout.of(SmallTables.FACTORIAL[18],4_000_000_000L,true).bytes()>Integer.MAX_VALUE);
    }
}
