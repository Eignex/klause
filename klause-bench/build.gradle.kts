import java.io.FileOutputStream

plugins {
    id("com.eignex.jvm") version "1.2.6"
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
    implementation(project(":klause-logicng"))
    implementation(project(":klause-choco"))
    implementation(project(":klause-ortools"))
    implementation(project(":klause-yuck"))
    // SolveStats exposes kumulant summary types (SumResult/MaxResult); needed to read them.
    implementation("com.eignex:kumulant:0.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    // runBlocking + Flow.collect bridge for the suspend Portfolio API in the anytime metric.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
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

/** Single bench entry point: `./gradlew :klause-bench:bench --args="<target-id>"`, or
 *  `--args="list"` to enumerate targets. Each target binds catalog suites to a metric. */
tasks.register<JavaExec>("bench") {
    group = "bench"
    description = "Run a bench target by id. Use --args=\"list\" to enumerate targets."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.target.BenchCli")
    val workspaceRoot = rootDir.absolutePath
    forwardBenchProps()
    // Opt-in CPU profiling: -PasyncProfiler=/path/to/libasyncProfiler.so [-PprofOut=...] attaches
    // async-profiler to the forked bench JVM and writes a flat top-method list on exit. Pair with
    // a long-running, single-engine target (e.g. diag:backtrack) for a clean CDCL hot-path view.
    (findProperty("asyncProfiler") as String?)?.let { agent ->
        val out = (findProperty("profOut") as String?) ?: "$workspaceRoot/build/prof.txt"
        val event = (findProperty("profEvent") as String?) ?: "cpu" // cpu | alloc | wall
        // -PprofFormat overrides the output mode: "flat=60" (default top-method list) or e.g.
        // "traces=30" for the hottest call stacks (shows callers, not just self-time).
        val format = (findProperty("profFormat") as String?) ?: "flat=60"
        jvmArgs("-agentpath:$agent=start,event=$event,$format,file=$out")
    }
    doFirst { systemProperty("klause.workspace.root", workspaceRoot) }
}

tasks.register("dumpSchema", JavaExec::class) {
    group = "tools"
    description = "Regenerate bundled JSON SchemaDef sample at corpus/schema/campaign.json."
    notCompatibleWithConfigurationCache(
        "JavaExec.standardOutput is not serialisable into the configuration cache",
    )
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.tools.SchemaDumperKt")
    doFirst {
        standardOutput = FileOutputStream(
            layout.projectDirectory.file("corpus/schema/campaign.json").asFile,
        )
    }
}

tasks.register<Copy>("saveBaseline") {
    group = "tools"
    description = "Copy the latest bench-time.json over bench-baseline.json."
    from(layout.buildDirectory.file("bench-time.json"))
    into(layout.projectDirectory)
    rename { "bench-baseline.json" }
}

tasks.withType<Test> {
    maxHeapSize = "4g"
}
