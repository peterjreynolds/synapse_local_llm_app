cmake_minimum_required(VERSION 3.31.6)

project("synapse-llama" VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(NOT ANDROID_ABI STREQUAL "armeabi-v7a" AND NOT ANDROID_ABI STREQUAL "x86_64")
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
    add_subdirectory(${LLAMA_SRC} build-llama)

    add_library(${CMAKE_PROJECT_NAME} SHARED embedded_llama.cpp)

    target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
    )

    target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src
    )

    target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log
    )
else()
    # Compatibility ABIs retain the app's chat/calling surface without pretending
    # that the ARM64-only embedded inference engine is available.
    add_library(${CMAKE_PROJECT_NAME} SHARED unsupported_embedded_llama.cpp)
endif()
