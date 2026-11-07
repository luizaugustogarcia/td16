package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Independently leased generation streams per GPU, shared by the bounded host pipeline. */
final class GpuCandidatePool implements AutoCloseable {
    final List<GpuCandidateGenerator> generators=new ArrayList<>();
    private final ArrayBlockingQueue<GpuCandidateGenerator> available;
    private boolean closed;
    private int producers;
    GpuCandidatePool(ExtensionResolver resolver,CanonicalLayerStore parents,int n,int floor,int capacity) {
        int streams=GpuSettings.generationStreams();
        available=new ArrayBlockingQueue<>(Math.multiplyExact(resolver.deviceCount(),streams),true);
        try {
            for(int index=0;index<resolver.deviceCount();index++) {
                for(int stream=0;stream<streams;stream++) {
                    GpuCandidateGenerator generator=new GpuCandidateGenerator(resolver,parents,n,floor,capacity,index);
                    generators.add(generator);available.add(generator);
                }
            }
        }catch(RuntimeException|Error error){try{close();}catch(Throwable cleanup){error.addSuppressed(cleanup);}throw error;}
    }
    void process(byte[] input,ExtensionRun.Stats stats,AsyncCanonicalWriter writer,ExtensionResolver resolver) throws IOException {
        process(input,stats,writer,resolver,false);
    }
    void process(byte[] input,ExtensionRun.Stats stats,AsyncCanonicalWriter writer,ExtensionResolver resolver,boolean rankRows) throws IOException {
        if(writer.hasMatch())return;
        GpuCandidateGenerator generator;
        try {
            while(true) {
                resolver.check();synchronized(this){if(closed)throw new IOException("GPU generation pool is closed");}
                generator=available.poll(100,TimeUnit.MILLISECONDS);if(generator==null)continue;
                synchronized(this){if(closed)throw new IOException("GPU generation pool is closed");producers++;}
                break;
            }
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("Interrupted waiting for a GPU generation stream",error);}
        GpuCandidateGenerator.Output packet;
        try {packet=generator.produce(input,stats,resolver,rankRows);}
        finally {
            synchronized(this){if(!closed)available.add(generator);producers--;notifyAll();}
        }
        if(packet!=null)generator.consume(packet,stats,writer,resolver);
    }
    public void close() {
        boolean interrupted=Thread.interrupted();
        synchronized(this) {
            if(closed){if(interrupted)Thread.currentThread().interrupt();return;}
            closed=true;
            while(producers>0)try{wait(100);}catch(InterruptedException error){interrupted=true;}
        }
        Throwable failure=null;
        for(int i=generators.size()-1;i>=0;i--)try{generators.get(i).close();}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        available.clear();if(interrupted)Thread.currentThread().interrupt();
        if(failure instanceof Error error)throw error;if(failure instanceof RuntimeException error)throw error;
        if(failure!=null)throw new IllegalStateException("Cannot close GPU generation streams",failure);
    }
}
