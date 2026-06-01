import java.io.FileOutputStream
import java.net.URI

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":klause"))
    implementation(project(":klause-logicng"))
    implementation(project(":klause-choco"))
    implementation(project(":klause-ortools"))
    // SolveStats exposes kumulant summary types (SumResult/MaxResult); needed to read them.
    implementation("com.eignex:kumulant:0.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.eignex.klause.bench.target.BenchCli")
}

/** Forward any `-Dklause.*` props from the gradle invocation into the JavaExec child JVM so
 *  callers can tune every bench / selection / diagnostic knob (`klause.bench.*`,
 *  `klause.measure.*`, `klause.cblsdiag.*`, …) without editing source. Uses doFirst so the
 *  System.getProperties() snapshot is captured at execution time — config-cache safe. */
fun JavaExec.forwardBenchProps() {
    doFirst {
        for ((k, v) in System.getProperties()) {
            val key = k.toString()
            if (key.startsWith("klause.")) systemProperty(key, v.toString())
        }
    }
}

/** Single bench entry point. `./gradlew :klause-bench:bench --args="<target-id>"`; pass
 *  `list` (or no args) to see the available targets and catalog suites. Each target binds a
 *  set of catalog suites to a metric (time / uniformness / completeness / verify). */
tasks.register<JavaExec>("bench") {
    group = "bench"
    description = "Run a bench target by id. Use --args=\"list\" to enumerate targets."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.target.BenchCli")
    val workspaceRoot = rootDir.absolutePath
    forwardBenchProps()
    doFirst { systemProperty("klause.workspace.root", workspaceRoot) }
}

/** Print vendored suites + external collections (with license and cache status). */
tasks.register<JavaExec>("listCorpus") {
    group = "bench"
    description = "List catalog suites and external problem collections."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.tools.CorpusCli")
    args("list")
    val workspaceRoot = rootDir.absolutePath
    doFirst { systemProperty("klause.workspace.root", workspaceRoot) }
}

/** Pre-fetch external problem collections into the cache. `--args="warm <id|all>"`. */
tasks.register<JavaExec>("warmCorpus") {
    group = "bench"
    description = "Fetch external problem collections into the cache. Use --args=\"warm <id|all>\"."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.tools.CorpusCli")
    notCompatibleWithConfigurationCache("ProcessBuilder git/tar calls inside CorpusFetcher")
    val workspaceRoot = rootDir.absolutePath
    doFirst { systemProperty("klause.workspace.root", workspaceRoot) }
}

/** Opt-in: shallow-clone the MiniZinc Challenge benchmarks repo into `build/mzn/`.
 *  Content lives in the build dir only — never committed — and is governed by the
 *  upstream GPLv3 + per-problem licenses. */
tasks.register("downloadMzn") {
    group = "tools"
    description = "Shallow-clone MiniZinc-benchmarks repo into build/mzn/."
    notCompatibleWithConfigurationCache("ProcessBuilder calls inside doLast capture Project")
    val outDir = layout.buildDirectory.dir("mzn").get().asFile
    val rootDirFile = rootDir
    doLast {
        outDir.mkdirs()
        val target = File(outDir, "minizinc-benchmarks")
        if (target.exists() && target.list()?.isNotEmpty() == true) {
            logger.lifecycle("[mzn] already present at ${target.relativeTo(rootDirFile)}, skipping")
            return@doLast
        }
        logger.lifecycle("[mzn] cloning MiniZinc/minizinc-benchmarks (shallow)")
        val proc = ProcessBuilder(
            "git", "clone", "--depth", "1",
            "https://github.com/MiniZinc/minizinc-benchmarks.git",
            target.absolutePath,
        ).directory(outDir).inheritIO().start()
        val rc = proc.waitFor()
        require(rc == 0) { "git clone failed (exit $rc); make sure git is installed and the network is reachable" }
        val problems = target.list { f, _ -> f.isDirectory }?.size ?: 0
        logger.lifecycle("[mzn] ready: $problems problem directories under build/mzn/minizinc-benchmarks/")
        logger.lifecycle("[mzn] content licensed under GPLv3 plus per-problem licenses; do not redistribute outside this build dir")
    }
}

