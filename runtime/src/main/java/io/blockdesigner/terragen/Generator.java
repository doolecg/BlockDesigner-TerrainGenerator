package io.blockdesigner.terragen;

import io.blockdesigner.terragen.GeneratorSpec.WorldShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A compiled generator: a spec and a seed, ready to fill chunks. Immutable and thread-safe; every value depends only
 * on the spec, the seed and the position, so chunks can be filled in any order on any thread.
 *
 * <p>The graph has a {@code height} output (a height map: solid below it), a {@code density} output (3D: solid where
 * it is above 0, which allows overhangs and caves), or both; with both, the density shapes the blocks and the height
 * is a fast estimate for maps.
 */
public final class Generator {
    private final GeneratorSpec spec;
    private final long seed;
    private final WorldShape world;
    /** The height output, or null. */
    private final Fn height;
    /** The density output, or null when the terrain is the height map alone. */
    private final Fn density;
    private final SurfaceRules surface;
    private final List<Scatter> scatter = new ArrayList<>();
    private final long fingerprint;

    private Generator(GeneratorSpec spec, long seed) {
        this.spec = spec;
        this.seed = seed;
        this.world = spec.world();
        GraphCompiler gc = new GraphCompiler(spec, seed);
        String h = spec.outputs().get("height"), d = spec.outputs().get("density");
        if (h == null && d == null) throw new IllegalArgumentException("The graph needs a \"height\" or \"density\" output");
        for (String out : spec.outputs().keySet()) {
            if (!out.equals("height") && !out.equals("density")) throw new IllegalArgumentException("Unknown output '" + out + "'");
        }
        if (h != null) {
            GraphCompiler.Compiled c = gc.compile(h);
            if (c.usesY()) throw new IllegalArgumentException("The height output can't depend on y (use a density output for 3D shapes)");
            height = c.fn();
        } else {
            height = null;
        }
        density = d == null ? null : gc.compile(d).fn();
        surface = new SurfaceRules(spec.surface(), world.seaLevel(), seed);
        for (int i = 0; i < spec.scatter().size(); i++) scatter.add(new Scatter(spec.scatter().get(i), seed, i));
        fingerprint = Seeds.mix64(Seeds.hash64(spec.toJson()) ^ seed);
    }

    /** Checks and compiles a spec; throws IllegalArgumentException with the node or rule at fault. */
    public static Generator compile(GeneratorSpec spec, long seed) {
        return new Generator(spec, seed);
    }

    public GeneratorSpec spec() {
        return spec;
    }

    public long seed() {
        return seed;
    }

    public WorldShape world() {
        return world;
    }

    /** A hash of the spec and seed: the same for the same terrain, so it can key caches. */
    public long fingerprint() {
        return fingerprint;
    }

    /** Node ids that don't feed any output. */
    public List<String> unusedNodes() {
        return GraphCompiler.unused(spec);
    }

    // ---- solidity ---------------------------------------------------------------------------------------------

    /** The density at a block: above 0 is solid. For a height map, the height minus y. */
    public double density(int x, int y, int z) {
        if (density == null) return height.at(x, y, z) - y;
        if (!world.interpolate()) return density.at(x, y, z);
        int cw = world.cellWidth(), ch = world.cellHeight();
        int x0 = Math.floorDiv(x, cw) * cw, z0 = Math.floorDiv(z, cw) * cw;
        int y0 = world.minY() + Math.floorDiv(y - world.minY(), ch) * ch;
        return trilerp(density.at(x0, y0, z0), density.at(x0 + cw, y0, z0), density.at(x0, y0 + ch, z0), density.at(x0 + cw, y0 + ch, z0),
                density.at(x0, y0, z0 + cw), density.at(x0 + cw, y0, z0 + cw), density.at(x0, y0 + ch, z0 + cw),
                density.at(x0 + cw, y0 + ch, z0 + cw), (x - x0) / (double) cw, (y - y0) / (double) ch, (z - z0) / (double) cw);
    }

