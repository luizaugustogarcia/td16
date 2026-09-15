#include "backtracking/sorting_search.cuh"

#include <algorithm>
#include <cstddef>
#include <cmath>
#include <mutex>
#include <stdexcept>
#include <utility>
#include <vector>

#include "backtracking/slot_manager.cuh"
#include "core/cuda_error.cuh"
#include "core/cuda_memory.cuh"

namespace {
    constexpr int WARP_SIZE = 32;

    struct BacktrackingNode {
        uint8_t depth;
        uint8_t evenCycles;
        PackedMove moves[MAX_DEPTH];
        uint8_t omega[MAX_N];
        uint8_t betaStart;  // first element of beta cycle (reconstructed via rho invariant)
    };

    struct BacktrackingQueue {
        int head;
        int tail;
        int size;
        int lock;
        int capacity;
        BacktrackingNode *nodes;
    };

    struct BacktrackingResult {
        int found;
        int solutionDepth;
        int overflowed;
        PackedMove solutionMoves[MAX_DEPTH];
        alignas(8) unsigned long long outstandingWork;
    };

    struct DeviceSearchContext {
        const short *tripleFirst;
        const short *tripleSecond;
        const short *tripleThird;
        int tripleCount;
        const short *omega0;
        const short *beta0;
        int n;
        int initialEvenCycles;
        int maxDepth;
        int requiredGainByDepth[MAX_DEPTH + 1];
        int requiredGainAtMaxDepth;
        float minRate;

        int queueCount;
        BacktrackingQueue *queues;
        BacktrackingQueue *overflow;

        int *foundFlag;
        int *solutionDepth;
        PackedMove *solutionMoves;
        int *overflowFlag;
        unsigned long long *outstandingWork;

        int reverseTripleOrder;
        int fullSorting;
        uint8_t rhoInv[MAX_N];  // precomputed rho^{-1} for beta reconstruction
    };

    struct SlotBuffers {
        int device = -1;
        int queueCount = 0;
        int localCapacity = 0;
        int overflowCapacity = 0;
        int cachedTriplesN = -1;
        DeviceAsyncUniquePtr<BacktrackingQueue> queues{};
        DeviceAsyncUniquePtr<BacktrackingQueue> overflow{};
        DeviceAsyncUniquePtr<BacktrackingNode> nodes{};
        DeviceAsyncUniquePtr<BacktrackingNode> overflowNodes{};
        DeviceAsyncUniquePtr<short> tripleFirst{};
        DeviceAsyncUniquePtr<short> tripleSecond{};
        DeviceAsyncUniquePtr<short> tripleThird{};
        DeviceAsyncUniquePtr<short> inputVectors{};
        DeviceAsyncUniquePtr<BacktrackingResult> result{};
        HostPinnedUniquePtr<BacktrackingResult> hostResult{};
    };

    struct SlotBufferView {
        BacktrackingQueue *queues = nullptr;
        BacktrackingQueue *overflow = nullptr;
        BacktrackingNode *nodes = nullptr;
        BacktrackingNode *overflowNodes = nullptr;
        short *tripleFirst = nullptr;
        short *tripleSecond = nullptr;
        short *tripleThird = nullptr;
        short *inputVectors = nullptr;
        BacktrackingResult *result = nullptr;
        BacktrackingResult *hostResult = nullptr;
        bool triplesNeedUpload = false;
    };

    std::mutex gSlotsMutex;
    std::vector<SlotBuffers> gSlots;

    void releaseSlotBuffers(SlotBuffers &buffers) {
        buffers.queues.reset();
        buffers.overflow.reset();
        buffers.nodes.reset();
        buffers.overflowNodes.reset();
        buffers.tripleFirst.reset();
        buffers.tripleSecond.reset();
        buffers.tripleThird.reset();
        buffers.inputVectors.reset();
        buffers.result.reset();
        buffers.hostResult.reset();
        buffers.queueCount = 0;
        buffers.localCapacity = 0;
        buffers.overflowCapacity = 0;
        buffers.cachedTriplesN = -1;
    }

    SlotBufferView viewSlotBuffers(const SlotBuffers &buffers, const int n) noexcept {
        return SlotBufferView{
                buffers.queues.get(),
                buffers.overflow.get(),
                buffers.nodes.get(),
                buffers.overflowNodes.get(),
                buffers.tripleFirst.get(),
                buffers.tripleSecond.get(),
                buffers.tripleThird.get(),
                buffers.inputVectors.get(),
                buffers.result.get(),
                buffers.hostResult.get(),
                buffers.cachedTriplesN != n
        };
    }

    SlotBufferView getSlotBufferView(const int slot, const int n) {
        if (slot < 0 || static_cast<size_t>(slot) >= gSlots.size()) {
            throw std::invalid_argument("invalid slot");
        }
        return viewSlotBuffers(gSlots[slot], n);
    }

    void markSlotTriplesUploaded(const int slot, const int device, const int n) {
        if (slot < 0 || static_cast<size_t>(slot) >= gSlots.size()) return;
        auto &buffers = gSlots[slot];
        if (buffers.device == device && buffers.tripleFirst != nullptr) {
            buffers.cachedTriplesN = n;
        }
    }

    // ─── Device helper functions ─────────────────────────────────────────────────

