package com.eignex.klause.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliSourceInspectionTest {
    @Test
    fun `source inspection should retain the CLI shared presolve allowance`() {
        val source = File.createTempFile("mps-preparation", ".mps").apply {
            writeText("ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 1\n Y COST 2 R 1\n" +
                "RHS\n RHS R 1\nBOUNDS\n BV B X\n BV B Y\nENDATA\n")
        }

        val prepared = try {
            assertNotNull(CliSourceInspection.prepareMps(source.absolutePath, 10_000L, 10_000L))
        } finally {
            source.delete()
        }

        assertEquals(2, prepared.declaredVariables)
        assertEquals(2, prepared.declaredIntegers)
        assertEquals(687_500_000L, prepared.presolveWorkAllowance)
        assertTrue(assertNotNull(prepared.presolveWorkSpent) > 0L)
        assertNotNull(prepared.result.presolve)
    }

    @Test
    fun `an exhausted containing allowance should withhold inspection before opening the source`() {
        assertNull(CliSourceInspection.prepareMps("missing-source.mps", 10_000L, 0L))
    }
}
