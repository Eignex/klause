package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.cancelledWhen

internal class SimplexSolveControl(
    m: Int,
    numVars: Int,
    private val iterationLimit: Int,
    private val workLimit: Long,
    token: Cancellation,
) {
    private val defaultIterations = 50 * (m + numVars) + 200
    val work = LpWork()
    var floatAllowance: LpFloatAllowance? = null
    var retainFundingStop = false
    var strictProbeWorkLimit = 0L
    var stopToken = token
    private var chargedOps = 0L

    val cancellation: Cancellation = cancelledWhen({ stopToken.deadline() }) {
        chargeWork()
        stopToken()
    }

    val maxIterations: Int
        get() {
            val configured = floatAllowance?.iterations ?: iterationLimit
            return if (configured > 0) configured else defaultIterations
        }

    val effectiveWorkLimit: Long
        get() {
            val configured = floatAllowance?.work ?: workLimit
            return when {
                strictProbeWorkLimit == 0L -> configured
                configured <= 0L -> strictProbeWorkLimit
                else -> minOf(configured, strictProbeWorkLimit)
            }
        }

    fun chargeWork() {
        val ops = work.ops
        if (ops > chargedOps) stopToken.charge(ops - chargedOps)
        chargedOps = ops
    }

    fun resetWork() {
        chargeWork()
        work.reset(if (strictProbeWorkLimit > 0L) effectiveWorkLimit else 0L)
        chargedOps = 0L
    }

    fun workExhausted(): Boolean = effectiveWorkLimit > 0L && work.ops >= effectiveWorkLimit

    fun iterationsRemain(progress: SolveProgress): Boolean = retainFundingStop || progress.iterations < maxIterations
}

// Recovery may replace factors or headings, but it remains part of one logical solve. Keeping these
// counters in one carrier prevents a restart from buying a fresh termination or anti-cycling budget.
internal class SolveProgress(
    var dualIterations: Int = 0,
    var primalIterations: Int = 0,
    var primalDegenerate: Int = 0,
    var restarts: Int = 0,
    var dualNumericalRecoveryTried: Boolean = false,
    var primalBlandActive: Boolean = false,
) {
    val iterations: Long get() = dualIterations.toLong() + primalIterations
}
