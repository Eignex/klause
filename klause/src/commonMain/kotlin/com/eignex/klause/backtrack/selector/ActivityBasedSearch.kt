package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.IndexedMaxHeap
import com.eignex.klause.util.IntArrayList
import kotlin.random.Random

/**
 * Activity-Based Search (Michel-Van Hentenryck 2012). Maintains a per-variable *activity*
 * counter that increments every time the variable is
 * forced into a singleton by a propagation step (read off [PropagationResult.Implied]).
 * Different from VSIDS: VSIDS bumps only on conflicts; ABS bumps on *every* propagation
 * event the variable participates in — a broader, lower-variance signal of which variables
 * the constraint network is structurally hardest at.
 *
 * Picks `argmax a(v) / dom(v)` over unpinned variables. Decay is implicit via geometric
 * `increment` growth (same trick as VSIDS); per-decision rescale when `increment` nears
 * `Double` overflow. Activities persist across [onRestart] by default — that's how ABS
 * learns from one run to the next; set [resetOnRestart] to true to clear them at every
 * Luby restart for an aggressive variant.
 *
 *  - [decay] ∈ (0, 1): higher = more conservative (gives long-tail history more weight),
 *    lower = more aggressive (forgets old conflicts fast).
 *  - [resetOnRestart] = false (default): preserve activities across restarts; true clears
 *    them, useful for "ABS restart" mode where each run rebuilds the activity map.
 *
 * Caveat: klause's [PropagationResult.Implied] currently reports newly-singletoned
 * variables, not every var whose domain was reduced. So our ABS activity is a narrower
 * signal than the textbook version (which counts every domain-reduction event). Still
 * captures the dominant signal on most CSPs — vars frequently forced into singletons are
 * exactly the structurally-critical ones.
 */
internal class ActivityBasedSearch(
    private val decay: Double = 0.999,
    private val resetOnRestart: Boolean = false,
    private val rescaleThreshold: Double = 1e100,
) : VariableSelector {

    init {
        require(decay in 0.5..0.9999) { "ABS decay must be in 0.5..0.9999, got $decay" }
    }

    override fun fresh() = ActivityBasedSearch(decay, resetOnRestart, rescaleThreshold)

    private var increment: Double = 1.0

    // Combined bool+int heap keyed on raw activity; dom(v) divider applied at pick time.
    // Shares [pickByActivityWithDomDivider] with [DomWdeg].
    private var heap: IndexedMaxHeap? = null
    private var numBoolCached: Int = 0
    private var numIntCached: Int = 0
    private val pickSkipBuffer = IntArrayList(16)

    private fun ensureSized(numBool: Int, numInt: Int) {
        if (heap != null && numBoolCached == numBool && numIntCached == numInt) return
        val h = IndexedMaxHeap(numBool + numInt)
        for (i in 0 until numBool + numInt) h.insert(i, 1.0)
        heap = h
        numBoolCached = numBool
        numIntCached = numInt
    }

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        ensureSized(problem.numBoolVars, problem.numIntVars)
        return pickByActivityWithDomDivider(
            heap = requireNotNull(heap),
            session = session,
            numBool = numBoolCached,
            skip = pickSkipBuffer,
        )
    }

    override fun onPropagation(implied: PropagationResult.Implied) {
        val h = heap ?: return
        implied.forEachBool { id, _ ->
            if (id < numBoolCached) h.updateKey(id, h.keyOf(id) + increment)
        }
        implied.forEachInt { id, _ ->
            if (id < numIntCached) {
                val combined = numBoolCached + id
                h.updateKey(combined, h.keyOf(combined) + increment)
            }
        }
    }

    override fun onCommit(varRef: VarRef) {
        // Implicit decay: grow increment so future bumps are larger (equivalent to dividing
        // every prior activity by `decay`, without touching the keys). Same trick as VSIDS.
        increment /= decay
        if (increment > rescaleThreshold) rescaleAll()
    }

    override fun onRestart() {
        if (resetOnRestart) {
            val h = heap ?: return
            h.resetAllKeysInIdOrder(1.0)
            increment = 1.0
        }
    }

    private fun rescaleAll() {
        val h = heap ?: return
        h.scaleKeys(1.0 / rescaleThreshold)
        increment *= 1.0 / rescaleThreshold
    }
}
