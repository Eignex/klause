import java.io.FileOutputStream
import java.net.URI
import org.gradle.process.ExecOperations

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

// --- Choco as a faithful MiniZinc solver (`minizinc --solver choco`) ---
// Choco is run end-to-end through MiniZinc (its own globals library), not via an in-process adapter.
// choco-parsers isn't a runnable MiniZinc solver out of the box, so `installChoco` fetches the
// FlatZinc parser jar + Choco's `mzn_lib` + the official `fzn-choco` wrapper, writes a `choco.msc`
// pointing at them, and registers it under ~/.minizinc/solvers. Mirrors `:klause-yuck:installYuck`.
val chocoVersion = "6.0.1"
val chocoCacheDir = File(System.getProperty("user.home"), ".cache/klause-choco")

abstract class InstallChocoTask : DefaultTask() {
    @get:Input abstract val version: Property<String>

    @get:Internal abstract val cacheDir: Property<File>

    @get:Inject abstract val exec: ExecOperations

    private fun download(url: String, dest: File) {
        logger.lifecycle("Downloading $url")
        val tmp = File.createTempFile(dest.name, ".part", dest.parentFile)
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
    }

    @TaskAction
    fun install() {
        val v = version.get()
        val cache = cacheDir.get().apply { mkdirs() }
        val jar = cache.resolve("choco-parsers-$v-light.jar")
        if (!jar.isFile) {
            download(
                "https://repo1.maven.org/maven2/org/choco-solver/choco-parsers/$v/choco-parsers-$v-light.jar",
                jar,
            )
        }
        val mznLib = cache.resolve("mzn_lib")
        val sh = cache.resolve("fzn-choco.sh")
        val py = cache.resolve("fzn-choco.py")
        if (!mznLib.isDirectory) {
            val tgz = cache.resolve("choco-src-$v.tgz")
            if (!tgz.isFile) download("https://github.com/chocoteam/choco-solver/archive/refs/tags/v$v.tar.gz", tgz)
            val sub = "choco-solver-$v/parsers/src/main/minizinc"
            exec.exec {
                commandLine(
                    "tar", "xzf", tgz.absolutePath, "-C", cache.absolutePath,
                    "$sub/mzn_lib", "$sub/fzn-choco.py", "$sub/fzn-choco.sh",
                )
            }
            val ex = cache.resolve(sub)
            ex.resolve("mzn_lib").copyRecursively(mznLib, overwrite = true)
            ex.resolve("fzn-choco.py").copyTo(py, overwrite = true)
            ex.resolve("fzn-choco.sh").copyTo(sh, overwrite = true)
        }
        // Point the official wrapper at the cached jar and make it runnable.
        exec.exec { commandLine("sed", "-i", "s#^JAR_FILE=.*#JAR_FILE='${jar.absolutePath}'#", py.absolutePath) }
        exec.exec { commandLine("chmod", "+x", sh.absolutePath, py.absolutePath) }
        // Write the solver config and register it so `minizinc --solver choco` resolves.
        val msc = cache.resolve("choco.msc")
        msc.writeText(
            """
            {
              "id": "org.choco.choco",
              "name": "Choco",
              "version": "$v",
              "mznlib": "${mznLib.absolutePath}",
              "executable": "${sh.absolutePath}",
              "tags": ["cp", "int", "choco"],
              "stdFlags": ["-a", "-f", "-p", "-t"],
              "supportsMzn": false,
              "supportsFzn": true,
              "needsSolns2Out": true,
              "isGUIApplication": false
            }
            """.trimIndent(),
        )
        val solvers = File(System.getProperty("user.home"), ".minizinc/solvers").apply { mkdirs() }
        msc.copyTo(solvers.resolve("choco.msc"), overwrite = true)
        logger.lifecycle("Registered Choco MiniZinc solver at ${solvers.resolve("choco.msc")}")
    }
}

val installChoco by tasks.registering(InstallChocoTask::class) {
    description = "Provision Choco as a MiniZinc solver (choco-parsers jar + mzn_lib + registered choco.msc)."
    version.set(chocoVersion)
    cacheDir.set(chocoCacheDir)
    outputs.dir(chocoCacheDir)
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
