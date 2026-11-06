typedef unsigned char Byte;
typedef unsigned long long Word;
__device__ __constant__ int pattern_offset_bytes=sizeof(Word);
__device__ __constant__ Word factorial[19]={1ULL,1ULL,2ULL,6ULL,24ULL,120ULL,720ULL,5040ULL,40320ULL,362880ULL,3628800ULL,39916800ULL,479001600ULL,6227020800ULL,87178291200ULL,1307674368000ULL,20922789888000ULL,355687428096000ULL,6402373705728000ULL};
__device__ int orbit_weight(const Byte* word,int n) {
    // Fixed target sigma=(0 1 ... n), left products as in ../td.
    // omega(x)=sigma(beta^{-1}(x)); sigma is implicit in these coordinates.
    Byte omega[19],inverse[19];int previous=0,N=n+1;
    for(int i=0;i<n;i++){int value=word[i];omega[value]=previous==n?0:previous+1;previous=value;}
    omega[0]=previous==n?0:previous+1;
    for(int x=0;x<N;x++)inverse[omega[x]]=x;
    int stabilizer=0;
    for(int shift=0;shift<N;shift++) {
        bool equal=true;
        for(int x=0;x<N;x++) {
            int source=x-shift;if(source<0)source+=N;
            int mapped=omega[source]+shift;if(mapped>=N)mapped-=N;
            if(mapped<omega[x])return 0;
            if(mapped>omega[x]){equal=false;break;}
        }
        if(equal)stabilizer++;
        int a=shift+1;if(a==N)a=0;
        equal=true;
        for(int x=0;x<N;x++) {
            int source=a-x;if(source<0)source+=N;
            int mapped=a-inverse[source];if(mapped<0)mapped+=N;
            if(mapped<omega[x])return 0;
            if(mapped>omega[x]){equal=false;break;}
        }
        if(equal)stabilizer++;
    }
    return 2*N/stabilizer;
}
__device__ void decode_rank(Word rank,int n,Byte* word) {
    unsigned available=(1U<<n)-1;
    for(int i=0;i<n;i++){
        int index=rank/factorial[n-1-i];rank%=factorial[n-1-i];unsigned remaining=available;
        while(index-->0)remaining&=remaining-1;
        int bit=__ffs(remaining)-1;word[i]=bit+1;available&=~(1U<<bit);
    }
}
__device__ Word packed_orbit_rank(const Byte* packed,int n,int action) {
    int N=n+1;Byte inverse[19],word[18];
    for(int i=0;i<N;i++) {
        int bit=5*i,index=bit>>3,shift=3-(bit&7);
        int v=shift>=0?(packed[index]>>shift)&31:((packed[index]<<-shift)|(packed[index+1]>>(8+shift)))&31;
        inverse[v]=i;
    }
    int x=0;for(int i=0;i<n;i++){x=inverse[x==n?0:x+1];word[i]=x;}
    int anchor=action>>1,direction=(action&1)?-1:1,value=anchor==0?0:word[anchor-1];
    Word rank=0;unsigned used=0;
    for(int j=1;j<N;j++) {
        int position=(anchor+direction*j+N)%N,symbol=position==0?0:word[position-1];
        int v=direction==1?(symbol-value+N)%N:(value-symbol+N)%N;
        rank=rank*(N-j)+v-1-__popc(used&((1U<<(v-1))-1));used|=1U<<(v-1);
    }
    return rank;
}
extern "C" __global__ void populate_core_table(int records,int n,int row_bytes,Word offset,const Byte* rows,signed char* table) {
    int job=blockIdx.x*blockDim.x+threadIdx.x,N=n+1,parent=job/(2*N),action=job%(2*N);
    if(parent>=records)return;
    Word index=offset+packed_orbit_rank(rows+parent*row_bytes+1,n,action);unsigned shift=(index&7)*4;
    unsigned* cell=((unsigned*)table)+(index>>3);
    atomicAnd(cell,~(15U<<shift));atomicOr(cell,(unsigned)rows[parent*row_bytes]<<shift);
}
extern "C" __global__ void populate_membership(int records,int n,int distance,int row_bytes,const Byte* rows,unsigned* bits,Word* errors) {
    int job=blockIdx.x*blockDim.x+threadIdx.x,N=n+1,parent=job/(2*N),action=job%(2*N);
    if(parent>=records)return;
    if(rows[parent*row_bytes]!=distance){if(action==0)atomicAdd(errors,1ULL);return;}
    Word rank=packed_orbit_rank(rows+parent*row_bytes+1,n,action);
    if(rank>=factorial[n]){atomicAdd(errors,1ULL);return;}
    atomicOr(bits+(rank>>5),1U<<(rank&31));
}
extern "C" __global__ void audit_membership(const unsigned* bits,Word words,Word* cardinality) {
    Word sum=0;
    for(Word i=(Word)blockIdx.x*blockDim.x+threadIdx.x;i<words;i+=(Word)gridDim.x*blockDim.x)sum+=__popc(bits[i]);
    for(int offset=16;offset>0;offset>>=1)sum+=__shfl_down_sync(0xffffffff,sum,offset);
    if((threadIdx.x&31)==0)atomicAdd(cardinality,sum);
}
// Only completed, cardinality-checked diametral bitmaps are exposed to search.
// An unset bit proves distance <= distance-1, not an exact smaller distance.
__device__ __forceinline__ int membership_distance(const unsigned* bits,Word rank,int distance) {
    return (bits[rank>>5]&(1U<<(rank&31)))?distance:-distance-1;
}
extern "C" __global__ void initialize_pattern_identities(int maximum,const Word* offsets,signed char* table) {
    int n=threadIdx.x;if(n>maximum)return;Word index=offsets[n];
    atomicAnd(((unsigned*)table)+(index>>3),~(15U<<((index&7)*4)));
}
__device__ __forceinline__ int pattern_distance(const signed char* table,const Word* offsets,int n,Word rank) {
    Word index=offsets[n]+rank;int value=(((const Byte*)table)[index>>1]>>((index&1)*4))&15;
    return value==15?-(int)offsets[19+n]-1:value;
}
// Bounded diagnostics can probe high-rank entries without scanning an entire table.
extern "C" __global__ void query_pattern_distances(int n,int jobs,const Word* ranks,const signed char* table,const Word* offsets,int* distances) {
    int job=blockIdx.x*blockDim.x+threadIdx.x;if(job>=jobs)return;
    distances[job]=pattern_distance(table,offsets,n,ranks[job]);
}
extern "C" __global__ void audit_canonical_weights(int jobs,int n,Byte* weights) {
    int index=blockIdx.x*blockDim.x+threadIdx.x;if(index>=jobs)return;
    Byte word[18];decode_rank(index,n,word);weights[index]=orbit_weight(word,n);
}
__device__ int lower_bound(const Byte* p,int n) {
    Byte pred[19]; int previous=0;
    for(int i=0;i<n;i++){int v=p[i];pred[v]=previous;previous=v;} pred[0]=previous;
    unsigned visited=0; int odd=0;
    for(int s=0;s<=n;s++) {
        if(visited&(1U<<s))continue;
        int x=s,length=0;
        do{visited|=1U<<x;length++;x=pred[x==n?0:x+1];}while(x!=s);
        odd+=length&1;
    }
    return (n+1-odd)/2;
}
__device__ int core_distance(const Byte* p,int n,const signed char* table,const Word* offsets,int table_degree,
    const unsigned* membership,int membership_degree,int membership_floor) {
    if(table_degree<0 && membership_degree<0)return -1;
    Byte tmp[18];for(int i=0;i<n;i++)tmp[i]=p[i];
    int m=n;
    while(m>0) {
        int omit=-1;
        if(tmp[0]==1)omit=0;
        else if(tmp[m-1]==m)omit=m-1;
        else for(int i=1;i<m;i++)if(tmp[i]==tmp[i-1]+1){omit=i;break;}
        if(omit<0)break;
        int value=tmp[omit];
        for(int i=0,j=0;i<m;i++)if(i!=omit){int v=tmp[i];tmp[j++]=v-(v>value);}
        m--;
    }
    if(m>table_degree && m!=membership_degree)return -1;
    Word rank=0;unsigned used=0;
    for(int i=0;i<m;i++){int v=tmp[i];rank=rank*(m-i)+v-1-__popc(used&((1U<<(v-1))-1));used|=1U<<(v-1);}
    return m<=table_degree?pattern_distance(table,offsets,m,rank):membership_distance(membership,rank,membership_floor);
}
extern "C" __global__ void filter_count_roots(Word first,int jobs,int n,int capacity,int begin,
    Word* roots,Byte* weights,int* canonical_count,Word* counters) {
    int index=begin+blockIdx.x*blockDim.x+threadIdx.x;
    Word rank=first+index;int weight=0;
    if(index<jobs){Byte word[18];decode_rank(rank,n,word);weight=orbit_weight(word,n);}
    unsigned rejected=__ballot_sync(0xFFFFFFFF,index<jobs && weight==0);
    if((threadIdx.x&31)==0 && rejected)atomicAdd(counters,(Word)__popc(rejected));
    if(index>=jobs || weight==0)return;
    int slot=atomicAdd(canonical_count,1);
    // The host detects an overflow and bisects the raw rank chunk before
    // searching anything. Never drop a canonical root on overflow.
    if(slot>=capacity)return;
    roots[slot]=rank;weights[slot]=weight;
}
__device__ __forceinline__ Word stack_index(int job,int frame,int i,int n,int frames,int stride) {
#ifdef SOA_STACK
    return ((Word)frame*n+i)*stride+job;
#else
    return ((Word)job*frames+frame)*n+i;
#endif
}
extern "C" __global__ void validate_root_keys(int jobs,int n,const Word* roots,const Byte* keys,Word* errors) {
    int job=blockIdx.x*blockDim.x+threadIdx.x;if(job>=jobs)return;
    if(roots[job]!=packed_orbit_rank(keys+(Word)job*((5*(n+1)+7)/8),n,0))atomicAdd(errors,1ULL);
}
extern "C" __global__ void initialize_count(int jobs,int n,int frames,int stride,int begin,
    const Word* roots,Byte* stack,short* next,signed char* depth,Byte* stage,int* active) {
    int job=begin+blockIdx.x*blockDim.x+threadIdx.x;if(job>=jobs)return;
    Byte word[18];decode_rank(roots[job],n,word);
    for(int i=0;i<n;i++)stack[stack_index(job,0,i,n,frames,stride)]=word[i];
#ifdef SOA_STACK
    next[job]=-1;
#else
    next[(Word)job*frames]=-1;
#endif
    depth[job]=0;stage[job]=0;active[job]=job;
}
extern "C" __global__ void advance_count(int active_count,int begin,const int* active,int* output,
    int* output_count,int n,int frames,int stride,int low,int high,int quantum,const int* cuts,int cut_count,
    Byte* stack,short* next,signed char* depth,Byte* stage,const Byte* weights,Byte* results,const signed char* table,const Word* offsets,
    int table_degree,const unsigned* membership,int membership_degree,int membership_floor,Word* counters) {
    int index=begin+blockIdx.x*blockDim.x+threadIdx.x;if(index>=active_count)return;
    int job=active[index],d=depth[job],phase=stage[job],classification=-1;
    Byte word[18],child[18];int cached=-2;
#ifdef SOA_STACK
    short* iter=next+job;int iter_stride=stride;
#else
    short* iter=next+(Word)job*frames;int iter_stride=1;
#endif
    for(int tick=0;tick<quantum && classification<0;tick++) {
        if(d<0) {
            if(phase==0 && low<high){phase=1;d=0;iter[0]=-1;}
            else{classification=phase==0?1:2;break;}
        }
        int remaining=(phase==0?low-1:high-1)-d;
        if(cached!=d){for(int i=0;i<n;i++)word[i]=stack[stack_index(job,d,i,n,frames,stride)];cached=d;}
        if(iter[d*iter_stride]<0) {
            int lower=lower_bound(word,n);
            if(lower==0){classification=phase==0?0:1;break;}
            if(lower>remaining || remaining==0){d--;continue;}
            int exact=core_distance(word,n,table,offsets,table_degree,membership,membership_degree,membership_floor);
            if(exact<-1 && -exact-2<=remaining){classification=phase==0?0:1;break;}
            if(exact>=0){if(exact<=remaining){classification=phase==0?0:1;break;}d--;continue;}
            iter[d*iter_stride]=0;
        }
        if(iter[d*iter_stride]>=cut_count){d--;continue;}
        int code=cuts[iter[d*iter_stride]++],a=code&31,b=(code>>5)&31,c=code>>10;
        int j=0;
        for(int i=0;i<a;i++)child[j++]=word[i];
        for(int i=b;i<c;i++)child[j++]=word[i];
        for(int i=a;i<b;i++)child[j++]=word[i];
        for(int i=c;i<n;i++)child[j++]=word[i];
        bool inverse=d>0;
        if(inverse)for(int i=0;i<n;i++)if(child[i]!=stack[stack_index(job,d-1,i,n,frames,stride)]){inverse=false;break;}
        if(inverse)continue;
        d++;iter[d*iter_stride]=-1;
        for(int i=0;i<n;i++){stack[stack_index(job,d,i,n,frames,stride)]=child[i];word[i]=child[i];}cached=d;
    }
    if(classification>=0){results[job]=(Byte)classification;atomicAdd(counters,1ULL);if(classification>0)atomicAdd(counters+classification,(Word)weights[job]);}
    // -1 is a valid continuation: root exhaustion may occur on the last
    // quantum tick. Preserve its sign so the next launch finalizes it.
    else{depth[job]=(signed char)d;stage[job]=phase;int slot=atomicAdd(output_count,1);output[slot]=job;}
}

