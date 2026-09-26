package io.blockdesigner.terragen.plugin;

import io.blockdesigner.core.model.Box;
import io.blockdesigner.plugin.PanelContext;
import io.blockdesigner.plugin.PluginContext;
import io.blockdesigner.plugin.PluginPanel;
import io.blockdesigner.terragen.GeneratorSpec;
import io.blockdesigner.terragen.Presets;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The Terrain panel: pick a generator and a seed, tune its sliders while a map of it redraws (here and on the ground in
 * the 3D view), set the region and bake it into a new layer. It shows and edits the {@link TerrainSession}'s state, so
 * everything here is saved in the project and undoable.
 */
final class TerrainPanel implements PluginPanel {
    private static final int MAP = TerrainSession.MAP;
    private static final List<Integer> ZOOMS = List.of(1, 2, 4, 8, 16, 32);
    private static final Color MUTED = Color.web("#9aa0a6"), ERROR = Color.web("#e06c6c");

    private final TerrainSession session;
    private final Runnable listener = this::refresh;
    /** True while the fields are being filled from the state, so their listeners don't write it back. */
    private boolean updating;
    /** The spec the sliders were built for. */
    private GeneratorSpec shownSpec;
    private int[] shownPixels;

    private final ComboBox<Presets.Preset> presets = new ComboBox<>();
    private final Label description = new Label();
    private final TextField seed = new TextField();
    private final VBox controls = new VBox(6);
    private final ImageView map = new ImageView();
    private final Canvas overlay = new Canvas(MAP, MAP);
    private final Label mapStatus = new Label();
    private final ComboBox<Integer> zoom = new ComboBox<>();
    private final CheckBox inView = new CheckBox("Show on the ground in the 3D view");
    private final Spinner<Integer> centreX = spinner(-30_000_000, 30_000_000, 0, 16);
    private final Spinner<Integer> centreZ = spinner(-30_000_000, 30_000_000, 0, 16);
    private final Spinner<Integer> width = spinner(1, TerrainState.MAX_SIZE, 256, 16);
    private final Spinner<Integer> depth = spinner(1, TerrainState.MAX_SIZE, 256, 16);
    private final Spinner<Integer> bottom = spinner(-64, 320, 40, 8);
    private final CheckBox water = new CheckBox("Keep the sea");
    private final ComboBox<Integer> voxel = new ComboBox<>();
    private final CheckBox model = new CheckBox("As a small model (one block per voxel)");
    private final Button bake = new Button("Bake to new layer");
    private final Button cancel = new Button("Cancel");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = new Label();

    TerrainPanel(TerrainSession session) {
        this.session = session;
    }

    @Override
    public String id() {
        return "terrain";
    }

    @Override
    public String title() {
        return "Terrain";
    }

    @Override
    public String icon() {
        return "M1 13 L5 7 L8 10 L11 5 L15 13 Z M11 5 L12.5 7.2";
    }

