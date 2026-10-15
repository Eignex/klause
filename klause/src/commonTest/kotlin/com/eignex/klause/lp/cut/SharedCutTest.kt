package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedCutTest {

    private fun relax(p: Problem, obj: LinearObjective): LpRelaxation =
        CpToLpRelaxation(p, obj).build(PropagationSession(p))

    private val roundTripProblem = Problem(
        0,
        4,
        Array(4) { IntDomain(0, 5) },
        arrayOf<Factor>(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 6)),
    )

    private fun overVars(cut: Cut, rel: LpRelaxation): Map<Int, Long> =
        cut.cols.indices.associate { rel.colVarId[cut.cols[it]] to cut.coeffs[it] }

    @Test
    fun `shared cut should round-trip across relaxations over CP variables`() {
        val r1 = relax(roundTripProblem, LinearObjective(intCoefficients = longArrayOf(1, 0, 0, 0)))
        val r2 = relax(roundTripProblem, LinearObjective(intCoefficients = longArrayOf(0, 0, 1, 0)))
        val cut = Cut(intArrayOf(r1.intColOf[0], r1.intColOf[2]), longArrayOf(3, 5), Relation.LE, 7, global = true)

        val shared = assertNotNull(SharedCut.fromCut(cut, r1))
        val back2 = assertNotNull(shared.toCut(r2))
        val back1 = assertNotNull(shared.toCut(r1))

        assertEquals(overVars(cut, r1), overVars(back2, r2))
        assertEquals(cut.rel, back2.rel)
        assertEquals(cut.rhs, back2.rhs)
        assertTrue(cut.cols.contentEquals(back1.cols) && cut.coeffs.contentEquals(back1.coeffs))
    }

    @Test
    fun `equal cuts should share a key regardless of term order`() {
        val r = relax(roundTripProblem, LinearObjective(intCoefficients = longArrayOf(1, 0, 0, 0)))
        val first = Cut(intArrayOf(r.intColOf[0], r.intColOf[2]), longArrayOf(3, 5), Relation.LE, 7, global = true)
        val reordered = Cut(intArrayOf(r.intColOf[2], r.intColOf[0]), longArrayOf(5, 3), Relation.LE, 7, global = true)

        assertEquals(
            assertNotNull(SharedCut.fromCut(first, r)).key,
            assertNotNull(SharedCut.fromCut(reordered, r)).key,
        )
    }

    @Test
    fun `import should drop a cut over a variable with no column`() {
        val p = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 3) },
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2)),
        )
        val r = relax(p, LinearObjective(intCoefficients = longArrayOf(1, 1)))
        val shared = assertNotNull(
            SharedCut.fromCut(
                Cut(intArrayOf(r.intColOf[0], r.intColOf[1]), longArrayOf(1, 1), Relation.LE, 2, global = true),
                r,
            ),
        )
        val unrelated = relax(Problem(0, 0, emptyArray(), emptyArray()), LinearObjective())
        assertTrue(shared.toCut(unrelated) == null)
    }
}
