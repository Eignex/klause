package com.eignex.klause.compile

import com.eignex.klause.ast.ge
import com.eignex.klause.ast.implies
import com.eignex.klause.ast.le
import com.eignex.klause.ast.not
import com.eignex.klause.cnf.BitBlaster
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
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
    fun `nominal produces exactly one factor and indicators`() {
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
    fun `end to end solve decodes valid assignments`() {
        val schema = TinyCampaign()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.samples(LocalSearchParams(maxFlips = 5_000, randomSeed = 11)).take(40).toList()
        assertEquals(5, samples.toSet().size, "All 5 feasible solutions should be reached")
        for (s in samples) {
            val type = compiled.decode(schema.type, s)
            val premium = compiled.decode(schema.premium, s)
            assertTrue(
                !(type == "a" && premium),
                "Constraint violated: type=$type premium=$premium",
            )
        }
    }

    @Test
    fun `int compare lowers to int factor at top level`() {
        class Direct : VariableSchema() {
            val budget by intVar(min = 0, max = 100)
            val cap by constraint { budget le 50 }
        }
        val compiled = Direct().compile()
        assertEquals(1, compiled.problem.numIntVars)
        val f = compiled.problem.factors.single() as Linear
        assertEquals(LinearOp.LE, f.op)
        assertEquals(50, f.bound)
    }

    @Test
    fun `int compare inside implies reifies`() {
        val compiled = IntCampaign().compile()

        assertTrue(compiled.problem.numBoolVars >= 4)
        assertEquals(1, compiled.problem.numIntVars)

        assertTrue(compiled.problem.factors.size >= 3)
    }

    @Test
    fun `int schema solves and decodes`() {
        val schema = IntCampaign()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.samples(LocalSearchParams(maxFlips = 20_000, randomSeed = 5)).take(15).toList()
        assertEquals(15, samples.size)
        for (s in samples) {
            val type = compiled.decode(schema.type, s)
            val budget = compiled.decode(schema.budget, s)
            assertTrue(budget in 1000..4000, "budget out of domain: $budget")
            if (type == "a") {
                assertTrue(
                    budget <= 2000,
                    "type=a should have budget≤2000 but was $budget",
                )
            }
        }
    }

    @Test
    fun `float var buckets and decodes`() {
        class FloatTune : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 11)
            val highRate by constraint { rate ge 0.5 }
        }
        val schema = FloatTune()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )

        val samples = solver.samples(LocalSearchParams(maxFlips = 5_000, randomSeed = 99)).take(40).toList()
        // The legacy schema-level bucketing exposed exactly 6 feasible values for rate ≥ 0.5
        // (one per bucket from 0.5..1.0 at 11 buckets). Native floats with backend-level
        // bucketing produce many more distinct values; just verify diversity and the bound.
        assertTrue(samples.toSet().size >= 6, "expected ≥6 distinct samples, got ${samples.toSet().size}")
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate >= 0.5 - 1e-9, "rate=$rate violated ge 0.5")
            assertTrue(rate <= 1.0 + 1e-9 && rate >= 0.0 - 1e-9, "rate=$rate out of [0,1]")
        }
    }

    @Test
    fun `bit blast round trips cnf header`() {
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
