package com.eignex.gradle

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Files
import kotlin.test.assertFailsWith

@RunWith(Parameterized::class)
class FrozenHarnessLockParsingTest(private val additional: String) {
    @Test
    fun `ambiguous or unknown lock fields are rejected`() {
        val directory = Files.createTempDirectory("frozen-harness-lock-test").toFile()
        try {
            val checksum = "0".repeat(64)
            val lock = directory.resolve("lock.properties").apply {
                writeText(
                    "format=1\nrevision=${"a".repeat(40)}\nbenchJar=bench.jar\nclasspath=bench.jar\n" +
                        "harnessFingerprint=$checksum\nlibraryFingerprint=$checksum\nsha256.bench.jar=$checksum\n" +
                        "$additional\n",
                )
            }

            assertFailsWith<IllegalArgumentException> { FrozenHarnessLock.read(lock) }
        } finally {
            directory.deleteRecursively()
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fields(): List<Array<String>> = listOf("format=1", "unknown=value").map { arrayOf(it) }
    }
}
