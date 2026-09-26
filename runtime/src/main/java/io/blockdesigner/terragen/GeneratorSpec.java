package io.blockdesigner.terragen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A terrain generator as a document: the world's shape, a graph of nodes, surface rules, scatter layers and a few
 * "controls" (friendly sliders on node parameters). Immutable; the {@code with…} methods return changed copies.
 * {@link #read(String)} and {@link #toJson()} convert it from and to the {@code .tgen.json} format.
 *
 * <p>Surface rules, scatter layers and controls are kept as the JSON they were written in; {@link Generator#compile}
 * checks them.
 */
public record GeneratorSpec(String name, String description, WorldShape world, Map<String, Node> nodes, Map<String, String> outputs,
                            List<Map<String, Object>> surface, List<Map<String, Object>> scatter, List<Map<String, Object>> controls,
                            Map<String, Object> meta) {

    public static final String FORMAT = "blockdesigner-terragen";
    /** The format version this runtime writes. Newer files are refused; older ones are migrated forward. */
    public static final int FORMAT_VERSION = 1;

    public GeneratorSpec {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        Objects.requireNonNull(world, "world");
        nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        surface = List.copyOf(surface);
        scatter = List.copyOf(scatter);
        controls = List.copyOf(controls);
        meta = Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    /**
     * The world's vertical extent and fill.
     *
     * @param cellWidth   horizontal size of the interpolation cell: 3D density is computed at cell corners and blended
     *                    in between (like Minecraft's 4 × 8 × 4), which is much faster than every block
     * @param cellHeight  vertical size of the interpolation cell
     * @param interpolate false to compute density at every block (slow, exact)
     */
    public record WorldShape(int minY, int height, int seaLevel, String defaultBlock, String defaultFluid, int cellWidth, int cellHeight,
                             boolean interpolate) {
        public WorldShape {
            if (height <= 0 || height > 4096) throw new IllegalArgumentException("World height must be 1 to 4096, not " + height);
            if (cellWidth <= 0 || 16 % cellWidth != 0) throw new IllegalArgumentException("Cell width must divide 16, not " + cellWidth);
            if (cellHeight <= 0 || height % cellHeight != 0) {
                throw new IllegalArgumentException("Cell height must divide the world height (" + height + "), not " + cellHeight);
            }
            Objects.requireNonNull(defaultBlock, "defaultBlock");
            Objects.requireNonNull(defaultFluid, "defaultFluid");
        }

        public int maxY() {
            return minY + height - 1;
        }

        public static WorldShape overworld() {
            return new WorldShape(-64, 384, 63, "minecraft:stone", "minecraft:water", 4, 8, true);
        }
    }

    /**
     * One node of the graph.
     *
     * @param params its settings (numbers as Double, text, true/false, lists)
     * @param inputs input name to the id of the node that feeds it
     * @param salt   what its seed is mixed from; stays the same when the node is renamed, so its noise doesn't change
     */
    public record Node(String id, String type, Map<String, Object> params, Map<String, String> inputs, String salt) {
        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            salt = salt == null || salt.isEmpty() ? id : salt;
        }

        public double number(String key, double fallback) {
            Object v = params.get(key);
            return v instanceof Number n ? n.doubleValue() : fallback;
        }

        public String text(String key, String fallback) {
            Object v = params.get(key);
            return v == null ? fallback : String.valueOf(v);
        }

        public Node withParam(String key, Object value) {
            Map<String, Object> p = new LinkedHashMap<>(params);
            p.put(key, value);
            return new Node(id, type, p, inputs, salt);
        }
    }

    public GeneratorSpec withParam(String nodeId, String key, Object value) {
        Node n = nodes.get(nodeId);
        if (n == null) throw new IllegalArgumentException("No node '" + nodeId + "'");
        Map<String, Node> m = new LinkedHashMap<>(nodes);
        m.put(nodeId, n.withParam(key, value));
        return new GeneratorSpec(name, description, world, m, outputs, surface, scatter, controls, meta);
    }

    public GeneratorSpec withWorld(WorldShape w) {
        return new GeneratorSpec(name, description, w, nodes, outputs, surface, scatter, controls, meta);
    }

    public GeneratorSpec withName(String n) {
        return new GeneratorSpec(n, description, world, nodes, outputs, surface, scatter, controls, meta);
    }

    public GeneratorSpec withScatter(List<Map<String, Object>> s) {
        return new GeneratorSpec(name, description, world, nodes, outputs, surface, s, controls, meta);
    }

    // ---- JSON -------------------------------------------------------------------------------------------------

    /** Reads a {@code .tgen.json} document. */
    @SuppressWarnings("unchecked")
    public static GeneratorSpec read(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("A generator file is a JSON object");
        Map<String, Object> m = (Map<String, Object>) root;
        if (!FORMAT.equals(m.get("format"))) throw new IllegalArgumentException("Not a terrain generator file (\"format\" is not \"" + FORMAT + "\")");
        int version = (int) num(m, "formatVersion", 1);
        if (version > FORMAT_VERSION) {
            throw new IllegalArgumentException("This generator needs a newer Terrain Generator (format " + version + ", this one reads up to "
                    + FORMAT_VERSION + ")");
        }
        Map<String, Object> w = map(m.get("world"));
        WorldShape def = WorldShape.overworld();
        List<Object> cell = w.get("cell") instanceof List<?> l ? (List<Object>) l : List.of();
        WorldShape world = new WorldShape(
                (int) num(w, "minY", def.minY()), (int) num(w, "height", def.height()), (int) num(w, "seaLevel", def.seaLevel()),
                str(w, "defaultBlock", def.defaultBlock()), str(w, "defaultFluid", def.defaultFluid()),
                cell.size() > 0 ? ((Number) cell.get(0)).intValue() : def.cellWidth(),
                cell.size() > 1 ? ((Number) cell.get(1)).intValue() : def.cellHeight(),
                !Boolean.FALSE.equals(w.get("interpolate")));
        Map<String, Object> graph = map(m.get("graph"));
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(graph.get("nodes")).entrySet()) {
            Map<String, Object> n = map(e.getValue());
            Map<String, String> inputs = new LinkedHashMap<>();
            for (Map.Entry<String, Object> in : map(n.get("inputs")).entrySet()) inputs.put(in.getKey(), String.valueOf(in.getValue()));
            if (!(n.get("type") instanceof String type)) throw new IllegalArgumentException("Node '" + e.getKey() + "' has no type");
            nodes.put(e.getKey(), new Node(e.getKey(), type, map(n.get("params")), inputs, str(n, "salt", e.getKey())));
        }
        Map<String, String> outputs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(graph.get("outputs")).entrySet()) outputs.put(e.getKey(), String.valueOf(e.getValue()));
        return new GeneratorSpec(str(m, "name", "Untitled"), str(m, "description", ""), world, nodes, outputs, maps(m.get("surface")),
                maps(m.get("scatter")), maps(m.get("controls")), map(m.get("meta")));
    }

    /** This generator as a {@code .tgen.json} document. */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("formatVersion", FORMAT_VERSION);
        m.put("name", name);
        if (!description.isEmpty()) m.put("description", description);
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("minY", world.minY());
        w.put("height", world.height());
        w.put("seaLevel", world.seaLevel());
        w.put("defaultBlock", world.defaultBlock());
        w.put("defaultFluid", world.defaultFluid());
        w.put("cell", List.of(world.cellWidth(), world.cellHeight()));
        if (!world.interpolate()) w.put("interpolate", false);
        m.put("world", w);
        Map<String, Object> ns = new LinkedHashMap<>();
        for (Node n : nodes.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("type", n.type());
            if (!n.salt().equals(n.id())) o.put("salt", n.salt());
            if (!n.params().isEmpty()) o.put("params", n.params());
            if (!n.inputs().isEmpty()) o.put("inputs", n.inputs());
            ns.put(n.id(), o);
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", ns);
        graph.put("outputs", outputs);
        m.put("graph", graph);
        if (!surface.isEmpty()) m.put("surface", surface);
        if (!scatter.isEmpty()) m.put("scatter", scatter);
        if (!controls.isEmpty()) m.put("controls", controls);
        if (!meta.isEmpty()) m.put("meta", meta);
        return Json.write(m);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        if (o == null) return new LinkedHashMap<>();
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected an object, found " + Json.write(o).strip());
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object o) {
        if (o == null) return List.of();
        if (!(o instanceof List<?> l)) throw new IllegalArgumentException("Expected a list, found " + Json.write(o).strip());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object x : l) out.add(map(x));
        return out;
    }

    static double num(Map<String, Object> m, String key, double fallback) {
        Object v = m.get(key);
        if (v == null) return fallback;
        if (!(v instanceof Number n)) throw new IllegalArgumentException("\"" + key + "\" should be a number");
        return n.doubleValue();
    }

    static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }
}
