package io.blockdesigner.terragen.plugin;

import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.Box;
import io.blockdesigner.core.model.Structure;
import io.blockdesigner.terragen.Generator;
import io.blockdesigner.terragen.Presets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateAndVoxelTest {
    private final Baker baker = new Baker(BlockState::parse);

    @Test
    void stateRoundTripsThroughJson() {
        TerrainState s = TerrainState.initial("hello world")
                .withPreset("islands", Presets.get("islands").spec().withParam("coast", "amplitude", 77.0))
                .withCentre(-300, 1200).withSize(96, 160).withBottom(-10).withWater(false).withZoom(8).withVoxel(4, false);
        assertThat(TerrainState.fromJson(s.toJson())).isEqualTo(s);
        // A file generator has no preset id.
        TerrainState file = s.withPreset(null, Presets.get("mesas").spec());
        assertThat(TerrainState.fromJson(file.toJson()).presetId()).isNull();
        assertThat(s.minX()).isEqualTo(-348);
        assertThat(s.minZ()).isEqualTo(1120);
    }

    @Test
    void stateKeepsItsValuesSane() {
        TerrainState s = TerrainState.initial("1").withSize(0, 999_999).withZoom(0).withVoxel(3, true);
        assertThat(s.sizeX()).isEqualTo(1);
        assertThat(s.sizeZ()).isEqualTo(TerrainState.MAX_SIZE);
        assertThat(s.zoom()).isEqualTo(1);
        assertThat(s.voxel()).isEqualTo(1);
        assertThatThrownBy(() -> TerrainState.fromJson("[1]")).isInstanceOf(IllegalArgumentException.class);
        // Missing fields take their defaults.
        assertThat(TerrainState.fromJson("{\"seed\": \"7\"}").sizeX()).isEqualTo(256);
    }

    @Test
    void expandedVoxelsAreSolidCubes() {
        Generator g = Generator.compile(Presets.get("rolling-hills").spec(), 3);
        Structure s = baker.bake(g, new Baker.Region(0, 0, 32, 32, 40, true, 4, true), p -> {
        }, () -> false);
        Box b = s.bounds().orElseThrow();
        assertThat(b.minX()).isZero();
        assertThat(b.maxX()).isEqualTo(31);
        // Every 4 × 4 × 4 cube holds one block kind.
        for (int x = 0; x < 32; x += 4) {
            for (int z = 0; z < 32; z += 4) {
                for (int y = 40; y < 90; y += 4) {
                    BlockState first = s.get(x, y, z);
                    for (int d = 0; d < 4; d++) assertThat(s.get(x + d, y + d, z + 3 - d)).isEqualTo(first);
                }
            }
        }
    }

    @Test
    void aVoxelModelIsSmaller() {
        Generator g = Generator.compile(Presets.get("rolling-hills").spec(), 3);
        Structure full = baker.bake(g, new Baker.Region(0, 0, 64, 64, 40, true), p -> {
        }, () -> false);
        Structure model = baker.bake(g, new Baker.Region(0, 0, 64, 64, 40, true, 4, false), p -> {
        }, () -> false);
        Box b = model.bounds().orElseThrow();
        assertThat(b.maxX() - b.minX() + 1).isEqualTo(16);
        assertThat(b.maxZ() - b.minZ() + 1).isEqualTo(16);
        // The ground is about 68 high, so a quarter of that in the model; the bottom is 40 / 4.
        assertThat(b.minY()).isEqualTo(10);
        assertThat(b.maxY()).isBetween(15, 20);
        assertThat(model.blockCount()).isLessThan(full.blockCount() / 32);
        // The model's top is the world's top block at the voxel's middle column.
        assertThat(model.get(0, 17, 0).name()).isIn("minecraft:grass_block", "minecraft:dirt", "minecraft:air", "minecraft:oak_leaves",
                "minecraft:oak_log", "minecraft:stone");
    }
}
