# AE Crafting Tracker

[**中文版**](README_ZH.md)

A Minecraft NeoForge 1.21.1 mod that provides real-time visual feedback for AE2 crafting status.

## Features

### Pattern Provider Highlighting
- Automatically detects active Pattern Providers in your AE2 network
- Color-coded highlights:
  - 🟢 **Green** — Provider is actively crafting
  - 🟡 **Yellow** — Provider is stalled (busy but no progress)
  - 🔴 **Red** — Provider is stuck (locked, output full, or missing ingredients)
- Shows output item icons above highlighted providers
- Configurable highlight colors and opacity

### Network Locator
- Craftable item that scans AE networks for blocks matching your filter items
- 9 filter slots in the GUI — drag items from EMI or manually place
- Bind the locator by shift-clicking on an AE network controller/access point
- Highlights matching blocks on the network with distance info
- Works across dimensions (scan only active when in the same dimension as the bound position)
- Instant network switch detection: clear old highlights and scan new network immediately
- Instant drop detection: all highlights clear when item is dropped

### Runtime Mode
- Enable runtime highlighting via the button in the locator screen
- Highlights persist even when the locator GUI is closed
- Toggle on/off freely without affecting your config settings

## Dependencies

- Minecraft 1.21.1
- NeoForge 21.1+
- Applied Energistics 2 (AE2) — runtime

## License

GNU LGPL 3.0

### Asset Credits
- The Network Locator item texture is based on AE2's `network_tool` texture, copyright © Applied Energistics 2.
