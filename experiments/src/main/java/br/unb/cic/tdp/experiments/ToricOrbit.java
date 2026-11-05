package br.unb.cic.tdp.experiments;

/** Canonicality of (omega, sigma), with fixed sigma=(0 1 ... n).
 * Products act on the left, as in ../td: omega = sigma * beta^{-1}.
 * The lexicographic key is omega's image vector, not beta's anchored word.
 */
final class ToricOrbit {
    private static final ThreadLocal<byte[][]> SCRATCH=ThreadLocal.withInitial(()->new byte[][]{new byte[19],new byte[19]});

    static int weight(byte[] word,int n) {
        byte[][] scratch=SCRATCH.get();
        omegaFromWord(word,n,scratch[0]);
        return weightOfOmega(scratch[0],scratch[1],n+1);
    }

    static void omegaFromWord(byte[] word,int n,byte[] omega) {
        int previous=0;
        for(int i=0;i<n;i++) {
            int value=word[i];omega[value]=(byte)(previous==n?0:previous+1);previous=value;
        }
        omega[0]=(byte)(previous==n?0:previous+1);
    }

    static void wordFromOmega(byte[] omega,int n,byte[] word) {
        byte[] inverse=new byte[n+1];
        for(int x=0;x<=n;x++)inverse[omega[x]]=(byte)x;
        int x=0;
        for(int i=0;i<n;i++){x=inverse[x==n?0:x+1];word[i]=(byte)x;}
    }

    static void canonicalOmega(byte[] word,int n,byte[] least) {
        byte[][] scratch=SCRATCH.get();byte[] omega=scratch[0],inverse=scratch[1];int N=n+1;
        omegaFromWord(word,n,omega);
        for(int x=0;x<N;x++){inverse[omega[x]]=(byte)x;least[x]=omega[x];}
        for(int shift=0;shift<N;shift++)for(int reflection=0;reflection<2;reflection++) {
            int a=shift+1;if(a==N)a=0;boolean smaller=false;
            for(int x=0;x<N;x++) {
                int source=reflection==0?x-shift:a-x;if(source<0)source+=N;
                int mapped=reflection==0?omega[source]+shift:a-inverse[source];
                if(mapped<0)mapped+=N;else if(mapped>=N)mapped-=N;
                if(mapped<least[x]){smaller=true;break;}if(mapped>least[x])break;
            }
            if(smaller)for(int x=0;x<N;x++) {
                int source=reflection==0?x-shift:a-x;if(source<0)source+=N;
                int mapped=reflection==0?omega[source]+shift:a-inverse[source];
                if(mapped<0)mapped+=N;else if(mapped>=N)mapped-=N;least[x]=(byte)mapped;
            }
        }
    }

    /** Distinct ordinary members of a parent's extended-toric orbit. */
    static int orbitRanks(byte[] word,int n,long[] ranks,byte[] transformed) {
        int N=n+1,size=0;
        for(int anchor=0;anchor<N;anchor++)for(int direction=1;direction>=-1;direction-=2) {
            int value=anchor==0?0:word[anchor-1];
            for(int j=1;j<N;j++) {
                int position=(anchor+direction*j+N)%N,symbol=position==0?0:word[position-1];
                transformed[j-1]=(byte)(direction==1?(symbol-value+N)%N:(value-symbol+N)%N);
            }
            ranks[size++]=SmallTables.rank(transformed,n);
        }
        java.util.Arrays.sort(ranks,0,size);int distinct=0;
        for(int i=0;i<size;i++)if(i==0||ranks[i]!=ranks[i-1])ranks[distinct++]=ranks[i];
        return distinct;
    }

    static int weightOfOmega(byte[] omega,byte[] inverse,int N) {
        for(int x=0;x<N;x++)inverse[omega[x]]=(byte)x;
        int stabilizer=0;
        for(int shift=0;shift<N;shift++) {
            boolean equal=true;
            for(int x=0;x<N;x++) {
                int source=x-shift;if(source<0)source+=N;
                int mapped=omega[source]+shift;if(mapped>=N)mapped-=N;
                if(mapped<omega[x])return 0;
                if(mapped>omega[x]){equal=false;break;}
            }
            if(equal)stabilizer++;
            // F_shift(omega)=(omega^{-1})^(sigma^(shift+1) r), r(x)=-x.
            int a=shift+1;if(a==N)a=0;
            equal=true;
            for(int x=0;x<N;x++) {
                int source=a-x;if(source<0)source+=N;
                int mapped=a-inverse[source];if(mapped<0)mapped+=N;
                if(mapped<omega[x])return 0;
                if(mapped>omega[x]){equal=false;break;}
            }
            if(equal)stabilizer++;
        }
        if(stabilizer==0||2*N%stabilizer!=0)throw new AssertionError("Invalid toric stabilizer");
        return 2*N/stabilizer;
    }
}
