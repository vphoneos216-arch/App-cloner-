cmake_minimum_required(VERSION 3.18.1)
project(appclonerbridge CXX)

add_library(appclonerbridge SHARED native_bridge.cpp)

find_library(log-lib log)
target_link_libraries(appclonerbridge ${log-lib})

target_compile_options(appclonerbridge PRIVATE -Wall -Wextra -fvisibility=hidden)
set_target_properties(appclonerbridge PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
