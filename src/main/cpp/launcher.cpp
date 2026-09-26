// ST Mobile — Node runtime launcher.
//
// This executable is exec'd from nativeLibraryDir (the read-only, W^X-safe
// location). It does NOT link the Node runtime: it dlopen()s the shared library
// named by the ST_NODE_LIB environment variable and calls node::Start().
//
// That indirection is what allows the runtime to be either bundled (a path in
// nativeLibraryDir) or downloaded on first run (a path in app storage), without
// changing the launcher.

#include <dlfcn.h>
#include <cstdio>
#include <cstdlib>

namespace {
using node_start_fn = int (*)(int, char**);

// Itanium-mangled C++ symbol for node::Start(int, char**).
constexpr const char* kNodeStartSymbol = "_ZN4node5StartEiPPc";
}

int main(int argc, char** argv) {
    const char* libPath = std::getenv("ST_NODE_LIB");
    if (libPath == nullptr || libPath[0] == '\0') {
        std::fprintf(stderr, "[stnode] ST_NODE_LIB is not set\n");
        return 4;
    }

    void* handle = dlopen(libPath, RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        std::fprintf(stderr, "[stnode] dlopen(%s) failed: %s\n", libPath, dlerror());
        return 2;
    }

    auto start = reinterpret_cast<node_start_fn>(dlsym(handle, kNodeStartSymbol));
    if (start == nullptr) {
        std::fprintf(stderr, "[stnode] dlsym(%s) failed: %s\n", kNodeStartSymbol, dlerror());
        return 3;
    }

    // node::Start blocks for the lifetime of the runtime.
    return start(argc, argv);
}
