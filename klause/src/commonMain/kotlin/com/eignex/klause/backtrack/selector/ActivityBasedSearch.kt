package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.IndexedMaxHeap
import com.eignex.klause.util.IntArrayList
import kotlin.random.Random

/**
 * Activity-based search (Michel–Van Hentenryck 2012). Picks `argmax activity(v) / dom(v)`
 * over unpinned variables. [PropagationResult.Implied] bumps variables forced into singletons;
 * partial domain reductions do not contribute.
 *
 * Decay uses geometric increment growth and rescales near [Double] overflow. Higher [decay]
 * retains older evidence. Activities survive [onRestart] unless [resetOnRestart] is true.
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
