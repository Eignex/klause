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
        val output = "v <instantiation><list>x</list><values>5</values></instantiation>\n" +
            """{"exit":"OPTIMAL","version":"${Xcsp3CpSatReference.VERSION}","objective":5,"exactObjective":"5","maximize":true,"cpModelPresolve":true}"""

        val result = Xcsp3CpSatReference.parse(emptyList(), output, "checked-image")

        assertEquals(true, result.feasible)
        assertEquals(true, result.proven)
        assertEquals(5.0, result.objective)
        assertEquals("5", result.exactObjective)
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

    @Test
    fun `proofs with incompatible native settings are undecided`() {
        val output = """{"exit":"UNSATISFIABLE","version":"${Xcsp3CpSatReference.VERSION}","cpModelPresolve":false}"""

        val result = Xcsp3CpSatReference.parse(emptyList(), output, "checked-image")

        assertNull(result.feasible)
        assertEquals(false, result.proven)
    }

    @Test
    fun `feasible reference claims without source witnesses are undecided`() {
        val output = """{"exit":"FEASIBLE","version":"${Xcsp3CpSatReference.VERSION}","cpModelPresolve":true}"""

        val result = Xcsp3CpSatReference.parse(emptyList(), output, "checked-image")

        assertNull(result.feasible)
        assertEquals(false, result.proven)
    }

    @Test
    fun `reference objectives retain integers beyond binary64 precision`() {
        val output = "o 9007199254740993\nv <instantiation><list>x</list><values>1</values></instantiation>\n" +
            """{"exit":"OPTIMAL","version":"${Xcsp3CpSatReference.VERSION}","objective":9007199254740993,"exactObjective":"9007199254740993","maximize":true,"cpModelPresolve":true,"seed":7,"workers":1}"""

        val result = Xcsp3CpSatReference.parse(emptyList(), output, "checked-image")

        assertEquals("9007199254740993", result.exactObjective)
        assertEquals("7", result.stats["referenceSeed"])
        assertEquals("1", result.stats["referenceWorkers"])
    }
}
