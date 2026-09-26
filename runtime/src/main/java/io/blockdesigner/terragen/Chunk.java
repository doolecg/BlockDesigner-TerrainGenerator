package io.blockdesigner.terragen;

import java.util.List;

/**
 * The blocks of one 16 × 16 column of the world, from {@code minY} up, as indices into a palette of block ids
 * ({@code palette().get(0)} is always {@code minecraft:air}).
 */
public final class Chunk {
    public static final String AIR = "minecraft:air";

    public final int cx, cz, minY, height;
    private final short[] blocks;
    private final List<String> palette;

    Chunk(int cx, int cz, int minY, int height, short[] blocks, List<String> palette) {
        this.cx = cx;
        this.cz = cz;
        this.minY = minY;
        this.height = height;
        this.blocks = blocks;
        this.palette = List.copyOf(palette);
    }

    public List<String> palette() {
        return palette;
    }

    /** The palette index at local x, world y, local z (0 is air). */
    public int index(int lx, int y, int lz) {
        return blocks[((y - minY) * 16 + lz) * 16 + lx];
    }

    public String block(int lx, int y, int lz) {
        return palette.get(index(lx, y, lz));
    }

    /** A hash of the contents (palette names, not indices), for tests that check nothing changed. */
    public long contentHash() {
        long h = Seeds.mix64(cx * 31L + cz);
        for (int i = 0; i < blocks.length; i++) {
            h = Seeds.mix64(h ^ Seeds.hash64(palette.get(blocks[i])) ^ i);
        }
        return h;
    }
}
