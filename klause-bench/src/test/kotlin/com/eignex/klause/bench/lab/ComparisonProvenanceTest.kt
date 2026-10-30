package com.eignex.klause.bench.lab

import com.eignex.klause.bench.metric.BuildProvenance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals

class ComparisonProvenanceTest {
    @ParameterizedTest
    @ValueSource(strings = ["harness", "library", "solver"])
    fun `comparison identities separate harness and library changes from solver revisions`(kind: String) {
        val root = Files.createTempDirectory("comparison-provenance").toFile()
        try {
            val harness = root.resolve("bench.jar")
            val library = root.resolve("sdk.jar")
            val solver = root.resolve("klause-jvm-SNAPSHOT.jar")
            val classpath = listOf(harness, library, solver)
            for (file in classpath) {
                JarOutputStream(file.outputStream()).use { jar ->
                    jar.putNextEntry(JarEntry("com/eignex/klause/bench/Fixture.class"))
                    jar.write("original".toByteArray())
                    jar.closeEntry()
                }
            }
            val before = ComparisonProvenance.capture(harness, classpath)
            val changed = when (kind) {
                "harness" -> harness
                "library" -> library
                else -> solver
            }

            JarOutputStream(changed.outputStream()).use { jar ->
                jar.putNextEntry(JarEntry("com/eignex/klause/bench/Fixture.class"))
                jar.write("changed".toByteArray())
                jar.closeEntry()
            }
            val after = ComparisonProvenance.capture(harness, classpath)

            assertEquals(kind == "harness", before.harnessFingerprint != after.harnessFingerprint)
            assertEquals(kind == "library", before.libraryFingerprint != after.libraryFingerprint)
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["solver", "library", "launcher"])
    fun `runtime identity retains launchers and dependency bytes across solver revisions`(kind: String) {
        val before = BuildProvenance(
            mapOf(
            "bin/klause-cli" to "launcher",
            "lib/klause-jvm-SNAPSHOT.jar" to "solver",
            "lib/dependency.jar" to "library",
        ),
            mapOf("bin/java" to "java"), emptyMap(), "Linux", "amd64"
        )
        val key = when (kind) {
            "solver" -> "lib/klause-jvm-SNAPSHOT.jar"
            "library" -> "lib/dependency.jar"
            else -> "bin/klause-cli"
        }
        val after = before.copy(distribution = before.distribution + (key to "other"))

        assertEquals(
            kind == "solver",
            ComparisonProvenance.runtimeFingerprint(before) == ComparisonProvenance.runtimeFingerprint(after),
        )
    }
}
