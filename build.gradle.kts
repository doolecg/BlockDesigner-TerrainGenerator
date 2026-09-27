// Terrain Generator, a BlockDesigner plugin released on its own. Build it with:  ./gradlew jar
// then install build/libs/terrain-generator-<version>.jar with Plugins > Manage plugins > Install.
version = "0.2.0"

subprojects {
    group = "io.blockdesigner.plugins"
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

// The plugin jar is copied to build/libs too, where releases are taken from (like the other plugin repositories).
tasks.register<Copy>("jar") {
    description = "Builds the plugin jar into build/libs."
    from(project(":plugin").tasks.named("jar"))
    into(layout.buildDirectory.dir("libs"))
}
