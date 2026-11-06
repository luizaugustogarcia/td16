package br.unb.cic.tdp.experiments;

/** Four-bit rank tables and their shared Java/CUDA metadata, with 64-bit entry offsets. */
final class GpuPatternLayout {
    static final int MAX_DEGREE=18;
    static final int DEGREES=19;
    static final long METADATA_BYTES=2L*DEGREES*Long.BYTES;
    // First half: entry offsets. Second half: retained distance floors.
    final long[] offsets=new long[2*DEGREES];
    final long bytes;

    GpuPatternLayout(int degree) {
        if(degree<0||degree>MAX_DEGREE)throw new IllegalArgumentException("GPU pattern degree must be 0.."+MAX_DEGREE);
        long entries=0;
        for(int n=0;n<=degree;n++){offsets[n]=entries;entries+=SmallTables.FACTORIAL[n];}
        // Round to 32-bit words for the kernels' atomic nibble updates.
        bytes=((entries+7)/8)*Integer.BYTES;
    }
    static int configuredDegree() {
        String value=System.getProperty("extension.gpuPatternMaxDegree","auto");
        if(value.equals("auto"))return MAX_DEGREE;
        int degree=Integer.parseInt(value);
        if(degree<0||degree>MAX_DEGREE)throw new IllegalArgumentException("GPU pattern degree must be 0.."+MAX_DEGREE);
        return degree;
    }
}
