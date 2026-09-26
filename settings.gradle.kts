rootProject.name = "terrain-generator"

// runtime: the generator itself, plain Java with no dependencies (not even BlockDesigner).
// plugin: the BlockDesigner plugin; the runtime is built into its jar.
include("runtime", "plugin")
