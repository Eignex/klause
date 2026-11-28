package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.VarRef
import com.eignex.util.arrays.emptyLongArray
import kotlin.random.Random

// Conflict-Ordering Search (Gay, Hartert, Lecoutre and Schaus, "Conflict Ordering Search for Scheduling
// Problems", CP 2015, https://doi.org/10.1007/978-3-319-23219-5_10): picks the unpinned variable with the
// most recent conflict stamp; never-stamped variables fall through to [base]. Stamps persist across
// restarts, since the conflict structure is a property of the constraint network.
internal class ConflictOrdering(private val base: VariableSelector) : VariableSelector {

    override fun fresh() = ConflictOrdering(base.fresh())

    private var counter: Long = 0
    private var boolStamp: LongArray = emptyLongArray()
    private var intStamp: LongArray = emptyLongArray()

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
            if (!session.needsBoolDecision(v)) continue
            val s = boolStamp[v]
            if (s > bestStamp) {
                bestStamp = s
                best = VarRef.Bool(v)
            }
        }
        for (v in 0 until problem.numIntVars) {
            if (!session.needsIntDecision(v)) continue
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
