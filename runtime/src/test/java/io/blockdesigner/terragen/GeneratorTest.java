package io.blockdesigner.terragen;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorTest {
    /**
     * Chunk hashes per preset for seed 12345, chunks (-10, -3), (-3, 2), (4, 7), (11, 12). If one changes, the
     * terrain changed: fine when a preset or the generator was meant to change (update the numbers and say so in the
     * release notes), a bug otherwise. Also run with -Xint (testInterpreted), so the JIT can't change them.
     */
    private static final Map<String, long[]> GOLDEN = Map.of(
            "vanilla-like", new long[]{0x8121e8b0971a0ea4L, 0xad6777b80c765c28L, 0x947ecbc781ae23a6L, 0xde77154f96d470baL},
            "islands", new long[]{0xfd4d60f20871065fL, 0x4304cd7b2f38dfd7L, 0xbdce4241c0e5823bL, 0x86cbd97688feb8c8L},
            "mesas", new long[]{0xa8056dea26daded9L, 0x00aec46965e97eecL, 0x848940ce5cb59a65L, 0x26f73108381733cdL},
            "rolling-hills", new long[]{0xa77d38e6dd911af7L, 0x6a99523fdc33d2c7L, 0xd01cfd6c4e2ae309L, 0x8fb59aea8053d309L});

    @Test
    @Tag("determinism")
    void presetsMatchTheirGoldenHashes() {
        assertThat(Presets.all()).extracting(Presets.Preset::id).containsExactlyInAnyOrderElementsOf(GOLDEN.keySet());
        for (Presets.Preset p : Presets.all()) {
            Generator g = Generator.compile(p.spec(), 12345);
            long[] want = GOLDEN.get(p.id());
            for (int i = 0; i < 4; i++) {
                assertThat(g.fillChunk(i * 7 - 10, i * 5 - 3).contentHash()).as(p.id() + " chunk " + i).isEqualTo(want[i]);
            }
        }
    }

    @Test
    @Tag("determinism")
    void chunkOrderAndThreadsDontMatter() throws Exception {
        Generator g = Generator.compile(Presets.get("vanilla-like").spec(), 777);
        List<int[]> coords = new ArrayList<>();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) coords.add(new int[]{x, z});
        long[] sequential = new long[coords.size()];
        for (int i = 0; i < coords.size(); i++) sequential[i] = g.fillChunk(coords.get(i)[0], coords.get(i)[1]).contentHash();

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < coords.size(); i++) order.add(i);
        Collections.shuffle(order, new java.util.Random(1));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            long[] parallel = new long[coords.size()];
            List<Future<?>> fs = new ArrayList<>();
            for (int i : order) fs.add(pool.submit(() -> parallel[i] = g.fillChunk(coords.get(i)[0], coords.get(i)[1]).contentHash()));
            for (Future<?> f : fs) f.get();
            assertThat(parallel).containsExactly(sequential);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void theSameSeedGivesTheSameTerrainAndAnotherSeedDoesnt() {
        GeneratorSpec spec = Presets.get("islands").spec();
        assertThat(Generator.compile(spec, 5).fillChunk(2, 3).contentHash()).isEqualTo(Generator.compile(spec, 5).fillChunk(2, 3).contentHash());
        assertThat(Generator.compile(spec, 5).fillChunk(2, 3).contentHash()).isNotEqualTo(Generator.compile(spec, 6).fillChunk(2, 3).contentHash());
        assertThat(Generator.compile(spec, 5).fingerprint()).isNotEqualTo(Generator.compile(spec, 6).fingerprint());
    }

    @Test
    void singleColumnsAgreeWithFilledChunks() {
        for (Presets.Preset p : Presets.all()) {
            Generator g = Generator.compile(p.spec(), 99);
            Chunk c = g.fillChunk(3, -2);
            for (int lx = 0; lx < 16; lx += 3) {
                for (int lz = 0; lz < 16; lz += 3) {
                    int x = 48 + lx, z = -32 + lz, top = g.surfaceY(x, z);
                    // The top block is solid ground (a tree or boulder may sit on it, never replace it).
                    assertThat(c.block(lx, top, lz)).as(p.id() + " top at " + x + "," + z).isEqualTo(g.column(x, z).block());
                    assertThat(g.density(x, top, z)).as(p.id()).isGreaterThan(0);
                    if (top < g.world().maxY()) assertThat(g.density(x, top + 1, z)).as(p.id()).isLessThanOrEqualTo(0);
                }
            }
        }
    }

    @Test
    void theSeaFillsBelowSeaLevel() {
        Generator g = Generator.compile(Presets.get("islands").spec(), 3);
        // Find an ocean column and check the water column above its floor.
        for (int x = 0; x < 4000; x += 37) {
            int top = g.surfaceY(x, 0);
            if (top < 50) {
                Chunk c = g.fillChunk(Math.floorDiv(x, 16), 0);
                int lx = Math.floorMod(x, 16);
                assertThat(c.block(lx, top + 1, 0)).isEqualTo("minecraft:water");
                assertThat(c.block(lx, 62, 0)).isEqualTo("minecraft:water");
                assertThat(c.block(lx, 63, 0)).isEqualTo(Chunk.AIR);
                return;
            }
        }
        throw new AssertionError("no ocean found");
    }

    @Test
    void treesCrossChunkBordersWhole() {
        Generator g = Generator.compile(Presets.get("rolling-hills").spec(), 42);
        // A trunk in the last two columns of a chunk has its canopy (2 wide, one below the top) in the next chunk.
        int found = 0;
        for (int cx = 0; cx < 60 && found < 5; cx++) {
            Chunk c = g.fillChunk(cx, 0);
            for (int lx = 14; lx < 16; lx++) {
                for (int lz = 2; lz < 14; lz++) {
                    int top = -1;
                    for (int y = 60; y < 90; y++) if (c.block(lx, y, lz).equals("minecraft:oak_log")) top = y;
                    if (top < 0 || !c.block(lx, top + 1, lz).equals("minecraft:oak_leaves")) continue;
                    Chunk next = g.fillChunk(cx + 1, 0);
                    assertThat(next.block(lx + 2 - 16, top - 1, lz)).as("canopy across the border at chunk " + cx)
                            .isIn("minecraft:oak_leaves", "minecraft:oak_log");
                    found++;
                }
            }
        }
        assertThat(found).as("trees near the chunk edge to check").isPositive();
    }
}
