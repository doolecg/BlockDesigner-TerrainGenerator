package io.blockdesigner.terragen.noise;

import io.blockdesigner.terragen.Seeds;

/**
 * Simplex noise (after Stefan Gustavson's reference implementation) in 2D and 3D with a seeded permutation: fewer
 * directional artefacts than Perlin and cheaper in 3D.
 */
public final class Simplex implements Noise {
    private static final int[][] GRAD3 = {{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1}, {1, 0, -1},
            {-1, 0, -1}, {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}};
    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0), G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
    private static final double F3 = 1.0 / 3.0, G3 = 1.0 / 6.0;

    private final short[] perm = new short[512];
    private final short[] permMod12 = new short[512];
    private final double ox, oy, oz;

    public Simplex(long seed) {
        Seeds.SplitMix r = new Seeds.SplitMix(seed);
        ox = r.nextDouble() * 1024;
        oy = r.nextDouble() * 1024;
        oz = r.nextDouble() * 1024;
        short[] p = new short[256];
        for (short i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            short t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = p[i & 255];
            permMod12[i] = (short) (perm[i] % 12);
        }
    }

    @Override
    public double sample2d(double xin, double yin) {
        xin += ox;
        yin += oz;
        double s = (xin + yin) * F2;
        int i = Noise.floor(xin + s), j = Noise.floor(yin + s);
        double t = (i + j) * G2;
        double x0 = xin - (i - t), y0 = yin - (j - t);
        int i1 = x0 > y0 ? 1 : 0, j1 = x0 > y0 ? 0 : 1;
        double x1 = x0 - i1 + G2, y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2, y2 = y0 - 1.0 + 2.0 * G2;
        int ii = i & 255, jj = j & 255;
        double n = corner2(permMod12[ii + perm[jj]], x0, y0)
                + corner2(permMod12[ii + i1 + perm[jj + j1]], x1, y1)
                + corner2(permMod12[ii + 1 + perm[jj + 1]], x2, y2);
        return 70.0 * n;
    }

    private static double corner2(int g, double x, double y) {
        double t = 0.5 - x * x - y * y;
        if (t < 0) return 0;
        t *= t;
        return t * t * (GRAD3[g][0] * x + GRAD3[g][1] * y);
    }

    @Override
    public double sample(double xin, double yin, double zin) {
        xin += ox;
        yin += oy;
        zin += oz;
        double s = (xin + yin + zin) * F3;
        int i = Noise.floor(xin + s), j = Noise.floor(yin + s), k = Noise.floor(zin + s);
        double t = (i + j + k) * G3;
        double x0 = xin - (i - t), y0 = yin - (j - t), z0 = zin - (k - t);
        int i1, j1, k1, i2, j2, k2;
        if (x0 >= y0) {
            if (y0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
            else if (x0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1; }
            else { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1; }
        } else {
            if (y0 < z0) { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1; }
            else if (x0 < z0) { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1; }
            else { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
        }
        double x1 = x0 - i1 + G3, y1 = y0 - j1 + G3, z1 = z0 - k1 + G3;
        double x2 = x0 - i2 + 2.0 * G3, y2 = y0 - j2 + 2.0 * G3, z2 = z0 - k2 + 2.0 * G3;
        double x3 = x0 - 1.0 + 3.0 * G3, y3 = y0 - 1.0 + 3.0 * G3, z3 = z0 - 1.0 + 3.0 * G3;
        int ii = i & 255, jj = j & 255, kk = k & 255;
        double n = corner3(permMod12[ii + perm[jj + perm[kk]]], x0, y0, z0)
                + corner3(permMod12[ii + i1 + perm[jj + j1 + perm[kk + k1]]], x1, y1, z1)
                + corner3(permMod12[ii + i2 + perm[jj + j2 + perm[kk + k2]]], x2, y2, z2)
                + corner3(permMod12[ii + 1 + perm[jj + 1 + perm[kk + 1]]], x3, y3, z3);
        return 32.0 * n;
    }

    private static double corner3(int g, double x, double y, double z) {
        double t = 0.6 - x * x - y * y - z * z;
        if (t < 0) return 0;
        t *= t;
        return t * t * (GRAD3[g][0] * x + GRAD3[g][1] * y + GRAD3[g][2] * z);
    }
}
