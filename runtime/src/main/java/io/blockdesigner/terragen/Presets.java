package io.blockdesigner.terragen;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The generators that come with the runtime, in the order they are offered. */
public final class Presets {
    private static final String DIR = "/io/blockdesigner/terragen/presets/";

    /** A built-in generator: a stable id and its spec. */
    public record Preset(String id, GeneratorSpec spec) {
        @Override
        public String toString() {
            return spec.name();
        }
    }

    private static List<Preset> all;

    private Presets() {
    }

    public static synchronized List<Preset> all() {
        if (all == null) {
            List<Preset> out = new ArrayList<>();
            for (String line : read("index.txt").split("\\R")) {
                String id = line.strip();
                if (id.isEmpty()) continue;
                out.add(new Preset(id, GeneratorSpec.read(read(id + ".tgen.json"))));
            }
            all = List.copyOf(out);
        }
        return all;
    }

    /** The preset with this id, or the first one. */
    public static Preset get(String id) {
        for (Preset p : all()) if (p.id().equals(id)) return p;
        return all().get(0);
    }

    private static String read(String name) {
        try (InputStream in = Presets.class.getResourceAsStream(DIR + name)) {
            if (in == null) throw new IllegalStateException("Missing preset resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
