package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Equivalence + soundness coverage for the batched exclusion path ([excludeIntValues], #599).
 *
 * Folding [excludeIntValue] over a sorted value list one-at-a-time rebuilds the hole array per
 * value (O(domain^2)); the batch merges them in one [IntDomain.excludeValues] pass. The two must
 * be observationally identical: after either, the **domain** and every materialized order
 * literal's **stored truth + level** must match. (Reasons/antecedents may legitimately differ —
 * the batch cites the shared batch reason where the sequential path chains rung-to-rung — but a
 * reason is only ever allowed to grow, never to drop a needed literal, so soundness is preserved;
 * end-state truth/level is the property that must hold exactly.)
 */
class PropagationStateBatchExcludeTest {

    private fun freshState(numVars: Int, hi: Int): PropagationState {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = numVars,
            intDomains = Array(numVars) { IntDomain(0, hi) },
            factors = arrayOf<Factor>(),
        )
        return PropagationState(problem, Assumptions.None).also { it.undoLogging = true }
    }

    private fun materializeAllAtoms(s: PropagationState, numVars: Int, hi: Int) {
        for (v in 0 until numVars) {
            for (k in 0..hi) {
                s.atomVarGe(v, k)
                s.atomVarLe(v, k)
                s.atomVarEq(v, k)
            }
        }
    }

    /** Enter a fresh decision level attributed to a (dummy) propagating factor. */
    private fun enterLevel(s: PropagationState, v: Int) {
        s.levelToDecisionVar.add(s.problem.numBoolVars + v)
        s.currentLevel = s.levelToDecisionVar.size
        s.currentFactor = 0
    }

    private fun assertStatesEquivalent(seq: PropagationState, batch: PropagationState, where: String) {
        assertEquals(seq.intDomains[0], batch.intDomains[0], "domain mismatch at $where")
        assertEquals(seq.atomIntVar.size, batch.atomIntVar.size)
        for (id in 0 until seq.atomIntVar.size) {
            assertEquals(
                seq.atomCurrentTruth(id),
                batch.atomCurrentTruth(id),
                "atom truth mismatch at $where: id=$id kind=${seq.atomKind[id]} k=${seq.atomThreshold[id]} " +
                    "seqDom=${seq.intDomains[0]} batchDom=${batch.intDomains[0]}",
            )
            assertEquals(
                seq.atomLvl[id],
                batch.atomLvl[id],
                "atom level mismatch at $where: id=$id kind=${seq.atomKind[id]} k=${seq.atomThreshold[id]}",
            )
        }
    }

    @Test
    fun batchExcludeMatchesSequentialEndState() {
        val hi = 14
        val rng = Random(0xBA7C4)
        repeat(600) { trial ->
            // A random subset of 0..hi to exclude, leaving at least one survivor.
            val picked = mutableSetOf<Int>()
            val k = rng.nextInt(0, hi + 1)
            repeat(k) { picked.add(rng.nextInt(0, hi + 1)) }
            if (picked.size > hi) picked.remove(picked.first()) // keep a survivor
            val values = picked.toIntArray().also { it.sort() }

            val seq = freshState(1, hi)
            materializeAllAtoms(seq, 1, hi)
            enterLevel(seq, 0)
            for (v in values) seq.excludeIntValue(0, v)

            val batch = freshState(1, hi)
            materializeAllAtoms(batch, 1, hi)
            enterLevel(batch, 0)
            batch.excludeIntValues(0, values, null)

            assertStatesEquivalent(seq, batch, "trial=$trial values=${values.toList()}")
        }
    }

    @Test
    fun batchExcludeReportsEmptyDomainAsConflict() {
        val s = freshState(1, 4)
        materializeAllAtoms(s, 1, 4)
        enterLevel(s, 0)
        assertEquals(false, s.excludeIntValues(0, intArrayOf(0, 1, 2, 3, 4), null), "excluding all → conflict")
    }

    @Test
    fun batchExcludeAbsentValuesIsNoOp() {
        val s = freshState(1, 4)
        materializeAllAtoms(s, 1, 4)
        enterLevel(s, 0)
        // 2 is already a hole; 7,9 are out of range — all no-ops, domain unchanged.
        s.excludeIntValue(0, 2)
        val before = s.intDomains[0]
        assertEquals(true, s.excludeIntValues(0, intArrayOf(2, 7, 9), null))
        assertEquals(before, s.intDomains[0])
    }

    /**
     * Drive a [PropagationState] through randomized batched exclusions interleaved with single
     * bound moves and backtracks, asserting after every step that each materialized atom's stored
     * truth still equals the truth derived from the live domain. This exercises the tag-3 undo
     * record (interior eq atoms reset on pop) against the range-limited bound-literal reset.
     */
    @Test
    fun batchExcludeSoundAcrossPushAndPop() {
        val numVars = 3
        val hi = 9
        val rng = Random(0x5eed5)
        repeat(12) { trial ->
            val s = freshState(numVars, hi)
            materializeAllAtoms(s, numVars, hi)
            val marks = ArrayDeque<PropagationState.LevelMark>()
            marks.addLast(s.mark())
            repeat(45) { step ->
                val pop = marks.size > 1 && rng.nextInt(3) == 0
                if (pop) {
                    val target = rng.nextInt(0, marks.size - 1)
                    while (marks.size > target + 1) marks.removeLast()
                    s.undoTo(marks.last())
                } else {
                    val v = rng.nextInt(numVars)
                    val d = s.intDomains[v]
                    if (d.min != d.max) {
                        enterLevel(s, v)
                        // Exclude a random non-empty subset of the live domain, keeping a survivor.
                        val picked = mutableSetOf<Int>()
                        val live = ArrayList<Int>()
                        d.forEach { live.add(it) }
                        val take = rng.nextInt(1, live.size)
                        repeat(take) { picked.add(live[rng.nextInt(live.size)]) }
                        if (picked.size >= live.size) picked.remove(picked.first())
                        val values = picked.toIntArray().also { it.sort() }
                        if (s.excludeIntValues(v, values, null)) marks.addLast(s.mark())
                    }
                }
                for (id in 0 until s.atomIntVar.size) {
                    val v = s.atomIntVar[id]
                    assertEquals(
                        s.atomTruthOf(v, s.atomKind[id], s.atomThreshold[id]),
                        s.atomCurrentTruth(id),
                        "stored truth diverged at trial=$trial step=$step pop=$pop: id=$id " +
                            "(var=$v kind=${s.atomKind[id]} k=${s.atomThreshold[id]}) domain=${s.intDomains[v]}",
                    )
                }
            }
        }
    }
}
