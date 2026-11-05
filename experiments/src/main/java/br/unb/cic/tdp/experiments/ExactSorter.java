package br.unb.cic.tdp.experiments;

/** Complete bounded DFS: every block transposition, including -2 moves. */
final class ExactSorter {
    final SmallTables tables;
    final Runnable cancellationCheck;
    final byte[] stack=new byte[11*18],scratch=new byte[18];
    final int[] next=new int[11],predecessor=new int[19];
    int[] cuts;
    int degree;
    long nodes;
    ExactSorter(SmallTables tables) { this(tables,null); }
    ExactSorter(SmallTables tables,Runnable cancellationCheck) {this.tables=tables;this.cancellationCheck=cancellationCheck;}
    static int[] cuts(int n) {
        int[] result=new int[(n+1)*n*(n-1)/6];
        for(int a=0,j=0;a<n;a++) for(int b=a+1;b<n;b++) for(int c=b+1;c<=n;c++)
            result[j++]=a|(b<<5)|(c<<10);
        return result;
    }
    int oddCycleBound(int offset,int n) {
        int previous=0;
        for(int i=0;i<n;i++) { int v=stack[offset+i]; predecessor[v]=previous; previous=v; }
        predecessor[0]=previous;
        int visited=0,odd=0,N=n+1;
        for(int s=0;s<N;s++) {
            if((visited&(1<<s))!=0) continue;
            int x=s,length=0;
            do { visited|=1<<x; length++; x=predecessor[x==n?0:x+1]; } while(x!=s);
            odd+=length&1;
        }
        return (N-odd)/2;
    }
    boolean canSort(long rank,int n,int budget) {
        if(budget<0)return false;
        if(n!=degree) { degree=n; cuts=cuts(n); }
        SmallTables.unrank(rank,n,stack,0);
        next[0]=-1;
        int depth=0;
        while(depth>=0) {
            if((++nodes&4095)==0) {
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Exact search interrupted");
                if(cancellationCheck!=null)cancellationCheck.run();
            }
            int offset=depth*n,remaining=budget-depth;
            if(next[depth]<0) {
                int lower=oddCycleBound(offset,n);
                if(lower==0) return true;
                if(lower>remaining || remaining==0) { depth--; continue; }
                if(tables!=null) {
                    int exact=tables.coreDistance(stack,offset,n,scratch);
                    if(exact>=0) { if(exact<=remaining) return true; depth--; continue; }
                }
                next[depth]=0;
            }
            if(next[depth]>=cuts.length) { depth--; continue; }
            int code=cuts[next[depth]++],a=code&31,b=(code>>>5)&31,c=code>>>10;
            int child=(depth+1)*n,j=child;
            for(int i=0;i<a;i++) stack[j++]=stack[offset+i];
            for(int i=b;i<c;i++) stack[j++]=stack[offset+i];
            for(int i=a;i<b;i++) stack[j++]=stack[offset+i];
            for(int i=c;i<n;i++) stack[j++]=stack[offset+i];
            boolean inverse=depth>0;
            if(inverse) for(int i=0;i<n;i++) if(stack[child+i]!=stack[offset-n+i]) { inverse=false; break; }
            if(inverse) continue;
            depth++; next[depth]=-1;
        }
        return false;
    }
    int classify(long rank,int n,int low,int high) {
        if(canSort(rank,n,low-1)) return 0;
        if(low==high || canSort(rank,n,high-1)) return 1;
        return 2;
    }
}
