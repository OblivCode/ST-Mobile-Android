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
#include <signal.h>
#include <sys/prctl.h>
#include <unistd.h>

int main(int argc, char** argv) {
    pid_t parent = getppid();
    if (parent <= 1) {
        return 1;
    }

    // Deliver SIGKILL if the Android parent process dies, eliminating zombies/orphans.
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0) {
        return 1;
    }

    if (getppid() != parent) {
        // Parent died before or during prctl setup, child reparented
        return 1;
    }

    // Establish own process group so child subprocesses can be signaled together.
    setpgid(0, 0);

    // node::Start blocks for the lifetime of the runtime and returns its exit code.
    return node::Start(argc, argv);
}