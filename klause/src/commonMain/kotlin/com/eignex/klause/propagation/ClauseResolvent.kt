package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.DynamicBits
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet

// Classical 1UIP clause resolvent (L. Zhang, C. F. Madigan, M. W. Moskewicz, S. Malik, "Efficient Conflict
// Driven Learning in a Boolean Satisfiability Solver", ICCAD 2001, https://doi.org/10.1109/ICCAD.2001.968634).
// It shares the ReasonGraph level and antecedent view with the pivot scan and minimizer.
internal class ClauseResolvent(private val state: PropagationState, private val graph: ReasonGraph) :
    ConflictResolvent {

    // Reads through the shared ReasonGraph so its atom-level memo is reused.
    private val minimizer = ClauseMinimizer(state, graph)

    // Scratch is grown once; loops bound by [universe] because a buffer may exceed it after a deeper earlier
    // conflict.
    private var universe = 0
    private var seen = DynamicBits()

    // Variables already resolved out as a pivot this analysis. The driver resolves the frontier in
    // reverse-assignment order, so a resolved variable recurs only when a reason cites a literal the trail
    // recorded late; [resolve] then skips it. Re-ingesting a resolved variable would let the 1UIP loop
    // ping-pong forever (and grow [bumpIntVars] until OOM), so the guard stays.
    private var resolved = DynamicBits()

    // The VSIDS bump set: every variable seen while walking the implication graph.
    override val bumpBoolVars = IntArrayList()
    override val bumpIntVars = IntArrayList()

    // Fallback frontier for seen atoms materialized mid-analysis and so off the pin trail, scanned in
    // O(frontier) rather than over all atoms. A superset of the seen atoms, so the scan re-checks [isFrontier].
    override val offTrailFrontier = IntArrayList()

    // Literals waiting to be folded in. Draining an explicit list keeps substituting an order literal for its
    // premises free of recursion depth however long the derived-reason chain gets.
    private val pending = IntArrayList()

    // Order literals already resolved through by substitution this analysis — the [resolved] role for
    // variables that have no frontier slot. Keyed by var id rather than indexed by [universe] because
    // building a derived reason materialises atoms, so ids grow past the universe the analysis started
    // with.
    private val substituted = IntHashSet()

    // Holds one literal per variable, so this is the O(1) by-variable dedup that keeps analysis linear.
    private val litsInLearned = IntHashSet()

    // Reused because a conflict is on the hot path; [finalizeResult] copies it out.
    private val learned = IntArrayList()

    // 1UIP terminates when resolving a pivot drops this to zero.
    private var currentLevelCount = 0

    override val liveAtCurrentLevel: Int get() = currentLevelCount

    override fun isFrontier(v: Int): Boolean = seen[v]

    override fun reset(universe: Int) {
        this.universe = universe
        seen = scratch(seen, universe)
        resolved = scratch(resolved, universe)
        offTrailFrontier.clear()
        pending.clear()
        substituted.clear()
        litsInLearned.clear()
        bumpBoolVars.clear()
        bumpIntVars.clear()
        currentLevelCount = 0
        learned.clear()
    }

    private fun scratch(bits: DynamicBits, n: Int): DynamicBits = bits.also {
        it.resize(n)
        it.clear()
    }

    override fun resolveOut(pivot: Int) {
        seen[pivot] = false
        resolved[pivot] = true
        currentLevelCount--
    }

    override fun addAsserting(pivot: Int) {
        addLearned(uipLit(pivot))
    }

    // Every literal reaches the clause through here, keeping [litsInLearned] exact.
    private fun addLearned(lit: Int) {
        learned.add(lit)
        litsInLearned.add(lit)
    }

    private fun uipLit(pivot: Int): Int {
        val numBoolVars = state.problem.numBoolVars
        return if (pivot < numBoolVars) {
            val pinned = state.boolValues[pivot] ?: error("UIP bool var $pivot unpinned")
            Lit.make(pivot, !pinned)
        } else {
            val atomId = pivot - numBoolVars
            val holds = state.atomCurrentTruth(atomId) ?: error("UIP atom $atomId undetermined")
            Lit.make(pivot, !holds)
        }
    }

    // Current-level literals join the frontier; lower-level ones go straight to the clause.
    override fun resolve(reason: IntArray, currentLevel: Int) {
        for (lit in reason) pending.add(lit)
        val numBoolVars = state.problem.numBoolVars
        while (pending.size > 0) {
            val lit = pending.last()
            pending.truncateTo(pending.size - 1)
            val v = Lit.variable(lit)
            if (v >= numBoolVars && substituteOffTrail(v, currentLevel)) continue
            if (v >= universe) {
                // An order literal materialised mid-analysis that [substituteOffTrail] could not resolve
                // through. It has no frontier slot, so keep the literal in the clause as a leaf (deduped
                // via [litsInLearned]): adding a literal only weakens the clause, while dropping it would
                // silently strengthen the nogood past what was derived.
                if (!litsInLearned.contains(lit)) {
                    addLearned(lit)
                    if (graph.levelOf(v) == currentLevel) currentLevelCount++
                }
                continue
            }
            if (seen[v]) continue // already in the frontier
            // A variable resolved earlier can recur when a reason cites a literal the trail recorded after it,
            // as an atom materialised by the citing reason is. Resolving it out folded its whole reason into
            // the resolvent, so meeting it again is one more resolution against that same reason, which adds
            // nothing: dropping it derives the same clause. Keeping it instead would put a second literal at
            // the conflict level and leave the clause non-asserting; re-resolving would ping-pong.
            if (resolved[v]) continue
            val lvl = graph.levelOf(v)
            if (lvl <= 0) continue
            seen[v] = true
            if (v < numBoolVars) {
                bumpBoolVars.add(v)
            } else {
                bumpIntVars.add(state.atoms.intVar[v - numBoolVars])
                offTrailFrontier.add(v) // frontier atom — candidate for the 1UIP atom-pivot scan
            }
            if (lvl == currentLevel) {
                currentLevelCount++
            } else {
                if (v < numBoolVars) {
                    val pinned = state.boolValues[v] ?: error("seen var $v not pinned")
                    addLearned(Lit.make(v, !pinned))
                } else {
                    val atomId = v - numBoolVars
                    val holds = state.atomCurrentTruth(atomId)
                        ?: error("ingest atom $atomId at lower level undetermined")
                    addLearned(Lit.make(v, !holds))
                }
            }
        }
    }

    // An order literal materialized after its bound crossed never went on the pin trail, so the driver would
    // reach it only after the whole trail, past the facts its reason cites, which would then recur as already
    // resolved and cost the clause its literals. Resolving it where it is cited puts its premises below the
    // citing literal. A decision move gives no reason, and literals below the conflict level stay leaves.
    private fun substituteOffTrail(v: Int, currentLevel: Int): Boolean {
        val atomId = v - state.problem.numBoolVars
        if (atomId >= state.atoms.intVar.size || state.atoms.lvl[atomId] >= 0) return false
        if (substituted.contains(v)) return true
        if (graph.levelOf(v) != currentLevel) return false
        val premises = state.atomAntecedentsDerived(atomId) ?: return false
        substituted.add(v)
        bumpIntVars.add(state.atoms.intVar[atomId])
        for (lit in premises) pending.add(lit)
        return true
    }

    override fun drainFrontier() {
        val numBoolVars = state.problem.numBoolVars
        for (v in 0 until universe) {
            if (!seen[v]) continue
            val lit: Int = if (v < numBoolVars) {
                val pinned = state.boolValues[v] ?: continue
                Lit.make(v, !pinned)
            } else {
                val atomId = v - numBoolVars
                val holds = state.atomCurrentTruth(atomId) ?: continue
                Lit.make(v, !holds)
            }
            if (!litsInLearned.contains(lit)) addLearned(lit)
        }
    }

    override fun finalizeResult(currentLevel: Int): ConflictAnalyzer.AnalysisResult {
        val minimized = minimizer.reduce(learned, currentLevel)
        val levels = distinctLevelsOf(minimized)
        // A proper 1UIP clause carries exactly one literal at the conflict level; that lone literal becomes the
        // unit-asserting literal after the backjump. A clause whose literals all sit below the conflict level
        // was already false lower down, where propagation missed it; it asserts in the same way from its own
        // highest level. A clause that rests on several literals at its highest level is not unit after any
        // backjump, so the engine must not try to assert it.
        var top = 0
        for (i in 0 until minimized.size) top = maxOf(top, graph.levelOf(Lit.variable(minimized[i])))
        var atTop = 0
        for (i in 0 until minimized.size) {
            if (graph.levelOf(Lit.variable(minimized[i])) == top) atTop++
        }
        return ConflictAnalyzer.AnalysisResult.Learned(
            minimized.toIntArray(),
            backjumpLevelOf(minimized, top),
            levels.size,
            levels,
            asserting = top > 0 && atTop == 1,
        )
    }

    private fun distinctLevelsOf(learned: IntArrayList): IntArray {
        if (learned.size == 0) return emptyIntArray()
        val seen = IntHashSet(learned.size)
        for (i in 0 until learned.size) seen.add(graph.levelOf(Lit.variable(learned[i])))
        val out = seen.toIntArray()
        out.sort()
        return out
    }

    // Popping to the highest level below the clause's top leaves it unit.
    private fun backjumpLevelOf(learned: IntArrayList, currentLevel: Int): Int {
        var best = 0
        for (i in 0 until learned.size) {
            val lvl = graph.levelOf(Lit.variable(learned[i]))
            if (lvl < currentLevel && lvl > best) best = lvl
        }
        return best
    }
}
