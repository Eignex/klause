package com.eignex.klause.solver

import com.eignex.klause.ast.atLeast
import com.eignex.klause.ast.ge
import com.eignex.klause.ast.le
import com.eignex.klause.compile.compile
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VariableObjectiveTest {

    @Test
    fun `minimizeInt factory equals hand-built coefficient vector`() {
        val factory = LinearObjective.minimizeInt(intVar = 2, numIntVars = 5)
        val handBuilt = LinearObjective(intCoefficients = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0))
        assertEquals(handBuilt, factory)
    }

    @Test
    fun `maximizeBool factory negates the coefficient`() {
        val maxObj = LinearObjective.maximizeBool(boolVar = 0, numBoolVars = 2)
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
            .minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 0L))
        assertNotNull(sample)
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
            .minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 0L))
        assertNotNull(sample)
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
            .minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 0L))
        assertNotNull(sample)
        // `a` should not be set if either b or c can carry the at-least-one.
        assertEquals(false, compiled.decode(schema.a, sample))
    }

    @Test
    fun `bounds checking on factories`() {
        try {
            LinearObjective.minimizeInt(intVar = 5, numIntVars = 3)
            error("should have thrown")
        } catch (_: IllegalArgumentException) {}
        try {
            LinearObjective.minimizeBool(boolVar = -1, numBoolVars = 3)
            error("should have thrown")
        } catch (_: IllegalArgumentException) {}
    }
}
