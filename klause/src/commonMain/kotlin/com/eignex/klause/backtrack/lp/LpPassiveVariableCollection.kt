package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef

internal class LpPassiveVariableCollection(
    private val observe: (String) -> Unit,
    private val charge: (Long) -> Unit,
    maxScans: Int = 32,
    private val maxVisitsPerScan: Int = 128,
) {
    init {
        require(maxScans in 0..32 && maxVisitsPerScan in 0..128)
    }

    private var remainingScans = maxScans
    private val choice = LpPassiveVariableChoice(true, observe, charge, LpPassiveVariableChoice.Kind.COLLECTION)

    fun clear() = choice.clear()

    fun select(
        baseline: LpPassiveVariableChoice.Candidate,
        history: LpDirectionalHistory,
        stamp: Int,
        point: (VarRef) -> Double,
        isOpen: (VarRef) -> Boolean,
        fresh: () -> Boolean,
    ): VarRef {
        clear()
        if (remainingScans == 0 || maxVisitsPerScan == 0 || !fresh()) return baseline.variable
        charge(4L)
        observe("PASSIVE_COLLECTION_VARIABLE_BASELINE_CHECK")
        if (!fresh()) return stale(baseline.variable)
        if (history.needsObservations(baseline.variable, baseline.point)) {
            observe("PASSIVE_COLLECTION_VARIABLE_BASELINE_READY")
            return baseline.variable
        }
        remainingScans--
        observe("PASSIVE_COLLECTION_VARIABLE_SCAN")
        var visits = 0
        for (variable in history.observedVariables) {
            if (visits == maxVisitsPerScan) break
            visits++
            charge(4L)
            observe("PASSIVE_COLLECTION_VARIABLE_VISIT")
            if (!fresh()) return stale(baseline.variable)
            if (variable == baseline.variable || !isOpen(variable)) continue
            val value = point(variable)
            if (!history.needsObservations(variable, value)) continue
            return choice.select(LpPassiveVariableChoice.Candidate(variable, value), baseline, stamp,
                fresh = fresh, reliable = { history.needsObservations(variable, value) })
        }
        observe("PASSIVE_COLLECTION_VARIABLE_EMPTY")
        return baseline.variable
    }

    private fun stale(baseline: VarRef): VarRef {
        observe("PASSIVE_COLLECTION_VARIABLE_SCAN_STALE")
        return baseline
    }

    fun branch(variable: VarRef, stamp: Int) = choice.branch(variable, stamp)

    fun commit(decision: SearchDecision, fresh: () -> Boolean) {
        if (!fresh()) clear()
        choice.commit(decision)
    }
}
