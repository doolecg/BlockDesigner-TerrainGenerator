package io.blockdesigner.terragen.plugin;

import io.blockdesigner.core.model.Structure;
import io.blockdesigner.plugin.ImageData;
import io.blockdesigner.plugin.ObjectHandle;
import io.blockdesigner.plugin.PluginContext;
import io.blockdesigner.plugin.Pose;
import io.blockdesigner.plugin.SceneEvent;
import io.blockdesigner.terragen.Generator;
import io.blockdesigner.terragen.Seeds;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * The one place the plugin's pieces meet: the Terrain panel, the region tool, the {@code /terrain} command and the
 * Terrain preview object all read and change the same {@link TerrainState} here.
 *
 * <p>The state lives in the project as the Terrain preview scene object: every change is written into it as an undo
 * step (changes within half a second, such as a slider drag, make one step), and undo, redo or opening a project put
 * the object's state back here. Until the project has a preview object, the first change adds one. The last state is
 * also remembered in the plugin's data folder, as the start for projects that have none.
 *
 * <p>Everything here runs on the JavaFX thread; map drawing and baking run on their own threads.
 */
final class TerrainSession {
    static final int MAP = 280;

    private final PluginContext ctx;
    private final MapRenderer maps;
    private final List<Runnable> listeners = new ArrayList<>();
    private final ExecutorService mapWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "terrain-map");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** Bumped when the map needs redrawing; a drawing for an older value is dropped. */
    private final AtomicLong mapGeneration = new AtomicLong();
    private final PauseTransition mapDebounce = new PauseTransition(Duration.millis(150));
    private final PauseTransition commitDebounce = new PauseTransition(Duration.millis(500));

    private TerrainState state;
    private Generator generator;
    private String error;
    private String commitLabel = "Change terrain";
    private ObjectHandle handle;

    // The last map drawn: its pixels and what they cover.
    private int[] mapPixels;
    private ImageData mapImage;
    private int mapCentreX, mapCentreZ, mapStep;
    private long mapMillis;

    // Baking.
    private volatile boolean baking, cancelBake;
    private double bakeProgress;
    private String bakeMessage = "";
    private boolean bakeFailed;

    /** The Terrain page's id. */
    static final String PAGE = "terrain";

    TerrainSession(PluginContext ctx) {
        this.ctx = ctx;
        this.maps = new MapRenderer(id -> {
            try {
                return ctx.blocks().averageColor(ctx.blocks().resolve(id));
            } catch (RuntimeException e) {
                return 0x808080;
            }
        });
        mapDebounce.setOnFinished(e -> drawMap());
        commitDebounce.setOnFinished(e -> commit());
        state = remembered();
        compile();
        ctx.on(SceneEvent.ProjectOpened.class, e -> bind());
        bind();
    }

    // ---- state ------------------------------------------------------------------------------------------------

    TerrainState state() {
        return state;
    }

    /** The compiled generator, or null when the spec has an error ({@link #error()}). */
    Generator generator() {
        return generator;
    }

    String error() {
        return error;
    }

    void listen(Runnable r) {
        listeners.add(r);
    }

    void unlisten(Runnable r) {
        listeners.remove(r);
    }

    /** Changes the state as an undo step called {@code label} (merged with more changes in the next half second). */
    void update(String label, UnaryOperator<TerrainState> change) {
        TerrainState next = change.apply(state);
        if (next.equals(state)) return;
        TerrainState before = state;
        state = next;
        changed(before);
        commitLabel = label;
        commitDebounce.playFromStart();
        remember();
    }

    /** Takes a state that came from the project (undo, redo, opening it): no new undo step. */
    void adopt(TerrainState s) {
        commitDebounce.stop();
        if (s.equals(state)) return;
        TerrainState before = state;
        state = s;
        changed(before);
    }

    private void changed(TerrainState before) {
        if (!before.spec().equals(state.spec()) || !before.seed().equals(state.seed())) compile();
        if (generator != null && (!before.spec().equals(state.spec()) || !before.seed().equals(state.seed())
                || before.centreX() != state.centreX() || before.centreZ() != state.centreZ() || before.zoom() != state.zoom())) {
            mapGeneration.incrementAndGet();
            mapDebounce.playFromStart();
        }
        fire();
        if (handle != null && handle.exists()) handle.refresh();
    }

    private void compile() {
        try {
            generator = Generator.compile(state.spec(), Seeds.parse(state.seed()));
            error = null;
        } catch (IllegalArgumentException e) {
            generator = null;
            error = e.getMessage();
        }
    }

    /** The app's dialogs, look and main window. */
    io.blockdesigner.plugin.ui.PluginUi ui() {
        return ctx.ui();
    }

    /** Brings the Terrain page forward (the preview object's menu). */
    void showPanel() {
        ctx.showPanel(PAGE);
    }

    /** The dot on the Terrain page's button: baking, or what went wrong; none otherwise. */
    private void updatePanelStatus() {
        if (baking) ctx.setPanelStatus(PAGE, io.blockdesigner.plugin.ui.Tone.ACCENT, "Baking… " + Math.round(bakeProgress * 100) + "%");
        else if (bakeFailed) ctx.setPanelStatus(PAGE, io.blockdesigner.plugin.ui.Tone.DANGER, bakeMessage);
        else if (error != null) ctx.setPanelStatus(PAGE, io.blockdesigner.plugin.ui.Tone.DANGER, error);
        else ctx.setPanelStatus(PAGE, null, null);
    }

    private void fire() {
        try {
            updatePanelStatus();
        } catch (RuntimeException e) {
            ctx.log("Terrain page status failed: " + e);
        }
        for (Runnable r : List.copyOf(listeners)) {
            try {
                r.run();
            } catch (RuntimeException e) {
                ctx.log("Terrain listener failed: " + e);
            }
        }
    }

    // ---- the preview object -----------------------------------------------------------------------------------

    /** The project's Terrain preview, if it has one. */
    ObjectHandle handle() {
        return handle != null && handle.exists() ? handle : null;
    }

    /** Finds the project's preview object (a project was opened, or it came back with undo) and takes its state. */
    void bind() {
        handle = null;
        for (ObjectHandle h : ctx.objects().list()) {
            if (h.type().equals(TerrainPreview.TYPE) && h.object() instanceof TerrainPreview p) {
                handle = h;
                adopt(p.state());
                break;
            }
        }
        if (mapPixels == null) {
            mapGeneration.incrementAndGet();
            drawMap();
        }
        fire();
    }

    /** The preview object told us its state changed from outside (undo, redo). */
    void objectLoaded(TerrainPreview p) {
        ObjectHandle h = handle();
        if (h == null) {
            bind();
            return;
        }
        if (h.object() == p) adopt(p.state());
    }

    /** Writes the state into the project, adding the preview object the first time. */
    private void commit() {
        ObjectHandle h = handle();
        if (h == null) {
            // It may have been deleted and brought back, or added in another way.
            for (ObjectHandle o : ctx.objects().list()) if (o.type().equals(TerrainPreview.TYPE) && o.object() instanceof TerrainPreview) h = o;
        }
        if (h == null) {
            TerrainPreview p = new TerrainPreview(this, state);
            handle = ctx.objects().add(TerrainPreview.TYPE, "Terrain preview", Pose.at(0, state.spec().world().seaLevel(), 0), p);
            return;
        }
        handle = h;
        TerrainPreview p = (TerrainPreview) h.object();
        if (p.state().equals(state)) return;
        TerrainState s = state;
        h.edit(commitLabel, () -> p.set(s));
    }

    /** Shows or hides the map and region in the 3D view. */
    void setShownInView(boolean shown) {
        ObjectHandle h = handle();
        if (h == null) {
            if (!shown) return;
            commitDebounce.stop();
            commit();
            return;
        }
        if (h.visible() != shown) h.setVisible(shown);
        fire();
    }

    boolean shownInView() {
        ObjectHandle h = handle();
        return h != null && h.visible();
    }

    // ---- the map ----------------------------------------------------------------------------------------------

    int[] mapPixels() {
        return mapPixels;
    }

    ImageData mapImage() {
        return mapImage;
    }

    int mapCentreX() {
        return mapCentreX;
    }

    int mapCentreZ() {
        return mapCentreZ;
    }

    int mapStep() {
        return mapStep;
    }

    long mapMillis() {
        return mapMillis;
    }

    boolean mapUpToDate() {
        return mapPixels != null && mapCentreX == state.centreX() && mapCentreZ == state.centreZ() && mapStep == state.zoom();
    }

    private void drawMap() {
        Generator g = generator;
        if (g == null) return;
        long gen = mapGeneration.get();
        int cx = state.centreX(), cz = state.centreZ(), step = state.zoom();
        mapWorker.submit(() -> {
            long t = System.nanoTime();
            int[] px;
            try {
                px = maps.render(g, cx, cz, MAP, step, () -> mapGeneration.get() != gen);
            } catch (RuntimeException e) {
                Platform.runLater(() -> ctx.log("Map failed: " + e));
                return;
            }
            if (px == null) return;
            long ms = (System.nanoTime() - t) / 1_000_000;
            Platform.runLater(() -> {
                if (mapGeneration.get() != gen) return;
                mapPixels = px;
                mapImage = new ImageData(MAP, MAP, px);
                mapCentreX = cx;
                mapCentreZ = cz;
                mapStep = step;
                mapMillis = ms;
                fire();
                ObjectHandle h = handle();
                if (h != null) h.refresh();
            });
        });
    }

    // ---- baking -----------------------------------------------------------------------------------------------

    boolean baking() {
        return baking;
    }

    double bakeProgress() {
        return bakeProgress;
    }

    String bakeMessage() {
        return bakeMessage;
    }

    boolean bakeFailed() {
        return bakeFailed;
    }

    void cancelBake() {
        cancelBake = true;
    }

    /** Starts baking the region into a new layer; returns a message saying so, or why not. */
    String bake() {
        if (baking) return "Already baking";
        Generator g = generator;
        if (g == null) return "The generator has an error: " + error;
        TerrainState s = state;
        Baker.Region r = Baker.Region.of(s);
        if (r.estimate(g.world().minY(), 90) > 80_000_000L) {
            return "That region is very large: raise \"Down to Y\", pick a smaller size or bake voxels as a model";
        }
        baking = true;
        cancelBake = false;
        bakeProgress = 0;
        bakeFailed = false;
        String size = s.sizeX() + "×" + s.sizeZ();
        bakeMessage = "Baking " + size + "…";
        fire();
        String voxels = s.voxel() == 1 ? "" : " · " + s.voxel() + "-block voxels" + (s.expanded() ? "" : " (model)");
        String name = "Terrain · " + s.spec().name() + " · seed " + s.seed().strip() + " · " + size + voxels;
        Baker baker = new Baker(id -> ctx.blocks().resolve(id));
        Thread t = new Thread(() -> {
            long start = System.nanoTime();
            Structure out;
            try {
                out = baker.bake(g, r, p -> Platform.runLater(() -> {
                    bakeProgress = p;
                    fire();
                }), () -> cancelBake);
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    ctx.log("Bake failed: " + e);
                    finishBake("✖ " + e.getMessage(), true);
                });
                return;
            }
            double secs = (System.nanoTime() - start) / 1e9;
            Platform.runLater(() -> {
                if (out == null) {
                    finishBake("Cancelled.", false);
                    return;
                }
                ctx.addLayer(name, out);
                finishBake(String.format("Baked %,d blocks in %.1f s into a new layer. Undo removes it.", out.blockCount(), secs), false);
                ctx.toast(String.format("Terrain baked · %,d blocks", out.blockCount()));
            });
        }, "terrain-bake");
        t.setDaemon(true);
        t.start();
        return "Baking " + size + "…";
    }

    private void finishBake(String message, boolean failed) {
        baking = false;
        bakeMessage = message;
        bakeFailed = failed;
        fire();
    }

    // ---- memory -----------------------------------------------------------------------------------------------

    private Path file() {
        return ctx.dataFolder().resolve("last.json");
    }

    private void remember() {
        try {
            Files.writeString(file(), state.toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ctx.log("Could not remember the terrain settings: " + e.getMessage());
        }
    }

    private TerrainState remembered() {
        try {
            if (Files.isRegularFile(file())) {
                TerrainState s = TerrainState.fromJson(Files.readString(file()));
                return s.seed().isBlank() ? s.withSeed(randomSeed()) : s;
            }
        } catch (IOException | RuntimeException e) {
            ctx.log("Could not read the last terrain settings: " + e.getMessage());
        }
        return TerrainState.initial(randomSeed());
    }

    void ctxToast(String message) {
        ctx.toast(message);
    }

    static String randomSeed() {
        return Long.toString(new Random().nextLong() % 10_000_000_000L);
    }

    void dispose() {
        cancelBake = true;
        mapGeneration.incrementAndGet();
        mapDebounce.stop();
        commitDebounce.stop();
        mapWorker.shutdownNow();
        listeners.clear();
    }
}