    __device__ __forceinline__ void acquire_lock(int *lock) {
        while (atomicCAS(lock, 0, 1) != 0);
    }

    __device__ __forceinline__ void release_lock(int *lock) {
        atomicExch(lock, 0);
    }

    __device__ __forceinline__ bool owner_pop(BacktrackingQueue &q, BacktrackingNode &node) {
        acquire_lock(&q.lock);
        if (q.size <= 0) { release_lock(&q.lock); return false; }
        q.tail = (q.tail + q.capacity - 1) % q.capacity;
        node = q.nodes[q.tail];
        q.size--;
        __threadfence();
        release_lock(&q.lock);
        return true;
    }

    __device__ __forceinline__ bool stealer_pop(BacktrackingQueue &q, BacktrackingNode &node) {
        if (atomicAdd(&q.size, 0) <= 0) return false;
        acquire_lock(&q.lock);
        if (q.size <= 0) { release_lock(&q.lock); return false; }
        node = q.nodes[q.head];
        q.head = (q.head + 1) % q.capacity;
        q.size--;
        __threadfence();
        release_lock(&q.lock);
        return true;
    }

    __device__ __forceinline__ bool donate_push(BacktrackingQueue &q, const BacktrackingNode &node) {
        if (atomicAdd(&q.size, 0) >= q.capacity) return false;
        acquire_lock(&q.lock);
        if (q.size >= q.capacity) { release_lock(&q.lock); return false; }
        q.head = (q.head + q.capacity - 1) % q.capacity;
        q.nodes[q.head] = node;
        q.size++;
        __threadfence();
        release_lock(&q.lock);
        return true;
    }

    __device__ __forceinline__ bool warp_should_stop(const DeviceSearchContext &ctx) {
        int shouldStop = 0;
        if (threadIdx.x == 0) {
            shouldStop = (atomicAdd(ctx.foundFlag, 0) != 0
                        || atomicAdd(ctx.overflowFlag, 0) != 0) ? 1 : 0;
        }
        return __shfl_sync(0xFFFFFFFF, shouldStop, 0) != 0;
    }

    // ─── Even-cycle counting ─────────────────────────────────────────────────────

    __device__ __forceinline__ int count_even_cycles(const uint8_t *omega, const int n) {
        uint64_t visited = 0;
        int count = 0;
        for (int s = 0; s < n; ++s) {
            if (visited & (1ULL << s)) continue;
            int size = 0;
            int cur = s;
            do {
                visited |= (1ULL << cur);
                size++;
                cur = omega[cur];
            } while (cur != s);
            if (size & 1) count++;
        }
        return count;
    }

    // Incremental even-cycle delta when applying 3-cycle (a b c) to omega.
    // The move sets: childOmega[a]=omega[c], childOmega[b]=omega[a], childOmega[c]=omega[b].
    //
    // Returns the delta in odd-cycle count (childEvenCycles - parentEvenCycles),
    // or INT_MIN if a, b, c span 3 different cycles (move should be skipped).
    //
    // Cases:
    //   All in same cycle, order a→...→b→...→c→...→a: splits into 3 cycles of
    //     sizes (d_ab, d_bc, d_ca). Delta depends on parity of each part.
    //   All in same cycle, order a→...→c→...→b→...→a: cycle stays intact. Delta = 0.
    //   Two in one cycle, one in another: 2 cycles remain 2 cycles with new sizes.
    //   Three different cycles: skipped (returns INT_MIN).
    __device__ __forceinline__ int delta_even_cycles(const uint8_t *omega,
                                                     const uint8_t a,
                                                     const uint8_t b,
                                                     const uint8_t c) {
        // Trace from a following omega until we hit one of {a, b, c}.
        // This gives us the segment length and tells us which cycle(s) they share.
        int d_a = 0; // steps from a (exclusive) to first hit of {a,b,c}
        uint8_t target_a; // which of {a,b,c} we hit
        {
            uint8_t cur = omega[a];
            while (cur != a && cur != b && cur != c) { cur = omega[cur]; d_a++; }
            d_a++; // count the final step to the target
            target_a = cur;
        }

        int d_b = 0;
        uint8_t target_b;
        {
            uint8_t cur = omega[b];
            while (cur != a && cur != b && cur != c) { cur = omega[cur]; d_b++; }
            d_b++;
            target_b = cur;
        }

        int d_c = 0;
        uint8_t target_c;
        {
            uint8_t cur = omega[c];
            while (cur != a && cur != b && cur != c) { cur = omega[cur]; d_c++; }
            d_c++;
            target_c = cur;
        }

        // Determine the case based on targets.
        // d_a = distance from a to target_a (number of omega steps, a excluded, target included)
        // The "full distance" including a itself as a node: segment has d_a elements after a.

        if (target_a == a && target_b == b && target_c == c) {
            // All three loop back to themselves → 3 different cycles. Skip move.
            return INT_MIN;
        }

        if (target_a == b && target_b == c && target_c == a) {
            // All in same cycle, order a→...→b→...→c→...→a
            // Splits into 3 cycles of sizes d_a, d_b, d_c (each segment becomes a cycle)
            // The original cycle size = d_a + d_b + d_c
            const int L = d_a + d_b + d_c;
            const int oldOdd = (L & 1) ? 1 : 0;
            const int newOdd = (d_a & 1) + (d_b & 1) + (d_c & 1);
            return newOdd - oldOdd;
        }

        if (target_a == c && target_b == a && target_c == b) {
            // All in same cycle, order a→...→c→...→b→...→a (reverse order)
            // Cycle stays intact, same size. No change.
            return 0;
        }

        // Two in one cycle, one in another.
        // Determine which two share a cycle and compute the delta.
        if (target_a == b && target_b == a) {
            // a and b in same cycle (size L1 = d_a + d_b), c alone (size L2 = d_c)
            // New sizes: d_a and (d_b + d_c)
            const int L1 = d_a + d_b;
            const int L2 = d_c;
            const int oldOdd = (L1 & 1) + (L2 & 1);
            const int newOdd = (d_a & 1) + ((d_b + d_c) & 1);
            return newOdd - oldOdd;
        }

        if (target_b == c && target_c == b) {
            // b and c in same cycle (size L1 = d_b + d_c), a alone (size L2 = d_a)
            // New sizes: d_b and (d_c + d_a)
            const int L1 = d_b + d_c;
            const int L2 = d_a;
            const int oldOdd = (L1 & 1) + (L2 & 1);
            const int newOdd = (d_b & 1) + ((d_c + d_a) & 1);
            return newOdd - oldOdd;
        }

        if (target_a == c && target_c == a) {
            // a and c in same cycle (size L1 = d_a + d_c), b alone (size L2 = d_b)
            // New sizes: d_c and (d_a + d_b)
            const int L1 = d_a + d_c;
            const int L2 = d_b;
            const int oldOdd = (L1 & 1) + (L2 & 1);
            const int newOdd = (d_c & 1) + ((d_a + d_b) & 1);
            return newOdd - oldOdd;
        }

        // Should not reach here for valid permutations, but guard:
        return INT_MIN;
    }

