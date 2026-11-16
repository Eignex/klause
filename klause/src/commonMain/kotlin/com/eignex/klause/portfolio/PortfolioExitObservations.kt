package com.eignex.klause.portfolio

import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason

internal class PortfolioExitObservations(size: Int) {
    private val outcomes = Array(size) { "not-run" }
    private val reasons = arrayOfNulls<TerminationReason>(size)
    private val cutoffBits = LongArray(size)
    private val versions = LongArray(size) { -1L }

    fun cutoff(arm: Int, cutoff: Double, version: Long) {
        cutoffBits[arm] = cutoff.toRawBits()
        versions[arm] = version
    }

    fun minimized(arm: Int, result: MinimizeResult?, failure: Throwable?) {
        outcomes[arm] = failed(failure) ?: when (result) {
            is MinimizeResult.Optimal -> "optimal"
            is MinimizeResult.BestFound -> "best-found"
            is MinimizeResult.Unknown -> "unknown"
            is MinimizeResult.Infeasible -> "infeasible"
            is MinimizeResult.Unbounded -> "unbounded"
            null -> "paused"
        }
        reasons[arm] = if (failure != null) null else when (result) {
            is MinimizeResult.BestFound -> result.reason
            is MinimizeResult.Unknown -> result.reason
            else -> null
        }
    }

    fun solved(arm: Int, result: SolveResult?, failure: Throwable?) {
        outcomes[arm] = failed(failure) ?: when (result) {
            is SolveResult.Sat -> "sat"
            is SolveResult.Unsat -> "unsat"
            is SolveResult.Unknown -> "unknown"
            null -> "paused"
        }
        reasons[arm] = if (failure == null) (result as? SolveResult.Unknown)?.reason else null
    }

    fun event(arm: Int, worker: String, cause: String): PortfolioEvent.WorkerExit {
        val version = versions[arm].takeIf { it >= 0L }
        return PortfolioEvent.WorkerExit(arm, worker, cause, outcomes[arm], reasons[arm],
            if (version == null) null else cutoffBits[arm], version)
    }

    private fun failed(failure: Throwable?): String? = when (failure) {
        null -> null
        is SearchInitializationCancelled -> "initialization-cancelled"
        else -> "failed"
    }
}
