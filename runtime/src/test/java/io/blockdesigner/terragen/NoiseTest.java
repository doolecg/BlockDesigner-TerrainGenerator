package io.blockdesigner.terragen;

import io.blockdesigner.terragen.noise.Fractal;
import io.blockdesigner.terragen.noise.Noise;
import io.blockdesigner.terragen.noise.Perlin;
import io.blockdesigner.terragen.noise.Simplex;
import io.blockdesigner.terragen.noise.ValueNoise;
import io.blockdesigner.terragen.noise.Worley;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NoiseTest {
    private static List<Noise> all(long seed) {
        return List.of(new Perlin(seed), new Simplex(seed), new ValueNoise(seed), new Worley(seed, Worley.Mode.F1, false),
                new Worley(seed, Worley.Mode.F2_MINUS_F1, true), new Fractal(seed, Perlin::new, Fractal.Mode.FBM, 5, 2, 0.5),
                new Fractal(seed, Simplex::new, Fractal.Mode.RIDGED, 4, 2, 0.5), new Fractal(seed, Perlin::new, Fractal.Mode.BILLOW, 3, 2, 0.5));
    }

    @Test
    void staysInRangeAndVaries() {
        for (Noise n : all(9)) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < 20000; i++) {
                double x = i * 0.371, y = i * 0.113 % 50, z = i * 0.577 % 300;
                double v = n.sample(x, y, z), v2 = n.sample2d(x, z);
                lo = Math.min(lo, Math.min(v, v2));
                hi = Math.max(hi, Math.max(v, v2));
            }
            assertThat(lo).as(n.getClass().getSimpleName()).isGreaterThanOrEqualTo(-1.3);
            assertThat(hi).as(n.getClass().getSimpleName()).isLessThanOrEqualTo(1.3);
            assertThat(hi - lo).as(n.getClass().getSimpleName() + " varies").isGreaterThan(0.5);
        }
    }

    @Test
    void isContinuousAcrossLatticeLines() {
        // Worley's F2 − F1 has creases at cell walls by design; the rest are smooth.
        for (Noise n : List.of(new Perlin(3), new Simplex(3), new ValueNoise(3), new Fractal(3, Simplex::new, Fractal.Mode.FBM, 4, 2, 0.5))) {
            for (int k = -20; k <= 20; k++) {
                assertThat(n.sample(k - 1e-7, 0.3, 0.7)).as(n.getClass().getSimpleName()).isCloseTo(n.sample(k + 1e-7, 0.3, 0.7), within(1e-4));
                assertThat(n.sample2d(0.4, k - 1e-7)).isCloseTo(n.sample2d(0.4, k + 1e-7), within(1e-4));
            }
        }
    }

    @Test
    void seedsGiveDifferentFields() {
        assertThat(new Perlin(1).sample(10.5, 3.3, 7.1)).isNotEqualTo(new Perlin(2).sample(10.5, 3.3, 7.1));
        assertThat(new Simplex(1).sample2d(10.5, 7.1)).isNotEqualTo(new Simplex(2).sample2d(10.5, 7.1));
        assertThat(new Perlin(1).sample(10.5, 3.3, 7.1)).isEqualTo(new Perlin(1).sample(10.5, 3.3, 7.1));
    }

    @Test
    void splinePassesThroughItsPointsWithoutOvershoot() {
        Spline s = Spline.of(List.of(List.of(-1.0, 0.0), List.of(0.0, 10.0), List.of(0.5, 10.0), List.of(1.0, 40.0)));
        assertThat(s.at(-2)).isEqualTo(0.0);
        assertThat(s.at(0)).isCloseTo(10.0, within(1e-9));
        assertThat(s.at(2)).isEqualTo(40.0);
        for (double x = 0; x <= 0.5; x += 0.01) assertThat(s.at(x)).isCloseTo(10.0, within(1e-9));
        for (double x = -1; x <= 1; x += 0.01) assertThat(s.at(x)).isBetween(0.0, 40.0);
    }
}
