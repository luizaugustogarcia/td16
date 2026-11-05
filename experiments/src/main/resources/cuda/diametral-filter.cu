// Maximum insertion plus the all-deletion filter. Audit only, n <= 10.
typedef unsigned long long Word;
extern "C" __global__ void filter_diametral_insertions(
    const Word* parents, int parent_count, int n,
    const signed char* previous_distance, int required_distance,
    unsigned char* keep)
{
    int id = blockIdx.x * blockDim.x + threadIdx.x;
    if (id >= parent_count * n) return;
    int position = id % n;
    Word parent = parents[id / n];
    Word prefix_mask = (1ULL << (4 * position)) - 1ULL;
    Word word = (parent & prefix_mask) | ((Word)n << (4 * position))
        | ((parent & ~prefix_mask) << 4);
    for (int omitted = 0; omitted < n; ++omitted) {
        if (omitted == position) continue; // deleting n gives the known parent
        int deleted = (word >> (4 * omitted)) & 15;
        int rank = 0, used = 0, radix = n - 1;
        for (int i = 0; i < n; ++i) {
            if (i == omitted) continue;
            int value = (word >> (4 * i)) & 15;
            value -= value > deleted;
            int smaller = value - 1 - __popc(used & ((1U << (value - 1)) - 1));
            rank = rank * radix-- + smaller;
            used |= 1U << (value - 1);
        }
        if (previous_distance[rank] != required_distance) {
            keep[id] = 0;
            return;
        }
    }
    keep[id] = 1;
}