/** One-shot task: regenerate the bundled JSON-SchemaDef sample file. Run as
 *  `./gradlew :klause-bench:dumpSchema`. */
/** Opt-in: shallow-clone the libminizinc test suite into `build/mzn/libminizinc/`. The
 *  suite owns MiniZinc's own correctness coverage (compiler edge cases plus solver
 *  expectations under `tests/spec/unit/`); we use it as a stress source for parity. */
tasks.register("downloadMznTestSuite") {
    group = "tools"
    description = "Shallow-clone MiniZinc/libminizinc into build/mzn/libminizinc/."
    notCompatibleWithConfigurationCache("ProcessBuilder calls inside doLast capture Project")
    val outDir = layout.buildDirectory.dir("mzn").get().asFile
    val rootDirFile = rootDir
    doLast {
        outDir.mkdirs()
        val target = File(outDir, "libminizinc")
        if (target.exists() && target.list()?.isNotEmpty() == true) {
            logger.lifecycle("[mzn-tests] already present at ${target.relativeTo(rootDirFile)}, skipping")
            return@doLast
        }
        logger.lifecycle("[mzn-tests] cloning MiniZinc/libminizinc (shallow)")
        val proc = ProcessBuilder(
            "git", "clone", "--depth", "1",
            "https://github.com/MiniZinc/libminizinc.git",
            target.absolutePath,
        ).directory(outDir).inheritIO().start()
        val rc = proc.waitFor()
        require(rc == 0) { "git clone failed (exit $rc)" }
        val testCount = File(target, "tests/spec/unit").walk().count { it.isFile && it.extension == "mzn" }
        logger.lifecycle("[mzn-tests] ready: $testCount .mzn files under build/mzn/libminizinc/tests/spec/unit/")
    }
}

/** Opt-in: shallow-clone Hakank's MiniZinc model collection. Large, stylistically
 *  varied, and overlapping with mzn-bench in spots — useful as a "long tail" parity
 *  source. The repo is ~200 MB cloned. */
tasks.register("downloadMznHakank") {
    group = "tools"
    description = "Shallow-clone hakank/hakank into build/mzn/hakank/ (MiniZinc subset)."
    notCompatibleWithConfigurationCache("ProcessBuilder calls inside doLast capture Project")
    val outDir = layout.buildDirectory.dir("mzn").get().asFile
    val rootDirFile = rootDir
    doLast {
        outDir.mkdirs()
        val target = File(outDir, "hakank")
        if (target.exists() && target.list()?.isNotEmpty() == true) {
            logger.lifecycle("[hakank] already present at ${target.relativeTo(rootDirFile)}, skipping")
            return@doLast
        }
        logger.lifecycle("[hakank] cloning hakank/hakank (shallow, sparse-checkout for minizinc/)")
        // Sparse-checkout so we don't clone the whole 1+ GB repo just for the MiniZinc dir.
        val parent = outDir
        val initProc = ProcessBuilder("git", "clone", "--filter=blob:none", "--no-checkout",
            "--depth", "1", "https://github.com/hakank/hakank.git", target.absolutePath)
            .directory(parent).inheritIO().start()
        require(initProc.waitFor() == 0) { "git clone --no-checkout failed" }
        require(ProcessBuilder("git", "sparse-checkout", "set", "minizinc")
            .directory(target).inheritIO().start().waitFor() == 0) { "sparse-checkout set failed" }
        require(ProcessBuilder("git", "checkout").directory(target).inheritIO().start().waitFor() == 0) {
            "git checkout failed"
        }
        val mznCount = File(target, "minizinc").walk().count { it.isFile && it.extension == "mzn" }
        logger.lifecycle("[hakank] ready: $mznCount .mzn files under build/mzn/hakank/minizinc/")
    }
}

/** Run the MiniZinc parity sweep against one or more discovery sources and write a JSON
 *  report. See [com.eignex.klause.bench.parity.MznParitySweepMain] for properties. */
