package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Soundness coverage for the trail-resident order-literal ladder (#588). Atom truth and level are
 * now *stored* on per-atom trail slots and maintained incrementally — set when a bound move crosses
 * the threshold ([wakeAtom]), reconstructed at materialization, and reset by [resetAtomTrailFor]'s
 * range-limited undo (which clears only the order literals whose truth flips). Crucially
 * [atomCurrentTruth] has **no derive fallback** any more: it returns the stored bit verbatim. So the
 * core invariant the whole representation rests on is:
 *
 *   for every materialized atom, after any sequence of bound moves and backtracks,
 *   the STORED truth ([atomCurrentTruth]) equals the truth DERIVED from the current domain
 *   ([atomTruthOf]).
 *
 * A stale slot (a missed flip on undo, an off-by-one in the widened range, a hole eq atom wrongly
 * cleared) breaks this and silently corrupts truth → unsound search. This drives a [PropagationState]
 * directly through randomized decision pushes (min/max tightens, interior carves) and undos, checking
 * the invariant after every operation across the full atom space. The same stale-truth class is what
 * surfaced (via the brute-force enumeration suites) as the eq-at-hole bug during development.
 */
class TrailResidentAtomSoundnessTest {

    private fun freshState(numVars: Int, hi: Int): PropagationState {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = numVars,
            intDomains = Array(numVars) { IntDomain(0, hi) },
            factors = arrayOf<Factor>(),
        )
        return PropagationState(problem, Assumptions.None).also { it.undoLogging = true }
    }

    /** Materialize every bound and value order literal for every var, so the invariant check
     *  covers GE/LE/EQ at every threshold (incl. the now-hole-bearing interior eq atoms). */
    private fun materializeAllAtoms(s: PropagationState, numVars: Int, hi: Int) {
        for (v in 0 until numVars) {
            for (k in 0..hi) {
                s.atomVarGe(v, k)
                s.atomVarLe(v, k)
                s.atomVarEq(v, k)
            }
        }
    }

    /**
     * Assert the **stored** truth of every materialized atom equals the truth **derived** from the
     * current domain. This is the soundness-critical invariant: [atomCurrentTruth] reads the stored
     * [PropagationState.atomState] bit with no domain fallback, so any stale bit (a missed flip on
     * undo, an off-by-one widened range, a hole eq atom wrongly cleared) silently corrupts truth.
     *
     * The stored *level* deliberately is NOT checked: the engine treats a level left high by a pop
     * as a stale advisory and clamps it on read (`maxLevelForVars`), so a stored level above the
     * live decision count is expected, not a violation.
     */
    private fun assertAtomTruthConsistent(s: PropagationState, where: String) {
        for (id in 0 until s.atomIntVar.size) {
            val v = s.atomIntVar[id]
            assertEquals(
                s.atomTruthOf(v, s.atomKind[id], s.atomThreshold[id]),
                s.atomCurrentTruth(id),
                "stored atom truth diverged from the domain at $where: atom id=$id " +
                    "(var=$v kind=${s.atomKind[id]} k=${s.atomThreshold[id]}) domain=${s.intDomains[v]}",
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
            0 -> s.tightenIntMin(v, rng.nextInt(d.min + 1, d.max + 1))

            // v >= lo
            1 -> s.tightenIntMax(v, rng.nextInt(d.min, d.max))

            // v <= hi
            else -> { // carve an interior value (falls back to an edge tighten if at a bound)
                val value = rng.nextInt(d.min, d.max + 1)
                s.excludeIntValue(v, value)
            }
        }
    }

    @Test
    fun storedAtomTruthMatchesDomainAcrossPushAndPop() {
        val numVars = 3
        val hi = 7
        val rng = Random(0x5eed)
        repeat(40) { trial ->
            val s = freshState(numVars, hi)
            materializeAllAtoms(s, numVars, hi)
            assertAtomTruthConsistent(s, "root/trial=$trial")
            val marks = ArrayDeque<PropagationState.LevelMark>()
            marks.addLast(s.mark())
            repeat(60) { step ->
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
