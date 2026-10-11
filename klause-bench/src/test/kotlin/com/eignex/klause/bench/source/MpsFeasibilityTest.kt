package com.eignex.klause.bench.source

import com.eignex.klause.formats.mps.Mps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpsFeasibilityTest {
    @Test
    fun `stripping free rows preserves columns constraints ranges and bounds`() {
        val source = """
NAME feasibility
OBJSENSE
 MAX
ROWS
 N cost
 N ignored
 L limit
COLUMNS
 MARK0 'MARKER' 'INTORG'
 x cost 7 limit 2
 MARK1 'MARKER' 'INTEND'
 y cost 3 ignored 1
RHS
 rhs cost -4 limit 8
RANGES
 range limit 2
BOUNDS
 UI bound x 3
 FR bound y
ENDATA
""".trimIndent()
        val baseline = Mps.parse(source)

        val transformed = MpsFeasibility.transform(source)
        val model = Mps.parse(transformed)

        assertTrue(model.objective.indices.isEmpty())
        assertEquals(0.0, model.objective.constant)
        assertEquals(baseline.variables, model.variables)
        assertEquals(baseline.constraints.single().lower, model.constraints.single().lower)
        assertEquals(baseline.constraints.single().upper, model.constraints.single().upper)
        assertEquals(listOf(2.0, 0.0), model.constraints.single().coeffs.toList())
        assertEquals(transformed, MpsFeasibility.transform(transformed))
    }
}
