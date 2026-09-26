package io.blockdesigner.terragen.noise;

/** A seeded noise field. Values are roughly in [-1, 1] unless a noise says otherwise. Implementations are immutable. */
public interface Noise {
    double sample(double x, double y, double z);

    /** The noise on the plane y = 0. */
    default double sample2d(double x, double z) {
        return sample(x, 0, z);
    }

    /** Quintic fade (6t⁵ − 15t⁴ + 10t³): smooth lattice interpolation with no creases. */
    static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /** Floor as an int, exact for the coordinate ranges a world uses. */
    static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
