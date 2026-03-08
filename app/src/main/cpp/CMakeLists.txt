cmake_minimum_required(VERSION 3.22.1)
project(retro_bridge)

add_library(retro_bridge SHARED libretro_bridge.cpp)

find_library(log-lib log)

target_link_libraries(retro_bridge ${log-lib})
