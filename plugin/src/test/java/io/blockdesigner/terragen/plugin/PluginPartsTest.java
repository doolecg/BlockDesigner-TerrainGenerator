package io.blockdesigner.terragen.plugin;

import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.Box;
import io.blockdesigner.core.model.Structure;
import io.blockdesigner.terragen.Chunk;
import io.blockdesigner.terragen.Generator;
import io.blockdesigner.terragen.Presets;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PluginPartsTest {
    private final Baker baker = new Baker(id -> BlockState.parse(id));

    @Test
    void bakesExactlyTheRegionFromTheBottomUp() {
        Generator g = Generator.compile(Presets.get("islands").spec(), 8);
        // Straddles chunk borders on purpose.
        Baker.Region r = new Baker.Region(-10, 5, 40, 20, 30, true);
        // Progress comes from the pool's threads while chunks fill.
        List<Double> steps = java.util.Collections.synchronizedList(new ArrayList<>());
        Structure s = baker.bake(g, r, steps::add, () -> false);
        Box b = s.bounds().orElseThrow();
        assertThat(b.minX()).isEqualTo(-10);
        assertThat(b.maxX()).isEqualTo(29);
        assertThat(b.minZ()).isEqualTo(5);
        assertThat(b.maxZ()).isEqualTo(24);
        assertThat(b.minY()).isEqualTo(30);
        assertThat(steps).isNotEmpty().last().satisfies(p -> assertThat(p).isCloseTo(1.0, within(1e-9)));
        // Same blocks as the chunks themselves.
        Chunk c = g.fillChunk(0, 0);
        for (int y = 30; y < 120; y++) assertThat(s.get(3, y, 7).name()).isEqualTo(c.block(3, y, 7));
    }

    @Test
    void theSeaCanBeLeftOut() {
        Generator g = Generator.compile(Presets.get("islands").spec(), 8);
        Baker.Region withSea = new Baker.Region(0, 0, 64, 64, 40, true), dry = new Baker.Region(0, 0, 64, 64, 40, false);
        Structure a = baker.bake(g, withSea, p -> {
        }, () -> false), b = baker.bake(g, dry, p -> {
        }, () -> false);
        long water = a.stateCounts().entrySet().stream().filter(e -> e.getKey().name().equals("minecraft:water")).mapToLong(e -> e.getValue()).sum();
        assertThat(b.blockCount()).isEqualTo(a.blockCount() - water);
        assertThat(b.usedStates()).noneMatch(st -> st.name().equals("minecraft:water"));
    }

    @Test
    void aCancelledBakeGivesNothing() {
        Generator g = Generator.compile(Presets.get("rolling-hills").spec(), 1);
        assertThat(baker.bake(g, new Baker.Region(0, 0, 64, 64, 60, true), p -> {
        }, () -> true)).isNull();
    }

    @Test
    void theMapIsOpaqueAndShowsWater() {
        MapRenderer m = new MapRenderer(id -> id.contains("water") ? 0x0000FF : 0x777777);
        Generator g = Generator.compile(Presets.get("islands").spec(), 8);
        int[] px = m.render(g, 0, 0, 64, 16, () -> false);
        assertThat(px).hasSize(64 * 64);
        assertThat(java.util.Arrays.stream(px).allMatch(c -> (c >>> 24) == 0xFF)).isTrue();
        // Islands are mostly sea: plenty of blue-tinted pixels.
        long blue = java.util.Arrays.stream(px).filter(c -> (c & 255) > ((c >> 16) & 255) + 40).count();
        assertThat(blue).isGreaterThan(64 * 64 / 4);
        assertThat(m.render(g, 0, 0, 64, 16, () -> true)).isNull();
        // Grass is tinted green, not left grey.
        int grass = m.colour("minecraft:grass_block");
        assertThat((grass >> 8) & 255).isGreaterThan((grass >> 16) & 255);
    }

    @Test
    void slidersMapBothWays() {
        for (boolean log : new boolean[]{false, true}) {
            for (boolean invert : new boolean[]{false, true}) {
                for (double v : new double[]{0.001, 0.004, 0.02}) {
                    double t = Sliders.toSlider(v, 0.001, 0.02, log, invert);
                    assertThat(Sliders.fromSlider(t, 0.001, 0.02, log, invert)).isCloseTo(v, within(1e-12));
                }
            }
        }
        // Inverted: right is the smaller frequency (bigger features).
        assertThat(Sliders.fromSlider(1, 0.001, 0.02, true, true)).isCloseTo(0.001, within(1e-12));
    }

    @Test
    void theJarHoldsThePluginTheRuntimeAndThePresets() throws Exception {
        Path libs = Path.of("build/libs");
        if (!Files.isDirectory(libs)) return; // built by `./gradlew jar`; skipped when only tests ran
        try (var files = Files.list(libs)) {
            Path jar = files.filter(p -> p.getFileName().toString().matches("terrain-generator-.*\\.jar")).findFirst().orElse(null);
            if (jar == null) return;
            try (JarFile j = new JarFile(jar.toFile())) {
                assertThat(j.getEntry("blockdesigner-plugin.json")).isNotNull();
                assertThat(j.getEntry("io/blockdesigner/terragen/plugin/TerrainGeneratorPlugin.class")).isNotNull();
                assertThat(j.getEntry("io/blockdesigner/terragen/Generator.class")).isNotNull();
                assertThat(j.getEntry("io/blockdesigner/terragen/presets/vanilla-like.tgen.json")).isNotNull();
                // Nothing from BlockDesigner itself is bundled.
                assertThat(j.stream().map(e -> e.getName())).noneMatch(n -> n.startsWith("io/blockdesigner/core/") || n.startsWith("io/blockdesigner/plugin/"));
            }
        }
    }
}
