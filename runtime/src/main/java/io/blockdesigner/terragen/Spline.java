package io.blockdesigner.terragen;

import java.util.ArrayList;
import java.util.List;

/**
 * A smooth curve through points (x, y), monotone between them (Fritsch–Carlson), so it never overshoots: a height
 * curve rising from 60 to 70 stays between 60 and 70. Flat beyond the first and last point.
 */
public final class Spline {
    private final double[] xs, ys, ms;

    private Spline(double[] xs, double[] ys) {
        this.xs = xs;
        this.ys = ys;
        int n = xs.length;
        ms = new double[n];
        if (n < 2) return;
        double[] d = new double[n - 1];
        for (int i = 0; i < n - 1; i++) d[i] = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]);
        ms[0] = d[0];
        ms[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) ms[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0) {
                ms[i] = 0;
                ms[i + 1] = 0;
                continue;
            }
            double a = ms[i] / d[i], b = ms[i + 1] / d[i], h = a * a + b * b;
            if (h > 9) {
                double t = 3 / Math.sqrt(h);
                ms[i] = t * a * d[i];
                ms[i + 1] = t * b * d[i];
            }
        }
    }

    /** From a JSON list of [x, y] pairs, at least two, with x rising. */
    public static Spline of(Object points) {
        if (!(points instanceof List<?> l) || l.size() < 2) throw new IllegalArgumentException("points needs at least two [x, y] pairs");
        List<double[]> ps = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof List<?> p) || p.size() != 2 || !(p.get(0) instanceof Number x) || !(p.get(1) instanceof Number y)) {
                throw new IllegalArgumentException("each point is an [x, y] pair of numbers");
            }
            ps.add(new double[]{x.doubleValue(), y.doubleValue()});
        }
        double[] xs = new double[ps.size()], ys = new double[ps.size()];
        for (int i = 0; i < ps.size(); i++) {
            xs[i] = ps.get(i)[0];
            ys[i] = ps.get(i)[1];
            if (i > 0 && xs[i] <= xs[i - 1]) throw new IllegalArgumentException("point x values must rise");
        }
        return new Spline(xs, ys);
    }

    public double at(double x) {
        int n = xs.length;
        if (x <= xs[0]) return ys[0];
        if (x >= xs[n - 1]) return ys[n - 1];
        int i = 0;
        while (x > xs[i + 1]) i++;
        double h = xs[i + 1] - xs[i], t = (x - xs[i]) / h, t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * ms[i] + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * ms[i + 1];
    }
}
