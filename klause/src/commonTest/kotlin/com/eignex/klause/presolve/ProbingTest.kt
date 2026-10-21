package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
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
    fun `unused columns should not consume the candidate cap`() {
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
        val delta = Presolve.probe(problem.bake(), 1, Cancellation.Never)
        assertEquals(listOf(Lit.make(1, false)), delta.addedFactors.filterIsInstance<Clause>().map { it.literals.single() })
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
    @Test
    fun `root fixed columns should not consume the candidate cap or emit redundant units`() {
        val problem = Problem(
            4, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, false))),
            ),
        ).bake()

        val delta = Presolve.probe(problem, 1, Cancellation.Never)

        assertEquals(listOf(Lit.make(2, true)), delta.addedFactors.filterIsInstance<Clause>().map { it.literals.single() })
    }

    @Test
    fun `unused columns should cause no finite probe construction`() {
        val problem = Problem(16, 0, emptyArray(), emptyList()).bake()
        val budget = PresolveBudget(Long.MAX_VALUE)

        val delta = Presolve.probe(problem, 4, budget.orSpent(Cancellation.Never))

        assertTrue(delta.isEmpty)
        assertEquals(0L, budget.rootConstructionEntries)
    }

    @Test
    fun `the candidate cap should stop before a later live failed literal`() {
        val problem = Problem(
            4, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, false))),
            ),
        ).bake()

        assertTrue(Presolve.probe(problem, 1, Cancellation.Never).isEmpty)
    }

    @Test
    fun `source preparation and root rows should stop at the next metered visit`() {
        val problem = Problem(
            0, 8, Array(8) { IntDomain(0, 1) },
            List(8) { Linear(intArrayOf(1), intArrayOf(it), LinearOp.LE, 1) },
        )
        for (allowance in listOf(1L, 81L, 331L, 481L, 561L, 721L, 801L)) {
            val budget = PresolveBudget(allowance)

            val delta = Presolve.probeSource(problem, cap, budget.orSpent(Cancellation.Never))

            assertTrue(delta.isEmpty, "allowance=$allowance")
            assertFalse(delta.infeasible)
            assertTrue(budget.spent() in allowance..allowance + 9, "spent=${budget.spent()}")
        }
    }

    @Test
    fun `source root interruption should retain a proved bound`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 10) },
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 5),
            ),
        )
        val budget = PresolveBudget(220)

        val delta = Presolve.probeSource(problem, cap, budget.orSpent(Cancellation.Never))

        assertFalse(delta.infeasible)
        assertEquals(5L, delta.bounds!!.upper(0))
        assertEquals(10L, delta.bounds.upper(1))
        assertEquals(220L, budget.spent())
    }

    @Test
    fun `interrupted hypothetical propagation should never publish its assumption`() {
        val problem = Problem(
            4, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, true))),
            ),
        )
        for (allowance in listOf(451L, 491L, 531L, 581L, 631L, 681L)) {
            val budget = PresolveBudget(allowance)

            val delta = Presolve.probeSource(problem, 1, budget.orSpent(Cancellation.Never))

            assertTrue(delta.isEmpty, "allowance=$allowance")
            assertFalse(delta.infeasible)
            assertTrue(budget.spent() in allowance..allowance + 9, "spent=${budget.spent()}")
        }
    }

    @Test
    fun `external cancellation should stop source compilation without deductions`() {
        val problem = Problem(
            0, 128, Array(128) { IntDomain(0, 1) },
            List(128) { Linear(intArrayOf(1), intArrayOf(it), LinearOp.LE, 1) },
        )
        var polls = 0
        val token = Cancellation { ++polls == 3 }

        val delta = Presolve.probeSource(problem, cap, token)

        assertEquals(3, polls)
        assertTrue(delta.isEmpty)
        assertFalse(delta.infeasible)
    }

    @Test
    fun `wide row compilation and propagation should poll individual terms`() {
        val problem = Problem(
            0, 16, Array(16) { IntDomain(0, 1) },
            listOf(Linear(IntArray(16) { 1 }, IntArray(16) { it }, LinearOp.LE, 16)),
        )
        for (allowance in listOf(181L, 851L, 1011L)) {
            val budget = PresolveBudget(allowance)

            val delta = Presolve.probeSource(problem, cap, budget.orSpent(Cancellation.Never))

            assertTrue(delta.isEmpty)
            assertFalse(delta.infeasible)
            assertTrue(budget.spent() in allowance..allowance + 9, "spent=${budget.spent()}")
        }
    }

    @Test
    fun `high degree incidence should stop inside a hypothetical column scan`() {
        val problem = Problem(
            129, 0, emptyArray(),
            List(128) { Clause(intArrayOf(Lit.make(0, false), Lit.make(it + 1, true))) },
        )
        for (allowance in listOf(21801L, 22441L, 23081L)) {
            val budget = PresolveBudget(allowance)

            val delta = Presolve.probeSource(problem, 1, budget.orSpent(Cancellation.Never))

            assertTrue(delta.isEmpty)
            assertFalse(delta.infeasible)
            assertTrue(budget.spent() in allowance..allowance + 9, "spent=${budget.spent()}")
        }
    }
}
