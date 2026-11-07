package br.unb.cic.tdp.experiments;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Published only after the asynchronous writer has drained and flushed. */
final class LayerManifest {
    static final String ENCODING="canonical-beta-rank-fixed-sigma-v1";
    static final String STORAGE="canonical-rank-archive-v1";
    final String storage;
    final boolean exact;
    final int degree,floor,maxDistance,horizon;
    final long[] canonical,weighted;
    LayerManifest(int degree,int floor,int horizon,long[] canonical,long[] weighted) {
        this(degree,floor,horizon,canonical,weighted,STORAGE,false);
    }
    LayerManifest(int degree,int floor,int horizon,long[] canonical,long[] weighted,boolean exact) {
        this(degree,floor,horizon,canonical,weighted,STORAGE,exact);
    }
    private LayerManifest(int degree,int floor,int horizon,long[] canonical,long[] weighted,String storage,boolean exact) {
        this.degree=degree;this.floor=floor;this.horizon=horizon;
        this.storage=storage;this.exact=exact;
        this.canonical=canonical.clone();this.weighted=weighted.clone();
        int maximum=-1;
        for(int d=0;d<weighted.length;d++)if(weighted[d]>0)maximum=d;
        if(maximum<floor&&!exact)throw new IllegalArgumentException("Empty retained distance set");
        if(exact)for(int d=0;d<weighted.length;d++)if(d!=floor&&weighted[d]!=0)throw new IllegalArgumentException("Exact store contains another distance");
        maxDistance=exact?floor:maximum;
    }
    long canonicalSize(){long n=0;for(long x:canonical)n=Math.addExact(n,x);return n;}
    void write(Path directory) throws IOException {
        Properties p=new Properties();
        p.setProperty("complete","true");p.setProperty("encoding",ENCODING);
        p.setProperty("storage",storage);
        p.setProperty("scope",exact?"exact":"tail");
        p.setProperty("degree",Integer.toString(degree));p.setProperty("symbols",Integer.toString(degree+1));
        p.setProperty("floor",Integer.toString(floor));p.setProperty("maxDistance",Integer.toString(maxDistance));
        p.setProperty("horizon",Integer.toString(horizon));
        for(int d=0;d<weighted.length;d++){p.setProperty("canonical."+d,Long.toString(canonical[d]));p.setProperty("weighted."+d,Long.toString(weighted[d]));}
        try(OutputStream out=Files.newOutputStream(directory.resolve("manifest.properties"))){p.store(out,"Complete canonical distance layers; no sorting witnesses");}
    }
    static LayerManifest read(Path directory) throws IOException {
        Properties p=new Properties();
        try(InputStream in=Files.newInputStream(directory.resolve("manifest.properties"))){p.load(in);}
        if(!"true".equals(p.getProperty("complete"))||!ENCODING.equals(p.getProperty("encoding")))throw new IOException("Incomplete or incompatible layer store: "+directory);
        String storage=p.getProperty("storage");
        if(!STORAGE.equals(storage))throw new IOException("Unsupported key/value format: "+storage+"; start a fresh compact run");
        try {
            int degree=Integer.parseInt(p.getProperty("degree")),floor=Integer.parseInt(p.getProperty("floor"));
            if(degree<1||degree>18||floor<0||floor>11||Integer.parseInt(p.getProperty("symbols"))!=degree+1)throw new IOException("Invalid layer header");
            long[] canonical=new long[12],weighted=new long[12];
            for(int d=0;d<12;d++) {
                canonical[d]=Long.parseLong(p.getProperty("canonical."+d,"0"));weighted[d]=Long.parseLong(p.getProperty("weighted."+d,"0"));
                if(canonical[d]<0||weighted[d]<canonical[d]||(d<floor&&weighted[d]!=0))throw new IOException("Invalid layer counts");
            }
            String scope=p.getProperty("scope","tail");
            if(!scope.equals("tail")&&!scope.equals("exact"))throw new IOException("Invalid layer scope");
            LayerManifest result=new LayerManifest(degree,floor,Integer.parseInt(p.getProperty("horizon")),canonical,weighted,storage,scope.equals("exact"));
            if(result.maxDistance!=Integer.parseInt(p.getProperty("maxDistance")))throw new IOException("Invalid maximum distance");
            return result;
        }catch(IllegalArgumentException failure){throw new IOException("Malformed layer manifest: "+directory,failure);}
    }
}