    // ─── Pruning logic ───────────────────────────────────────────────────────────

    __device__ __forceinline__ bool satisfies_rate(const DeviceSearchContext &ctx,
                                                   const int evenCycles,
                                                   const int depth) {
        if (depth <= 0) return false;
        if (ctx.fullSorting) {
            if (evenCycles != ctx.n) return false;
        }
        const int gained = evenCycles - ctx.initialEvenCycles;
        const float achievedRatio = static_cast<float>(gained) / static_cast<float>(depth);
        return gained > 0 && achievedRatio >= ctx.minRate;
    }

    __device__ __forceinline__ bool can_still_reach(const DeviceSearchContext &ctx,
                                                    const int evenCycles,
                                                    const int depth) {
        const int maxPossibleEvenCycles = ctx.n - evenCycles;

        if (ctx.fullSorting) {
            const int lowerBound = (maxPossibleEvenCycles + 1) / 2;
            if (depth + lowerBound > ctx.maxDepth) return false;
        } else {
            const int gained = evenCycles - ctx.initialEvenCycles;
            const int movesLeft = ctx.maxDepth - depth;

            const int optimisticGain = gained + 2 * movesLeft;
            const int requiredGainAtMaxDepth = static_cast<int>(
                    floorf(ctx.minRate * static_cast<float>(ctx.maxDepth)));
            if (optimisticGain < requiredGainAtMaxDepth) return false;

            const int lowerBound = (maxPossibleEvenCycles + 1) / 2;
            const int bestCaseDepth = depth + lowerBound;
            if (bestCaseDepth > 0) {
                const float bestCaseRatio = (float)(gained + maxPossibleEvenCycles) / (float)bestCaseDepth;
                if (bestCaseRatio < ctx.minRate) return false;
            }
        }
        return true;
    }

    __device__ __forceinline__ bool contains_fixed_symbol(const uint8_t *omega,
                                                          const uint8_t a,
                                                          const uint8_t b,
                                                          const uint8_t c) {
        return omega[a] == a || omega[b] == b || omega[c] == c;
    }

    // ─── Initialization kernel ───────────────────────────────────────────────────

