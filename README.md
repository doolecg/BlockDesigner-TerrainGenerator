<h1 align="center">Terrain Generator</h1>

<p align="center">
  Minecraft-style terrain for BlockDesigner: pick a generator, type a seed, tune it while a map redraws,<br>
  and bake a region into a new layer of ordinary, editable blocks.
</p>

<p align="center">
  <a href="https://github.com/doolecg/BlockDesigner"><img alt="BlockDesigner plugin API 3" src="https://img.shields.io/badge/BlockDesigner-plugin%20API%203-46C46E"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-blue"></a>
</p>

---

**Terrain Generator** is a plugin for [BlockDesigner](https://github.com/doolecg/BlockDesigner), the Windows editor for
Minecraft builds. It is released on its own, separately from the app. It needs **BlockDesigner 0.4.17 or later**.

**Contents:** [Install](#install) · [Using it](#using-it) · [Generators](#generators) · [Building from source](#building-from-source) · [Project layout](#project-layout)

## Install

1. Download `terrain-generator-<version>.jar` from the [releases page](https://github.com/doolecg/BlockDesigner-TerrainGenerator/releases/latest).
2. In BlockDesigner open **Plugins (puzzle icon) › Manage plugins… › Install…** and pick the jar.

It appears as the **Terrain Generator** tab on the right; its **Terrain** page is where you work.

## Using it

1. **Pick a generator** (Vanilla-like, Islands, Mesas or Rolling hills), or **Open…** a `.tgen.json` file.
2. **Type a seed**, a number or any text (text is hashed the way Minecraft hashes it), or roll one with the dice.
3. **Tune the sliders** under Shape. The map redraws as you go, in the panel and lying on the ground in the 3D view,
   with the bake region drawn as a box and its chunks marked.
4. **Set the region:** click the map to centre it there (the wheel zooms), type its centre and size, **Use
   selection**, or drag it out in the view with the **Terrain region** tool (a click moves it; the wheel while dragging
   sets how deep it goes). **Down to Y** sets how deep to bake: the world goes down to −64, and most of that is plain
   stone.
5. **Voxels** (optional): bake at 2, 4 or 8 blocks per voxel, either expanded to full size (blocky terrain) or as a
   small model with one block per voxel.
6. **Bake to new layer.** The region becomes a new layer of normal blocks in one undo step. Build on it, edit it or
   export it like anything else.

Everything you set is kept in the project as the **Terrain preview** (in the Layers panel, badge TERRAIN): it is saved
with the project, every change can be undone, and hiding it hides the map and the region box in the view. Right-click
it to bake or roll a new seed. **Save…** writes the generator with your slider settings as a `.tgen.json` file.

From the command line (T or /):

| Command | What it does |
|---|---|
| `/terrain seed <number or text>` | Sets the seed (a random one without a value) |
| `/terrain preset <id>` | Picks a generator: `vanilla-like`, `islands`, `mesas`, `rolling-hills` |
| `/terrain region <x> <z> <width> [depth]` | Sets the region; `/terrain region` alone takes the //pos1 //pos2 region |
| `/terrain bake [width] [depth]` | Bakes the region (resized first when sizes are given) |

## Generators

A generator is a small graph of noise nodes (Perlin, simplex, value and Worley noise, fractal layering, domain
warp, curves, terraces and maths) with surface rules (grass on top, dirt below, sand at the shore, and so on) and
scatter (trees and boulders). The same seed always gives the same blocks, on any machine and in any order. The
format is plain JSON; see [docs/plan.md](docs/plan.md) for where this is going (a live 3D preview, a node editor and
exact vanilla terrain from your own Minecraft).

| Generator | What it makes |
|---|---|
| **Vanilla-like** | Continents and oceans, eroded plains, ridged mountains with snowy peaks, overhangs, beaches, oak and birch trees. It looks like Minecraft but is not a copy of a real seed. |
| **Islands** | Wavy islands in a wide sea, with beaches, rocky cliffs, trees and boulders. |
| **Mesas** | Stepped badlands plateaus with banded terracotta cliffs over red sand. |
| **Rolling hills** | Flat land with gentle hills. The simplest place to start. |

## Building from source

You need Windows and a JDK 26 (Temurin 26 is what BlockDesigner uses; set `org.gradle.java.home` in
`gradle.properties` to yours). Then:

```
./gradlew jar      # build/libs/terrain-generator-<version>.jar
./gradlew check    # all tests, including the determinism tests run again with -Xint
```

The plugin compiles against the BlockDesigner plugin API jars in [`libs/`](libs) (from BlockDesigner 0.4.17). The app
provides them, and JavaFX, at runtime, so they are never bundled into the plugin.

## Project layout

| Path | What it is |
|---|---|
| `runtime/` | The generator: noises, the node graph and its JSON format, the compiler, the chunk filler, surface rules, scatter and the built-in presets. Plain Java 17 with no dependencies. |
| `plugin/` | The BlockDesigner plugin: the Terrain panel, the Terrain preview object, the region tool, the `/terrain` command, the map and baking. The runtime is built into its jar. |
| `libs/` | The BlockDesigner plugin API jars it compiles against |
| `docs/plan.md` | The design plan and its phases |

## License

[MIT](LICENSE).
