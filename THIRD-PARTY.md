# Third-party notices — ST Mobile

ST Mobile bundles and/or links the following components. Redistributors must
preserve these notices.

## SillyTavern (bundled, unmodified)
- Source: https://github.com/SillyTavern/SillyTavern
- Version: 1.19.0 (commit 7e8663cd9c184a550b37238218bdd32c6efc68e9)
- License: **AGPL-3.0** (see `LICENSE`)
- Because SillyTavern is AGPL-3.0, this application as distributed is subject to
  the AGPL-3.0: the complete corresponding source must be made available to users.

## Node.js (`libnode.so`)
- Source: https://github.com/FongMi/nodejs-mobile (fork of nodejs/node)
- Version: v24.20.0-android.2
- License: **MIT** (Node.js); see the runtime bundle for the full text.

## libc++ (`libc++_shared.so`)
- Project: LLVM libc++
- License: Apache-2.0 WITH LLVM-exception

## Bundled npm dependency tree
SillyTavern's `node_modules` is included under the terms of each package's own
license. Run `ci/build_st_bundle.sh`, which retains SillyTavern's
`package-lock.json`, and generate an aggregated notice with your preferred tool
before publishing a release.

ST Mobile itself is not affiliated with or endorsed by SillyTavern.