tasks.register<JavaExec>("runMznParity") {
    group = "verification"
    description = "MiniZinc parity sweep: compiles models against klause, compares to Gecode."
    notCompatibleWithConfigurationCache(
        "doFirst { ... } reads gradle.startParameter.systemPropertiesArgs at execution time " +
            "to forward -Dklause.parity.* knobs through to the JavaExec child, which the " +
            "configuration cache rejects",
    )
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.parity.MznParitySweepMain")
    // Reuse the bench-prop forwarding to pick up -Dklause.parity.* knobs. Capture the
    // workspace root at configuration time so doFirst doesn't reach back into Project.
    val workspaceRoot = rootDir.absolutePath
    doFirst {
        for ((k, v) in System.getProperties()) {
            val key = k.toString()
            if (key.startsWith("klause.parity.")) systemProperty(key, v.toString())
        }
        systemProperty("klause.workspace.root", workspaceRoot)
    }
    dependsOn(":klause-fzn-cli:installDist")
}

/** LS-vs-baseline anytime bench. Drives klause-LS and Yuck through `minizinc --solver`
 *  on the same instances, captures time-to-first/best and final objective for each. See
 *  [com.eignex.klause.bench.parity.LsBenchMain] for properties. */
tasks.register<JavaExec>("runLsBench") {
    group = "verification"
    description = "LS bench: klause-LS vs Yuck on the same MiniZinc instances."
    notCompatibleWithConfigurationCache(
        "doFirst reads gradle.startParameter.systemPropertiesArgs at execution time",
    )
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.parity.LsBenchMain")
    val workspaceRoot = rootDir.absolutePath
    doFirst {
        for ((k, v) in System.getProperties()) {
            val key = k.toString()
            if (key.startsWith("klause.lsbench.")) systemProperty(key, v.toString())
        }
        systemProperty("klause.workspace.root", workspaceRoot)
    }
    dependsOn(":klause-fzn-cli:installDist")
}

/** Solver-agnostic in-process sweep over an `.fzn` corpus. Optimization instances get an
 *  objective + avg-rank comparison of the configured solvers; satisfaction instances get a
 *  solve-rate / time comparison plus feature-based routing-rule regret. Built-in configs cover
 *  local search and complete backtracking; see [com.eignex.klause.bench.parity.SolverSweepMain]. */
tasks.register<JavaExec>("runSolverSweep") {
    group = "verification"
    description = "Solver-agnostic sweep: per-config objective/solve-rate + routing-rule regret."
    notCompatibleWithConfigurationCache(
        "doFirst reads gradle.startParameter.systemPropertiesArgs at execution time",
    )
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.parity.SolverSweepMain")
    val workspaceRoot = rootDir.absolutePath
    doFirst {
        for ((k, v) in System.getProperties()) {
            val key = k.toString()
            if (key.startsWith("klause.solversweep.")) systemProperty(key, v.toString())
        }
        systemProperty("klause.workspace.root", workspaceRoot)
    }
}

tasks.register<JavaExec>("runMeasureBacktrack") {
    group = "verification"
    description = "Run BacktrackSolver over a generated PHP / random-3SAT scaling series and dump SolveStats."
    notCompatibleWithConfigurationCache(
        "doFirst reads gradle.startParameter.systemPropertiesArgs at execution time",
    )
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.parity.MeasureBacktrackMainKt")
    doFirst {
        for ((k, v) in System.getProperties()) {
            val key = k.toString()
            if (key.startsWith("klause.measure.")) systemProperty(key, v.toString())
        }
        // Opt-in CPU profiling: -Dklause.measure.profile=<out> -Dklause.measure.asyncProfiler=<libasyncProfiler.so>
        val profOut = System.getProperty("klause.measure.profile")
        val profSo = System.getProperty("klause.measure.asyncProfiler")
        if (profOut != null && profSo != null) {
            jvmArgs("-agentpath:$profSo=start,event=cpu,flat=45,file=$profOut")
        }
    }
}

