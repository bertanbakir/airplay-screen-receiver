cmake_minimum_required(VERSION 3.22)
project(airplay_receiver)

set(CMAKE_CXX_STANDARD 11)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 \
    -DSTANDALONE -D__STDC_CONSTANT_MACROS -D__STDC_LIMIT_MACROS \
    -DTARGET_POSIX -D_LINUX -fPIC -DPIC -D_REENTRANT \
    -D_LARGEFILE64_SOURCE -D_FILE_OFFSET_BITS=64 \
    -DNOHOLD -DFULL_RANGE_RGB_FIX -DSUPPRESS_AVAHI_COMPAT_WARNING")

# ── OpenSSL built from source (pure CMake, no Perl needed) ────────────────────
include(FetchContent)

FetchContent_Declare(
    openssl_cmake
    GIT_REPOSITORY https://github.com/janbar/openssl-cmake.git
    GIT_TAG        1.1.1w-20231130
    GIT_SHALLOW    TRUE
)
set(WITH_APPS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(openssl_cmake)

# janbar/openssl-cmake exposes targets: ssl  crypto
# Headers are under <build>/include  and <source>/include

# ── libplist (binary plist only, no XML / no libxml2) ────────────────────────
set(PLIST_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libplist)

file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/plist_config/config.h
"#pragma once
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_WCHAR_H 1
#define HAVE_BYTESWAP_H 1
#define PACKAGE \"libplist\"
#define PACKAGE_VERSION \"2.7.0\"
")

add_library(plist STATIC
    ${PLIST_DIR}/libcnary/cnary.c
    ${PLIST_DIR}/libcnary/node.c
    ${PLIST_DIR}/libcnary/node_list.c
    ${PLIST_DIR}/src/common.c
    ${PLIST_DIR}/src/plist.c
    ${PLIST_DIR}/src/bplist.c
    ${PLIST_DIR}/src/bytearray.c
    ${PLIST_DIR}/src/hashtable.c
    ${PLIST_DIR}/src/ptrarray.c
    ${PLIST_DIR}/src/base64.c
    ${PLIST_DIR}/src/jsmn.c
    ${PLIST_DIR}/src/jplist.c
    ${PLIST_DIR}/src/oplist.c
    ${PLIST_DIR}/src/time64.c
    ${PLIST_DIR}/src/out-default.c
    ${PLIST_DIR}/src/out-limd.c
    ${PLIST_DIR}/src/out-plutil.c
    ${CMAKE_CURRENT_SOURCE_DIR}/xplist_stub.c
)
target_include_directories(plist PUBLIC
    ${PLIST_DIR}/include
    ${PLIST_DIR}/libcnary/include
    ${PLIST_DIR}/src
    ${CMAKE_CURRENT_BINARY_DIR}/plist_config
)
target_compile_definitions(plist PRIVATE HAVE_CONFIG_H HAVE_STRNDUP)

# ── UxPlay: llhttp ─────────────────────────────────────────────────────────────
set(UXPLAY_LIB ${CMAKE_CURRENT_SOURCE_DIR}/UxPlay/lib)

add_library(llhttp STATIC
    ${UXPLAY_LIB}/llhttp/api.c
    ${UXPLAY_LIB}/llhttp/http.c
    ${UXPLAY_LIB}/llhttp/llhttp.c
)
target_include_directories(llhttp PUBLIC ${UXPLAY_LIB}/llhttp)

# ── UxPlay: playfair ──────────────────────────────────────────────────────────
add_library(playfair STATIC
    ${UXPLAY_LIB}/playfair/playfair.c
    ${UXPLAY_LIB}/playfair/hand_garble.c
    ${UXPLAY_LIB}/playfair/modified_md5.c
    ${UXPLAY_LIB}/playfair/omg_hax.c
    ${UXPLAY_LIB}/playfair/sap_hash.c
)
target_include_directories(playfair PUBLIC ${UXPLAY_LIB}/playfair)

# ── UxPlay: airplay core ──────────────────────────────────────────────────────
file(GLOB AIRPLAY_SRCS "${UXPLAY_LIB}/*.c")
list(REMOVE_ITEM AIRPLAY_SRCS "${UXPLAY_LIB}/dnssd.c")

add_library(airplay STATIC
    ${AIRPLAY_SRCS}
    ${CMAKE_CURRENT_SOURCE_DIR}/dnssd_android.c
)
target_include_directories(airplay PUBLIC
    ${UXPLAY_LIB}
    ${CMAKE_CURRENT_SOURCE_DIR}
)
target_compile_definitions(airplay PUBLIC
    OPENSSL_API_COMPAT=0x10101000L
    PLIST_210   # libplist >= 2.1.0 API
    PLIST_230   # libplist >= 2.3.0 API
)
target_link_libraries(airplay
    llhttp
    playfair
    plist
    crypto          # from janbar/openssl-cmake
    android
    log
)

# ── JNI bridge ────────────────────────────────────────────────────────────────
add_library(airplay_jni SHARED
    airplay_jni.cpp
)
target_link_libraries(airplay_jni
    airplay
    android
    log
)
