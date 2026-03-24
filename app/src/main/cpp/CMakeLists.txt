cmake_minimum_required(VERSION 3.22.1)
project(cannoli_native)

find_library(log-lib log)

# --- retro_bridge (libretro core runner) ---
add_library(retro_bridge SHARED libretro_bridge.cpp)
target_link_libraries(retro_bridge ${log-lib})

# --- glslang (Vulkan GLSL → SPIR-V) ---
set(ENABLE_HLSL OFF CACHE BOOL "" FORCE)
set(ENABLE_CTEST OFF CACHE BOOL "" FORCE)
set(ENABLE_GLSLANG_BINARIES OFF CACHE BOOL "" FORCE)
set(SKIP_GLSLANG_INSTALL ON CACHE BOOL "" FORCE)
set(ENABLE_OPT OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/glslang)

# --- spirv-cross (SPIR-V → GLES GLSL) ---
set(SPIRV_CROSS_CLI OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_HLSL OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_MSL OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_CPP OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_REFLECT OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/spirv-cross)

# --- slang_transpiler (JNI bridge for shader transpilation) ---
add_library(slang_transpiler SHARED slang_transpiler.cpp)
target_link_libraries(slang_transpiler
    glslang SPIRV glslang-default-resource-limits
    spirv-cross-glsl
    ${log-lib})
target_include_directories(slang_transpiler PRIVATE
    third_party/glslang
    third_party/spirv-cross
    third_party/spirv-cross/include)
