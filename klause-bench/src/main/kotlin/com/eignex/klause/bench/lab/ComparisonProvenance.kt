package com.eignex.klause.bench.lab

import com.eignex.klause.bench.metric.BuildProvenance
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarFile

internal object ComparisonProvenance {
    internal data class Identities(val harness: Map<String, String>, val libraries: Map<String, String>) {
        val harnessFingerprint: String get() = fingerprint(harness)
        val libraryFingerprint: String get() = fingerprint(libraries)
        val retained: Map<String, String> get() = harness.mapKeys { "classes/${it.key}" } +
            libraries.mapKeys { "classpath/${it.key}" }
    }

    val current: Identities by lazy {
        capture(
            File(ComparisonProvenance::class.java.protectionDomain.codeSource.location.toURI()),
            System.getProperty("java.class.path").split(File.pathSeparator).map(::File),
        )
    }

    fun runtimeFingerprint(build: BuildProvenance): String = build.copy(
        distribution = build.distribution.filterKeys { !solverLibrary(it.substringAfterLast('/')) },
        harness = emptyMap(),
        runtimeOptions = build.runtimeOptions - "KLAUSE_CLI_OPTS" - "KLAUSE_CLI_PREFLIGHT_OPTS",
    ).fingerprint

    internal fun capture(location: File, classpath: List<File>): Identities {
        val root = location.canonicalFile
        val classes = if (root.isDirectory) {
            root.resolve(PREFIX).walkTopDown().filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(root).invariantSeparatorsPath to hash(it) }.toList()
        } else {
            JarFile(root).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(PREFIX) && it.name.endsWith(".class") }
                    .map { entry -> entry.name to jar.getInputStream(entry).use { sha256(it.readBytes()) } }.toList()
            }
        }
        require(classes.isNotEmpty()) { "Bench harness classes unavailable at $location" }
        val libraries = classpath.map(File::getCanonicalFile)
            .filter { it.isFile && it.extension == "jar" && it != root && !solverLibrary(it.name) }
            .mapIndexed { index, file -> "$index/${file.name}" to hash(file) }.toMap()
        require(libraries.isNotEmpty()) { "Bench runtime libraries unavailable" }
        return Identities(classes.sortedBy { it.first }.toMap(), libraries)
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun solverLibrary(name: String): Boolean =
        (name.startsWith("klause-jvm-") || name.startsWith("klause-cli-jvm-")) && name.endsWith(".jar")

    private fun fingerprint(hashes: Map<String, String>): String =
        sha256(Reports.json.encodeToString(hashes).toByteArray())

    private const val PREFIX = "com/eignex/klause/bench/"
}
