cmake_minimum_required(VERSION 3.22.1)
project(cannoli_native)

find_library(log-lib log)

# --- rcheevos (RetroAchievements client library) ---
file(GLOB RCHEEVOS_SRC
    third_party/rcheevos/src/rcheevos/*.c
    third_party/rcheevos/src/rapi/*.c
    third_party/rcheevos/src/rhash/*.c
    third_party/rcheevos/src/rc_client.c
    third_party/rcheevos/src/rc_compat.c
    third_party/rcheevos/src/rc_util.c
    third_party/rcheevos/src/rc_version.c)
add_library(rcheevos STATIC ${RCHEEVOS_SRC})
target_include_directories(rcheevos PUBLIC third_party/rcheevos/include)
target_include_directories(rcheevos PRIVATE third_party/rcheevos/src ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(rcheevos PRIVATE RC_DISABLE_LUA)
target_compile_definitions(rcheevos PUBLIC RC_CLIENT_SUPPORTS_HASH)

# --- retro_bridge (libretro core runner) ---
add_library(retro_bridge SHARED libretro_bridge.cpp ra_integration.c)
target_link_libraries(retro_bridge rcheevos ${log-lib})
target_include_directories(retro_bridge PRIVATE third_party/rcheevos/include)

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

# --- vulkan_renderer (Vulkan rendering backend) ---
add_library(vulkan_renderer SHARED vulkan_renderer.cpp vulkan_jni.cpp)
target_link_libraries(vulkan_renderer vulkan android ${log-lib})
target_include_directories(vulkan_renderer PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
