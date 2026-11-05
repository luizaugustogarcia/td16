package br.unb.cic.tdp.experiments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Gain-pattern tries for certificates k + TD(contracted degree) <= budget. */
final class PrefixTrie {
    // One record: degree, accepting, child[0], child[1], child[2], child[3].
    static final int STRIDE=6;
    final int[] roots,records;
    final int moves,budget;

    PrefixTrie(int degree,int budget,int moves) {
        if(degree<1||degree>18||budget<0||moves<1||moves>3)
            throw new IllegalArgumentException("Prefix search requires degree 1..18, budget >= 0, moves 1..3");
        this.moves=moves;this.budget=budget;roots=new int[degree+1];
        List<int[]> nodes=new ArrayList<>();
        for(int m=0;m<=degree;m++)roots[m]=build(nodes,m,0);
        records=new int[nodes.size()*STRIDE];
        for(int i=0;i<nodes.size();i++)System.arraycopy(nodes.get(i),0,records,i*STRIDE,STRIDE);
    }

    private int build(List<int[]> nodes,int degree,int depth) {
        boolean accepts=depth+RetentionPlan.upper(degree)<=budget;
        int[] node={degree,accepts?1:0,-1,-1,-1,-1};
        if(!accepts&&depth<moves&&depth<budget) {
            for(int gain=0;gain<=3;gain++)node[2+gain]=build(nodes,Math.max(0,degree-gain),depth+1);
        }
        if(!accepts&&Arrays.stream(node,2,STRIDE).allMatch(child->child<0))return -1;
        int index=nodes.size();nodes.add(node);return index;
    }

    int child(int node,int gain){return node<0?-1:records[node*STRIDE+2+gain];}
    boolean accepts(int node){return node>=0&&records[node*STRIDE+1]!=0;}
}
