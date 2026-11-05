// Prepended at runtime with the actual count_even_cycles/delta_even_cycles
// functions extracted from emitter/src/native/backtracking/sorting_search.cu.
extern "C" __global__ void audit_cycle_deltas(
    const unsigned long long* words, int word_count, int n,
    const int* triples, int triple_count, int* counters)
{
    int id = blockIdx.x * blockDim.x + threadIdx.x;
    if (id >= word_count * triple_count * 2) return;
    int oriented = id % (triple_count * 2);
    int triple = triples[oriented / 2];
    int a = triple & 255, b = (triple >> 8) & 255, c = (triple >> 16) & 255;
    if (oriented & 1) { int swap = b; b = c; c = swap; }
    unsigned long long word = words[id / (triple_count * 2)];
    uint8_t omega[9], child[9];
    for (int i = 0; i < n; ++i) child[i] = omega[i] = (word >> (4*i)) & 15;
    child[a] = omega[c]; child[b] = omega[a]; child[c] = omega[b];
    int actual = delta_even_cycles(omega,a,b,c);
    int expected = count_even_cycles(child,n) - count_even_cycles(omega,n);
    if (actual != expected || (actual != -2 && actual != 0 && actual != 2))
        atomicAdd(counters,1);
    if (actual == -2) atomicAdd(counters+1,1);
    if (actual == 0) atomicAdd(counters+2,1);
    if (actual == 2) atomicAdd(counters+3,1);
}
