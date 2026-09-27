# Terrain Generator: plan

**0.2.0 (UI overhaul, API 6, needs BlockDesigner 0.4.24):** the Terrain page is built with BlockDesigner's UI kit
(Generator, Map scaled to the page, Shape with sizes shown in blocks via `ShapeFormat`, Region to bake, a folded Voxels
section, and Bake with progress and errors at the bottom); the seed commits on Enter or focus loss (one undo step);
the page's status dot shows baking and failures; the preview object's menu opens the page with `ctx.showPanel`.

Status (2026-09-26): **Phases 0, 1 and 2 done.** This repository (`BlockDesigner-TerrainGenerator`) holds the
plugin, released on its own; plugin code never goes into BlockDesigner's main repository. What exists and what changed
from the plan below:

- **Modules:** `runtime` (Java 17, no dependencies) and `plugin` (builds the runtime into its jar). `mcdata` comes with
  Phase 7. The portable runtime release and other Java games are out of scope (section 12).
- **Runtime (Phase 1):** Perlin, simplex, value and Worley noise with fBm / ridged / billow; domain warp; maths,
  remap, clamp, lerp, spline and terrace nodes; `height_to_density`; the `.tgen.json` reader and writer (format 1) with
  its own JSON parser; the compiler (cycles, missing inputs, unknown types and y-dependent heights are errors); a chunk
  filler with 4 × 8 × 4 cell interpolation and per-column caching of flat values; surface rules (depth, above_water,
  y_above / y_below / y_between, steep, noise, not / any / all); water at sea level; scatter (trees, boulders) on a
  jittered grid that is identical across chunk borders. Presets: Vanilla-like, Islands, Mesas, Rolling hills. Golden
  chunk hashes pass under the JIT and `-Xint`; filling chunks in any order on 8 threads gives the same result. Speed on
  this machine: about 1.5 ms per chunk for height-map presets, about 3 ms for Vanilla-like (3D).
- **Plugin (Phase 2), declares API 3:** the Terrain panel (generator picker, Open / Save `.tgen.json`, seed with dice,
  one slider per preset "control", a map with hillshade and water depth: click to move the region, wheel to zoom);
  the **Terrain preview** scene object (badge TERRAIN) that holds the whole state (`TerrainState`) in the project,
  so it saves and every change is an undo step (changes within 0.5 s merge), and draws the map on the ground at sea
  level (`Drawing.image`, behind blocks) plus the region box and chunk grid (`Drawing.line`); the **Terrain region**
  tool; `/terrain seed|preset|region|bake`; bake to a new layer (off the FX thread, one undo step) with voxel scale 1,
  2, 4 or 8 as an expanded or model bake.
- **Differences from the plan:** the preview object stays where the region is whatever its pose (the Move tool
  doesn't move the region; the map, the tool, the fields and the command do), because keeping pose and region in step
  through undo would add undo steps of its own. The map is one 280 × 280 image at 1 to 32 blocks per pixel rather than
  tiles. UI snapshot tests aren't there (they need BlockDesigner's own test harness); the panel was checked by hand in
  the app.

The original plan follows. The host API numbering in section 5 predates the API 3, 4 and 5 releases; the terrain
needs listed there (A–K) are still to come, as a later API version.

The feature, in the user's words: *"a Terrain Generator, this lets you generate minecraft terrain as default but also
a playground for generation ideas, voxel scales. Noises. Layers and such. Also a preview for infinite generation. see a
small chunk of generation that will go on once in minecraft or a different game in java."*

It ships **as a plugin** (`plugins/terrain-generator`), released on its own with its own version, like Reference
Planes. BlockDesigner's side is limited to the plugin API extensions in section 5.

---

## 1. Goals and non-goals

### Goals

1. **Minecraft terrain by default.** Pick the "Vanilla Overworld" preset, type a seed, pick a region (e.g. 256 × 256
   around 0,0) and get terrain that looks like Minecraft's, with continents, erosion, peaks and valleys, rivers, oceans
   at sea level 63, biome surfaces, and optionally noise caves and aquifers. It lands in a new BlockDesigner layer made
   of normal, editable blocks.
2. **A playground for generation ideas.** Build a generator from noises (Perlin, Simplex, OpenSimplex2, Value,
   Worley), fractal modifiers (fBm, ridged, billow), domain warp, terraces, math, remaps, splines and clamps. Arrange it
   as layers: height layers, 3D density layers, material and surface layers, and feature scatter such as trees and
   rocks. Every change previews live.
3. **Voxel scale.** Generate at 1, 2, 4 or 8 blocks per voxel, or at any unit size for other voxel games, and view and
   bake at that scale.
4. **Infinite-generation preview.** A streamed, chunked view around the camera generates as you pan or fly, with
   lower detail farther away. It shows what the generator would produce endlessly in Minecraft or another game. It has
   two modes: a small full-detail chunk area, and a wide, low-detail overview (a height and biome map several
   kilometres across).
5. **Outputs.**
   - Bake into layers: plain blocks, undoable, saved in the project.
   - Export a Minecraft worldgen data pack (noise settings, density functions, biomes, surface rules, world preset)
     for everything that maps onto vanilla's data-driven system, with a clear report of what doesn't.
   - A portable Java runtime: a small jar plus the JSON generator format. It is deterministic for a given seed and
     usable from any Java game (a Fabric or NeoForge mod, libGDX, jMonkeyEngine, a custom engine).
6. **Deterministic everywhere.** The same generator file and seed give the same blocks in the plugin, in the runtime
   jar and across JVMs and machines.

### Non-goals (for this plan)

- **Block-for-block parity with the whole vanilla world.** Terrain *shape* (the noise phase) and *surface rules* can
  match vanilla, and the plan aims for that on target versions. Vanilla *features* (trees, ores, flowers, lakes, geodes,
  dripstone), *structures* (villages, strongholds), legacy carvers, light and mob spawning are out of scope. The
  generator's own scatter layer stands in for features.
- The Nether and the End (possible later; the evaluator would support them, but they get no presets or tests).
- Editing a running Minecraft world, or talking to a game server.
- A general-purpose visual scripting language. The node set is fixed and domain-specific.
- Shipping Minecraft data. Like the rest of BlockDesigner, the vanilla preset reads Mojang's worldgen JSON from the
  user's own Minecraft install at runtime and ships none of it (see section 7.2).
- GPU compute noise. Everything runs on CPU threads. A GPU path could come later but is not planned.

---

## 2. Plugin vs built-in: decided

The user decided on **a plugin** with a separate release. This fits the codebase:

- The plugin loader (`PluginManager`) gives each jar its own `URLClassLoader`, so the plugin can shade its own
  runtime library without clashing with anything.
- API 3 (being added for Reference Planes in the `worktree-agent-a7e5d6f09553106b3` worktree) already covers most
  of the "things in the scene that aren't layers" problem.
- The generator core is a plain Java library with no BlockDesigner dependency. The plugin and the portable runtime
  are then the same code, which answers "a different game in java" for free.

The plugin's parent class loader is the app's, so a plugin *could* reach into `render` or `app` internals. **It must
not.** Everything it needs goes through the plugin API, and section 5 lists the additions. That keeps the plugin
working across BlockDesigner versions and keeps the API honest.

---

## 3. User-facing UX

### 3.1 Where it lives

| Place | What |
|---|---|
| Right-hand tab **"Terrain"** (a `PluginPanel`) | The main panel: generator picker (presets and saved generators), seed, the **layer stack**, parameters of the selected layer or node, preview controls, and Bake / Export buttons. |
| **Graph editor** window (a plugin-owned JavaFX `Stage`, non-modal) | The node editor, for power users. It opens from the panel ("Edit as graph…"). The stack view and the graph are two views of one document. |
| **Viewport** | The preview is a scene object **"Terrain preview"** (badge `TERRAIN`) in the Layers panel. It can be moved, hidden and locked, and the Move tool moves its origin. It draws the 2D map, the 3D chunk preview and the streamed LOD rings. |
| **Layers panel** | Baked layers appear as normal layers named e.g. `Terrain · Vanilla · seed 1234 · 256×256`. |
| **Tool dock** | A **"Terrain region"** plugin tool: drag a box on the ground to set the bake or preview region; the wheel changes its height. |
| **Plugins menu** | Terrain › New generator, Open generator…, Bake region, Export data pack…, Export runtime bundle…, Show graph editor. |
| **Commands** | `/terrain seed <n>`, `/terrain bake [w] [d]`, `/terrain preset <id>`, `/terrain export`. |
| **Export window (Ctrl+E)** | A "Terrain data pack" card and a "Generator bundle (.zip: runtime jar + JSON)" card. These need small API changes; see 5.3 (E). |

