package io.blockdesigner.terragen.noise;

import io.blockdesigner.terragen.Seeds;

/** Value noise: a random value at each lattice point, smoothly interpolated. Blobby, cheap, good for masks. */
public final class ValueNoise implements Noise {
    private final long seed;

    public ValueNoise(long seed) {
        this.seed = seed;
    }

    private double at(int x, int y, int z) {
        return Seeds.unit(Seeds.hash(seed, x, y, z)) * 2 - 1;
    }

    @Override
    public double sample(double x, double y, double z) {
        int xi = Noise.floor(x), yi = Noise.floor(y), zi = Noise.floor(z);
        double u = Noise.fade(x - xi), v = Noise.fade(y - yi), w = Noise.fade(z - zi);
        return Noise.lerp(w,
                Noise.lerp(v, Noise.lerp(u, at(xi, yi, zi), at(xi + 1, yi, zi)), Noise.lerp(u, at(xi, yi + 1, zi), at(xi + 1, yi + 1, zi))),
                Noise.lerp(v, Noise.lerp(u, at(xi, yi, zi + 1), at(xi + 1, yi, zi + 1)),
                        Noise.lerp(u, at(xi, yi + 1, zi + 1), at(xi + 1, yi + 1, zi + 1))));
    }
}
