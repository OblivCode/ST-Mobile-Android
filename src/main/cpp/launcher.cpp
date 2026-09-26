// ST Mobile — Node runtime launcher (Approach B, D2).
//
// This is the entire launcher: a tiny PIE executable that links the prebuilt
// libnode.so and calls node::Start(). It is packaged into jniLibs as
// libstnode.so so AGP extracts it to nativeLibraryDir with the executable bit
// set (extractNativeLibs=true). The app execs it from that read-only location,
// which is W^X-safe at modern targetSdk — unlike files in filesDir.
//
// libnode.so is resolved at runtime via LD_LIBRARY_PATH=nativeLibraryDir.

#include <node/node.h>

int main(int argc, char** argv) {
    // node::Start blocks for the lifetime of the runtime and returns its exit code.
    return node::Start(argc, argv);
}