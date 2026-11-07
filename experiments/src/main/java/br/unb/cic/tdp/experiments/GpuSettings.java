package br.unb.cic.tdp.experiments;

import br.unb.cic.tdp.base.GpuDeviceLayout;

/** Uses the emitter's device list and equal slots-per-device convention. */
record GpuSettings(GpuDeviceLayout layout,long slotMemoryBytes) {
    GpuSettings {
        if(slotMemoryBytes<0)throw new IllegalArgumentException("GPU slot memory must be nonnegative");
    }
    static GpuSettings system() {
        int[] devices=GpuDeviceLayout.parseDevices(System.getProperty("extension.gpuDevices",System.getProperty("tdp.gpuDevices","0")));
        int slots=Integer.parseInt(System.getProperty("extension.gpuSlots",System.getProperty("tdp.gpuSlots","4")));
        long mib=Long.parseLong(System.getProperty("extension.gpuSlotMiB",System.getProperty("tdp.gpuBudgetMB","0")));
        try{return new GpuSettings(new GpuDeviceLayout(devices,slots),Math.multiplyExact(mib,1L<<20));}
        catch(ArithmeticException error){throw new IllegalArgumentException("GPU slot memory budget overflows",error);}
    }
    static int generationStreams() {
        int streams=Integer.getInteger("extension.gpuGenerationStreams",1);
        if(streams<1)throw new IllegalArgumentException("GPU generation streams must be positive");
        return streams;
    }
    void validateAvailable(int count) {
        for(int ordinal:layout.devices())if(ordinal>=count)throw new IllegalArgumentException("CUDA device "+ordinal+" is unavailable; visible device count="+count);
    }
    long slotBudget(long freeBytes,long generatorBytes,double fraction) {
        if(fraction<=0||fraction>0.95||freeBytes<=generatorBytes)throw new IllegalArgumentException("Insufficient free GPU memory after reserving generation buffers");
        long automatic=(long)((freeBytes-generatorBytes)*fraction)/layout.slotsPerDevice();
        return slotMemoryBytes==0?automatic:Math.min(automatic,slotMemoryBytes);
    }
}
