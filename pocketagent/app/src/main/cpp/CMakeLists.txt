cmake_minimum_required(VERSION 3.22.1)
project(pocketagent)

set(CMAKE_CXX_STANDARD 17)

# ── 期望 llama.cpp 源码在 pocketagent/llama.cpp ─────────────────────────
# 从 cpp 目录(app/src/main/cpp)上溯 4 层即 pocketagent/。
# 用 git submodule 或手动 clone 放到那里（见 README）。
if(NOT DEFINED LLAMA_DIR)
    set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp)
endif()

if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "找不到 llama.cpp（期望在 ${LLAMA_DIR}）。\n"
        "请先在 pocketagent/ 下：git clone https://github.com/ggml-org/llama.cpp")
endif()

# 关掉 llama.cpp 的附带目标，只编库，加快构建。
# 注：这些开关名可能随 llama.cpp 版本变（有的版本用 GGML_* 前缀），编译报错就去 llama.cpp 的 CMake 看实际名字。
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

# 我们的 JNI 库
add_library(pocketagent SHARED native-lib.cpp)

find_library(log-lib log)
# 链接 llama.cpp 的 llama 目标 + Android 日志库
target_link_libraries(pocketagent llama ${log-lib})
