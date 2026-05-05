package com.eignex.klause.compile

import com.eignex.klause.ast.implies
import com.eignex.klause.ast.not
import com.eignex.klause.cnf.BitBlaster
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.IntLeq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class TinyCampaign : VariableSchema() {
    val premium by boolVar()
    val type by nominal("a", "b", "c")
    val noPremiumForA by constraint { (type eq "a") implies !premium }
}

private class IntCampaign : VariableSchema() {
    val budget by intVar(min = 1000, max = 4000)
    val type by nominal("a", "b", "c")
    val capWhenA by constraint { (type eq "a") implies (budget le 2000) }
}

class CompileTest {

    @Test
    fun nominalProducesExactlyOneFactorAndIndicators() {
        val schema = TinyCampaign()
        val compiled = schema.compile()
        assertEquals(4, compiled.problem.numBoolVars)
        assertEquals(0, compiled.problem.numIntVars)
        assertEquals(2, compiled.problem.factors.size)
        assertTrue(compiled.problem.factors[0] is Cardinality)
        assertTrue(compiled.problem.factors[1] is Clause)
        assertNotNull(compiled.boolVarIdByName["premium"])
        assertEquals(setOf("a", "b", "c"), compiled.nominalIndicators["type"]!!.keys)
    }

    @Test
    fun endToEndSolveDecodesValidAssignments() {
        // TinyCampaign solutions: type=a forces premium=false (1), type=b/c free (4). 5 unique.
        val schema = TinyCampaign()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, randomSeed = 11, maxFlipsBeforeRestart = 200)
        val samples = solver.sample(maxFlips = 5_000).take(20).toList()
        assertEquals(5, samples.size)
        assertEquals(samples.toSet().size, samples.size)
        for (s in samples) {
            val type = compiled.decodeNominal("type", s)
            val premium = compiled.decodeBool("premium", s)
            assertTrue(!(type == "a" && premium),
                "Constraint violated: type=$type premium=$premium")
        }
    }

    @Test
    fun intCompareLowersToIntFactorAtTopLevel() {
        // Direct top-level int constraint without any reification.
        class Direct : VariableSchema() {
            val budget by intVar(min = 0, max = 100)
            val cap by constraint { budget le 50 }
        }
        val compiled = Direct().compile()
        assertEquals(1, compiled.problem.numIntVars)
        assertTrue(compiled.problem.factors.single() is IntLeq)
    }

    @Test
    fun intCompareInsideImpliesReifies() {
        val compiled = IntCampaign().compile()
        // Vars: 3 nominal indicators + at least one aux for the reified IntCompare = 4+
        assertTrue(compiled.problem.numBoolVars >= 4)
        assertEquals(1, compiled.problem.numIntVars)
        // Factors include the nominal ExactlyOne, the reified IntCompare, and a clause for the implication.
        assertTrue(compiled.problem.factors.size >= 3)
    }

    @Test
    fun intSchemaSolvesAndDecodes() {
        val schema = IntCampaign()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, randomSeed = 5, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 20_000).take(15).toList()
        assertEquals(15, samples.size)
        assertEquals(samples.toSet().size, samples.size, "Samples must be unique")
        for (s in samples) {
            val type = compiled.decodeNominal("type", s)
            val budget = compiled.decodeInt("budget", s)
            assertTrue(budget in 1000..4000, "budget out of domain: $budget")
            if (type == "a") {
                assertTrue(budget <= 2000,
                    "type=a should have budget≤2000 but was $budget")
            }
        }
    }

    @Test
    fun floatVarBucketsAndDecodes() {
        class FloatTune : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 11)
            val highRate by constraint { rate ge 0.5 }
        }
        val schema = FloatTune()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, randomSeed = 99, maxFlipsBeforeRestart = 200)
        // 11 buckets, ge 0.5 leaves buckets 5..10 → 6 unique solutions.
        val samples = solver.sample(maxFlips = 5_000).take(20).toList()
        assertEquals(6, samples.size)
        assertEquals(samples.toSet().size, samples.size)
        for (s in samples) {
            val rate = compiled.decodeFloat("rate", s)
            assertTrue(rate >= 0.5 - 1e-9, "rate=$rate violated ge 0.5")
            assertTrue(rate <= 1.0 + 1e-9 && rate >= 0.0 - 1e-9, "rate=$rate out of [0,1]")
        }
    }

    @Test
    fun bitBlastRoundTripsCnfHeader() {
        val schema = TinyCampaign()
        val compiled = schema.compile()
        val text = BitBlaster.compile(compiled.problem).toDimacs()
        val firstLine = text.lineSequence().first()
        assertTrue(firstLine.startsWith("p cnf "))
        text.lineSequence().drop(1).filter { it.isNotBlank() }.forEach { line ->
            assertTrue(line.trimEnd().endsWith("0"))
        }
    }
}
