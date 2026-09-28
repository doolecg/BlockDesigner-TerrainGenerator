# Terrain Generator 0.2.1

Kept up to date with BlockDesigner 0.4.27: built and tested against its plugin API. Nothing changes in how it works.

**Needs BlockDesigner 0.4.24 or later** (plugin API 6). Older BlockDesigners keep 0.1.3 until BlockDesigner itself is updated.

## Changed
- Built against the BlockDesigner 0.4.27 plugin API.

---

# Terrain Generator 0.2.0

The Terrain page is easier to read and fits a narrow panel, and the sliders show sizes in blocks.

**Needs BlockDesigner 0.4.24 or later** (plugin API 6). Older BlockDesigners keep 0.1.3 until BlockDesigner itself is updated.

## New
- **Sizes in blocks:** the size sliders read "≈ 1,490 blocks" instead of a noise frequency, and heights read in blocks.
- **A status dot** on the Terrain page's button while it bakes, and when something went wrong.
- **The Terrain region tool** can be given a key in Settings › Keybinds.

## Changed
- **Page layout:** Generator, Map, Shape, Region to bake and Voxels (folded away), with **Bake to new layer**, its progress and any error at the bottom. Open and save a `.tgen.json` with the buttons next to Generator; the shuffle button rolls a seed.
- **The map fits the page** (up to 360 pixels wide) instead of a fixed size.
- **The region's fields** fit a narrow panel: centre and size in pairs.
- **Open the Terrain panel** on the Terrain preview's right-click menu opens the page with BlockDesigner's own navigation.

## Fixed
- **Typing a seed** changes the terrain when you press Enter or leave the field, as one undo step, instead of one step (and a redraw) per letter.

---

# Terrain Generator 0.1.3

Kept up to date with BlockDesigner 0.4.23: built and tested against its plugin API. Nothing changes in how it works.

**Needs BlockDesigner 0.4.17 or later** (plugin API 3). BlockDesigner 0.4.16 and later update to it by themselves.

## Changed
- Built against the BlockDesigner 0.4.23 plugin API.

---

# Terrain Generator 0.1.2

Kept up to date with BlockDesigner 0.4.22: built and tested against its plugin API. Nothing changes in how it works.

**Needs BlockDesigner 0.4.17 or later** (plugin API 3). BlockDesigner 0.4.16 and later update to it by themselves.

## Changed
- Built against the BlockDesigner 0.4.22 plugin API.

---

# Terrain Generator 0.1.1

Kept up to date with BlockDesigner 0.4.18: built and tested against its plugin API. Nothing changes in how it works.

**Needs BlockDesigner 0.4.17 or later** (plugin API 3). BlockDesigner 0.4.16 and later update to it by themselves.

## Changed
- Built against the BlockDesigner 0.4.18 plugin API.
- README in the same format as BlockDesigner's.

---

# Terrain Generator 0.1.0

The first version: generate Minecraft-style terrain, see it as a map, and bake a region into a layer.

**Needs BlockDesigner 0.4.17 or later.** Install: download `terrain-generator-0.1.0.jar` below, then in BlockDesigner open **Plugins › Manage plugins… › Install…** and pick it.

## New
- **Terrain page** in the plugin's tab: pick a generator, type or roll a seed, and tune its sliders while a top-down map redraws.
- **The map on the ground** in the 3D view, with the bake region drawn as a box and its chunks marked.
- **Four generators:** Vanilla-like, Islands, Mesas and Rolling hills.
- **Terrain region tool:** drag a rectangle in the view to set the region, click to move it, and use the wheel while dragging to set how deep it goes. You can also click the map, type the numbers, or use the selection.
- **Bake to new layer:** the region becomes a layer of ordinary blocks, as one undo step. Choose how deep it goes and whether to keep the sea.
- **Voxels:** bake at 2, 4 or 8 blocks per voxel, expanded to full size or as a small model.
- **Saved with the project:** the generator, seed and region live in the Terrain preview (Layers panel, badge TERRAIN), so they save with the project and every change can be undone.
- **`/terrain` commands:** `seed`, `preset`, `region` and `bake`.
- **Open… and Save…** generators as `.tgen.json` files.
- **The same seed gives the same terrain** every time, on any machine.