    @Override
    public Node create(PanelContext context) {
        PluginContext ctx = context.plugin();
        session.revealPanel = context::reveal;

        presets.getItems().setAll(Presets.all());
        presets.setMaxWidth(Double.MAX_VALUE);
        presets.setOnAction(e -> {
            Presets.Preset p = presets.getValue();
            if (!updating && p != null && !p.id().equals(session.state().presetId())) {
                session.update("Terrain generator", s -> s.withPreset(p.id(), p.spec()));
            }
        });
        Button open = small("Open…", "Open a generator file (.tgen.json)");
        open.setOnAction(e -> openFile());
        Button save = small("Save…", "Save this generator, with your slider settings, as a .tgen.json file");
        save.setOnAction(e -> saveFile());
        HBox.setHgrow(presets, Priority.ALWAYS);
        HBox genRow = new HBox(4, presets, open, save);
        genRow.setAlignment(Pos.CENTER_LEFT);
        description.setWrapText(true);
        description.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");

        seed.setPromptText("a number or any text");
        seed.textProperty().addListener((o, a, b) -> {
            if (!updating) session.update("Terrain seed", s -> s.withSeed(b));
        });
        Button dice = small("🎲", "A random seed");
        dice.setOnAction(e -> session.update("Terrain seed", s -> s.withSeed(TerrainSession.randomSeed())));
        HBox.setHgrow(seed, Priority.ALWAYS);
        HBox seedRow = new HBox(4, seed, dice);
        seedRow.setAlignment(Pos.CENTER_LEFT);

        map.setFitWidth(MAP);
        map.setFitHeight(MAP);
        map.setSmooth(false);
        StackPane mapBox = new StackPane(map, overlay);
        mapBox.setMaxSize(MAP, MAP);
        mapBox.setStyle("-fx-background-color: #1b1b1b;");
        Tooltip.install(mapBox, new Tooltip("Click to move the region there · wheel to zoom"));
        mapBox.setOnMouseClicked(e -> {
            int step = session.mapPixels() != null ? session.mapStep() : session.state().zoom();
            int cx = session.mapPixels() != null ? session.mapCentreX() : session.state().centreX();
            int cz = session.mapPixels() != null ? session.mapCentreZ() : session.state().centreZ();
            int x = cx + (int) Math.round((e.getX() - MAP / 2.0) * step), z = cz + (int) Math.round((e.getY() - MAP / 2.0) * step);
            session.update("Move terrain region", s -> s.withCentre(x, z));
        });
        mapBox.setOnScroll(e -> {
            int i = ZOOMS.indexOf(session.state().zoom()) + (e.getDeltaY() < 0 ? 1 : -1);
            if (i >= 0 && i < ZOOMS.size()) session.update("Terrain map zoom", s -> s.withZoom(ZOOMS.get(i)));
            e.consume();
        });
        zoom.getItems().setAll(ZOOMS);
        zoom.setConverter(converter(v -> "1 px = " + v + (v == 1 ? " block" : " blocks"), zoom));
        zoom.setOnAction(e -> {
            if (!updating && zoom.getValue() != null) session.update("Terrain map zoom", s -> s.withZoom(zoom.getValue()));
        });
        mapStatus.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        HBox zoomRow = new HBox(8, zoom, mapStatus);
        zoomRow.setAlignment(Pos.CENTER_LEFT);
        inView.setOnAction(e -> {
            if (!updating) session.setShownInView(inView.isSelected());
        });

        bind(centreX, "Move terrain region", (s, v) -> s.withCentre(v, s.centreZ()));
        bind(centreZ, "Move terrain region", (s, v) -> s.withCentre(s.centreX(), v));
        bind(width, "Resize terrain region", (s, v) -> s.withSize(v, s.sizeZ()));
        bind(depth, "Resize terrain region", (s, v) -> s.withSize(s.sizeX(), v));
        bind(bottom, "Terrain bake depth", TerrainState::withBottom);
        water.setOnAction(e -> {
            if (!updating) session.update("Terrain sea", s -> s.withWater(water.isSelected()));
        });
        Button fromSel = small("Use selection", "Bake the area of the selected blocks (or the //pos1 //pos2 region)");
        fromSel.setOnAction(e -> useSelection(ctx));
        GridPane region = new GridPane();
        region.setHgap(8);
        region.setVgap(6);
        region.addRow(0, label("Centre X"), centreX, label("Z"), centreZ);
        region.addRow(1, label("Width"), width, label("Depth"), depth);
        region.addRow(2, label("Down to Y"), bottom, water);
        GridPane.setColumnSpan(water, 2);
        Label toolHint = new Label("Or drag it out with the Terrain region tool in the tool dock.");
        toolHint.setWrapText(true);
        toolHint.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");

        voxel.getItems().setAll(TerrainState.VOXELS);
        voxel.setConverter(converter(v -> v == 1 ? "Blocks (1 : 1)" : v + " blocks per voxel", voxel));
        voxel.setOnAction(e -> {
            if (!updating && voxel.getValue() != null) session.update("Terrain voxel size", s -> s.withVoxel(voxel.getValue(), s.expanded()));
        });
        model.setOnAction(e -> {
            if (!updating) session.update("Terrain voxel bake", s -> s.withVoxel(s.voxel(), !model.isSelected()));
        });

        bake.getStyleClass().add("accent");
        bake.setMaxWidth(Double.MAX_VALUE);
        bake.setOnAction(e -> {
            String msg = session.bake();
            if (!session.baking()) {
                status.setTextFill(ERROR);
                status.setText(msg);
            }
        });
        cancel.setOnAction(e -> session.cancelBake());
        progress.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bake, Priority.ALWAYS);
        HBox bakeRow = new HBox(6, bake, cancel);
        status.setWrapText(true);
        status.setStyle("-fx-font-size: 11px;");

        VBox root = new VBox(10,
                section("Generator", genRow, description),
                section("Seed", seedRow),
                section("Shape", controls),
                section("Map", mapBox, zoomRow, inView),
                section("Region to bake", region, fromSel, toolHint),
                section("Voxels", voxel, model),
                bakeRow, progress, status);
        root.setPadding(new Insets(10));

