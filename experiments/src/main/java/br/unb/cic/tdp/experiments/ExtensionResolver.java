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
    private record Request(int budget,int count,long[] ranks,byte[] omegas,byte[] weights){}
    private static final Request FINISH=new Request(-1,0,null,null,null);
    final int degree,floor,payloadBytes,batchRecords;
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
        byte[] omegas,weights;
        int budget=-1,count;
        final int producerRecords=Math.min(batchRecords,256);
        Pending(ExactSorter cpu){this.cpu=cpu;}
        void append(byte[] word,byte[] omega,int q,int weight) throws IOException {
            byte[] packed=PackedPermutation5.encode(omega,degree+1);
            appendRank(SmallTables.rank(word,degree),packed,0,q,weight);
        }
        void appendRank(long rank,byte[] packed,int offset,int q,int weight) throws IOException {
            if(writer.hasMatch())return;
            check();if(count>0&&budget!=q)flush();
            if(ranks==null){ranks=new long[producerRecords];omegas=new byte[producerRecords*payloadBytes];weights=new byte[producerRecords];}
            budget=q;ranks[count]=rank;weights[count]=(byte)weight;
            System.arraycopy(packed,offset,omegas,count*payloadBytes,payloadBytes);
            if(++count==producerRecords)flush();
        }
        void flush() throws IOException {
            if(writer.hasMatch()){ranks=null;omegas=null;weights=null;count=0;return;}
            if(count==0)return;check();Request request=new Request(budget,count,ranks,omegas,weights);
            ranks=null;omegas=null;weights=null;count=0;
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
                if(distance>=floor)output.appendPacked(request.omegas,i*payloadBytes,distance,request.weights[i]&255);
            }
        }
        cpuQueries.addAndGet(resolved);
    }
    private final class Assembly {
        final int budget;
        long[] ranks=new long[batchRecords];byte[] omegas=new byte[batchRecords*payloadBytes],weights=new byte[batchRecords];int count;
        Assembly(int budget){this.budget=budget;}
        Request drain(){Request result=new Request(budget,count,ranks,omegas,weights);
            ranks=new long[batchRecords];omegas=new byte[batchRecords*payloadBytes];weights=new byte[batchRecords];count=0;return result;}
    }
    private void gpuLoop(double vramFraction,Path dataDirectory) {
        List<Device> opened=new ArrayList<>();ExecutorService slots=null;
        try {
            GpuSettings settings=GpuSettings.system();settings.validateAvailable(factory.available());
            int quantum=Integer.getInteger("extension.gpuQuantum",4096),parentBatch=Integer.getInteger("extension.gpuParentBatch",4096);
            if(parentBatch<1)throw new IllegalArgumentException("GPU parent batch must be positive");
            for(int ordinal:settings.layout().devices()) {
                GpuStreamingCounter gpu=factory.open(ordinal,tables,quantum);Device device=new Device(ordinal,gpu);opened.add(device);
                List<Device> sources=opened.subList(0,opened.size()-1).stream().sorted(Comparator.comparingInt((Device d)->d.gpu.tableDegree).reversed()).toList();
                for(Device source:sources)if(gpu.copyPatternTablesFrom(source.gpu))break;
                // A larger GPU may grow beyond a cache copied from a smaller GPU.
                gpu.loadPersistedTables(dataDirectory,Math.min(13,degree-1));
                if(Boolean.getBoolean("extension.verifyGpuPatterns")&&gpu.tableDegree>0)
                    try(CanonicalLayerStore source=new LayerCatalog(dataDirectory).open(gpu.tableDegree,gpu.patternFloors[gpu.tableDegree],RetentionPlan.upper(gpu.tableDegree))) {
                        gpu.verifyStoredHistogram(source.manifest);
                    }
                long workspaceReserve=settings.layout().slotsPerDevice()*(GpuStreamingCounter.fixedBatchBytes(degree)+
                    batchRecords*GpuStreamingCounter.bytesPerJob(degree,RetentionPlan.upper(degree)+1));
                long cacheReserve=GpuCandidateGenerator.deviceBytes(degree,parentBatch,false)+workspaceReserve+(256L<<20);
                for(Device source:sources)if(gpu.copyMembershipFrom(source.gpu,cacheReserve))break;
                // A completed higher layer also accelerates a later support layer
                // at the same degree (notably E(14,7) after D(14)).
                gpu.loadMembershipTable(dataDirectory,degree,cacheReserve);
                gpu.activate();long[] free={0},total={0};cuMemGetInfo(free,total);
                boolean dense=gpu.parentLookupKind(degree-1,Math.max(0,floor-1))!=0;
                long generationBytes=GpuCandidateGenerator.deviceBytes(degree,parentBatch,dense);
                device.slotBudget=settings.slotBudget(free[0],generationBytes,vramFraction);
                GpuStreamingCounter.capacityForBudget(degree,RetentionPlan.upper(degree)+1,1,device.slotBudget);
                System.out.printf("GPU_DEVICE ordinal=%d name=%s slots=%d slot_budget_bytes=%d generation_reserved_bytes=%d pattern_degree=%d membership_degree=%d membership_bytes=%d%n",ordinal,gpu.context.deviceName,settings.layout().slotsPerDevice(),device.slotBudget,generationBytes,gpu.tableDegree,gpu.membershipDegree(),gpu.membership==null?0:gpu.membership.bytes);
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
            System.out.printf("GPU_SLOTS n=%d devices=%s slots_per_device=%d total_slots=%d target_batch=%d CPU_sorting=false quantum=%d%n",degree,Arrays.toString(settings.layout().devices()),settings.layout().slotsPerDevice(),slotCount,batchRecords,quantum);
            ready.complete(null);Assembly[] groups=new Assembly[12];
            while(true) {
                check();Request request=pending.poll(20,TimeUnit.MILLISECONDS);
                if(request==FINISH)break;
                if(writer.hasMatch()){for(Assembly group:groups)if(group!=null)group.count=0;continue;}
                if(request==null){for(Assembly group:groups)if(group!=null&&group.count>0)put(jobs,group.drain());continue;}
                Assembly group=groups[request.budget];if(group==null)groups[request.budget]=group=new Assembly(request.budget);
                for(int first=0;first<request.count;) {
                    int take=Math.min(batchRecords-group.count,request.count-first),offset=group.count;
                    System.arraycopy(request.ranks,first,group.ranks,offset,take);
                    System.arraycopy(request.omegas,first*payloadBytes,group.omegas,offset*payloadBytes,take*payloadBytes);
                    System.arraycopy(request.weights,first,group.weights,offset,take);group.count+=take;first+=take;
                    if(group.count==batchRecords)put(jobs,group.drain());
                }
            }
            if(!writer.hasMatch())for(Assembly group:groups)if(group!=null&&group.count>0)put(jobs,group.drain());
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
                    active=gpu.new Batch(degree,q+1,q+1,capacity,stream);
                    try{active.ensureStates(capacity);}catch(Throwable error){active.close();throw error;}
                    batches.put(q,active);allocated+=active.allocatedBytes;
                    if(allocated>device.slotBudget)throw new IllegalStateException("GPU slot exceeded its workspace budget");
                }
                try(AsyncCanonicalWriter.Buffer output=writer.buffer()) {
                    for(int first=0;first<request.count;first+=active.capacity) {
                        if(writer.hasMatch())break;
                        int count=Math.min(active.capacity,request.count-first);
                        long[] ranks=first==0?request.ranks:Arrays.copyOfRange(request.ranks,first,first+count);
                        byte[] decisions=active.classifyRanks(ranks,count,()->{if(writer.hasMatch())throw new DecisionComplete();try{check();}catch(IOException error){throw new java.io.UncheckedIOException(error);}},request.omegas,first*payloadBytes);
                        for(int i=0;i<count;i++) {
                            int distance=q+decisions[i];
                            if(distance>=floor)output.appendPacked(request.omegas,(first+i)*payloadBytes,distance,request.weights[first+i]&255);
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
