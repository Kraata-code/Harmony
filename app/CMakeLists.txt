# ============================================
# ARCHIVO: app/CMakeLists.txt
# UBICACIÓN: /home/kraata/Escritorio/Proyecto/Hamony_OuterTune/app/CMakeLists.txt
# DESCRIPCIÓN: Este es TU archivo principal de CMake
# ============================================

cmake_minimum_required(VERSION 3.22.1)
project("harmony_outertune" CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

message(STATUS "=== Configurando llama.cpp para Android ===")
message(STATUS "ABI: ${ANDROID_ABI}")
message(STATUS "Platform: ${ANDROID_PLATFORM}")
message(STATUS "Source dir: ${CMAKE_CURRENT_SOURCE_DIR}")

# ============================================
# CONFIGURACIÓN DE LLAMA.CPP
# ============================================

# Deshabilitar todo lo innecesario ANTES de incluir el proyecto
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_TOOLS_INSTALL OFF CACHE BOOL "" FORCE)

# Solo habilitar lo necesario
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)

# Deshabilitar networking
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_HTTPLIB OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)

# ============================================
# CONFIGURACIÓN DE GGML
# ============================================

set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_KOMPUTE OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Optimizaciones ARM64
if(ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "Configurando optimizaciones ARM64")
    add_compile_definitions(GGML_USE_CPU)
endif()

# ============================================
# INCLUIR LLAMA.CPP
# ============================================

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/src/main/llama.cpp")
message(STATUS "Buscando llama.cpp en: ${LLAMA_CPP_DIR}")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "No se encuentra ${LLAMA_CPP_DIR}/CMakeLists.txt")
endif()

# CRÍTICO: Incluir con EXCLUDE_FROM_ALL para evitar que compile herramientas
add_subdirectory(${LLAMA_CPP_DIR} EXCLUDE_FROM_ALL)

message(STATUS "=== Verificando targets ===")

# Los targets que necesitamos
set(REQUIRED_TARGETS "llama" "ggml")
foreach(target ${REQUIRED_TARGETS})
    if(TARGET ${target})
        message(STATUS "✓ Target '${target}' disponible")
    else()
        message(WARNING "✗ Target '${target}' NO disponible")
    endif()
endforeach()

# ============================================
# COMPILAR LIBRERÍA JNI
# ============================================

message(STATUS "=== Compilando librería JNI ===")

# Crear la librería JNI que carga llama.cpp
add_library(llama-jni-lib SHARED
    src/main/llama.cpp/llama-jni.cpp
)

# Incluir headers de llama.cpp
target_include_directories(llama-jni-lib PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

# Enlazar con llama y ggml
target_link_libraries(llama-jni-lib PRIVATE
    llama
    ggml
    log  # NDK log library
)

# Configuración específica
set_target_properties(llama-jni-lib PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

# Optimizaciones de tamaño para ARM64
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(llama-jni-lib PRIVATE
        -O3
        -march=armv8-a
        -mtune=cortex-a53
    )
endif()

message(STATUS "✓ Librería JNI 'llama-jni-lib' configurada")

message(STATUS "=== Configuración completada ===")
message(STATUS "PASO 1: llama.cpp compilado")
message(STATUS "PASO 2: Librería JNI compilada")