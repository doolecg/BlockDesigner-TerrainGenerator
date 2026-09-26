package io.blockdesigner.terragen;

import io.blockdesigner.terragen.GeneratorSpec.Node;
import io.blockdesigner.terragen.noise.Fractal;
import io.blockdesigner.terragen.noise.Noise;
import io.blockdesigner.terragen.noise.Perlin;
import io.blockdesigner.terragen.noise.Simplex;
import io.blockdesigner.terragen.noise.ValueNoise;
import io.blockdesigner.terragen.noise.Worley;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a spec's node graph into {@link Fn}s: checks types, inputs and cycles, and gives every node its seed. A node
 * that doesn't depend on y is marked flat (2D), and flat values feeding 3D nodes are cached per column, so a height
 * map under a 3D density is computed once per column rather than once per block.
 */
final class GraphCompiler {
    /** A compiled node and whether it depends on y. */
    record Compiled(Fn fn, boolean usesY) {
    }

    /** Every node type, for the panel and for error messages. */
    static final List<String> TYPES = List.of("input.x", "input.y", "input.z", "input.constant", "input.y_gradient",
            "noise.perlin", "noise.simplex", "noise.value", "noise.worley", "warp.domain",
            "math.add", "math.sub", "math.mul", "math.min", "math.max", "math.scale", "math.abs", "math.negate", "math.clamp",
            "math.remap", "math.lerp", "curve.spline", "shape.terraces", "shape.height_to_density");

    private final GeneratorSpec spec;
    private final long seed;
    private final Map<String, Compiled> done = new HashMap<>();
    private final Set<String> visiting = new HashSet<>();

    GraphCompiler(GeneratorSpec spec, long seed) {
        this.spec = spec;
        this.seed = seed;
    }

    Compiled compile(String id) {
        Compiled c = done.get(id);
        if (c != null) return c;
        Node n = spec.nodes().get(id);
        if (n == null) throw new IllegalArgumentException("There is no node '" + id + "'");
        if (!visiting.add(id)) throw new IllegalArgumentException("Node '" + id + "' feeds into itself (a cycle)");
        try {
            c = build(n);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Node '")) throw e;
            throw new IllegalArgumentException("Node '" + id + "' (" + n.type() + "): " + e.getMessage(), e);
        }
        visiting.remove(id);
        done.put(id, c);
        return c;
    }

    private Compiled in(Node n, String name) {
        String ref = n.inputs().get(name);
        if (ref == null) throw new IllegalArgumentException("Node '" + n.id() + "' (" + n.type() + ") needs its input '" + name + "'");
        return compile(ref);
    }

