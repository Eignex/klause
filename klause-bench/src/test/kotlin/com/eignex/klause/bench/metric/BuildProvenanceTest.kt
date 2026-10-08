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
