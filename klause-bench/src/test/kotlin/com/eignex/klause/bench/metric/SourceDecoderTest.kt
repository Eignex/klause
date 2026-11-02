package com.eignex.klause.bench.metric

import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceDecoderTest {
    @Test
    fun `decoder identity binds the selected executable bytes without running it`() {
        val root = Files.createTempDirectory("source-decoder").toFile()
        try {
            val executable = root.resolve("zstd").apply { writeBytes(byteArrayOf()); setExecutable(true) }

            val identity = SourceDecoder.capture({}, root.path)

            assertEquals(executable.canonicalPath, identity["sourceDecoderExecutable"])
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                identity["sourceDecoderSha256"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing decoder fails instead of using an unidentified command`() {
        val root = Files.createTempDirectory("source-decoder").toFile()
        try {
            assertFailsWith<IOException> { SourceDecoder.capture({}, root.path) }
        } finally {
            root.deleteRecursively()
        }
    }
}
