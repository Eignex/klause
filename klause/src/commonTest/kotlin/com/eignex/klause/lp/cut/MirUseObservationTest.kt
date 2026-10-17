package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.UnitConsts
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MirUseBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MirUseObservationTest {
    @Test
    fun `exact metadata should distinguish potential rows from actual admission without changing cuts`() {
        val row = ExactLinear.of(IntArray(2) { Term.ofIntVar(it) }, List(2) { BigFraction.ofLong(2) },
            LinearOp.LE, BigFraction.ofLong(3))
        val p = Problem(0, 2, Array(2) { IntDomain(0, 3) }, arrayOf<Factor>(row))
        val session = PropagationSession(p)
        val rel = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(-1, -1))).build(session)
        val ctx = CutContext(p, rel, doubleArrayOf(0.75, 0.75), session)
        val budget = assertNotNull(MirUseBudget.create())
        val owner = assertNotNull(budget.owner(1010L, 3, 0, 2, 0, 1))
        val plain = AggregationMirSeparator().separate(ctx)

        val observed = AggregationMirSeparator(MirUseObservation(owner)).separate(ctx)
        val snapshot = budget.finish()

        assertEquals(plain.map { it.coeffs.toList() to it.rhs }, observed.map { it.coeffs.toList() to it.rhs })
        val words = snapshot.encoded.removeSurrounding("[", "]").split(',').map(String::toLong)
        assertEquals(1L, words[30])
        assertEquals(if (plain.isEmpty()) 0L else 1L, words[32])
        assertTrue(snapshot.retainedScalars <= 2048)
        assertTrue(snapshot.encoded.length <= 65536)
    }

    @Test
    fun `a produced pair cut should retain both exact word parent identities`() {
        val p = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1),
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
        ))
        val session = PropagationSession(p)
        val rel = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(-1, -1))).build(session)
        val ctx = CutContext(p, rel, doubleArrayOf(0.5, 0.5), session)
        val budget = assertNotNull(MirUseBudget.create())
        val owner = assertNotNull(budget.owner(1010L, 3, 0, 2, 0, 2))

        val cuts = AggregationMirSeparator(MirUseObservation(owner)).separate(ctx)
        val snapshot = budget.finish()

        assertEquals(1, cuts.size)
        val words = snapshot.encoded.removeSurrounding("[", "]").split(',').map(String::toLong)
        assertEquals(2L, words[36])
        assertEquals(1L, words[37])
        assertTrue(snapshot.complete)
    }

    @Test
    fun `retained row words should not alias producer arrays`() {
        val row = Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3)
        val p = Problem(0, 2, Array(2) { IntDomain(0, 3) }, arrayOf<Factor>(row))
        val session = PropagationSession(p)
        val rel = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(-1, -1))).build(session)
        val ctx = CutContext(p, rel, doubleArrayOf(0.75, 0.75), session)
        val budget = assertNotNull(MirUseBudget.create())
        val owner = assertNotNull(budget.owner(1010L, 3, 0, 2, 0, 1))
        val observation = MirUseObservation(owner)
        val columns = intArrayOf(rel.intColOf[0], rel.intColOf[1])
        val coefficients = longArrayOf(2, 2)
        observation.begin(ctx)
        observation.factor(0, row)
        observation.integerRow(row, false, 0, columns, coefficients, 3L, ctx)

        columns[0] = -1
        coefficients[0] = 999L
        val snapshot = budget.finish()

        val words = snapshot.encoded.removeSurrounding("[", "]").split(',').map(String::toLong)
        val normalized = 16 + 40 + 11 + 2 * row.size
        assertEquals(rel.intColOf[0].toLong(), words[normalized])
        assertEquals(2L, words[normalized + 3])
    }

    @Test
    fun `oversized metadata should decline before visiting source references`() {
        val p = Problem(0, 0, emptyArray(), emptyArray())
        val session = PropagationSession(p)
        val rel = CpToLpRelaxation(p, null).build(session)
        val ctx = CutContext(p, rel, doubleArrayOf(), session)
        val row = object : LinearRow {
            override val size = Int.MAX_VALUE
            override val constants = IntegerConstants(UnitConsts(1), 0L)
            override val relation = LinearOp.LE
            override fun ref(k: Int): Int = error("metadata input walk must be refused")
        }
        val budget = assertNotNull(MirUseBudget.create())
        val owner = assertNotNull(budget.owner(1010L, 3, 0, 0, 0, 0))
        val observation = MirUseObservation(owner)
        observation.begin(ctx)

        observation.integerRow(row, false, 0, intArrayOf(), longArrayOf(), 0L, ctx)
        val snapshot = budget.finish()

        assertFalse(snapshot.complete)
        assertTrue(snapshot.encoded.length < 4096)
    }

    @Test
    fun `produced cuts should remain unidentified when parent row metadata is unavailable`() {
        val p = Problem(0, 2, Array(2) { IntDomain(0, 3) }, emptyArray())
        val session = PropagationSession(p)
        val rel = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(-1, -1))).build(session)
        val ctx = CutContext(p, rel, doubleArrayOf(0.75, 0.75), session)
        val budget = assertNotNull(MirUseBudget.create())
        val owner = assertNotNull(budget.owner(1010L, 3, 0, 2, 0, 0))
        val observation = MirUseObservation(owner)
        observation.begin(ctx)
        val cut = Cut(intArrayOf(rel.intColOf[0], rel.intColOf[1]), longArrayOf(1, 1), Relation.LE, 1L,
            global = true)

        observation.produced(0, -1, cut, ctx)
        val snapshot = budget.finish()

        val words = snapshot.encoded.removeSurrounding("[", "]").split(',').map(String::toLong)
        assertEquals(1L, words[34])
        assertEquals(0L, words[37])
        assertFalse(snapshot.complete)
    }
}