### 3.2 The Terrain panel (stack view)

```
+- Terrain ---------------------------------------- x -+
| Generator [ Vanilla Overworld (26.1)        v ] [...]|
| Seed      [ 1234567890          ] [dice] [lock]       |
| Scale     ( 1 )( 2 )( 4 )( 8 )  blocks per voxel      |
|------------------------------------------------------|
| LAYERS                                   [+ Add v]    |
|  [eye] v Shape  (density, 3D)         [vanilla ok]    |
|         continentalness . erosion . ridges            |
|  [eye] v Caves  (density, subtract)   [vanilla ok]    |
|  [eye] v Water  (fluid, sea level 63) [vanilla ok]    |
|  [eye] v Surface (material rules)     [vanilla ok]    |
|  [eye] > Trees  (scatter)             [approx]        |
|  [eye] > Boulders (scatter)           [no export]     |
|------------------------------------------------------|
| SELECTED: Shape > Erosion noise                       |
|   Type        [ Normal noise (vanilla)  v ]           |
|   First octave [ -9 ]   Amplitudes [1 1 0 1 1]        |
|   XZ scale    [====o-----] 0.25                       |
|   Salt        [ minecraft:erosion ]                   |
|   [Edit as graph...]                                  |
|------------------------------------------------------|
| PREVIEW   (o) Map 2D   ( ) Chunks 3D   ( ) Infinite   |
|   Radius [ 8 ] chunks   LOD rings [ 3 ]   [x] Follow  |
|   Colour by [ Biome v ]    Slice Y [ off ]            |
|------------------------------------------------------|
| Region 256 x 384 x 256 at (0, -64, 0)  [Pick region]  |
| [ Bake to new layer ]  [ Re-bake layer v ]  [Export v]|
| 47 chunks queued . 12 ms/chunk . 180 MB               |
+------------------------------------------------------+
```

- Layers here are **generator layers**, not BlockDesigner layers. They are an ordered stack of generation stages. Each
  one is a small subgraph with a friendly face. The "[vanilla ok] / [approx] / [no export]" chip shows whether that
  layer exports to a data pack (section 7.4).
- Parameters use the plugin API's `Options` where they fit (numbers, toggles, choices, block lists). Spline and curve
  parameters get a small custom curve editor.
- Any change re-runs the preview after 150 ms of quiet, like `TransformDialog`.

### 3.3 The graph editor

```
+- Terrain graph: My Islands ----------------------------------------------+
| [Nodes v] [Auto-layout] [Fit] | Output: (Density v) | Preview node [x]   |
|                                                                         |
|  [Pos X,Z]--+                                                            |
|             +->[Domain warp]--->[OpenSimplex2 fBm]--+                    |
|  [Worley F1]+    amp 40           oct 5, freq 1/512  |                    |
|                                                      +->[Add]-->[Spline]-+-> Height
|  [Ridged Perlin]-->[Remap -1..1 -> 0..1]-->[x 0.4]---+          |        |
|                                                                 v        |
|                                                     [Terraces 8 steps]  |
|                                                                         |
| +- Node preview ------+   Selected: Spline (height)                      |
| |  [ 128x128 greyscale|   points: (-1,-40) (-0.2,62) (0.1,70) (1,160)    |
| |    of this node   ] |   [curve editor..................]              |
| +---------------------+                                                  |
+-------------------------------------------------------------------------+
```

- Each node can show a thumbnail (a 128² slice at the camera's X/Z or at a chosen Y). "Preview node" routes the
  selected node to the viewport, so you can see a single noise as terrain.
- The stack view and the graph are one document. A stack layer is a named group node in the graph, and graph-only
  edits appear in the stack as "Custom (graph)".

### 3.4 Viewport preview modes

```
 Map 2D (overview, up to ~8 km)          Chunks 3D (full detail)            Infinite (streamed + LOD)
+----------------------------+        +----------------------------+     +----------------------------+
| ~~~~ ocean   ##### mountain|        |      /\    ___             |     |  . . lod3 . . . . . . . .  |
| ~~~ .... beach  #### ##    |        |   __/  \__/   \__  (real   |     | . . +--------------+ . .   |
|  ~~ .... plains ### ####   |        |  /  block meshes  \  blocks|     | . . | lod1  +----+  | . .   |
|  ~ ..... forest ##  #####  |        | /__ region box ____\ )     |     | . . |       |lod0|  | . .   |
|  (one textured quad on the |        |  outlined in white          |     | . . |       |cam |  | . .   |
|   ground, heights shaded)  |        +----------------------------+     | . . +--------------+ . .   |
+----------------------------+                                            +----------------------------+
```

- **Map 2D:** one image quad on the ground plane (`Drawing.image`, already in API 3). Each pixel is the surface block's
  average colour (`BlockCatalog.averageColor`), biome colour or a height ramp, with hillshading. Resolution is 1 pixel
  per 1 to 16 blocks, depending on zoom. Pan and zoom follow the camera. This mode works with **API 3 only**.
- **Chunks 3D:** a fixed region (default 8 × 8 chunks), meshed with real block textures, regenerated live as parameters
  change. You can fly through it.
- **Infinite:** chunks stream around the camera (or the orbit target when not flying). LOD0 is real blocks. Outer
  rings are coloured heightfield tiles at 2×, 4×, 8× and 16× downsampling. Fog hides the edge.
- Overlays (lines, already in API 3): a chunk grid, the bake region box, sea level, and region-file (512 × 512)
  borders.
- The ghost block preview from `ToolContext.Preview` is **not** used: it's per-block and would not scale.

### 3.5 Workflows

1. **"Just give me Minecraft terrain."** Open the Terrain tab, keep the preset "Vanilla Overworld", type a seed, pick
   Map 2D, and fly around to find a nice spot. Drag a region with the Terrain region tool, then **Bake to new layer**.
   The result is one undo step and a new layer that can be built on.
2. **Playground.** Pick "Empty (heightmap)" or "Islands", add layers (noise, then warp, then terraces), tweak sliders
   while watching Chunks 3D, and switch to Infinite to judge large-scale variety. Save the generator (`.tgen.json` in
   the project, and optionally a file).
3. **Ship to Minecraft.** Export › Data pack. A compatibility report lists each layer as exact, approximated (and
   how) or not exported. Save as a zip or install into a world, like the structure data pack dialog. Then create a new
   world with the "BlockDesigner: <name>" world preset.
4. **Ship to another Java game.** Export › Generator bundle writes `terragen-runtime-<v>.jar`, `<name>.tgen.json` and
   a README with a ten-line usage snippet. Optional: a Fabric mod template (Phase 9).
5. **Voxel scale.** Set the scale to 4 and the preview shows 4-block voxels. Baking asks: "Voxels as single blocks
   (a 1:4 model)" or "Expand each voxel to 4×4×4 blocks".

---

## 4. Architecture

### 4.1 Modules

```
plugins/terrain-generator/
  runtime/        io.blockdesigner.terragen            pure Java, NO dependencies (not even core), release 17
                  - noise primitives, graph model, JSON reader/writer, compiler, evaluator,
                    chunk filler, surface rules, scatter, vanilla density-function interpreter
  mcdata/         io.blockdesigner.terragen.mc         depends on runtime; reads vanilla worldgen JSON,
                  (plain Java, release 17)              writes data packs; McVersion-like version table (own copy)
  plugin/         io.blockdesigner.terragen.plugin     compileOnly :plugin-api (+ JavaFX compileOnly);
                                                        shades runtime + mcdata into the plugin jar
```

- `settings.gradle.kts`: `include("plugins:terrain-generator:runtime", "…:mcdata", "…:plugin")`. This follows the
  Reference Planes convention of "plugins released on their own, each with its own version".
- **runtime targets Java 17 bytecode** (`options.release = 17`), not the repo's 25, so Minecraft 1.20.5+ mods (Java
  21) and older engines can use it. It has no Jackson (the app uses Jackson, but the runtime must stay
  dependency-free), so it has its own small JSON parser (about 300 lines; the format is simple).
