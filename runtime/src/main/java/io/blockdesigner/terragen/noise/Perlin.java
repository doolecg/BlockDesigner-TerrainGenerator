package io.blockdesigner.terragen.noise;

import io.blockdesigner.terragen.Seeds;

/**
 * Improved Perlin noise (Perlin 2002): gradient noise on the integer lattice with a seeded permutation and a random
 * offset, so the lattice never lines up with the world grid.
 */
public final class Perlin implements Noise {
    private final int[] perm = new int[512];
    private final double ox, oy, oz;

    public Perlin(long seed) {
        Seeds.SplitMix r = new Seeds.SplitMix(seed);
        ox = r.nextDouble() * 256;
        oy = r.nextDouble() * 256;
        oz = r.nextDouble() * 256;
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    @Override
    public double sample(double x, double y, double z) {
        x += ox;
        y += oy;
        z += oz;
        int xi = Noise.floor(x), yi = Noise.floor(y), zi = Noise.floor(z);
        double xf = x - xi, yf = y - yi, zf = z - zi;
        int X = xi & 255, Y = yi & 255, Z = zi & 255;
        double u = Noise.fade(xf), v = Noise.fade(yf), w = Noise.fade(zf);
        int a = perm[X] + Y, aa = perm[a] + Z, ab = perm[a + 1] + Z;
        int b = perm[X + 1] + Y, ba = perm[b] + Z, bb = perm[b + 1] + Z;
        return Noise.lerp(w,
                Noise.lerp(v, Noise.lerp(u, grad(perm[aa], xf, yf, zf), grad(perm[ba], xf - 1, yf, zf)),
                        Noise.lerp(u, grad(perm[ab], xf, yf - 1, zf), grad(perm[bb], xf - 1, yf - 1, zf))),
                Noise.lerp(v, Noise.lerp(u, grad(perm[aa + 1], xf, yf, zf - 1), grad(perm[ba + 1], xf - 1, yf, zf - 1)),
                        Noise.lerp(u, grad(perm[ab + 1], xf, yf - 1, zf - 1), grad(perm[bb + 1], xf - 1, yf - 1, zf - 1))));
    }

    /** Dot product with one of the 12 edge gradients of a cube. */
    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
