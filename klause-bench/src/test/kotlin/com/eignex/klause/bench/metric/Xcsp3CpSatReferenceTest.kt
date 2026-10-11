package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class Xcsp3CpSatReferenceTest {
    @Test
    fun `reference identity includes the image and importer policy`() {
        val identity = Xcsp3CpSatReference.identity("sha256:abc")

        assertEquals("${Xcsp3CpSatReference.IMAGE}@sha256:abc|${Xcsp3CpSatReference.VERSION}", identity)
    }

    @Test
    fun `reference records retain their importer identity`() {
        val output = """{"exit":"OPTIMAL","version":"${Xcsp3CpSatReference.VERSION}","objective":5,"maximize":true}"""

        val result = Xcsp3CpSatReference.parse(emptyList(), output, "checked-image")

        assertEquals(true, result.feasible)
        assertEquals(true, result.proven)
        assertEquals(5.0, result.objective)
        assertEquals("true", result.stats["maximize"])
        assertEquals("checked-image", result.stats["referenceVersion"])
    }

    @Test
    fun `unversioned importer proofs are refused`() {
        val output = """{"exit":"UNSATISFIABLE"}"""

        assertFailsWith<IllegalStateException> {
            Xcsp3CpSatReference.parse(emptyList(), output, "checked-image")
        }
    }

    @Test
    fun `unknown reference records retain their importer identity`() {
        val output = """{"exit":"UNKNOWN","version":"${Xcsp3CpSatReference.VERSION}"}"""

        val result = Xcsp3CpSatReference.parse(emptyList(), output, "checked-image")

        assertNull(result.feasible)
        assertEquals(false, result.proven)
        assertEquals("checked-image", result.stats["referenceVersion"])
    }
}