        session.listen(listener);
        context.onShown(this::refresh);
        refresh();
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    /** Fills every field from the session's state (after any change, including undo). */
    private void refresh() {
        TerrainState s = session.state();
        updating = true;
        try {
            Presets.Preset match = s.presetId() == null ? null
                    : presets.getItems().stream().filter(p -> p.id().equals(s.presetId())).findFirst().orElse(null);
            presets.setValue(match);
            presets.setPromptText(match == null ? s.spec().name() + " (from a file)" : "");
            description.setText(s.spec().description());
            if (!seed.getText().equals(s.seed())) seed.setText(s.seed());
            if (!s.spec().equals(shownSpec)) buildControls(s.spec());
            zoom.setValue(s.zoom());
            set(centreX, s.centreX());
            set(centreZ, s.centreZ());
            set(width, s.sizeX());
            set(depth, s.sizeZ());
            set(bottom, s.bottomY());
            water.setSelected(s.water());
            voxel.setValue(s.voxel());
            model.setSelected(!s.expanded());
            model.setDisable(s.voxel() == 1);
            inView.setSelected(session.shownInView() || session.handle() == null);
        } finally {
            updating = false;
        }

        int[] px = session.mapPixels();
        if (px != null && px != shownPixels) {
            WritableImage img = new WritableImage(MAP, MAP);
            img.getPixelWriter().setPixels(0, 0, MAP, MAP, PixelFormat.getIntArgbInstance(), px, 0, MAP);
            map.setImage(img);
            shownPixels = px;
        }
        int step = session.mapStep();
        mapStatus.setText(session.error() != null ? "" : !session.mapUpToDate() ? "drawing…"
                : String.format("%,d × %,d blocks · %d ms", MAP * step, MAP * step, session.mapMillis()));
        drawRegion();

        boolean baking = session.baking();
        bake.setDisable(baking || session.error() != null);
        show(cancel, baking);
        show(progress, baking);
        progress.setProgress(session.bakeProgress());
        if (session.error() != null) {
            status.setTextFill(ERROR);
            status.setText("✖ " + session.error());
        } else {
            status.setTextFill(session.bakeFailed() ? ERROR : MUTED);
            status.setText(session.bakeMessage());
        }
    }

    /** One slider per control of the generator: {"label", "node", "param", "min", "max", "log", "invert"}. */
    private void buildControls(GeneratorSpec spec) {
        shownSpec = spec;
        controls.getChildren().clear();
        for (Map<String, Object> c : spec.controls()) {
            String node = String.valueOf(c.get("node")), param = String.valueOf(c.get("param"));
            GeneratorSpec.Node n = spec.nodes().get(node);
            if (n == null) continue;
            double min = num(c, "min", 0), max = num(c, "max", 1);
            boolean log = Boolean.TRUE.equals(c.get("log")) && min > 0, invert = Boolean.TRUE.equals(c.get("invert"));
            String label = String.valueOf(c.getOrDefault("label", param));
            Slider slider = new Slider(0, 1, Sliders.toSlider(n.number(param, min), min, max, log, invert));
            Label value = new Label(format(n.number(param, min)));
            value.setMinWidth(54);
            value.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
            slider.valueProperty().addListener((o, a, b) -> {
                if (updating) return;
                double v = Sliders.fromSlider(b.doubleValue(), min, max, log, invert);
                value.setText(format(v));
                session.update("Terrain: " + label, s -> s.withSpec(s.spec().withParam(node, param, v)));
                // The sliders stay as they are while dragging; only a change from elsewhere rebuilds them.
                shownSpec = session.state().spec();
            });
            HBox.setHgrow(slider, Priority.ALWAYS);
            Label name = new Label(label);
            name.setMinWidth(104);
            HBox row = new HBox(6, name, slider, value);
            row.setAlignment(Pos.CENTER_LEFT);
            controls.getChildren().add(row);
        }
        if (controls.getChildren().isEmpty()) {
            Label none = new Label("This generator has no sliders.");
            none.setStyle("-fx-text-fill: -color-fg-muted;");
            controls.getChildren().add(none);
        }
    }

