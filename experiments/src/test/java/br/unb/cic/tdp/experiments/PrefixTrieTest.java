package br.unb.cic.tdp.experiments;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrefixTrieTest {
    @Test void zeroGainPrefixContinuesToAThreeMoveCertificate() {
        PrefixTrie trie=new PrefixTrie(15,8,3);
        int node=trie.roots[15];assertFalse(trie.accepts(node));
        node=trie.child(node,0);assertTrue(node>=0);assertFalse(trie.accepts(node));
        node=trie.child(node,3);assertTrue(node>=0);assertFalse(trie.accepts(node));
        node=trie.child(node,3);assertTrue(trie.accepts(node));
    }
    @Test void everyAcceptingPatternSatisfiesTheActualBudget() {
        for(int n=1;n<=18;n++)for(int budget=0;budget<=11;budget++) {
            PrefixTrie trie=new PrefixTrie(n,budget,3);
            for(int m=0;m<=n;m++)visit(trie,trie.roots[m],m,0);
        }
    }
    private void visit(PrefixTrie trie,int node,int degree,int depth) {
        if(node<0)return;
        assertEquals(degree,trie.records[node*PrefixTrie.STRIDE]);
        if(trie.accepts(node))assertTrue(depth+RetentionPlan.upper(degree)<=trie.budget);
        else {
            assertTrue(depth<trie.moves);
            for(int gain=0;gain<=3;gain++)visit(trie,trie.child(node,gain),Math.max(0,degree-gain),depth+1);
        }
    }
    @Test void degree18DoesNotAssumeTheUnknownDiameterIsTen() {
        PrefixTrie trie=new PrefixTrie(18,10,3);
        assertFalse(trie.accepts(trie.roots[18]));
    }
}
