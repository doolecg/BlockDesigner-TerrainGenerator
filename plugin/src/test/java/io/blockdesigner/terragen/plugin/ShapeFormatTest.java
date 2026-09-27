package io.blockdesigner.terragen.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Slider values as the Terrain page shows them. */
class ShapeFormatTest {
    @Test
    void frequenciesReadAsFeatureSizes() {
        assertThat(ShapeFormat.value("frequency", 0.00067, 0.004)).isEqualTo("≈ 1,490 blocks");
        assertThat(ShapeFormat.value("frequency", 0.01, 0.02)).isEqualTo("≈ 100 blocks");
        assertThat(ShapeFormat.value("frequency", 0.0002, 0.004)).isEqualTo("≈ 5,000 blocks");
        assertThat(ShapeFormat.value("frequency", 0.05, 0.05)).isEqualTo("≈ 20 blocks");
        assertThat(ShapeFormat.value("frequency", 0, 1)).isEqualTo("flat");
    }

    @Test
    void heightsLevelsAndPlainNumbers() {
        assertThat(ShapeFormat.value("amplitude", 40, 40)).isEqualTo("40 blocks");
        assertThat(ShapeFormat.value("factor", 220, 220)).isEqualTo("220 blocks");
        assertThat(ShapeFormat.value("factor", 0.45, 1)).as("a factor, not a height").isEqualTo("0.45");
        assertThat(ShapeFormat.value("offset", 64.4, 140)).isEqualTo("Y 64");
        assertThat(ShapeFormat.value("smoothness", 0.5, 1)).isEqualTo("0.50");
        assertThat(ShapeFormat.value("smoothness", 1, 1)).isEqualTo("1");
    }
}
