package io.blockdesigner.terragen.plugin;

import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.Structure;
import io.blockdesigner.terragen.Chunk;
import io.blockdesigner.terragen.Generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.Function;

/**
 * Turns a region of a generator into blocks: chunks are filled in parallel and copied into one {@link Structure} in
 * world coordinates (the layer it goes into sits at the origin).
 *
 * <p>With a voxel size above 1, the world is sampled at the middle of each voxel: either each voxel fills its
 * voxel × voxel × voxel blocks (expanded: blocky terrain at full size), or each voxel becomes one block (a model at
 * 1 : voxel, with its corner at the region's corner and y divided by the voxel size).
 */
final class Baker {
    /**
     * What to bake.
     *
     * @param minX     west edge (inclusive)
     * @param minZ     north edge (inclusive)
     * @param sizeX    width along x
     * @param sizeZ    depth along z
     * @param bottomY  the lowest y kept (the world goes much deeper; most of it is plain stone)
     * @param water    whether the sea is kept
     * @param voxel    blocks per voxel (1 for plain blocks)
     * @param expanded with a voxel above 1: fill each voxel's blocks, rather than one block per voxel
     */
    record Region(int minX, int minZ, int sizeX, int sizeZ, int bottomY, boolean water, int voxel, boolean expanded) {
        Region {
            if (sizeX <= 0 || sizeZ <= 0) throw new IllegalArgumentException("The region is empty");
            if (voxel < 1) throw new IllegalArgumentException("The voxel size is at least 1");
        }

        Region(int minX, int minZ, int sizeX, int sizeZ, int bottomY, boolean water) {
            this(minX, minZ, sizeX, sizeZ, bottomY, water, 1, true);
        }

        static Region of(TerrainState s) {
            return new Region(s.minX(), s.minZ(), s.sizeX(), s.sizeZ(), s.bottomY(), s.water(), s.voxel(), s.expanded());
        }

        /** Roughly how many blocks the result holds at most, to refuse huge bakes up front. */
        long estimate(int worldMinY, int surfaceGuess) {
            long cols = (long) sizeX * sizeZ, depth = Math.max(1, surfaceGuess - Math.max(bottomY, worldMinY));
            return voxel > 1 && !expanded ? cols * depth / ((long) voxel * voxel * voxel) : cols * depth;
        }
    }

    private final Function<String, BlockState> resolve;
    private final Map<String, BlockState> states = new ConcurrentHashMap<>();

    /** @param resolve a block id to its state (for an unknown id, it may throw; stone is used then) */
    Baker(Function<String, BlockState> resolve) {
        this.resolve = resolve;
    }

    private BlockState state(String id) {
        return states.computeIfAbsent(id, k -> {
            try {
                return resolve.apply(k);
            } catch (RuntimeException e) {
                return BlockState.of("stone");
            }
        });
    }

    /**
     * Bakes the region; null when {@code cancelled} turns true. Progress goes from 0 to 1. Fills chunks on the common
     * pool, then copies them in order, so the result is the same however the work was split.
     */
    Structure bake(Generator g, Region r, DoubleConsumer progress, BooleanSupplier cancelled) {
        int v = r.voxel();
        // The world columns to read: every one, or the middle of each voxel.
        List<Integer> xs = new ArrayList<>(), zs = new ArrayList<>();
        for (int x = r.minX(); x < r.minX() + r.sizeX(); x += v) xs.add(v == 1 ? x : x + v / 2);
        for (int z = r.minZ(); z < r.minZ() + r.sizeZ(); z += v) zs.add(v == 1 ? z : z + v / 2);
        TreeSet<Long> keys = new TreeSet<>();
        for (int x : xs) for (int z : zs) keys.add(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
        List<Long> order = new ArrayList<>(keys);
        Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger();
        order.parallelStream().forEach(k -> {
            if (cancelled.getAsBoolean()) return;
            chunks.put(k, g.fillChunk((int) (k >> 32), (int) (long) k));
            progress.accept(0.8 * done.incrementAndGet() / order.size());
        });
        if (cancelled.getAsBoolean()) return null;

        Structure s = new Structure();
        int bottom = Math.max(r.bottomY(), g.world().minY()), top = g.world().maxY();
        String fluid = g.world().defaultFluid();
        for (int i = 0; i < xs.size(); i++) {
            if (cancelled.getAsBoolean()) return null;
            int x = xs.get(i);
            for (int j = 0; j < zs.size(); j++) {
                int z = zs.get(j);
                Chunk c = chunks.get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
                int lx = Math.floorMod(x, 16), lz = Math.floorMod(z, 16);
                if (v == 1) {
                    for (int y = bottom; y <= top; y++) {
                        BlockState st = block(c, lx, y, lz, r.water(), fluid);
                        if (st != null) s.set(x, y, z, st);
                    }
                    continue;
                }
                // Voxel layers whose middle lies in [bottom, top].
                for (int vy = Math.floorDiv(bottom, v); vy * v <= top; vy++) {
                    int sy = vy * v + v / 2;
                    if (sy < bottom || sy > top) continue;
                    BlockState st = block(c, lx, sy, lz, r.water(), fluid);
                    if (st == null) continue;
                    if (!r.expanded()) {
                        s.set(r.minX() + i, vy, r.minZ() + j, st);
                        continue;
                    }
                    int bx = r.minX() + i * v, bz = r.minZ() + j * v;
                    for (int dx = 0; dx < v && bx + dx < r.minX() + r.sizeX(); dx++)
                        for (int dz = 0; dz < v && bz + dz < r.minZ() + r.sizeZ(); dz++)
                            for (int dy = 0; dy < v; dy++)
                                if (vy * v + dy >= bottom) s.set(bx + dx, vy * v + dy, bz + dz, st);
                }
            }
            progress.accept(0.8 + 0.2 * (i + 1) / xs.size());
        }
        return s;
    }

    private BlockState block(Chunk c, int lx, int y, int lz, boolean water, String fluid) {
        int idx = c.index(lx, y, lz);
        if (idx == 0) return null;
        String id = c.palette().get(idx);
        return !water && id.equals(fluid) ? null : state(id);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
