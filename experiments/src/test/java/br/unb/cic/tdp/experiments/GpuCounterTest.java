package br.unb.cic.tdp.experiments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="gpuTests",matches="true")
class GpuCounterTest {
    @Test void completeGpuSearchMatchesKnownSmallGraphs() throws Exception { GpuCounterAudit.run(); }
    @ParameterizedTest
    @ValueSource(ints={1,2,3})
    void exhaustionSurvivesEveryKernelYield(int quantum) throws Exception {
        try(GpuStreamingCounter gpu=new GpuStreamingCounter(null,quantum)) {
            for(int n=2;n<=6;n++) {
                int diameter=CountsRun.LOW[n];
                try(GpuStreamingCounter.Batch batch=gpu.new Batch(n,diameter,diameter,DiametralExtensionAudit.FACT[n])) {
                    long[] counts=batch.count(0,DiametralExtensionAudit.FACT[n],null);
                    int[] histogram=DiametralExtensionAudit.EXPECTED[n];
                    assertEquals(histogram[histogram.length-1],counts[1],"quantum="+quantum+" n="+n);
                }
            }
            try(GpuStreamingCounter.Batch batch=gpu.new Batch(5,2,3,120)) {
                long[] counts=batch.count(0,120,null);
                assertEquals(68,counts[1]);assertEquals(31,counts[2]);
            }
        }
        System.out.println("PASS: root exhaustion and two-budget transitions with GPU quantum="+quantum);
    }
}