    private Compiled build(Node n) {
        long s = Seeds.nodeSeed(seed, n.salt());
        switch (n.type()) {
            case "input.x": {
                double k = n.number("scale", 1);
                return new Compiled((x, y, z) -> x * k, false);
            }
            case "input.y": {
                double k = n.number("scale", 1);
                return new Compiled((x, y, z) -> y * k, true);
            }
            case "input.z": {
                double k = n.number("scale", 1);
                return new Compiled((x, y, z) -> z * k, false);
            }
            case "input.constant": {
                double v = n.number("value", 0);
                return new Compiled((x, y, z) -> v, false);
            }
            case "input.y_gradient": {
                double y0 = n.number("fromY", -64), y1 = n.number("toY", 320), v0 = n.number("fromValue", 1), v1 = n.number("toValue", -1);
                if (y1 == y0) throw new IllegalArgumentException("fromY and toY must differ");
                return new Compiled((x, y, z) -> {
                    double t = Math.min(1, Math.max(0, (y - y0) / (y1 - y0)));
                    return v0 + t * (v1 - v0);
                }, true);
            }
            case "noise.perlin":
            case "noise.simplex":
            case "noise.value":
            case "noise.worley":
                return noise(n, s);
            case "warp.domain":
                return warp(n, s);
            case "math.add":
                return binary(n, Double::sum);
            case "math.sub":
                return binary(n, (a, b) -> a - b);
            case "math.mul":
                return binary(n, (a, b) -> a * b);
            case "math.min":
                return binary(n, Math::min);
            case "math.max":
                return binary(n, Math::max);
            case "math.scale": {
                Compiled a = in(n, "in");
                double k = n.number("factor", 1), o = n.number("offset", 0);
                return new Compiled((x, y, z) -> a.fn.at(x, y, z) * k + o, a.usesY);
            }
            case "math.abs": {
                Compiled a = in(n, "in");
                return new Compiled((x, y, z) -> Math.abs(a.fn.at(x, y, z)), a.usesY);
            }
            case "math.negate": {
                Compiled a = in(n, "in");
                return new Compiled((x, y, z) -> -a.fn.at(x, y, z), a.usesY);
            }
            case "math.clamp": {
                Compiled a = in(n, "in");
                double lo = n.number("min", -1), hi = n.number("max", 1);
                return new Compiled((x, y, z) -> Math.min(hi, Math.max(lo, a.fn.at(x, y, z))), a.usesY);
            }
            case "math.remap": {
                Compiled a = in(n, "in");
                double f0 = n.number("fromMin", -1), f1 = n.number("fromMax", 1), t0 = n.number("toMin", 0), t1 = n.number("toMax", 1);
                if (f1 == f0) throw new IllegalArgumentException("fromMin and fromMax must differ");
                double k = (t1 - t0) / (f1 - f0);
                return new Compiled((x, y, z) -> t0 + (a.fn.at(x, y, z) - f0) * k, a.usesY);
            }
            case "math.lerp": {
                Compiled a = in(n, "a"), b = in(n, "b"), t = in(n, "t");
                boolean usesY = a.usesY || b.usesY || t.usesY;
                Fn fa = flatIf(a, usesY), fb = flatIf(b, usesY), ft = flatIf(t, usesY);
                return new Compiled((x, y, z) -> {
                    double k = Math.min(1, Math.max(0, ft.at(x, y, z)));
                    return fa.at(x, y, z) + k * (fb.at(x, y, z) - fa.at(x, y, z));
                }, usesY);
            }
            case "curve.spline": {
                Compiled a = in(n, "in");
                Spline sp = Spline.of(n.params().get("points"));
                return new Compiled((x, y, z) -> sp.at(a.fn.at(x, y, z)), a.usesY);
            }
            case "shape.terraces": {
                Compiled a = in(n, "in");
                double step = n.number("step", 8), smooth = Math.min(1, Math.max(0, n.number("smoothness", 0.2)));
                if (step <= 0) throw new IllegalArgumentException("step must be above 0");
                // Smoothness 1 is no terracing at all; 0 is sheer steps.
                double p = 1 + (1 - smooth) * 15;
                return new Compiled((x, y, z) -> {
                    double v = a.fn.at(x, y, z) / step;
                    double k = Math.floor(v);
                    return (k + StrictMath.pow(v - k, p)) * step;
                }, a.usesY);
            }
            case "shape.height_to_density": {
                Compiled h = in(n, "height");
                double falloff = n.number("falloff", 1);
                Fn fh = flat(h);
                return new Compiled((x, y, z) -> (fh.at(x, y, z) - y) * falloff, true);
            }
            default:
                throw new IllegalArgumentException("Node '" + n.id() + "' has an unknown type '" + n.type() + "'");
        }
    }

    private Compiled noise(Node n, long s) {
        String type = n.type();
        double freq = n.number("frequency", 1.0 / 256), freqY = freq * n.number("scaleY", 1);
        boolean flat = !"3d".equalsIgnoreCase(n.text("dims", "2d"));
        double amp = n.number("amplitude", 1), offset = n.number("offset", 0);
        Fractal.Mode mode = fractalMode(n.text("fractal", type.equals("noise.worley") ? "none" : "fbm"));
        int octaves = (int) n.number("octaves", 4);
        if (octaves < 1 || octaves > 16) throw new IllegalArgumentException("octaves must be 1 to 16");
        Worley.Mode worley = switch (n.text("mode", "f1").toLowerCase(Locale.ROOT)) {
            case "f1" -> Worley.Mode.F1;
            case "f2" -> Worley.Mode.F2;
            case "f2-f1", "f2_minus_f1" -> Worley.Mode.F2_MINUS_F1;
            default -> throw new IllegalArgumentException("mode must be f1, f2 or f2-f1");
        };
        java.util.function.LongFunction<Noise> make = switch (type) {
            case "noise.perlin" -> Perlin::new;
            case "noise.simplex" -> Simplex::new;
            case "noise.value" -> ValueNoise::new;
            default -> k -> new Worley(k, worley, flat);
        };
        Noise noise = new Fractal(s, make, mode, octaves, n.number("lacunarity", 2), n.number("gain", 0.5));
        if (flat) return new Compiled((x, y, z) -> noise.sample2d(x * freq, z * freq) * amp + offset, false);
        return new Compiled((x, y, z) -> noise.sample(x * freq, y * freqY, z * freq) * amp + offset, true);
    }

