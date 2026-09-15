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

Task 1 is the active implementation task. The repository currently contains the build skeleton, a minimal STRAVO launch surface, local project rules, and the GitHub Actions workflow. The concrete VPN engine and target subscription contract are not selected yet.

## Evidence boundary

The current launch surface proves only source structure and branding. It does not prove profile import or real VPN connectivity.

## Next step

Run the first debug build in GitHub Actions after the repository is connected, then continue with domain models and the capability policy.
