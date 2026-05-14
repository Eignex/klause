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
    implementation(project(":klause-z3"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.eignex.klause.bench.TimeBenchMainKt")
}

tasks.register<JavaExec>("runTime") {
    group = "bench"
    description = "Time + propagation microbench. Writes build/bench-time.json."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.TimeBenchMainKt")
}

tasks.register<JavaExec>("runUniformness") {
    group = "bench"
    description = "Sampling-uniformness bench (coverage, KL, Hamming, entropy). Writes build/bench-uniformness.json."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.UniformnessBenchMainKt")
}

tasks.register<JavaExec>("runCompleteness") {
    group = "bench"
    description = "Enumeration reach-under-budget bench. Writes build/bench-completeness.json."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eignex.klause.bench.CompletenessBenchMainKt")
}

/** One-shot task: regenerate the bundled JSON-SchemaDef sample file. Run as
 *  `./gradlew :klause-bench:dumpSchema`. */
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
            layout.projectDirectory.file("src/main/resources/schema/campaign.json").asFile,
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
