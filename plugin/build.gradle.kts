// The BlockDesigner plugin. It compiles against the BlockDesigner plugin API jars in libs/; BlockDesigner provides them
// and JavaFX at runtime, so they are never bundled. The runtime module is built into the jar.
plugins {
    `java-library`
    alias(libs.plugins.javafx)
}

base {
    archivesName = "terrain-generator"
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(26)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing,-classfile", "-parameters"))
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls")
    configuration = "compileOnly"
}

// The BlockDesigner API this plugin targets (from BlockDesigner 0.4.18).
val blockDesigner = rootProject.files("libs/blockdesigner-plugin-api-0.4.18.jar", "libs/blockdesigner-core-0.4.18.jar")

dependencies {
    implementation(project(":runtime"))
    compileOnly(blockDesigner)

    testImplementation(blockDesigner)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// The manifest's version is this project's.
tasks.named<ProcessResources>("processResources") {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("blockdesigner-plugin.json") { filter { it.replace("@VERSION@", v) } }
}

// One jar: the plugin plus the runtime's classes and presets.
tasks.jar {
    dependsOn(":runtime:jar")
    from(project(":runtime").sourceSets.main.get().output)
}
