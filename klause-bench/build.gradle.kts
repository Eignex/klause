import java.io.FileOutputStream

plugins {
    id("com.eignex.jvm") version "1.3.4"
    kotlin("plugin.serialization")
    application
}

// Internal tooling module: use the build conventions but never publish.
eignexPublish {
    publish.set(false)
}

// Skip doc generation; the lintDocs/dokka gate trips on internal KDoc links, as in :klause.
tasks.withType<org.jetbrains.dokka.gradle.tasks.DokkaGenerateTask>().configureEach {
    enabled = false
}

dependencies {
    implementation(project(":klause"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
    testImplementation("org.junit.jupiter:junit-jupiter")
    // SolveStats exposes kumulant summary types (SumResult/MaxResult); needed to read them.
    implementation("com.eignex:kumulant:0.3.4-20261013.194059-67")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // runBlocking + Flow.collect bridge for the suspend Portfolio API in the anytime metric.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

application {
    mainClass.set("com.eignex.klause.bench.target.BenchCli")
}

/** Forward `-Dklause.*` props into the JavaExec child JVM so callers can tune any bench knob
 *  from the gradle invocation. doFirst keeps the property snapshot config-cache safe. */
fun JavaExec.forwardBenchProps() {
    doFirst {
        for ((k, v) in System.getProperties()) {
            val key = k.toString()
            if (key.startsWith("klause.")) systemProperty(key, v.toString())
        }
    }
}

/** Runs a bench command from the source tree: `./gradlew :klause-bench:bench --args="select suite=…"`. */
tasks.register<JavaExec>("bench") {
    group = "bench"
    description = "Run a bench command. Use --args=\"help\" to list them."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.target.BenchCli")
    val workspaceRoot = rootDir.absolutePath
    forwardBenchProps()
    doFirst { systemProperty("klause.workspace.root", workspaceRoot) }
}

tasks.register<JavaExec>("intDomainMicrobench") {
    group = "bench"
    description = "Run host-sensitive IntDomain representation timing probes."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.microbench.IntDomainMicrobench")
}

tasks.register("dumpSchema", JavaExec::class) {
    group = "tools"
    description = "Regenerate bundled JSON SchemaDef sample at smoke-corpus/schema/campaign.json."
    notCompatibleWithConfigurationCache(
        "JavaExec.standardOutput is not serialisable into the configuration cache",
    )
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.tools.SchemaDumperKt")
    doFirst {
        standardOutput = FileOutputStream(
            layout.projectDirectory.file("smoke-corpus/schema/campaign.json").asFile,
        )
    }
}

tasks.withType<Test> {
    maxHeapSize = "4g"
}

