package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.abs
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.ge
import com.eignex.klause.schema.implies
import com.eignex.klause.schema.le
import com.eignex.klause.schema.minus
import com.eignex.klause.schema.not
import com.eignex.klause.schema.plus
import com.eignex.klause.schema.times
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

class CompilerTest {

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
        assertEquals(setOf("a", "b", "c"), compiled.nominalIndicators.getValue("type").keys)
    }

    @Test
    fun `end to end solve decodes valid assignments`() {
        val schema = TinyCampaign()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem.bake(),
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
            compiled.problem.bake(),
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
    fun `float var lowers to an LP-only column and decodes`() {
        class FloatTune : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 11)
            val highRate by constraint { rate ge 0.5 }
        }
        val schema = FloatTune()
        val compiled = schema.compile()
        // `rate` appears only in a non-strict linear constraint, so it lowers to an LP-only continuous
        // column (issue #1232) the simplex resolves; its value rides on the solution's reals.
        assertEquals(1, compiled.problem.numRealVars)
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 99)),
        )
        val rate = compiled.decode(schema.rate, sat.assignment)
        assertTrue(rate >= 0.5 - 1e-9, "rate=$rate violated ge 0.5")
        assertTrue(rate in -1e-9..(1.0 + 1e-9), "rate=$rate out of [0,1]")
    }

    @Test
    fun `constant false equality fails at compile time`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val cap by constraint { (x - x) eq 5 }
        }
        assertFails { S().compile() }
    }

    @Test
    fun `constant false inequality fails at compile time`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val cap by constraint { (x - x + 5) le 1 }
        }
        assertFails { S().compile() }
    }

    @Test
    fun `large-coefficient scale domain overflow is a clean error`() {
        // domainOf(IntScale): 1_000_000 · [0, 100_000] = [0, 1e11], past Int. abs() forces the
        // scaled expression to be materialized, which computes its domain.
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 100_000)
            val c by constraint { abs(1_000_000 * x) le 5 }
        }
        assertFailsWith<IllegalArgumentException> { S().compile() }
    }

    @Test
    fun `large-literal affine sum overflow is a clean error`() {
        // affine(IntSum): the running constant 2e9 + 2e9 = 4e9 overflows a 32-bit accumulator.
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val c by constraint { (x + 2_000_000_000 + 2_000_000_000) le 0 }
        }
        assertFailsWith<IllegalArgumentException> { S().compile() }
    }

    @Test
    fun `nested scale coefficient fold overflow is a clean error`() {
        // IntOperators.scale constant fold: 100_000 · 100_000 = 1e10 overflows Int.
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val c by constraint { (100_000 * (100_000 * x)) le 0 }
        }
        assertFailsWith<IllegalArgumentException> { S().compile() }
    }
}
