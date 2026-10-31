import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    id("com.eignex.jvm") version "1.3.5"
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

val labMacOs = System.getProperty("os.name").startsWith("Mac")

repositories {
    exclusiveContent {
        forRepository {
            ivy {
                name = "labBenchReleases"
                url = uri(providers.gradleProperty("labBenchRepository")
                    .getOrElse("https://github.com/Eignex/eignex-lab/releases/download"))
                patternLayout { artifact("lab-bench-v[revision]/[artifact]-[revision](-[classifier]).[ext]") }
                metadataSources { gradleMetadata() }
            }
        }
        filter { includeModule("com.eignex", "lab-bench") }
    }
    exclusiveContent {
        forRepository {
            ivy {
                name = "miniZincReleases"
                url = uri(if (labMacOs) "https://github.com/MiniZinc/libminizinc/releases/download"
                    else "https://github.com/MiniZinc/MiniZincIDE/releases/download")
                patternLayout {
                    artifact(if (labMacOs) "[revision]/minizinc-compiler-only-[classifier].[ext]"
                        else "[revision]/MiniZincIDE-[revision]-[classifier].[ext]")
                }
                metadataSources { artifact() }
            }
        }
        filter { includeModule("org.minizinc", "minizinc") }
    }
}
val labBenchVersion = providers.gradleProperty("labBenchVersion").getOrElse("0.1.0")
val labMiniZinc = configurations.create("labMiniZinc") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val labMiniZincTarget = when {
    labMacOs -> when (System.getProperty("os.arch")) {
        "aarch64", "arm64" -> "aarch64-apple-darwin" to "aeb1a25471ad753e67c2a73034b0725dff7c4d011d23cc8c8c91499efdb26f29"
        "amd64", "x86_64" -> "x86_64-apple-darwin" to "02b7ce514714d57c50865e4c2458169eb76fb18bb789af26b45026a362460d06"
        else -> null
    }
    System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64") ->
        "x86_64-linux-gnu" to "b63dd88491d47b05d0eb0ff4aa1112d1e6f77292c023800b20b98851458ce01c"
    else -> null
}

dependencies {
    labMiniZincTarget?.let { (target, _) ->
        add(labMiniZinc.name, "org.minizinc:minizinc:2.10.1:$target@${if (labMacOs) "tar.gz" else "tgz"}")
    }
    implementation("com.eignex:lab-bench:$labBenchVersion")
    implementation(project(":klause"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
    testImplementation("org.junit.jupiter:junit-jupiter")
    // SolveStats exposes kumulant summary types (SumResult/MaxResult); needed to read them.
    implementation("com.eignex:kumulant:0.3.4-20261031.205003-119")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.tukaani:xz:1.12")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.luben:zstd-jni:1.5.7-24")
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

val prepareLabRuntime = tasks.register("prepareLabRuntime") {
    group = "build"
    description = "Stage the pinned host MiniZinc compiler and standard library for lab jobs."
    notCompatibleWithConfigurationCache("Uses Gradle archive operations to stage the compiler")
    val destination = layout.buildDirectory.dir("lab-runtime/minizinc")
    val archive = labMiniZinc
    val checksum = labMiniZincTarget?.second.orEmpty()
    inputs.files(archive)
    inputs.property("checksum", checksum)
    outputs.dir(destination)
    doLast {
        requireNotNull(labMiniZincTarget) { "Lab MiniZinc runtime supports macOS ARM64/x86_64 and Linux x86_64" }
        val file = archive.singleFile
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        check(HexFormat.of().formatHex(digest.digest()) == checksum) { "MiniZinc release checksum mismatch" }
        project.sync {
            from(tarTree(resources.gzip(file)))
            if (labMacOs) include("bin/minizinc", "share/minizinc/std/**")
            else {
                include("*/bin/minizinc", "*/share/minizinc/std/**")
                eachFile { relativePath = RelativePath(!isDirectory, *relativePath.segments.drop(1).toTypedArray()) }
            }
            includeEmptyDirs = false
            into(destination)
        }
        val root = destination.get().asFile
        check(root.resolve("bin/minizinc").setExecutable(true))
        root.resolve("sha256").writeText("$checksum\n")
    }
}
