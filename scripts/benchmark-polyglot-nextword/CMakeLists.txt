cmake_minimum_required(VERSION 3.21)
project(benchmark_polyglot_nextword LANGUAGES CXX)

set(LLAMA_SOURCE_DIR "" CACHE PATH "llama.cpp source directory")
set(LLAMA_BUILD_DIR "" CACHE PATH "existing Windows llama.cpp build directory")

if(NOT EXISTS "${LLAMA_SOURCE_DIR}/include/llama.h")
    message(FATAL_ERROR "LLAMA_SOURCE_DIR must contain include/llama.h")
endif()
if(NOT EXISTS "${LLAMA_BUILD_DIR}/src/llama.lib")
    message(FATAL_ERROR "LLAMA_BUILD_DIR must contain src/llama.lib")
endif()

add_executable(benchmark-polyglot-nextword ../benchmark-polyglot-nextword.cpp)
target_compile_features(benchmark-polyglot-nextword PRIVATE cxx_std_20)
target_include_directories(benchmark-polyglot-nextword PRIVATE
    "${LLAMA_SOURCE_DIR}/include"
    "${LLAMA_SOURCE_DIR}/ggml/include")
target_link_libraries(benchmark-polyglot-nextword PRIVATE "${LLAMA_BUILD_DIR}/src/llama.lib")

if(MSVC)
    target_compile_options(benchmark-polyglot-nextword PRIVATE /EHsc)
endif()
