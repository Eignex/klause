package com.eignex.klause.bench.metric

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BuildProvenanceTest {
    @Test
    fun `changed installed bytes should invalidate provenance even when timestamps are preserved`() = withRoot { root ->
        val launcher = distribution(root)
        val modules = write(root, "jdk/lib/modules", "modules")
        val before = InstalledBuild.capture(launcher, File(root, "jdk"), emptyMap())
        val files = listOf(launcher, File(root, "dist/lib/solver.jar"), File(root, "jdk/bin/java"), modules)

        for (file in files) {
            val original = file.readBytes()
            val timestamp = file.lastModified()
            file.writeText("replacement")
            file.setLastModified(timestamp)
            val after = InstalledBuild.capture(launcher, File(root, "jdk"), emptyMap())

            assertNotEquals(before.fingerprint, after.fingerprint, file.name)
            file.writeBytes(original)
            file.setLastModified(timestamp)
        }
    }

    @Test
    fun `identical distributions at different paths should retain their fingerprint`() = withRoot { root ->
        val launcher = distribution(root, "first/")
        File(root, "first").copyRecursively(File(root, "second"))

        val first = InstalledBuild.capture(launcher, File(root, "first/jdk"), emptyMap())
        val second = InstalledBuild.capture(
            File(root, "second/dist/bin/klause-cli"),
            File(root, "second/jdk"),
            emptyMap(),
        )

        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `an incomplete installed distribution should not claim provenance`() = withRoot { root ->
        val launcher = write(root, "dist/bin/klause-cli", "launcher")
        write(root, "jdk/bin/java", "java")

        assertFailsWith<IllegalArgumentException> {
            InstalledBuild.capture(launcher, File(root, "jdk"), emptyMap())
        }
    }

    @Test
    fun `harness paths should not enter runtime identity while solver options remain intact`() {
        val options = listOf(
            "-Dklause.workspace.root=/first -Dklause.bench.cache=false -Xmx3g",
            "'-Dklause.workspace.root=/second path' -Dklause.bench.corpusCache='/corpus path' -Xmx3g",
            "-Dklause.workspace.root=\"/third path\" -Dklause.bench.corpusCache=/corpus -Xmx3g",
        )

        val normalized = options.map { runtimeOptions(mapOf("JAVA_OPTS" to it)) }

        assertTrue(normalized.all { it == mapOf("JAVA_OPTS" to "-Xmx3g") })
        val inputOption = mapOf("JAVA_OPTS" to "-Dklause.bench.smtlib.strictBounds=true")
        assertEquals(inputOption, runtimeOptions(inputOption))
    }

    @Test
    fun `a manifest should reuse captured hashes and bind each case runtime options`() = withRoot { root ->
        val launcher = distribution(root)
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
        assertNotEquals(reused.fingerprint, changedRuntime.fingerprint)
    }

    @Test
    fun `manifest freshness should reject changed bytes with their modification time preserved`() = withRoot { root ->
        val launcher = distribution(root)
        val jar = File(root, "dist/lib/solver.jar")
        val manifest = File(root, "provenance.json")
        InstalledBuild.writeManifest(manifest, launcher, File(root, "jdk"))
        val timestamp = jar.lastModified()

        jar.writeText("SOLVER")
        jar.setLastModified(timestamp)

        assertFailsWith<IllegalArgumentException> {
            InstalledBuild.readManifest(manifest, launcher, File(root, "jdk"), emptyMap())
        }
    }

    @Test
    fun `a manifest should not claim another runtime`() = withRoot { root ->
        val launcher = distribution(root)
        val manifest = File(root, "provenance.json")
        InstalledBuild.writeManifest(manifest, launcher, File(root, "jdk"))
        File(root, "jdk").copyRecursively(File(root, "other-jdk"))

        assertFailsWith<IllegalArgumentException> {
            InstalledBuild.readManifest(manifest, launcher, File(root, "other-jdk"), emptyMap())
        }
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("provenance").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun write(root: File, path: String, text: String): File =
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    private fun distribution(root: File, prefix: String = ""): File {
        write(root, "${prefix}dist/lib/solver.jar", "solver")
        write(root, "${prefix}jdk/bin/java", "java")
        return write(root, "${prefix}dist/bin/klause-cli", "launcher")
    }
}
