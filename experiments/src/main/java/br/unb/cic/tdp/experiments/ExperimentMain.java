package br.unb.cic.tdp.experiments;

public final class ExperimentMain {
    private ExperimentMain(){}
    public static void main(String[] args) throws Exception {
        String mode=args.length==0?"audit":args[0];
        switch(mode) {
            case "audit"->DiametralExtensionAudit.run(Integer.getInteger("audit.maxN",9));
            case "density-audit"->SetDensityAudit.run();
            case "gpu-benchmark"->GpuDiametralFilter.benchmark();
            case "search-benchmark"->ExactSearchBenchmark.run();
            case "gpu-audit"->GpuCounterAudit.run();
            case "sample-membership"->MembershipDiagnostics.sample();
            case "classify-sample"->MembershipDiagnostics.classify();
            case "audit-generation"->MembershipDiagnostics.generation();
            case "count","extend"->ExtensionRun.run(ExtensionConfig.system());
            case "diameter"->{
                System.setProperty("extension.diameterOnly","true");
                if(Integer.getInteger("count.maxN",18)==18&&System.getProperty("extension.patternHorizon")==null)
                    System.setProperty("extension.patternHorizon","17");
                ExtensionRun.run(ExtensionConfig.system());
            }
            case "extension-benchmark"->ExtensionRun.benchmark(ExtensionConfig.system());
            case "verify-store"->{
                var root=ExtensionConfig.system().dataDirectory();int n=Integer.getInteger("verify.n",12);
                try(var store=new LayerCatalog(root).openTail(n);GpuStreamingCounter gpu=new GpuStreamingCounter(null)){gpu.loadPersistedTables(root,n);gpu.verifyStoredHistogram(store.manifest);}
            }
            case "verify-membership"->{
                var root=ExtensionConfig.system().dataDirectory();int n=Integer.getInteger("verify.n",14);
                for(int ordinal:GpuSettings.system().layout().devices())try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,64,ordinal)) {
                    gpu.membership=GpuMembershipTable.load(gpu,root,n,0);
                    if(gpu.membership==null)throw new java.io.IOException("Complete membership set is missing or does not fit in VRAM");
                }
            }
            case "extension-plan"->new RetentionPlan(Integer.getInteger("count.maxN",18)).print();
            case "scan-count"->CountsRun.run(Integer.getInteger("count.maxN",18),
                Integer.getInteger("count.cpuThreads",Runtime.getRuntime().availableProcessors()),
                Double.parseDouble(System.getProperty("count.vramFraction","0.90")),
                Boolean.parseBoolean(System.getProperty("count.gpu","true")));
            default->throw new IllegalArgumentException("Modes: diameter, count/extend, extension-plan, extension-benchmark, search-benchmark, scan-count, verify-membership, audit, density-audit, gpu-benchmark, gpu-audit");
        }
    }
}
