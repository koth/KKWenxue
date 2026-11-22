cmake_minimum_required(VERSION 3.22.1)
project(shanye_tts_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_ANDROID_STL_TYPE c++_shared)

add_library(shanye_tts_native SHARED shanye_tts_native.cpp)
target_compile_options(shanye_tts_native PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_compile_definitions(shanye_tts_native PRIVATE SHANYE_NATIVE_BRIDGE=1)
find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(shanye_tts_native PRIVATE ${log-lib} ${android-lib})

# The ZONOS3 checkout is intentionally kept outside the Android module because it is also
# used by the server. When it is available, build the same inference and codec sources
# against ggml. A clean checkout still produces a small capability bridge, so the Android
# project remains buildable before the optional model source bundle is installed.
set(SHANYE_ZONOS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../tmp/zonos3-mini/ZONOS3-Mini-main/servers/ggml")
if(EXISTS "${SHANYE_ZONOS_ROOT}/ggml/CMakeLists.txt" AND EXISTS "${SHANYE_ZONOS_ROOT}/src/zonos3.h")
    set(SHANYE_GGML_PATCH "${SHANYE_ZONOS_ROOT}/patches/metal-conv1d-implicit.patch")
    if(EXISTS "${SHANYE_GGML_PATCH}")
        execute_process(COMMAND git -C "${SHANYE_ZONOS_ROOT}/ggml" apply --reverse --check "${SHANYE_GGML_PATCH}"
            RESULT_VARIABLE SHANYE_GGML_PATCHED OUTPUT_QUIET ERROR_QUIET)
        if(NOT SHANYE_GGML_PATCHED EQUAL 0)
            execute_process(COMMAND git -C "${SHANYE_ZONOS_ROOT}/ggml" apply "${SHANYE_GGML_PATCH}"
                RESULT_VARIABLE SHANYE_GGML_APPLY RESULT_STRIP_TRAILING_WHITESPACE)
            if(NOT SHANYE_GGML_APPLY EQUAL 0)
                message(FATAL_ERROR "Unable to apply the ZONOS3 ggml CPU codec patch")
            endif()
        endif()
    endif()
    set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
    add_subdirectory("${SHANYE_ZONOS_ROOT}/ggml" "${CMAKE_CURRENT_BINARY_DIR}/ggml")

    function(shanye_embed_json source symbol header)
        file(READ "${source}" hex HEX)
        string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," bytes "${hex}")
        string(LENGTH "${hex}" hex_len)
        math(EXPR byte_len "${hex_len} / 2")
        set(ZONOS3_EMBED_SYMBOL "${symbol}")
        set(ZONOS3_EMBED_BYTES "${bytes}")
        set(ZONOS3_EMBED_LEN "${byte_len}")
        configure_file("${SHANYE_ZONOS_ROOT}/src/embed-json.h.in"
            "${CMAKE_CURRENT_BINARY_DIR}/generated/${header}" @ONLY)
    endfunction()
    file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/generated")
    shanye_embed_json("${SHANYE_ZONOS_ROOT}/../../model/src/zonos3/registries/languages.json"
        zonos3_languages_json zonos3-languages-json.h)
    shanye_embed_json("${SHANYE_ZONOS_ROOT}/../../model/src/zonos3/registries/generation.json"
        zonos3_generation_json zonos3-generation-json.h)
    shanye_embed_json("${SHANYE_ZONOS_ROOT}/../../model/src/zonos3/registries/short_text.json"
        zonos3_short_text_json zonos3-short-text-json.h)

    add_library(shanye_zonos3_text STATIC "${SHANYE_ZONOS_ROOT}/src/zonos3-text.cpp")
    target_include_directories(shanye_zonos3_text PUBLIC "${SHANYE_ZONOS_ROOT}/src")

    add_library(shanye_zonos3 STATIC
        "${SHANYE_ZONOS_ROOT}/src/zonos3.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-graph.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-sampler.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-prompt.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-tokenizer.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-steer.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-languages.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-generation.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-accent.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-short-text.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-laci.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-span-follow.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-edit.cpp"
        "${SHANYE_ZONOS_ROOT}/src/zonos3-phonemes.cpp")
    target_include_directories(shanye_zonos3 PUBLIC
        "${SHANYE_ZONOS_ROOT}/src"
        "${SHANYE_ZONOS_ROOT}/ggml/include"
        "${SHANYE_ZONOS_ROOT}/vendor"
        "${CMAKE_CURRENT_BINARY_DIR}/generated")
    add_library(shanye_lmdb STATIC
        "${SHANYE_ZONOS_ROOT}/vendor/lmdb/mdb.c"
        "${SHANYE_ZONOS_ROOT}/vendor/lmdb/midl.c")
    target_include_directories(shanye_lmdb PUBLIC "${SHANYE_ZONOS_ROOT}/vendor/lmdb")
    find_package(Threads REQUIRED)
    target_link_libraries(shanye_zonos3 PUBLIC ggml shanye_zonos3_text shanye_lmdb Threads::Threads ${CMAKE_DL_LIBS})

    add_library(shanye_zac STATIC "${SHANYE_ZONOS_ROOT}/src/zac.cpp")
    target_include_directories(shanye_zac PUBLIC
        "${SHANYE_ZONOS_ROOT}/src"
        "${SHANYE_ZONOS_ROOT}/ggml/include"
        "${SHANYE_ZONOS_ROOT}/vendor")
    target_link_libraries(shanye_zac PUBLIC ggml shanye_zonos3_text)
    target_link_libraries(shanye_tts_native PRIVATE shanye_zonos3 shanye_zac)
    target_include_directories(shanye_tts_native PRIVATE
        "${SHANYE_ZONOS_ROOT}/src"
        "${SHANYE_ZONOS_ROOT}/ggml/include"
        "${SHANYE_ZONOS_ROOT}/vendor")
    target_compile_definitions(shanye_tts_native PRIVATE SHANYE_ZONOS3_AVAILABLE=1)
endif()
