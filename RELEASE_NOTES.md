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
