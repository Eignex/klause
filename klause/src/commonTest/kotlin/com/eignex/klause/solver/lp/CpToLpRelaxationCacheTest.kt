package com.eignex.klause.solver.lp

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #564: the shared dense-row cache must produce an [LpModel] field-for-field identical to a full
 * rebuild at every node. A cached relaxer (which captures on its first build and reuses thereafter)
 * is compared against a freshly constructed relaxer (cache never populated → always a full build)
 * across a range of node states — declared bounds, tightened domains, pinned bools, and with cuts —
 * so a wrongly-shared live row (reified big-M) or a stale right-hand side would diverge.
 */
class CpToLpRelaxationCacheTest {

    private val nInt = 6
    private val nBool = 4

    private fun problem(): Problem = Problem(
        numBoolVars = nBool,
        numIntVars = nInt,
        intDomains = Array(nInt) { IntDomain(0, 10) },
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 18),
            Linear(intArrayOf(2, -1), intArrayOf(3, 4), LinearOp.GE, 1),
            // Live big-M reified rows — their coefficients depend on the live bounds of 0,1 / 2,3.
            ReifiedLinear(
                auxBoolVar = 0,
                coeffs = intArrayOf(1, 1),
                vars = intArrayOf(0, 1),
                op = LinearOp.LE,
                bound = 7,
            ),
            ReifiedLinear(
                auxBoolVar = 1,
                coeffs = intArrayOf(1, -1),
                vars = intArrayOf(2, 3),
                op = LinearOp.GE,
                bound = 0,
            ),
            Cardinality(intArrayOf(Lit.make(1, true), Lit.make(2, false), Lit.make(3, true)), min = 1, max = 2),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(0, false))),
            PseudoBoolean(intArrayOf(Lit.make(0, true), Lit.make(3, true)), intArrayOf(2, 3), PbOp.LE, 4),
        ),
    )

    private val objective = LinearObjective(intCoefficients = longArrayOf(1L, 0L, 1L, 0L, -1L, 0L), constant = 7L)

    /** Apply a deterministic, sound set of tightenings keyed by [seed] so live ≠ declared. */
    private fun tighten(session: PropagationSession, seed: Int) {
        if (seed and 1 == 1) session.implyIntAtLeast(0, 2)
        if (seed and 2 == 2) session.implyIntAtMost(1, 6)
        if (seed and 4 == 4) session.implyIntAtMost(2, 5)
        if (seed and 8 == 8) session.implyIntAtLeast(3, 1)
        if (seed and 16 == 16) session.pinBool(0, true)
        if (seed and 32 == 32) session.pinBool(1, false)
    }

    @Test
    fun `cached build matches a full rebuild across node states`() {
        val p = problem()
        val cached = CpToLpRelaxation(p, objective)

        for (seed in 0..47) {
            val cuts = if (seed % 5 == 0) {
                listOf(Cut(intArrayOf(0, 2), longArrayOf(1L, 1L), Relation.LE, 9L, global = false))
            } else {
                emptyList()
            }
            val sFresh = PropagationSession(p).also { tighten(it, seed) }
            val sCached = PropagationSession(p).also { tighten(it, seed) }
            // A new instance never populates its cache → always a full build (the trusted oracle).
            val fresh = CpToLpRelaxation(p, objective).build(sFresh, cuts).model
            val got = cached.build(sCached, cuts).model
            assertModelsEqual(fresh, got, seed)
        }
    }

    private fun assertModelsEqual(a: LpModel, b: LpModel, seed: Int) {
        assertEquals(a.n, b.n, "n at seed $seed")
        assertEquals(a.m, b.m, "m at seed $seed")
        for (i in 0 until a.m) {
            if (!a.a[i].contentEquals(
                    b.a[i],
                )
            ) {
                fail("row $i coeffs differ at seed $seed: ${a.a[i].toList()} vs ${b.a[i].toList()}")
            }
        }
        assertTrue(a.rhs.contentEquals(b.rhs), "rhs at seed $seed: ${a.rhs.toList()} vs ${b.rhs.toList()}")
        assertTrue(a.cost.contentEquals(b.cost), "cost at seed $seed")
        assertTrue(a.upper.contentEquals(b.upper), "upper at seed $seed")
        assertTrue(a.hasUpper.contentEquals(b.hasUpper), "hasUpper at seed $seed")
        assertTrue(a.loShift.contentEquals(b.loShift), "loShift at seed $seed")
        assertEquals(a.objConstant, b.objConstant, "objConstant at seed $seed")
        assertTrue(a.tag.contentEquals(b.tag), "tag at seed $seed")
        assertTrue(a.rowGlobal.contentEquals(b.rowGlobal), "rowGlobal at seed $seed")
        for (i in 0 until a.m) {
            val pa = a.rowPremises[i]
            val pb = b.rowPremises[i]
            if (pa == null) {
                assertTrue(pb == null, "rowPremises $i should be null at seed $seed")
            } else {
                assertTrue(pb != null, "rowPremises $i should be non-null at seed $seed")
                assertTrue(pa.vars.contentEquals(pb.vars), "premise vars $i at seed $seed")
                assertTrue(pa.isUpper.contentEquals(pb.isUpper), "premise isUpper $i at seed $seed")
                assertTrue(pa.thresholds.contentEquals(pb.thresholds), "premise thresholds $i at seed $seed")
            }
        }
    }
}
