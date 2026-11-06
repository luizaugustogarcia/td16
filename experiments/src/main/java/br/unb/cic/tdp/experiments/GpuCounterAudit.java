package br.unb.cic.tdp.experiments;

final class GpuCounterAudit {
    static void run() throws Exception {
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null)) {
            gpu.context.auditMoveDeltas();
            gpu.auditSlackMoveDeltas();
            for(int n=1;n<=9;n++)gpu.auditCanonicality(n);
            if(new ExactSorter(null).classify(0,1,0,0)!=1)throw new AssertionError("D(1) mismatch");
            System.out.println("COMPLETE SEARCH n=1 |D(1)|=1");
            for(int n=2;n<=7;n++) {
                int low=CountsRun.LOW[n];
                try(GpuStreamingCounter.Batch batch=gpu.new Batch(n,low,low,DiametralExtensionAudit.FACT[n])) {
                    long[] counts=batch.count(0,DiametralExtensionAudit.FACT[n],null);
                    int[] histogram=DiametralExtensionAudit.EXPECTED[n];
                    if(counts[1]!=histogram[histogram.length-1])throw new AssertionError("GPU D("+n+") mismatch");
                    System.out.printf("GPU COMPLETE SEARCH n=%d |D(%d)|=%d (no pattern tables, all moves)%n",n,n,counts[1]);
                }
            }
        }
        // Degree 8 uses only exact tables through degree 7; the degree-8
        // expected answer remains an independent BFS result.
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(new SmallTables(7));
            GpuStreamingCounter.Batch batch=gpu.new Batch(8,5,5,40320)) {
            java.util.List<Long> stolen=new java.util.ArrayList<>();
            long[] counts=batch.count(0,40320,null,()->2,stolen::add);
            ExactSorter cpu=new ExactSorter(new SmallTables(7));byte[] word=new byte[8];
            for(long rank:stolen) {
                SmallTables.unrank(rank,8,word,0);int weight=ToricOrbit.weight(word,8);
                if(weight>0&&cpu.classify(rank,8,5,5)==1)counts[1]+=weight;
            }
            if(counts[0]+stolen.size()!=40320)throw new AssertionError("GPU/CPU steal coverage mismatch");
            if(counts[1]!=2836)throw new AssertionError("GPU D(8) mismatch with smaller pattern tables");
            System.out.println("GPU COMPLETE SEARCH n=8 |D(8)|=2836 (tables only through n=7, all moves)");
        }
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(new SmallTables(8));
            GpuStreamingCounter.Batch batch=gpu.new Batch(9,5,5,362880)) {
            long[] counts=batch.count(0,362880,null);
            if(counts[1]!=114327)throw new AssertionError("GPU D(9) mismatch");
            System.out.println("GPU COMPLETE SEARCH n=9 |D(9)|=114327 (tables only through n=8, all moves)");
        }
        // Exercise both outcomes of the degree-18 two-budget protocol on a
        // tractable complete graph: distances 2 and 3 in S_5.
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null);
            GpuStreamingCounter.Batch batch=gpu.new Batch(5,2,3,1)) {
            long[] counts=new long[3];
            for(int first=0;first<120;first+=12) {
                long[] part=batch.count(first,12,null);for(int i=0;i<3;i++)counts[i]+=part[i];
            }
            if(counts[0]!=120||counts[1]!=68||counts[2]!=31)throw new AssertionError("GPU two-budget mismatch");
            if(batch.overflowSplits==0)throw new AssertionError("Canonical queue overflow fallback was not exercised");
        }
        System.out.println("PASS: complete all-move GPU counting and the two-budget protocol.");
    }
}