    /** Blends a cell's eight corner values; the chunk filler and {@link #density} use this same arithmetic. */
    private static double trilerp(double c000, double c100, double c010, double c110, double c001, double c101, double c011,
                                  double c111, double fx, double fy, double fz) {
        double x00 = c000 + fx * (c100 - c000), x10 = c010 + fx * (c110 - c010);
        double x01 = c001 + fx * (c101 - c001), x11 = c011 + fx * (c111 - c011);
        double y0 = x00 + fy * (x10 - x00), y1 = x01 + fy * (x11 - x01);
        return y0 + fz * (y1 - y0);
    }

    /** The top solid block's y at (x, z), or {@code minY - 1} when the column is empty. Exact. */
    public int surfaceY(int x, int z) {
        if (density == null) return heightTop(height.at(x, 0, z));
        if (!world.interpolate()) {
            for (int y = world.maxY(); y >= world.minY(); y--) if (density.at(x, y, z) > 0) return y;
            return world.minY() - 1;
        }
        // A cell level at a time from the top: four corners per level, the same blend as the chunk filler.
        int cw = world.cellWidth(), ch = world.cellHeight(), minY = world.minY();
        int x0 = Math.floorDiv(x, cw) * cw, z0 = Math.floorDiv(z, cw) * cw;
        double fx = (x - x0) / (double) cw, fz = (z - z0) / (double) cw;
        int levels = world.height() / ch;
        double[] hi = corners(x0, minY + levels * ch, z0, cw);
        for (int iy = levels - 1; iy >= 0; iy--) {
            int yl = minY + iy * ch;
            double[] lo = corners(x0, yl, z0, cw);
            for (int dy = ch - 1; dy >= 0; dy--) {
                if (trilerp(lo[0], lo[1], hi[0], hi[1], lo[2], lo[3], hi[2], hi[3], fx, dy / (double) ch, fz) > 0) return yl + dy;
            }
            hi = lo;
        }
        return minY - 1;
    }

    /** The density at a cell level's four corners: (x0, z0), (x0 + cw, z0), (x0, z0 + cw), (x0 + cw, z0 + cw). */
    private double[] corners(int x0, int y, int z0, int cw) {
        return new double[]{density.at(x0, y, z0), density.at(x0 + cw, y, z0), density.at(x0, y, z0 + cw), density.at(x0 + cw, y, z0 + cw)};
    }

    private int heightTop(double h) {
        int top = (int) Math.ceil(h) - 1;
        return Math.max(world.minY() - 1, Math.min(world.maxY(), top));
    }

    /**
     * A quick surface height for maps: the height output when there is one (for a 3D generator it ignores overhangs),
     * otherwise {@link #surfaceY}.
     */
    public int mapSurfaceY(int x, int z) {
        return height != null ? heightTop(height.at(x, 0, z)) : surfaceY(x, z);
    }

    /** What the top of a column looks like. {@code block} is null when the column is empty. */
    public record Column(int top, String block, boolean underwater) {
    }

    /** The exact top block of a column, as a filled chunk has it before scatter. */
    public Column column(int x, int z) {
        return columnAt(x, z, surfaceY(x, z), true);
    }

    /** The top block of a column from {@link #mapSurfaceY}: fast, for maps. */
    public Column mapColumn(int x, int z) {
        return columnAt(x, z, mapSurfaceY(x, z), false);
    }

