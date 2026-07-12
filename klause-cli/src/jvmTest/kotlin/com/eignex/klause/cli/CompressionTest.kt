package com.eignex.klause.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompressionTest {

    @Test
    fun `the format extension is taken after any compression suffix`() {
        assertEquals("cnf", fileExtension("foo.cnf"))
        assertEquals("cnf", fileExtension("dir/foo.cnf.xz"))
        assertEquals("xml", fileExtension("foo.xml.gz"))
        assertEquals("opb", fileExtension("foo.opb.bz2"))
        assertEquals("", fileExtension("noext"))
    }

    @Test
    fun `the compression suffix is recognised only for known compressors`() {
        assertEquals("xz", compressionExtension("foo.cnf.xz"))
        assertEquals("gz", compressionExtension("foo.cnf.gz"))
        assertNull(compressionExtension("foo.cnf"))
        assertNull(compressionExtension("foo.tar"))
    }

    @Test
    fun `readTextFile transparently decompresses a gzip-compressed instance`() {
        val plain = File.createTempFile("klause-cmp", ".cnf").apply { deleteOnExit() }
        val body = "p cnf 1 1\n1 0\n"
        plain.writeText(body)
        ProcessBuilder("gzip", "-kf", plain.absolutePath).start().waitFor()
        val gz = File(plain.absolutePath + ".gz").apply { deleteOnExit() }
        assertEquals(body, readTextFile(gz.absolutePath))
    }
}
