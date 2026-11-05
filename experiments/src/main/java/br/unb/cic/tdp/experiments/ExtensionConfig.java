package br.unb.cic.tdp.experiments;

import java.nio.file.Path;

record ExtensionConfig(Path dataDirectory,int maxN,int threads,int baseDegree,int batchRecords,
                       int writeQueueBatches,int gpuBatchRecords,int gpuQueueBatches,boolean gpu,double vramFraction) {
    ExtensionConfig {
        dataDirectory=dataDirectory.toAbsolutePath().normalize();
        if(maxN<1||maxN>18||threads<1||baseDegree<0||baseDegree>9||batchRecords<1||writeQueueBatches<1||gpuBatchRecords<1||gpuQueueBatches<1||vramFraction<=0||vramFraction>0.95)
            throw new IllegalArgumentException("Invalid extension configuration");
    }
    static ExtensionConfig system() {
        return new ExtensionConfig(Path.of(System.getProperty("extension.dataDir","experiments/data/extension")),
            Integer.getInteger("count.maxN",18),Integer.getInteger("count.cpuThreads",Runtime.getRuntime().availableProcessors()),
            Integer.getInteger("extension.baseDegree",7),Integer.getInteger("extension.batchRecords",16384),
            Integer.getInteger("extension.writeQueueBatches",8),Integer.getInteger("extension.gpuBatchRecords",16384),
            Integer.getInteger("extension.gpuQueueBatches",8),Boolean.parseBoolean(System.getProperty("count.gpu","true")),
            Double.parseDouble(System.getProperty("count.vramFraction","0.90")));
    }
}
