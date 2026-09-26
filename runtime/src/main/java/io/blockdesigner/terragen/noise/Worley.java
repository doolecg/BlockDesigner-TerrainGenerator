package io.blockdesigner.terragen.noise;

import io.blockdesigner.terragen.Seeds;

/**
 * Worley (cellular) noise: one random point per lattice cell; the value is the distance to the nearest point (F1), the
 * second nearest (F2) or their difference (F2 − F1, which draws cell walls). Distances are in lattice units, mapped to
 * [-1, 1] so it mixes with the other noises.
 */
public final class Worley implements Noise {
    public enum Mode { F1, F2, F2_MINUS_F1 }

    private final long seed;
    private final Mode mode;
    private final boolean flat;

    /** @param flat true for 2D cells (points on the y = 0 plane), which is what most terrain uses */
    public Worley(long seed, Mode mode, boolean flat) {
        this.seed = seed;
        this.mode = mode;
        this.flat = flat;
    }

    @Override
    public double sample(double x, double y, double z) {
        if (flat) y = 0;
        int xi = Noise.floor(x), yi = Noise.floor(y), zi = Noise.floor(z);
        double f1 = Double.MAX_VALUE, f2 = Double.MAX_VALUE;
        int yr = flat ? 0 : 1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -yr; dy <= yr; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = xi + dx, cy = yi + dy, cz = zi + dz;
                    long h = Seeds.hash(seed, cx, cy, cz);
                    double px = cx + Seeds.unit(h), pz = cz + Seeds.unit(Seeds.mix64(h ^ 0x51L));
                    double py = flat ? 0 : cy + Seeds.unit(Seeds.mix64(h ^ 0xA3L));
                    double ddx = px - x, ddy = py - y, ddz = pz - z;
                    double d = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                    if (d < f1) {
                        f2 = f1;
                        f1 = d;
                    } else if (d < f2) {
                        f2 = d;
                    }
                }
            }
        }
        double v = switch (mode) {
            case F1 -> f1;
            case F2 -> f2;
            case F2_MINUS_F1 -> f2 - f1;
        };
        // Distances rarely pass about 1.0 in lattice units: map [0, 1] onto [-1, 1], clamped for the rare far ones.
        return Math.min(1, v * 2 - 1);
    }
}
