#ifndef SLOT_MANAGER_CUH
#define SLOT_MANAGER_CUH

#include <cuda_runtime.h>
#include <cstddef>
#include <vector>

void initGpuSortSlots(int totalSlots, const std::vector<int> &devices, size_t queueBytesBudget);

void destroyGpuSortSlots();

cudaStream_t getGpuSortSlotStream(int slot);

cudaEvent_t getGpuSortSlotCompletionEvent(int slot);

int getGpuSortSlotDevice(int slot);

#endif
