cmake_minimum_required(VERSION 3.22.1)
project(stmobile CXX)

# ---------------------------------------------------------------------------
# 16 KB page alignment for every binary we build (Android 15+ requirement).
# ---------------------------------------------------------------------------
add_link_options(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

# ---------------------------------------------------------------------------
# Standalone launcher. Exec'd from nativeLibraryDir; it dlopen()s the Node
# runtime named by ST_NODE_LIB at runtime (bundled or downloaded), so it does
# not link libnode.so.
# ---------------------------------------------------------------------------
add_executable(stnode launcher.cpp)
target_link_libraries(stnode dl)
set_target_properties(stnode PROPERTIES
        OUTPUT_NAME stnode
        PREFIX "lib"
        SUFFIX ".so"
        RUNTIME_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
