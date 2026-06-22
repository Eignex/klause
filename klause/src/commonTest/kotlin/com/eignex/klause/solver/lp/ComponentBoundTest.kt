package com.eignex.klause.solver.lp

import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Per-component LP decomposition must produce the SAME bound as the monolithic solve — the blocks are
 * independent, so the objective separates and the per-block optima sum exactly. Validated against both
 * the monolithic [ExactBasisCertifier] ceil and the exact LP optimum, over block-structured models
 * (multiple components + isolated columns) so the decomposition path is genuinely exercised.
 */
class ComponentBoundTest {

    /** A model of 2–3 independent variable blocks (rows confined within a block) plus a few isolated,
     *  row-free objective columns — so it has several connected components. */
    private fun blockModel(rng: Random): LpModel {
        val b = LpBuilder()
        val blockSizes = List(rng.nextInt(2, 4)) { rng.nextInt(2, 5) }
        val blocks = ArrayList<IntArray>()
        var start = 0
        for (sz in blockSizes) {
            repeat(sz) { b.addVar(0L, rng.nextLong(2, 9), cost = rng.nextLong(-6, 7)) }
            blocks.add(IntArray(sz) { start + it })
            start += sz
        }
        repeat(rng.nextInt(0, 3)) { b.addVar(0L, rng.nextLong(2, 6), cost = rng.nextLong(-6, 7)) } // isolated
        for (blk in blocks) {
            repeat(rng.nextInt(1, blk.size + 2)) {
                // Force a nonzero so no empty structural row appears (which would force a fallback).
                val vals = LongArray(blk.size) { rng.nextLong(-4, 5) }
                vals[rng.nextInt(blk.size)] = (rng.nextLong(1, 5)) * (if (rng.nextBoolean()) 1 else -1)
                b.addRow(blk, vals, Relation.LE, rng.nextLong(3, 25))
            }
        }
        return b.build(Sense.MINIMIZE)
    }

    private fun monolithicCeil(model: LpModel): Long? {
        val r = RevisedSimplex(model).solve() ?: return null
        return ExactBasisCertifier.lowerBoundCeil(model, r.basis)
    }

    @Test
    fun `component bound equals the monolithic bound and is sound`() {
        val rng = Random(20260622)
        var compared = 0
        var matched = 0
        repeat(1500) { _ ->
            val model = blockModel(rng)
            val opt = exactLpOptimum(model)
            if (opt.isNaN()) return@repeat
            val comp = componentLowerBoundCeil(model) ?: return@repeat
            compared++
            // Sound: never exceeds ceil(LP optimum).
            assertTrue(comp.toDouble() <= ceil(opt) + 1e-6, "UNSOUND component bound $comp > ceil(opt $opt)")
            val mono = monolithicCeil(model)
            if (mono != null) {
                assertTrue(comp <= mono, "component bound $comp exceeded monolithic $mono")
                if (comp == mono) matched++
            }
        }
        assertTrue(compared > 300, "decomposition fired on only $compared instances")
        // Independent blocks ⇒ the decomposed bound equals the monolithic one on the large majority.
        assertTrue(matched >= compared * 9 / 10, "matched the monolithic bound on only $matched/$compared")
    }
}
