package io.blockdesigner.terragen.plugin;

import io.blockdesigner.plugin.Drawing;
import io.blockdesigner.plugin.ImageData;
import io.blockdesigner.plugin.ObjectHandle;
import io.blockdesigner.plugin.Pose;
import io.blockdesigner.plugin.SceneObject;
import io.blockdesigner.plugin.SceneObjectType;
import io.blockdesigner.plugin.ToolEvent.Vec3;
import io.blockdesigner.plugin.ViewInfo;
import javafx.scene.control.MenuItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The Terrain preview: the generator's settings kept in the project (so they save and undo), drawn in the 3D view as
 * the map lying at sea level and a box around the bake region, with chunk lines inside it. It sits where the region is
 * whatever its pose, so moving it with the Move tool doesn't move the region; the map, the region tool and the panel do.
 */
final class TerrainPreview implements SceneObject {
    static final String TYPE = "terrain-preview";
    private static final int BOX = 0xE6FFFFFF, GRID = 0x40FFFFFF;

    private final TerrainSession session;
    private TerrainState state;

    TerrainPreview(TerrainSession session, TerrainState state) {
        this.session = session;
        this.state = state;
    }

    TerrainState state() {
        return state;
    }

    void set(TerrainState s) {
        state = s;
    }

    /** Makes preview objects: when a project is opened, and when the plugin adds one. */
    static SceneObjectType type(TerrainSession session) {
        return new SceneObjectType() {
            @Override
            public String id() {
                return TYPE;
            }

            @Override
            public String name() {
                return "Terrain preview";
            }

            @Override
            public String badge() {
                return "TERRAIN";
            }

            @Override
            public SceneObject create() {
                return new TerrainPreview(session, TerrainState.initial(""));
            }
        };
    }

    @Override
    public void draw(ViewInfo view, Drawing out) {
        ObjectHandle h = session.handle();
        boolean current = h != null && h.object() == this;
        TerrainState s = current ? session.state() : state;
        // The object's own space is the world moved by its pose; undo that, so everything sits where it belongs.
        Pose pose = h != null && current ? h.pose() : Pose.IDENTITY;
        double ox = pose.position().x(), oy = pose.position().y(), oz = pose.position().z();
        double sea = s.spec().world().seaLevel() - oy;
        if (current && session.mapImage() != null) {
            ImageData img = session.mapImage();
            double half = TerrainSession.MAP / 2.0 * session.mapStep();
            double cx = session.mapCentreX() - ox, cz = session.mapCentreZ() - oz;
            Vec3[] corners = {new Vec3(cx - half, sea, cz + half), new Vec3(cx + half, sea, cz + half), new Vec3(cx + half, sea, cz - half),
                    new Vec3(cx - half, sea, cz - half)};
            out.image(img, corners, new double[]{0, 1, 1, 1, 1, 0, 0, 0}, 1, Drawing.Depth.BEHIND_BLOCKS);
        }
        // The region: a box from the bottom up to a little above the sea, and the chunk grid on the map.
        double x0 = s.minX() - ox, z0 = s.minZ() - oz, x1 = x0 + s.sizeX(), z1 = z0 + s.sizeZ();
        double y0 = Math.max(s.bottomY(), s.spec().world().minY()) - oy, y1 = sea + 0.05;
        rect(out, x0, z0, x1, z1, y1, BOX);
        rect(out, x0, z0, x1, z1, y0, BOX);
        for (double[] c : new double[][]{{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}}) out.line(new Vec3(c[0], y0, c[1]), new Vec3(c[0], y1, c[1]), BOX);
        if (s.sizeX() <= 1024 && s.sizeZ() <= 1024) {
            for (int x = Math.floorDiv(s.minX(), 16) * 16 + 16; x < s.minX() + s.sizeX(); x += 16) {
                out.line(new Vec3(x - ox, y1, z0), new Vec3(x - ox, y1, z1), GRID);
            }
            for (int z = Math.floorDiv(s.minZ(), 16) * 16 + 16; z < s.minZ() + s.sizeZ(); z += 16) {
                out.line(new Vec3(x0, y1, z - oz), new Vec3(x1, y1, z - oz), GRID);
            }
        }
    }

    private static void rect(Drawing out, double x0, double z0, double x1, double z1, double y, int argb) {
        out.line(new Vec3(x0, y, z0), new Vec3(x1, y, z0), argb);
        out.line(new Vec3(x1, y, z0), new Vec3(x1, y, z1), argb);
        out.line(new Vec3(x1, y, z1), new Vec3(x0, y, z1), argb);
        out.line(new Vec3(x0, y, z1), new Vec3(x0, y, z0), argb);
    }

    @Override
    public String description(ObjectHandle self) {
        TerrainState s = state;
        return s.spec().name() + " · seed " + s.seed().strip() + " · " + s.sizeX() + "×" + s.sizeZ() + " at " + s.centreX() + ", " + s.centreZ();
    }

    @Override
    public List<MenuItem> menu(ObjectHandle self) {
        MenuItem bake = new MenuItem("Bake the region to a new layer");
        bake.setOnAction(e -> session.ctxToast(session.bake()));
        MenuItem seed = new MenuItem("New random seed");
        seed.setOnAction(e -> session.update("New terrain seed", s -> s.withSeed(TerrainSession.randomSeed())));
        MenuItem panel = new MenuItem("Open the Terrain panel");
        panel.setOnAction(e -> session.showPanel());
        return List.of(bake, seed, panel);
    }

    @Override
    public byte[] save() {
        return state.toJson().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void load(byte[] data) throws IOException {
        try {
            state = TerrainState.fromJson(new String(data, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException("Unreadable terrain settings: " + e.getMessage(), e);
        }
        session.objectLoaded(this);
    }
}
