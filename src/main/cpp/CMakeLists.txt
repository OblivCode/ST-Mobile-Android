cmake_minimum_required(VERSION 3.22.1)
project(stmobile CXX)

# ---------------------------------------------------------------------------
# 16 KB page alignment for every binary we build (Android 15+ requirement).
# ---------------------------------------------------------------------------
add_link_options(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

# ---------------------------------------------------------------------------
# Prebuilt Node.js runtime, shipped in jniLibs (FongMi/nodejs-mobile,
# Node 24.20.0, NDK r27d, 16 KB-aligned). Both the launcher and the JNI spike
# link against it; at runtime it is loaded from nativeLibraryDir.
# ---------------------------------------------------------------------------
add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so)

# ---------------------------------------------------------------------------
# Phase 1: standalone launcher. Built as an executable, named libstnode.so so
# AGP extracts it as a native lib; the app exec()s it from nativeLibraryDir.
# ---------------------------------------------------------------------------
add_executable(stnode launcher.cpp)
target_include_directories(stnode PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/node-include
        ${CMAKE_CURRENT_SOURCE_DIR}/node-include/node)
target_link_libraries(stnode node)
set_target_properties(stnode PROPERTIES
        OUTPUT_NAME stnode
        PREFIX "lib"
        SUFFIX ".so"
        RUNTIME_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})