- The plugin jar is expected at about 400–600 KB. The runtime jar alone should stay under about 250 KB.
- It does **not** reuse `worldgen/DatapackExporter`. That exporter is structure-focused and lives in the app's
  modules, which plugins shouldn't depend on. Terrain data pack writing is new code in `mcdata`. The only overlap is
  pack.mcmeta and version formats, and `mcdata` keeps a copy of the few `McVersion` rules it needs (folder names,
  `min_format`/`max_format`). The plugin can take the target version from `ctx.targetVersion()`.

### 4.2 Key runtime types (sketch)

```java
// ---- the document ----
public record GeneratorSpec(int formatVersion, String name, WorldShape world, Graph graph,
                            List<SurfaceRule> surface, List<ScatterLayer> scatter, BiomeSetup biomes,
                            Map<String, String> meta) { }
public record WorldShape(int minY, int height, int seaLevel, String defaultBlock, String defaultFluid,
                         int voxelSize, int cellWidth, int cellHeight) { }   // cell = vanilla's 4x8 interpolation cell
public final class Graph { Map<String, Node> nodes; Map<Output, String> outputs; }  // Output: DENSITY, HEIGHT, BIOME_PARAMS...
public record Node(String id, String type, Map<String, Object> params, Map<String, String> inputs, String salt) { }

// ---- compiled, immutable, thread-safe ----
public final class Generator {
    public static Generator compile(GeneratorSpec spec, long seed);          // validates, resolves seeds, builds evaluators
    public double density(int x, int y, int z);
    public int surfaceHeight(int x, int z);                                  // fast path when the graph is heightmap-shaped
    public void fillChunk(int cx, int cz, ChunkSink sink, Lod lod);          // full blocks or a heightfield tile
    public BiomeId biome(int x, int y, int z);
    public long fingerprint();                                               // hash(spec, seed): cache key
}
public interface ChunkSink { void set(int lx, int y, int lz, int paletteIndex); String[] palette(); }

// ---- evaluation ----
interface DensityFn {                           // compiled node
    double compute(Ctx c);                      // single point
    void fill(double[] out, CellCtx cells);     // batched over a cell grid (the fast path)
}
```

