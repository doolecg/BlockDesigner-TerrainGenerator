package io.blockdesigner.terragen;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

/** Prints golden hashes, timings and a block census per preset. Run by hand: ./gradlew :runtime:test -Dprobe=true. */
@Tag("probe")
class ProbeTest {
    @Test
    void probe() {
        if (!Boolean.getBoolean("probe")) return;
        for (Presets.Preset p : Presets.all()) {
            Generator g = Generator.compile(p.spec(), 12345);
            StringBuilder hashes = new StringBuilder();
            for (int i = 0; i < 4; i++) hashes.append(Long.toHexString(g.fillChunk(i * 7 - 10, i * 5 - 3).contentHash())).append("L, ");
            long t = System.nanoTime();
            int n = 64;
            Map<String, Integer> census = new TreeMap<>();
            int minTop = Integer.MAX_VALUE, maxTop = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                Chunk c = g.fillChunk(i % 8, i / 8);
                for (int lx = 0; lx < 16; lx += 5) for (int lz = 0; lz < 16; lz += 5) {
                    for (int y = c.minY; y < c.minY + c.height; y++) {
                        String b = c.block(lx, y, lz);
                        if (!b.equals(Chunk.AIR)) census.merge(b, 1, Integer::sum);
                    }
                }
            }
            double ms = (System.nanoTime() - t) / 1e6 / n;
            for (int x = -2000; x < 2000; x += 97) for (int z = -2000; z < 2000; z += 89) {
                int top = g.mapSurfaceY(x, z);
                minTop = Math.min(minTop, top);
                maxTop = Math.max(maxTop, top);
            }
            System.out.printf("%s: %.2f ms/chunk, tops %d..%d%n  golden %s%n  %s%n", p.id(), ms, minTop, maxTop, hashes, census);
        }
    }
}
