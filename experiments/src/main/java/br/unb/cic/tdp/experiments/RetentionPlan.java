package br.unb.cic.tdp.experiments;

import java.util.Arrays;

/** Legacy eager closure for benchmark/audit comparisons; production uses LazyLayerPlan. */
final class RetentionPlan {
    final int maxN;
    final int[] floor;
    RetentionPlan(int maxN) {
        if(maxN<1||maxN>18)throw new IllegalArgumentException("Maximum degree must be 1..18");
        this.maxN=maxN;floor=new int[maxN+1];floor[maxN]=CountsRun.LOW[maxN];
        for(int n=maxN-1;n>=1;n--)floor[n]=Math.max(0,Math.min(CountsRun.LOW[n],floor[n+1]-1));
    }
    static int upper(int n){return n==18?11:CountsRun.LOW[n];}
    void print(){
        System.out.printf("PLAN mode=lazy targets=D(1)..D(%d) output=one-exact-distance-set-at-a-time%n",maxN);
        System.out.println("DEPENDENCY E(n,d) requires only E(n-1,d-1) and E(n-1,d), within proven diameter bounds");
        if(maxN==18)System.out.println("TARGET E(18,11) first; diameter mode concludes TD(18)=10 if empty; only count mode requests E(18,10)");
    }
    @Override public String toString(){return Arrays.toString(floor);}
}
