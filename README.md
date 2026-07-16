# AE Crafting Tracker

[Chinese](README_ZH.md)

AE Crafting Tracker is a Minecraft NeoForge 1.21.1 mod for visualizing AE2 crafting activity in-world. It highlights pattern providers, shows compact crafting status badges, previews requested outputs, and provides a Network Locator tool for finding AE network blocks from filter items.

## Features

### Provider Highlights

- Shape-aware outlines that follow the visible block shape instead of drawing heavy internal lines.
- Floating status badges with output previews, placed to avoid overlapping item icons.
- Status colors for active, slow, and blocked providers.
- Output tracking for AE2 item, fluid, and supported chemical outputs.
- Recovery detection for machine workflows where the provider is idle but an adjacent machine is actively processing.
- Runtime highlight mode controlled from the AE crafting status screen or Network Locator workflow.

### Network Locator

- Craftable locator item for scanning a bound AE network.
- Nine ghost filter slots for target items.
- EMI drag-and-drop support for the virtual filter slots.
- Highlight feedback while dragging EMI stacks over compatible filter slots.
- Independent locator tracking that continues even when normal provider highlighting is not enabled.
- Immediate highlight clearing when the locator is dropped, unbound, or switched to another network.

### EMI and EmiLink

- Built-in EMI compatibility for the Network Locator screen.
- Compatible with EmiLink quick-fill behavior for filling locator ghost slots from EMI with the configured quick-fill key.

### Configuration

Configuration is split into focused groups:

- `status`: slow and blocked thresholds.
- `scan`: scan radius and full scan interval.
- `appearance.colors`: hex RGB colors such as `#55FF55`.
- `appearance.opacity`: badge and outline opacity.
- `diagnostics`: optional tracking debug logs.

Provider highlighting is not enabled by default through configuration. Use the runtime controls when you want temporary in-world feedback.

## Status Colors

- Active: the provider or related craft request is progressing.
- Slow: the request has taken longer than the configured slow threshold.
- Blocked: the request has exceeded the blocked threshold, the provider is locked, or the tracker detects a hard delivery problem.

For machine-based crafting, a provider may remain idle while the adjacent machine processes the input. The tracker checks common machine state properties such as `active`, `lit`, `working`, `running`, `crafting`, and `processing` to avoid keeping a recovered craft marked as blocked.

## Recipe

```
PEP
ENE
PEP
```

- `P`: AE2 Calculation Processor
- `E`: Ender Eye
- `N`: AE2 Network Tool

## Commands

- `/crafttracker toggle`
- `/crafttracker on`
- `/crafttracker off`
- `/crafttracker status`

## Dependencies

- Minecraft 1.21.1
- NeoForge 21.1+
- Applied Energistics 2
- EMI is optional, but recommended for locator filter workflows.

## Build

```bash
./gradlew build
```

On Windows:

```bat
gradlew.bat build
```

Java 21 is required.

## Diagnostics

Enable `diagnostics.debugTracking` in the config to write detailed provider state transitions to the log. Useful phases include:

- `quick.create_tentative`
- `refresh.tentative_promote`
- `refresh.idle_cpu_busy`
- `refresh.stuck_clear_adjacent_active`
- `refresh.recover_busy`
- `entry.remove_missed`

Diagnostics are disabled by default and are intended for testing or bug reports.

## License

GNU LGPL 3.0

### Asset Credits

The Network Locator item texture is based on AE2's `network_tool` texture. Copyright belongs to Applied Energistics 2.
