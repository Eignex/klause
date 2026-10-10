package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation

internal fun LpEngine.harvestRootRelaxation(token: Cancellation): CpToLpRelaxation? {
    if (params.lpPlan.pruneHulls) pruneIneffectiveHulls(token)
    val relaxer = lpRelaxer ?: return null
    val gomory = params.lpPlan.gomoryEnabled
    val mir = params.lpPlan.mirEnabled
    if (params.lpPlan.rootCutHarvest && (lpSeparators.isNotEmpty() || gomory || mir)) {
        cutPool.addAll(
            harvestRootCuts(
                relaxer,
                PropagationSession(problem, token),
                lpSeparators,
                gomory,
                mir,
                token,
            ),
        )
        sink.lp.observeCuts(lpGlobalCuts.size)
    }
    return relaxer
}
