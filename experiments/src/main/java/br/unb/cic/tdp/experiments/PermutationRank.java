package br.unb.cic.tdp.experiments;

/** Collision-free rank of the anchored word belonging to the chosen canonical omega. */
final class PermutationRank {
    private static final ThreadLocal<byte[][]> SCRATCH=ThreadLocal.withInitial(()->new byte[][]{new byte[19],new byte[19]});
    static int bytes(int n){if(n<1||n>18)throw new IllegalArgumentException("Rank degree must be 1..18");return Math.max(1,(64-Long.numberOfLeadingZeros(SmallTables.FACTORIAL[n]-1)+7)/8);}
    static long ofOmega(byte[] omega,int n) {
        if(n<1||n>18||omega.length<n+1)throw new IllegalArgumentException("Rank requires degree 1..18 and its complete omega");
        byte[] inverse=SCRATCH.get()[1];int seen=0;
        for(int i=0;i<=n;i++){int v=omega[i];if(v<0||v>n||(seen&(1<<v))!=0)throw new IllegalArgumentException("Invalid omega image vector");inverse[v]=(byte)i;seen|=1<<v;}
        long rank=0;int x=0,used=0;
        for(int i=0;i<n;i++){x=inverse[x==n?0:x+1];if(x==0||(used&(1<<(x-1)))!=0)throw new IllegalArgumentException("Unrealizable omega");rank=rank*(n-i)+x-1-Integer.bitCount(used&((1<<(x-1))-1));used|=1<<(x-1);}
        return rank;
    }
    static long ofPackedOmega(byte[] packed,int offset,int n){byte[] omega=SCRATCH.get()[0];PackedPermutation5.decode(packed,offset,n+1,omega);return ofOmega(omega,n);}
    static void encode(long rank,int n,byte[] target,int offset){if(rank<0||rank>=SmallTables.FACTORIAL[n])throw new IllegalArgumentException("Rank outside degree universe");int width=bytes(n);for(int i=width-1;i>=0;i--){target[offset+i]=(byte)rank;rank>>>=8;}}
    static long decode(byte[] key,int n){if(key.length!=bytes(n))throw new IllegalArgumentException("Invalid fixed-width rank");long rank=0;for(byte b:key)rank=(rank<<8)|(b&255);if(rank>=SmallTables.FACTORIAL[n])throw new IllegalArgumentException("Rank outside degree universe");return rank;}
}
