# Changelog

[**中文版**](CHANGELOG_ZH.md)

## [0.1.0] - 2025-07-01

### Added
- Network Locator item: scan AE networks to find blocks matching filter items
- Locator GUI with filter slots (9 slots) and EMI drag-drop support
- Runtime highlight mode with toggle button in locator screen
- Crafting recipe for Network Locator
- `/crafttracker` command (toggle/on/off/status)

### Fixed
- Drop detection: highlights now clear immediately when locator is dropped
- Network switch: old highlights clear and new network scans instantly
- Locator scans work independently of the general highlight config toggle
- Drop no longer permanently disables player highlights
- Recipe type corrected from `minecraft:shaped` to `minecraft:crafting_shaped`

### Changed
- Default highlight config changed to `false`
- Removed debug logging for production use
