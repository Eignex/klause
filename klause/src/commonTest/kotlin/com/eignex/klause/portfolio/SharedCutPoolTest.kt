package com.eignex.klause.portfolio

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.lp.Relation
import com.eignex.klause.solver.lp.cut.Cut
import com.eignex.klause.solver.lp.cut.CutPool
import com.eignex.klause.solver.lp.cut.CutSharing
import com.eignex.klause.solver.lp.cut.SharedCut
import com.eignex.klause.solver.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.solver.lp.relaxation.LpRelaxation
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [SharedCutPool] + [PoolCutExchange]: a global cut one arm harvests must reach another arm's local
 *  [CutPool] through the pool, mapped onto that arm's relaxation, and the pool must de-duplicate. */
class SharedCutPoolTest {

    private val problem = Problem(
        0,
        3,
        Array(3) { IntDomain(0, 5) },
        arrayOf<Factor>(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 4)),
    )

    private fun relax(obj: LinearObjective): LpRelaxation =
        CpToLpRelaxation(problem, obj).build(PropagationSession(problem))

    /** A worker's [CutSharing] view over a local pool + relaxation — what [LpEngine] provides in
     *  production, inlined here so the test drives [PoolCutExchange] without a full engine. */
    private fun sharing(local: CutPool, rel: LpRelaxation) = object : CutSharing {
        override fun exportGlobalCuts(): List<SharedCut> =
            local.cuts().mapNotNull { if (it.global) SharedCut.fromCut(it, rel) else null }

        override fun importCuts(cuts: List<SharedCut>) {
            for (c in cuts) c.toCut(rel)?.let { local.add(it) }
        }
    }

    @Test
    fun `a global cut published by one arm reaches another arm`() {
        val rA = relax(LinearObjective(intCoefficients = longArrayOf(1, 0, 0)))
        val rB = relax(LinearObjective(intCoefficients = longArrayOf(0, 0, 1)))
        val pool = SharedCutPool()

        val localA = CutPool().apply {
            add(Cut(intArrayOf(rA.intColOf[0], rA.intColOf[1]), longArrayOf(1, 1), Relation.LE, 1, global = true))
        }
        PoolCutExchange(pool).exchange(sharing(localA, rA)) // arm A publishes

        val localB = CutPool()
        PoolCutExchange(pool).exchange(sharing(localB, rB)) // arm B imports

        assertEquals(1, localB.size, "arm B should receive arm A's cut")
        val imported = localB.cuts().single()
        // Same inequality over CP variables, re-mapped onto B's columns.
        assertEquals(setOf(0, 1), imported.cols.map { rB.colVarId[it] }.toSet())
        assertEquals(Relation.LE, imported.rel)
        assertEquals(1, imported.rhs)
    }

    @Test
    fun `re-exchange imports nothing new and the pool de-duplicates`() {
        val r = relax(LinearObjective(intCoefficients = longArrayOf(1, 0, 0)))
        val pool = SharedCutPool()
        val localA = CutPool().apply {
            add(Cut(intArrayOf(r.intColOf[0], r.intColOf[1]), longArrayOf(1, 1), Relation.LE, 1, global = true))
        }
        val exA = PoolCutExchange(pool)
        exA.exchange(sharing(localA, r))
        exA.exchange(sharing(localA, r)) // second pass must not re-publish (seen-set + pool key de-dup)

        val localB = CutPool()
        val exB = PoolCutExchange(pool)
        exB.exchange(sharing(localB, r))
        val afterFirst = localB.size
        exB.exchange(sharing(localB, r)) // cursor advanced ⇒ nothing new
        assertEquals(afterFirst, localB.size, "a second exchange imports nothing new")
        assertEquals(1, afterFirst)
    }

    @Test
    fun `pool drains since a cursor and ignores duplicate keys`() {
        val pool = SharedCutPool()
        val noBool = booleanArrayOf(false, false)
        val a = SharedCut(intArrayOf(0, 1), noBool, longArrayOf(1, 1), Relation.LE, 2)
        val aGain = SharedCut(intArrayOf(1, 0), noBool, longArrayOf(1, 1), Relation.LE, 2) // same inequality ⇒ same key
        val b = SharedCut(intArrayOf(0, 2), noBool, longArrayOf(2, 1), Relation.LE, 3)
        pool.publish(listOf(a, aGain, b))

        val first = pool.drainSince(0)
        assertEquals(2, first.cuts.size, "duplicate key dropped")
        assertTrue(pool.drainSince(first.cursor).cuts.isEmpty(), "nothing new past the cursor")
    }
}
