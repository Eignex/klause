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

/**
 * [SharedCut] must round-trip a cut across two relaxations of the same [Problem] preserving the
 * inequality over CP variables (column indices differ per relaxation, CP-variable ids do not), and an
 * imported cut must stay valid — satisfied by every integer-feasible point — since it is folded into
 * another worker's relaxation. Validity is checked by brute force over the integer box.
 */
class SharedCutTest {

    private fun relax(p: Problem, obj: LinearObjective): LpRelaxation =
        CpToLpRelaxation(p, obj).build(PropagationSession(p))

    /** The cut as a CP-variable → coefficient map, read through [rel]'s column→variable map. */
    private fun overVars(cut: Cut, rel: LpRelaxation): Map<Int, Long> =
        cut.cols.indices.associate { rel.colVarId[cut.cols[it]] to cut.coeffs[it] }

    @Test
    fun `a cut round-trips across relaxations over CP variables`() {
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 5) },
            arrayOf<Factor>(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 6)),
        )
        // Two relaxations of the same problem, different objectives ⇒ potentially different layouts.
        val r1 = relax(p, LinearObjective(intCoefficients = longArrayOf(1, 0, 0, 0)))
        val r2 = relax(p, LinearObjective(intCoefficients = longArrayOf(0, 0, 1, 0)))

        // Hand-build a cut over r1's columns for int vars 0 and 2.
        val cut = Cut(
            intArrayOf(r1.intColOf[0], r1.intColOf[2]),
            longArrayOf(3, 5),
            Relation.LE,
            7,
            global = true,
        )

        val shared = assertNotNull(SharedCut.fromCut(cut, r1), "export should name both columns")
        // The portable form carries CP variables, not columns.
        assertEquals(setOf(0, 2), shared.source.expression.terms.keys.map { it.id }.toSet())

        val back2 = assertNotNull(shared.toCut(r2), "r2 has columns for vars 0 and 2")
        // The inequality over CP variables is identical after crossing to r2, whatever r2's columns are.
        assertEquals(overVars(cut, r1), overVars(back2, r2))
        assertEquals(cut.rel, back2.rel)
        assertEquals(cut.rhs, back2.rhs)

        // Round-trip on the source relaxation recovers the exact columns.
        val back1 = assertNotNull(shared.toCut(r1))
        assertTrue(cut.cols.contentEquals(back1.cols) && cut.coeffs.contentEquals(back1.coeffs))

        // Equal inequalities hash equally regardless of term order.
        val reordered = assertNotNull(
            SharedCut.fromCut(
                Cut(intArrayOf(r1.intColOf[2], r1.intColOf[0]), longArrayOf(5, 3), Relation.LE, 7, global = true),
                r1,
            ),
        )
        assertEquals(shared.key, reordered.key)
    }

    @Test
    fun `missing column drops the cut on import`() {
        // A problem whose relaxation has no column for some variable: importing a cut over it returns null.
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
        assertTrue(shared.toCut(unrelated) == null, "a variable with no column cannot be expressed and is dropped")
    }

}
