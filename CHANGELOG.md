# Changelog

[Chinese](CHANGELOG_ZH.md)

## [Unreleased] - 2026-07-16

### Added

- Added a refreshed mod icon and wired it through `neoforge.mods.toml`.
- Added shape-aware provider outlines that render only the outer visible block shape.
- Added floating status badges with compact localized text and separated output icon previews.
- Added hex RGB color configuration under `appearance.colors`.
- Added grouped opacity settings under `appearance.opacity`.
- Added diagnostics configuration for detailed provider tracking logs.
- Added independent locator tracking service for drop detection, network switching, and runtime highlight state.
- Added EMI drag-hover feedback and ghost-slot updates for Network Locator filter slots.
- Added adjacent machine activity detection for machine workflows where the provider is idle while the machine is processing.

### Changed

- Reworked provider tracking into clearer refresh, quick scan, full scan, and send phases.
- Provider highlighting is no longer enabled by default through config; runtime controls are used for temporary highlighting.
- Refined active, slow, and blocked status timing so recovered machine crafts do not stay blocked.
- Refined Network Locator screen behavior and virtual filter slot handling.
- Reworked configuration layout into `status`, `scan`, `appearance`, and `diagnostics`.
- Updated English and Chinese localization for configuration groups and overlay text.
- Updated mod description to match the new rendering and locator behavior.

### Fixed

- Fixed output icons overlapping floating status badges.
- Fixed overly thick outlines drawing internal lines for layered block shapes.
- Fixed provider highlights flickering or disappearing while AE CPU requests were still active.
- Fixed tentative provider entries being repeatedly cleared and recreated.
- Fixed blocked machine crafts staying blocked after an adjacent machine resumed processing.
- Fixed missing configuration screen localization keys.

## [0.1.0] - 2025-07-01

### Added

- Network Locator item for scanning AE networks to find blocks matching filter items.
- Locator GUI with 9 filter slots and EMI drag-and-drop support.
- Runtime highlight mode with a toggle button in the locator screen.
- Crafting recipe for Network Locator.
- `/crafttracker` command with `toggle`, `on`, `off`, and `status`.

### Fixed

- Drop detection now clears highlights immediately when the locator is dropped.
- Network switch detection now clears old highlights and scans the new network immediately.
- Locator scans work independently of the general highlight config toggle.
- Dropping the locator no longer permanently disables player highlights.
- Recipe type corrected from `minecraft:shaped` to `minecraft:crafting_shaped`.

### Changed

- Default highlight config changed to `false`.
- Removed production debug logging from the initial locator implementation.
