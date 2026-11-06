package br.unb.cic.tdp.experiments;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import jcuda.Pointer;
import jcuda.driver.*;
import static jcuda.driver.JCudaDriver.*;

/** Resumable complete CUDA DFS with per-input decisions and aggregate counters. */
final class GpuStreamingCounter implements AutoCloseable {
    final GpuDiametralFilter context;
    final CUmodule module=new CUmodule();
    final CUfunction filterRoots=new CUfunction(),initialize=new CUfunction(),advance=new CUfunction(),validateRoots=new CUfunction();
    final CUdeviceptr table=new CUdeviceptr(),offsets=new CUdeviceptr();
    private final CUdeviceptr emptyMembership=new CUdeviceptr();
    GpuMembershipTable membership;
    int tableDegree;
    final int[] patternFloors=new int[19];
    final int quantum;
    final boolean slackMoveFilter;
    final boolean rootDeletionPrepass;
    final boolean rootMovePrepass;
    final boolean oddCyclePrepass;
    final boolean rootMoveAutomatic;
    final int rootMoveMinBudget;
    boolean moduleReady,tableReady,offsetsReady;
    long tableBytes;
    GpuStreamingCounter(SmallTables tables) throws Exception {
        this(tables,64);
    }
    GpuStreamingCounter(SmallTables tables,int quantum) throws Exception {
        this(tables,quantum,0);
    }
    GpuStreamingCounter(SmallTables tables,int quantum,int deviceOrdinal) throws Exception {
        if(quantum<1)throw new IllegalArgumentException("GPU quantum must be positive");
        this.quantum=quantum;
        slackMoveFilter=Boolean.parseBoolean(System.getProperty("extension.slackMoveFilter","true"));
        rootDeletionPrepass=Boolean.parseBoolean(System.getProperty("extension.rootDeletionPrepass","true"));
        String rootMoves=System.getProperty("extension.rootMovePrepass","auto");
        if(!rootMoves.equals("auto")&&!rootMoves.equals("true")&&!rootMoves.equals("false"))
            throw new IllegalArgumentException("extension.rootMovePrepass must be auto, true, or false");
        rootMovePrepass=!rootMoves.equals("false");rootMoveAutomatic=rootMoves.equals("auto");
        rootMoveMinBudget=Integer.getInteger("extension.rootMoveMinBudget",8);
        if(rootMoveMinBudget<0||rootMoveMinBudget>11)throw new IllegalArgumentException("extension.rootMoveMinBudget must be 0..11");
        oddCyclePrepass=Boolean.parseBoolean(System.getProperty("extension.oddCyclePrepass","true"));
        context=new GpuDiametralFilter(deviceOrdinal);tableDegree=tables==null?-1:tables.degree;
        Arrays.fill(patternFloors,Integer.MAX_VALUE);
        if(tables!=null)Arrays.fill(patternFloors,0,tableDegree+1,0);
        try {
            boolean soa=Boolean.parseBoolean(System.getProperty("extension.gpuSoa","true"));
            String ptx=ExperimentResources.ptx("streaming-count-"+(soa?"soa":"aos"),context.architecture,
                ()->ExperimentResources.emitterCycleFunctions()+ExperimentResources.read("cuda/streaming-count.cu"),
                "--define-macro="+(soa?"SOA_STACK":"AOS_STACK"));
            cuModuleLoadData(module,ptx);moduleReady=true;
            verifyPatternLayout();
            cuModuleGetFunction(filterRoots,module,"filter_count_roots");
            cuModuleGetFunction(validateRoots,module,"validate_root_keys");
            cuModuleGetFunction(initialize,module,"initialize_count");cuModuleGetFunction(advance,module,"advance_count");
            byte[] data=tables==null?new byte[]{0}:tables.distances;
            long[] starts=new GpuPatternLayout(Math.max(0,tableDegree)).offsets;
            byte[] packed=new byte[Math.max(4,((data.length+7)/8)*4)];Arrays.fill(packed,(byte)0xff);
            for(int i=0;i<data.length;i++){int shift=(i&1)*4;packed[i>>1]=(byte)((packed[i>>1]&~(15<<shift))|((data[i]&15)<<shift));}
            cuMemAlloc(table,packed.length);tableReady=true;tableBytes=packed.length;cuMemcpyHtoD(table,Pointer.to(packed),packed.length);
            cuMemAlloc(offsets,GpuPatternLayout.METADATA_BYTES);offsetsReady=true;cuMemcpyHtoD(offsets,Pointer.to(starts),GpuPatternLayout.METADATA_BYTES);
            // Consumers use nonblocking streams, which do not wait for legacy-stream uploads.
            cuCtxSynchronize();
        }catch(Exception|Error error){close();throw error;}
    }
    static Pointer integer(int n){return Pointer.to(new int[]{n});}
    static Pointer number(long n){return Pointer.to(new long[]{n});}
    private void verifyPatternLayout() {
        try {
            CUdeviceptr width=new CUdeviceptr();long[] bytes={0};int[] actual={0};
            cuModuleGetGlobal(width,bytes,module,"pattern_offset_bytes");
            if(bytes[0]!=Integer.BYTES)throw new IllegalStateException("Invalid pattern offset metadata");
            cuMemcpyDtoH(Pointer.to(actual),width,Integer.BYTES);
            if(actual[0]!=Long.BYTES)throw new IllegalStateException("Expected 64-bit pattern offsets");
        }catch(RuntimeException error){
            throw new IllegalStateException("Incompatible CUDA pattern layout; rebuild the native PTX with -Pcuda-native",error);
        }
    }
    void activate(){cuCtxSetCurrent(context.context);}
    CUdeviceptr membershipBits(){return membership==null?emptyMembership:membership.bits;}
    int membershipDegree(){return membership==null?-1:membership.degree;}
    int membershipDistance(){return membership==null?0:membership.distance;}
    int parentLookupKind(int n,int floor) {
        if(Boolean.getBoolean("extension.forceDiskLookups"))return 0;
        if(tableDegree>=n&&patternFloors[n]<=floor)return 1;
        return membershipDegree()==n&&membershipDistance()==floor?2:0;
    }
    /** Protect a useful bitmap when its full four-bit rank table cannot fit.
     * Otherwise greedily loading a smaller dense table could crowd it out. */
    long membershipReserve(Path root,int patternRequested,int requested,GpuCacheBudget budget) throws Exception {
        String setting=System.getProperty("extension.gpuMembershipDegree","auto");
        boolean automatic=setting.equals("auto");int maximum=automatic?requested:Integer.parseInt(setting);
        if(maximum<0||maximum>18)throw new IllegalArgumentException("GPU membership degree must be auto or 0..18");
        activate();long[] free={0},total={0};cuMemGetInfo(free,total);LayerCatalog catalog=new LayerCatalog(root);
        for(int degree=maximum;degree>0;degree--) {
            if(degree<=requested&&catalog.has(degree,RetentionPlan.upper(degree))) {
                if(degree<=patternRequested&&degree<=GpuPatternLayout.configuredDegree()&&
                    budget.fits(new GpuPatternLayout(degree).bytes,free[0],tableBytes))return 0;
                long bytes=GpuMembershipTable.bytesForDegree(degree);
                if(budget.fits(bytes,free[0],0))return bytes;
            }
            if(!automatic)break;
        }
        return 0;
    }
    void loadMembershipTable(Path root,int requested,long reserve) throws Exception {
        loadMembershipTable(root,requested,new GpuCacheBudget(reserve,0.95));
    }
    void loadMembershipTable(Path root,int requested,GpuCacheBudget budget) throws Exception {
        String setting=System.getProperty("extension.gpuMembershipDegree","auto");
        boolean automatic=setting.equals("auto");int maximum=automatic?requested:Integer.parseInt(setting);
        if(maximum<0||maximum>18)throw new IllegalArgumentException("GPU membership degree must be auto or 0..18");
        // Try the nearest useful completed degree first. Larger ranks are useful
        // both to parent lookups and to distance-preserving DFS contractions.
        for(int degree=maximum;degree>0;degree--) {
            boolean covered=tableDegree>=degree&&patternFloors[degree]!=Integer.MAX_VALUE;
            if(!covered&&degree<=requested&&(membership==null||degree>membership.degree)) {
                GpuMembershipTable loaded=GpuMembershipTable.load(this,root,degree,budget);
                if(loaded!=null){if(membership!=null)membership.close();membership=loaded;return;}
            }
            if(!automatic)return;
        }
    }
    boolean copyMembershipFrom(GpuStreamingCounter source,long reserve) throws Exception {
        return copyMembershipFrom(source,new GpuCacheBudget(reserve,0.95));
    }
    boolean copyMembershipFrom(GpuStreamingCounter source,GpuCacheBudget budget) throws Exception {
        if(membership!=null)return true;
        if(source.membership==null||tableDegree>=source.membership.degree&&patternFloors[source.membership.degree]!=Integer.MAX_VALUE)return false;
        membership=source.membership.copyTo(this,budget);return membership!=null;
    }
    boolean rootMovePrepass(int budget){return rootMovePrepass&&(!rootMoveAutomatic||budget>=rootMoveMinBudget);}
    boolean oddCyclePrepass(int n,int budget){return oddCyclePrepass&&n==18&&budget>=10;}
    /** Context-safe staged copies avoid rebuilding the same pattern table on every GPU. */
    boolean copyPatternTablesFrom(GpuStreamingCounter source) {
        return copyPatternTablesFrom(source,new GpuCacheBudget(0,0.90));
    }
    boolean copyPatternTablesFrom(GpuStreamingCounter source,GpuCacheBudget budget) {
        if(source==this)return true;
        activate();long[] free={0},total={0};cuMemGetInfo(free,total);
        if(source.tableDegree>GpuPatternLayout.configuredDegree()||!budget.fits(source.tableBytes,free[0],tableBytes))return false;
        long[] starts=new long[2*GpuPatternLayout.DEGREES];source.activate();cuMemcpyDtoH(Pointer.to(starts),source.offsets,GpuPatternLayout.METADATA_BYTES);
        activate();cuMemFree(table);tableReady=false;cuMemAlloc(table,source.tableBytes);tableReady=true;tableBytes=source.tableBytes;
        Pointer staging=new Pointer();long chunk=Math.min(4L<<20,tableBytes);cuMemHostAlloc(staging,chunk,CU_MEMHOSTALLOC_PORTABLE);
        try {
            for(long offset=0;offset<tableBytes;offset+=chunk) {
                long bytes=Math.min(chunk,tableBytes-offset);source.activate();cuMemcpyDtoH(staging,source.table.withByteOffset(offset),bytes);
                activate();cuMemcpyHtoD(table.withByteOffset(offset),staging,bytes);
            }
            cuMemcpyHtoD(offsets,Pointer.to(starts),GpuPatternLayout.METADATA_BYTES);tableDegree=source.tableDegree;
            System.arraycopy(source.patternFloors,0,patternFloors,0,patternFloors.length);
            cuCtxSynchronize();
        }finally{activate();cuMemFreeHost(staging);}
        System.out.printf("GPU_PATTERN_COPY device=%d source_device=%d degree=%d bytes=%d%n",context.deviceOrdinal,source.context.deviceOrdinal,tableDegree,tableBytes);
        return true;
    }
    /** Build exact pattern tables on the GPU from completed canonical stores, including all orbit members. */
    void loadPersistedTables(Path root,int requested) throws Exception {
        loadPersistedTables(root,requested,new GpuCacheBudget(0,0.90));
    }
    void loadPersistedTables(Path root,int requested,GpuCacheBudget budget) throws Exception {
        activate();
        int maximum=0;long[] free={0},total={0};cuMemGetInfo(free,total);
        LayerCatalog catalog=new LayerCatalog(root);
        int patternHorizon=Integer.getInteger("benchmark.patternHorizon",0);
        RetentionPlan patternPlan=patternHorizon==0?null:new RetentionPlan(patternHorizon);
        int limit=GpuPatternLayout.configuredDegree();
        if(patternPlan!=null&&patternHorizon<Math.min(limit,requested))throw new IllegalArgumentException("Pattern horizon must cover the requested table degree");
        for(int n=1;n<=Math.min(limit,requested);n++){
            if(catalog.tailFloor(n)<0)continue;
            if(!budget.fits(new GpuPatternLayout(n).bytes,free[0],tableBytes))break;
            maximum=n;
        }
        if(maximum<=tableDegree)return;
        long began=System.nanoTime();GpuPatternLayout layout=new GpuPatternLayout(maximum);
        long[] starts=layout.offsets;long size=layout.bytes;
        cuMemFree(table);tableReady=false;cuMemAlloc(table,size);tableReady=true;tableBytes=size;cuMemsetD8(table,(byte)-1,size);tableDegree=maximum;
        Arrays.fill(patternFloors,Integer.MAX_VALUE);
        CUfunction populate=new CUfunction();cuModuleGetFunction(populate,module,"populate_core_table");
        CUdeviceptr rows=new CUdeviceptr();cuMemAlloc(rows,16384L*14);
        try {
            for(int n=1;n<=maximum;n++) {
                int sourceFloor=catalog.tailFloor(n);if(sourceFloor<0)continue;
                int selectedFloor=patternPlan==null?sourceFloor:Math.max(sourceFloor,patternPlan.floor[n]);
                try(CanonicalLayerStore store=catalog.open(n,selectedFloor,RetentionPlan.upper(n));CanonicalLayerStore.BatchCursor cursor=store.source(selectedFloor,16384,Runtime.getRuntime().availableProcessors())) {
                    // An absent member is below the retained floor. Encode its proven upper bound.
                    starts[GpuPatternLayout.DEGREES+n]=selectedFloor;patternFloors[n]=selectedFloor;
                    byte[] batch;long loaded=0;
                    while((batch=cursor.nextBatch(16384))!=null) {
                        int records=batch.length/store.rowBytes,jobs=records*2*(n+1);loaded+=records;
                        cuMemcpyHtoD(rows,Pointer.to(batch),batch.length);
                        cuLaunchKernel(populate,(jobs+255)/256,1,1,256,1,1,0,null,Pointer.to(integer(records),integer(n),integer(store.rowBytes),
                            number(starts[n]),Pointer.to(rows),Pointer.to(table)),null);
                    }
                    if(loaded!=store.manifest.canonicalSize())throw new java.io.IOException("Incomplete GPU pattern source n="+n+" loaded="+loaded+" expected="+store.manifest.canonicalSize());
                }
            }
            cuMemcpyHtoD(offsets,Pointer.to(starts),GpuPatternLayout.METADATA_BYTES);
            CUfunction identities=new CUfunction();cuModuleGetFunction(identities,module,"initialize_pattern_identities");
            cuLaunchKernel(identities,1,1,1,32,1,1,0,null,Pointer.to(integer(maximum),Pointer.to(offsets),Pointer.to(table)),null);
            cuCtxSynchronize();
        }finally{cuMemFree(rows);}
        System.out.printf(java.util.Locale.ROOT,"GPU_PATTERN_TABLE device=%d degree=%d bytes=%d bits_per_distance=4 build_seconds=%.3f source=persisted-canonicals orbit_expansion=GPU%n",context.deviceOrdinal,maximum,size,(System.nanoTime()-began)/1e9);
    }
    void auditCanonicality(int n) {
        activate();
        int jobs=DiametralExtensionAudit.FACT[n];CUdeviceptr output=new CUdeviceptr();
        cuMemAlloc(output,jobs);
        try {
            CUfunction audit=new CUfunction();cuModuleGetFunction(audit,module,"audit_canonical_weights");
            cuLaunchKernel(audit,(jobs+255)/256,1,1,256,1,1,0,null,Pointer.to(integer(jobs),integer(n),Pointer.to(output)),null);
            byte[] weights=new byte[jobs],word=new byte[n];cuMemcpyDtoH(Pointer.to(weights),output,jobs);
            long sum=0,representatives=0;
            for(int rank=0;rank<jobs;rank++) {
                SmallTables.unrank(rank,n,word,0);int expected=ToricOrbit.weight(word,n);
                if(weights[rank]!=expected)throw new AssertionError("Fixed-target CPU/GPU canonicality mismatch at n="+n+" rank="+rank);
                sum+=weights[rank];if(weights[rank]>0)representatives++;
            }
            if(sum!=jobs)throw new AssertionError("Canonical orbit weights lost instances");
            System.out.printf("FIXED-SIGMA CANONICALITY n=%d representatives=%d weighted=%d PASS%n",n,representatives,sum);
        }finally{cuMemFree(output);}
    }
    void auditSlackMoveDeltas() {
        activate();CUfunction audit=new CUfunction();cuModuleGetFunction(audit,module,"audit_slack_move_deltas");
        CUdeviceptr deviceCuts=new CUdeviceptr(),errors=new CUdeviceptr();
        cuMemAlloc(deviceCuts,ExactSorter.cuts(8).length*4L);cuMemAlloc(errors,8);
        try {
            for(int n=2;n<=8;n++) {
                int[] cuts=ExactSorter.cuts(n);long jobs=SmallTables.FACTORIAL[n]*cuts.length;
                cuMemcpyHtoD(deviceCuts,Pointer.to(cuts),cuts.length*4L);cuMemsetD32(errors,0,2);
                cuLaunchKernel(audit,(int)((jobs+255)/256),1,1,256,1,1,0,null,
                    Pointer.to(number(jobs),integer(n),Pointer.to(deviceCuts),integer(cuts.length),Pointer.to(errors)),null);
                long[] failures={0};cuMemcpyDtoH(Pointer.to(failures),errors,8);
                if(failures[0]!=0)throw new AssertionError("Slack move-delta mapping mismatch at n="+n+": "+failures[0]);
                System.out.printf("SLACK MOVE AUDIT n=%d permutations=%d cuts=%d queries=%d PASS%n",
                    n,SmallTables.FACTORIAL[n],cuts.length,jobs);
            }
        }finally{cuMemFree(errors);cuMemFree(deviceCuts);}
    }
    void verifyStoredHistogram(LayerManifest manifest) {
        activate();
        int n=manifest.degree;if(n>tableDegree)throw new IllegalArgumentException("Store exceeds resident GPU pattern table");
        CUfunction audit=new CUfunction();cuModuleGetFunction(audit,module,"audit_pattern_histogram");
        CUdeviceptr histogram=new CUdeviceptr();cuMemAlloc(histogram,128);
        try {
            cuMemsetD32(histogram,0,32);int blocks=(int)Math.min(65536,(SmallTables.FACTORIAL[n]+255)/256);
            cuLaunchKernel(audit,blocks,1,1,256,1,1,0,null,Pointer.to(integer(n),Pointer.to(table),Pointer.to(offsets),Pointer.to(histogram)),null);
            long[] actual=new long[16];cuMemcpyDtoH(Pointer.to(actual),histogram,128);
            for(int d=manifest.floor;d<15;d++) {
                long expected=d<manifest.weighted.length?manifest.weighted[d]:0;
                if(actual[d]!=expected)throw new AssertionError("Reopened GPU histogram mismatch n="+n+" d="+d+" actual="+actual[d]+" expected="+expected);
            }
            System.out.printf("GPU_STORE_VERIFIED n=%d ordinary_rank_slots=%d histogram=%s%n",n,SmallTables.FACTORIAL[n],Arrays.toString(actual));
        }finally{cuMemFree(histogram);}
    }
    int capacity(int n,int high,double fraction) {
        activate();
        long[] free={0},total={0};cuMemGetInfo(free,total);
        return (int)Math.max(1,Math.min(Integer.MAX_VALUE-256,((long)(free[0]*fraction)-fixedBatchBytes(n))/bytesPerJob(n,high)));
    }
    static long bytesPerJob(int n,int frames){return (long)frames*(n+2)+20+PackedPermutation5.bytes(n+1);}
    static long fixedBatchBytes(int n){return 44+4L*n*(n+1)*(n-1)/6;}
    static int capacityForBudget(int n,int frames,int requested,long bytes) {
        long capacity=(bytes-fixedBatchBytes(n))/bytesPerJob(n,frames);
        if(capacity<1)throw new IllegalArgumentException("GPU slot memory budget is too small for one search state");
        return (int)Math.min(requested,capacity);
    }
    final class Batch implements AutoCloseable {
        final int n,low,high,capacity;
        final boolean filterSlackMoves,prepassRootDeletions,prepassRootMoves,prepassOddCycles;
        final CUstream stream;
        final Pointer hostRanks=new Pointer(),hostResults=new Pointer(),hostProgress=new Pointer(),hostKeys=new Pointer();
        final CUevent timedStart=new CUevent(),timedEnd=new CUevent(),completion=new CUevent();
        int pinnedCount,eventCount;
        boolean closed;
        long launches,activeJobs,tinyWaves,oddCycleResolved;double kernelMillis;
        final List<CUdeviceptr> allocations=new ArrayList<>();
        final List<CUdeviceptr> stateAllocations=new ArrayList<>();
        final CUdeviceptr roots,weights,activeCount,counters,cuts,rootKeys;
        CUdeviceptr stack,next,depth,stage,results,firstActive,secondActive;
        final int cutCount;
        long allocatedBytes,stateBytes;
        int stateCapacity;
        int overflowSplits;
        Batch(int n,int low,int high,int capacity) {
            this(n,low,high,capacity,null);
        }
        Batch(int n,int low,int high,int capacity,CUstream stream) {
            this(n,low,high,capacity,stream,slackMoveFilter,rootDeletionPrepass,rootMovePrepass(low-1),oddCyclePrepass(n,high-1));
        }
        Batch(int n,int low,int high,int capacity,CUstream stream,boolean filterSlackMoves) {
            this(n,low,high,capacity,stream,filterSlackMoves,rootDeletionPrepass,rootMovePrepass(low-1),oddCyclePrepass(n,high-1));
        }
        Batch(int n,int low,int high,int capacity,CUstream stream,boolean filterSlackMoves,boolean prepassRootDeletions) {
            this(n,low,high,capacity,stream,filterSlackMoves,prepassRootDeletions,rootMovePrepass(low-1),oddCyclePrepass(n,high-1));
        }
        Batch(int n,int low,int high,int capacity,CUstream stream,boolean filterSlackMoves,boolean prepassRootDeletions,boolean prepassRootMoves) {
            this(n,low,high,capacity,stream,filterSlackMoves,prepassRootDeletions,prepassRootMoves,oddCyclePrepass(n,high-1));
        }
        Batch(int n,int low,int high,int capacity,CUstream stream,boolean filterSlackMoves,boolean prepassRootDeletions,boolean prepassRootMoves,boolean prepassOddCycles) {
            this.n=n;this.low=low;this.high=high;this.capacity=capacity;
            this.stream=stream;this.filterSlackMoves=filterSlackMoves;this.prepassRootDeletions=prepassRootDeletions;this.prepassRootMoves=prepassRootMoves;this.prepassOddCycles=prepassOddCycles;
            activate();
            try {
                roots=allocate(capacity*8L);
                rootKeys=allocate((long)capacity*PackedPermutation5.bytes(n+1));
                weights=allocate(capacity);
                activeCount=allocate(4);counters=allocate(40);
                int[] moves=ExactSorter.cuts(n);cutCount=moves.length;
                cuts=allocate(moves.length*4L);cuMemcpyHtoD(cuts,Pointer.to(moves),moves.length*4L);
                if(stream!=null){cuMemAllocHost(hostRanks,capacity*8L);pinnedCount=1;cuMemAllocHost(hostResults,capacity);pinnedCount=2;
                    cuMemAllocHost(hostProgress,40);pinnedCount=3;cuMemAllocHost(hostKeys,(long)capacity*PackedPermutation5.bytes(n+1));pinnedCount=4;
                    cuEventCreate(timedStart,0);eventCount=1;cuEventCreate(timedEnd,0);eventCount=2;
                    cuEventCreate(completion,CUevent_flags.CU_EVENT_BLOCKING_SYNC|CUevent_flags.CU_EVENT_DISABLE_TIMING);eventCount=3;
                    // cuMemcpyHtoD(cuts,...) may return after staging pageable memory.
                    // Make the nonblocking search stream wait for its device upload.
                    cuEventRecord(completion,null);cuStreamWaitEvent(stream,completion,0);}
            }catch(RuntimeException|Error error){close();throw error;}
        }
        CUdeviceptr allocate(long bytes){CUdeviceptr p=new CUdeviceptr();cuMemAlloc(p,bytes);allocations.add(p);allocatedBytes+=bytes;return p;}
        CUdeviceptr allocateState(long bytes){CUdeviceptr p=new CUdeviceptr();cuMemAlloc(p,bytes);stateAllocations.add(p);stateBytes+=bytes;allocatedBytes+=bytes;return p;}
        void ensureStates(int jobs) {
            if(stream!=null)jobs=capacity;
            if(jobs<=stateCapacity)return;
            for(int i=stateAllocations.size()-1;i>=0;i--)cuMemFree(stateAllocations.get(i));
            stateAllocations.clear();allocatedBytes-=stateBytes;stateBytes=0;stateCapacity=0;
            stack=allocateState((long)jobs*high*n);next=allocateState((long)jobs*high*2);
            depth=allocateState(jobs);stage=allocateState(jobs);
            results=allocateState(jobs);
            firstActive=allocateState(jobs*4L);secondActive=allocateState(jobs*4L);stateCapacity=jobs;
        }
        int scanCapacity(){return (int)Math.min(Integer.MAX_VALUE-262144,(long)capacity*2*(n+1));}
        long[] count(long first,int jobs,Consumer<long[]> progress) {
            return count(first,jobs,progress,null,null);
        }
        long[] count(long first,int jobs,Consumer<long[]> progress,IntSupplier idle,LongConsumer steal) {
            activate();
            if(jobs<1||jobs>scanCapacity())throw new IllegalArgumentException("Invalid batch size");
            cuMemsetD32(counters,0,10);
            cuMemsetD32(activeCount,0,1);
            final int slice=262144;
            for(int begin=0;begin<jobs;begin+=slice) {
                int size=Math.min(slice,jobs-begin);
                Pointer args=Pointer.to(number(first),integer(jobs),integer(n),integer(capacity),integer(begin),
                    Pointer.to(roots),Pointer.to(weights),Pointer.to(activeCount),Pointer.to(counters));
                cuLaunchKernel(filterRoots,(size+255)/256,1,1,256,1,1,0,null,args,null);
            }
            int[] canonical={0};cuMemcpyDtoH(Pointer.to(canonical),activeCount,4);
            if(canonical[0]>capacity) {
                overflowSplits++;
                // Retry disjoint halves before publishing progress or sorting.
                // This makes the orbit-density estimate a performance choice,
                // never a reason to lose a canonical representative.
                int left=jobs/2;long[] sum=new long[3];
                for(int half=0;half<2;half++) {
                    long[] before=sum.clone();
                    Consumer<long[]> nested=progress==null?null:partial->{for(int i=0;i<3;i++)partial[i]+=before[i];progress.accept(partial);};
                    long[] result=count(first+(half==0?0:left),half==0?left:jobs-left,nested,idle,steal);
                    for(int i=0;i<3;i++)sum[i]+=result[i];
                }
                return sum;
            }
            int representatives=canonical[0];
            if(representatives>0)ensureStates(representatives);
            System.out.printf(java.util.Locale.ROOT,"CANONICAL n=%d raw=%d representatives=%d rejected=%d VRAM=%.1f MiB%n",
                n,jobs,representatives,jobs-representatives,allocatedBytes/1048576.0);
            for(int begin=0;begin<representatives;begin+=slice) {
                int size=Math.min(slice,representatives-begin);
                Pointer args=Pointer.to(integer(representatives),integer(n),integer(high),integer(stateCapacity),integer(begin),
                    Pointer.to(roots),Pointer.to(stack),Pointer.to(next),Pointer.to(depth),Pointer.to(stage),Pointer.to(firstActive));
                cuLaunchKernel(initialize,(size+255)/256,1,1,256,1,1,0,null,args,null);
            }
            CUdeviceptr input=firstActive,output=secondActive;
            int pending=representatives,stolen=0;long[] totals=new long[3];
            cuMemcpyDtoH(Pointer.to(totals),counters,24);
            if(progress!=null)progress.accept(totals.clone());
            while(pending>0) {
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("GPU count interrupted");
                cuMemsetD32(activeCount,0,1);
                for(int begin=0;begin<pending;begin+=slice) {
                    int size=Math.min(slice,pending-begin);
                    Pointer args=Pointer.to(integer(pending),integer(begin),Pointer.to(input),Pointer.to(output),
                        Pointer.to(activeCount),integer(n),integer(high),integer(stateCapacity),integer(low),integer(high),integer(quantum),
                        Pointer.to(cuts),integer(cutCount),integer(filterSlackMoves?1:0),integer(prepassRootDeletions?1:0),integer(prepassRootMoves?1:0),integer(prepassOddCycles?1:0),Pointer.to(stack),Pointer.to(next),Pointer.to(depth),
                        Pointer.to(stage),Pointer.to(weights),Pointer.to(results),Pointer.to(table),Pointer.to(offsets),integer(tableDegree),
                        Pointer.to(membershipBits()),integer(membershipDegree()),integer(membershipDistance()),Pointer.to(counters));
                    cuLaunchKernel(advance,(size+255)/256,1,1,256,1,1,0,null,args,null);
                    cuMemcpyDtoH(Pointer.to(totals),counters,24);
                    if(progress!=null)progress.accept(totals.clone());
                }
                int[] count={0};cuMemcpyDtoH(Pointer.to(count),activeCount,4);pending=count[0];
                CUdeviceptr swap=input;input=output;output=swap;
                if(idle!=null&&steal!=null) {
                    int take=Math.min(pending/4,Math.max(0,idle.getAsInt())*8);
                    if(take>0) {
                        int[] ids=new int[take];
                        cuMemcpyDtoH(Pointer.to(ids),input.withByteOffset((pending-take)*4L),take*4L);
                        pending-=take;stolen+=take;
                        long[] rank={0};
                        for(int id:ids){cuMemcpyDtoH(Pointer.to(rank),roots.withByteOffset(id*8L),8);steal.accept(rank[0]);}
                    }
                }
            }
            cuMemcpyDtoH(Pointer.to(totals),counters,24);
            if(totals[0]+stolen!=jobs)throw new AssertionError("GPU lost or duplicated jobs");
            return totals;
        }
        /** One exact CanSort(low-1) decision per supplied rank, in input order. */
        byte[] classifyRanks(long[] ranks,int jobs) {
            return classifyRanks(ranks,jobs,null);
        }
        byte[] classifyRanks(long[] ranks,int jobs,Runnable cancellationCheck) {
            return classifyRanks(ranks,jobs,cancellationCheck,null,0);
        }
        byte[] classifyRanks(long[] ranks,int jobs,Runnable cancellationCheck,byte[] expectedKeys,int keyOffset) {
            activate();
            if(low!=high||jobs<1||jobs>capacity||jobs>ranks.length)throw new IllegalArgumentException("Invalid supplied-rank batch");
            if(stream==null){cuMemcpyHtoD(roots,Pointer.to(ranks),jobs*8L);cuMemsetD8(weights,(byte)1,jobs);cuMemsetD32(counters,0,10);}
            else {hostRanks.getByteBuffer(0,jobs*8L).order(ByteOrder.nativeOrder()).asLongBuffer().put(ranks,0,jobs);
                cuMemcpyHtoDAsync(roots,hostRanks,jobs*8L,stream);cuMemsetD8Async(weights,(byte)1,jobs,stream);cuMemsetD32Async(counters,0,10,stream);}
            if(expectedKeys!=null) {
                int bytes=Math.multiplyExact(jobs,PackedPermutation5.bytes(n+1));
                if(keyOffset<0||keyOffset>expectedKeys.length-bytes)throw new IllegalArgumentException("Malformed expected root keys");
                if(stream==null)cuMemcpyHtoD(rootKeys,Pointer.to(expectedKeys).withByteOffset(keyOffset),bytes);
                else {hostKeys.getByteBuffer(0,bytes).put(expectedKeys,keyOffset,bytes);cuMemcpyHtoDAsync(rootKeys,hostKeys,bytes,stream);}
                cuLaunchKernel(validateRoots,(jobs+255)/256,1,1,256,1,1,0,stream,Pointer.to(integer(jobs),integer(n),Pointer.to(roots),Pointer.to(rootKeys),Pointer.to(counters.withByteOffset(24))),null);
            }
            ensureStates(jobs);
            final int slice=262144;
            for(int begin=0;begin<jobs;begin+=slice) {
                int size=Math.min(slice,jobs-begin);
                Pointer args=Pointer.to(integer(jobs),integer(n),integer(high),integer(stateCapacity),integer(begin),Pointer.to(roots),
                    Pointer.to(stack),Pointer.to(next),Pointer.to(depth),Pointer.to(stage),Pointer.to(firstActive));
                cuLaunchKernel(initialize,(size+255)/256,1,1,256,1,1,0,stream,args,null);
            }
            CUdeviceptr input=firstActive,output=secondActive;int pending=jobs;
            while(pending>0) {
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("GPU supplied-rank search interrupted");
                if(cancellationCheck!=null)cancellationCheck.run();
                if(stream==null)cuMemsetD32(activeCount,0,1);else {cuMemsetD32Async(activeCount,0,1,stream);cuEventRecord(timedStart,stream);}
                for(int begin=0;begin<pending;begin+=slice) {
                    int size=Math.min(slice,pending-begin);
                    Pointer args=Pointer.to(integer(pending),integer(begin),Pointer.to(input),Pointer.to(output),
                        Pointer.to(activeCount),integer(n),integer(high),integer(stateCapacity),integer(low),integer(high),integer(quantum),
                        Pointer.to(cuts),integer(cutCount),integer(filterSlackMoves?1:0),integer(prepassRootDeletions?1:0),integer(prepassRootMoves?1:0),integer(prepassOddCycles?1:0),Pointer.to(stack),Pointer.to(next),Pointer.to(depth),
                        Pointer.to(stage),Pointer.to(weights),Pointer.to(results),Pointer.to(table),Pointer.to(offsets),integer(tableDegree),
                        Pointer.to(membershipBits()),integer(membershipDegree()),integer(membershipDistance()),Pointer.to(counters));
                    cuLaunchKernel(advance,(size+255)/256,1,1,256,1,1,0,stream,args,null);
                }
                launches++;activeJobs+=pending;if(pending<256)tinyWaves++;
                if(stream==null){int[] remaining={0};cuMemcpyDtoH(Pointer.to(remaining),activeCount,4);pending=remaining[0];}
                else {cuEventRecord(timedEnd,stream);cuMemcpyDtoHAsync(hostProgress,activeCount,4,stream);synchronize();
                    float[] ms={0};cuEventElapsedTime(ms,timedStart,timedEnd);kernelMillis+=ms[0];
                    pending=hostProgress.getByteBuffer(0,4).order(ByteOrder.nativeOrder()).getInt();}
                CUdeviceptr swap=input;input=output;output=swap;
            }
            long[] totals=new long[5];
            if(stream==null)cuMemcpyDtoH(Pointer.to(totals),counters,40);
            else {cuMemcpyDtoHAsync(hostProgress,counters,40,stream);cuMemcpyDtoHAsync(hostResults,results,jobs,stream);synchronize();
                hostProgress.getByteBuffer(0,40).order(ByteOrder.nativeOrder()).asLongBuffer().get(totals);}
            if(totals[3]!=0)throw new AssertionError("GPU input ranks disagree with "+totals[3]+" packed root keys");
            if(totals[0]!=jobs)throw new AssertionError("Supplied-rank GPU search lost jobs");
            oddCycleResolved=totals[4];
            byte[] decisions=new byte[jobs];if(stream==null)cuMemcpyDtoH(Pointer.to(decisions),results,jobs);else hostResults.getByteBuffer(0,jobs).get(decisions);
            int positive=0;for(byte decision:decisions){if(decision!=0&&decision!=1)throw new AssertionError("Invalid supplied-rank result");positive+=decision;}
            if(positive!=totals[1])throw new AssertionError("GPU result bytes disagree with classification counters");
            return decisions;
        }
        public void close(){
            if(closed)return;closed=true;
            activate();if(stream!=null)synchronize();
            if(launches>0&&stream!=null)System.out.printf(java.util.Locale.ROOT,"GPU_KERNEL_PROFILE device=%d n=%d budget=%d launches=%d active_job_steps=%d tiny_waves=%d kernel_ms=%.3f state_bytes=%d%n",context.deviceOrdinal,n,low-1,launches,activeJobs,tinyWaves,kernelMillis,stateBytes);
            for(int i=stateAllocations.size()-1;i>=0;i--)cuMemFree(stateAllocations.get(i));stateAllocations.clear();for(int i=allocations.size()-1;i>=0;i--)cuMemFree(allocations.get(i));allocations.clear();
            if(pinnedCount>0)cuMemFreeHost(hostRanks);if(pinnedCount>1)cuMemFreeHost(hostResults);if(pinnedCount>2)cuMemFreeHost(hostProgress);if(pinnedCount>3)cuMemFreeHost(hostKeys);pinnedCount=0;
            if(eventCount>0)cuEventDestroy(timedStart);if(eventCount>1)cuEventDestroy(timedEnd);if(eventCount>2)cuEventDestroy(completion);eventCount=0;
        }
        private void synchronize(){if(eventCount>2){cuEventRecord(completion,stream);cuEventSynchronize(completion);}else cuStreamSynchronize(stream);}
    }
    public void close() {
        if(context.contextReady)activate();
        if(membership!=null){membership.close();membership=null;}
        if(offsetsReady){cuMemFree(offsets);offsetsReady=false;}
        if(tableReady){cuMemFree(table);tableReady=false;}
        if(moduleReady){cuModuleUnload(module);moduleReady=false;}
        context.close();
    }
}
