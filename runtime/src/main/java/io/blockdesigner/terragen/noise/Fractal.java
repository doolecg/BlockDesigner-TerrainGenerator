package io.blockdesigner.terragen.noise;

import io.blockdesigner.terragen.Seeds;

import java.util.function.LongFunction;

/**
 * Layers several octaves of a noise at rising frequency and falling amplitude. The result is normalised back to
 * roughly [-1, 1] whatever the octave count, so changing octaves adds detail without changing the overall height.
 */
public final class Fractal implements Noise {
    /** How octaves combine: plain sum (fBm), sharp ridges (1 − |n|, squared) or puffy billows (|n|). */
    public enum Mode { NONE, FBM, RIDGED, BILLOW }

    private final Noise[] octaves;
    private final Mode mode;
    private final double lacunarity, gain, norm;

    /**
     * @param make builds one octave's noise from its seed (each octave gets its own, so they don't line up)
     */
    public Fractal(long seed, LongFunction<Noise> make, Mode mode, int octaves, double lacunarity, double gain) {
        this.mode = mode;
        int n = mode == Mode.NONE ? 1 : Math.max(1, octaves);
        this.octaves = new Noise[n];
        for (int i = 0; i < n; i++) this.octaves[i] = make.apply(Seeds.mix64(seed + i * 0x9E3779B97F4A7C15L));
        this.lacunarity = lacunarity;
        this.gain = gain;
        double sum = 0, amp = 1;
        for (int i = 0; i < n; i++) {
            sum += amp;
            amp *= gain;
        }
        this.norm = sum == 0 ? 1 : 1 / sum;
    }

    @Override
    public double sample(double x, double y, double z) {
        return combine(x, y, z, false);
    }

    @Override
    public double sample2d(double x, double z) {
        return combine(x, 0, z, true);
    }

    private double combine(double x, double y, double z, boolean flat) {
        double sum = 0, amp = 1, freq = 1;
        for (Noise o : octaves) {
            double n = flat ? o.sample2d(x * freq, z * freq) : o.sample(x * freq, y * freq, z * freq);
            switch (mode) {
                case RIDGED -> {
                    double r = 1 - Math.abs(n);
                    n = r * r * 2 - 1;
                }
                case BILLOW -> n = Math.abs(n) * 2 - 1;
                default -> {
                }
            }
            sum += n * amp;
            amp *= gain;
            freq *= lacunarity;
        }
        return sum * norm;
    }
}
