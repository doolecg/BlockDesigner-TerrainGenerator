package io.blockdesigner.terragen.plugin;

import io.blockdesigner.core.model.BlockPos;
import io.blockdesigner.core.model.Box;
import io.blockdesigner.plugin.PluginTool;
import io.blockdesigner.plugin.ToolContext;
import io.blockdesigner.plugin.ToolEvent;
import io.blockdesigner.plugin.ToolHandler;

/**
 * The Terrain region tool: drag a rectangle on the ground to set what gets baked, or click to move the region there
 * keeping its size. While dragging, the wheel sets how deep the bake goes; Esc cancels.
 */
final class RegionTool implements PluginTool {
    private final TerrainSession session;

    RegionTool(TerrainSession session) {
        this.session = session;
    }

    @Override
    public String id() {
        return "region";
    }

    @Override
    public String name() {
        return "Terrain region";
    }

    @Override
    public String description() {
        return "drag a rectangle to set the terrain bake region · click to move it · wheel while dragging sets the depth · Esc cancels";
    }

    @Override
    public String icon() {
        return "M2 11 L6 5 L9 8 L12 4 L14 11 Z M1.5 1.5 H4 M1.5 1.5 V4 M14.5 1.5 H12 M14.5 1.5 V4 M1.5 14.5 H4 M1.5 14.5 V12 M14.5 14.5 H12 M14.5 14.5 V12";
    }

    @Override
    public ToolHandler activate(ToolContext ctx) {
        return new ToolHandler() {
            private BlockPos start, end;
            private int bottom;

            @Override
            public void press(ToolEvent e) {
                if (e.button() != ToolEvent.Button.PRIMARY) return;
                e.hit().ifPresent(h -> {
                    start = end = h.block();
                    bottom = session.state().bottomY();
                    show();
                });
            }

            @Override
            public void drag(ToolEvent e) {
                if (start == null) return;
                e.hit().ifPresent(h -> {
                    end = h.block();
                    show();
                });
            }

            @Override
            public void release(ToolEvent e) {
                if (start == null) return;
                BlockPos a = start, b = end;
                start = null;
                ctx.preview().clear();
                int sx = Math.abs(b.x() - a.x()) + 1, sz = Math.abs(b.z() - a.z()) + 1;
                if (sx <= 2 && sz <= 2) {
                    // A click: move the region here, same size.
                    session.update("Move terrain region", s -> s.withCentre(a.x(), a.z()).withBottom(bottom));
                } else {
                    int minX = Math.min(a.x(), b.x()), minZ = Math.min(a.z(), b.z());
                    session.update("Set terrain region", s -> s.withSize(sx, sz).withCentre(minX + sx / 2, minZ + sz / 2).withBottom(bottom));
                }
            }

            @Override
            public boolean scroll(ToolEvent e, double delta) {
                if (start == null) return false;
                bottom = Math.max(session.state().spec().world().minY(), Math.min(session.state().spec().world().maxY(),
                        bottom + (delta > 0 ? 8 : -8)));
                show();
                return true;
            }

            @Override
            public boolean key(String key) {
                if (!key.equals("Esc") || start == null) return false;
                start = null;
                ctx.preview().clear();
                return true;
            }

            @Override
            public void deactivate() {
                start = null;
            }

            private void show() {
                int top = Math.max(Math.max(start.y(), end.y()), session.state().spec().world().seaLevel()) + 8;
                ctx.preview().outline(new Box(Math.min(start.x(), end.x()), Math.min(bottom, top), Math.min(start.z(), end.z()),
                        Math.max(start.x(), end.x()), top, Math.max(start.z(), end.z())));
            }
        };
    }
}