    __global__ void initialize_queues_kernel(const short *tripleFirst,
                                             const short *tripleSecond,
                                             const short *tripleThird,
                                             const int tripleCount,
                                             BacktrackingQueue *queues,
                                             const int queueCount,
                                             BacktrackingNode *nodes,
                                             const int localCapacity,
                                             BacktrackingQueue *overflow,
                                             BacktrackingNode *overflowNodes,
                                             const int overflowCapacity,
                                             int *overflowFlag,
                                             unsigned long long *outstandingWork,
                                             const short *omega0,
                                             const short *beta0,
                                             const int n,
                                             const int initialEvenCycles,
                                             const int reverseTripleOrder) {
        const int queueIndex = static_cast<int>(blockIdx.x * blockDim.x + threadIdx.x);
        if (queueIndex < queueCount) {
            BacktrackingQueue queue{};
            queue.head = 0;
            queue.lock = 0;
            queue.capacity = localCapacity;
            queue.nodes = nodes + static_cast<size_t>(queueIndex) * localCapacity;

            int rootCount = 0;
            if (queueIndex < tripleCount) {
                const int queueRoots = 1 + (tripleCount - 1 - queueIndex) / queueCount;
                if (queueRoots > localCapacity) {
                    atomicExch(overflowFlag, 1);
                }
                const int rootLimit = min(localCapacity, queueRoots);
                for (int rootIndex = 0; rootIndex < rootLimit; ++rootIndex) {
                    const int fwdTripleIndex = queueIndex + rootIndex * queueCount;
                    const int tripleIndex = reverseTripleOrder
                        ? (tripleCount - 1 - fwdTripleIndex)
                        : fwdTripleIndex;
                    const short moveFirst = tripleFirst[tripleIndex];
                    const short moveSecond = tripleSecond[tripleIndex];
                    const short moveThird = tripleThird[tripleIndex];
                    BacktrackingNode node{};
                    node.depth = 1;
                    node.moves[0] = pack_move(moveFirst, moveSecond, moveThird);

                    const short a = beta0[moveFirst];
                    const short b = beta0[moveSecond];
                    const short c = beta0[moveThird];
                    for (int p = 0; p < n; ++p) {
                        node.omega[p] = static_cast<uint8_t>(omega0[p]);
                    }

                    // Root moves must obey the same restrictions as descendants.
                    // In particular, do not seed the search with a move that
                    // merges three distinct cycles.
                    if (contains_fixed_symbol(node.omega, a, b, c)) continue;
                    const int delta = delta_even_cycles(node.omega, a, b, c);
                    if (delta != 0 && delta != 2) continue;

                    node.omega[a] = static_cast<uint8_t>(omega0[c]);
                    node.omega[b] = static_cast<uint8_t>(omega0[a]);
                    node.omega[c] = static_cast<uint8_t>(omega0[b]);

                    node.evenCycles = static_cast<uint8_t>(count_even_cycles(node.omega, n));
                    // betaStart: first element of beta cycle after block transposition
                    // transposed_src_index(0, i, j, k) = j when i==0, else 0
                    node.betaStart = (moveFirst == 0)
                        ? static_cast<uint8_t>(beta0[moveSecond])
                        : static_cast<uint8_t>(beta0[0]);

                    queue.nodes[rootCount++] = node;
                }
            }

            queue.tail = (rootCount == localCapacity) ? 0 : rootCount;
            queue.size = rootCount;
            queues[queueIndex] = queue;
            if (rootCount > 0) {
                atomicAdd(outstandingWork, static_cast<unsigned long long>(rootCount));
            }
        }

        if (blockIdx.x == 0 && threadIdx.x == 0) {
            overflow->head = 0;
            overflow->tail = 0;
            overflow->size = 0;
            overflow->lock = 0;
            overflow->capacity = overflowCapacity;
            overflow->nodes = overflowNodes;
        }
    }

    // ─── Persistent search kernel ────────────────────────────────────────────────

