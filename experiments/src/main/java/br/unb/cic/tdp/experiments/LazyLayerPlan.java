package br.unb.cic.tdp.experiments;

/** Requests only E(n-1,d-1) and E(n-1,d), by deletion monotonicity and the insertion lemma. */
final class LazyLayerPlan {
    interface Sets {
        boolean has(int n,int d) throws Exception;
        long size(int n,int d) throws Exception;
        void generate(int n,int d) throws Exception;
        default boolean exists(int n,int d) throws Exception {generate(n,d);return size(n,d)>0;}
    }
    private final Sets sets;
    LazyLayerPlan(Sets sets){this.sets=sets;}
    void require(int n,int d) throws Exception {
        if(d<0||d>RetentionPlan.upper(n)||sets.has(n,d))return;
        if(n>1){require(n-1,d-1);require(n-1,d);}
        System.out.printf("NEED E(%d,%d) reason=requested-exact-layer%n",n,d);
        sets.generate(n,d);
    }
    int diameter(int n) throws Exception {
        for(int d=RetentionPlan.upper(n);d>=CountsRun.LOW[n];d--) {
            require(n,d);if(sets.size(n,d)>0)return d;
            System.out.printf("EMPTY E(%d,%d) requesting_next_proven_bound%n",n,d);
        }
        throw new IllegalStateException("No permutations at the established diameter lower bound for n="+n);
    }
    /** Determine the diameter without enumerating its lower-bound layer. */
    int determine(int n) throws Exception {
        int lower=n==18?RetentionPlan.upper(17):CountsRun.LOW[n],upper=RetentionPlan.upper(n);
        if(lower==upper)return lower;
        if(upper!=lower+1)throw new IllegalArgumentException("Decision mode requires adjacent diameter bounds");
        if(sets.has(n,upper))return sets.size(n,upper)>0?upper:lower;
        if(n>1){require(n-1,upper-1);require(n-1,upper);}
        System.out.printf("DECIDE n=%d lower=%d upper=%d stop_at_first_match=true%n",n,lower,upper);
        return sets.exists(n,upper)?upper:lower;
    }
    /** Prepare a pattern cache from sets already required by a fixed, known dependency horizon. */
    void preparePatterns(int horizon,int degree) throws Exception {
        if(degree<1||degree>GpuPatternLayout.MAX_DEGREE||degree>horizon)throw new IllegalArgumentException("Invalid pattern preparation degree");
        RetentionPlan dependencies=new RetentionPlan(horizon);
        System.out.printf("PATTERN_PREPARE through=%d dependency_horizon=%d floor=%d%n",degree,horizon,dependencies.floor[degree]);
        for(int d=dependencies.floor[degree];d<=RetentionPlan.upper(degree);d++)require(degree,d);
    }
}
