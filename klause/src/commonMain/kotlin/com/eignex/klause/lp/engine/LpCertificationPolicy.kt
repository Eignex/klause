package com.eignex.klause.lp.engine

import kotlin.math.nextDown

/** Immutable proof-acceptance seam. Certifiers compute and report their own observations regardless. */
internal fun interface LpCertificationPolicy {
    fun accepts(certifier: LpCertifier, successful: Boolean): Boolean
}

internal object ProductionLpCertificationPolicy : LpCertificationPolicy {
    override fun accepts(certifier: LpCertifier, successful: Boolean): Boolean = successful
}

internal fun <T : Any> LpCertificationPolicy.acceptNullable(certifier: LpCertifier, candidate: T?): T? {
    val successful = candidate != null
    val accepted = accepts(certifier, successful)
    return if (successful && accepted) candidate else null
}

internal fun LpCertificationPolicy.acceptBoolean(certifier: LpCertifier, candidate: Boolean): Boolean {
    val accepted = accepts(certifier, candidate)
    return candidate && accepted
}

internal fun certifiedTightObjectiveLowerBound(
    model: LpModel,
    y: DoubleArray,
    observer: LpCertificationObserver?,
    policy: LpCertificationPolicy,
): Double? {
    return observeLpClaim(LpClaim.BOUND, observer) { observation ->
        val safe = policy.acceptNullable(LpCertifier.SAFE_OBJECTIVE, safeObjectiveLowerBound(model, y, observation))
        val exact = policy.acceptNullable(LpCertifier.INTEGER, model.exactObjectiveLowerBoundCeil(y, observation))
        return@observeLpClaim tighterAcceptedLowerBound(safe, exact)
    }
}

internal fun certifiedTightObjectiveLowerBound(
    model: LpModel,
    y: DoubleArray,
    certificate: IntegerCertificate?,
    observer: LpCertificationObserver?,
    policy: LpCertificationPolicy,
): Double? {
    return observeLpClaim(LpClaim.BOUND, observer) { observation ->
        val safe = policy.acceptNullable(LpCertifier.SAFE_OBJECTIVE, safeObjectiveLowerBound(model, y, observation))
        return@observeLpClaim tighterAcceptedLowerBound(
            safe,
            policy.acceptNullable(
                LpCertifier.INTEGER,
                certificate?.takeIf { it.belongsTo(
                    model,
                    observation,
                ) && model.hasIntegralObjective() }?.objectiveBoundCeil(0L),
            ),
        )
    }
}

internal fun certifiedTightObjectiveLowerBound(
    model: LpModel,
    y: DoubleArray,
    certificate: LpDeductionCertificate?,
    observer: LpCertificationObserver?,
    policy: LpCertificationPolicy,
): Double? {
    return observeLpClaim(LpClaim.BOUND, observer) { observation ->
        val safe = policy.acceptNullable(LpCertifier.SAFE_OBJECTIVE, safeObjectiveLowerBound(model, y, observation))
        val exact = policy.acceptNullable(
            LpCertifier.INTEGER,
            certificate?.takeIf { it.belongsTo(model, observation) }?.let {
                if (model.hasIntegralObjective()) {
                    it.objectiveBoundCeil(0L)?.let(::acceptedLowerBoundAsDouble)
                } else {
                    it.exactObjectiveBound().lowerBoundDouble()
                }
            },
        )
        return@observeLpClaim when {
            safe == null -> exact
            exact == null -> safe
            else -> maxOf(safe, exact)
        }
    }
}

internal fun LpModel.certifiedTightVariableBound(
    result: FloatLpResult,
    objectiveCol: Int,
    maximize: Boolean,
    clamped: Boolean?,
    observer: LpCertificationObserver?,
    policy: LpCertificationPolicy,
): Long? {
    return observeLpClaim(LpClaim.BOUND, observer) { observation ->
        val safe = policy.acceptNullable(
            LpCertifier.SAFE_OBJECTIVE,
            safeVariableBound(result, objectiveCol, maximize, clamped, observation),
        )
        val exact = policy.acceptNullable(
            LpCertifier.INTEGER,
            exactVariableBound(result, objectiveCol, maximize, clamped, observation),
        )
        return@observeLpClaim when {
            safe == null -> exact
            exact == null -> safe
            maximize -> minOf(safe, exact)
            else -> maxOf(safe, exact)
        }
    }
}

private fun tighterAcceptedLowerBound(safe: Double?, exactCeil: Long?): Double? {
    val exact = exactCeil?.let(::acceptedLowerBoundAsDouble)
    return when {
        safe == null -> exact
        exact == null -> safe
        else -> maxOf(safe, exact)
    }
}

private fun acceptedLowerBoundAsDouble(value: Long): Double {
    val widened = value.toDouble()
    return if (widened == Long.MAX_VALUE.toDouble() || widened.toLong() > value) widened.nextDown() else widened
}
