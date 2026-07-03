package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import kotlin.random.Random

/**
 * Conflict-Ordering Search (Gay-Hartert-Lecoutre-Schaus 2015). Generalisation of
 * [LastConflict]: instead of pinning the *single* most recent conflict variable, COS
 * maintains a per-variable *timestamp* of its last conflict and picks the unpinned
 * variable with the most recent stamp. Variables never implicated in a conflict have
 * stamp 0 and fall through to the [base] heuristic.
 *
 * Why it's better than plain last-conflict: when the search backjumps past several
 * conflict layers, COS replays them in reverse-chronological order — the deepest unstable
 * subtree is re-explored first, then the next deepest, etc. Plain last-conflict only
 * remembers the very top conflict and forgets the rest. Empirically COS is Choco's
 * default search and routinely beats plain heuristics on structured CSPs.
 *
 *  - Stamps persist across [onRestart] (Choco's default behaviour). The conflict structure
 *    is a global property of the constraint network, not a per-run signal.
 *  - The richer [onConflict] hook (with `unsat`) stamps **every** variable in the
 *    reason set, not just the failed decision. So COS sees the full conflict-graph
 *    attribution (same as VSIDS / dom-wdeg) at no extra plumbing cost.
 *
 * Composes cleanly: `ConflictOrdering(Vsids())` gives COS-on-VSIDS — recent conflicts
 * lead, activity drives the long tail. `ConflictOrdering(DomWdeg())` is the
 * Lecoutre-recommended configuration.
 */
internal class ConflictOrdering(private val base: VariableSelector) : VariableSelector {

    private var counter: Long = 0
    private var boolStamp: LongArray = LongArray(0)
    private var intStamp: LongArray = LongArray(0)

    private fun ensureSized(numBool: Int, numInt: Int) {
        if (boolStamp.size < numBool) boolStamp = boolStamp.copyOf(numBool)
        if (intStamp.size < numInt) intStamp = intStamp.copyOf(numInt)
    }

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        ensureSized(problem.numBoolVars, problem.numIntVars)
        var best: VarRef? = null
        var bestStamp: Long = 0
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) != null) continue
            val s = boolStamp[v]
            if (s > bestStamp) {
                bestStamp = s
                best = VarRef.Bool(v)
            }
        }
        for (v in 0 until problem.numIntVars) {
            if (session.intDomain(v).size <= 1) continue
            val s = intStamp[v]
            if (s > bestStamp) {
                bestStamp = s
                best = VarRef.IntVar(v)
            }
        }
        return best ?: base.pick(session, rng)
    }

    override fun onConflict(varRef: VarRef) {
        counter++
        stamp(varRef)
        base.onConflict(varRef)
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        counter++
        for (b in unsat.conflictBools) {
            growBool(b)
            boolStamp[b] = counter
        }
        for (i in unsat.conflictInts) {
            growInt(i)
            intStamp[i] = counter
        }
        stamp(varRef)
        base.onConflict(varRef, unsat)
    }

    private fun stamp(varRef: VarRef) {
        when (varRef) {
            is VarRef.Bool -> {
                growBool(varRef.varId)
                boolStamp[varRef.varId] = counter
            }

            is VarRef.IntVar -> {
                growInt(varRef.varId)
                intStamp[varRef.varId] = counter
            }
        }
    }

    private fun growBool(id: Int) {
        if (id >= boolStamp.size) boolStamp = boolStamp.copyOf((id + 1).coerceAtLeast(8))
    }
    private fun growInt(id: Int) {
        if (id >= intStamp.size) intStamp = intStamp.copyOf((id + 1).coerceAtLeast(8))
    }

    override fun onCommit(varRef: VarRef) = base.onCommit(varRef)
    override fun onPropagation(implied: PropagationResult.Implied) = base.onPropagation(implied)
    override fun onRestart() = base.onRestart()
    override fun onSolution(snapshot: Sample) = base.onSolution(snapshot)
    override val tracksUnassign: Boolean get() = base.tracksUnassign
    override fun onUnassign(varRef: VarRef) = base.onUnassign(varRef)
}