    __global__ void __launch_bounds__(32)
    sorting_search_persistent_kernel(const DeviceSearchContext ctx) {
        const int warpId = static_cast<int>(blockIdx.x);
        const int lane = static_cast<int>(threadIdx.x);

        __shared__ uint8_t sh_parent_omega[MAX_N];
        __shared__ uint8_t sh_parent_beta[MAX_N];
        __shared__ uint8_t sh_tmp[MAX_N];
        __shared__ BacktrackingNode sh_children[WARP_SIZE];
#if __CUDA_ARCH__ >= 700
        unsigned idleBackoff = 64;
#endif
        int stealOffset = 1;

        while (true) {
            if (warp_should_stop(ctx)) return;

            BacktrackingNode node;
            bool hasNode = false;

            if (lane == 0) {
                hasNode = owner_pop(ctx.queues[warpId], node);
                if (!hasNode) {
                    const int victimCount = ctx.queueCount - 1;
                    const int probeCount = min(victimCount, 8);
                    for (int probe = 0; probe < probeCount; ++probe) {
                        const int offset = 1 + ((stealOffset - 1 + probe) % victimCount);
                        const int victim = (warpId + offset) % ctx.queueCount;
                        if (stealer_pop(ctx.queues[victim], node)) { hasNode = true; break; }
                    }
                    if (victimCount > 0) {
                        stealOffset = 1 + ((stealOffset - 1 + probeCount) % victimCount);
                    }
                }
                if (!hasNode) {
                    hasNode = stealer_pop(*ctx.overflow, node);
                }
            }

            int foundNode = __shfl_sync(0xFFFFFFFF, hasNode ? 1 : 0, 0);
            if (!foundNode) {
                int noWork = 0;
                if (lane == 0) {
                    noWork = atomicAdd(ctx.outstandingWork, 0ULL) == 0 ? 1 : 0;
                }
                noWork = __shfl_sync(0xFFFFFFFF, noWork, 0);
                if (noWork != 0) return;
#if __CUDA_ARCH__ >= 700
                __nanosleep(idleBackoff);
                idleBackoff = min(idleBackoff * 2U, 1024U);
#endif
                continue;
            }
#if __CUDA_ARCH__ >= 700
            idleBackoff = 64;
#endif

            node.depth = (uint8_t)__shfl_sync(0xFFFFFFFF, node.depth, 0);
            node.evenCycles = (uint8_t)__shfl_sync(0xFFFFFFFF, static_cast<int>(node.evenCycles), 0);
            node.betaStart = (uint8_t)__shfl_sync(0xFFFFFFFF, static_cast<int>(node.betaStart), 0);
            for (int d = 0; d < ctx.maxDepth; ++d) {
                node.moves[d] = static_cast<PackedMove>(__shfl_sync(0xFFFFFFFF, node.moves[d], 0));
            }

            // Store parent omega in shared memory, reconstruct beta via rho invariant
            if (lane == 0) {
                for (int i = 0; i < ctx.n; ++i)
                    sh_parent_omega[i] = node.omega[i];

                // Reconstruct beta_cycle from rho_inv and omega:
                // beta_func_inv[x] = rho_inv[omega[x]], invert to get beta_func
                for (int x = 0; x < ctx.n; ++x) {
                    const uint8_t y = ctx.rhoInv[sh_parent_omega[x]];
                    sh_parent_beta[y] = static_cast<uint8_t>(x);  // beta_func[y] = x
                }
                // Traverse cycle from betaStart to build beta_cycle sequence
                uint8_t cur = node.betaStart;
                for (int pos = 0; pos < ctx.n; ++pos) {
                    sh_tmp[pos] = cur;
                    cur = sh_parent_beta[cur];
                }
                for (int i = 0; i < ctx.n; ++i)
                    sh_parent_beta[i] = sh_tmp[i];
            }
            __syncwarp(0xFFFFFFFF);

            if (node.depth == 0 || node.depth > ctx.maxDepth || node.depth > MAX_DEPTH) {
                if (lane == 0) atomicAdd(ctx.outstandingWork, ~0ULL);
                continue;
            }

            const int parentEvenCycles = node.evenCycles;

            if (satisfies_rate(ctx, parentEvenCycles, node.depth)) {
                if (atomicCAS(ctx.foundFlag, 0, 1) == 0) {
                    *ctx.solutionDepth = node.depth;
                    for (int d = 0; d < node.depth; ++d) ctx.solutionMoves[d] = node.moves[d];
                }
                return;
            }

            if (node.depth >= ctx.maxDepth || !can_still_reach(ctx, parentEvenCycles, node.depth)) {
                if (lane == 0) atomicAdd(ctx.outstandingWork, ~0ULL);
                continue;
            }

            const int childDepth = node.depth + 1;
            for (int tBase = 0; tBase < ctx.tripleCount; tBase += WARP_SIZE) {
                if (warp_should_stop(ctx)) return;

                const int t = ctx.reverseTripleOrder
                    ? (ctx.tripleCount - 1 - tBase - lane)
                    : (tBase + lane);
                bool foundChild = false;
                bool wantsPush = false;
                short solutionFirst{}, solutionSecond{}, solutionThird{};
                int childEvenCycles = 0;
                uint8_t childBetaStart = 0;
                uint8_t child_a = 0, child_b = 0, child_c = 0;
                short moveFirst = 0, moveSecond = 0, moveThird = 0;

                if (t >= 0 && t < ctx.tripleCount) {
                    moveFirst = ctx.tripleFirst[t];
                    moveSecond = ctx.tripleSecond[t];
                    moveThird = ctx.tripleThird[t];
                    const uint8_t a = sh_parent_beta[moveFirst];
                    const uint8_t b = sh_parent_beta[moveSecond];
                    const uint8_t c = sh_parent_beta[moveThird];

                    if (!contains_fixed_symbol(sh_parent_omega, a, b, c)) {
                        const int delta = delta_even_cycles(sh_parent_omega, a, b, c);

                        if (delta == 0 || delta == 2) {
                            childEvenCycles = parentEvenCycles + delta;

                            if (satisfies_rate(ctx, childEvenCycles, childDepth)) {
                                foundChild = true;
                                solutionFirst = moveFirst;
                                solutionSecond = moveSecond;
                                solutionThird = moveThird;
                            } else if (childDepth < ctx.maxDepth && can_still_reach(ctx, childEvenCycles, childDepth)) {
                                child_a = a;
                                child_b = b;
                                child_c = c;
                                // betaStart only changes when moveFirst == 0
                                childBetaStart = (moveFirst == 0)
                                    ? sh_parent_beta[moveSecond]
                                    : node.betaStart;
                                wantsPush = true;
                            }
                        }
                    }
                }

                const unsigned foundBallot = __ballot_sync(0xFFFFFFFF, foundChild ? 1 : 0);
                if (foundBallot != 0) {
                    const int solutionLane = __ffs(foundBallot) - 1;
                    if (lane == solutionLane && atomicCAS(ctx.foundFlag, 0, 1) == 0) {
                        *ctx.solutionDepth = childDepth;
                        for (int d = 0; d < node.depth; ++d) ctx.solutionMoves[d] = node.moves[d];
                        ctx.solutionMoves[node.depth] = pack_move(
                                solutionFirst, solutionSecond, solutionThird);
                    }
                    return;
                }

                // Warp-cooperative push: stage children in shared memory, batch-push
                unsigned pushBallot = __ballot_sync(0xFFFFFFFF, wantsPush ? 1 : 0);
                const int pushCount = __popc(pushBallot);
                if (pushCount > 0) {
                    if (wantsPush) {
                        const unsigned mask_below = (1u << lane) - 1u;
                        const int mySlot = __popc(pushBallot & mask_below);
                        sh_children[mySlot].depth = static_cast<uint8_t>(childDepth);
                        sh_children[mySlot].evenCycles = static_cast<uint8_t>(childEvenCycles);
                        for (int d = 0; d < node.depth; ++d)
                            sh_children[mySlot].moves[d] = node.moves[d];
                        sh_children[mySlot].moves[node.depth] = pack_move(moveFirst, moveSecond, moveThird);
                        for (int i = 0; i < ctx.n; ++i)
                            sh_children[mySlot].omega[i] = sh_parent_omega[i];
                        sh_children[mySlot].omega[child_a] = sh_parent_omega[child_c];
                        sh_children[mySlot].omega[child_b] = sh_parent_omega[child_a];
                        sh_children[mySlot].omega[child_c] = sh_parent_omega[child_b];
                        sh_children[mySlot].betaStart = childBetaStart;
                    }
                    __syncwarp(0xFFFFFFFF);

                    if (lane == 0) {
                        // Reserve every child before publishing any queue entry.
                        // The active parent remains counted until the entire
                        // expansion finishes, so the count cannot transiently
                        // reach zero while work is in flight.
                        atomicAdd(ctx.outstandingWork,
                                  static_cast<unsigned long long>(pushCount));

                        // Batch push to owner queue under single lock
                        BacktrackingQueue &q = ctx.queues[warpId];
                        acquire_lock(&q.lock);
                        int pushed = 0;
                        for (int c = pushCount - 1; c >= 0; --c) {
                            if (q.size < q.capacity) {
                                q.nodes[q.tail] = sh_children[c];
                                q.tail = (q.tail + 1) % q.capacity;
                                q.size++;
                                pushed++;
                            } else {
                                break;
                            }
                        }
                        if (pushed > 0) __threadfence();
                        release_lock(&q.lock);

                        // Handle overflow for remaining children
                        for (int c = pushCount - 1 - pushed; c >= 0; --c) {
                            bool donated = false;
                            for (int i = 1; i < ctx.queueCount; ++i) {
                                int receiver = (warpId + i) % ctx.queueCount;
                                if (donate_push(ctx.queues[receiver], sh_children[c])) {
                                    donated = true;
                                    break;
                                }
                            }
                            if (!donated) {
                                if (!donate_push(*ctx.overflow, sh_children[c])) {
                                    atomicExch(ctx.overflowFlag, 1);
                                }
                            }
                        }
                    }
                    __syncwarp(0xFFFFFFFF);
                }
            }
            if (lane == 0) atomicAdd(ctx.outstandingWork, ~0ULL);
        }
    }

