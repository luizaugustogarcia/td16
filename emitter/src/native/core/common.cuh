#pragma once
#include <cstdint>

struct Move {
    short firstIndex;
    short secondIndex;
    short thirdIndex;
};

#ifndef MAX_DEPTH
#define MAX_DEPTH 9
#endif

#ifndef MAX_N
#define MAX_N 17
#endif

constexpr int packed_move_index_bits_for_max_n(const int maxN) {
    int bits = 0;
    int maxIndex = maxN - 1;
    while (maxIndex > 0) {
        ++bits;
        maxIndex >>= 1;
    }
    return bits > 0 ? bits : 1;
}

constexpr int PACKED_MOVE_INDEX_BITS = packed_move_index_bits_for_max_n(MAX_N);
using PackedMove = uint16_t;
constexpr uint32_t PACKED_MOVE_INDEX_MASK = (1u << PACKED_MOVE_INDEX_BITS) - 1u;

static_assert(MAX_N <= 32, "PackedMove supports MAX_N up to 32");
static_assert((PACKED_MOVE_INDEX_BITS * 3) <= 16, "PackedMove does not fit in 16 bits");

__host__ __device__ __forceinline__ int combinations_of_three(int elementCount) {
    if (elementCount < 3) {
        return 0;
    }
    return (elementCount * (elementCount - 1) * (elementCount - 2)) / 6;
}

__host__ __device__ __forceinline__
PackedMove pack_move(short i, short j, short k) {
    return static_cast<PackedMove>((static_cast<uint32_t>(i) & PACKED_MOVE_INDEX_MASK)
                                   | ((static_cast<uint32_t>(j) & PACKED_MOVE_INDEX_MASK) << PACKED_MOVE_INDEX_BITS)
                                   | ((static_cast<uint32_t>(k) & PACKED_MOVE_INDEX_MASK) << (PACKED_MOVE_INDEX_BITS * 2)));
}

__host__ __device__ __forceinline__
void unpack_move(PackedMove m, short &i, short &j, short &k) {
    i = static_cast<short>(m & PACKED_MOVE_INDEX_MASK);
    j = static_cast<short>((m >> PACKED_MOVE_INDEX_BITS) & PACKED_MOVE_INDEX_MASK);
    k = static_cast<short>((m >> (PACKED_MOVE_INDEX_BITS * 2)) & PACKED_MOVE_INDEX_MASK);
}

template<class T>
__host__ __device__ __forceinline__ T dmin(T a, T b) { return a < b ? a : b; }
