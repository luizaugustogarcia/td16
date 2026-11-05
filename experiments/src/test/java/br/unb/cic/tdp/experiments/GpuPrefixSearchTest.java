package br.unb.cic.tdp.experiments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="gpuTests",matches="true")
class GpuPrefixSearchTest {
    @Test void everyShortCertificateAgreesWithIndependentBfs() throws Exception {
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,257)) {
            for(int n=2;n<=7;n++) {
                byte[] distances=DiametralExtensionAudit.bfs(n);long[] ranks=new long[distances.length];
                for(int i=0;i<ranks.length;i++)ranks[i]=i;
                for(int budget=0;budget<=RetentionPlan.upper(n);budget++) {
                    try(GpuPrefixSearch prefix=new GpuPrefixSearch(gpu,n,budget,ranks.length,3)) {
                        GpuPrefixSearch.Result result=prefix.search(ranks,ranks.length,null);
                        byte[] depths=result.depths();
                        for(int i=0;i<ranks.length;i++) {
                            String label="n="+n+" budget="+budget+" rank="+i;
                            if(depths[i]>=0)assertTrue(distances[i]<=budget,label);
                            // With budget <= 3, any successful exact sort is
                            // itself a prefix certificate. This also detects
                            // premature termination after an unsuccessful branch.
                            if(budget<=3)assertEquals(distances[i]<=budget,depths[i]>=0,label);
                        }
                        if(budget==RetentionPlan.upper(n))assertEquals(ranks.length,result.certified());
                    }
                }
            }
        }
    }
}
