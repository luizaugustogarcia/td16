package br.unb.cic.tdp.experiments;

/** Cache capacity after reserving the next operation's generation and search workspaces. */
record GpuCacheBudget(long reservedBytes,double fraction) {
    GpuCacheBudget {
        if(reservedBytes<0||fraction<=0||fraction>0.95)throw new IllegalArgumentException("Invalid GPU cache budget");
    }
    long available(long freeBytes,long replacedBytes) {
        return Math.max(0,(long)((freeBytes+replacedBytes)*fraction)-reservedBytes);
    }
    boolean fits(long bytes,long freeBytes,long replacedBytes){return bytes<=available(freeBytes,replacedBytes);}
    GpuCacheBudget reserve(long bytes){return new GpuCacheBudget(Math.addExact(reservedBytes,bytes),fraction);}
}
