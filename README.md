# ST Mobile

SillyTavern on Android, in a single APK. No laptop, no server, no Termux, no setup.

SillyTavern normally runs on a computer under Node. ST Mobile embeds that runtime on the phone and starts it with a small native launcher, then unpacks its own copy of SillyTavern and serves it to a web view. Everything stays local, on the device.

## Built on

- **SillyTavern** (AGPL-3.0), bundled unmodified.
- **Node.js Mobile Runtime** (MIT), embedding Node.js 24 on Android.
- **AndroidX & Jetpack Compose** (Apache-2.0), modern native UI and lifecycle coordinator.

For comprehensive component attributions and third-party licenses, see [THIRD-PARTY.md](THIRD-PARTY.md).

## License

This project is licensed under the [GNU Affero General Public License v3.0 (AGPL-3.0)](LICENSE) because SillyTavern is bundled herein and governed by the AGPL-3.0. In compliance with Sections 6 and 13 of the AGPL-3.0, corresponding source code is made publicly available.

## Disclaimer

ST Mobile is an independent open-source project and is not affiliated with, endorsed by, sponsored by, or officially associated with the SillyTavern project or its maintainers. SillyTavern is a registered or common-law trademark of its respective authors.
