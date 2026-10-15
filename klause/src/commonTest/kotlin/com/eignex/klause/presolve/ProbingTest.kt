package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProbingTest {
    private val cap = 1024

    private fun units(problem: Problem): List<Int> =
        problem.factors.filterIsInstance<Clause>().filter { it.literals.size == 1 }.map { it.literals[0] }

    private fun probed(problem: Problem): Problem {
        val baked = problem.bake()
        return baked.withPassDelta(Presolve.probe(baked, cap, Cancellation.Never), BakeConfig.NONE)
    }

    private fun isFeasible(problem: Problem, ints: LongArray, bools: BooleanArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, bools[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun feasibleAssignments(problem: Problem): Set<String> {
        val n = problem.numIntVars
        val nb = problem.numBoolVars
        val found = HashSet<String>()
        val ints = LongArray(n) { problem.finiteIntDomain(it).min }
        while (true) {
            for (mask in 0 until (1 shl nb)) {
                val bits = BooleanArray(nb) { (mask shr it) and 1 == 1 }
                if (isFeasible(problem, ints.copyOf(), bits)) found.add(ints.joinToString(",") + "|" + mask)
            }
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.finiteIntDomain(i).max) break
                ints[i] = problem.finiteIntDomain(i).min
                i++
            }
            if (i == n) break
        }
        return found
    }

    @Test
    fun `a failed literal should be emitted as a forcing unit`() {
        // b0 = true forces both b1 and !b1.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ),
        )
        val out = probed(problem)
        assertTrue(Lit.make(0, false) in units(out))
        assertEquals(feasibleAssignments(problem), feasibleAssignments(out))
    }

    @Test
    fun `a bound implied under both polarities should be tightened`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 2,
                ),
                ReifiedLinear(
                    auxBoolVar = 1,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 7,
                ),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        val out = probed(problem)
        assertEquals(2L, out.finiteIntDomain(0).min)
        assertEquals(feasibleAssignments(problem), feasibleAssignments(out))
    }

    @Test
    fun `the candidate cap should stop probing before a later failed literal`() {
        // A cap of 1 spends the only candidate slot on the unconstrained b0.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        assertTrue(Lit.make(1, false) in units(probed(problem)))
        assertTrue(Presolve.probe(problem.bake(), 1, Cancellation.Never).isEmpty)
    }

    @Test
    fun `a consequence holding under only one polarity should not be applied`() {
        // b0 <-> (x >= 6) bounds x only under b0 = true.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 6,
                ),
            ),
        )
        val out = probed(problem)
        assertEquals(0L, out.finiteIntDomain(0).min)
        assertTrue(units(out).isEmpty())
        assertEquals(feasibleAssignments(problem), feasibleAssignments(out))
    }

    private fun sourceUnits(delta: SourceDelta): Set<Int> =
        delta.addedFactors.filterIsInstance<Clause>().map { it.literals.single() }.toSet()

    @Test
    fun `source probing should fix the activator of a row the declared range rules out`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 100)),
            factors = listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, -1),
            ),
            openIntHi = booleanArrayOf(true),
        )

        val delta = Presolve.probeSource(problem, cap, Cancellation.Never)

        assertEquals(setOf(Lit.make(0, false)), sourceUnits(delta))
    }

    @Test
    fun `source probing should force the other polarity of a failed literal on an open column`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 0)),
            factors = listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.GE, 10),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            ),
            openIntLo = booleanArrayOf(true),
            openIntHi = booleanArrayOf(true),
        )

        val delta = Presolve.probeSource(problem, cap, Cancellation.Never)

        assertTrue(Lit.make(0, false) in sourceUnits(delta))
    }

    @Test
    fun `source probing should close an open side both polarities bound`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 100)),
            factors = listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 5),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 7),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
            openIntHi = booleanArrayOf(true),
        )

        val bounds = Presolve.probeSource(problem, cap, Cancellation.Never).bounds!!

        assertFalse(bounds.isOpenUpper(0))
        assertEquals(7L, bounds.upper(0))
    }

    @Test
    fun `source probing should leave a consequence of only one polarity alone`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 100)),
            factors = listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 6),
            ),
            openIntHi = booleanArrayOf(true),
        )

        assertTrue(Presolve.probeSource(problem, cap, Cancellation.Never).isEmpty)
    }
}
