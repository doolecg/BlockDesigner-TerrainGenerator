package io.blockdesigner.terragen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecTest {
    private static String graph(String nodes, String outputs) {
        return "{\"format\": \"blockdesigner-terragen\", \"formatVersion\": 1, \"name\": \"T\", \"graph\": {\"nodes\": {" + nodes
                + "}, \"outputs\": {" + outputs + "}}}";
    }

    @Test
    void presetsRoundTripThroughJson() {
        for (Presets.Preset p : Presets.all()) {
            GeneratorSpec again = GeneratorSpec.read(p.spec().toJson());
            assertThat(again).as(p.id()).isEqualTo(p.spec());
            assertThat(again.toJson()).isEqualTo(p.spec().toJson());
            assertThat(Generator.compile(p.spec(), 1).unusedNodes()).as(p.id() + " has no stray nodes").isEmpty();
        }
    }

    @Test
    void jsonReadsAndWritesPlainValues() {
        Object v = Json.parse("{\"a\": [1, 2.5, -3e2], \"b\": \"x\\\"y\\n\", \"c\": true, \"d\": null, \"e\": {}}");
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
        assertThat(m.get("a")).isEqualTo(List.of(1.0, 2.5, -300.0));
        assertThat(m.get("b")).isEqualTo("x\"y\n");
        assertThat(m).containsEntry("c", true).containsKey("d");
        assertThat(Json.parse(Json.write(v))).isEqualTo(v);
        assertThatThrownBy(() -> Json.parse("{\"a\": [1, 2}")).hasMessageContaining("line 1");
    }

    @Test
    void badGraphsSayWhatIsWrong() {
        assertThatThrownBy(() -> Generator.compile(GeneratorSpec.read(graph(
                "\"a\": {\"type\": \"math.abs\", \"inputs\": {\"in\": \"b\"}}, \"b\": {\"type\": \"math.abs\", \"inputs\": {\"in\": \"a\"}}",
                "\"height\": \"a\"")), 0)).hasMessageContaining("cycle");
        assertThatThrownBy(() -> Generator.compile(GeneratorSpec.read(graph("\"a\": {\"type\": \"math.abs\"}", "\"height\": \"a\"")), 0))
                .hasMessageContaining("'a'").hasMessageContaining("'in'");
        assertThatThrownBy(() -> Generator.compile(GeneratorSpec.read(graph("\"a\": {\"type\": \"noise.bogus\"}", "\"height\": \"a\"")), 0))
                .hasMessageContaining("unknown type 'noise.bogus'");
        assertThatThrownBy(() -> Generator.compile(GeneratorSpec.read(graph("\"a\": {\"type\": \"input.y\"}", "\"height\": \"a\"")), 0))
                .hasMessageContaining("can't depend on y");
        assertThatThrownBy(() -> Generator.compile(GeneratorSpec.read(graph("\"a\": {\"type\": \"input.constant\"}", "")), 0))
                .hasMessageContaining("output");
        assertThatThrownBy(() -> GeneratorSpec.read("{\"format\": \"blockdesigner-terragen\", \"formatVersion\": 99}"))
                .hasMessageContaining("newer");
        assertThatThrownBy(() -> GeneratorSpec.read("{\"format\": \"other\"}")).hasMessageContaining("Not a terrain generator");
    }

    @Test
    void parametersChangeByCopy() {
        GeneratorSpec s = Presets.get("rolling-hills").spec();
        GeneratorSpec t = s.withParam("hills", "amplitude", 20.0);
        assertThat(t.nodes().get("hills").number("amplitude", 0)).isEqualTo(20.0);
        assertThat(s.nodes().get("hills").number("amplitude", 0)).isEqualTo(8.0);
        assertThat(Generator.compile(t, 1).fingerprint()).isNotEqualTo(Generator.compile(s, 1).fingerprint());
    }

    @Test
    void seedsFromTextMatchMinecraft() {
        assertThat(Seeds.parse("12345")).isEqualTo(12345L);
        assertThat(Seeds.parse(" -7 ")).isEqualTo(-7L);
        assertThat(Seeds.parse("hello")).isEqualTo("hello".hashCode());
        assertThat(Seeds.parse("")).isZero();
    }
}
