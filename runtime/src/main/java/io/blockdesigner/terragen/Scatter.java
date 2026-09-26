package io.blockdesigner.terragen;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Objects dotted over the surface: trees, boulders. Candidates sit on a jittered grid (one per {@code spacing} ×
 * {@code spacing} cell, kept with probability {@code chance}), decided only from the seed and the cell, so an object
 * across a chunk border comes out the same from either side. Objects reach at most {@link #MAX_RADIUS} blocks from
 * their base.
 *
 * <p>JSON: {@code {"name": "Trees", "object": {"type": "tree", ...}, "spacing": 7, "chance": 0.6, "on":
 * ["minecraft:grass_block"], "salt": "trees"}}. Objects: {@code tree} (log, leaves, height [min, max]) and
 * {@code boulder} (block, radius [min, max]).
 */
final class Scatter {
    static final int MAX_RADIUS = 8;

    /** Writes an object's blocks; only empty (air) cells are filled. */
    interface Writer {
        void set(int x, int y, int z, String block);
    }

    private interface Placer {
        /** Places the object with its base on the air block above the surface at (x, y, z). */
        void place(long hash, int x, int y, int z, Writer w);
    }

    final String name;
    final int spacing;
    final double chance;
    final Set<String> on;
    final long seed;
    private final Placer object;

    Scatter(Map<String, Object> m, long worldSeed, int index) {
        name = GeneratorSpec.str(m, "name", "Scatter " + (index + 1));
        spacing = (int) GeneratorSpec.num(m, "spacing", 8);
        if (spacing < 2 || spacing > 256) throw new IllegalArgumentException(name + ": spacing must be 2 to 256");
        chance = GeneratorSpec.num(m, "chance", 0.5);
        on = new HashSet<>();
        if (m.get("on") instanceof List<?> l) for (Object o : l) on.add(String.valueOf(o));
        seed = Seeds.nodeSeed(worldSeed, GeneratorSpec.str(m, "salt", name));
        object = placer(GeneratorSpec.map(m.get("object")));
    }

    /** The candidate in grid cell (cx, cz): its x and z, or null when the cell has none. */
    int[] candidate(int cx, int cz) {
        long h = Seeds.hash(seed, cx, cz);
        if (Seeds.unit(h) >= chance) return null;
        long h2 = Seeds.mix64(h);
        int x = cx * spacing + (int) Long.remainderUnsigned(h2, spacing);
        int z = cz * spacing + (int) Long.remainderUnsigned(h2 >>> 20, spacing);
        return new int[]{x, z};
    }

    void place(int x, int y, int z, Writer w) {
        object.place(Seeds.hash(seed, x, z), x, y, z, w);
    }

    private Placer placer(Map<String, Object> m) {
        String type = GeneratorSpec.str(m, "type", "tree").toLowerCase(Locale.ROOT);
        switch (type) {
            case "tree": {
                String log = GeneratorSpec.str(m, "log", "minecraft:oak_log"), leaves = GeneratorSpec.str(m, "leaves", "minecraft:oak_leaves");
                int[] h = range(m.get("height"), 4, 6);
                if (h[1] > 12) throw new IllegalArgumentException(name + ": tree height is at most 12");
                return (hash, x, y, z, w) -> {
                    int height = h[0] + (int) Long.remainderUnsigned(hash, h[1] - h[0] + 1);
                    int top = y + height - 1;
                    // The trunk first: objects only fill empty cells, so leaves placed first would cut it short.
                    for (int i = 0; i < height; i++) w.set(x, y + i, z, log);
                    for (int dy = -2; dy <= 1; dy++) {
                        int r = dy >= 0 ? 1 : 2;
                        for (int dx = -r; dx <= r; dx++) {
                            for (int dz = -r; dz <= r; dz++) {
                                boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
                                // Corners are left out at random, like vanilla's blob foliage.
                                if (corner && (dy == 1 || (Seeds.hash(hash, dx * 31 + dy, dz) & 1) == 0)) continue;
                                w.set(x + dx, top + dy, z + dz, leaves);
                            }
                        }
                    }
                };
            }
            case "boulder": {
                String block = GeneratorSpec.str(m, "block", "minecraft:mossy_cobblestone");
                int[] r = range(m.get("radius"), 1, 2);
                if (r[1] > 5) throw new IllegalArgumentException(name + ": boulder radius is at most 5");
                return (hash, x, y, z, w) -> {
                    int rad = r[0] + (int) Long.remainderUnsigned(hash, r[1] - r[0] + 1);
                    double rr = (rad + 0.5) * (rad + 0.5);
                    int cy = y + rad - 2;
                    for (int dx = -rad; dx <= rad; dx++)
                        for (int dy = -rad; dy <= rad; dy++)
                            for (int dz = -rad; dz <= rad; dz++)
                                if (dx * dx + dy * dy * 1.4 + dz * dz <= rr) w.set(x + dx, cy + dy, z + dz, block);
                };
            }
            default:
                throw new IllegalArgumentException(name + ": unknown object type '" + type + "' (tree or boulder)");
        }
    }

    private int[] range(Object o, int min, int max) {
        if (o instanceof List<?> l && l.size() == 2 && l.get(0) instanceof Number a && l.get(1) instanceof Number b) {
            min = a.intValue();
            max = b.intValue();
        } else if (o instanceof Number n) {
            min = max = n.intValue();
        }
        if (min < 1 || max < min) throw new IllegalArgumentException(name + ": a size range is [min, max] with 1 ≤ min ≤ max");
        return new int[]{min, max};
    }
}
