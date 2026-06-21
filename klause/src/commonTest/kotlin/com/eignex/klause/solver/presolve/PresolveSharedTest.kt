package com.eignex.klause.solver.presolve

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.factor.bool.PseudoBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * At-most-one clique extraction (the reusable builder behind clique-aware presolve and the planned
 * clique-aware local-search moves). Each test asserts which cliques a factor set yields.
 */
class PresolveSharedTest {

    private fun pos(v: Int) = Lit.make(v, true)

    @Test
    fun `at-most-one cardinality yields one clique of its literals`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(pos(0), pos(1), pos(2)), min = 0, max = 1)),
        )
        val cliques = Presolve.amoCliques(problem)
        assertEquals(listOf(setOf(pos(0), pos(1), pos(2))), cliques)
    }

    @Test
    fun `binary clause yields the clique of its negated literals`() {
        // The clause l0 v l1 is exactly at most one of {not l0, not l1}.
        val problem = Problem(
            2,
            0,
            emptyArray(),
            listOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false)))),
        )
        val cliques = Presolve.amoCliques(problem)
        assertEquals(listOf(setOf(pos(0), pos(1))), cliques)
    }

    @Test
    fun `a three-literal clause contributes no clique`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Clause(intArrayOf(pos(0), pos(1), pos(2)))),
        )
        assertTrue(Presolve.amoCliques(problem).isEmpty())
    }

    @Test
    fun `a plain linear contributes no clique`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        assertTrue(Presolve.amoCliques(problem).isEmpty())
    }

    @Test
    fun `a non-unit-max cardinality contributes no clique`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(pos(0), pos(1), pos(2)), min = 0, max = 2)),
        )
        assertTrue(Presolve.amoCliques(problem).isEmpty())
    }

    @Test
    fun `a pseudo-boolean knapsack contributes no clique`() {
        val problem = Problem(
            2,
            0,
            emptyArray(),
            listOf(PseudoBoolean(intArrayOf(1, 1), intArrayOf(pos(0), pos(1)), PbOp.LE, 1)),
        )
        assertTrue(Presolve.amoCliques(problem).isEmpty())
    }
}
