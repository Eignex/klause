package com.eignex.klause.simplex.basis

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class BasisTraceCaptureTest {
    @Test
    fun `precompiled FlatZinc metadata should describe source semantics and bytes`() {
        val source = "var 0.0..2.0: x; constraint float_le(x, 1.0); solve satisfy;"
        val hash = MessageDigest.getInstance("SHA-256").digest(source.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

        val prepared = BasisTraceCapture.prepareMiniZinc(Path.of("source.fzn"), source)

        assertEquals("format=FLATZINC;numeric=BINARY64_LITERALS;floats=continuous;" +
            "unbounded-int-lo=-1000000;unbounded-int-hi=1000000;" +
            "unbounded-real-lo=open;unbounded-real-hi=open;" +
            "absolute-tolerance=1/10000000;relative-tolerance=0;fznSha256=$hash", prepared.scaling)
    }
}
