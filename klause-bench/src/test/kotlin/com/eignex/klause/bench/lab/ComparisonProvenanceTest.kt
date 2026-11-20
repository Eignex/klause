package com.eignex.klause.bench.lab

import com.eignex.klause.bench.metric.BuildProvenance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ComparisonProvenanceTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "\""])
    fun `managed recording destinations do not change runtime identity`(quote: String) {
        val arguments = "\"-Xmx2560m\"\n" +
            "$quote-XX:StartFlightRecording=filename=/local/cases/1/out/report/cli.jfr," +
            "settings=profile,dumponexit=true$quote"
        val before = BuildProvenance(
            emptyMap(), emptyMap(), mapOf("lab.cli.args" to arguments), "Linux", "amd64",
        )
        val after = before.copy(runtimeOptions = mapOf("lab.cli.args" to arguments.replace("cases/1", "cases/2")))

        assertEquals(ComparisonProvenance.runtimeFingerprint(before), ComparisonProvenance.runtimeFingerprint(after))
        assertEquals(arguments, before.runtimeOptions["lab.cli.args"])
        assertNotEquals(before.fingerprint, after.fingerprint)
    }

    @ParameterizedTest
    @ValueSource(strings = ["-Dproperty=", "\"-Dproperty=", "prefix"])
    fun `recording flag text embedded in another argument retains runtime identity`(prefix: String) {
        val arguments = "$prefix-XX:StartFlightRecording=filename=/local/cases/1/out/report/cli.jfr,settings=profile"
        val before = BuildProvenance(
            emptyMap(), emptyMap(), mapOf("lab.cli.args" to arguments), "Linux", "amd64",
        )
        val after = before.copy(runtimeOptions = mapOf("lab.cli.args" to arguments.replace("cases/1", "cases/2")))

        assertNotEquals(ComparisonProvenance.runtimeFingerprint(before), ComparisonProvenance.runtimeFingerprint(after))
    }

    @ParameterizedTest
    @ValueSource(strings = ["settings=default", "-Xmx1280m", "stackdepth=64", "custom.jfr", "unknown=true"])
    fun `runtime identity retains recording settings and other JVM arguments`(change: String) {
        val arguments = "-Xmx2560m\n-XX:FlightRecorderOptions=stackdepth=128\n" +
            "-XX:StartFlightRecording=filename=/local/cases/1/out/report/cli.jfr,settings=profile,dumponexit=true"
        val before = BuildProvenance(
            emptyMap(), emptyMap(), mapOf("lab.cli.args" to arguments), "Linux", "amd64",
        )
        val changed = when (change) {
            "settings=default" -> arguments.replace("settings=profile", change)
            "-Xmx1280m" -> arguments.replace("-Xmx2560m", change)
            "stackdepth=64" -> arguments.replace("stackdepth=128", change)
            "custom.jfr" -> arguments.replace("cli.jfr", change)
            else -> "$arguments\n$change"
        }
        val after = before.copy(runtimeOptions = mapOf("lab.cli.args" to changed))

        assertNotEquals(ComparisonProvenance.runtimeFingerprint(before), ComparisonProvenance.runtimeFingerprint(after))
    }

    @Test
    fun `cached harness classes retain exact identities and recheck changed files`() {
        val root = Files.createTempDirectory("cached-comparison-")
        try {
            val classes = Files.createDirectory(root.resolve("classes"))
            val file = classes.resolve("com/eignex/klause/bench/Fixture.class")
            Files.createDirectories(file.parent)
            Files.writeString(file, "original")
            val library = Files.writeString(root.resolve("library.jar"), "library").toFile()
            val classpath = listOf(classes.toFile(), library)
            val cache = root.resolve("cache")
            val fresh = ComparisonProvenance.capture(classes.toFile(), classpath, null)
            assertEquals(fresh, ComparisonProvenance.capture(classes.toFile(), classpath, cache))
            assertEquals(fresh, ComparisonProvenance.capture(classes.toFile(), classpath, cache))
            val modified = Files.getLastModifiedTime(file)

            Files.writeString(file, "modified")
            Files.setLastModifiedTime(file, modified)

            val changed = ComparisonProvenance.capture(classes.toFile(), classpath, cache)
            assertNotEquals(fresh, changed)
            assertEquals(ComparisonProvenance.capture(classes.toFile(), classpath, null), changed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

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
