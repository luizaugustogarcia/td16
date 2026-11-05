package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import jcuda.nvrtc.*;
import static jcuda.nvrtc.JNvrtc.*;

final class ExperimentResources {
    interface Source {String get() throws IOException;}
    static String ptx(String name,String architecture,Source source,String... definitions) throws IOException {
        String directory=System.getProperty("extension.cudaPtxDir");
        if(directory!=null) {
            String capability=architecture.substring(architecture.indexOf("compute_")+8);
            Path file=Path.of(directory).resolve(name+"-compute_"+capability+".ptx");
            System.out.printf("CUDA_MODULE backend=CMake kernel=%s file=%s%n",name,file);
            return Files.readString(file);
        }
        JNvrtc.setExceptionsEnabled(true);nvrtcProgram program=new nvrtcProgram();
        nvrtcCreateProgram(program,source.get(),name+".cu",0,null,null);
        try {
            List<String> options=new ArrayList<>(List.of(architecture,"--std=c++11","--generate-line-info"));options.addAll(Arrays.asList(definitions));
            try{nvrtcCompileProgram(program,options.size(),options.toArray(String[]::new));}
            catch(RuntimeException error){String[] log={null};nvrtcGetProgramLog(program,log);throw new IllegalStateException(log[0],error);}
            String[] ptx={null};nvrtcGetPTX(program,ptx);return ptx[0];
        }finally{nvrtcDestroyProgram(program);}
    }
    static String read(String name) throws IOException {
        try (var stream = ExperimentResources.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IOException("Missing resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
