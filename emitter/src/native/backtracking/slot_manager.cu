#include <cuda_runtime.h>

#include <cstdint>
#include <limits>
#include <mutex>
#include <string>
#include <stdexcept>
#include <vector>

#include "core/cuda_error.cuh"
#include "backtracking/slot_manager.cuh"
#include "backtracking/sorting_search.cuh"

static std::vector<cudaStream_t> gSlotStreams;
static std::vector<cudaEvent_t> gSlotCompletionEvents;
static std::vector<int> gSlotDevices;
static std::mutex gSlotStateMutex;

void destroyGpuSortSlots() {
    std::lock_guard<std::mutex> lock(gSlotStateMutex);
    int originalDevice = -1;
    cudaGetDevice(&originalDevice);
    for (int slot = 0; slot < static_cast<int>(gSlotStreams.size()); ++slot) {
        if (slot < static_cast<int>(gSlotDevices.size()) && gSlotDevices[slot] >= 0) {
            cudaSetDevice(gSlotDevices[slot]);
        }
        if (gSlotStreams[slot] != nullptr) {
            cudaStreamDestroy(gSlotStreams[slot]);
            gSlotStreams[slot] = nullptr;
        }
        if (slot < static_cast<int>(gSlotCompletionEvents.size())
            && gSlotCompletionEvents[slot] != nullptr) {
            cudaEventDestroy(gSlotCompletionEvents[slot]);
            gSlotCompletionEvents[slot] = nullptr;
        }
    }
    gSlotStreams.clear();
    gSlotCompletionEvents.clear();
    gSlotDevices.clear();
    destroyGpuSortingSearchSlots();
    if (originalDevice != -1) cudaSetDevice(originalDevice);
}

void initGpuSortSlots(const int totalSlots, const std::vector<int> &devices, const size_t queueBytesBudget) {
    if (devices.empty()) {
        throw std::invalid_argument("devices must contain at least one CUDA device ordinal");
    }
    if (totalSlots <= 0) {
        throw std::invalid_argument("totalSlots must be positive");
    }
    if (queueBytesBudget == 0) {
        throw std::invalid_argument("queueBytesBudget must be positive");
    }

    int deviceCount = 0;
    CHECK_CUDA(cudaGetDeviceCount(&deviceCount));
    for (const int device : devices) {
        if (device < 0 || device >= deviceCount) {
            throw std::invalid_argument("devices contains a CUDA device ordinal outside the available range");
        }
    }
    std::vector<bool> selectedDevices(static_cast<size_t>(deviceCount), false);
    for (const int device : devices) {
        if (selectedDevices[static_cast<size_t>(device)]) {
            throw std::invalid_argument("devices must contain distinct CUDA device ordinals");
        }
        selectedDevices[static_cast<size_t>(device)] = true;
    }

    destroyGpuSortSlots();
    std::unique_lock<std::mutex> lock(gSlotStateMutex);
    int originalDevice = -1;
    cudaGetDevice(&originalDevice);

    gSlotStreams.assign(totalSlots, nullptr);
    gSlotCompletionEvents.assign(totalSlots, nullptr);
    gSlotDevices.assign(totalSlots, -1);

    std::vector<int> slotsPerDevice(static_cast<size_t>(deviceCount), 0);
    for (int i = 0; i < totalSlots; ++i) {
        const int device = devices[static_cast<size_t>(i) % devices.size()];
        slotsPerDevice[static_cast<size_t>(device)]++;
    }

    std::vector<int> assignedSlotsPerDevice(static_cast<size_t>(deviceCount), 0);
    try {
        for (const int device : devices) {
            CHECK_CUDA(cudaSetDevice(device));
            size_t freeBytes = 0;
            size_t totalBytes = 0;
            CHECK_CUDA(cudaMemGetInfo(&freeBytes, &totalBytes));
            const size_t deviceSlots = static_cast<size_t>(
                    slotsPerDevice[static_cast<size_t>(device)]);
            if (deviceSlots > 0
                && queueBytesBudget > std::numeric_limits<size_t>::max() / deviceSlots) {
                throw std::invalid_argument("per-device GPU queue budget overflows size_t");
            }
            const size_t requiredQueueBytes = queueBytesBudget * deviceSlots;
            if (requiredQueueBytes > freeBytes) {
                throw GpuMemoryExhaustedException(
                        "CUDA device " + std::to_string(device)
                        + " has " + std::to_string(freeBytes)
                        + " free bytes, but its search queues require at least "
                        + std::to_string(requiredQueueBytes) + " bytes");
            }
        }

        for (int i = 0; i < totalSlots; ++i) {
            const int device = devices[static_cast<size_t>(i) % devices.size()];
            const int slotOrdinalOnDevice = assignedSlotsPerDevice[static_cast<size_t>(device)]++;
            CHECK_CUDA(cudaSetDevice(device));
            gSlotDevices[i] = device;
            CHECK_CUDA(cudaStreamCreateWithFlags(&gSlotStreams[i], cudaStreamNonBlocking));
            CHECK_CUDA(cudaEventCreateWithFlags(
                    &gSlotCompletionEvents[i], cudaEventBlockingSync | cudaEventDisableTiming));
            allocateGpuSortingSearchSlot(i, device, queueBytesBudget,
                                         slotsPerDevice[static_cast<size_t>(device)],
                                         slotOrdinalOnDevice);
        }
    } catch (...) {
        lock.unlock();
        destroyGpuSortSlots();
        if (originalDevice != -1) cudaSetDevice(originalDevice);
        throw;
    }

    if (originalDevice != -1) cudaSetDevice(originalDevice);
}

cudaStream_t getGpuSortSlotStream(const int slot) {
    if (slot < 0 || slot >= static_cast<int>(gSlotStreams.size())) return nullptr;
    const int device = (slot < static_cast<int>(gSlotDevices.size())) ? gSlotDevices[slot] : -1;
    if (device < 0) return nullptr;
    CHECK_CUDA(cudaSetDevice(device));
    return gSlotStreams[slot];
}

cudaEvent_t getGpuSortSlotCompletionEvent(const int slot) {
    if (slot < 0 || slot >= static_cast<int>(gSlotCompletionEvents.size())) {
        return nullptr;
    }
    return gSlotCompletionEvents[slot];
}

int getGpuSortSlotDevice(const int slot) {
    if (slot < 0 || slot >= static_cast<int>(gSlotDevices.size())) return -1;
    return gSlotDevices[slot];
}