    private Column columnAt(int x, int z, int top, boolean exact) {
        if (top < world.minY()) return new Column(top, null, world.seaLevel() > world.minY());
        SurfaceRules.Ctx c = new SurfaceRules.Ctx();
        c.x = x;
        c.z = z;
        c.y = top;
        c.runTop = top;
        c.depth = 0;
        if (surface.usesSlope) {
            int s = 0;
            int[][] ns = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : ns) s = Math.max(s, Math.abs(top - (exact ? surfaceY(x + d[0], z + d[1]) : mapSurfaceY(x + d[0], z + d[1]))));
            c.slope = s;
        }
        String b = surface.pick(c);
        return new Column(top, b != null ? b : world.defaultBlock(), top + 1 < world.seaLevel());
    }

    // ---- chunks -----------------------------------------------------------------------------------------------

    /** Fills the 16 × 16 column of blocks at chunk (cx, cz): terrain, surface, water, then scatter. */
    public Chunk fillChunk(int cx, int cz) {
        int minY = world.minY(), h = world.height(), x0 = cx * 16, z0 = cz * 16;
        // Solidity for the chunk plus a one-block ring around it (the ring gives the edge columns their slopes).
        boolean ring = surface.usesSlope;
        boolean[] solid = new boolean[18 * 18 * h];
        if (density == null) {
            for (int lz = -1; lz <= 16; lz++) {
                for (int lx = -1; lx <= 16; lx++) {
                    if (!ring && (lx < 0 || lx > 15 || lz < 0 || lz > 15)) continue;
                    int top = heightTop(height.at(x0 + lx, 0, z0 + lz));
                    for (int y = minY; y <= top; y++) solid[s18(lx, y - minY, lz)] = true;
                }
            }
        } else if (world.interpolate()) {
            fillInterpolated(solid, x0, z0, ring);
        } else {
            for (int y = minY; y < minY + h; y++)
                for (int lz = -1; lz <= 16; lz++)
                    for (int lx = -1; lx <= 16; lx++)
                        if (ring || (lx >= 0 && lx < 16 && lz >= 0 && lz < 16))
                            solid[s18(lx, y - minY, lz)] = density.at(x0 + lx, y, z0 + lz) > 0;
        }

        int[] tops = new int[18 * 18];
        for (int lz = -1; lz <= 16; lz++) {
            for (int lx = -1; lx <= 16; lx++) {
                int top = minY - 1;
                for (int y = minY + h - 1; y >= minY; y--) {
                    if (solid[s18(lx, y - minY, lz)]) {
                        top = y;
                        break;
                    }
                }
                tops[(lz + 1) * 18 + lx + 1] = top;
            }
        }

        Palette pal = new Palette();
        short[] blocks = new short[16 * 16 * h];
        short fluid = pal.index(world.defaultFluid()), stone = pal.index(world.defaultBlock());
        String[] topBlock = new String[256];
        SurfaceRules.Ctx c = new SurfaceRules.Ctx();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int ti = (lz + 1) * 18 + lx + 1, colTop = tops[ti];
                c.x = x0 + lx;
                c.z = z0 + lz;
                c.slope = ring ? Math.max(Math.max(Math.abs(colTop - tops[ti - 1]), Math.abs(colTop - tops[ti + 1])),
                        Math.max(Math.abs(colTop - tops[ti - 18]), Math.abs(colTop - tops[ti + 18]))) : 0;
                int depth = -1;
                for (int y = minY + h - 1; y >= minY; y--) {
                    int i = ((y - minY) * 16 + lz) * 16 + lx;
                    if (solid[s18(lx, y - minY, lz)]) {
                        if (depth < 0) c.runTop = y;
                        depth++;
                        c.y = y;
                        c.depth = depth;
                        String b = surface.pick(c);
                        blocks[i] = b == null ? stone : pal.index(b);
                        if (y == colTop) topBlock[lz * 16 + lx] = b == null ? world.defaultBlock() : b;
                    } else {
                        depth = -1;
                        if (y < world.seaLevel()) blocks[i] = fluid;
                    }
                }
            }
        }

        // Scatter, from every candidate that can reach this chunk, in one fixed order so overlaps resolve the same way
        // whichever chunk is filled.
        int r = Scatter.MAX_RADIUS;
        for (Scatter s : scatter) {
            for (int gx = Math.floorDiv(x0 - r, s.spacing); gx <= Math.floorDiv(x0 + 15 + r, s.spacing); gx++) {
                for (int gz = Math.floorDiv(z0 - r, s.spacing); gz <= Math.floorDiv(z0 + 15 + r, s.spacing); gz++) {
                    int[] p = s.candidate(gx, gz);
                    if (p == null || p[0] < x0 - r || p[0] > x0 + 15 + r || p[1] < z0 - r || p[1] > z0 + 15 + r) continue;
                    int lx = p[0] - x0, lz = p[1] - z0, top;
                    String block;
                    if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16) {
                        top = tops[(lz + 1) * 18 + lx + 1];
                        block = topBlock[lz * 16 + lx];
                    } else {
                        Column col = column(p[0], p[1]);
                        top = col.top();
                        block = col.block();
                    }
                    if (block == null || top + 1 < world.seaLevel() || top + 1 > world.maxY()) continue;
                    if (!s.on.isEmpty() && !s.on.contains(block)) continue;
                    s.place(p[0], top + 1, p[1], (x, y, z, b) -> {
                        int bx = x - x0, bz = z - z0;
                        if (bx < 0 || bx > 15 || bz < 0 || bz > 15 || y < minY || y >= minY + h) return;
                        int i = ((y - minY) * 16 + bz) * 16 + bx;
                        if (blocks[i] == 0) blocks[i] = pal.index(b);
                    });
                }
            }
        }
        return new Chunk(cx, cz, minY, h, blocks, pal.names);
    }

    /** Index into the 18 × 18 solidity grid (the chunk plus a ring), local x and z from -1 to 16. */
    private static int s18(int lx, int yi, int lz) {
        return (yi * 18 + lz + 1) * 18 + lx + 1;
    }

    /**
     * Solid cells from density sampled at cell corners and blended in between, for the chunk and (with {@code ring})
     * the columns just around it, which belong to the neighbouring cells.
     */
    private void fillInterpolated(boolean[] solid, int x0, int z0, boolean ring) {
        int cw = world.cellWidth(), ch = world.cellHeight(), minY = world.minY(), h = world.height();
        // Cells from index c0 to c1 (inclusive) along x and z; with the ring, one more cell on each side.
        int c0 = ring ? -1 : 0, c1 = ring ? 16 / cw : 16 / cw - 1, n = c1 - c0 + 2, ny = h / ch + 1;
        double[] corner = new double[n * n * ny];
        for (int ix = 0; ix < n; ix++)
            for (int iz = 0; iz < n; iz++)
                for (int iy = 0; iy < ny; iy++)
                    corner[(ix * n + iz) * ny + iy] = density.at(x0 + (ix + c0) * cw, minY + iy * ch, z0 + (iz + c0) * cw);
        for (int ix = 0; ix < n - 1; ix++) {
            for (int iz = 0; iz < n - 1; iz++) {
                for (int iy = 0; iy < ny - 1; iy++) {
                    double c000 = corner[(ix * n + iz) * ny + iy], c100 = corner[((ix + 1) * n + iz) * ny + iy];
                    double c010 = corner[(ix * n + iz) * ny + iy + 1], c110 = corner[((ix + 1) * n + iz) * ny + iy + 1];
                    double c001 = corner[(ix * n + iz + 1) * ny + iy], c101 = corner[((ix + 1) * n + iz + 1) * ny + iy];
                    double c011 = corner[(ix * n + iz + 1) * ny + iy + 1], c111 = corner[((ix + 1) * n + iz + 1) * ny + iy + 1];
                    for (int dz = 0; dz < cw; dz++) {
                        int lz = (iz + c0) * cw + dz;
                        if (lz < -1 || lz > 16) continue;
                        for (int dx = 0; dx < cw; dx++) {
                            int lx = (ix + c0) * cw + dx;
                            if (lx < -1 || lx > 16) continue;
                            for (int dy = 0; dy < ch; dy++) {
                                double d = trilerp(c000, c100, c010, c110, c001, c101, c011, c111, dx / (double) cw, dy / (double) ch, dz / (double) cw);
                                if (d > 0) solid[s18(lx, iy * ch + dy, lz)] = true;
                            }
                        }
                    }
                }
            }
        }
    }

    /** Block ids to palette indices, air first. */
    private static final class Palette {
        final List<String> names = new ArrayList<>(List.of(Chunk.AIR));
        final Map<String, Short> index = new HashMap<>(Map.of(Chunk.AIR, (short) 0));

        short index(String name) {
            Short i = index.get(name);
            if (i != null) return i;
            short n = (short) names.size();
            names.add(name);
            index.put(name, n);
            return n;
        }
    }
}
