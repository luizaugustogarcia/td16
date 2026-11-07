package br.unb.cic.tdp.experiments;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Demand-driven exact-layer extension. No S_n rank scan after the degree-1 seed. */
final class ExtensionRun {
    static final class Stats {
        final AtomicLong parents=new AtomicLong(),generated=new AtomicLong(),canonical=new AtomicLong(),rejected=new AtomicLong(),determined=new AtomicLong(),rootMoveResolved=new AtomicLong();
        long cpuQueries,gpuQueries;
        byte[] firstMatch;
    }
    static void run(ExtensionConfig config) throws Exception {
        int through=Integer.getInteger("extension.through",config.maxN());
        if(through<1||through>config.maxN())throw new IllegalArgumentException("Through degree must be within the planned horizon");
        Files.createDirectories(config.dataDirectory());
        try(FileChannel channel=FileChannel.open(config.dataDirectory().resolve(".extension.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock()) {
            if(lock==null)throw new IOException("Another extension run owns "+config.dataDirectory());
            SmallTables tables=new SmallTables(config.gpu()?0:Math.min(config.baseDegree(),config.maxN()));
            System.out.println("EXTENSION data="+config.dataDirectory()+" encoding="+LayerManifest.ENCODING+" storage="+LayerManifest.STORAGE+" canonical-only=true async-writer=true gpu="+config.gpu());
            RocksTuning.log();
            LayerCatalog catalog=new LayerCatalog(config.dataDirectory());
            Stats[] decision={null};
            LazyLayerPlan plan=new LazyLayerPlan(new LazyLayerPlan.Sets() {
                public boolean has(int n,int d) throws IOException{return catalog.has(n,d);}
                public long size(int n,int d) throws IOException{return catalog.size(n,d);}
                public void generate(int n,int d) throws Exception {
                    if(n==1) {
                        try(AsyncCanonicalWriter writer=new AsyncCanonicalWriter(config.dataDirectory(),1,0,config.maxN(),config.batchRecords(),config.writeQueueBatches(),true)) {
                            try(AsyncCanonicalWriter.Buffer buffer=writer.buffer()){buffer.append(new byte[]{0,1},0,1);}
                            writer.finish();
                        }
                    }else extendExact(config,n,d,catalog,tables);
                }
                public boolean exists(int n,int d) throws Exception {
                    Stats stats=decideExact(config,n,d,catalog,tables);decision[0]=stats;
                    return stats.firstMatch!=null;
                }
            });
            for(int n=1;n<=through;n++) {
                int patternHorizon=Integer.getInteger("extension.patternHorizon",0);
                if(n==14&&config.gpu()&&patternHorizon>0&&GpuPatternLayout.configuredDegree()>=13)plan.preparePatterns(patternHorizon,13);
                if(n==config.maxN()&&Boolean.getBoolean("extension.diameterOnly")) {
                    int diameter=plan.determine(n);
                    writeDecision(config,n,diameter,decision[0]);
                    return;
                }
                int d=plan.diameter(n);Path path=catalog.find(n,d);LayerManifest stored=LayerManifest.read(path);
                long expected=n<=9?DiametralExtensionAudit.EXPECTED[n][DiametralExtensionAudit.EXPECTED[n].length-1]:CountsRun.DiametralExpected.count(n);
                if(expected>=0&&stored.weighted[d]!=expected)throw new AssertionError("D("+n+") disagrees with established count");
                System.out.printf("FINAL n=%d TD=%d |D(%d)|=%d canonical_D=%d input_store=%s%n",n,d,n,stored.weighted[d],stored.canonical[d],path);
            }
        }
    }
    static Stats extendExact(ExtensionConfig config,int n,int d,LayerCatalog catalog,SmallTables tables) throws Exception {
        int parentFloor=Math.max(0,d-1),parentCeiling=Math.min(d,RetentionPlan.upper(n-1));
        return extend(config,n,d,parentFloor,catalog.open(n-1,parentFloor,parentCeiling),tables,0,Long.MAX_VALUE,false,true,false,catalog.root);
    }
    static Stats decideExact(ExtensionConfig config,int n,int d,LayerCatalog catalog,SmallTables tables) throws Exception {
        int parentFloor=Math.max(0,d-1),parentCeiling=Math.min(d,RetentionPlan.upper(n-1));
        return extend(config,n,d,parentFloor,catalog.open(n-1,parentFloor,parentCeiling),tables,0,Long.MAX_VALUE,false,true,true,catalog.root);
    }
    private static void writeDecision(ExtensionConfig config,int n,int diameter,Stats stats) throws IOException {
        byte[] match=stats==null?null:stats.firstMatch;
        int lower=n==18?RetentionPlan.upper(17):CountsRun.LOW[n],upper=RetentionPlan.upper(n);
        String basis=lower==upper?"known-bounds":diameter==lower?"exhaustive-empty-upper-layer":"verified-upper-distance-instance";
        java.util.Properties p=new java.util.Properties();p.setProperty("complete","true");p.setProperty("degree",Integer.toString(n));
        p.setProperty("TD",Integer.toString(diameter));p.setProperty("lowerBound",Integer.toString(lower));p.setProperty("upperBound",Integer.toString(upper));
        p.setProperty("basis",basis);p.setProperty("countD","not-computed");
        if(match!=null)p.setProperty("canonicalOmegaHex",java.util.HexFormat.of().formatHex(match));
        if(stats!=null) {
            p.setProperty("parents",Long.toString(stats.parents.get()));p.setProperty("generated",Long.toString(stats.generated.get()));
            p.setProperty("canonicalCandidates",Long.toString(stats.canonical.get()));p.setProperty("deletionRejected",Long.toString(stats.rejected.get()));
            p.setProperty("exactDecisions",Long.toString(stats.cpuQueries+stats.gpuQueries));p.setProperty("enumerationComplete",Boolean.toString(match==null));
        }
        Path target=config.dataDirectory().resolve("td-"+n+".properties"),temporary=Files.createTempFile(config.dataDirectory(),".td-"+n+"-",".partial");
        try(OutputStream out=Files.newOutputStream(temporary)){p.store(out,"Computational diameter result; no sorting sequences");}
        Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("DIAMETER_RESULT n=%d TD=%d basis=%s count_D=not-computed result=%s%n",n,diameter,basis,target);
    }
    static Stats extend(ExtensionConfig config,RetentionPlan plan,int n,Path parentDirectory,SmallTables tables) throws Exception {
        return extend(config,plan,n,parentDirectory,tables,0,Long.MAX_VALUE,false);
    }
    static void benchmark(ExtensionConfig config) throws Exception {
        int n=Integer.getInteger("benchmark.n",12),d=Integer.getInteger("benchmark.distance",RetentionPlan.upper(n));
        if(n<2||n>config.maxN()||d<0||d>RetentionPlan.upper(n))throw new IllegalArgumentException("Invalid benchmark target");
        LayerCatalog catalog=new LayerCatalog(config.dataDirectory());
        Path output=Files.createTempDirectory(config.dataDirectory(),".benchmark-");
        ExtensionConfig measured=new ExtensionConfig(output,config.maxN(),config.threads(),config.baseDegree(),config.batchRecords(),
            config.writeQueueBatches(),config.gpuBatchRecords(),config.gpuQueueBatches(),config.gpu(),config.vramFraction());
        boolean persist=Boolean.getBoolean("benchmark.persist");long skip=Long.getLong("benchmark.skip",0L),limit=Long.getLong("benchmark.parents",8192L);
        if(skip<0||limit<1||(persist&&(skip!=0||limit!=Long.MAX_VALUE)))throw new IllegalArgumentException("Persisted benchmark requires all parents and zero skip");
        int parentFloor=Math.max(0,d-1),parentCeiling=Math.min(d,RetentionPlan.upper(n-1));
        Path patternRoot=Path.of(System.getProperty("benchmark.patternRoot",catalog.root.toString()));
        extend(measured,n,d,parentFloor,catalog.open(n-1,parentFloor,parentCeiling),new SmallTables(config.gpu()?0:config.baseDegree()),skip,limit,!persist,true,false,patternRoot);
    }
    private static Stats extend(ExtensionConfig config,RetentionPlan plan,int n,Path parentDirectory,SmallTables tables,long skip,long limit,boolean benchmark) throws Exception {
        return extend(config,n,plan.floor[n],plan.floor[n-1],new CanonicalLayerStore(parentDirectory),tables,skip,limit,benchmark,false,false,parentDirectory.getParent().getParent());
    }
    private static Stats extend(ExtensionConfig config,int n,int floor,int sourceFloor,CanonicalLayerStore parents,SmallTables tables,long skip,long limit,boolean benchmark,boolean exact,boolean decision,Path lookupRoot) throws Exception {
        Stats stats=new Stats();long started=System.nanoTime();
        try(parents;
            AsyncCanonicalWriter writer=new AsyncCanonicalWriter(config.dataDirectory(),n,floor,config.maxN(),config.batchRecords(),config.writeQueueBatches(),exact,decision);
            ExtensionResolver resolver=new ExtensionResolver(n,floor,writer,tables,config.gpu(),config.gpuBatchRecords(),config.gpuQueueBatches(),config.vramFraction(),lookupRoot)) {
            long computationStarted=System.nanoTime();
            System.out.printf("EXTEND n=%d parent=%s parent_distances=%d..%d output_distances=%d..%d scope=%s%n",n,parents.directory,sourceFloor,parents.manifest.maxDistance,floor,writer.ceiling,exact?"exact":"tail");
            ExecutorService workers=Executors.newFixedThreadPool(config.threads(),Thread.ofPlatform().name("host-stage-n"+n+"-",0).factory());
            CompletionService<Void> completed=new ExecutorCompletionService<>(workers);
            ScheduledExecutorService logger=Executors.newSingleThreadScheduledExecutor();
            java.util.ArrayDeque<long[]> rateSamples=new java.util.ArrayDeque<>();
            logger.scheduleAtFixedRate(()->{
                long now=System.nanoTime(),settled=stats.rejected.get()+stats.determined.get()+resolver.cpuQueries.get()+resolver.gpuQueries.get();
                rateSamples.addLast(new long[]{now,settled,writer.nativeWriteBytes()});if(rateSamples.size()>31)rateSamples.removeFirst();
                long[] previous=rateSamples.getFirst();double rate=now==previous[0]?0:(settled-previous[1])*60e9/(now-previous[0]);
                double ingestRate=now==previous[0]?0:(writer.nativeWriteBytes()-previous[2])*1e9/(now-previous[0])/(1L<<20);
                System.out.printf(java.util.Locale.ROOT,
                "EXTENSION_PROGRESS n=%d parents=%d generated=%d canonical=%d deletion_rejected=%d determined=%d CPU_queries=%d GPU_queries=%d written=%d queued_buffers=%d queued_bytes_estimate=%d write_queue_capacity=%d write_queue_wait_seconds=%.3f aggregate_bytes=%d aggregate_target_bytes=%d aggregate_flushes=%d native_write_calls=%d native_write_bytes=%d native_ingest_MiB_per_second=%.1f settled=%d settled_per_minute=%.0f elapsed=%.1fs GPU_device_queries=%s%n",
                n,stats.parents.get(),stats.generated.get(),stats.canonical.get(),stats.rejected.get(),stats.determined.get(),
                resolver.cpuQueries.get(),resolver.gpuQueries.get(),writer.writtenRecords(),writer.queuedBatches(),writer.queuedBytesEstimate(),writer.pendingCapacity(),writer.enqueueWaitSeconds(),writer.aggregateBytes(),writer.aggregateTargetBytes(),writer.aggregateFlushes(),writer.nativeWrites(),writer.nativeWriteBytes(),ingestRate,settled,rate,(now-started)/1e9,resolver.deviceQueries());
            },2,2,TimeUnit.SECONDS);
            ThreadLocal<Worker> local=ThreadLocal.withInitial(()->new Worker(n,floor,Math.max(0,floor-1),parents,writer,resolver,tables,stats));
            int outstanding=0;
            int parentBatch=config.gpu()?Integer.getInteger("extension.gpuParentBatch",4096):128;
            boolean rankRows=config.gpu();int rowBytes=rankRows?9:parents.rowBytes;
            try(CanonicalLayerStore.BatchCursor cursor=rankRows?parents.sourceRanks(sourceFloor,parentBatch,benchmark&&skip>0?1:config.threads()):benchmark&&skip>0?parents.cursor(sourceFloor):parents.source(sourceFloor,parentBatch,config.threads());
                GpuCandidatePool generator=config.gpu()?new GpuCandidatePool(resolver,parents,n,floor,parentBatch):null) {
                byte[] input;
                long remainingSkip=skip,submitted=0;
                while(!writer.hasMatch()&&(input=cursor.nextBatch(parentBatch))!=null) {
                    int records=input.length/rowBytes;
                    if(remainingSkip>=records){remainingSkip-=records;continue;}
                    int first=(int)remainingSkip,take=(int)Math.min(records-first,limit-submitted);remainingSkip=0;
                    if(take==0)break;
                    if(first!=0||take!=records)input=java.util.Arrays.copyOfRange(input,first*rowBytes,(first+take)*rowBytes);
                    submitted+=take;
                    writer.check();resolver.check();
                    if(outstanding>=2*config.threads()){completed.take().get();outstanding--;}
                    byte[] owned=input;
                    if(generator!=null) {
                        completed.submit(()->{generator.process(owned,stats,writer,resolver,rankRows);return null;});outstanding++;
                        continue;
                    }
                    completed.submit(()->{local.get().process(owned);return null;});outstanding++;
                }
                while(outstanding>0){completed.take().get();outstanding--;}
                if(generator!=null)generator.close();
                resolver.finish();stats.cpuQueries=resolver.cpuQueries.get();stats.gpuQueries=resolver.gpuQueries.get();
                stats.firstMatch=writer.firstMatch();
                if(config.gpu()&&stats.cpuQueries!=0)throw new AssertionError("GPU mode performed a CPU sorting query");
                if(decision&&!writer.hasMatch()) {
                    long expectedParents=parents.manifest.canonicalSize(),expectedGenerated=0;
                    for(long weight:parents.manifest.weighted)expectedGenerated=Math.addExact(expectedGenerated,Math.multiplyExact(n,weight));
                    long settled=stats.rejected.get()+stats.determined.get()+stats.cpuQueries+stats.gpuQueries;
                    if(stats.parents.get()!=expectedParents||stats.generated.get()!=expectedGenerated)
                        throw new IOException("Incomplete decision enumeration: parent/orbit totals disagree with the input manifest");
                    if(floor<=RetentionPlan.upper(n)&&stats.canonical.get()!=settled)
                        throw new IOException("Incomplete decision enumeration: unresolved canonical candidates");
                    System.out.printf("DECISION_EXHAUSTED n=%d target=%d parents=%d generated=%d canonical=%d settled=%d coverage_checked=true%n",n,floor,expectedParents,expectedGenerated,stats.canonical.get(),settled);
                }
                if(decision&&writer.hasMatch()) {
                    System.out.printf("DECISION_MATCH n=%d distance=%d canonicalOmegaHex=%s enumeration_complete=false%n",n,floor,java.util.HexFormat.of().formatHex(stats.firstMatch));
                }else if(benchmark) {
                    double seconds=(System.nanoTime()-computationStarted)/1e9;
                    long settled=stats.rejected.get()+stats.determined.get()+stats.cpuQueries+stats.gpuQueries;
                    System.out.printf(java.util.Locale.ROOT,"GPU_BENCHMARK n=%d skip=%d parents=%d settled=%d canonical_per_minute=%.0f CPU_queries=%d GPU_queries=%d compute_seconds=%.3f total_seconds=%.3f%n",n,skip,stats.parents.get(),settled,settled*60/seconds,stats.cpuQueries,stats.gpuQueries,seconds,(System.nanoTime()-started)/1e9);
                }else {
                    long expected=CountsRun.DiametralExpected.layerCount(n,writer.ceiling);
                    LayerManifest manifest=writer.finish(expected);
                    if(exact)System.out.printf("SET_COMPLETE n=%d distance=%d size=%d canonicals=%d store=%s%n",n,floor,manifest.weighted[floor],manifest.canonicalSize(),writer.target);
                    else report(manifest,writer.target,false);
                    double seconds=(System.nanoTime()-started)/1e9;long settled=stats.rejected.get()+stats.determined.get()+stats.cpuQueries+stats.gpuQueries;
                    System.out.printf(java.util.Locale.ROOT,"DEGREE_TIMING n=%d seconds=%.3f settled_canonicals=%d canonical_per_minute=%.0f includes=GPU-setup-compute-drain-flush-publish%n",n,seconds,settled,settled*60/seconds);
                }
                System.out.printf("EXTENSION_WORK n=%d generated=%d canonical=%d deletion_rejected=%d determined=%d root_move_resolved=%d CPU_queries=%d GPU_queries=%d%n",
                    n,stats.generated.get(),stats.canonical.get(),stats.rejected.get(),stats.determined.get(),stats.rootMoveResolved.get(),stats.cpuQueries,stats.gpuQueries);
            }finally {
                logger.shutdownNow();workers.shutdownNow();
                if(!workers.awaitTermination(10,TimeUnit.SECONDS))throw new IOException("Extension workers did not terminate");
            }
        }
        return stats;
    }
    private static void report(LayerManifest manifest,Path path,boolean reused) {
        int diameter=manifest.maxDistance;long expected=CountsRun.DiametralExpected.count(manifest.degree);
        if(manifest.degree<=9) {int[] histogram=DiametralExtensionAudit.EXPECTED[manifest.degree];expected=histogram[histogram.length-1];}
        if(diameter<CountsRun.LOW[manifest.degree]||(expected>=0&&manifest.weighted[diameter]!=expected))
            throw new AssertionError("Persisted D("+manifest.degree+") disagrees with established count");
        System.out.printf("%s n=%d TD=%d |D(%d)|=%d canonical_D=%d retained_floor=%d retained_canonicals=%d input_store=%s%n",
            reused?"REUSE":"FINAL",manifest.degree,diameter,manifest.degree,manifest.weighted[diameter],manifest.canonical[diameter],manifest.floor,manifest.canonicalSize(),path);
    }
    private static final class Worker {
        final int n,floor,parentFloor;
        final CanonicalLayerStore parents;
        final AsyncCanonicalWriter writer;
        final ExtensionResolver resolver;
        final ExactSorter sorter;
        final Stats stats;
        final byte[] omega=new byte[19],parent=new byte[18],member=new byte[18],candidate=new byte[18],deletion=new byte[18],canonicalDeletion=new byte[19],transformed=new byte[18];
        final long[] orbit=new long[38];
        Worker(int n,int floor,int parentFloor,CanonicalLayerStore parents,AsyncCanonicalWriter writer,ExtensionResolver resolver,SmallTables tables,Stats stats) {
            this.n=n;this.floor=floor;this.parentFloor=parentFloor;this.parents=parents;this.writer=writer;this.resolver=resolver;this.stats=stats;
            sorter=new ExactSorter(tables,()->{try{resolver.check();}catch(IOException error){throw new UncheckedIOException(error);}});
        }
        void process(byte[] input) throws IOException {
            try(AsyncCanonicalWriter.Buffer output=writer.buffer();ExtensionResolver.Pending unknown=resolver.buffer(sorter)) {
                for(int offset=0;offset<input.length;offset+=parents.rowBytes) {
                    if(writer.hasMatch())return;
                    if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Extension interrupted");
                    resolver.check();int parentDistance=input[offset]&255;
                    PackedPermutation5.decode(input,offset+1,n,omega);ToricOrbit.wordFromOmega(omega,n-1,parent);
                    int members=ToricOrbit.orbitRanks(parent,n-1,orbit,transformed);
                    stats.parents.incrementAndGet();
                    for(int m=0;m<members;m++) {
                        SmallTables.unrank(orbit[m],n-1,member,0);
                        for(int position=0;position<n;position++) {
                            if(writer.hasMatch())return;
                            for(int i=0;i<position;i++)candidate[i]=member[i];candidate[position]=(byte)n;
                            for(int i=position;i<n-1;i++)candidate[i+1]=member[i];
                            stats.generated.incrementAndGet();int weight=ToricOrbit.weight(candidate,n);if(weight==0)continue;
                            stats.canonical.incrementAndGet();int minimum=parentDistance,maximum=parentDistance;boolean rejected=false;
                            for(int omitted=0;omitted<n&&maximum<Math.min(minimum+1,RetentionPlan.upper(n));omitted++) {
                                if(omitted==position)continue;int value=candidate[omitted];
                                for(int i=0,j=0;i<n;i++)if(i!=omitted){int v=candidate[i];deletion[j++]=(byte)(v>value?v-1:v);}
                                ToricOrbit.canonicalOmega(deletion,n-1,canonicalDeletion);int distance=parents.distance(canonicalDeletion,parentFloor);
                                if(distance<0) {
                                    if(parentFloor==0&&!writer.exact)throw new IOException("Missing member of a declared complete parent set");
                                    rejected=true;break;
                                }
                                minimum=Math.min(minimum,distance);maximum=Math.max(maximum,distance);
                            }
                            if(rejected){stats.rejected.incrementAndGet();continue;}
                            int upper=Math.min(minimum+1,RetentionPlan.upper(n));
                            if(maximum>upper)throw new IOException("Inconsistent deletion distances or diameter bound");
                            if(upper<floor)continue;
                            ToricOrbit.omegaFromWord(candidate,n,omega);
                            if(maximum==upper){output.append(omega,maximum,weight);stats.determined.incrementAndGet();}
                            else {
                                if(maximum!=minimum||upper!=maximum+1)throw new IOException("Unexpected deletion interval");
                                unknown.append(candidate,omega,maximum,weight);
                            }
                        }
                    }
                }
            }
        }
    }
}
