<div align="center">
  <!-- Logo and Title -->
  <img src="https://raw.githubusercontent.com/or4acle/or4acles-funny-5b5t-duper/refs/heads/main/assets/logo.png" alt="logo" width="30%"/>
  <h1>or4acle's Funny 5b5t Auto Duper</h1>
  <p>super cool skidded duper & chest storer for 5b5t</p>

  <img src="https://img.shields.io/badge/Meteor%20Addon-6f1ab1?logo=meteor&logoColor=white" alt="Meteor Addon"/>
  <img src="https://img.shields.io/badge/Minecraft-1.21.1-brightgreen" alt="Minecraft Version"/>
  <img src="https://img.shields.io/badge/Fabric-0.16.9-blue" alt="Fabric"/>
  <img src="https://img.shields.io/badge/5b5t-Working-orange" alt="5b5t Working"/>

## Installation

1. Download the latest `.jar` release of the addon.
2. Drop the `.jar` into your `.minecraft/mods` directory.
3. Launch Minecraft with **Fabric Loader** and **Meteor Client**.
4. Open the Meteor GUI (press `Right Shift` by default) and look for the **5b5t** category.

## Dependencies

- **Minecraft**: `1.21.1`
- **Fabric Loader**: `0.16.0+`
- **Meteor Client**: `0.5.8+` (1.21.1)

## Features

- **Multi-Item Duplication**: Select the exact items from your inventory that you want to dupe directly inside the module settings.
- **Flexible Target Modes**:
  - `TargetItems`: Only dupes items selected in your custom list.
  - `AllInventory`: Automatically dupes eligible items found in your inventory (safely ignores wood planks & crafting materials).
  - `HeldItem`: Dupes whatever item you are holding in your main hand (classic mode).
- **Auto Chest Storer (AFK Safe)**: When your inventory gets full, the mod automatically searches for nearby chests, trapped chests, barrels, or shulker boxes, opens them, and deposits the excess duped items while leaving a few stacks in your inventory so you can keep duping forever.
- **Configurable Delay & Rotation**:
  - Rotation modes: `Silent`, `Client`, or `None`.
  - Adjustable tick delay between cycles to ensure items are collected back from the ground.
- **Crafting Resource Protection**: Never drops or deposits planks or wood logs needed to maintain the crafting desync loop.

## How to Use

1. Make sure you have plenty of **wood planks** in your inventory (needed for the stick / crafting table recipe desync).
2. If using **Auto Chest**, place a chest, barrel, or shulker box near your standing position (search radius is customizable from 1 to 6 blocks).
3. Open the `Auto 5b5t Dupe` module settings in the Meteor GUI:
   - Under **Mode**, pick `TargetItems` and add the items you want to dupe (e.g. Totems, Shulkers, God Apples, End Crystals).
   - Turn on **Auto Repeat** for hands-free continuous duping.
   - Turn on **Auto Store** if you want it to offload excess stacks into nearby containers when full.
   - Adjust **Keep Stacks** (how many stacks to keep on your character to continue duping, default: 1).
4. Turn on the module (or bind it to a hotkey). The mod handles hotbar selection, dropping, crafting desync packets, and chest dumping automatically!

## Settings

| Setting | Default | Description |
|---|---|---|
| `mode` | `TargetItems` | Choose between `TargetItems` (custom list), `AllInventory`, or `HeldItem`. |
| `items` | `[empty]` | List of items the module will automatically look for and duplicate. |
| `recipe` | `Stick` | Recipe used to trigger the desync exploit (`Stick` or `CraftingTable`). |
| `auto-repeat` | `true` | Runs dupe cycles automatically in a continuous loop. |
| `delay` | `4` ticks | Delay in ticks between dupe cycles (allows picking items up). |
| `drop-all` | `false` | Drops the entire stack instead of one item at a time. |
| `rotation-mode` | `Silent` | How to look down (`Silent` packets, `Client` view, or `None`). |
| `auto-store` | `true` | Automatically deposits duped items into a nearby chest when inventory is full. |
| `chest-range` | `4` | Search radius in blocks for chests/barrels/shulkers. |
| `keep-stacks` | `1` | Number of stacks to keep in your inventory so you can continue duping. |
| `empty-slots-threshold` | `2` | Triggers container storage when remaining empty slots reach or drop below this amount. |

## Credits

- **or4acle** - multi item dupe and chest sorter stuff idsfojsjidf
- **StorageESP** - Initial addon base
- **Meteor Development** - [Meteor Client](https://meteorclient.com)
- **[BepHax](https://github.com/dekrom/BepHaxAddon/)** by [dekrom](https://github.com/dekrom) and **[AutoBookshelf](https://github.com/oehrasa/Oehrasa-Bookies-Addon)** by [oehrasa](https://github.com/oehrasa) - this readme :)))))))))))))))
