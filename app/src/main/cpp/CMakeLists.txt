cmake_minimum_required(VERSION 3.22.1)

project(newpipe-translator LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pull a pinned llama.cpp release. The first configure downloads ~30 MB of source.
include(FetchContent)
FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        b8996
        GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(newpipe-translator SHARED
        llama_translator.cpp
)

target_link_libraries(newpipe-translator
        PRIVATE
        llama
        ggml
        log
        android
)
