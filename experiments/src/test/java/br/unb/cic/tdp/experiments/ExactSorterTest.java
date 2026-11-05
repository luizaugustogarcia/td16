package br.unb.cic.tdp.experiments;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactSorterTest {
    @Test void orbitWeightsReconstructEveryDistanceLayer() {
        byte[] word=new byte[18];
        for(int n=1;n<=9;n++) {
            byte[] exact=DiametralExtensionAudit.bfs(n);
            int[] weighted=new int[DiametralExtensionAudit.EXPECTED[n].length];
            for(int rank=0;rank<exact.length;rank++) {
                SmallTables.unrank(rank,n,word,0);weighted[exact[rank]]+=ToricOrbit.weight(word,n);
            }
            assertArrayEquals(DiametralExtensionAudit.EXPECTED[n],weighted,"n="+n);
        }
    }
    @Test void fixedTargetOmegaRoundTripsEveryInstanceThroughNine() {
        byte[] word=new byte[18],omega=new byte[19],decoded=new byte[18];
        for(int n=1;n<=9;n++)for(int rank=0;rank<DiametralExtensionAudit.FACT[n];rank++) {
            SmallTables.unrank(rank,n,word,0);ToricOrbit.omegaFromWord(word,n,omega);
            ToricOrbit.wordFromOmega(omega,n,decoded);
            for(int i=0;i<n;i++)assertEquals(word[i],decoded[i],"n="+n+" rank="+rank);
        }
    }
    @Test void fixedTargetCanonicalityMatchesExplicitWordOrbit() {
        for(int n=1;n<=8;n++) {
            int N=n+1;byte[] word=new byte[n],omega=new byte[N],candidate=new byte[n],image=new byte[N];
            for(int rank=0;rank<DiametralExtensionAudit.FACT[n];rank++) {
                SmallTables.unrank(rank,n,word,0);ToricOrbit.omegaFromWord(word,n,omega);
                int stabilizer=0;boolean least=true;
                for(int anchor=0;anchor<N;anchor++)for(int direction=1;direction>=-1;direction-=2) {
                    int a=anchor==0?0:word[anchor-1];
                    for(int j=1;j<N;j++) {
                        int position=(anchor+direction*j+N)%N;
                        int value=position==0?0:word[position-1];
                        candidate[j-1]=(byte)(direction==1?(value-a+N)%N:(a-value+N)%N);
                    }
                    ToricOrbit.omegaFromWord(candidate,n,image);
                    int comparison=java.util.Arrays.compare(image,omega);
                    if(comparison<0)least=false;
                    if(comparison==0)stabilizer++;
                }
                assertEquals(least?2*N/stabilizer:0,ToricOrbit.weight(word,n),"n="+n+" rank="+rank);
            }
        }
    }
    @Test void completeSearchMatchesBfsWithoutPatternTables() {
        ExactSorter sorter=new ExactSorter(null);
        for(int n=2;n<=7;n++) {
            byte[] exact=DiametralExtensionAudit.bfs(n);
            for(int rank=0;rank<exact.length;rank++) {
                assertTrue(sorter.canSort(rank,n,exact[rank]),"n="+n+" rank="+rank);
                assertFalse(sorter.canSort(rank,n,exact[rank]-1),"n="+n+" rank="+rank);
            }
        }
    }
    @Test void contractionAndSmallTableDecisionsMatchBfs() {
        SmallTables tables=new SmallTables(7);
        ExactSorter sorter=new ExactSorter(tables);
        byte[] exact=DiametralExtensionAudit.bfs(8);
        for(int rank=0;rank<exact.length;rank++) {
            assertTrue(sorter.canSort(rank,8,exact[rank]));
            assertFalse(sorter.canSort(rank,8,exact[rank]-1));
        }
    }
    @Test void longRanksSupportDegree18() {
        byte[] word=new byte[18];
        for(int n=1;n<=18;n++) {
            SmallTables.unrank(0,n,word,0);for(int i=0;i<n;i++)assertEquals(i+1,word[i]);
            SmallTables.unrank(SmallTables.FACTORIAL[n]-1,n,word,0);for(int i=0;i<n;i++)assertEquals(n-i,word[i]);
        }
    }
    @Test void degree18TwoBudgetProtocolHasBothOutcomesAtSmallDegree() {
        ExactSorter sorter=new ExactSorter(null);int[] counts=new int[3];
        for(int rank=0;rank<120;rank++)counts[sorter.classify(rank,5,2,3)]++;
        assertArrayEquals(new int[]{21,68,31},counts);
    }
}
