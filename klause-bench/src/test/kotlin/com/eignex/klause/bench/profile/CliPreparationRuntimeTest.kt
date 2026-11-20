package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertFailsWith

class CliPreparationRuntimeTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a shared class requires identical bytes across the helper classpath`(identical: Boolean) {
        val root = Files.createTempDirectory("cli-preparation-classpath-").toFile()
        try {
            val jars = listOf(root.resolve("cli.jar"), root.resolve("bench.jar"))
            jars.forEachIndexed { index, file ->
                JarOutputStream(file.outputStream()).use { jar ->
                    jar.putNextEntry(JarEntry("sample/Shared.class"))
                    jar.write(if (index == 0 || identical) byteArrayOf(1, 2) else byteArrayOf(1, 3))
                    jar.closeEntry()
                }
            }

            if (identical) CliPreparationRuntime.requireSharedClasses(jars)
            else assertFailsWith<IllegalArgumentException> { CliPreparationRuntime.requireSharedClasses(jars) }
        } finally {
            root.deleteRecursively()
        }
    }
}