    /** The bake region on the map, where it is relative to what the map shows. */
    private void drawRegion() {
        GraphicsContext gc = overlay.getGraphicsContext2D();
        gc.clearRect(0, 0, MAP, MAP);
        TerrainState s = session.state();
        int step = session.mapPixels() != null ? session.mapStep() : s.zoom();
        int cx = session.mapPixels() != null ? session.mapCentreX() : s.centreX();
        int cz = session.mapPixels() != null ? session.mapCentreZ() : s.centreZ();
        double x = MAP / 2.0 + (s.minX() - cx) / (double) step, z = MAP / 2.0 + (s.minZ() - cz) / (double) step;
        double w = s.sizeX() / (double) step, d = s.sizeZ() / (double) step;
        gc.setStroke(Color.color(0, 0, 0, 0.5));
        gc.setLineWidth(1);
        gc.strokeRect(x - 1.5, z - 1.5, w + 3, d + 3);
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(1.5);
        gc.strokeRect(x, z, w, d);
    }

    private void useSelection(PluginContext ctx) {
        Box b = ctx.selection().orElse(null);
        if (b == null) {
            ctx.toast("Select some blocks first (Select mode, or //pos1 and //pos2)");
            return;
        }
        int sx = b.maxX() - b.minX() + 1, sz = b.maxZ() - b.minZ() + 1;
        session.update("Terrain region from selection", s -> s.withSize(sx, sz).withCentre(b.minX() + sx / 2, b.minZ() + sz / 2));
    }

    private void openFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Open generator");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Terrain generator", "*.tgen.json", "*.json"));
        File f = fc.showOpenDialog(presets.getScene() == null ? null : presets.getScene().getWindow());
        if (f == null) return;
        try {
            GeneratorSpec spec = GeneratorSpec.read(Files.readString(f.toPath()));
            session.update("Open terrain generator", s -> s.withPreset(null, spec));
        } catch (IOException | IllegalArgumentException e) {
            status.setTextFill(ERROR);
            status.setText("✖ " + f.getName() + ": " + e.getMessage());
        }
    }

    private void saveFile() {
        GeneratorSpec spec = session.state().spec();
        FileChooser fc = new FileChooser();
        fc.setTitle("Save generator");
        fc.setInitialFileName(spec.name().replaceAll("[^A-Za-z0-9 _-]", "").strip() + ".tgen.json");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Terrain generator", "*.tgen.json"));
        File f = fc.showSaveDialog(presets.getScene() == null ? null : presets.getScene().getWindow());
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), spec.toJson(), StandardCharsets.UTF_8);
            status.setTextFill(MUTED);
            status.setText("Saved " + f.getName());
        } catch (IOException e) {
            status.setTextFill(ERROR);
            status.setText("✖ " + e.getMessage());
        }
    }

    // ---- bits -------------------------------------------------------------------------------------------------

    private interface Change {
        TerrainState apply(TerrainState s, int v);
    }

    private void bind(Spinner<Integer> s, String label, Change change) {
        s.valueProperty().addListener((o, a, b) -> {
            if (!updating && b != null) session.update(label, st -> change.apply(st, b));
        });
    }

    private static Spinner<Integer> spinner(int min, int max, int value, int step) {
        Spinner<Integer> s = new Spinner<>(min, max, value, step);
        s.setEditable(true);
        s.setPrefWidth(96);
        return s;
    }

    private static void set(Spinner<Integer> s, int v) {
        if (s.getValue() == null || s.getValue() != v) s.getValueFactory().setValue(v);
    }

    private static void show(Node n, boolean shown) {
        n.setVisible(shown);
        n.setManaged(shown);
    }

    private static <T> StringConverter<T> converter(Function<T, String> text, ComboBox<T> box) {
        return new StringConverter<>() {
            @Override
            public String toString(T v) {
                return v == null ? "" : text.apply(v);
            }

            @Override
            public T fromString(String s) {
                return box.getValue();
            }
        };
    }

    private static String format(double v) {
        if (v != 0 && Math.abs(v) < 0.1) return String.format(Locale.ROOT, "%.5f", v);
        return String.format(Locale.ROOT, v == Math.rint(v) ? "%.0f" : "%.2f", v);
    }

    private static double num(Map<String, Object> m, String key, double fallback) {
        return m.get(key) instanceof Number n ? n.doubleValue() : fallback;
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: -color-fg-muted;");
        return l;
    }

    private static Button small(String text, String tip) {
        Button b = new Button(text);
        b.setTooltip(new Tooltip(tip));
        return b;
    }

    private static Node section(String title, Node... content) {
        Label t = new Label(title.toUpperCase(Locale.ROOT));
        t.setStyle("-fx-font-size: 10.5px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");
        VBox v = new VBox(6, t);
        v.getChildren().addAll(content);
        return v;
    }

    @Override
    public void dispose() {
        session.unlisten(listener);
    }
}
