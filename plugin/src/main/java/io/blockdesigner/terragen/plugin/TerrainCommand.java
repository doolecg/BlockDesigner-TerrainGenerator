package io.blockdesigner.terragen.plugin;

import io.blockdesigner.core.model.Box;
import io.blockdesigner.plugin.PluginCommand;
import io.blockdesigner.terragen.Presets;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@code /terrain}: the Terrain Generator from the command line.
 * <ul>
 *   <li>{@code /terrain seed <number or text>} (no value: a random one)</li>
 *   <li>{@code /terrain preset <id>}: vanilla-like, islands, mesas, rolling-hills</li>
 *   <li>{@code /terrain region <x> <z> <width> [depth]}, or {@code /terrain region} for the //pos1 //pos2 region</li>
 *   <li>{@code /terrain bake [width] [depth]}: bakes the region (optionally resized first)</li>
 * </ul>
 */
final class TerrainCommand {
    private TerrainCommand() {
    }

    static PluginCommand create(TerrainSession session) {
        return new PluginCommand("terrain", "/terrain seed|preset|region|bake …",
                "Terrain Generator: set the seed or generator, set the region, or bake it", c -> run(session, c.args(), c.region().orElse(null)));
    }

    static String run(TerrainSession session, List<String> args, Box selection) {
        if (args.isEmpty()) return usage(session);
        String sub = args.get(0).toLowerCase(Locale.ROOT);
        List<String> rest = args.subList(1, args.size());
        switch (sub) {
            case "seed": {
                String seed = rest.isEmpty() ? TerrainSession.randomSeed() : String.join(" ", rest);
                session.update("Terrain seed", s -> s.withSeed(seed));
                return "Terrain seed: " + seed;
            }
            case "preset": {
                if (rest.isEmpty()) throw new IllegalArgumentException("Which generator? " + ids());
                Presets.Preset p = Presets.all().stream().filter(x -> x.id().equalsIgnoreCase(rest.get(0))).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No generator '" + rest.get(0) + "'. There are: " + ids()));
                session.update("Terrain generator", s -> s.withPreset(p.id(), p.spec()));
                return "Terrain generator: " + p.spec().name();
            }
            case "region": {
                if (rest.isEmpty()) {
                    if (selection == null) throw new IllegalArgumentException("Select a region first (//pos1 and //pos2), or give /terrain region <x> <z> <width> [depth]");
                    int sx = selection.maxX() - selection.minX() + 1, sz = selection.maxZ() - selection.minZ() + 1;
                    session.update("Terrain region", s -> s.withSize(sx, sz).withCentre(selection.minX() + sx / 2, selection.minZ() + sz / 2));
                    return "Terrain region: " + sx + " × " + sz;
                }
                if (rest.size() < 3) throw new IllegalArgumentException("Usage: /terrain region <x> <z> <width> [depth]");
                int x = number(rest.get(0)), z = number(rest.get(1)), w = size(rest.get(2)), d = rest.size() > 3 ? size(rest.get(3)) : w;
                session.update("Terrain region", s -> s.withCentre(x, z).withSize(w, d));
                return "Terrain region: " + w + " × " + d + " around " + x + ", " + z;
            }
            case "bake": {
                if (!rest.isEmpty()) {
                    int w = size(rest.get(0)), d = rest.size() > 1 ? size(rest.get(1)) : w;
                    session.update("Terrain region", s -> s.withSize(w, d));
                }
                return session.bake();
            }
            default:
                throw new IllegalArgumentException("Unknown /terrain command '" + sub + "'. " + usage(session));
        }
    }

    private static String usage(TerrainSession session) {
        TerrainState s = session.state();
        return "Terrain: " + s.spec().name() + ", seed " + s.seed() + ", region " + s.sizeX() + " × " + s.sizeZ() + " around " + s.centreX()
                + ", " + s.centreZ() + ". Use /terrain seed <n>, preset <id>, region <x> <z> <w> [d], or bake [w] [d].";
    }

    private static String ids() {
        return Presets.all().stream().map(Presets.Preset::id).collect(Collectors.joining(", "));
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' is not a whole number");
        }
    }

    private static int size(String s) {
        int v = number(s);
        if (v < 1 || v > TerrainState.MAX_SIZE) throw new IllegalArgumentException("A size is 1 to " + TerrainState.MAX_SIZE + ", not " + v);
        return v;
    }
}
