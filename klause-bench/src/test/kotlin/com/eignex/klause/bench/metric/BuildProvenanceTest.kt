package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BuildProvenanceTest {
    @Test
    fun `changed installed bytes invalidate provenance even when timestamps are preserved`() {
        val root = Files.createTempDirectory("provenance").toFile()
        try {
            val launcher = File(root, "dist/bin/klause-cli").apply {
                parentFile.mkdirs()
                writeText("launcher")
            }
            val jar = File(root, "dist/lib/solver.jar").apply {
                parentFile.mkdirs()
                writeText("solver")
            }
            val java = File(root, "jdk/bin/java").apply {
                parentFile.mkdirs()
                writeText("java")
            }
            val modules = File(root, "jdk/lib/modules").apply {
                parentFile.mkdirs()
                writeText("modules")
            }
            val before = InstalledBuild.capture(launcher, File(root, "jdk"), emptyMap())

            for (file in listOf(launcher, jar, java, modules)) {
                val original = file.readBytes()
                val timestamp = file.lastModified()
                file.writeText("replacement")
                file.setLastModified(timestamp)
                val after = InstalledBuild.capture(launcher, File(root, "jdk"), emptyMap())

                assertNotEquals(before.fingerprint, after.fingerprint, file.name)
                file.writeBytes(original)
                file.setLastModified(timestamp)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `identical distributions at different paths retain their fingerprint`() {
        val root = Files.createTempDirectory("provenance").toFile()
        try {
            val launcher = File(root, "first/dist/bin/klause-cli").apply {
                parentFile.mkdirs()
                writeText("launcher")
            }
            File(root, "first/dist/lib/solver.jar").apply {
                parentFile.mkdirs()
                writeText("solver")
            }
            File(root, "first/jdk/bin/java").apply {
                parentFile.mkdirs()
                writeText("java")
            }
            File(root, "first").copyRecursively(File(root, "second"))

            val first = InstalledBuild.capture(launcher, File(root, "first/jdk"), emptyMap())
            val second = InstalledBuild.capture(
                File(root, "second/dist/bin/klause-cli"),
                File(root, "second/jdk"),
                emptyMap(),
            )

            assertEquals(first.fingerprint, second.fingerprint)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an incomplete installed distribution cannot claim provenance`() {
        val root = Files.createTempDirectory("provenance").toFile()
        try {
            val launcher = File(root, "dist/bin/klause-cli").apply {
                parentFile.mkdirs()
                writeText("launcher")
            }
            File(root, "jdk/bin/java").apply {
                parentFile.mkdirs()
                writeText("java")
            }

            assertFailsWith<IllegalArgumentException> {
                InstalledBuild.capture(launcher, File(root, "jdk"), emptyMap())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `harness paths do not enter runtime identity and solver options remain intact`() {
        val options = listOf(
            "-Dklause.workspace.root=/first -Dklause.bench.cache=false -Xmx3g",
            "'-Dklause.workspace.root=/second path' -Dklause.bench.corpusCache='/corpus path' -Xmx3g",
            "-Dklause.workspace.root=\"/third path\" -Dklause.bench.corpusCache=/corpus -Xmx3g",
        )

        val normalized = options.map { runtimeOptions(mapOf("JAVA_OPTS" to it)) }

        assertTrue(normalized.all { it == mapOf("JAVA_OPTS" to "-Xmx3g") })
        assertNotEquals(normalized.first(), runtimeOptions(mapOf("JAVA_OPTS" to "-Xmx4g")))
        assertEquals(mapOf("LD_LIBRARY_PATH" to "/vendor"), runtimeOptions(mapOf("LD_LIBRARY_PATH" to "/vendor")))
        val inputOption = mapOf("JAVA_OPTS" to "-Dklause.bench.smtlib.strictBounds=true")
        assertEquals(inputOption, runtimeOptions(inputOption))
    }

    @Test
    fun `a manifest reuses captured hashes and binds each case runtime options`() {
        val root = Files.createTempDirectory("provenance").toFile()
        try {
            val launcher = File(root, "dist/bin/klause-cli").apply {
                parentFile.mkdirs()
                writeText("launcher")
            }
            File(root, "dist/lib/solver.jar").apply {
                parentFile.mkdirs()
                writeText("solver")
            }
            File(root, "jdk/bin/java").apply {
                parentFile.mkdirs()
                writeText("java")
            }
            val runtime = File(root, "jdk")
            val manifest = File(root, "provenance.json")
            InstalledBuild.writeManifest(manifest, launcher, runtime)
            val environment = mapOf("JAVA_OPTS" to "-Dklause.workspace.root=/work -Xmx3g")

            val reused = InstalledBuild.readManifest(manifest, launcher, runtime, environment)
            val fresh = InstalledBuild.capture(launcher, runtime, environment)
            val changedRuntime = InstalledBuild.readManifest(
                manifest,
                launcher,
                runtime,
                mapOf("KLAUSE_CLI_OPTS" to "-XX:ActiveProcessorCount=2"),
            )

            assertEquals(fresh, reused)
            assertEquals(fresh.fingerprint, reused.fingerprint)
            assertNotEquals(reused.fingerprint, changedRuntime.fingerprint)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `manifest freshness rejects changed bytes with their modification time preserved`() {
        val root = Files.createTempDirectory("provenance").toFile()
        try {
            val launcher = File(root, "dist/bin/klause-cli").apply {
                parentFile.mkdirs()
                writeText("launcher")
            }
            val jar = File(root, "dist/lib/solver.jar").apply {
                parentFile.mkdirs()
                writeText("solver")
            }
            File(root, "jdk/bin/java").apply {
                parentFile.mkdirs()
                writeText("java")
            }
            val manifest = File(root, "provenance.json")
            InstalledBuild.writeManifest(manifest, launcher, File(root, "jdk"))
            val timestamp = jar.lastModified()

            jar.writeText("SOLVER")
            jar.setLastModified(timestamp)

            assertFailsWith<IllegalArgumentException> {
                InstalledBuild.readManifest(manifest, launcher, File(root, "jdk"), emptyMap())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a manifest cannot claim another runtime`() {
        val root = Files.createTempDirectory("provenance").toFile()
        try {
            val launcher = File(root, "dist/bin/klause-cli").apply {
                parentFile.mkdirs()
                writeText("launcher")
            }
            File(root, "dist/lib/solver.jar").apply {
                parentFile.mkdirs()
                writeText("solver")
            }
            File(root, "jdk/bin/java").apply {
                parentFile.mkdirs()
                writeText("java")
            }
            val manifest = File(root, "provenance.json")
            InstalledBuild.writeManifest(manifest, launcher, File(root, "jdk"))
            File(root, "jdk").copyRecursively(File(root, "other-jdk"))

            assertFailsWith<IllegalArgumentException> {
                InstalledBuild.readManifest(manifest, launcher, File(root, "other-jdk"), emptyMap())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `subprocess cache serialization retains installed build provenance`() {
        val provenance = BuildProvenance(
            mapOf("lib/solver.jar" to "abc"),
            mapOf("bin/java" to "def"),
            emptyMap(),
            "Linux",
            "amd64",
        )
        val result = SolverInvocation.Result(
            true,
            null,
            1L,
            proven = false,
            stats = emptyMap(),
            rawOutput = "sat",
            command = "cli",
            buildProvenance = provenance,
        )

        val restored = Reports.json.decodeFromString<SolverInvocation.Result>(Reports.json.encodeToString(result))

        assertEquals(provenance, restored.buildProvenance)
    }
}