    int getQueueCount(const int device) {
        static std::mutex queueCountMutex;
        static std::vector<int> queueCounts;

        std::lock_guard<std::mutex> lock(queueCountMutex);
        if (static_cast<size_t>(device) >= queueCounts.size()) {
            queueCounts.resize(static_cast<size_t>(device) + 1, 0);
        }

        int &cached = queueCounts[static_cast<size_t>(device)];
        if (cached > 0) return cached;

        int multiprocessors = 0;
        CHECK_CUDA(cudaDeviceGetAttribute(&multiprocessors, cudaDevAttrMultiProcessorCount, device));
        int activeBlocksPerSM = 1;
        CHECK_CUDA(cudaOccupancyMaxActiveBlocksPerMultiprocessor(
                &activeBlocksPerSM, sorting_search_persistent_kernel, WARP_SIZE, 0));
        cudaDeviceProp props{};
        CHECK_CUDA(cudaGetDeviceProperties(&props, device));

        const int occupancyTarget = std::max(1, multiprocessors * std::max(1, activeBlocksPerSM));
        const int gridXLimit = std::max(1, props.maxGridSize[0]);
        cached = std::min(occupancyTarget, gridXLimit);
        return cached;
    }

    int getQueueCountForSlot(const int device,
                             const int slotsOnDevice,
                             const int slotOrdinalOnDevice) {
        const int fullQueueCount = getQueueCount(device);
        const int divisor = std::max(1, slotsOnDevice);
        if (divisor > fullQueueCount) {
            throw std::invalid_argument(
                    "slots per device exceeds the persistent kernel's resident-block capacity");
        }
        const int base = std::max(1, fullQueueCount / divisor);
        const int remainder = fullQueueCount % divisor;
        return base + (slotOrdinalOnDevice >= 0 && slotOrdinalOnDevice < remainder ? 1 : 0);
    }
}

