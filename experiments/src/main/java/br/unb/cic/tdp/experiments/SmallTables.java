package br.unb.cic.tdp.experiments;

import java.util.Arrays;

final class SmallTables {
    static final long[] FACTORIAL = new long[19];
    static { FACTORIAL[0]=1; for(int n=1;n<19;n++) FACTORIAL[n]=FACTORIAL[n-1]*n; }
    final int degree;
    final int[] offsets = new int[10];
    final byte[] distances;
    final long[] diametralCounts = new long[10];
    SmallTables(int degree) {
        this.degree=degree;
        int length=0;
        for(int n=0;n<=degree;n++) { offsets[n]=length; length+=Math.toIntExact(FACTORIAL[n]); }
        distances=new byte[length];
        for(int n=1;n<=degree;n++) {
            byte[] table=DiametralExtensionAudit.bfs(n);
            System.arraycopy(table,0,distances,offsets[n],table.length);
            int diameter=DiametralExtensionAudit.diameter(table);
            for(byte d:table) if(d==diameter) diametralCounts[n]++;
            long[] histogram=new long[diameter+1];
            for(byte d:table) histogram[d]++;
            if(!Arrays.equals(histogram,Arrays.stream(DiametralExtensionAudit.EXPECTED[n]).asLongStream().toArray()))
                throw new AssertionError("Base histogram mismatch at n="+n);
        }
    }
    static void unrank(long rank,int n,byte[] out,int offset) {
        int available=(1<<n)-1;
        for(int i=0;i<n;i++) {
            int index=(int)(rank/FACTORIAL[n-1-i]); rank%=FACTORIAL[n-1-i];
            int remaining=available;
            while(index-->0) remaining&=remaining-1;
            int bit=Integer.numberOfTrailingZeros(remaining);
            out[offset+i]=(byte)(bit+1); available&=~(1<<bit);
        }
    }
    static long rank(byte[] word,int n) {
        long result=0;int used=0;
        for(int i=0;i<n;i++) {
            int value=word[i];result=result*(n-i)+value-1-Integer.bitCount(used&((1<<(value-1))-1));used|=1<<(value-1);
        }
        return result;
    }
    int coreDistance(byte[] word,int offset,int n,byte[] scratch) {
        System.arraycopy(word,offset,scratch,0,n);
        int m=n;
        while(m>0) {
            int omit=-1;
            if(scratch[0]==1) omit=0;
            else if(scratch[m-1]==m) omit=m-1;
            else for(int i=1;i<m;i++) if(scratch[i]==scratch[i-1]+1) { omit=i; break; }
            if(omit<0) break;
            int value=scratch[omit];
            for(int i=0,j=0;i<m;i++) if(i!=omit) {
                int v=scratch[i]; scratch[j++]=(byte)(v>value?v-1:v);
            }
            m--;
        }
        if(m>degree) return -1;
        int rank=0,used=0;
        for(int i=0;i<m;i++) {
            int v=scratch[i];
            rank=rank*(m-i)+v-1-Integer.bitCount(used&((1<<(v-1))-1));
            used|=1<<(v-1);
        }
        return distances[offsets[m]+rank];
    }
}
