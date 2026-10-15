package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.pow
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PowerCompilationTest {
    @Test
    fun `integer source formats retain powers and preserve both source roots`() {
        val x = IntHandle("x", -3, 3)
        val sources = listOf(
            SchemaDef<SchemaEntry>(mapOf("x" to IntSpec(-3, 3), "power" to NamedConstraint(x.pow(4) eq 16)))
                .compile().problem,
            parseFlatZinc("var -3..3: x; constraint int_pow(x, 4, 16); solve satisfy;").problem,
            SmtLib.parse("(declare-const x Int) (assert (and (<= (- 3) x 3) (= (^ x 4) 16)))").model,
            Xcsp3.parse("""<instance><variables><var id="x">-3..3</var></variables>
                <constraints><intension>eq(pow(x,4),16)</intension></constraints></instance>""").problem,
        )
        for (problem in sources) {
            val power = problem.factors.filterIsInstance<Power>().single()
            assertEquals(4, power.exponent)
            assertTrue(problem.factors.none { it is Product })

            val values = BacktrackSolver(problem.bake()).enumerate(BacktrackParams(maxDecisions = 100))
                .map { it.intValue(0) }.toSet()

            assertEquals(setOf(-2L, 2L), values)
        }
    }
}
