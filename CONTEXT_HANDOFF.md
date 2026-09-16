# STRAVO VPN context handoff

## Current state

- Project: STRAVO VPN.
- Branch: `feature/stravo-vpn-mvp`.
- Scope: one universal Android APK for Android phone and Android TV.
- UI direction: professional paper canvas, graphite map engraving, mountains, forest, compass, and restrained colored-pencil accents.
- Phone: ordinary VPN and white-list mode.
- TV: ordinary VPN only; no white-list button, menu item, deep-link activation path, or hidden fallback.
- Build policy: GitHub Actions is the canonical heavy-build environment because the owner's computer is weak.

## Checkpoint

Task 1 is complete at commit `61743df`. Tasks 2–4 are complete at commits `e328451`, `c6571ea`, and `fdad92f`; Task 5 is complete at `79b9b4c`; Task 6 is complete at `7cd0c06`. The repository contains a compiled Android app with a paper-inspired UI.

The 2026-09-15 visual checkpoint adds a native vector recreation of the owner's STRAVO launcher icon reference, wires it in the manifest, and replaces the Home placeholder with the paper/graphite/mint composition from the approved reference. Phone mode selection includes white lists; TV remains ordinary-only. Home connection status and metrics remain honest placeholders until the VPN engine is integrated.

The 2026-09-15 UI completion checkpoint is on commit `948b4d2`. Servers, Import, WhiteList (phone only), Settings, and Help are wired into the navigation graph. Profile selection and settings are persisted locally; the Home screen reflects the selected profile and mode. No UI path claims a connected tunnel before the engine reports one. Import uses the same shared paper screen shell on phone and TV, with clipboard import limited to phone.

GitHub Actions run `35020908022` on commit `948b4d2` completed `:app:assembleDebug` successfully. Its APK upload was rejected by the account artifact-storage quota, so no downloadable artifact is currently available. GitHub reports that quota usage is recalculated every 6–12 hours.

## Evidence boundary

The current source proves structure, branding, domain policy, local persistence, import validation by static inspection, and Android compilation in GitHub Actions. It does not prove APK download, installation, or runtime behavior on a physical device or emulator.

## Next step

After GitHub recalculates artifact usage, run the unchanged debug build again to obtain a downloadable APK. Before implementing the VPN engine, provide a redacted ordinary/white-list profile control flow diagram, an event-sequence diagram for Home status and metrics, and a threat model for profile data at rest and the clipboard import path.
