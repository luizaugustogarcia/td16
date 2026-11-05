package br.unb.cic.tdp.experiments;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExperimentResourcesTest {
    @TempDir Path root;
    @Test void precompiledKernelsNeverInvokeTheRuntimeCompiler() throws Exception {
        String previous=System.getProperty("extension.cudaPtxDir");
        System.setProperty("extension.cudaPtxDir",root.toString());
        try {
            Files.writeString(root.resolve("streaming-count-soa-compute_120.ptx"),"test PTX");
            assertEquals("test PTX",ExperimentResources.ptx("streaming-count-soa","--gpu-architecture=compute_120",
                ()->{throw new AssertionError("Precompiled mode must not initialize NVRTC");}));
            assertThrows(NoSuchFileException.class,()->ExperimentResources.ptx("missing","--gpu-architecture=compute_120",
                ()->{throw new AssertionError("Missing PTX must not fall back to NVRTC");}));
        } finally {
            if(previous==null)System.clearProperty("extension.cudaPtxDir");else System.setProperty("extension.cudaPtxDir",previous);
        }
    }
}
