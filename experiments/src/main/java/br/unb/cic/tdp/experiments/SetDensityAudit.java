package br.unb.cic.tdp.experiments;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Read-only sizing and bounded exactness audit for completed canonical rank archives. */
final class SetDensityAudit {
    static void run() throws Exception {
        Path root=ExtensionConfig.system().dataDirectory();
        int maximum=Integer.getInteger("count.maxN",18);
        int limit=Integer.getInteger("density.maxRecords",100_000);
        if(maximum<1||maximum>18||limit<0)throw new IllegalArgumentException("Density audit requires degree 1..18 and a nonnegative record limit");
        if(!Files.isDirectory(root))throw new IOException("Layer root does not exist: "+root);
        List<Path> directories;
        try(var paths=Files.walk(root,3)) {
            directories=paths.filter(p->p.getFileName().toString().equals("manifest.properties"))
                .map(Path::getParent).filter(p->p.getFileName().toString().matches("(?:distance|floor)-[0-9]{2}"))
                .sorted().toList();
        }
        System.out.printf("DENSITY_AUDIT root=%s max_n=%d audit_limit=%d estimates_include_header_and_bucket_index=true java_object_headers_excluded=true%n",root,maximum,limit);
        int reported=0,audited=0;
        for(Path directory:directories) {
            LayerManifest manifest=LayerManifest.read(directory);
            if(manifest.degree>maximum)continue;
            long records=manifest.canonicalSize();
            long estimate=CompactRankArchive.bytesFor(manifest);
            int rankBytes=PermutationRank.bytes(manifest.degree);
            System.out.printf(Locale.ROOT,
                "DENSITY_SET n=%d floor=%d scope=%s canonicals=%d omega_key_bytes=%d fixed_rank_bytes=%d dense_membership_bytes=%d ef_estimated_bytes=%d bitmap_to_ef_ratio=%.3f directory=%s%n",
                manifest.degree,manifest.floor,manifest.exact?"exact":"tail",records,PackedPermutation5.bytes(manifest.degree+1),rankBytes,
                GpuMembershipTable.bytesForDegree(manifest.degree),estimate,estimate==0?0:(double)GpuMembershipTable.bytesForDegree(manifest.degree)/estimate,directory);
            reported++;
            if(records<=limit){audit(directory,manifest);audited++;}
        }
        System.out.printf("DENSITY_DONE reported_stores=%d audited_stores=%d storage=canonical-rank-archive-v1%n",reported,audited);
    }
    private static void audit(Path directory,LayerManifest manifest) throws Exception {
        int n=manifest.degree;long began=System.nanoTime();
        long[] ranks=new long[Math.toIntExact(manifest.canonicalSize())],canonical=new long[12],weighted=new long[12];
        byte[] omega=new byte[n+1],inverse=new byte[n+1],word=new byte[n];
        try(CompactRankArchive archive=CompactRankArchive.open(directory,manifest)) {
            var cursor=archive.ranks();
            for(int i=0;i<ranks.length;i++) {
                long rank=cursor.next();ranks[i]=rank;int distance=archive.distanceAt(i);
                if(rank<0||i>0&&rank<=ranks[i-1]||archive.find(rank)!=i||archive.distance(rank)!=distance)
                    throw new IOException("Compressed rank round trip failed: "+directory);
                SmallTables.unrank(rank,n,word,0);ToricOrbit.omegaFromWord(word,n,omega);
                if(PermutationRank.ofOmega(omega,n)!=rank)throw new IOException("Rank/omega mismatch: "+directory);
                int weight=ToricOrbit.weightOfOmega(omega,inverse,n+1);
                if(weight<=0)throw new IOException("Noncanonical stored rank: "+directory);
                canonical[distance]++;weighted[distance]+=weight;
            }
            if(cursor.next()!=-1||!Arrays.equals(canonical,manifest.canonical)||!Arrays.equals(weighted,manifest.weighted))
                throw new IOException("Rank archive count mismatch: "+directory);
            Random random=new Random(20261107L+n);int probes=10000;
            for(int i=0;i<probes;i++) {
                long rank=random.nextLong(SmallTables.FACTORIAL[n]);
                if((archive.find(rank)>=0)!=(Arrays.binarySearch(ranks,rank)>=0))
                    throw new IOException("Compressed membership mismatch: "+directory);
            }
            System.out.printf(Locale.ROOT,"DENSITY_VERIFIED n=%d floor=%d canonicals=%d compressed_bytes=%d all_keys_round_tripped=true orbit_counts_verified=true random_membership_probes=%d seconds=%.3f%n",
                n,manifest.floor,ranks.length,archive.bytes,probes,(System.nanoTime()-began)/1e9);
        }
    }
}
