package com.eignex.klause.compile

import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.atLeast
import com.eignex.klause.schema.atMost
import com.eignex.klause.schema.cardinality
import com.eignex.klause.schema.implies
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardinalityDslTest {

    @Test
    fun `at most three at top level emits cardinality`() {
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
            compiled.problem,
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
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 3_000, randomSeed = 19)).take(20).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val truthCount = listOf(schema.a, schema.b, schema.c, schema.d, schema.e).count { compiled.decode(it, s) }
            assertTrue(truthCount <= 4, "got $truthCount true")
        }
    }
}
