package com.eignex.klause.propagation

import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Soundness coverage for the trail-resident order-literal ladder (#588). Atom truth and level are
 * *stored* on per-atom trail slots as a forward cache — set when a bound move crosses the threshold
 * ([wakeAtom]) or a clause forces the literal ([PropagationState.pinAtomLit]), and restored on
 * backtrack by the reversible atom trail ([recordAtomTruthChange] / [undoTo]). A cache miss (`0`)
 * falls back to the domain-derived [atomTruthOf], so the core invariant the whole representation
 * rests on is:
 *
 *   for every materialized atom, after any sequence of bound moves and backtracks,
 *   the truth read ([atomCurrentTruth]) equals the truth DERIVED from the current domain
 *   ([atomTruthOf]) — i.e. a *cached* `1`/`2` bit never disagrees with the live domain.
 *
 * A stale slot (a missed flip on undo, an off-by-one in the widened range, a hole eq atom wrongly
 * cleared) breaks this and silently corrupts truth → unsound search. This drives a [PropagationState]
 * directly through randomized decision pushes (min/max tightens, interior carves) and undos, checking
 * the invariant after every operation across the full atom space. The same stale-truth class is what
 * the brute-force enumeration suites surface as the eq-at-hole failure.
 */
class PropagationStateTrailResidentAtomSoundnessTest {

    private fun freshState(numVars: Int, hi: Int): PropagationState {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = numVars,
            intDomains = Array(numVars) { IntDomain(0, hi.toLong()) },
            factors = arrayOf<Factor>(),
        )
        return PropagationState(problem, Assumptions.None).also { it.undoLogging = true }
    }

    /** Materialize every bound and value order literal for every var, so the invariant check
     *  covers GE/LE/EQ at every threshold, including the hole-bearing interior eq atoms. */
    private fun materializeAllAtoms(s: PropagationState, numVars: Int, hi: Int) {
        for (v in 0 until numVars) {
            for (k in 0..hi) {
                s.atomVarGe(v, k.toLong())
                s.atomVarLe(v, k.toLong())
                s.atomVarEq(v, k.toLong())
            }
        }
    }

    /**
     * Assert the truth read for every materialized atom equals the truth **derived** from the current
     * domain. This is the soundness-critical invariant: [atomCurrentTruth] returns the cached
     * [PropagationState.atoms.truth] bit when present, so any stale *cached* bit — one the reversible
     * atom trail failed to restore on backtrack — silently corrupts truth. An uncached (`0`) slot
     * falls back to the domain and so is consistent by construction; the hazard is a surviving `1`/`2`.
     *
     * The stored *level* deliberately is NOT checked: the engine treats a level left high by a pop
     * as a stale advisory and clamps it on read (`maxLevelForVars`), so a stored level above the
     * live decision count is expected, not a violation.
     */
    private fun assertAtomTruthConsistent(s: PropagationState, where: String) {
        for (id in 0 until s.atoms.intVar.size) {
            val v = s.atoms.intVar[id]
            assertEquals(
                s.atomTruthOf(v, s.atoms.kind[id], s.atoms.threshold[id]),
                s.atomCurrentTruth(id),
                "stored atom truth diverged from the domain at $where: atom id=$id " +
                    "(var=$v kind=${s.atoms.kind[id]} k=${s.atoms.threshold[id]}) domain=${s.intDomains[v]}",
            )
        }
    }

    /** Push a random narrowing of [v] as a fresh decision; returns false if no room. */
    private fun randomDecision(s: PropagationState, v: Int, rng: Random): Boolean {
        val d = s.intDomains[v]
        if (d.min == d.max) return false
        s.levelToDecisionVar.add(s.problem.numBoolVars + v)
        s.currentLevel = s.levelToDecisionVar.size
        s.currentFactor = -1
        return when (rng.nextInt(3)) {
            0 -> s.tightenIntMin(v, rng.nextInt((d.min + 1).toInt(), (d.max + 1).toInt()).toLong())

            // v >= lo
            1 -> s.tightenIntMax(v, rng.nextInt(d.min.toInt(), d.max.toInt()).toLong())

            // v <= hi
            else -> { // carve an interior value (falls back to an edge tighten if at a bound)
                val value = rng.nextInt(d.min.toInt(), (d.max + 1).toInt()).toLong()
                s.excludeIntValue(v, value)
            }
        }
    }

    @Test
    fun `stored atom truth matches the live domain across push and pop`() {
        val numVars = 3
        val hi = 6
        val rng = Random(0x5eed)
        repeat(12) { trial ->
            val s = freshState(numVars, hi)
            materializeAllAtoms(s, numVars, hi)
            assertAtomTruthConsistent(s, "root/trial=$trial")
            val marks = ArrayDeque<PropagationState.LevelMark>()
            marks.addLast(s.mark())
            repeat(40) { step ->
                val pop = marks.size > 1 && rng.nextInt(3) == 0
                if (pop) {
                    // Backtrack to a random earlier level — the range-limited undo path.
                    val target = rng.nextInt(0, marks.size - 1)
                    while (marks.size > target + 1) marks.removeLast()
                    s.undoTo(marks.last())
                } else {
                    val v = rng.nextInt(numVars)
                    if (randomDecision(s, v, rng)) marks.addLast(s.mark())
                }
                assertAtomTruthConsistent(s, "trial=$trial step=$step pop=$pop")
            }
        }
    }
}
