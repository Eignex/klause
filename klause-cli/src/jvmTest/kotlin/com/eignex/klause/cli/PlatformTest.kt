package com.eignex.klause.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformTest {

    @Test
    fun `the format extension should be taken after any compression suffix`() {
        assertEquals("cnf", fileExtension("foo.cnf"))
        assertEquals("cnf", fileExtension("dir/foo.cnf.xz"))
        assertEquals("xml", fileExtension("foo.xml.gz"))
        assertEquals("opb", fileExtension("foo.opb.bz2"))
        assertEquals("mps", fileExtension("foo.mps.zst"))
        assertEquals("", fileExtension("noext"))
    }

    @Test
    fun `the compression suffix should be recognised only for known compressors`() {
        assertEquals("xz", compressionExtension("foo.cnf.xz"))
        assertEquals("gz", compressionExtension("foo.cnf.gz"))
        assertEquals("zst", compressionExtension("foo.cnf.zst"))
        assertNull(compressionExtension("foo.cnf"))
        assertNull(compressionExtension("foo.tar"))
    }
}