__device__ Word rank_word(const Byte* p,int n) {
    Word rank=0;unsigned used=0;
    for(int i=0;i<n;i++){int v=p[i];rank=rank*(n-i)+v-1-__popc(used&((1U<<(v-1))-1));used|=1U<<(v-1);}
    return rank;
}
__device__ void omega_of_word(const Byte* word,int n,Byte* omega) {
    int previous=0;for(int i=0;i<n;i++){int v=word[i];omega[v]=previous==n?0:previous+1;previous=v;}omega[0]=previous==n?0:previous+1;
}
__device__ void pack_omega(const Byte* omega,int N,Byte* packed) {
    int bytes=(5*N+7)/8;for(int i=0;i<bytes;i++)packed[i]=0;
    for(int i=0;i<N;i++){int bit=5*i,index=bit>>3,shift=3-(bit&7),v=omega[i];
        if(shift>=0)packed[index]|=v<<shift;else {packed[index]|=v>>-shift;packed[index+1]|=v<<(8+shift);}}
}
__device__ void canonical_omega_key(const Byte* word,int n,Byte* packed) {
    Byte omega[19],inverse[19],least[19];int N=n+1;omega_of_word(word,n,omega);
    for(int x=0;x<N;x++){inverse[omega[x]]=x;least[x]=omega[x];}
    for(int shift=0;shift<N;shift++)for(int reflection=0;reflection<2;reflection++) {
        int a=shift+1;if(a==N)a=0;bool smaller=false;
        for(int x=0;x<N;x++) {
            int source=reflection==0?x-shift:a-x;if(source<0)source+=N;
            int mapped=reflection==0?omega[source]+shift:a-inverse[source];if(mapped<0)mapped+=N;else if(mapped>=N)mapped-=N;
            if(mapped<least[x]){smaller=true;break;}if(mapped>least[x])break;
        }
        if(smaller)for(int x=0;x<N;x++) {
            int source=reflection==0?x-shift:a-x;if(source<0)source+=N;
            int mapped=reflection==0?omega[source]+shift:a-inverse[source];if(mapped<0)mapped+=N;else if(mapped>=N)mapped-=N;least[x]=mapped;
        }
    }
    pack_omega(least,N,packed);
}
// Diagnostic only: bounded deterministic sampling from a completed membership set.
extern "C" __global__ void sample_membership(int jobs,int n,Word seed,const unsigned* bits,Byte* output,int* errors) {
    int job=blockIdx.x*blockDim.x+threadIdx.x;if(job>=jobs)return;
    Word state=seed^((Word)(job+1)*0x9e3779b97f4a7c15ULL);if(state==0)state=1;
    for(int attempt=0;attempt<4096;attempt++) {
        state^=state>>12;state^=state<<25;state^=state>>27;
        Word rank=(state*0x2545f4914f6cdd1dULL)%factorial[n];
        if(bits[rank>>5]&(1U<<(rank&31))) {
            Byte word[18];decode_rank(rank,n,word);canonical_omega_key(word,n,output+(Word)job*((5*(n+1)+7)/8));return;
        }
    }
    atomicAdd(errors,1);
}
extern "C" __global__ void decode_sample_ranks(int jobs,int n,const Byte* keys,Word* ranks) {
    int job=blockIdx.x*blockDim.x+threadIdx.x;if(job>=jobs)return;
    ranks[job]=packed_orbit_rank(keys+(Word)job*((5*(n+1)+7)/8),n,0);
}
extern "C" __global__ void prepare_parent_members(int parents,int n,int row_bytes,const Byte* rows,Word* members,int* counts) {
    int parent=blockIdx.x,action=threadIdx.x,N=n+1;if(parent>=parents)return;
    __shared__ Word ranks[38];Word rank=~0ULL;
    if(action<2*N) {
        const Byte* packed=rows+parent*row_bytes+1;Byte inverse[19],word[18],member[18];
        for(int i=0;i<N;i++){int bit=5*i,index=bit>>3,shift=3-(bit&7);
            int v=shift>=0?(packed[index]>>shift)&31:((packed[index]<<-shift)|(packed[index+1]>>(8+shift)))&31;inverse[v]=i;}
        int x=0;for(int i=0;i<n;i++){x=inverse[x==n?0:x+1];word[i]=x;}
        int anchor=action>>1,direction=(action&1)?-1:1,value=anchor==0?0:word[anchor-1];
        for(int j=1;j<N;j++){int position=(anchor+direction*j+N)%N,symbol=position==0?0:word[position-1];member[j-1]=direction==1?(symbol-value+N)%N:(value-symbol+N)%N;}
        rank=rank_word(member,n);ranks[action]=rank;
    }
    __syncthreads();
    if(action<2*N){for(int i=0;i<action;i++)if(ranks[i]==rank){rank=~0ULL;break;}members[parent*2*N+action]=rank;if(rank!=~0ULL)atomicAdd(counts+4,n+1);}
}
extern "C" __global__ void generate_extensions(int parents,int n,int floor,int upper,int parent_floor,int row_bytes,const Byte* rows,const Word* members,
    int dense_parent,const signed char* table,const Word* offsets,const unsigned* membership,int membership_floor,Byte* output,Byte* deletion_keys,int* counts) {
    int job=blockIdx.x*blockDim.x+threadIdx.x,total=parents*2*n*n;if(job>=total)return;
    int member_index=job/n,position=job%n,parent=member_index/(2*n);Word member_rank=members[member_index];if(member_rank==~0ULL)return;
    Byte member[18],word[18],deletion[18],omega[19];decode_rank(member_rank,n-1,member);
    for(int i=0;i<position;i++)word[i]=member[i];word[position]=n;for(int i=position;i<n-1;i++)word[i+1]=member[i];
    int weight=orbit_weight(word,n);if(weight==0)return;atomicAdd(counts+1,1);
    int q=rows[parent*row_bytes],minimum=q,maximum=q,hi=minimum+1;if(hi>upper)hi=upper;
    if(dense_parent)for(int omitted=0;omitted<n && maximum<hi;omitted++) {
        if(omitted==position)continue;int value=word[omitted];
        for(int i=0,j=0;i<n;i++)if(i!=omitted){int v=word[i];deletion[j++]=v-(v>value);}
        Word rank=rank_word(deletion,n-1);
        int d=dense_parent==2?membership_distance(membership,rank,membership_floor):pattern_distance(table,offsets,n-1,rank);
        if(d<parent_floor){if(parent_floor==0&&d<0)atomicAdd(counts+5,1);atomicAdd(counts+2,1);return;}
        if(d<minimum)minimum=d;if(d>maximum)maximum=d;hi=minimum+1;if(hi>upper)hi=upper;
    }
    if(maximum>hi){atomicAdd(counts+5,1);return;}if(hi<floor)return;
    int slot=atomicAdd(counts,1),payload=(5*(n+1)+7)/8,out_bytes=payload+10;
    Byte* out=output+(Word)slot*out_bytes;
    Word rank=maximum==hi?0:rank_word(word,n);for(int b=0;b<8;b++)out[b]=(Byte)(rank>>(8*b));
    out[8]=maximum==hi?maximum:dense_parent?(0x80|maximum):(0x40|q);omega_of_word(word,n,omega);pack_omega(omega,n+1,out+9);out[9+payload]=weight;
    if(maximum==hi)atomicAdd(counts+3,1);
    else if(!dense_parent) {
        int key_bytes=(5*n+7)/8;
        for(int omitted=0;omitted<n;omitted++) {
            int value=word[omitted];for(int i=0,j=0;i<n;i++)if(i!=omitted){int v=word[i];deletion[j++]=v-(v>value);}
            canonical_omega_key(deletion,n-1,deletion_keys+((Word)slot*n+omitted)*key_bytes);
        }
    }
}

extern "C" __global__ void audit_pattern_histogram(int n,const signed char* table,const Word* offsets,Word* histogram) {
    Word count=factorial[n],rounded=(count+31)&~31ULL,stride=(Word)gridDim.x*blockDim.x;
    for(Word rank=(Word)blockIdx.x*blockDim.x+threadIdx.x;rank<rounded;rank+=stride) {
        Word index=offsets[n]+rank;
        int value=rank<count?(((const Byte*)table)[index>>1]>>((index&1)*4))&15:16;
        for(int d=0;d<16;d++) {
            unsigned mask=__ballot_sync(0xffffffff,value==d);
            if((threadIdx.x&31)==0&&mask)atomicAdd(histogram+d,(Word)__popc(mask));
        }
    }
}