- **Compilation.** The graph is validated (types, cycles, missing inputs) and turned into a tree of `DensityFn`
  objects. Constant folding and dead-node removal happen there. Heightmap-only subgraphs (no Y input) are marked
  **2D** and cached per column (vanilla's `flat_cache` and `cache_2d` ideas). Everything else is 3D, evaluated at
  cell corners (default 4 × 8 × 4, like vanilla) and trilinearly interpolated, unless a node opts out
  (`interpolated: false`).
- **Batching.** `fillChunk` evaluates a chunk in one pass over arrays, not per block through virtual calls. This is
  the main performance lever; section 10 has the targets.
- **Palette ints, not BlockState.** The runtime knows blocks only as strings like `minecraft:stone` or `mygame:rock`,
  mapped to palette indices. The plugin converts them to `BlockState` via `BlockCatalog.resolve`, and another game
  maps them to its own ids.

### 4.3 The JSON format (`.tgen.json`)

```json
{
  "format": "blockdesigner-terragen",
  "formatVersion": 1,
  "name": "Islands",
  "world": { "minY": -64, "height": 384, "seaLevel": 63, "defaultBlock": "minecraft:stone",
             "defaultFluid": "minecraft:water", "voxelSize": 1, "cell": [4, 8, 4] },
  "graph": {
    "nodes": {
      "warp":   { "type": "warp.domain", "salt": "warp", "params": { "amplitude": 40, "frequency": 0.004, "octaves": 3 } },
      "base":   { "type": "noise.opensimplex2", "salt": "base",
                  "params": { "frequency": 0.002, "fractal": "fbm", "octaves": 5, "lacunarity": 2.0, "gain": 0.5 },
                  "inputs": { "pos": "warp" } },
      "height": { "type": "curve.spline", "params": { "points": [[-1, 20], [-0.1, 62], [0.2, 70], [1, 150]] },
                  "inputs": { "in": "base" } },
      "density":{ "type": "shape.height_to_density", "params": { "falloff": 1.0 }, "inputs": { "height": "height" } }
    },
    "outputs": { "density": "density" }
  },
  "surface": [
    { "if": { "type": "above_water", "offset": 0 }, "then": [
        { "if": { "type": "depth", "max": 0 }, "block": "minecraft:grass_block" },
        { "if": { "type": "depth", "max": 3 }, "block": "minecraft:dirt" } ] },
    { "if": { "type": "depth", "max": 2 }, "block": "minecraft:sand" }
  ],
  "scatter": [
    { "name": "Trees", "object": { "type": "tree.simple", "log": "minecraft:oak_log", "leaves": "minecraft:oak_leaves",
      "height": [4, 7] }, "spacing": 7, "chance": 0.6, "on": ["minecraft:grass_block"], "salt": "trees" }
  ],
  "meta": { "author": "", "createdWith": "BlockDesigner Terrain Generator 0.1.0" }
}
```

- `formatVersion` bumps on breaking changes. Readers migrate old versions forward and refuse newer ones with a clear
  message, the same policy as `PluginApi.VERSION`.
- Vanilla density functions are embedded as nodes of type `mc.density_function` holding the vanilla JSON verbatim, or
  as references `mc.ref: "minecraft:overworld/continents"` resolved from a vanilla data source at load time. A spec
  that references vanilla data is marked `"requires": ["minecraft-data:26.1"]`.
- Unknown node types fail to compile with the node id in the message; they are never silently dropped.

### 4.4 Determinism and seeding

- **World seed:** a 64-bit `long`, entered as a number or text. Text is hashed the way Minecraft hashes it
  (`String.hashCode()` when it's not a number), so seeds typed in-game and in BlockDesigner agree.
- **Per-node seeds:** `nodeSeed = mix64(worldSeed ^ hash64(salt))`, with SplitMix64-style mixing. The salt defaults to
  the node's id at creation and then stays put, so renaming or re-wiring a node never reshuffles its noise, and two
  nodes with the same salt are deliberately identical.
- **Vanilla nodes use vanilla's randomness:** Xoroshiro128++ with a positional random factory seeded from the MD5
  of the noise's resource name (`RandomSupport.seedFromHashOf`), and `ImprovedNoise` / `NormalNoise` construction order
  exactly as vanilla. Without that, "Vanilla Overworld, seed 1234" can't look like the real seed 1234.
- **No `java.util.Random`, no `Math.random`, no `HashMap` iteration order** in anything that affects output.
  Transcendentals (`sin`, `exp`, `pow`) go through `StrictMath`, because `Math` may use JIT intrinsics that differ by
  1 ulp between interpreter and C2 or across CPUs. `floor`, `+`, `*` and `sqrt` are exact IEEE in Java 17+ (strictfp is
  the default).
- **Evaluation order independence:** each value depends only on (spec, seed, position). Chunks can be generated in any
  order on any thread, which is what makes infinite streaming and the portable runtime trustworthy.
- **Scatter:** candidates come from a jittered grid per cell (`hash(seed, salt, cellX, cellZ)`), so a tree near a
  chunk border is decided the same way from both sides. Objects are limited to a radius of 8 blocks so a chunk only
  needs its 8 neighbours' candidates.
- Test: a golden hash of 64 sample chunks per preset, checked under `-Xint` and default JIT (section 11).

### 4.5 Threading and chunk streaming

```
  JavaFX thread                     plugin worker pool (cores-2, daemon, low priority)     host render thread
  ------------                      ---------------------------------------------------     ------------------
  param change --> spec edit --> compile Generator (~ms) --> bump generation g
  camera moved (view event) --> ChunkScheduler.update(eye, radius, lods)
                                    PriorityQueue<ChunkTask> by (lod, distance, in-frustum)
                                    task: if task.g != current g -> drop
                                          fillChunk -> ChunkData (palette + short[])
                                          -> LOD0: host API putSection(...)  (host meshes, see 5)
                                          -> LOD1+: build coloured heightfield tile -> host API putMesh(...)
  runOnUiThread(refresh) <-------- results batched every 16 ms
```

- **Scheduling:** chunks nearest the camera and inside the view frustum go first, then the rest of the ring, then the
  outer LOD rings. At most `2 × threads` tasks are in flight, and the queue is rebuilt when the camera moves more than
  8 blocks or turns more than 10°.
- **Cancellation:** a monotonic *generation* counter. Every parameter edit bumps it, and stale tasks drop at the next
  check (per chunk and between Y-slices), so dragging a slider never piles up work. The old preview stays on screen
  until new chunks replace it, chunk by chunk (no flashing to empty).
- **Progressive refinement on edits:** after an edit, the 2D map refreshes first (cheap, about 20 ms), then LOD rings,
  then LOD0 chunks.
- The Scene must only be touched on the JavaFX thread (`PluginContext` rule). Workers produce plain data; only bake
  touches the scene, via `runOnUiThread`.

### 4.6 Caching

| Cache | Key | Size cap | Notes |
|---|---|---|---|
| Column cache (2D values) | (fingerprint, cx, cz, lod) | 4096 columns-chunks, LRU | Heights, biome parameters, surface depth. Shared by map, LOD and LOD0. |
| Chunk block cache | (fingerprint, cx, cz) | ~256 MB, LRU | Palette + `short[]` per 16³ section (8 KB), all-air and all-stone sections stored as a flag. |
| Map tiles | (fingerprint, tileX, tileZ, blocksPerPixel) | 64 tiles of 256² ARGB | Reused when panning. |
| Node thumbnails | (node subgraph hash, seed, view) | 128 | Graph editor only. |

- `fingerprint` hashes the spec and seed. Undoing a change hits the cache again, so undo/redo of parameters is
  instant.
- Only the subgraph hash of the edited node's dependants changes. A refinement for later (not in v1) is caching 2D
  outputs per node, so tweaking the surface layer doesn't regenerate density.

### 4.7 Meshing and LOD for the infinite preview

- **LOD0 (real blocks, radius R0 = 4–8 chunks):** chunk data goes to the **host** as block sections, and the host meshes
  it with its own `SectionMesher` (real models, textures, culling, tints). The plugin must not duplicate the block
  mesher, which depends on `assets` and baked models. This needs API extension A (section 5).
- **LOD1–LOD4 (2×, 4×, 8×, 16×):** a **heightfield tile** per 32/64/128/256-block square: a grid of (height, ARGB
  colour) per sample, turned into a vertex-coloured mesh with top faces plus side walls down to the neighbour's
  height, and skirts along tile edges to hide cracks between LODs. Colours come from `BlockCatalog.averageColor` of
  the surface block, multiplied by biome tint for grass and leaves (the colormap from
  `AssetAccess.texture("minecraft:textures/colormap/grass.png")`). Water is a flat translucent quad at sea level.
  This needs API extension B.
- **Rings:** LOD0 r ≤ R0, LOD1 up to 2·R0, LOD2 up to 4·R0 and so on, snapped to the tile grid. A tile is only
  replaced once its finer replacement is uploaded (no holes while flying).
- **Caves in the LOD preview:** not shown; LODs are surface-only. LOD0 shows caves, and there's a "slice Y" control to
  cut the view like the app's slice view.
- **Voxel scale:** the generator samples at voxel centres, and chunks are 16 voxels wide. The preview volume's
  transform scales by `voxelSize` (the scene object's `Pose` scale), so a 4-block voxel is drawn as one textured block
  4× bigger. No extra meshing path is needed.

### 4.8 Memory limits

- **Budget defaults**, with a setting in the panel: CPU 512 MB for caches, GPU about 400 MB of vertex data. LOD0
  radius is capped by budget: at 384 blocks of height, a full chunk is about 24 sections, about 200 KB raw, about
  0.3–1 MB of mesh. 8 chunks radius is about 200 chunks, which is roughly the limit on a mid-range GPU.
- **Hard limits** for **bake**: warn above 32 M blocks (512 × 384 × 256 is about 50 M) and refuse above what
  `Runtime.maxMemory()` suggests. `Structure` stores 8 KB per non-empty 16³ section, so a fully solid 512 × 384 × 512
  is about 100 MB.
- Everything is released when the Terrain tab and preview are closed or hidden for 60 s. Caches are soft, trimmed on
  `disable()`.
- The runtime library keeps no global state, so memory is whatever the caller's `Generator` and caches hold.

### 4.9 Baking into BlockDesigner layers

- A plugin action runs the chunk filler over the region on workers (with progress in the panel and status bar), builds
  one `Structure` off-thread, then `ctx.addLayer(name, structure)` on the FX thread. That's already one undo step via
  `LayerChange.Add`, which keeps the layer by reference and so is cheap.
- **Re-bake** (replacing a previously baked layer's blocks) would go through `SceneEditor.BlockSession` today, which
  records every changed block in a `BlockChange` map. That's far too heavy for millions of blocks. It needs API
  extension D (replace a layer's structure as one cheap undo step).
- Baked layers remember their origin (generator fingerprint, seed, region) in the preview scene object's saved state,
  so "Re-bake layer" knows what to regenerate. Layer contents are plain blocks, not linked: editing them is normal.
- Voxel scale on bake: "as a model" (1 block per voxel) or "expanded" (voxelSize³ blocks per voxel).

---

## 5. Plugin API: what the host app must add

### 5.1 Already coming in API 3 (Reference Planes worktree), and how the Terrain Generator uses it

| API 3 piece | Use in Terrain Generator |
|---|---|
| `registerObjectType` / `SceneObjectType` / `SceneObject` (saved in the project with `save()`/`load()`, undoable via `ObjectHandle.edit`, listed in the Layers panel with a badge, Move/Rotate/Scale drive its `Pose`) | The **Terrain preview** object. Its state is the generator spec (JSON) + seed + preview settings + bake records. Parameter edits become undo steps through `ObjectHandle.edit`. |
| `SceneObjects.storeBlob` / `blob` | Larger generator files and cached map thumbnails kept with the project. |
| `Drawing.image(ImageData, corners, uv, opacity, Depth)` | **Map 2D mode**: a ground-plane quad per map tile. Also node thumbnails in the view. |
| `Drawing.line` | Chunk grid, region box, sea-level outline, LOD ring borders. |
| `ViewInfo` (eye, forward, target, ortho, side) | Where to stream chunks around, read in `draw()` or via `SceneObjects.view()`. |
| `SceneObject.menu` | Right-click on the preview: Bake here, Re-roll seed, Show graph, Hide LOD. |
| `ObjectHandle.refresh()` | Redraw when new chunks or tiles arrive (no undo step). |
| `Pose` | Preview origin and voxel scale. |

With API 3 alone, **Phases 1–2 ship**: presets, the stack panel, the 2D map preview and baking into new layers.

### 5.2 Still missing, needed by later phases (proposed API 4, or folded into API 3 if it hasn't shipped yet)

Every item has a priority (**must** / **should** / **nice**), the phase that needs it, and a sketch.

**A. Retained block volumes rendered by the host (must, Phase 4).** The plugin supplies block sections; the host meshes
them with `SectionMesher` on its pool, uploads, frustum-culls and draws them, and applies fog and the slice view. It
isn't part of the `Scene` (no undo, not saved, not in exports, not pickable by default).

```java
interface PreviewVolumes {                         // ctx.previews()
    PreviewVolume create(String id);               // removed automatically on disable
}
interface PreviewVolume extends AutoCloseable {
    void putSection(int sx, int sy, int sz, String[] palette, short[] blocks4096); // any thread; host copies/meshes
    void removeSection(int sx, int sy, int sz);
    void clear();
    void setTransform(Pose pose);                  // voxel scale and origin
    void setVisible(boolean v);
    void setOpacity(double o); void setTint(int rgb, double strength);   // mirrors FrameRequest.LayerDraw
    int pendingSections();                         // back-pressure
}
```

Implementation note: `SceneRenderer` already has the meshing pool, versioning and `ViewportRenderer.uploadMesh(layerId,
key, …)`. A volume is essentially a `Group` fed from the plugin instead of from a `Layer`. The Terrain Generator
cannot do this itself without reaching into `render`.

**B. Retained custom meshes (must, Phase 5).** Vertex-coloured (optionally atlas-textured) triangle meshes, uploaded
once and drawn every frame until removed: the LOD heightfield tiles. `Drawing` is immediate-mode per frame, which is
fine for a few image quads but not for 100k+ triangles re-sent each frame. Either follow `ImageData`'s "same instance
= uploaded once" rule with a `MeshData`-like immutable object passed to `Drawing.mesh(…)`, or use retained handles:

```java
final class ColoredMesh {                          // immutable, like ImageData
    ColoredMesh(float[] xyz, int[] argb, int[] indices, boolean translucent);
}
// in Drawing:
void mesh(ColoredMesh mesh, Depth depth);          // host uploads once per instance, culls by its bounds
```

This needs a new simple shader in `render` (position + colour, same fog uniforms as the block shader) or a mode of the
block shader with a white texel.

**C. View events and view distance (should, Phase 5).** `ctx.on(ViewChanged)` delivering `ViewInfo` at most once per
frame (like the other `SceneEvent`s), plus `ViewInfo` fields for **fly mode**, **fov**, **fog distance** and **clip
end**, so the plugin sizes LOD rings to what's visible. The fallback without it is polling `SceneObjects.view()` from
an `AnimationTimer`, which works but wakes every frame.

**D. Replace a layer's blocks as one cheap undo step (must, Phase 4 re-bake).** Something like
`ctx.replaceLayerBlocks(Layer, Structure, String label)` backed by a new `LayerChange.Replace(layerId, before, after)`
that stores the two `Structure` references, not per-block entries. It fires `blocksChanged` for the whole bounds.
Also useful to other plugins (importers, generators).

**E. Exporters that don't need layers (should, Phase 8).** `PluginExporter.needsLayers()` (default `true`). When false,
the Export window shows the card even with no layers chosen, and `Request.merged` may be empty. The fallback is Plugins
menu actions with the plugin's own `FileChooser` (fine for v1).

**F. Minecraft instances and worlds (nice, Phase 8).** `ctx.minecraft()` → the detected instances and their `saves/*`
worlds (the data pack dialog already finds them), so "Install into world" works like the structure data pack dialog.
Also **read access to the selected version's client jar data** (`AssetAccess.dataFile("data/minecraft/worldgen/…")`).
`AssetAccess.texture(resourceId)` reads only `assets/`; the vanilla preset needs `data/minecraft/worldgen/**`
(noise_settings, density_function, noise, and in newer versions the biome parameter presets). This is **must for
Phase 7** unless the plugin locates the jar itself, which would duplicate `McInstallLocator` and is fragile.

**G. Plugin-owned windows follow the theme (should, Phase 6).** `ctx.ui().ownerWindow()` and
`ctx.ui().applyTheme(javafx.scene.Scene)` (adds the app's stylesheets and theme class, and follows theme changes), so
the graph editor `Stage` looks like the app in all 6 themes, light and dark. Today only panels inside the main window
inherit the stylesheet.

**H. Larger dock areas (nice).** `Dock.BOTTOM` actually implemented (it currently falls back to the right). Useful for
the graph editor as a docked strip. G is enough to ship.

**I. Background tasks with progress in the status bar (nice).** `ctx.runTask(label, Consumer<Progress>)`, which shows
progress and a cancel button in the status bar the way exports do. The plugin can do its own, but a shared one keeps
the UI consistent.

**J. Biome tints and block properties for LOD colours (nice).** `BlockCatalog.averageColor(state, biomeTint)` or
`AssetAccess.tint(state)` returning the tint index, so grass and leaves get the right colour without the plugin
guessing from ids.

**K. Flight input for plugin tools (nice).** Already an open item in PLUGINS.md; handy for "click the terrain to set
the region" while flying.

### 5.3 Coordination with the Reference Planes work

- A, B and C touch the same code the API 3 worktree changes: `FrameRequest` (adds `images`), `ViewportRenderer`
  (image shader) and `SceneObjectStore`. They should be designed **after API 3 lands** on `main`, as API 4, or folded
  into API 3 **before it is released** if the timing lines up (see open questions). B especially should share the
  retained-resource pattern `ImageData` introduced (upload once per instance, dropped when no longer drawn).
- `Drawing` should gain `mesh(…)`, with volumes (A) as a separate retained service, because volumes are big, persistent
  and fed from worker threads, unlike per-frame drawing.
- API 3's `SceneObject.draw` runs on the JavaFX thread every frame; the Terrain preview's `draw` must stay trivial
  (hand over current tile handles and lines). All heavy work stays on workers.

---

## 6. Node catalogue

"Export" says how the node maps to a vanilla data pack: **exact** (a vanilla density function does the same thing),
**approx** (converted, e.g. sampled into a spline), or **none**.

### 6.1 Sources

| Node | Params | Export |
|---|---|---|
| `input.x`, `input.y`, `input.z` | scale | y: exact (`y_clamped_gradient`); x/z: none |
| `input.constant` | value | exact (`constant`) |
| `input.y_gradient` | fromY, toY, fromValue, toValue | exact (`y_clamped_gradient`) |
| `input.seed_value` | range | exact (baked constant) |

### 6.2 Noises

Each noise has frequency (or scale xz/y), an offset, a salt, 2D/3D, and a fractal mode (none / fBm / ridged / billow)
with octaves, lacunarity, gain and weighted strength.

| Node | Notes | Export |
|---|---|---|
| `noise.mc_normal` | Vanilla `NormalNoise` (two `ImprovedNoise` stacks, firstOctave, amplitude list) | exact (`noise` / `shifted_noise` + `worldgen/noise` JSON) |
| `noise.mc_blended` | Vanilla `old_blended_noise` | exact |
| `noise.perlin` | Classic improved Perlin | approx: only when lacunarity is 2, gain 0.5 and frequency a power of two, it becomes `mc_normal` with matching octaves (not bit-identical, since vanilla sums two offset stacks) |
| `noise.simplex`, `noise.opensimplex2`, `noise.opensimplex2s` | Smooth, fewer axis artefacts | none (approx via `mc_normal` stand-in, with a warning) |
| `noise.value`, `noise.value_cubic` | Blocky or soft | none |
| `noise.worley` | F1, F2, F2−F1, cell value; distance Euclid/Manhattan/Chebyshev; jitter | none |
| `noise.white` | Per-voxel hash | none |

### 6.3 Warps and coordinates

| Node | Export |
|---|---|
| `warp.domain` (warp the position fed to a noise by up to 3 noises × amplitude) | approx→exact when the warped thing is a single `noise`: `shifted_noise` takes shift_x/y/z density functions. Warping an arbitrary subgraph: none |
| `warp.scale`, `warp.offset`, `warp.rotate` (XZ) | scale/offset exact through noise xz/y_scale and shift; rotate none |
| `warp.voxelize` (snap coordinates to N blocks) | none |

### 6.4 Math and combine

| Node | Export |
|---|---|
| `add`, `mul`, `min`, `max`, `abs`, `square`, `cube`, `half_negative`, `quarter_negative`, `squeeze`, `clamp` | exact (vanilla has each) |
| `negate`, `subtract` | exact (mul by −1, add) |
| `remap` (a..b → c..d, linear) | exact (mul + add) |
| `lerp(a, b, t)` | exact when t is a constant or `range_choice`-able; otherwise approx via `a + t·(b−a)` (exact since mul of two DFs is allowed) |
| `select` / `range_choice` (if in [lo, hi) then A else B) | exact (`range_choice`) |
| `smooth_min`, `smooth_max` | none |
| `pow`, `sqrt`, `sin`, `exp`, `divide` | none (vanilla has no such functions); approx by a spline over the input's known range |
| `curve.spline` (cubic Hermite points, optionally with a coordinate input per point for nesting) | exact (`spline`; vanilla splines nest the same way) |
| `curve.terrace` (steps, smoothness) | approx (spline with 2 points per step) |
| `curve.bias_gain`, `curve.ease` | approx (spline) |

### 6.5 Shape and structure

| Node | Export |
|---|---|
| `shape.height_to_density` (density = height(x,z) − y, with falloff) | exact (`add(height, y_clamped_gradient)`, with `flat_cache` on the height) |
| `shape.density_layer` (combine this layer's density with the stack: add / min / max / subtract / replace) | exact for add/min/max |
| `shape.caves_cheese`, `caves_spaghetti`, `caves_noodle` (vanilla-style presets) | exact (vanilla functions) |
| `shape.overhang` (3D noise added near the surface, masked by depth) | exact when built from exact nodes |
| `shape.island_falloff`, `shape.radial_gradient` | none (needs x/z distance) |
| `cache.flat`, `cache.2d`, `cache.interpolated` (hints) | exact (`flat_cache`, `cache_2d`, `interpolated`) |
| `mc.density_function` (raw vanilla JSON or a reference) | exact |

### 6.6 Materials, water, biomes, scatter (the non-density layers)

| Layer | Contents | Export |
|---|---|---|
| **Fluid** | sea level, fluid block, lava below Y, aquifers on/off (vanilla-style) | sea level and default fluid exact; vanilla aquifers exact when using the vanilla router's fluid functions |
| **Surface rules** | ordered conditions → blocks: depth (stone_depth floor/ceiling), above water, Y above/below, steepness, noise threshold, biome, vertical gradient (bedrock, deepslate) | exact (maps 1:1 to vanilla `surface_rule` conditions: `stone_depth`, `water`, `y_above`, `steep`, `noise_threshold`, `biome`, `vertical_gradient`, `hole`, `temperature`) |
| **Material strata** | block by density value or by Y bands with noise | approx (becomes surface rules with `y_above` + `noise_threshold`); none for 3D-noise ore-like blobs |
| **Biomes** | climate parameters (temperature, humidity, continentalness, erosion, weirdness, depth) → biome ids, from vanilla or custom | exact (`multi_noise` biome source + custom biome JSON). Custom biomes need colours, effects and spawn settings; the plugin writes version-correct defaults |
| **Scatter** | jittered-grid placement of objects: simple trees (trunk + blob / cone canopy), boulders, pillars, a BlockDesigner layer as a prefab (a schematic), grass/flowers | approx: simple trees and boulders map to vanilla configured features (`tree`, `forest_rock`, `random_patch`) with count/rarity placement; prefabs could map to structures via the existing structure data pack exporter (later); the rest none |

### 6.7 Presets shipped

1. **Vanilla Overworld (from your Minecraft)**: vanilla's router read from the selected version's data. Needs 5.2 F.
2. **Vanilla-like (built-in)**: a hand-made, fully exportable approximation (continentalness/erosion/ridges splines,
   biome surfaces) that works without a Minecraft install and serves as a clean starting point.
3. **Flat plus noise**, **Islands**, **Mesas/terraces**, **Floating islands** (3D density), **Canyons** (ridged +
   terraces), **Amplified-ish**, **Heightmap image** (import a greyscale PNG as a height source; approx export via
   none, bake only).

---

## 7. The vanilla-compatible preset

### 7.1 How vanilla terrain works (what we reproduce)

Since 1.18, overworld terrain is data-driven. `worldgen/noise_settings/overworld.json` holds a `noise_router` of
density functions: `continents`, `erosion`, `ridges` (weirdness → peaks and valleys), `depth`, `initial_density_…`,
`final_density` (with cheese, spaghetti and noodle caves), plus aquifer and ore-vein functions. There is also a
`surface_rule` tree, `sea_level`, `default_block` and `default_fluid`. Terrain shape comes from the `offset`, `factor`
and `jaggedness` splines over continentalness, erosion and ridges. Biomes come from a `multi_noise` biome source
matching the same climate parameters.

### 7.2 Fidelity tiers (what can match, realistically)

| Tier | What | Achievable? | Notes |
|---|---|---|---|
| T1 Terrain shape | Stone / air / water in the noise phase, same seed | **Yes, exactly**, with care | A density-function interpreter covering every vanilla DF type (about 35) with vanilla's RNG (Xoroshiro128++, MD5-seeded positional random), `ImprovedNoise`, `NormalNoise`, `BlendedNoise`, cell interpolation (4×8×4) and `flat_cache`/`cache_2d` semantics. Known-exact open-source reimplementations exist in other languages, so this is proven possible. Expect days of debugging off-by-one details. |
| T2 Biomes | Biome per 4×4×4 quart | **Yes**, if we have the parameter list | The overworld `multi_noise` parameter list is built in code in vanilla (`OverworldBiomeBuilder`), not shipped as JSON in the client jar. Options: (a) read it from a report generated by the user's own server jar (`--reports` outputs `minecraft/worldgen/multi_noise_biome_source_parameter_list/overworld.json` in recent versions); (b) a hand-written table; (c) approximate. See open questions. |
| T3 Surface | Grass/dirt/sand/stone/deepslate/bedrock/terracotta bands | **Yes, mostly** | The surface rule JSON is data. Needs the surface system's own noises (`surface`, `surface_secondary`, the badlands `clay_bands` generation) ported exactly. |
| T4 Aquifers and ore veins | Underground water/lava pockets, copper/iron veins | **Possible, harder** | Aquifers are Java code, not data (`Aquifer.NoiseBasedAquifer`); a faithful port is 500+ lines of subtle logic. Ship "aquifers off" (sea-level flooding only) first, then aquifers as a follow-up. |
| T5 Carvers | Legacy canyons and caves | Not planned | Code-driven, removed from the overworld in recent versions except canyons. |
| T6 Features and structures | Trees, ores, flowers, villages | **No** | Hundreds of code-driven features. The scatter layer gives an "approximately forested" look instead. |
| T7 Blending | Old-chunk blending | No | Only matters when upgrading old worlds. |

**The claim to make to users:** "The landscape, water and ground blocks match Minecraft for the same seed and version.
Trees, ores, caves' decorations and structures do not."

**Versions:** DF JSON has changed across 1.18 → 1.19 → 1.20.5 → 1.21.x → 26.x (new types, renamed fields, changed
settings). Support the version selected in BlockDesigner (`ctx.targetVersion()`) for the latest two majors, and
validate unknown DF types with a clear message ("This Minecraft version uses `minecraft:foo`, which the terrain
generator doesn't know yet").

**Legal and data:** read `data/minecraft/worldgen/**` from the user's selected Minecraft jar at runtime, and cache
the parsed router in the plugin's data folder keyed by version. Never ship it, the same policy as textures. Built-in
"Vanilla-like" (preset 2) is our own numbers, so it ships.

### 7.3 Verification against real Minecraft

- A dev-only tool (not shipped) reads a real Minecraft world's region files (`.mca`, NBT, which core already reads) for
  a known seed. It compares the top solid Y and stone/air/water per block for 32 chunks around spawn, and biomes per
  quart. The world is generated once by the developer with *Generate features: off* to isolate terrain.
- Store only compact expected hashes and heightmaps in `testdata/`, not Mojang files.
- Target: 100% match for T1 at the chosen versions; ≥99.9% for T3 (any mismatch explained).

### 7.4 Data pack export mapping

What the export writes (namespace `bd_<name>` by default):

```
pack.mcmeta                                            (format from the target version, min/max_format when needed)
data/<ns>/worldgen/noise_settings/<name>.json          noise router + surface_rule + sea level + default blocks
data/<ns>/worldgen/density_function/<name>/*.json      one file per named/shared subgraph (keeps it readable)
data/<ns>/worldgen/noise/<name>/*.json                 noise parameters (firstOctave + amplitudes)
data/<ns>/worldgen/biome/*.json                        only custom biomes
data/<ns>/worldgen/world_preset/<name>.json            dimension overworld = noise generator(settings, multi_noise source)
data/minecraft/tags/worldgen/world_preset/normal.json  (optional) make the preset show in "World Type"
data/<ns>/worldgen/configured_feature, placed_feature  for exportable scatter (approx)
```

- **Alternative mode:** "Replace the vanilla overworld" overrides `data/minecraft/worldgen/noise_settings/overworld.json`.
  It's simpler for players but affects every new world with the pack, so it's opt-in with a warning.
- **Mapping rule:** compile the graph to DF JSON node by node. **Exact** nodes emit their DF. **Approx** nodes emit a
  substitute and add a line to the report (e.g. "Terraces (8 steps) → spline with 16 points; max error 0.4 blocks").
  **None** nodes block the export of that output. The user either removes them, picks "approximate with spline" (only
  for 1-input functions of a bounded input), or exports only the runtime bundle.
- **2D-only generators** (height graphs) export as `final_density = add(flat_cache(height(x,z)), y_clamped_gradient(…))`.
  Arbitrary heightmaps from x/z-dependent maths (radial falloff, images) are **not** expressible, because vanilla
  density functions can't read x or z except through noise. This is the biggest limit, and the UI says so plainly.
- **The compatibility badge** in the stack is computed live from the same compiler, so users see it while designing,
  not at export.
- **Round-trip test:** the exported DF JSON is re-loaded by our own `mc.density_function` interpreter and must equal
  the original generator's output for exact graphs (bit-for-bit) and within the reported error for approx ones.
- **In-game smoke test (manual, per release):** install the pack, create a world, `/tp` to three sample coordinates and
  compare screenshots with the preview.

---

## 8. The "other Java game" runtime

**Artifact:** `terragen-runtime-<v>.jar`. Java 17+, no dependencies, MIT (same as the app, pending confirmation).
Published as a GitHub release asset alongside the plugin, and optionally to Maven Central later.

```java
GeneratorSpec spec = GeneratorSpec.read(Files.newInputStream(Path.of("islands.tgen.json")));
Generator gen = Generator.compile(spec, 1234L);

// Minecraft-like chunk
ChunkBuffer chunk = new ChunkBuffer(spec.world());    // palette + short[] per 16³ section
gen.fillChunk(cx, cz, chunk, Lod.FULL);
for (String id : chunk.palette()) myEngine.registerBlock(id);

// Heights only (for LOD, maps, spawn finding)
int h = gen.surfaceHeight(x, z);
double d = gen.density(x, y, z);                      // raw density for smooth-voxel / marching-cubes engines
```

- **Thread-safety:** a compiled `Generator` is immutable. Callers may fill chunks from any number of threads. Per-thread
  scratch comes from a small `ThreadLocal` pool.
- **Block mapping:** `BlockMapper` lets a game map `minecraft:grass_block` to its own ids, or the spec can use the
  game's ids directly.
- **Non-Minecraft voxel games:** `voxelSize` and `minY/height` are free, with no 16-block section assumption in the
  API (the `ChunkBuffer` size is configurable). `density()` is exposed for smooth terrain (marching cubes / dual
  contouring).
- **Vanilla-referencing specs** need the vanilla data. The runtime accepts a `DataSource` (a folder or zip holding
  `data/minecraft/worldgen`), so a mod can pass the game's own data.
- **Minecraft mod (optional, Phase 9):** a small Fabric (and later NeoForge) mod, `terragen-mc`, registering a
  `ChunkGenerator` backed by the runtime. It supports **everything**, including nodes with no data pack mapping
  (Simplex, Worley, radial falloffs), because it isn't limited to vanilla density functions. Surface and features
  still use the game's systems where possible. This is its own project; the plan only reserves the design.
- **Stability:** the `.tgen.json` format version and the runtime's public API follow semver. Output for a given
  (spec, seed, runtime major) never changes. Any change to a noise's numbers requires a new node type or a new
  `formatVersion` with a migration that keeps old files producing the same terrain.

---

## 9. Plugin internals (the `plugin` module)

| Class | Role |
|---|---|
| `TerrainGeneratorPlugin` | Entry point; registers the panel, the object type, the tool, actions, commands and (later) exporters. |
| `TerrainPanel` | The stack view (JavaFX); binds to a `TerrainDocument`. |
| `TerrainDocument` | The editable spec + seed + preview settings. Undo via `ObjectHandle.edit` on the preview object. Emits "changed" to the scheduler. |
| `TerrainPreviewType` / `TerrainPreviewObject` | `SceneObjectType` / `SceneObject` (API 3): saves the document, draws map tiles and lines, owns volumes and meshes (API 4). |
| `ChunkScheduler` | Priority queue, generation counter, worker pool, back-pressure on `PreviewVolume.pendingSections()`. |
| `MapTiler` | 2D map tiles (height/biome/surface colour, hillshade) → `ImageData`. |
| `LodMesher` | Heightfield tiles → `ColoredMesh` with skirts. |
| `Baker` | Region → `Structure` (off-thread) → `addLayer` / `replaceLayerBlocks`. |
| `GraphEditorWindow` | Node canvas (JavaFX `Pane` + `Canvas` for wires), node inspector, thumbnails. |
| `RegionTool` | `PluginTool` for dragging the region. |
| `DatapackExport`, `BundleExport` | Actions or exporters; use `mcdata`. |
| `VanillaData` | Loads and caches the vanilla router for the target version (API 4 F). |

---

## 10. Performance targets

Reference machine: 8-core / 16-thread desktop CPU, mid-range GPU, 16 GB RAM. Workers = cores − 2.

| Operation | Target |
|---|---|
| Compile a generator after an edit | < 5 ms (typical), < 30 ms (vanilla router) |
| 2D map, 512 × 512 samples, heightmap generator | < 60 ms total (all workers) |
| 2D map, 512 × 512, vanilla router (surface height search) | < 400 ms |
| Full chunk, 16 × 384 × 16, vanilla router, one thread | ≤ 8 ms (vanilla itself runs a few ms per chunk for noise) |
| Full chunk, heightmap generator (2D + surface) | ≤ 1.5 ms |
| First visible update after a slider change (map mode) | < 150 ms |
| LOD0 ring of 8 chunks radius (≈ 200 chunks) filled from cold | < 3 s including host meshing |
| Flying at sprint speed with LOD0 r=6 | no holes at LOD0 inside the view after 1 s of travel; UI stays at 60 fps (all work off the FX thread) |
| Bake 256 × 384 × 256 (vanilla) | < 10 s, peak extra memory < 400 MB |
| Plugin heap budget (caches) | default 512 MB, configurable |
| `SceneObject.draw` per frame | < 0.5 ms on the FX thread |

Levers if targets are missed: 2D caching, batched cell evaluation, skipping air-only and solid-only sections early
(density bounds per cell), fewer Y cells above the max terrain height, and `float` instead of `double` for
non-vanilla nodes (vanilla nodes must stay `double` for parity).

---

## 11. Testing strategy

**Runtime (pure JUnit, fast, no BlockDesigner):**
- Noise unit tests: range bounds, continuity (no cracks at integer and cell boundaries), isotropy smoke tests, and
  known-value fixtures per noise type and seed.
- Determinism: a golden hash of 64 chunks × each preset. The same tests run under `-Xint` and default JIT, on the
  `release 17` target and on the repo's JDK 26.
- Chunk-order independence: filling chunks in random order on 8 threads gives identical results to sequential.
- JSON: round trip, migration of old `formatVersion` fixtures, and clear errors for cycles, missing inputs and
  unknown types.
- Scatter: an object straddling a chunk border is identical whichever chunk is generated first.

**Vanilla parity (`mcdata`):**
- A DF interpreter per-type test against hand-computed values.
- Parity against real-world hashes (section 7.3). Tests needing a Minecraft install are tagged and skipped without
  one, like the app's asset-backed UI tests.
- Export round trip: graph → DF JSON → interpreter equals graph (exact) or within the reported error (approx).
- A data pack schema check: every emitted DF type exists in the target version's DF list, and pack.mcmeta matches
  `McVersion` rules.

**Plugin (with plugin-api test fixtures, like `PluginManagerTest`):**
- Loads in the plugin manager next to other plugins; enable and disable leak no threads (pool shut down in
  `disable`).
- Bake gives one undo step, and undo removes the layer; re-bake (API 4 D) gives one cheap undo step.
- The preview object saves and loads through a project round trip; parameter edits undo.
- The scheduler drops stale generations, never exceeds its in-flight cap and respects the memory budget.
- UI snapshots (`UI_SNAPSHOTS=1`, like `PluginUiSnapshotsIT`): the Terrain panel, the graph editor and the export
  report, in dark and light themes.

**Performance:** a small benchmark harness (a JUnit-tagged `@Tag("bench")` or a JMH module) for chunk fill and map
tiles, run manually before releases and recorded in the plugin's release notes.

**Manual per release:** fly through Infinite mode for 2 minutes and watch memory; export a data pack, generate a world
in the target Minecraft version, and compare three spots; use the runtime jar from a scratch Java project.

---

## 12. Risks and open questions

### Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Vanilla parity is fiddly (RNG order, cell interpolation, spline details) | T1 slips by weeks | Port type by type with fixtures; build the parity harness first; ship "Vanilla-like (built-in)" earlier so users aren't blocked. |
| Minecraft changes the DF format every major | Vanilla preset breaks on new versions | Version-gated DF registry; unknown types fail clearly; parity tests per supported version. |
| Biome parameter list is code, not data | T2 blocked or approximate | See Q3. |
| The host API work (A, B, D, F) depends on API 3 landing and on renderer changes | Phases 4–5 wait | Phases 1–3 need only API 3. Design API 4 with whoever finishes Reference Planes; keep the proposals small. |
| GPU memory with large LOD0 radii on weak GPUs | Stutter or out-of-memory | Budget-driven radius, `pendingSections()` back-pressure, an automatic step-down of the radius. |
| Users expect "exactly my Minecraft world" including trees and villages | Disappointment | Say plainly in the UI which layers match; show approx/none chips. |
| Graph editor scope creep | Months of UI | The stack view is the primary UI; the graph editor comes later (Phase 6) and stays basic (no groups, no comments in v1). |
| Plugin reaching into app internals for speed | Breaks on app updates | Code review rule: imports only from `io.blockdesigner.plugin` and `io.blockdesigner.core`. A test scans the plugin jar's constant pool for `io.blockdesigner.render`/`app` references. |
| Data pack export can't express x/z-dependent maths | Users build something that can't ship to vanilla | Live compatibility badges; the Fabric mod path (Phase 9) for everything else. |

### Answers from the user (2026-09-26)

- **Versions:** match Minecraft **26.3** only.
- **Fidelity:** **exact**: block-for-block vanilla terrain for a given seed is the goal.
- **Biome parameters:** **pull them from the jar** (generate from the user's own server jar).
- **Other Java games:** **dropped**: focus on Minecraft only. The runtime stays a separate library inside the plugin,
  but the portable-runtime release and the Fabric mod (Phase 9's "other games" part) are out of scope.
- **Where it lives:** a plugin, developed and released separately; plugin code never goes into the app's main branch.
- **API timing:** API 3 ships in the app as it is (0.4.12); the terrain needs come as API 4 in a later app release.

### Open questions for the user

1. **Which Minecraft versions must the vanilla preset match?** Only the newest (26.x), or also 1.21.x? Each extra major
   adds parity work.
2. **Exact or "looks like"?** Is block-exact terrain for a given seed required (T1–T3), or is a close, fully
   exportable "Vanilla-like" preset enough for v1? This decides whether Phase 7 is 2 weeks or 5+.
3. **Biome parameters:** OK to generate them from the user's own server jar (`--reports`, needs a server jar download
   and about 30 s once per version), or should we hand-write an approximate table?
4. **Reading vanilla worldgen data from the user's install at runtime:** fine, like textures? (Assumed yes.)
5. **Which "different game in Java"?** A specific engine (libGDX, jMonkeyEngine, your own), or a Minecraft mod? If a
   mod, Fabric, NeoForge or both, and should the Fabric `ChunkGenerator` mod (Phase 9) be in scope?
6. **API timing:** fold the terrain needs (volumes, meshes, view events, replace-layer, data file access) into API 3
   before it releases, or ship API 3 as-is for Reference Planes and do API 4 next?
7. **Runtime license and Java target:** MIT and Java 17 bytecode OK?
8. **Should baked layers stay linked** to the generator (re-bake on change, a "generated" badge), or become plain
   blocks immediately (the plan's default, with a re-bake command)?
9. **Graph editor placement:** a separate window (plan default) or a bottom dock in the main window (needs `Dock.BOTTOM`
   implemented)?
10. **Maximum bake size** you want to support (512² × 384? 1024²?). It drives memory work in core's `Structure` and
    the renderer.

---

## 13. Phased delivery plan

Estimates assume one developer working focused days. They are honest ranges, not promises. The riskiest item is
vanilla parity (Phase 7).

### Phase 0: Decisions and API design (2–3 days)
- Answer the open questions (at least 1, 2, 5, 6).
- Write the API 4 proposal (5.2 A–F) as interface stubs + a short design note, agreed with the Reference Planes work.
- **Done when:** the questions are answered in this doc, the API 4 stubs are reviewed, and the module skeleton
  (`plugins/terrain-generator/{runtime,mcdata,plugin}`) builds and the empty plugin loads under API 3.

### Phase 1: Runtime core (1.5–2.5 weeks)
- Noise primitives (Perlin, OpenSimplex2, Value, Worley, fractal modes, white), math/curve/select nodes, domain warp,
  terraces, `height_to_density`, surface rules (depth, water, Y, steep, noise), fluid at sea level, simple scatter
  (tree, boulder).
- JSON reader/writer + `formatVersion` 1, compiler, batched chunk filler, `surfaceHeight` fast path.
- **Done when:** the presets "Flat plus noise", "Islands", "Mesas" and "Vanilla-like (built-in)" generate
  deterministically (golden hashes pass under `-Xint` and JIT); a chunk fills in ≤ 1.5 ms for heightmap presets;
  runtime has zero dependencies and targets Java 17.

### Phase 2: Plugin MVP on API 3: map preview and bake (1.5–2 weeks). First shippable release (plugin 0.1.0)
- Terrain panel (stack view, presets, seed, parameters via `Options` + a curve editor), preview scene object saved in
  the project, Map 2D mode (tiles via `Drawing.image`, lines for region and chunk grid), Region tool, bake to new
  layer (off-thread, one undo step), voxel scale on bake (model / expanded), `/terrain` commands.
- **Done when:** a user can install the jar, pick "Vanilla-like", change the seed, see the map update in < 150 ms,
  drag a 256² region, bake it into an editable layer, undo it, and save and reopen the project with the generator
  intact. UI snapshots exist for both themes.

### Phase 3: Host API 4, part 1 (1–1.5 weeks, host app release)
- `PreviewVolumes` (A), `replaceLayerBlocks` (D), view events + view distance (C), `AssetAccess.dataFile` (F-data),
  theme for plugin windows (G). Docs in PLUGINS.md, the reference and a design record like `plugin-api-v2.md`; tests
  like the existing plugin tests.
- **Done when:** a BlockDesigner release with `PluginApi.VERSION` bumped ships; palette-tools and Reference Planes
  still load; a test plugin streams 100 sections into a volume and they render, cull and disappear on disable.

### Phase 4: 3D chunk preview and re-bake (1 week). Plugin 0.2.0
- Chunks 3D mode on `PreviewVolume` with live regeneration, slice Y, voxel scale via the volume transform, and
  re-bake of a baked layer.
- **Done when:** an 8 × 8 chunk preview updates within 1 s of a slider change without FX-thread stalls (> 16 ms); re-bake
  is one undo step whose memory is proportional to the structure, not per-block entries.

### Phase 5: Infinite streaming and LOD (1.5–2 weeks, including host API B). Plugin 0.3.0
- Host: `ColoredMesh` + `Drawing.mesh` (B) with a vertex-colour shader and fog.
- Plugin: `ChunkScheduler` priorities and cancellation, LOD rings 1–4 heightfield tiles with skirts, biome tint colours,
  memory budget and automatic radius step-down, follow-camera and fly support.
- **Done when:** the performance targets in section 10 for streaming are met on the reference machine; a 2-minute flight
  keeps memory within budget; LOD transitions have no visible holes at normal speed.

### Phase 6: Graph editor (2–3 weeks). Plugin 0.4.0
- Node canvas, wiring, inspector, per-node thumbnails, "preview this node", stack↔graph sync, copy and paste of nodes,
  saving and loading `.tgen.json` files.
- **Done when:** every preset opens as a graph and round-trips unchanged; a user can build "Islands" from scratch in the
  graph; undo covers graph edits; the window follows all themes.

### Phase 7: Vanilla Overworld from the user's Minecraft (3–5 weeks, risk). Plugin 0.5.0
- `mcdata`: DF interpreter (all types for the supported versions), vanilla RNG and noises, cell interpolation,
  surface rules with vanilla surface noises, biome source (per Q3), parity harness against real worlds; then aquifers
  as a follow-up milestone (+1–2 weeks).
- **Done when:** for each supported version, 32 chunks around spawn for 3 seeds match real Minecraft 100% in terrain
  shape and ≥ 99.9% in surface blocks and biomes (with features off); the chunk fill meets ≤ 8 ms/thread.

### Phase 8: Data pack export (1.5–2 weeks). Plugin 0.6.0
- Graph → DF JSON compiler with exact/approx/none, the compatibility badges in the stack and graph, the report, world
  preset and overworld-override modes, custom biome JSON, scatter → simple configured features, zip and install into
  world (API F-instances if available; own file picker otherwise), and optionally exporter cards (API E).
- **Done when:** every exact preset exports, loads in the target Minecraft without errors, and generates terrain that
  matches the preview at three checked spots; the round-trip test passes; a graph with a Worley node shows a clear
  "not exportable" reason.

### Phase 9: Runtime release and other games (1 week, + 2–3 weeks for the optional Fabric mod)
- Publish `terragen-runtime` with a README, a Javadoc and a sample project (a console heightmap PNG writer, plus a
  minimal LWJGL or libGDX viewer if Q5 names one). "Export › Generator bundle" in the plugin.
- Optional: the `terragen-mc` Fabric `ChunkGenerator` mod.
- **Done when:** a scratch Java 17 project using only the runtime jar reproduces the plugin's chunk hashes for a
  bundled generator; (optional) the Fabric mod creates a world with an Islands generator including a Worley node.

### Rough total

About **3–4 months** of focused work to Phase 8 (plus about 1 month if full vanilla parity and aquifers are required,
and about 2–3 weeks for the optional mod). The first useful release (Phase 2) is about 3–4 weeks in and needs no
host changes beyond API 3.
