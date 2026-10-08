cmake_minimum_required(VERSION 3.22.1)
project(llama_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- 16 KB page alignment (Android 15 ready) ---
add_link_options(
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")

# --- llama.cpp 源码就在本目录下（我们自己下载的那份官方源码）---
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# ── OpenCL GPU 加速（Qualcomm Adreno）─────────────────────────────
# llama.cpp 的 OpenCL 后端就是**为 Adreno 优化的**（官方支持表里有 Adreno 830 =
# 骁龙 8 Elite）。头文件和 ICD loader 我们自己放在 ./opencl/ 下（没动 NDK）。
#
# ⚠ 那份 libOpenCL.so **只用于链接，不打进 APK** —— 运行期用设备厂商自带的那份
#   （靠 AndroidManifest 里的 <uses-native-library android:name="libOpenCL.so"/>）。
# ⚠ x86_64（只给模拟器）没有 OpenCL 驱动 → 不开这个后端，也就不会产生
#   `DT_NEEDED libOpenCL.so`，模拟器照旧纯 CPU 跑。
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
else()
    set(GGML_OPENCL      ON  CACHE BOOL "" FORCE)
    set(_ocl "${CMAKE_CURRENT_SOURCE_DIR}/opencl")
    set(OpenCL_INCLUDE_DIR "${_ocl}/include" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY "${_ocl}/lib/${CMAKE_ANDROID_ARCH_ABI}/libOpenCL.so" CACHE FILEPATH "" FORCE)
    # 内核要嵌进 .so，得用宿主 python 跑 embed_kernel.py
    set(Python3_EXECUTABLE "D:/python13.3/python.exe" CACHE FILEPATH "" FORCE)
endif()

# 构建配置：CPU/NEON（+ Adreno 上的 OpenCL GPU）。
# ⚠ Vulkan GPU 已回退（Adreno 上开 Vulkan 会闪退）—— 要重启用见 auto-memory
#   里的 `reference_vulkan_build.md`（四个坑都在那儿）。
# 不开 OpenMP（避免 libomp.so 运行期依赖）、不要工具与示例。
# 动态库 → libllama.so + libggml*.so，由 Kotlin 那边按顺序 load。
set(BUILD_SHARED_LIBS    ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)   # 绑定只用核心 llama.h API

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# --- JNI 桥 → libllama_jni.so（这份 JNI 是 llama-android 的原版，不是我手写的）---
add_library(llama_jni SHARED llama_jni.cpp)

find_library(log-lib log)

target_include_directories(llama_jni PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include)

target_link_libraries(llama_jni
    llama
    ${log-lib})
