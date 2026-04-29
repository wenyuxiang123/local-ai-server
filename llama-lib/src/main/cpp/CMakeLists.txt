cmake_minimum_required(VERSION 3.22.0)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
        # Enable Vulkan GPU offload for ARM64 devices
        set(GGML_VULKAN ON)
        message(STATUS "Vulkan GPU offload enabled for ARM64")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
        set(GGML_VULKAN OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# 指向 llama.cpp 源码目录，可通过环境变量 LLAMA_CPP_SRC 覆盖
if(DEFINED ENV{LLAMA_CPP_SRC})
    set(LLAMA_SRC "$ENV{LLAMA_CPP_SRC}")
    message(STATUS "Using llama.cpp from environment: ${LLAMA_SRC}")
elseif(DEFINED LLAMA_CPP_SRC)
    set(LLAMA_SRC "${LLAMA_CPP_SRC}")
    message(STATUS "Using llama.cpp from variable: ${LLAMA_SRC}")
elseif(EXISTS "/app/data/所有对话/主对话/llama-cpp-temp")
    set(LLAMA_SRC "/app/data/所有对话/主对话/llama-cpp-temp")
    message(STATUS "Using local llama.cpp: ${LLAMA_SRC}")
else()
    # Fallback: look for llama.cpp in common relative paths
    set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
    message(STATUS "Defaulting to local llama.cpp: ${LLAMA_SRC}")
endif()

if(NOT EXISTS ${LLAMA_SRC})
    message(FATAL_ERROR "llama.cpp source not found at: ${LLAMA_SRC}. Please set LLAMA_CPP_SRC environment variable or clone llama.cpp.")
endif()

message(STATUS "Using llama.cpp from: ${LLAMA_SRC}")

# Pass Vulkan flag to llama.cpp
set(GGML_VULKAN ${GGML_VULKAN} CACHE BOOL "Enable Vulkan backend" FORCE)

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
        GGML_VULKAN=$<BOOL:${GGML_VULKAN}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

# Link libraries - Vulkan is compiled into llama if GGML_VULKAN=ON
target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