void allocateGpuSortingSearchSlot(const int slot, const int device,
                                   const size_t queueBytesBudget,
                                   const int slotsOnDevice,
                                   const int slotOrdinalOnDevice) {
    std::lock_guard<std::mutex> lock(gSlotsMutex);
    if (static_cast<size_t>(slot) >= gSlots.size()) {
        gSlots.resize(static_cast<size_t>(slot) + 1);
    }

    auto &buffers = gSlots[slot];
    buffers.device = device;

    const int queueCount = getQueueCountForSlot(device, slotsOnDevice, slotOrdinalOnDevice);
    const size_t overflowBudget = queueBytesBudget / 4;
    const size_t localBudget = queueBytesBudget - overflowBudget;
    const int localCapacity = std::max(2, (int)(localBudget / (queueCount * sizeof(BacktrackingNode))));
    const int overflowCapacity = std::max(1024, (int)(overflowBudget / sizeof(BacktrackingNode)));

    buffers.queues = allocateDeviceBuffer<BacktrackingQueue>(
            static_cast<size_t>(queueCount), "sort.queues");
    buffers.overflow = allocateDeviceBuffer<BacktrackingQueue>(1, "sort.overflowQueue");
    buffers.nodes = allocateDeviceBuffer<BacktrackingNode>(
            static_cast<size_t>(queueCount) * static_cast<size_t>(localCapacity), "sort.nodes");
    buffers.overflowNodes = allocateDeviceBuffer<BacktrackingNode>(
            static_cast<size_t>(overflowCapacity), "sort.overflowNodes");
    const size_t maxTriples = static_cast<size_t>(combinations_of_three(MAX_N));
    buffers.tripleFirst = allocateDeviceBuffer<short>(maxTriples, "sort.tripleFirst");
    buffers.tripleSecond = allocateDeviceBuffer<short>(maxTriples, "sort.tripleSecond");
    buffers.tripleThird = allocateDeviceBuffer<short>(maxTriples, "sort.tripleThird");
    buffers.inputVectors = allocateDeviceBuffer<short>(
            static_cast<size_t>(MAX_N) * 2, "sort.inputVectors");
    buffers.result = allocateDeviceBuffer<BacktrackingResult>(1, "sort.result");
    buffers.hostResult = makePinnedHostBuffer<BacktrackingResult>(1);

    buffers.queueCount = queueCount;
    buffers.localCapacity = localCapacity;
    buffers.overflowCapacity = overflowCapacity;
    buffers.cachedTriplesN = -1;
}

void destroyGpuSortingSearchSlots() {
    std::lock_guard<std::mutex> lock(gSlotsMutex);
    int originalDevice = -1;
    CHECK_CUDA(cudaGetDevice(&originalDevice));

    for (auto &buffers : gSlots) {
        if (buffers.device < 0) continue;
        if (originalDevice != buffers.device) CHECK_CUDA(cudaSetDevice(buffers.device));
        releaseSlotBuffers(buffers);
        buffers.device = -1;
    }

    gSlots.clear();
    if (originalDevice >= 0) CHECK_CUDA(cudaSetDevice(originalDevice));
}

