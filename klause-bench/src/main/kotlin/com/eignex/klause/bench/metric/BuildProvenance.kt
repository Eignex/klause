package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
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
    // One campaign uses one installed build; replacing files during a campaign is unsupported.
    val current: BuildProvenance by lazy {
        val launcher = SolverInvocation.klauseCliBin()
        val environment = System.getenv()
        val java = environment["JAVA_HOME"]?.let { File(it, "bin/java") }
            ?: environment["PATH"].orEmpty().split(File.pathSeparator)
                .map { File(it, "java") }.firstOrNull { it.canExecute() }
            ?: error("No Java runtime on JAVA_HOME or PATH")
        capture(launcher, java.canonicalFile.parentFile.parentFile, environment)
    }

    fun capture(launcher: File, javaHome: File, environment: Map<String, String>): BuildProvenance {
        require(launcher.isFile) { "Missing installed CLI: $launcher" }
        val distribution = launcher.parentFile.parentFile
        require(File(distribution, "lib").isDirectory) { "Missing installed CLI libraries: $distribution" }
        require(File(javaHome, "bin/java").isFile) { "Missing Java runtime: $javaHome" }
        return BuildProvenance(
            distribution = hashes(distribution),
            runtime = hashes(javaHome),
            runtimeOptions = RUNTIME_OPTIONS.mapNotNull { name -> environment[name]?.let { name to it } }.toMap(),
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
