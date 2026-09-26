package io.blockdesigner.terragen.plugin;

import io.blockdesigner.plugin.BlockDesignerPlugin;
import io.blockdesigner.plugin.PluginContext;

/**
 * Registers the Terrain panel, the Terrain preview object (kept in the project), the Terrain region tool and the
 * {@code /terrain} command. Everything it adds is removed again when the plugin is disabled.
 */
public final class TerrainGeneratorPlugin implements BlockDesignerPlugin {
    private TerrainSession session;

    @Override
    public void enable(PluginContext ctx) {
        session = new TerrainSession(ctx);
        ctx.registerObjectType(TerrainPreview.type(session));
        ctx.registerPanel(new TerrainPanel(session));
        ctx.registerTool(new RegionTool(session));
        ctx.registerCommand(TerrainCommand.create(session));
        // Objects of our type in an already open project were made while it was off: find them now.
        session.bind();
    }

    @Override
    public void disable() {
        if (session != null) session.dispose();
        session = null;
    }
}
