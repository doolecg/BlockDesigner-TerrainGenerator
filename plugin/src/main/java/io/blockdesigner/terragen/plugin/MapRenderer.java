package io.blockdesigner.terragen.plugin;

import io.blockdesigner.terragen.Generator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

/**
 * Draws a generator from above: each pixel is the top block's colour, shaded by the slope (light from the north-west)
 * and seen through water by depth. Uses the generator's quick map heights, so a 3D generator's overhangs don't show.
 */
final class MapRenderer {
    private static final int WATER = 0x3F76E4;
    /** Plains-like colours for blocks the game tints by biome; their textures are grey. */
    private static final Map<String, Integer> TINTS = Map.of(
            "minecraft:grass_block", 0x91BD59, "minecraft:oak_leaves", 0x77AB2F, "minecraft:birch_leaves", 0x80A755,
            "minecraft:spruce_leaves", 0x619961, "minecraft:jungle_leaves", 0x30BB0B, "minecraft:acacia_leaves", 0xAEA42A,
            "minecraft:dark_oak_leaves", 0x59AE30, "minecraft:vine", 0x77AB2F);

    private final ToIntFunction<String> averageColor;
    private final Map<String, Integer> colours = new ConcurrentHashMap<>();

    /** @param averageColor a block id's average texture colour (RGB), e.g. from {@code BlockCatalog.averageColor} */
    MapRenderer(ToIntFunction<String> averageColor) {
        this.averageColor = averageColor;
    }

    /** The colour of a block seen from above, tinted where the game tints it. */
    int colour(String id) {
        return colours.computeIfAbsent(id, k -> {
            int c = averageColor.applyAsInt(k) & 0xFFFFFF;
            Integer tint = TINTS.get(k);
            return tint == null ? c : multiply(c, tint);
        });
    }

    /**
     * A {@code size} × {@code size} ARGB image centred on (cx, cz), one pixel per {@code step} blocks, rows along z.
     * Rows are drawn in parallel; returns null when {@code cancelled} turns true part-way.
     */
    int[] render(Generator g, int cx, int cz, int size, int step, BooleanSupplier cancelled) {
        int x0 = cx - size / 2 * step, z0 = cz - size / 2 * step, sea = g.world().seaLevel();
        // Heights one pixel beyond each edge, for the shading.
        int n = size + 2;
        int[] tops = new int[n * n];
        String[] blocks = new String[size * size];
        boolean[] stop = new boolean[1];
        IntStream.range(0, n).parallel().forEach(j -> {
            if (stop[0] || cancelled.getAsBoolean()) {
                stop[0] = true;
                return;
            }
            for (int i = 0; i < n; i++) {
                int x = x0 + (i - 1) * step, z = z0 + (j - 1) * step;
                boolean inside = i > 0 && i <= size && j > 0 && j <= size;
                if (inside) {
                    Generator.Column col = g.mapColumn(x, z);
                    tops[j * n + i] = col.top();
                    blocks[(j - 1) * size + i - 1] = col.block();
                } else {
                    tops[j * n + i] = g.mapSurfaceY(x, z);
                }
            }
        });
        if (stop[0]) return null;
        int[] out = new int[size * size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                int top = tops[(j + 1) * n + i + 1];
                String b = blocks[j * size + i];
                int c = b == null ? 0x202020 : colour(b);
                // Light from the north-west: brighter where the ground rises towards it.
                double slope = (tops[j * n + i] - tops[(j + 2) * n + i + 2]) / (double) step;
                double shade = Math.max(0.55, Math.min(1.35, 1 - slope * 0.12));
                c = scale(c, shade);
                if (top + 1 < sea) {
                    double depth = sea - 1 - top;
                    c = blend(c, WATER, Math.min(0.88, 0.5 + depth * 0.03));
                }
                out[j * size + i] = 0xFF000000 | c;
            }
        }
        return out;
    }

    static int multiply(int a, int b) {
        int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255, g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255, bl = (a & 255) * (b & 255) / 255;
        return (r << 16) | (g << 8) | bl;
    }

    static int scale(int c, double k) {
        int r = (int) Math.min(255, ((c >> 16) & 255) * k), g = (int) Math.min(255, ((c >> 8) & 255) * k), b = (int) Math.min(255, (c & 255) * k);
        return (r << 16) | (g << 8) | b;
    }

    static int blend(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
