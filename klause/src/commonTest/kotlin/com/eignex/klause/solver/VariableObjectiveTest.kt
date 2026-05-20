package com.eignex.klause.solver

import com.eignex.klause.ast.atLeast
import com.eignex.klause.ast.ge
import com.eignex.klause.ast.le
import com.eignex.klause.compile.compile
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VariableObjectiveTest {

    @Test
    fun `Problem minimizeInt extension equals hand-built coefficient vector`() {
        val problem = Problem(numBoolVars = 0, numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 1) }, factors = emptyArray())
        val factory = problem.minimizeInt(intVar = 2)
        val handBuilt = LinearObjective(intCoefficients = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0))
        assertEquals(handBuilt, factory)
    }

    @Test
    fun `Problem maximizeBool extension negates the coefficient`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val maxObj = problem.maximizeBool(boolVar = 0)
        assertEquals(-1.0, maxObj.boolWeights[0])
        assertEquals(0.0, maxObj.boolWeights[1])
    }

    @Test
    fun `compiled problem minimize on int handle solves`() {
        class S : VariableSchema() {
            val cost by intVar(min = 0, max = 10)
            val req by constraint { cost ge 3 }
        }
        val schema = S()
        val compiled = schema.compile()
        val objective = compiled.minimize(schema.cost)
        val sample = LocalSearchSolver(compiled.problem)
            .minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 0L)).assignment!!
        // assertNotNull merged into !!
        assertEquals(3, compiled.decode(schema.cost, sample))
    }

    @Test
    fun `compiled problem maximize on int handle solves`() {
        class S : VariableSchema() {
            val cost by intVar(min = 0, max = 10)
            val cap by constraint { cost le 7 }
        }
        val schema = S()
        val compiled = schema.compile()
        val objective = compiled.maximize(schema.cost)
        val sample = LocalSearchSolver(compiled.problem)
            .minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 0L)).assignment!!
        // assertNotNull merged into !!
        assertEquals(7, compiled.decode(schema.cost, sample))
    }

    @Test
    fun `compiled problem minimize on bool handle solves`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val req by constraint { atLeast(1, a, b, c) }
        }
        val schema = S()
        val compiled = schema.compile()
        // Minimise the count of true bools — should leave us with exactly one true.
        // Use one of them as the objective handle: picking `a` means `a=false` is preferred.
        val objective = compiled.minimize(schema.a)
        val sample = LocalSearchSolver(compiled.problem)
            .minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 0L)).assignment!!
        // asserted non-null via !!
        // `a` should not be set if either b or c can carry the at-least-one.
        assertEquals(false, compiled.decode(schema.a, sample))
    }

    @Test
    fun `bounds checking on factories`() {
        val intsOnly = Problem(numBoolVars = 0, numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 1) }, factors = emptyArray())
        val boolsOnly = Problem(numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        try {
            intsOnly.minimizeInt(intVar = 5)
            error("should have thrown")
        } catch (_: IllegalArgumentException) {}
        try {
            boolsOnly.minimizeBool(boolVar = -1)
            error("should have thrown")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `compiled problem minimize float returns real-valued objective`() {
        class S : VariableSchema() {
            val temp by floatVar(min = -10.0, max = 30.0, buckets = 41) // 1-unit buckets
        }
        val schema = S()
        val compiled = schema.compile()
        val objective = compiled.minimize(schema.temp)
        val sample = LocalSearchSolver(compiled.problem)
            .minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 0L)).assignment!!
        // asserted non-null via !!
        val decoded = compiled.decode(schema.temp, sample)
        val objValue = objective.evaluate(sample)
        // Objective value should match the decoded real value (modulo float rounding) —
        // confirming scaling-and-constant-offset are right.
        assertTrue(abs(decoded - objValue) < 1e-9, "objective $objValue should match decoded $decoded")
        // Optimum should be the minimum bucket (decoded ≈ -10.0).
        assertTrue(decoded < -9.5, "expected minimum near -10, got $decoded")
    }

    @Test
    fun `compiled problem maximize float returns negated real-valued objective`() {
        class S : VariableSchema() {
            val temp by floatVar(min = -10.0, max = 30.0, buckets = 41)
        }
        val schema = S()
        val compiled = schema.compile()
        val objective = compiled.maximize(schema.temp)
        val sample = LocalSearchSolver(compiled.problem)
            .minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 0L)).assignment!!
        // asserted non-null via !!
        val decoded = compiled.decode(schema.temp, sample)
        // Optimum should be the max bucket (decoded ≈ 30.0).
        assertTrue(decoded > 29.5, "expected maximum near 30, got $decoded")
        // Objective = -decoded (so optimizer's minimised value is negated real).
        assertTrue(abs(-decoded - objective.evaluate(sample)) < 1e-9)
    }
}
