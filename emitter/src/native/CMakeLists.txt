cmake_minimum_required(VERSION 3.24)
project(tdp1375_native LANGUAGES CXX CUDA)

find_package(CUDAToolkit REQUIRED)

set(TDP_DEFAULT_CUDA_ARCHITECTURES)
set(TDP_FORWARD_CUDA_ARCHITECTURE)

# sm_60, sm_70: dropped in CUDA 13.0
if(CMAKE_CUDA_COMPILER_VERSION VERSION_LESS 13.0)
    list(APPEND TDP_DEFAULT_CUDA_ARCHITECTURES 60-real 70-real)
endif()

# sm_75, sm_80, sm_86: supported since CUDA 10/11, still present in 13.x
list(APPEND TDP_DEFAULT_CUDA_ARCHITECTURES 75-real 80-real 86-real)
set(TDP_FORWARD_CUDA_ARCHITECTURE 86-virtual)

if(CMAKE_CUDA_COMPILER_VERSION VERSION_GREATER_EQUAL 11.8)
    list(APPEND TDP_DEFAULT_CUDA_ARCHITECTURES 89-real)
    set(TDP_FORWARD_CUDA_ARCHITECTURE 89-virtual)
endif()
if(CMAKE_CUDA_COMPILER_VERSION VERSION_GREATER_EQUAL 12.0)
    list(APPEND TDP_DEFAULT_CUDA_ARCHITECTURES 90-real)
    set(TDP_FORWARD_CUDA_ARCHITECTURE 90-virtual)
endif()
if(CMAKE_CUDA_COMPILER_VERSION VERSION_GREATER_EQUAL 12.8)
    list(APPEND TDP_DEFAULT_CUDA_ARCHITECTURES 100-real 120-real)
    set(TDP_FORWARD_CUDA_ARCHITECTURE 120-virtual)
endif()
list(APPEND TDP_DEFAULT_CUDA_ARCHITECTURES ${TDP_FORWARD_CUDA_ARCHITECTURE})
set(TDP_CUDA_ARCHITECTURES "${TDP_DEFAULT_CUDA_ARCHITECTURES}"
    CACHE STRING "CUDA architectures supported by the native libraries" FORCE)
set(CMAKE_CUDA_ARCHITECTURES ${TDP_CUDA_ARCHITECTURES})
message(STATUS "CUDA architectures: ${CMAKE_CUDA_ARCHITECTURES}")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CUDA_STANDARD 17)
set(CMAKE_CUDA_STANDARD_REQUIRED ON)
set(CMAKE_CUDA_RUNTIME_LIBRARY Shared)

set(TDP_NATIVE_MAX_N 17)
set(TDP_NATIVE_MAX_DEPTH 9)

# ── tdp1375_jni (JNI shared library) ────────────────────────────────────────

find_package(JNI)

if(JNI_FOUND)
    add_library(tdp1375_jni SHARED
        jni/jni_sorter.cu
        backtracking/slot_manager.cu
        backtracking/sorting_search.cu
    )

    target_include_directories(tdp1375_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${JNI_INCLUDE_DIRS}
    )
    target_link_libraries(tdp1375_jni PRIVATE CUDA::cudart ${JNI_LIBRARIES})

    set_target_properties(tdp1375_jni PROPERTIES
        POSITION_INDEPENDENT_CODE ON
        CUDA_SEPARABLE_COMPILATION ON
        CUDA_RESOLVE_DEVICE_SYMBOLS ON
    )

    target_compile_definitions(tdp1375_jni PRIVATE MAX_N=${TDP_NATIVE_MAX_N} MAX_DEPTH=${TDP_NATIVE_MAX_DEPTH})

    target_compile_options(tdp1375_jni PRIVATE
        $<$<COMPILE_LANGUAGE:CUDA>:-Xptxas=-v>
        $<$<CONFIG:Debug>:-G>
        $<$<CONFIG:RelWithDebInfo>:-lineinfo>
    )

    install(TARGETS tdp1375_jni DESTINATION lib)
endif()
