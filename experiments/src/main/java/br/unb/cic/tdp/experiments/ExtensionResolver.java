package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import jcuda.driver.*;
import static jcuda.driver.JCudaDriver.*;

/** GPU-only production decisions, aggregated by budget and dispatched to independent CUDA slots. */
final class ExtensionResolver implements AutoCloseable {
    interface DeviceFactory {
        int available() throws Exception;
        GpuStreamingCounter open(int ordinal,SmallTables tables,int quantum) throws Exception;
    }
    private static final DeviceFactory CUDA=new DeviceFactory() {
        public int available(){return GpuDiametralFilter.deviceCount();}
        public GpuStreamingCounter open(int ordinal,SmallTables tables,int quantum) throws Exception{return new GpuStreamingCounter(tables,quantum,ordinal);}
    };
    private static final class Device {
        final int ordinal;
        final GpuStreamingCounter gpu;
        final AtomicLong queries=new AtomicLong(),batches=new AtomicLong(),workspaceEvictions=new AtomicLong();
        long slotBudget;
        Device(int ordinal,GpuStreamingCounter gpu){this.ordinal=ordinal;this.gpu=gpu;}
    }
    private static final class DecisionComplete extends RuntimeException {
        DecisionComplete(){super(null,null,false,false);}
    }
    private record Request(int budget,int count,long[] ranks,byte[] weights){}
    private static final Request FINISH=new Request(-1,0,null,null);
    final int degree,floor,payloadBytes,batchRecords,producerBatchRecords;
    final AsyncCanonicalWriter writer;
    final SmallTables tables;
    final AtomicLong cpuQueries=new AtomicLong(),gpuQueries=new AtomicLong(),gpuBatches=new AtomicLong();
    private final ArrayBlockingQueue<Request> pending;
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    private final CompletableFuture<Void> ready=new CompletableFuture<>(),ended=new CompletableFuture<>();
    private final Thread worker;
    private volatile List<Device> devices=List.of();
    private final DeviceFactory factory;
    private int deviceLeases;
    private boolean closingDevice;
    private volatile boolean finishing;
    ExtensionResolver(int degree,int floor,AsyncCanonicalWriter writer,SmallTables tables,
                      boolean gpu,int batchRecords,int queueBatches,double vramFraction,Path dataDirectory) throws IOException {
        this(degree,floor,writer,tables,gpu,batchRecords,queueBatches,vramFraction,dataDirectory,CUDA);
    }
    ExtensionResolver(int degree,int floor,AsyncCanonicalWriter writer,SmallTables tables,
                      boolean gpu,int batchRecords,int queueBatches,double vramFraction,Path dataDirectory,DeviceFactory factory) throws IOException {
        this.degree=degree;this.floor=floor;this.writer=writer;this.tables=tables;this.batchRecords=batchRecords;
        producerBatchRecords=Integer.getInteger("extension.gpuProducerBatchRecords",Math.min(batchRecords,4096));
        if(producerBatchRecords<1||producerBatchRecords>batchRecords)throw new IllegalArgumentException("GPU producer batch must be 1..gpuBatchRecords");
        this.factory=factory;
        payloadBytes=PackedPermutation5.bytes(degree+1);pending=new ArrayBlockingQueue<>(queueBatches);
        if(gpu) {
            worker=Thread.ofPlatform().name("extension-gpu-dispatch-n"+degree).start(()->gpuLoop(vramFraction,dataDirectory));
            await(ready,"initialize the extension GPU slots");
        } else {worker=null;ready.complete(null);ended.complete(null);}
    }
    void check() throws IOException {writer.check();Throwable error=failure.get();if(error!=null)throw new IOException("Extension GPU failed",error);}
    private void put(ArrayBlockingQueue<Request> queue,Request request) throws IOException {
        try{while(true){check();if(queue.offer(request,100,TimeUnit.MILLISECONDS))return;}}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Interrupted queuing GPU work",interrupted);}
    }
    Pending buffer(ExactSorter cpu){return new Pending(cpu);}
    int deviceCount(){return devices.size();}
    Map<Integer,Long> deviceQueries(){Map<Integer,Long> counts=new LinkedHashMap<>();for(Device device:devices)counts.put(device.ordinal,device.queries.get());return counts;}
    long workspaceEvictions(){return devices.stream().mapToLong(d->d.workspaceEvictions.get()).sum();}
    GpuStreamingCounter device(){if(worker==null||devices.isEmpty())throw new IllegalStateException("GPU is not ready");return devices.getFirst().gpu;}
    synchronized GpuStreamingCounter retainDevice(){return retainDevice(0);}
    synchronized GpuStreamingCounter retainDevice(int index){if(closingDevice)throw new IllegalStateException("GPU is closing");GpuStreamingCounter gpu=devices.get(index).gpu;deviceLeases++;return gpu;}
    synchronized void releaseDevice(){deviceLeases--;notifyAll();}
    final class Pending implements AutoCloseable {
        final ExactSorter cpu;
        long[] ranks;
        byte[] weights;
        int budget=-1,count;
        final int producerRecords=producerBatchRecords;
        Pending(ExactSorter cpu){this.cpu=cpu;}
        void append(byte[] word,byte[] omega,int q,int weight) throws IOException {
            appendRank(SmallTables.rank(word,degree),q,weight);
        }
        void appendRank(long rank,int q,int weight) throws IOException {
            if(writer.hasMatch())return;
            check();if(count>0&&budget!=q)flush();
            if(ranks==null){ranks=new long[producerRecords];weights=new byte[producerRecords];}
            budget=q;ranks[count]=rank;weights[count]=(byte)weight;
            if(++count==producerRecords)flush();
        }
        void flush() throws IOException {
            if(writer.hasMatch()){ranks=null;weights=null;count=0;return;}
            if(count==0)return;check();Request request=new Request(budget,count,ranks,weights);
            ranks=null;weights=null;count=0;
            if(worker==null)resolveCpu(request,cpu);else put(pending,request);
        }
        public void close() throws IOException {flush();}
    }
    private void resolveCpu(Request request,ExactSorter cpu) throws IOException {
        if(worker!=null)throw new IllegalStateException("CPU exact search is forbidden in GPU mode");
        int resolved=0;
        try(AsyncCanonicalWriter.Buffer output=writer.buffer()) {
            for(int i=0;i<request.count;i++) {
                if(writer.hasMatch())break;
                int distance=request.budget+(cpu.canSort(request.ranks[i],degree,request.budget)?0:1);
                resolved++;
                if(distance>=floor)output.appendRank(request.ranks[i],distance,request.weights[i]&255);
            }
        }
        cpuQueries.addAndGet(resolved);
    }
    private final class Assembly {
        final int budget;
        long[] ranks=new long[batchRecords];byte[] weights=new byte[batchRecords];int count;
        Assembly(int budget){this.budget=budget;}
        Request drain(){Request result=new Request(budget,count,ranks,weights);
            ranks=new long[batchRecords];weights=new byte[batchRecords];count=0;return result;}
    }
    private void gpuLoop(double vramFraction,Path dataDirectory) {
        List<Device> opened=new ArrayList<>();ExecutorService slots=null;
        try {
            GpuSettings settings=GpuSettings.system();settings.validateAvailable(factory.available());
            int generationStreams=GpuSettings.generationStreams();
            int quantum=Integer.getInteger("extension.gpuQuantum",4096),parentBatch=Integer.getInteger("extension.gpuParentBatch",4096);
            if(parentBatch<1)throw new IllegalArgumentException("GPU parent batch must be positive");
            for(int ordinal:settings.layout().devices()) {
                GpuStreamingCounter gpu=factory.open(ordinal,tables,quantum);Device device=new Device(ordinal,gpu);opened.add(device);
                long workspaceReserve=Math.multiplyExact((long)settings.layout().slotsPerDevice(),GpuStreamingCounter.fixedBatchBytes(degree)+
                    batchRecords*GpuStreamingCounter.bytesPerJob(degree,RetentionPlan.upper(degree)+1));
                long generationReserve=Math.multiplyExact(generationStreams,GpuCandidateGenerator.deviceBytes(degree,parentBatch,false));
                long cacheReserve=Math.addExact(Math.addExact(generationReserve,workspaceReserve),256L<<20);
                GpuCacheBudget cacheBudget=new GpuCacheBudget(cacheReserve,vramFraction);
                int parentDegree=degree-1,parentFloor=Math.max(0,floor-1),parentCeiling=Math.min(floor,RetentionPlan.upper(parentDegree));
                gpu.loadCompactTables(dataDirectory,parentDegree,parentFloor,parentCeiling,degree,cacheBudget);
                long membershipReserve=gpu.membershipReserve(dataDirectory,degree-1,degree,cacheBudget);
                long canonicalBloomReserve=gpu.canonicalBloomReserve(dataDirectory,parentDegree,parentFloor,parentCeiling,cacheBudget.reserve(membershipReserve));
                GpuCacheBudget patternBudget=cacheBudget.reserve(Math.addExact(membershipReserve,canonicalBloomReserve));
                List<Device> sources=opened.subList(0,opened.size()-1).stream().sorted(Comparator.comparingInt((Device d)->d.gpu.tableDegree).reversed()).toList();
                for(Device source:sources)if(gpu.copyPatternTablesFrom(source.gpu,patternBudget))break;
                // A larger GPU may grow beyond a cache copied from a smaller GPU.
                gpu.loadPersistedTables(dataDirectory,degree-1,patternBudget);
                if(Boolean.getBoolean("extension.verifyGpuPatterns")&&gpu.tableDegree>0)
                    try(CanonicalLayerStore source=new LayerCatalog(dataDirectory).open(gpu.tableDegree,gpu.patternFloors[gpu.tableDegree],RetentionPlan.upper(gpu.tableDegree))) {
                        gpu.verifyStoredHistogram(source.manifest);
                    }
                for(Device source:sources)if(gpu.copyMembershipFrom(source.gpu,cacheBudget))break;
                // A completed higher layer also accelerates a later support layer
                // at the same degree (notably E(14,7) after D(14)).
                gpu.loadMembershipTable(dataDirectory,degree,cacheBudget);
                if(gpu.parentLookupKind(parentDegree,parentFloor)==0&&canonicalBloomReserve>0)
                    gpu.loadCanonicalBloom(dataDirectory,parentDegree,parentFloor,parentCeiling,cacheBudget);
                gpu.activate();long[] free={0},total={0};cuMemGetInfo(free,total);
                boolean dense=gpu.parentLookupKind(degree-1,Math.max(0,floor-1))!=0;
                long generationBytes=Math.multiplyExact(generationStreams,GpuCandidateGenerator.deviceBytes(degree,parentBatch,dense));
                device.slotBudget=settings.slotBudget(free[0],generationBytes,vramFraction);
                GpuStreamingCounter.capacityForBudget(degree,RetentionPlan.upper(degree)+1,1,device.slotBudget);
                System.out.printf("GPU_DEVICE ordinal=%d name=%s slots=%d slot_budget_bytes=%d generation_streams=%d generation_reserved_bytes=%d pattern_degree=%d pattern_bytes=%d membership_degree=%d membership_bytes=%d canonical_bloom_bytes=%d cache_workspace_reserved_bytes=%d%n",ordinal,gpu.context.deviceName,settings.layout().slotsPerDevice(),device.slotBudget,generationStreams,generationBytes,gpu.tableDegree,gpu.tableBytes,gpu.membershipDegree(),gpu.membership==null?0:gpu.membership.bytes,gpu.canonicalBloom==null?0:gpu.canonicalBloom.bytes,cacheReserve);
                System.out.printf("GPU_COMPACT_DEVICE ordinal=%d compact_bytes=%d parent_lookup_kind=%d%n",ordinal,gpu.compactTables==null?0:gpu.compactTables.bytes,gpu.parentLookupKind(parentDegree,parentFloor));
            }
            devices=List.copyOf(opened);int slotCount=settings.layout().totalSlots();
            slots=Executors.newFixedThreadPool(slotCount,Thread.ofPlatform().name("extension-gpu-slot-",0).factory());
            ArrayBlockingQueue<Request> jobs=new ArrayBlockingQueue<>(Math.max(Math.multiplyExact(slotCount,2),pending.remainingCapacity()));
            Future<?>[] workers=new Future<?>[slotCount];
            for(int s=0;s<slotCount;s++) {
                int slot=s;
                int ordinal=settings.layout().deviceForSlot(s);Device device=opened.stream().filter(d->d.ordinal==ordinal).findFirst().orElseThrow();
                workers[s]=slots.submit(()->runSlot(device,jobs,slot));
            }
            int minimumBatch=Integer.getInteger("extension.gpuMinBatchRecords",Math.min(4096,batchRecords));
            if(minimumBatch<1||minimumBatch>batchRecords)throw new IllegalArgumentException("extension.gpuMinBatchRecords must be 1..gpuBatchRecords");
            System.out.printf("GPU_SLOTS n=%d devices=%s slots_per_device=%d total_slots=%d target_batch=%d producer_batch=%d minimum_batch=%d CPU_sorting=false quantum=%d%n",degree,Arrays.toString(settings.layout().devices()),settings.layout().slotsPerDevice(),slotCount,batchRecords,producerBatchRecords,minimumBatch,quantum);
            ready.complete(null);Assembly[] groups=new Assembly[12];
            while(true) {
                check();Request request=pending.poll(20,TimeUnit.MILLISECONDS);
                if(request==FINISH)break;
                if(writer.hasMatch()){for(Assembly group:groups)if(group!=null)group.count=0;continue;}
                // Sparse hard roots are extraordinarily inefficient as tiny
                // CUDA waves. Retain them until a useful batch forms; FINISH
                // below still drains every residual root exactly.
                if(request==null){for(Assembly group:groups)if(group!=null&&group.count>=minimumBatch)put(jobs,group.drain());continue;}
                Assembly group=groups[request.budget];if(group==null)groups[request.budget]=group=new Assembly(request.budget);
                for(int first=0;first<request.count;) {
                    int take=Math.min(batchRecords-group.count,request.count-first),offset=group.count;
                    System.arraycopy(request.ranks,first,group.ranks,offset,take);
                    System.arraycopy(request.weights,first,group.weights,offset,take);group.count+=take;first+=take;
                    if(group.count==batchRecords)put(jobs,group.drain());
                }
            }
            if(!writer.hasMatch())for(Assembly group:groups)if(group!=null&&group.count>0) {
                // A final batch smaller than one CUDA block would otherwise
                // execute on a single SM. Shard it across the existing slot
                // streams so independent serial DFS roots occupy more SMs.
                int shards=Math.min(slotCount,group.count),total=group.count;
                for(int shard=0;shard<shards;shard++) {
                    int first=total*shard/shards,end=total*(shard+1)/shards,count=end-first;
                    put(jobs,new Request(group.budget,count,
                        Arrays.copyOfRange(group.ranks,first,end),
                        Arrays.copyOfRange(group.weights,first,end)));
                }
                group.count=0;
            }
            for(int s=0;s<slotCount;s++)put(jobs,FINISH);
            for(Future<?> task:workers)task.get();check();
        }catch(Throwable error){failure.compareAndSet(null,error);}
        finally {
            boolean interrupted=Thread.interrupted();
            if(slots!=null){slots.shutdownNow();while(!slots.isTerminated())try{slots.awaitTermination(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){interrupted=true;}}
            synchronized(this){closingDevice=true;while(deviceLeases>0)try{wait(100);}catch(InterruptedException ignored){interrupted=true;}}
            for(int i=opened.size()-1;i>=0;i--) {
                Device device=opened.get(i);
                try{device.gpu.close();}catch(Throwable error){failure.compareAndSet(null,error);}
                System.out.printf("GPU_DEVICE_WORK ordinal=%d GPU_queries=%d GPU_batches=%d workspace_evictions=%d%n",device.ordinal,device.queries.get(),device.batches.get(),device.workspaceEvictions.get());
            }
            Throwable error=failure.get();
            if(error==null){ready.complete(null);ended.complete(null);}else{ready.completeExceptionally(error);ended.completeExceptionally(error);}
            if(interrupted)Thread.currentThread().interrupt();
        }
    }
    private void runSlot(Device device,ArrayBlockingQueue<Request> jobs,int slot) {
        Thread.currentThread().setName("extension-gpu-device-"+device.ordinal+"-slot-"+slot);
        GpuStreamingCounter gpu=device.gpu;
        CUstream stream=new CUstream();Map<Integer,GpuStreamingCounter.Batch> batches=new LinkedHashMap<>(4,0.75f,true);boolean created=false;
        long allocated=0;
        try {
            cuCtxSetCurrent(gpu.context.context);cuStreamCreate(stream,CUstream_flags.CU_STREAM_NON_BLOCKING);created=true;
            while(true) {
                Request request=jobs.take();if(request==FINISH)break;check();int q=request.budget;
                if(writer.hasMatch())continue;
                GpuStreamingCounter.Batch active=batches.get(q);
                if(active==null) {
                    int capacity=GpuStreamingCounter.capacityForBudget(degree,q+1,batchRecords,device.slotBudget);
                    long needed=GpuStreamingCounter.fixedBatchBytes(degree)+capacity*GpuStreamingCounter.bytesPerJob(degree,q+1);
                    while(allocated+needed>device.slotBudget&&!batches.isEmpty()) {
                        var iterator=batches.entrySet().iterator();GpuStreamingCounter.Batch expired=iterator.next().getValue();iterator.remove();allocated-=expired.allocatedBytes;expired.close();
                        device.workspaceEvictions.incrementAndGet();
                    }
                    // Extension generation has already checked every root
                    // deletion at this same budget; repeating the direct
                    // deletion prepass inside DFS is necessarily inconclusive.
                    active=gpu.new Batch(degree,q+1,q+1,capacity,stream,gpu.slackMoveFilter,false,gpu.rootMovePrepass(q));
                    try{active.ensureStates(capacity);}catch(Throwable error){active.close();throw error;}
                    batches.put(q,active);allocated+=active.allocatedBytes;
                    if(allocated>device.slotBudget)throw new IllegalStateException("GPU slot exceeded its workspace budget");
                }
                try(AsyncCanonicalWriter.Buffer output=writer.buffer()) {
                    for(int first=0;first<request.count;first+=active.capacity) {
                        if(writer.hasMatch())break;
                        int count=Math.min(active.capacity,request.count-first);
                        long[] ranks=first==0?request.ranks:Arrays.copyOfRange(request.ranks,first,first+count);
                        byte[] decisions=active.classifyRanks(ranks,count,()->{if(writer.hasMatch())throw new DecisionComplete();try{check();}catch(IOException error){throw new java.io.UncheckedIOException(error);}});
                        for(int i=0;i<count;i++) {
                            int distance=q+decisions[i];
                            if(distance>=floor)output.appendRank(request.ranks[first+i],distance,request.weights[first+i]&255);
                        }
                        gpuQueries.addAndGet(count);gpuBatches.incrementAndGet();
                        device.queries.addAndGet(count);device.batches.incrementAndGet();
                    }
                }catch(DecisionComplete complete){if(!writer.hasMatch())throw complete;}
            }
        }catch(Throwable error){failure.compareAndSet(null,error);throw new CompletionException(error);}
        finally {
            try{gpu.activate();for(GpuStreamingCounter.Batch batch:batches.values())batch.close();if(created)cuStreamDestroy(stream);}
            catch(Throwable error){failure.compareAndSet(null,error);throw error;}
        }
    }
    void finish() throws IOException {
        if(finishing)return;finishing=true;check();
        if(worker!=null){put(pending,FINISH);await(ended,"finish extension GPU slots");check();}
    }
    private static void await(CompletableFuture<?> future,String action) throws IOException {
        try{future.get();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Interrupted trying to "+action,interrupted);}
        catch(ExecutionException error){throw new IOException("Cannot "+action,error.getCause());}
    }
    public void close() throws IOException {
        if(worker!=null) {
            if(!ended.isDone())worker.interrupt();
            try{worker.join();}catch(InterruptedException interrupted){worker.interrupt();Thread.currentThread().interrupt();throw new IOException("Interrupted closing extension GPU",interrupted);}
            if(finishing)check();
        }
    }
}
