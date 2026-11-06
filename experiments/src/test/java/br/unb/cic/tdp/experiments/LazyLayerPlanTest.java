package br.unb.cic.tdp.experiments;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LazyLayerPlanTest {
    private static final class Sets implements LazyLayerPlan.Sets {
        final Set<String> complete=new HashSet<>();
        final List<String> generated=new ArrayList<>();
        boolean empty18;
        String key(int n,int d){return n+":"+d;}
        public boolean has(int n,int d){return complete.contains(key(n,d));}
        public long size(int n,int d){return empty18&&n==18&&d==11?0:1;}
        public void generate(int n,int d){String key=key(n,d);assertTrue(complete.add(key),"Recomputed "+key);generated.add(key);}
    }
    @Test void d15NeedsOnlyTheTopOfD14AndD16RequestsItsLowerSupportLater() throws Exception {
        Sets sets=new Sets();LazyLayerPlan plan=new LazyLayerPlan(sets);
        for(int n=1;n<=14;n++)plan.diameter(n);
        assertTrue(sets.has(14,8));assertFalse(sets.has(14,7));assertFalse(sets.has(14,6));
        int before=sets.generated.size();assertEquals(9,plan.diameter(15));
        assertEquals(List.of("15:9"),sets.generated.subList(before,sets.generated.size()));
        plan.diameter(16);assertTrue(sets.has(14,7));assertFalse(sets.has(14,6));
    }
    @Test void degree18LowerBranchIsGeneratedOnlyAfterAnExhaustivelyEmptyUpperBranch() throws Exception {
        for(boolean empty:new boolean[]{false,true}) {
            Sets sets=new Sets();sets.empty18=empty;LazyLayerPlan plan=new LazyLayerPlan(sets);
            for(int n=1;n<=17;n++)plan.diameter(n);
            assertFalse(sets.has(14,6));assertFalse(sets.has(17,9));
            assertEquals(empty?10:11,plan.diameter(18));
            assertEquals(empty,sets.has(14,6));assertEquals(empty,sets.has(17,9));
            if(empty)assertTrue(sets.generated.indexOf("18:11")<sets.generated.indexOf("14:6"));
            int before=sets.generated.size();plan.diameter(18);assertEquals(before,sets.generated.size());
        }
    }
    @Test void diameterOnlyNeverRequestsTheDistanceTenSetOrItsSupportChain() throws Exception {
        for(boolean empty:new boolean[]{false,true}) {
            Sets sets=new Sets();sets.empty18=empty;LazyLayerPlan plan=new LazyLayerPlan(sets);
            for(int n=1;n<=17;n++)plan.diameter(n);
            assertEquals(empty?10:11,plan.determine(18));
            assertTrue(sets.has(18,11));assertFalse(sets.has(18,10));
            assertFalse(sets.has(17,9));assertFalse(sets.has(14,6));
            int before=sets.generated.size();plan.determine(18);assertEquals(before,sets.generated.size());
        }
    }
    @Test void preparingTheD17PatternCacheDoesNotGenerateSpeculativeLargeLayers() throws Exception {
        Sets sets=new Sets();LazyLayerPlan plan=new LazyLayerPlan(sets);
        for(int n=1;n<=13;n++)plan.diameter(n);
        assertFalse(sets.has(13,6));
        plan.preparePatterns(17,13);
        assertTrue(sets.has(13,6));assertFalse(sets.has(13,5));assertFalse(sets.has(14,7));assertFalse(sets.has(14,6));
        int before=sets.generated.size();plan.preparePatterns(17,13);assertEquals(before,sets.generated.size());
        plan.diameter(14);plan.diameter(15);assertFalse(sets.has(14,7));
    }
    @Test void degreeFourteenPreparationOnlyReordersSetsAlreadyNeededForD17() throws Exception {
        Sets ordinary=new Sets();LazyLayerPlan baseline=new LazyLayerPlan(ordinary);
        for(int n=1;n<=17;n++)baseline.diameter(n);
        Sets cached=new Sets();LazyLayerPlan plan=new LazyLayerPlan(cached);
        for(int n=1;n<=13;n++)plan.diameter(n);
        plan.preparePatterns(17,13);plan.diameter(14);
        assertFalse(cached.has(14,7));
        plan.preparePatterns(17,14);
        assertTrue(cached.has(14,7));assertTrue(cached.has(14,8));
        assertFalse(cached.has(14,6));assertFalse(cached.has(13,5));
        int before=cached.generated.size();plan.preparePatterns(17,14);
        assertEquals(before,cached.generated.size());
        for(int n=15;n<=17;n++)plan.diameter(n);
        assertEquals(ordinary.complete,cached.complete);
        cached.empty18=true;
        assertEquals(10,plan.determine(18));
        assertFalse(cached.has(18,10));assertFalse(cached.has(14,6));
    }
}