    private static Fractal.Mode fractalMode(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "none" -> Fractal.Mode.NONE;
            case "fbm" -> Fractal.Mode.FBM;
            case "ridged" -> Fractal.Mode.RIDGED;
            case "billow" -> Fractal.Mode.BILLOW;
            default -> throw new IllegalArgumentException("fractal must be none, fbm, ridged or billow");
        };
    }

    /** Moves the positions its input is read at by smooth noise, which bends straight features into natural ones. */
    private Compiled warp(Node n, long s) {
        Compiled a = in(n, "in");
        double amp = n.number("amplitude", 32), freq = n.number("frequency", 1.0 / 128);
        int octaves = (int) n.number("octaves", 3);
        Noise wx = new Fractal(Seeds.mix64(s ^ 1), Simplex::new, Fractal.Mode.FBM, octaves, 2, 0.5);
        Noise wz = new Fractal(Seeds.mix64(s ^ 2), Simplex::new, Fractal.Mode.FBM, octaves, 2, 0.5);
        if (!a.usesY) {
            return new Compiled((x, y, z) -> a.fn.at(x + wx.sample2d(x * freq, z * freq) * amp, y,
                    z + wz.sample2d(x * freq, z * freq) * amp), false);
        }
        Noise wy = new Fractal(Seeds.mix64(s ^ 3), Simplex::new, Fractal.Mode.FBM, octaves, 2, 0.5);
        return new Compiled((x, y, z) -> {
            double fx = x * freq, fy = y * freq, fz = z * freq;
            return a.fn.at(x + wx.sample(fx, fy, fz) * amp, y + wy.sample(fx, fy, fz) * amp, z + wz.sample(fx, fy, fz) * amp);
        }, true);
    }

    private Compiled binary(Node n, java.util.function.DoubleBinaryOperator op) {
        Compiled a = in(n, "a"), b = in(n, "b");
        boolean usesY = a.usesY || b.usesY;
        Fn fa = flatIf(a, usesY), fb = flatIf(b, usesY);
        return new Compiled((x, y, z) -> op.applyAsDouble(fa.at(x, y, z), fb.at(x, y, z)), usesY);
    }

    /** A flat input to a 3D node goes through a per-column cache. */
    private static Fn flatIf(Compiled c, boolean consumerUsesY) {
        return consumerUsesY ? flat(c) : c.fn;
    }

    /**
     * Caches a flat (2D) value per thread for the last few columns asked, so a 3D consumer walking up columns or
     * round a cell's corners (as the chunk filler and the surface search do) computes it once per column. A 3D input
     * is returned as it is.
     */
    static Fn flat(Compiled c) {
        if (c.usesY) return c.fn;
        Fn f = c.fn;
        // Eight columns, replaced round-robin: x, z, value per slot, then the next slot to replace.
        ThreadLocal<double[]> cache = ThreadLocal.withInitial(() -> {
            double[] a = new double[8 * 3 + 1];
            java.util.Arrays.fill(a, Double.NaN);
            a[24] = 0;
            return a;
        });
        return (x, y, z) -> {
            double[] a = cache.get();
            for (int i = 0; i < 24; i += 3) if (a[i] == x && a[i + 1] == z) return a[i + 2];
            double v = f.at(x, y, z);
            int slot = (int) a[24];
            a[slot] = x;
            a[slot + 1] = z;
            a[slot + 2] = v;
            a[24] = (slot + 3) % 24;
            return v;
        };
    }

    /** Node ids no output reaches, for a warning in the panel. */
    static List<String> unused(GeneratorSpec spec) {
        Set<String> seen = new HashSet<>();
        List<String> stack = new ArrayList<>(spec.outputs().values());
        while (!stack.isEmpty()) {
            String id = stack.remove(stack.size() - 1);
            if (!seen.add(id)) continue;
            Node n = spec.nodes().get(id);
            if (n != null) stack.addAll(n.inputs().values());
        }
        List<String> out = new ArrayList<>();
        for (String id : spec.nodes().keySet()) if (!seen.contains(id)) out.add(id);
        return out;
    }
}
