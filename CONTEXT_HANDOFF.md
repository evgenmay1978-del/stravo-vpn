# STRAVO VPN context handoff

## Current state

- Project: STRAVO VPN.
- Branch: `feature/stravo-vpn-mvp`.
- Scope: one universal Android APK for Android phone and Android TV.
- UI direction: professional paper canvas, graphite map engraving, mountains, forest, compass, and restrained colored-pencil accents.
- Phone: ordinary VPN and white-list mode.
- TV: ordinary VPN only; no white-list button, menu item, deep-link activation path, or hidden fallback.
- Build policy: GitHub Actions is the canonical heavy-build environment because the owner’s computer is weak.

## Checkpoint

Task 1 is complete at commit `61743df`. Tasks 2–4 are complete at commits `e328451`, `c6571ea`, and `fdad92f`; Task 5 is complete at `79b9b4c`; Task 6 is complete at `7cd0c06`. The repository contains the Android build skeleton, pencil-canvas design system, universal phone/TV navigation, encrypted local profile storage, DataStore settings/subscriptions, and profile import/validation. Local static checks passed; the heavy Android assemble was not run on the owner’s weak computer. The concrete VPN engine and target subscription contract are not selected yet.

The 2026-09-15 visual checkpoint adds a native vector recreation of the owner’s STRAVO launcher icon reference, wires it in the manifest, and replaces the Home placeholder with the paper/graphite/mint composition from the approved reference. Phone mode selection includes white lists; TV remains ordinary-only. Home connection status and metrics remain honest placeholders until the VPN engine is integrated.

GitHub Actions run `35006576041` on commit `844be54` completed `assembleDebug` successfully. Its APK upload was rejected by the account artifact-storage quota, so no downloadable artifact is currently available.

## Evidence boundary

The current source proves structure, branding, domain policy, local persistence, and import validation by static inspection only. It does not prove a GitHub build, profile import on a device, or real VPN connectivity.

## Next step

Clear the GitHub Actions artifact-storage quota before the next debug run if a downloadable APK is required. Before implementing the VPN engine, provide a redacted ordinary/white-list profile contract so the exact engine and version can be selected from primary documentation.
