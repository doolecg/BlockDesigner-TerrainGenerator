package io.blockdesigner.terragen.plugin;

import io.blockdesigner.terragen.GeneratorSpec;
import io.blockdesigner.terragen.Json;
import io.blockdesigner.terragen.Presets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the Terrain Generator works with, as one immutable value: the generator (with slider changes), the seed,
 * the bake region and how to bake it, and the map zoom. It is what the Terrain preview object saves in the project, so
 * one undo step puts all of it back.
 *
 * @param presetId  the built-in generator it started from, or null for one opened from a file
 * @param spec      the generator
 * @param seed      the seed as typed (a number or any text)
 * @param centreX   the middle of the bake region
 * @param centreZ   the middle of the bake region
 * @param sizeX     the region's width along x
 * @param sizeZ     the region's depth along z
 * @param bottomY   the lowest y baked
 * @param water     whether the sea is baked
 * @param zoom      map blocks per pixel
 * @param voxel     blocks per voxel: 1, 2, 4 or 8
 * @param expanded  with voxels above 1: fill each voxel's blocks (true) or bake one block per voxel, a small model (false)
 */
record TerrainState(String presetId, GeneratorSpec spec, String seed, int centreX, int centreZ, int sizeX, int sizeZ, int bottomY,
                    boolean water, int zoom, int voxel, boolean expanded) {
    static final List<Integer> VOXELS = List.of(1, 2, 4, 8);
    static final int MAX_SIZE = 4096;

    TerrainState {
        Objects.requireNonNull(spec, "spec");
        seed = seed == null ? "" : seed;
        sizeX = Math.max(1, Math.min(MAX_SIZE, sizeX));
        sizeZ = Math.max(1, Math.min(MAX_SIZE, sizeZ));
        zoom = Math.max(1, Math.min(64, zoom));
        if (!VOXELS.contains(voxel)) voxel = 1;
    }

    static TerrainState initial(String seed) {
        Presets.Preset p = Presets.get("vanilla-like");
        return new TerrainState(p.id(), p.spec(), seed, 0, 0, 256, 256, 40, true, 4, 1, true);
    }

    int minX() {
        return centreX - sizeX / 2;
    }

    int minZ() {
        return centreZ - sizeZ / 2;
    }

    TerrainState withPreset(String id, GeneratorSpec s) {
        return new TerrainState(id, s, seed, centreX, centreZ, sizeX, sizeZ, bottomY, water, zoom, voxel, expanded);
    }

    TerrainState withSpec(GeneratorSpec s) {
        return new TerrainState(presetId, s, seed, centreX, centreZ, sizeX, sizeZ, bottomY, water, zoom, voxel, expanded);
    }

    TerrainState withSeed(String s) {
        return new TerrainState(presetId, spec, s, centreX, centreZ, sizeX, sizeZ, bottomY, water, zoom, voxel, expanded);
    }

    TerrainState withCentre(int x, int z) {
        return new TerrainState(presetId, spec, seed, x, z, sizeX, sizeZ, bottomY, water, zoom, voxel, expanded);
    }

    TerrainState withSize(int sx, int sz) {
        return new TerrainState(presetId, spec, seed, centreX, centreZ, sx, sz, bottomY, water, zoom, voxel, expanded);
    }

    TerrainState withBottom(int y) {
        return new TerrainState(presetId, spec, seed, centreX, centreZ, sizeX, sizeZ, y, water, zoom, voxel, expanded);
    }

    TerrainState withWater(boolean w) {
        return new TerrainState(presetId, spec, seed, centreX, centreZ, sizeX, sizeZ, bottomY, w, zoom, voxel, expanded);
    }

    TerrainState withZoom(int z) {
        return new TerrainState(presetId, spec, seed, centreX, centreZ, sizeX, sizeZ, bottomY, water, z, voxel, expanded);
    }

    TerrainState withVoxel(int v, boolean e) {
        return new TerrainState(presetId, spec, seed, centreX, centreZ, sizeX, sizeZ, bottomY, water, zoom, v, e);
    }

    // ---- JSON -------------------------------------------------------------------------------------------------

    String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 1);
        if (presetId != null) m.put("preset", presetId);
        m.put("spec", Json.parse(spec.toJson()));
        m.put("seed", seed);
        m.put("centre", List.of(centreX, centreZ));
        m.put("size", List.of(sizeX, sizeZ));
        m.put("bottomY", bottomY);
        m.put("water", water);
        m.put("zoom", zoom);
        m.put("voxel", voxel);
        m.put("expanded", expanded);
        return Json.write(m);
    }

    /** Reads {@link #toJson()}; anything missing takes its default. */
    @SuppressWarnings("unchecked")
    static TerrainState fromJson(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Terrain settings are a JSON object");
        Map<String, Object> m = (Map<String, Object>) root;
        TerrainState d = initial("");
        String preset = m.get("preset") instanceof String p ? p : null;
        GeneratorSpec spec = m.get("spec") != null ? GeneratorSpec.read(Json.write(m.get("spec"))) : d.spec();
        List<Object> centre = m.get("centre") instanceof List<?> l ? (List<Object>) l : List.of(0.0, 0.0);
        List<Object> size = m.get("size") instanceof List<?> l ? (List<Object>) l : List.of(256.0, 256.0);
        return new TerrainState(preset, spec, m.get("seed") == null ? "" : String.valueOf(m.get("seed")),
                i(centre.get(0)), i(centre.get(1)), i(size.get(0)), i(size.get(1)), (int) num(m, "bottomY", d.bottomY()),
                !Boolean.FALSE.equals(m.get("water")), (int) num(m, "zoom", d.zoom()), (int) num(m, "voxel", 1),
                !Boolean.FALSE.equals(m.get("expanded")));
    }

    private static int i(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static double num(Map<String, Object> m, String key, double fallback) {
        return m.get(key) instanceof Number n ? n.doubleValue() : fallback;
    }
}
