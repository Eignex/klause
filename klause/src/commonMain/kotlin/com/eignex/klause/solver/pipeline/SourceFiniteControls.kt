package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.util.ExecutionControl

internal sealed interface SourceFiniteControls {
    val engine: FiniteEngine
    val cancellation: ExecutionControl
    val nodeBudget: NodeBudget?
    val randomSeed: Long?

    class Backtrack(val params: BacktrackParams) : SourceFiniteControls {
        override val engine = FiniteEngine.BACKTRACK
        override val cancellation get() = params.cancellation
        override val nodeBudget get() = params.nodeBudget
        override val randomSeed get() = params.randomSeed
    }

    class LocalSearch(val params: LocalSearchParams) : SourceFiniteControls {
        override val engine = FiniteEngine.LOCAL_SEARCH
        override val cancellation get() = params.cancellation
        override val nodeBudget get() = params.nodeBudget
        override val randomSeed get() = params.randomSeed
    }
}
