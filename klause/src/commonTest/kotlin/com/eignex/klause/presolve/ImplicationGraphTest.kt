package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImplicationGraphTest {
    private val cap = 1024

    private fun isFeasible(problem: Problem, sample: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, sample.bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, sample.ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun reduced(problem: Problem, objectiveBoolVars: Set<Int> = emptySet()): Problem {
        val baked = problem.bake()
        val delta = Presolve.reduceImplicationGraph(baked, cap, objectiveBoolVars = objectiveBoolVars)
        return baked.withPassDelta(delta, BakeConfig.NONE)
    }

    @Test
    fun `merging should keep equivalent cardinality literals distinct`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
                Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true)), min = 0, max = 1),
            ),
        )

        val cardinality = reduced(problem).factors.filterIsInstance<Cardinality>().single()
        assertEquals(intArrayOf(0, 1).toList(), cardinality.boolVars.toList())
    }

    @Test
    fun `anti-equivalent variables should not be merged`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        assertTrue(reduced(problem).factors.any { 1 in it.boolVars })
    }

    @Test
    fun `an objective variable should never be merged`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
            ),
        )
        assertTrue(reduced(problem, objectiveBoolVars = setOf(1)).factors.any { 1 in it.boolVars })
    }

    @Test
    fun `an unmerged equivalence cycle should keep the binaries that state it`() {
        val pairs = listOf(0 to 1, 1 to 0, 1 to 2, 2 to 1, 0 to 2, 2 to 0)
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = pairs.map { (a, b) -> Clause(intArrayOf(Lit.make(a, false), Lit.make(b, true))) },
        )

        val reduced = reduced(problem, objectiveBoolVars = setOf(0, 1, 2))

        val split = Sample(booleanArrayOf(true, false, false), LongArray(0))
        assertTrue(!isFeasible(reduced, split))
    }

    @Test
    fun `an equivalence a row reads should be renamed on a model with an open column`() {
        val open = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, open),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            ),
        )

        val delta = Presolve.reduceSourceImplicationGraph(problem, Cancellation.Never, emptySet())

        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        assertTrue(reduced.factors.none { 1 in it.boolVars })
        val lifted = booleanArrayOf(true, false).also { delta.rebuild.rebuildInto(it) }
        assertEquals(listOf(true, true), lifted.toList())
    }
}
