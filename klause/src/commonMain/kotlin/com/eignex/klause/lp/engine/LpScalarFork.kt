package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl

internal class LpScalarFork(create: () -> PersistentLpSolver) {
    private var pending: (() -> PersistentLpSolver)? = create

    fun create(): PersistentLpSolver {
        val construct = checkNotNull(pending)
        pending = null
        return construct()
    }
}

// Opting in separately preserves constructor overrides on delegated engine factories.
internal interface LpScalarForkFactory {
    fun newScalarFork(
        source: LpSolver,
        previous: LpModel,
        next: LpModel,
        warm: Basis,
        token: ExecutionControl,
        staging: LpComponentStaging,
        pricing: LpPricingOptions,
    ): LpScalarFork? = (source as? RevisedSimplex)?.scalarFork(previous, next, warm, token, staging, pricing)
}
