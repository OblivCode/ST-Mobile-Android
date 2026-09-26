# ST Mobile

SillyTavern on Android, in a single APK. No laptop, no server, no Termux, no setup.

SillyTavern normally runs on a computer under Node. ST Mobile embeds that runtime on the phone and starts it with a small native launcher, then unpacks its own copy of SillyTavern and serves it to a web view. Everything stays local, on the device.

The full build story, decision by decision, is in [dev-story.md](dev-story.md).

## Built on

- **SillyTavern** (AGPL-3.0), bundled unmodified.
- **nodejs-mobile** (MIT), the project that makes Node embeddable on mobile. The runtime here is a community fork's build of it, Node 24.
- **Node.js** (MIT), the runtime itself.

## License

AGPL-3.0, because SillyTavern is bundled here and SillyTavern is AGPL-3.0. That is why this source is public. See [LICENSE](LICENSE).

Not affiliated with or endorsed by SillyTavern.
