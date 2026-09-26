// The generator: noises, the node graph, its JSON format, the compiler and the chunk filler. No dependencies, so the
// same code runs anywhere; it knows blocks only as ids like "minecraft:stone".
plugins {
    `java-library`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(26)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing,-classfile,-options", "-parameters"))
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    // ./gradlew :runtime:test --tests "*ProbeTest*" -Dprobe=true prints hashes, timings and a block census.
    systemProperty("probe", System.getProperty("probe") ?: "false")
}

// The same determinism tests in the interpreter: the output must not depend on JIT choices.
val testInterpreted by tasks.registering(Test::class) {
    description = "Runs the determinism tests with -Xint."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("determinism") }
    jvmArgs("-Xint")
}
tasks.check { dependsOn(testInterpreted) }
