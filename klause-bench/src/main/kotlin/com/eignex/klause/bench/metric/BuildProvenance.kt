package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

@Serializable
internal data class BuildProvenance(
    val distribution: Map<String, String>,
    val runtime: Map<String, String>,
    val runtimeOptions: Map<String, String>,
    val osName: String,
    val osArch: String,
) {
    val fingerprint: String get() = sha256(Reports.json.encodeToString(this).toByteArray())
}

internal object InstalledBuild {
    // A campaign's installed distribution and runtime stay fixed after setup.
    val current: BuildProvenance by lazy {
        val launcher = SolverInvocation.klauseCliBin()
        val environment = System.getenv()
        val javaHome = javaHome(environment)
        val manifest = environment["KLAUSE_BENCH_PROVENANCE"]?.let(::File)
        if (manifest == null) {
            capture(launcher, javaHome, environment)
        } else {
            readManifest(manifest, launcher, javaHome, environment)
        }
    }

    fun writeManifest(output: File) {
        writeManifest(output, SolverInvocation.klauseCliBin(), javaHome(System.getenv()))
    }

    fun writeManifest(output: File, launcher: File, javaHome: File) {
        val distribution = launcher.canonicalFile.parentFile.parentFile
        val runtime = javaHome.canonicalFile
        val destination = output.canonicalFile
        require(
            !destination.toPath().startsWith(distribution.toPath()) &&
                !destination.toPath().startsWith(runtime.toPath()),
        ) { "Manifest must be outside its captured trees" }
        val distributionFiles = metadata(distribution)
        val runtimeFiles = metadata(runtime)
        val provenance = capture(launcher, runtime, emptyMap())
        require(distributionFiles == metadata(distribution) && runtimeFiles == metadata(runtime)) {
            "Installed files changed during provenance capture"
        }
        val manifest = ProvenanceManifest(
            launcher = launcher.canonicalPath,
            javaHome = runtime.path,
            provenance = provenance,
            distributionFiles = distributionFiles,
            runtimeFiles = runtimeFiles,
        )
        destination.parentFile.mkdirs()
        val temporary = File.createTempFile("provenance-", ".json", destination.parentFile)
        try {
            temporary.writeText(Reports.json.encodeToString(manifest))
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    fun readManifest(input: File, launcher: File, javaHome: File, environment: Map<String, String>): BuildProvenance {
        val manifest = Reports.json.decodeFromString<ProvenanceManifest>(input.readText())
        require(manifest.version == 1) { "Unsupported provenance manifest version: ${manifest.version}" }
        require(manifest.launcher == launcher.canonicalPath && manifest.javaHome == javaHome.canonicalPath) {
            "Provenance manifest belongs to another distribution or runtime"
        }
        require(
            manifest.distributionFiles == metadata(launcher.canonicalFile.parentFile.parentFile) &&
                manifest.runtimeFiles == metadata(javaHome.canonicalFile),
        ) {
            "Installed files changed after provenance capture; regenerate the manifest"
        }
        require(
            manifest.provenance.osName == System.getProperty("os.name") &&
                manifest.provenance.osArch == System.getProperty("os.arch"),
        ) {
            "Provenance manifest belongs to another host platform"
        }
        return manifest.provenance.copy(runtimeOptions = runtimeOptions(environment))
    }

    private fun javaHome(environment: Map<String, String>): File {
        val java = environment["JAVA_HOME"]?.let { File(it, "bin/java") }
            ?: environment["PATH"].orEmpty().split(File.pathSeparator)
                .map { File(it, "java") }.firstOrNull { it.canExecute() }
            ?: error("No Java runtime on JAVA_HOME or PATH")
        return java.canonicalFile.parentFile.parentFile
    }

    private fun metadata(root: File): Map<String, InstalledFile> = root.walkTopDown().filter { it.isFile }
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.associate { file ->
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            val changed = runCatching { Files.getAttribute(file.toPath(), "unix:ctime").toString() }.getOrNull()
            require(changed != null && attributes.fileKey() != null) {
                "Provenance manifests require file identity and change time: $file"
            }
            file.relativeTo(root).invariantSeparatorsPath to InstalledFile(
                attributes.size(),
                attributes.lastModifiedTime().toString(),
                changed,
                attributes.fileKey().toString(),
            )
        }

    fun capture(launcher: File, javaHome: File, environment: Map<String, String>): BuildProvenance {
        require(launcher.isFile) { "Missing installed CLI: $launcher" }
        val distribution = launcher.canonicalFile.parentFile.parentFile
        require(File(distribution, "lib").isDirectory) { "Missing installed CLI libraries: $distribution" }
        require(File(javaHome, "bin/java").isFile) { "Missing Java runtime: $javaHome" }
        return BuildProvenance(
            distribution = hashes(distribution),
            runtime = hashes(javaHome),
            runtimeOptions = runtimeOptions(environment),
            osName = System.getProperty("os.name"),
            osArch = System.getProperty("os.arch"),
        )
    }

    private fun hashes(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        .associate { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            file.relativeTo(root).invariantSeparatorsPath to hex(digest.digest())
        }
}

@Serializable
internal data class ProvenanceManifest(
    val version: Int = 1,
    val launcher: String,
    val javaHome: String,
    val provenance: BuildProvenance,
    val distributionFiles: Map<String, InstalledFile>,
    val runtimeFiles: Map<String, InstalledFile>,
)

@Serializable
internal data class InstalledFile(val size: Long, val modified: String, val changed: String, val identity: String)

internal fun runtimeOptions(environment: Map<String, String>): Map<String, String> =
    RUNTIME_OPTIONS.mapNotNull { name ->
        environment[name]?.let { value ->
            val normalized = if (name == "LD_LIBRARY_PATH") {
                value
            } else {
                OPTION_WORD.findAll(value)
                    .map { it.value }.filterNot { word ->
                        val option = word.trimStart('\'', '"')
                        option.startsWith("-Dklause.bench.cache=") ||
                            option.startsWith("-Dklause.bench.corpusCache=") ||
                            option.startsWith("-Dklause.workspace.root=")
                    }.joinToString(" ")
            }
            normalized.takeIf { it.isNotEmpty() }?.let { name to it }
        }
    }.toMap()

private val OPTION_WORD = Regex("""(?:[^\s"'\\]|\\.|"(?:[^"\\]|\\.)*"|'[^']*')+""")

private val RUNTIME_OPTIONS = listOf(
    "JAVA_OPTS",
    "KLAUSE_CLI_OPTS",
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "_JAVA_OPTIONS",
    "LD_LIBRARY_PATH",
)

internal fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun hex(bytes: ByteArray): String = bytes.joinToString("") {
    (it.toInt() and 0xFF).toString(16).padStart(2, '0')
}
