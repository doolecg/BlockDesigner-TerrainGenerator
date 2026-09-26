package io.blockdesigner.terragen;

import io.blockdesigner.terragen.noise.Fractal;
import io.blockdesigner.terragen.noise.Noise;
import io.blockdesigner.terragen.noise.Perlin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Picks the block for each solid position from an ordered list of rules; the first that matches wins, and a solid
 * block no rule claims is the world's default block. A rule is {@code {"if": condition, "block": id}} or
 * {@code {"if": condition, "then": [rules]}}; "if" can be left out (always).
 *
 * <p>Conditions: {@code depth} (max: solid blocks above this one in its run, 0 = the top), {@code above_water}
 * (offset: the run's top is at or above sea level + offset), {@code y_above} (min), {@code y_below} (max),
 * {@code y_between} (min, max), {@code steep}
 * (min: the height difference to a neighbouring column), {@code noise} (threshold, frequency, salt: patches),
 * {@code not} (if), {@code any} / {@code all} (of: [conditions]).
 */
final class SurfaceRules {
    /** Where a block is and what's around it. */
    static final class Ctx {
        int x, y, z;
        /** Solid blocks between this one and the air or water above its run (0 at the top). */
        int depth;
        /** The top y of the solid run this block is in. */
        int runTop;
        /** The largest height difference from the column's top to a neighbour's (4 directions). */
        int slope;
    }

    private interface Cond {
        boolean test(Ctx c);
    }

    private record Rule(Cond cond, String block, List<Rule> then) {
    }

    private final List<Rule> rules;
    private final int seaLevel;
    private final long seed;
    /** Whether any rule looks at the slope, so the filler only works it out when needed. */
    boolean usesSlope;

    SurfaceRules(List<Map<String, Object>> json, int seaLevel, long seed) {
        this.seaLevel = seaLevel;
        this.seed = seed;
        this.rules = list(json, "surface");
    }

    /** The block for a solid position, or null for the world's default block. */
    String pick(Ctx c) {
        return pick(rules, c);
    }

    private static String pick(List<Rule> rules, Ctx c) {
        for (Rule r : rules) {
            if (r.cond != null && !r.cond.test(c)) continue;
            if (r.block != null) return r.block;
            String b = pick(r.then, c);
            if (b != null) return b;
        }
        return null;
    }

    private List<Rule> list(List<Map<String, Object>> json, String where) {
        List<Rule> out = new ArrayList<>();
        for (int i = 0; i < json.size(); i++) out.add(rule(json.get(i), where + " rule " + (i + 1)));
        return out;
    }

    private Rule rule(Map<String, Object> m, String where) {
        Cond cond = m.containsKey("if") ? cond(GeneratorSpec.map(m.get("if")), where) : null;
        Object block = m.get("block");
        if (block != null) return new Rule(cond, String.valueOf(block), List.of());
        if (!m.containsKey("then")) throw new IllegalArgumentException(where + " needs a \"block\" or \"then\"");
        return new Rule(cond, null, list(GeneratorSpec.maps(m.get("then")), where));
    }

    private Cond cond(Map<String, Object> m, String where) {
        String type = GeneratorSpec.str(m, "type", "").toLowerCase(Locale.ROOT);
        switch (type) {
            case "always":
                return c -> true;
            case "depth": {
                int max = (int) GeneratorSpec.num(m, "max", 0);
                return c -> c.depth <= max;
            }
            case "above_water": {
                int offset = (int) GeneratorSpec.num(m, "offset", 0);
                return c -> c.runTop >= seaLevel + offset;
            }
            case "y_above": {
                int min = (int) GeneratorSpec.num(m, "min", 0);
                return c -> c.y >= min;
            }
            case "y_below": {
                int max = (int) GeneratorSpec.num(m, "max", 0);
                return c -> c.y <= max;
            }
            case "y_between": {
                int min = (int) GeneratorSpec.num(m, "min", 0), max = (int) GeneratorSpec.num(m, "max", 0);
                return c -> c.y >= min && c.y <= max;
            }
            case "steep": {
                int min = (int) GeneratorSpec.num(m, "min", 3);
                usesSlope = true;
                return c -> c.slope >= min;
            }
            case "noise": {
                double threshold = GeneratorSpec.num(m, "threshold", 0), freq = GeneratorSpec.num(m, "frequency", 1.0 / 32);
                Noise n = new Fractal(Seeds.nodeSeed(seed, GeneratorSpec.str(m, "salt", "surface-noise")), Perlin::new, Fractal.Mode.FBM, 2, 2, 0.5);
                return c -> n.sample2d(c.x * freq, c.z * freq) > threshold;
            }
            case "not": {
                Cond inner = cond(GeneratorSpec.map(m.get("if")), where);
                return c -> !inner.test(c);
            }
            case "any":
            case "all": {
                List<Cond> of = new ArrayList<>();
                for (Map<String, Object> x : GeneratorSpec.maps(m.get("of"))) of.add(cond(x, where));
                boolean any = type.equals("any");
                return c -> {
                    for (Cond x : of) if (x.test(c) == any) return any;
                    return !any;
                };
            }
            default:
                throw new IllegalArgumentException(where + ": unknown condition '" + type + "'");
        }
    }
}
