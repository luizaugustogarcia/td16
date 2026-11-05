// Short certificate search only. A miss must fall through to complete sorting.
typedef unsigned char Byte;
typedef unsigned long long Rank;

__device__ int contract_prefix(Byte* p,int m) {
    while(m>0) {
        int omit=-1;
        if(p[0]==1)omit=0;
        else if(p[m-1]==m)omit=m-1;
        else for(int i=1;i<m;i++)if(p[i]==p[i-1]+1){omit=i;break;}
        if(omit<0)break;
        int value=p[omit];
        for(int i=0,j=0;i<m;i++)if(i!=omit){int v=p[i];p[j++]=v-(v>value);}
        --m;
    }
    return m;
}

extern "C" __global__ void initialize_prefix(int jobs,int n,const Rank* ranks,
        const int* roots,const int* trie,Byte* words,int* nodes,int* cursors,
        signed char* depths,Byte* certificates,Rank* trials) {
    int id=blockIdx.x*blockDim.x+threadIdx.x;if(id>=jobs)return;
    Rank factorial[19];factorial[0]=1;for(int i=1;i<=n;i++)factorial[i]=factorial[i-1]*i;
    Byte p[18];unsigned available=(1u<<n)-1;Rank rank=ranks[id];
    for(int i=0;i<n;i++) {
        int k=(int)(rank/factorial[n-1-i]);rank%=factorial[n-1-i];unsigned bits=available;
        while(k-->0)bits&=bits-1;
        int value=__ffs(bits);p[i]=value;available&=~(1u<<(value-1));
    }
    int m=contract_prefix(p,n),node=roots[m];
    certificates[id]=255;trials[id]=0;depths[id]=-1;
    if(node<0)return;
    if(trie[node*6+1]){certificates[id]=0;return;}
    for(int i=0;i<m;i++)words[i*jobs+id]=p[i];
    nodes[id]=node;cursors[id]=0;depths[id]=0;
}

extern "C" __global__ void advance_prefix(int jobs,int quantum,const int* trie,
        const int* cuts,const int* cutOffsets,Byte* words,int* nodes,int* cursors,
        signed char* depths,Byte* certificates,Rank* trials,unsigned* pending) {
    int id=blockIdx.x*blockDim.x+threadIdx.x;if(id>=jobs)return;
    int depth=depths[id];if(depth<0)return;
    Rank tried=trials[id];
    for(int step=0;step<quantum&&depth>=0;step++) {
        int frame=depth*jobs+id,node=nodes[frame],m=trie[node*6];
        int count=cutOffsets[m+1]-cutOffsets[m],cursor=cursors[frame];
        if(count==0||cursor>=4*count){--depth;continue;}
        int gain=3-cursor/count;
        int child=trie[node*6+2+gain];
        if(child<0){cursors[frame]=(cursor/count+1)*count;continue;}
        int cut=cuts[cutOffsets[m]+cursor%count];cursors[frame]=cursor+1;++tried;
        int a=cut&31,b=(cut>>5)&31,c=(cut>>10)&31;
        const Byte* parent=words+depth*18*jobs+id;
        int left=a?parent[(a-1)*jobs]:0,right=c<m?parent[c*jobs]:m+1;
        int fixed=(parent[b*jobs]==left+1)+(parent[a*jobs]==parent[(c-1)*jobs]+1)+(right==parent[(b-1)*jobs]+1);
        if(fixed!=gain)continue;
        Byte p[18];for(int i=0;i<m;i++)p[i]=parent[i*jobs];
        Byte q[18];int j=0;
        for(int i=0;i<a;i++)q[j++]=p[i];
        for(int i=b;i<c;i++)q[j++]=p[i];
        for(int i=a;i<b;i++)q[j++]=p[i];
        for(int i=c;i<m;i++)q[j++]=p[i];
        int reduced=contract_prefix(q,m);
        // Each parent is irreducible; the three new boundaries account for
        // all contraction. The identity has m+1 fixed boundaries and degree 0.
        if(reduced!=trie[child*6]){certificates[id]=254;depth=-1;break;}
        ++depth;
        if(trie[child*6+1]){certificates[id]=(Byte)depth;depth=-1;break;}
        for(int i=0;i<reduced;i++)words[(depth*18+i)*jobs+id]=q[i];
        nodes[depth*jobs+id]=child;cursors[depth*jobs+id]=0;
    }
    trials[id]=tried;depths[id]=(signed char)depth;
    if(depth>=0)atomicAdd(pending,1u);
}
