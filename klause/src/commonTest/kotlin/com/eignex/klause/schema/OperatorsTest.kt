package com.eignex.klause.schema

import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.model.CircuitExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CircuitReifiedSchema : VariableSchema() {
    val n0 by intVar(min = 0, max = 2)
    val n1 by intVar(min = 0, max = 2)
    val n2 by intVar(min = 0, max = 2)
    val flag by boolVar()

    // Sub-expression position: reify the global behind iff/implies.
    val c by constraint {
        flag iff CircuitExpr(listOf(n0.toIntExpr(), n1.toIntExpr(), n2.toIntExpr()))
    }
}

class OperatorsTest {

    @Test
    fun `at most at top level emits a cardinality bounded by the given max`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val d by boolVar()
            val cap by constraint { atMost(2, a, b, c, d) }
        }
        val compiled = S().compile()
        val card = compiled.problem.factors.single { it is Cardinality } as Cardinality
        assertEquals(0, card.min)
        assertEquals(2, card.max)
    }

    @Test
    fun `at least nested reifies`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val d by boolVar()
            val rule by constraint { flag implies atLeast(2, a, b, c, d) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is ReifiedCardinality })
    }

    @Test
    fun `reified range end to end solve`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val d by boolVar()
            val rule by constraint { flag implies cardinality(2, 3, a, b, c, d) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem.bake(),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 7)).take(8).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val flagSet = compiled.decode(schema.flag, s)
            val truthCount = listOf(schema.a, schema.b, schema.c, schema.d).count { compiled.decode(it, s) }
            if (flagSet) {
                assertTrue(
                    truthCount in 2..3,
                    "flag set should force count∈[2,3], got $truthCount",
                )
            }
        }
    }

    @Test
    fun `at most four of five cardinality solves`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val d by boolVar()
            val e by boolVar()
            val cap by constraint { atMost(4, a, b, c, d, e) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem.bake(),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 3_000, randomSeed = 19)).take(20).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val truthCount = listOf(schema.a, schema.b, schema.c, schema.d, schema.e).count { compiled.decode(it, s) }
            assertTrue(truthCount <= 4, "got $truthCount true")
        }
    }

    @Test
    fun `reified circuit produces a feasibility-checkable model`() {
        val s = CircuitReifiedSchema()
        val compiled = s.compile()
        val solver = LocalSearchSolver(
            compiled.problem.bake(),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 300),
        )
        // Exercise the lowering and confirm it produces a model the solver can iterate against
        // without crashing; we don't insist LS terminates on a feasible sample within a fixed budget.
        val samples = solver.samples(LocalSearchParams(maxFlips = 5_000, randomSeed = 41)).take(5).toList()
        assertTrue(samples.isNotEmpty(), "solver returned no samples")
    }
}
