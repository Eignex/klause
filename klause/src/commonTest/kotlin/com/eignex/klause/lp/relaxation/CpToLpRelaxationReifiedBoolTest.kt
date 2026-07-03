package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #655 (Tranche B): the big-M relaxation of [ReifiedPseudoBoolean] / [ReifiedCardinality]. Soundness
 * is checked exhaustively — for every assignment that satisfies the reified semantics, the relaxation
 * (with all Booleans pinned to that assignment) must stay LP-feasible, i.e. no big-M row cuts a valid
 * point. Strength is spot-checked: an assignment that violates the reified semantics in a direction
 * the rows encode must be excluded.
 */
class CpToLpRelaxationReifiedBoolTest {

    /** Whether the integer point [bits] satisfies every emitted relaxation row. Rows are stored in the
     *  normalized `Σ a·x' ≤ rhs` form (GE negated to LE; these reified rows emit no equalities), so the
     *  point is feasible iff `Σ a[i][j]·(x_j − loShift_j) ≤ rhs[i]` for every row — evaluated directly,
     *  independent of the simplex. */
    private fun feasibleAt(p: Problem, bits: BooleanArray): Boolean {
        val r = CpToLpRelaxation(p, null).build(PropagationSession(p))
        val m = r.model
        val x = LongArray(m.n) { col -> (if (bits[r.colVarId[col]]) 1L else 0L) - m.loShift[col] }
        // Row activity Σ a[i][j]·x[j], accumulated column-wise over the sparse CSC core.
        val s = LongArray(m.m)
        for (j in 0 until m.n) m.forEachInColumn(j) { i, v -> s[i] += v * x[j] }
        for (i in 0 until m.m) if (s[i] > m.rhs[i]) return false
        return true
    }

    /** Every reified-consistent assignment over [nBool] Booleans (aux = `nBool-1`) is in the relaxation;
     *  returns the count of inconsistent assignments the relaxation managed to exclude (its strength). */
    private fun checkSoundness(p: Problem, nBool: Int, aux: Int, cond: (BooleanArray) -> Boolean): Int {
        var excluded = 0
        for (mask in 0 until (1 shl nBool)) {
            val bits = BooleanArray(nBool) { (mask shr it) and 1 == 1 }
            val consistent = bits[aux] == cond(bits)
            val feasible = feasibleAt(p, bits)
            if (consistent) {
                assertTrue(feasible, "reified-consistent assignment ${bits.toList()} was cut by the relaxation")
            } else if (!feasible) {
                excluded++
            }
        }
        return excluded
    }

    private fun pbProblem(op: PbOp, bound: Int, lits: IntArray, weights: IntArray, aux: Int, nBool: Int): Problem =
        Problem(
            numBoolVars = nBool,
            numIntVars = 0,
            intDomains = arrayOf(),
            factors = arrayOf<Factor>(ReifiedPseudoBoolean(aux, weights, lits, op, bound)),
        )

    @Test
    fun `reified pseudo-boolean LE is sound and excludes both violating directions`() {
        // aux <-> (2 b0 + 3 b1 + 1 b2 <= 3), aux = b3.
        val p = pbProblem(
            PbOp.LE,
            bound = 3,
            lits = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
            weights = intArrayOf(2, 3, 1),
            aux = 3,
            nBool = 4,
        )
        val excluded = checkSoundness(p, nBool = 4, aux = 3) { 2 * b(it, 0) + 3 * b(it, 1) + b(it, 2) <= 3 }
        assertTrue(excluded > 0, "LE encodes both directions, so some inconsistent point must be excluded")
    }

    @Test
    fun `reified pseudo-boolean GE is sound`() {
        val p = pbProblem(
            PbOp.GE,
            bound = 4,
            lits = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
            weights = intArrayOf(2, 3, 1),
            aux = 3,
            nBool = 4,
        )
        val excluded = checkSoundness(p, nBool = 4, aux = 3) { 2 * b(it, 0) + 3 * b(it, 1) + b(it, 2) >= 4 }
        assertTrue(excluded > 0)
    }

    @Test
    fun `reified pseudo-boolean EQ is sound`() {
        val p = pbProblem(
            PbOp.EQ,
            bound = 3,
            lits = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
            weights = intArrayOf(2, 3, 1),
            aux = 3,
            nBool = 4,
        )
        // EQ only encodes the aux=1 ⇒ (=bound) direction, so just soundness is asserted.
        checkSoundness(p, nBool = 4, aux = 3) { 2 * b(it, 0) + 3 * b(it, 1) + b(it, 2) == 3 }
    }

    @Test
    fun `reified pseudo-boolean with a negative literal is sound`() {
        // aux <-> (2 b0 + 3 (1 - b1) <= 3) = (2 b0 - 3 b1 <= 0).
        val p = pbProblem(
            PbOp.LE,
            bound = 3,
            lits = intArrayOf(Lit.make(0, true), Lit.make(1, false)),
            weights = intArrayOf(2, 3),
            aux = 2,
            nBool = 3,
        )
        val excluded = checkSoundness(p, nBool = 3, aux = 2) { 2 * b(it, 0) + 3 * (1 - b(it, 1)) <= 3 }
        assertTrue(excluded > 0)
    }

    @Test
    fun `reified cardinality range is sound`() {
        // aux <-> (1 <= #true{b0,b1,b2} <= 2), aux = b3.
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = arrayOf(),
            factors = arrayOf<Factor>(
                ReifiedCardinality(3, intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), 1, 2),
            ),
        )
        checkSoundness(p, nBool = 4, aux = 3) {
            val c = b(it, 0) + b(it, 1) + b(it, 2)
            c in 1..2
        }
    }

    private fun b(bits: BooleanArray, i: Int): Int = if (bits[i]) 1 else 0
}
