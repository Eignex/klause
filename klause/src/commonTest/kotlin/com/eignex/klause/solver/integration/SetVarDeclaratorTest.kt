package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.model.MultipleSpec
import com.eignex.klause.model.SetSpec
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.inSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetVarDeclaratorTest {
    private class Sch : VariableSchema() {
        val chosen by setVar(0..3)
        val pickedLabels by multiple("a", "b", "c")
    }

    @Test
    fun `declarators register SetSpec and MultipleSpec`() {
        val s = Sch()
        val entries = s.entries.entries.toList()
        assertEquals(2, entries.size)
        assertEquals("chosen", entries[0].key)
        assertTrue(entries[0].value is SetSpec)
        assertEquals(listOf(0, 1, 2, 3), (entries[0].value as SetSpec).universe)
        assertEquals("pickedLabels", entries[1].key)
        assertTrue(entries[1].value is MultipleSpec)
        assertEquals(listOf("a", "b", "c"), (entries[1].value as MultipleSpec).labels)
    }

    @Test
    fun `compile allocates one indicator bool per universe element`() {
        val s = Sch()
        val compiled = s.compile()
        // 4 + 3 = 7 indicators
        assertEquals(7, compiled.problem.numBoolVars)
        assertEquals(4, compiled.setLayouts.getValue("chosen").size)
        assertEquals(3, compiled.setLayouts.getValue("pickedLabels").size)
        assertEquals(listOf("a", "b", "c"), compiled.setNominalLabels["pickedLabels"])
    }
}

class SetMembershipTest {
    private class Sch : VariableSchema() {
        val s by setVar(0..3)
        val x by intVar(0, 3)
        val c by constraint { x inSet s }
    }

    @Test
    fun `top-level x inSet s forces x to a present element`() {
        val schema = Sch()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem.bake())
        val samples = solver.enumerate(BacktrackParams()).take(50).toList()
        assertTrue(samples.isNotEmpty(), "expected at least one solution")
        for (sample in samples) {
            val xv = compiled.decode(schema.x, sample)
            val sv = compiled.decode(schema.s, sample)
            assertTrue(xv.toInt() in sv, "x=$xv not in s=$sv")
        }
    }
}