tasks.register<JavaExec>("runCblsDiag") {
    group = "verification"
    description = "Diagnose CBLS feasibility plateaus: cost trajectory + violated-class histogram + flat-gradient fraction."
    notCompatibleWithConfigurationCache(
        "doFirst reads gradle.startParameter.systemPropertiesArgs at execution time",
    )
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.parity.CblsDiagMain")
    doFirst {
        for ((k, v) in System.getProperties()) {
            val key = k.toString()
            if (key.startsWith("klause.cblsdiag.")) systemProperty(key, v.toString())
        }
    }
}

/** Compile-only audit across the corpus. Per instance: MiniZinc → FZN, parse constraint
 *  kinds, optional klause-fzn-cli ingest smoke. Useful for spotting MiniZinc-side
 *  decomposition and per-family compile breakage without running any solve. See
 *  [com.eignex.klause.bench.parity.LsCompileAuditMain]. */
tasks.register<JavaExec>("runLsCompileAudit") {
    group = "verification"
    description = "Compile-only audit: classify FZN constraint kinds across the corpus."
    notCompatibleWithConfigurationCache(
        "doFirst reads gradle.startParameter.systemPropertiesArgs at execution time",
    )
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.parity.LsCompileAuditMain")
    val workspaceRoot = rootDir.absolutePath
    doFirst {
        for ((k, v) in System.getProperties()) {
            val key = k.toString()
            if (key.startsWith("klause.lscompile.")) systemProperty(key, v.toString())
        }
        systemProperty("klause.workspace.root", workspaceRoot)
    }
    dependsOn(":klause-fzn-cli:installDist")
}

/** Opt-in: download SATLIB benchmark tarballs to `build/satlib/<set>/`. The bench
 *  harness picks them up automatically on the next `:klause-bench:run`. Tarballs are
 *  small (~300 KB each, 1000 instances of 20-50 vars). */
tasks.register("downloadSatlib") {
    group = "tools"
    description = "Download SATLIB uf20-91 (sat) and uuf50-218 (unsat) into build/satlib/."
    notCompatibleWithConfigurationCache("relativeTo(rootDir) call inside doLast captures Project")
    val outDir = layout.buildDirectory.dir("satlib").get().asFile
    val rootDirFile = rootDir
    doLast {
        outDir.mkdirs()
        val sets = listOf(
            "uf20-91" to "https://www.cs.ubc.ca/~hoos/SATLIB/Benchmarks/SAT/RND3SAT/uf20-91.tar.gz",
            "uuf50-218" to "https://www.cs.ubc.ca/~hoos/SATLIB/Benchmarks/SAT/RND3SAT/uuf50-218.tar.gz",
        )
        for ((name, url) in sets) {
            val dest = File(outDir, name)
            if (dest.exists() && dest.list()?.isNotEmpty() == true) {
                logger.lifecycle("[$name] already present at ${dest.relativeTo(rootDirFile)}, skipping")
                continue
            }
            dest.mkdirs()
            val tarball = File(outDir, "$name.tar.gz")
            logger.lifecycle("[$name] downloading from $url")
            URI(url).toURL().openStream().use { input ->
                tarball.outputStream().use { output -> input.copyTo(output) }
            }
            logger.lifecycle("[$name] extracting into ${dest.relativeTo(rootDirFile)}")
            // Tarballs vary in structure (uf20-91 ships flat; uuf50-218 has a top-level
            // dir). Extract as-is and rely on a recursive walk in the loader.
            val proc = ProcessBuilder("tar", "xzf", tarball.absolutePath, "-C", dest.absolutePath)
                .directory(outDir).inheritIO().start()
            val rc = proc.waitFor()
            require(rc == 0) { "tar xzf failed for $name (exit $rc)" }
            tarball.delete()
            val count = dest.walk().count { it.isFile && it.name.endsWith(".cnf") }
            logger.lifecycle("[$name] $count instances ready")
        }
    }
}

tasks.register("dumpSchema", JavaExec::class) {
    group = "tools"
    description = "Regenerate bundled JSON SchemaDef sample at resources/schema/campaign.json."
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

kotlin {
    jvmToolchain(24)
}
