# Roadmap

High-level plan. The detailed technical history lives outside the repo (project notes).

## Done

- **Runtime** — prebuilt Node 24 driven by a native launcher, giving a real Node process.
- **SillyTavern** — bundled and booting on-device.
- **Background** — server hosted in a foreground service.
- **Shell** — Compose UI, settings, logs, backup, file picker.
- **CI** — pinned runtime + bundle build, 16 KB checks, signing, releases.

## Next

1. **Settings & first-time config** — an onboarding/first-run flow and a clearer configuration
   experience.
2. **llama.cpp** — assess local embeddings (`llama-server` + gguf) as a follow-up.

## Later

- Manual acceptance pass — character-card upload, backup export/import round-trip.
- First tagged release.

## Gates before push

- First-run/settings experience in place.
- Acceptance pass green.
