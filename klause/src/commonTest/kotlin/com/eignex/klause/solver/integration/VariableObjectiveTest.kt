package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.atLeast
import com.eignex.klause.schema.ge
import com.eignex.klause.schema.le
import com.eignex.klause.solver.*
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.maximizeBool
import com.eignex.klause.solver.objective.minimizeBool
import com.eignex.klause.solver.objective.minimizeInt
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VariableObjectiveTest {

    @Test
    fun `Problem minimizeInt extension equals hand-built coefficient vector`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 1) },
            factors = emptyArray(),
        )
        val factory = problem.minimizeInt(intVar = 2)
        val handBuilt = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 1L, 0L, 0L))
        assertEquals(handBuilt, factory)
    }

    @Test
    fun `Problem maximizeBool extension negates the coefficient`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val maxObj = problem.maximizeBool(boolVar = 0)
        assertEquals(-1L, maxObj.boolWeights[0])
        assertEquals(0L, maxObj.boolWeights[1])
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
        // Picking `a` as the objective handle means `a=false` is preferred, so `a` should
        // not be set if either b or c can carry the at-least-one.
        val objective = compiled.minimize(schema.a)
        val sample = LocalSearchSolver(compiled.problem)
            .minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 0L)).assignment!!
        assertEquals(false, compiled.decode(schema.a, sample))
    }

    @Test
    fun `bounds checking on factories`() {
        val intsOnly = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 1) },
            factors = emptyArray(),
        )
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
    fun `compiled problem minimize float finds the minimum bucket`() {
        class S : VariableSchema() {
            val temp by floatVar(min = -10.0, max = 30.0, buckets = 41) // 1-unit buckets
        }
        val schema = S()
        val compiled = schema.compile()
        // Float objectives optimise the integer bucket index (a strictly increasing affine
        // map of the real value), so the objective is integer and the real value is recovered
        // by decode. Minimising the bucket index lands at the minimum real value ≈ -10.
        // The float is an LP-only continuous column (issue #1232), so the objective is a real objective the
        // simplex resolves; backtrack minimises it to the real minimum ≈ -10.
        val objective = compiled.minimize(schema.temp)
        val r = BacktrackSolver(compiled.problem).minimize(objective, BacktrackParams(randomSeed = 0L))
        val sample = assertIs<MinimizeResult.WithSample>(r).sample
        val decoded = compiled.decode(schema.temp, sample)
        assertTrue(decoded < -9.5, "expected minimum near -10, got $decoded")
    }

    @Test
    fun `compiled problem maximize float finds the maximum bucket`() {
        class S : VariableSchema() {
            val temp by floatVar(min = -10.0, max = 30.0, buckets = 41)
        }
        val schema = S()
        val compiled = schema.compile()
        // Maximise negates the bucket-index objective; the optimum decodes to the max ≈ 30.
        val objective = compiled.maximize(schema.temp)
        val r = BacktrackSolver(compiled.problem).minimize(objective, BacktrackParams(randomSeed = 0L))
        val sample = assertIs<MinimizeResult.WithSample>(r).sample
        val decoded = compiled.decode(schema.temp, sample)
        assertTrue(decoded > 29.5, "expected maximum near 30, got $decoded")
    }
}
