cmake_minimum_required(VERSION 3.22.1)
project(apkfixer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(apkfixer SHARED bridge.cpp)
target_compile_options(apkfixer PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
target_link_options(apkfixer PRIVATE -Wl,-z,max-page-size=16384)