std::vector<Move> performGpuSortingSearch(std::vector<short> beta,
                                          std::vector<short> omega,
                                          const int initialEvenCycles,
                                          const double minRate,
                                          const int maximumDepth,
                                          const bool fullSorting,
                                          const cudaStream_t stream,
                                          const int slot,
                                          const int maxWarps) {
    if (omega.size() != beta.size()) {
        throw std::invalid_argument("performGpuSortingSearch: inconsistent input sizes");
    }
    const int n = static_cast<int>(omega.size());
    if (n > MAX_N) throw std::invalid_argument("performGpuSortingSearch: n exceeds MAX_N");
    if (maximumDepth <= 0 || maximumDepth > MAX_DEPTH) throw std::invalid_argument("performGpuSortingSearch: invalid maximumDepth");
    const int tripleCount = combinations_of_three(n);
    if (tripleCount == 0) return {};

    int currentDevice = 0;
    CHECK_CUDA(cudaGetDevice(&currentDevice));

    int queueCount;
    int localCapacity;
    int overflowCapacity;
    if (slot < 0 || static_cast<size_t>(slot) >= gSlots.size()) {
        throw std::invalid_argument("invalid slot");
    }
    const auto &buffers = gSlots[slot];
    queueCount = buffers.queueCount;
    localCapacity = buffers.localCapacity;
    overflowCapacity = buffers.overflowCapacity;
    if (maxWarps > 0 && maxWarps < queueCount) queueCount = maxWarps;
    queueCount = std::min(queueCount, tripleCount);

    const auto slotView = getSlotBufferView(slot, n);

    if (slotView.triplesNeedUpload) {
        std::vector<short> firsts, seconds, thirds;
        firsts.reserve(static_cast<size_t>(tripleCount));
        seconds.reserve(static_cast<size_t>(tripleCount));
        thirds.reserve(static_cast<size_t>(tripleCount));
        for (short i = 0; i < n - 2; ++i) {
            for (short j = i + 1; j < n - 1; ++j) {
                for (short k = j + 1; k < n; ++k) {
                    firsts.push_back(i);
                    seconds.push_back(j);
                    thirds.push_back(k);
                }
            }
        }
        CHECK_CUDA(cudaMemcpyAsync(slotView.tripleFirst, firsts.data(),
                                   sizeof(short) * tripleCount, cudaMemcpyHostToDevice, stream));
        CHECK_CUDA(cudaMemcpyAsync(slotView.tripleSecond, seconds.data(),
                                   sizeof(short) * tripleCount, cudaMemcpyHostToDevice, stream));
        CHECK_CUDA(cudaMemcpyAsync(slotView.tripleThird, thirds.data(),
                                   sizeof(short) * tripleCount, cudaMemcpyHostToDevice, stream));
    }

    auto inputVectors = std::vector<short>(static_cast<size_t>(n) * 2);
    std::copy(beta.begin(), beta.end(), inputVectors.begin());
    std::copy(omega.begin(), omega.end(), inputVectors.begin() + n);
    CHECK_CUDA(cudaMemcpyAsync(slotView.inputVectors, inputVectors.data(),
                               sizeof(short) * inputVectors.size(), cudaMemcpyHostToDevice, stream));

    BacktrackingResult initialResult{};
    initialResult.solutionDepth = -1;
    CHECK_CUDA(cudaMemcpyAsync(slotView.result, &initialResult, sizeof(BacktrackingResult),
                               cudaMemcpyHostToDevice, stream));

    const short *dBeta0 = slotView.inputVectors;
    const short *dOmega0 = slotView.inputVectors + n;
    auto *resultBytes = reinterpret_cast<char *>(slotView.result);
    auto *dFound = reinterpret_cast<int *>(resultBytes + offsetof(BacktrackingResult, found));
    auto *dSolutionDepth = reinterpret_cast<int *>(resultBytes + offsetof(BacktrackingResult, solutionDepth));
    auto *dOverflow = reinterpret_cast<int *>(resultBytes + offsetof(BacktrackingResult, overflowed));
    auto *dSolutionMoves = reinterpret_cast<PackedMove *>(resultBytes + offsetof(BacktrackingResult, solutionMoves));
    auto *dOutstandingWork = reinterpret_cast<unsigned long long *>(
            resultBytes + offsetof(BacktrackingResult, outstandingWork));

    DeviceSearchContext host{};
    host.tripleCount = tripleCount;
    host.n = n;
    host.maxDepth = maximumDepth;
    host.queueCount = queueCount;
    host.initialEvenCycles = initialEvenCycles;
    host.minRate = static_cast<float>(minRate);
    host.fullSorting = fullSorting ? 1 : 0;

    // Compute rho_inv for beta reconstruction via the invariant:
    // rho = omega0 ∘ beta_func, where beta_func is the function form of the cycle beta0.
    // beta_func(beta0[i]) = beta0[(i+1) % n], so rho(x) = omega0(beta_func(x)).
    {
        uint8_t betaFunc[MAX_N];
        for (int i = 0; i < n; ++i)
            betaFunc[static_cast<uint8_t>(beta[i])] = static_cast<uint8_t>(beta[(i + 1) % n]);

        uint8_t rho[MAX_N];
        for (int i = 0; i < n; ++i)
            rho[i] = static_cast<uint8_t>(omega[betaFunc[i]]);

        for (int i = 0; i < n; ++i)
            host.rhoInv[rho[i]] = static_cast<uint8_t>(i);
    }

    constexpr int INIT_THREADS = 128;
    const int initBlocks = (queueCount + INIT_THREADS - 1) / INIT_THREADS;
    initialize_queues_kernel<<<initBlocks, INIT_THREADS, 0, stream>>>(
            slotView.tripleFirst, slotView.tripleSecond, slotView.tripleThird,
            tripleCount,
            slotView.queues, queueCount,
            slotView.nodes, localCapacity,
            slotView.overflow, slotView.overflowNodes, overflowCapacity,
            dOverflow, dOutstandingWork, dOmega0, dBeta0, n, initialEvenCycles,
            (queueCount == 1) ? 1 : 0);
    CHECK_CUDA(cudaGetLastError());

    host.tripleFirst = slotView.tripleFirst;
    host.tripleSecond = slotView.tripleSecond;
    host.tripleThird = slotView.tripleThird;
    host.omega0 = dOmega0;
    host.beta0 = dBeta0;
    host.queues = slotView.queues;
    host.overflow = slotView.overflow;
    host.foundFlag = dFound;
    host.solutionDepth = dSolutionDepth;
    host.solutionMoves = dSolutionMoves;
    host.overflowFlag = dOverflow;
    host.outstandingWork = dOutstandingWork;
    host.reverseTripleOrder = (queueCount == 1) ? 1 : 0;

    sorting_search_persistent_kernel<<<queueCount, WARP_SIZE, 0, stream>>>(host);
    CHECK_CUDA(cudaGetLastError());

    CHECK_CUDA(cudaMemcpyAsync(slotView.hostResult, slotView.result,
                               sizeof(BacktrackingResult), cudaMemcpyDeviceToHost, stream));
    const cudaEvent_t completionEvent = getGpuSortSlotCompletionEvent(slot);
    if (completionEvent == nullptr) {
        throw std::runtime_error("performGpuSortingSearch: missing slot completion event");
    }
    CHECK_CUDA(cudaEventRecord(completionEvent, stream));
    CHECK_CUDA(cudaEventSynchronize(completionEvent));
    if (slotView.triplesNeedUpload) {
        markSlotTriplesUploaded(slot, currentDevice, n);
    }

    const BacktrackingResult resultSnapshot = *slotView.hostResult;
    const int found = resultSnapshot.found;
    const int solutionDepth = resultSnapshot.solutionDepth;
    const int overflowed = resultSnapshot.overflowed;
    if (!found && overflowed != 0) {
        throw GpuMemoryExhaustedException("GPU sorting search queue overflow");
    }
    if (!found || solutionDepth <= 0) return {};

    std::vector<Move> result;
    for (int d = 0; d < solutionDepth; ++d) {
        short i, j, k;
        unpack_move(resultSnapshot.solutionMoves[d], i, j, k);
        result.push_back(Move{i, j, k});
    }
    return result;
}
