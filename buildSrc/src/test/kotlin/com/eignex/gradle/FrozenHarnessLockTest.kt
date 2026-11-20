package com.eignex.gradle

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(Parameterized::class)
class FrozenHarnessLockTest(private val change: String) {
    @Test
    fun `changed distributions cannot replace a verification receipt`() {
        val directory = Files.createTempDirectory("frozen-harness-test").toFile()
        try {
            val distribution = directory.resolve("distribution").apply { mkdirs() }
            val library = distribution.resolve("lib").apply { mkdirs() }
            JarOutputStream(library.resolve("bench.jar").outputStream()).use {
                it.putNextEntry(JarEntry("com/eignex/klause/bench/Example.class"))
                it.write("class fixture".toByteArray())
                it.closeEntry()
            }
            library.resolve("first.jar").writeText("first")
            library.resolve("second.jar").writeText("second")
            val hashes = listOf("bench.jar", "first.jar", "second.jar").associateWith {
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(library.resolve(it).readBytes()))
            }
            val classpath = if (change == "order") "bench.jar,second.jar,first.jar" else "bench.jar,first.jar,second.jar"
            val lock = directory.resolve("lock.properties").apply {
                writeText(
                    "format=1\nrevision=${"a".repeat(40)}\nbenchJar=bench.jar\nclasspath=$classpath\n" +
                        "harnessFingerprint=1237cb923476c31c80aa6a1d7e2d22e93432d8b2c950718bf9d814b257d70816\n" +
                        "libraryFingerprint=e2258d336a57230b5a16aca63d2fa6a06dbf864e2255ba39178faee17f08ae8e\n" +
                        hashes.entries.joinToString("\n", postfix = "\n") { "sha256.${it.key}=${it.value}" },
                )
            }
            when (change) {
                "bytes" -> library.resolve("first.jar").writeText("changed")
                "missing" -> check(library.resolve("first.jar").delete())
                "extra" -> library.resolve("extra.jar").writeText("extra")
            }
            val receipt = directory.resolve("receipt").apply { mkdirs() }
            receipt.resolve("existing").writeText("verified earlier")

            assertFailsWith<IllegalStateException> { FrozenHarnessLock.read(lock).verify(distribution, receipt) }

            assertEquals(listOf("existing"), receipt.listFiles()!!.map { it.name })
            assertEquals("verified earlier", receipt.resolve("existing").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun changes(): List<Array<String>> = listOf("bytes", "missing", "extra", "order").map { arrayOf(it) }
    }
}
