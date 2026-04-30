cmake_minimum_required(VERSION 3.10.2)
project("localai-jni")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ==========================================
# MNN LLM 框架配置
# LocalAI-Server v4.0-MNN
# ==========================================

# MNN预编译库路径
set(MNN_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# 导入MNN核心库
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION ${MNN_LIB_DIR}/libMNN.so)

add_library(MNN_Express SHARED IMPORTED)
set_target_properties(MNN_Express PROPERTIES IMPORTED_LOCATION ${MNN_LIB_DIR}/libMNN_Express.so)

add_library(llm SHARED IMPORTED)
set_target_properties(llm PROPERTIES IMPORTED_LOCATION ${MNN_LIB_DIR}/libllm.so)

# MNN头文件目录
set(MNN_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/mnn-src/include)

# JNI库
add_library(localai-jni SHARED
    native-lib.cpp
)

target_include_directories(localai-jni PRIVATE
    ${MNN_INCLUDE_DIR}
)

target_link_libraries(localai-jni
    MNN
    MNN_Express
    llm
    log
    android
)

# 编译选项
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-rtti -fno-exceptions")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fno-rtti")
