package com.eignex.klause.compile

import com.eignex.klause.ast.implies
import com.eignex.klause.ast.not
import com.eignex.klause.export.DimacsWriter
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class TinyCampaign : VariableSchema() {
    val premium by boolVar()
    val type by nominal("a", "b", "c")
    val noPremiumForA by constraint { (type eq "a") implies !premium }
}

class CompileTest {

    @Test
    fun nominalProducesExactlyOneFactorAndIndicators() {
        val schema = TinyCampaign()
        val compiled = schema.compile()
        // 1 boolean (premium) + 3 nominal indicators = 4 base vars (no aux yet).
        assertEquals(4, compiled.problem.numVars)
        // Factors: 1 ExactlyOne for nominal + 1 Clause for the implication (lowered to disjunction).
        assertEquals(2, compiled.problem.factors.size)
        assertTrue(compiled.problem.factors[0] is Cardinality)
        assertTrue(compiled.problem.factors[1] is Clause)
        assertNotNull(compiled.varIdByName["premium"])
        assertEquals(setOf("a", "b", "c"), compiled.nominalIndicators["type"]!!.keys)
    }

    @Test
    fun endToEndSolveDecodesValidAssignments() {
        val schema = TinyCampaign()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, randomSeed = 11, maxFlipsBeforeRestart = 200)
        val samples = solver.sample(maxFlips = 5_000).take(20).toList()
        assertEquals(20, samples.size)
        for (s in samples) {
            val type = compiled.decodeNominal("type", s)
            val premium = compiled.decodeBool("premium", s)
            // Constraint: type==a implies !premium  (i.e. not (type==a and premium))
            assertTrue(!(type == "a" && premium),
                "Constraint violated: type=$type premium=$premium")
        }
    }

    @Test
    fun dimacsExportProducesParseableHeader() {
        val schema = TinyCampaign()
        val compiled = schema.compile()
        val text = DimacsWriter.write(compiled.problem)
        val firstLine = text.lineSequence().first()
        assertTrue(firstLine.startsWith("p cnf "))
        val parts = firstLine.split(' ')
        assertEquals(compiled.problem.numVars.toString(), parts[2])
        // Body lines (excluding header) end in 0.
        text.lineSequence().drop(1).filter { it.isNotBlank() }.forEach { line ->
            assertTrue(line.trimEnd().endsWith("0"))
        }
    }
}
