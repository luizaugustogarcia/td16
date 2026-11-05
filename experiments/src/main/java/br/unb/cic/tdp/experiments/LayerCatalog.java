package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Completed exact sets, with compatibility for existing complete tail stores. */
final class LayerCatalog {
    final Path root;
    LayerCatalog(Path root){this.root=root;}
    static Path path(Path root,int n,int d){return root.resolve(String.format(Locale.ROOT,"n-%02d/distance-%02d",n,d));}
    Path find(int n,int d) throws IOException {
        Path exact=path(root,n,d);
        if(Files.isRegularFile(exact.resolve("manifest.properties"))) {
            LayerManifest manifest=LayerManifest.read(exact);
            if(!manifest.exact||manifest.degree!=n||manifest.floor!=d)throw new IOException("Invalid exact set: "+exact);
            return exact;
        }
        return CanonicalLayerStore.find(root,n,d);
    }
    boolean has(int n,int d) throws IOException {return find(n,d)!=null;}
    long size(int n,int d) throws IOException {
        Path path=find(n,d);if(path==null)throw new IOException("Missing E("+n+","+d+")");
        return LayerManifest.read(path).weighted[d];
    }
    CanonicalLayerStore open(int n,int floor,int ceiling) throws IOException {
        Map<Path,Integer> selected=new LinkedHashMap<>();
        for(int d=floor;d<=ceiling;d++) {
            Path source=find(n,d);if(source==null)throw new IOException("Missing complete E("+n+","+d+")");
            selected.merge(source,1<<d,(a,b)->a|b);
        }
        return new CanonicalLayerStore(root,n,floor,selected);
    }
    int tailFloor(int n) throws IOException {
        int floor=RetentionPlan.upper(n)+1;
        while(floor>0&&has(n,floor-1))floor--;
        return floor>RetentionPlan.upper(n)?-1:floor;
    }
    CanonicalLayerStore openTail(int n) throws IOException {
        int floor=tailFloor(n);if(floor<0)throw new IOException("No complete upper tail for degree "+n);
        return open(n,floor,RetentionPlan.upper(n));
    }
}
