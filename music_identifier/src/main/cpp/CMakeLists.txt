cmake_minimum_required(VERSION 3.22.1)

project("music_identifier")

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(AUDIO_PROCESSOR_LIB "" CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(FFT_LIB kissfft CACHE STRING "" FORCE)
set(KISSFFT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/chromaprint/src/3rdparty/kissfft" CACHE PATH "" FORCE)
set(KISSFFT_SOURCE_DIR "${KISSFFT_ROOT}" CACHE PATH "" FORCE)
set(USE_INTERNAL_AVRESAMPLE ON CACHE BOOL "" FORCE)

add_subdirectory(chromaprint EXCLUDE_FROM_ALL)

add_library(chromaprint-jni SHARED chromaprint_jni.cpp)
target_include_directories(chromaprint-jni PRIVATE chromaprint/src/include)
target_link_options(chromaprint-jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
target_link_libraries(chromaprint-jni PRIVATE chromaprint log)
