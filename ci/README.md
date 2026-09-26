# Build scripts

| Script | Purpose |
| :--- | :--- |
| `fetch_node_runtime.sh` | Download the pinned FongMi Node runtime; verify SHA-256; place `jniLibs/` + headers |
| `build_st_bundle.sh` | Clone the pinned SillyTavern tag, `npm ci --omit=dev --ignore-scripts`, native-addon audit, tar + manifest |
| `check_elf_align.py` | Verify 16 KB `PT_LOAD` alignment of the native binaries |
| `build_all.sh` | Run all of the above, then `assembleDebug` |

[`.github/workflows/build.yml`](../.github/workflows/build.yml) runs these on CI, builds the APK,
signs it when the `RELEASE_*` secrets are present, writes checksums, and publishes a GitHub release
on a `v*` tag.
